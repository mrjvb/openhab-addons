/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dyson.internal.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.openhab.binding.dyson.internal.api.dto.DysonIotCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT connection to a single Dyson device, either directly to the broker embedded in the device (local) or through
 * the Dyson AWS IoT proxy over secure WebSockets (cloud).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonMqttConnection implements MqttCallbackExtended {

    public enum Type {
        LOCAL,
        CLOUD
    }

    /**
     * Receives messages and connection events.
     */
    public interface Listener {
        void onMessage(DysonMqttConnection connection, String topic, String payload);

        void onConnectionLost(DysonMqttConnection connection, @Nullable Throwable cause);
    }

    private static final int LOCAL_PORT = 1883;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int KEEP_ALIVE_SECONDS = 60;
    private static final long OPERATION_TIMEOUT_MS = 10000;

    private final Logger logger = LoggerFactory.getLogger(DysonMqttConnection.class);
    private final Type type;
    private final String serverUri;
    private final String clientId;
    private final MqttConnectOptions options;
    private final String commandTopic;
    private final List<String> subscriptions;
    private final Listener listener;
    private final String host;
    private @Nullable MqttAsyncClient client;
    private volatile boolean closing;

    private DysonMqttConnection(Type type, String host, String serverUri, String clientId, MqttConnectOptions options,
            String topicBase, List<String> subscriptions, Listener listener) {
        this.type = type;
        this.host = host;
        this.serverUri = serverUri;
        this.clientId = clientId;
        this.options = options;
        this.commandTopic = topicBase + "/command";
        this.subscriptions = subscriptions;
        this.listener = listener;
    }

    /**
     * Creates a connection to the MQTT broker running on the device itself.
     *
     * @param useMqtt31 robot vacuums only accept MQTT protocol version 3.1
     */
    public static DysonMqttConnection local(String host, String serialNumber, String password, String mqttPrefix,
            boolean useMqtt31, Listener listener) {
        MqttConnectOptions options = baseOptions();
        options.setUserName(serialNumber);
        options.setPassword(password.toCharArray());
        options.setMqttVersion(useMqtt31 ? MqttConnectOptions.MQTT_VERSION_3_1 : MqttConnectOptions.MQTT_VERSION_3_1_1);
        String topicBase = mqttPrefix + "/" + serialNumber;
        // A fresh random client id avoids the device rejecting a reconnect while a stale session is still active
        String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        return new DysonMqttConnection(Type.LOCAL, host, "tcp://" + host + ":" + LOCAL_PORT, clientId, options,
                topicBase, List.of(topicBase + "/status/#"), listener);
    }

    /**
     * Creates a connection through the Dyson AWS IoT proxy, authenticated with a custom authorizer.
     */
    public static DysonMqttConnection cloud(DysonIotCredentials iot, String serialNumber, String mqttPrefix,
            Listener listener) throws IllegalArgumentException {
        DysonIotCredentials.Credentials credentials = iot.credentials;
        String endpoint = iot.endpoint;
        String clientId = credentials != null ? credentials.clientId : null;
        String authorizer = credentials != null ? credentials.customAuthorizerName : null;
        String tokenValue = credentials != null ? credentials.tokenValue : null;
        String signature = credentials != null ? credentials.tokenSignature : null;
        if (credentials == null || endpoint == null || clientId == null || authorizer == null || tokenValue == null
                || signature == null) {
            throw new IllegalArgumentException("Incomplete AWS IoT credentials received from the Dyson cloud");
        }
        String tokenKey = credentials.tokenKey;
        Properties headers = new Properties();
        headers.put(tokenKey == null || tokenKey.isBlank() ? "token" : tokenKey, tokenValue);
        headers.put("X-Amz-CustomAuthorizer-Name", authorizer);
        headers.put("X-Amz-CustomAuthorizer-Signature", signature);

        MqttConnectOptions options = baseOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCustomWebSocketHeaders(headers);
        String topicBase = mqttPrefix + "/" + serialNumber;
        List<String> topics = List.of(topicBase + "/status/current", topicBase + "/status/faults",
                topicBase + "/status/connection", topicBase + "/status/software", topicBase + "/status/summary");
        return new DysonMqttConnection(Type.CLOUD, endpoint, "wss://" + endpoint + ":443/mqtt", clientId, options,
                topicBase, topics, listener);
    }

    private static MqttConnectOptions baseOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
        options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
        return options;
    }

    public Type getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public boolean isConnected() {
        MqttAsyncClient localClient = client;
        return localClient != null && localClient.isConnected();
    }

    /**
     * Connects and subscribes to the status topics. Blocks until done.
     */
    public synchronized void connect() throws MqttException {
        closing = false;
        MqttAsyncClient newClient = new MqttAsyncClient(serverUri, clientId, new MemoryPersistence());
        newClient.setCallback(this);
        client = newClient;
        logger.debug("Connecting to Dyson MQTT broker {} ({})", serverUri, type);
        try {
            newClient.connect(options).waitForCompletion(OPERATION_TIMEOUT_MS + CONNECT_TIMEOUT_SECONDS * 1000L);
            for (String topic : subscriptions) {
                newClient.subscribe(topic, 0).waitForCompletion(OPERATION_TIMEOUT_MS);
            }
        } catch (MqttException e) {
            closeQuietly(newClient);
            client = null;
            throw e;
        }
        logger.debug("Connected to Dyson MQTT broker {} and subscribed to {}", serverUri, subscriptions);
    }

    public void publish(String payload) throws MqttException {
        MqttAsyncClient localClient = client;
        if (localClient == null || !localClient.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        logger.trace("Publishing to {}: {}", commandTopic, payload);
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(type == Type.CLOUD ? 1 : 0);
        localClient.publish(commandTopic, message);
    }

    public synchronized void disconnect() {
        closing = true;
        MqttAsyncClient localClient = client;
        client = null;
        if (localClient != null) {
            closeQuietly(localClient);
        }
    }

    private void closeQuietly(MqttAsyncClient mqttClient) {
        try {
            if (mqttClient.isConnected()) {
                mqttClient.disconnect(1000).waitForCompletion(2000);
            }
        } catch (MqttException e) {
            logger.debug("Error disconnecting from {}: {}", serverUri, e.getMessage());
            try {
                mqttClient.disconnectForcibly(500, 500);
            } catch (MqttException e2) {
                logger.trace("Forced disconnect failed: {}", e2.getMessage());
            }
        }
        try {
            mqttClient.close();
        } catch (MqttException e) {
            logger.trace("Error closing MQTT client: {}", e.getMessage());
        }
    }

    @Override
    public void connectComplete(boolean reconnect, @Nullable String serverURI) {
        logger.trace("MQTT connect complete for {}", serverURI);
    }

    @Override
    public void connectionLost(@Nullable Throwable cause) {
        if (!closing) {
            logger.debug("Lost MQTT connection to {}: {}", serverUri, cause != null ? cause.getMessage() : "");
            listener.onConnectionLost(this, cause);
        }
    }

    @Override
    public void messageArrived(@Nullable String topic, @Nullable MqttMessage message) {
        if (topic == null || message == null) {
            return;
        }
        try {
            listener.onMessage(this, topic, new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Exceptions must never reach Paho, as they would close the connection
            logger.warn("Error processing Dyson message on {}: {}", topic, e.getMessage(), e);
        }
    }

    @Override
    public void deliveryComplete(@Nullable IMqttDeliveryToken token) {
    }
}
