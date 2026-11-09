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
package org.openhab.binding.dyson.internal.handler;

import static org.openhab.binding.dyson.internal.DysonBindingConstants.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.openhab.binding.dyson.internal.DysonStateDescriptionProvider;
import org.openhab.binding.dyson.internal.api.DysonApiException;
import org.openhab.binding.dyson.internal.api.dto.DysonIotCredentials;
import org.openhab.binding.dyson.internal.api.dto.DysonManifestDevice;
import org.openhab.binding.dyson.internal.api.dto.DysonPendingRelease;
import org.openhab.binding.dyson.internal.config.DysonDeviceConfiguration;
import org.openhab.binding.dyson.internal.device.DysonDeviceState;
import org.openhab.binding.dyson.internal.device.DysonDeviceState.MessageType;
import org.openhab.binding.dyson.internal.mqtt.DysonMqttConnection;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Base handler for Dyson Wi-Fi devices. Manages the MQTT connection (local, cloud and fallback between them), the
 * periodic state requests, firmware information and the removal of unsupported channels.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public abstract class DysonBaseDeviceHandler extends BaseThingHandler implements DysonMqttConnection.Listener {
    private static final long PREFERRED_RETRY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_RECONNECT_DELAY_SECONDS = 300;
    private static final int PRUNE_DELAY_SECONDS = 30;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Logger logger = LoggerFactory.getLogger(DysonBaseDeviceHandler.class);
    protected final DysonStateDescriptionProvider stateDescriptionProvider;
    protected final Gson gson = new Gson();
    protected DysonDeviceState state = new DysonDeviceState();
    protected DysonDeviceConfiguration config = new DysonDeviceConfiguration();
    protected Set<String> capabilities = Set.of();
    protected String mqttPrefix = "";

    private final Object connectionLock = new Object();
    private @Nullable String localPassword;
    private @Nullable DysonMqttConnection connection;
    private @Nullable ScheduledFuture<?> heartbeatJob;
    private @Nullable ScheduledFuture<?> reconnectJob;
    private @Nullable ScheduledFuture<?> pruneJob;
    private volatile boolean usingFallback;
    private volatile long lastPreferredAttempt;
    private int reconnectDelaySeconds = 5;
    private boolean pruned;

    protected DysonBaseDeviceHandler(Thing thing, DysonStateDescriptionProvider stateDescriptionProvider) {
        super(thing);
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    public String getSerialNumber() {
        return config.serialNumber;
    }

    /** Robot vacuums only accept MQTT 3.1 on their local broker. */
    protected abstract boolean isRobot();

    /** Updates all device specific channels after a message of the given type. */
    protected abstract void updateChannels(MessageType type);

    /** Handles a command for a device specific channel. */
    protected abstract void handleDeviceCommand(String channelId, Command command) throws MqttException;

    /** Returns the ids of channels for features that the device does not report. */
    protected abstract Set<String> getUnsupportedChannels();

    /** Called once the capabilities are known, to set up dynamic state descriptions. */
    protected void onCapabilitiesKnown() {
    }

    /** Sends the periodic requests to the device. */
    protected void sendHeartbeat() throws MqttException {
        sendRequest("REQUEST-CURRENT-STATE");
        sendRequest("REQUEST-CURRENT-FAULTS");
    }

    @Override
    public void initialize() {
        config = getConfigAs(DysonDeviceConfiguration.class);
        if (config.serialNumber.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.conf-error-serial");
            return;
        }
        state = new DysonDeviceState();
        pruned = false;
        usingFallback = false;
        reconnectDelaySeconds = 5;
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::setup);
    }

    @Override
    public void dispose() {
        cancelJobs();
        disconnect();
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE && getThing().getStatus() != ThingStatus.ONLINE) {
            dispose();
            initialize();
        }
    }

    protected @Nullable DysonAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof DysonAccountHandler handler ? handler : null;
    }

    private void setup() {
        DysonAccountHandler account = getAccountHandler();
        DysonManifestDevice device = account != null ? account.getDevice(config.serialNumber) : null;
        DysonManifestDevice.Mqtt mqtt = device != null ? device.getMqtt() : null;

        String prefix = config.mqttPrefix;
        if (prefix.isBlank() && mqtt != null && mqtt.mqttRootTopicLevel != null) {
            prefix = mqtt.mqttRootTopicLevel;
        }
        if (prefix == null || prefix.isBlank()) {
            String stored = getThing().getProperties().get(PROPERTY_MQTT_PREFIX);
            prefix = stored != null ? stored : "";
        }
        mqttPrefix = prefix;

        String password = config.localPassword;
        if (password.isBlank() && account != null) {
            password = account.getLocalPassword(config.serialNumber);
        }
        localPassword = password == null || password.isBlank() ? null : password;

        Set<String> caps = new LinkedHashSet<>();
        if (!config.capabilities.isBlank()) {
            Arrays.stream(config.capabilities.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(caps::add);
        } else if (device != null) {
            caps.addAll(device.getCapabilities());
        } else {
            String stored = getThing().getProperties().get(PROPERTY_CAPABILITIES);
            if (stored != null && !stored.isBlank()) {
                Arrays.stream(stored.split(",")).map(String::trim).forEach(caps::add);
            }
        }
        capabilities = Set.copyOf(caps);

        if (device != null) {
            updateDeviceProperties(device);
        }

        if (mqttPrefix.isBlank()) {
            if (account != null && account.getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.conf-error-mqtt-prefix");
            }
            return;
        }
        if (getConnectionOrder().isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-connection");
            return;
        }

        onCapabilitiesKnown();
        if (device != null) {
            onCloudInfoUpdated(device);
        }
        connect();

        int interval = Math.max(10, config.refreshInterval);
        heartbeatJob = scheduler.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.SECONDS);
    }

    private void updateDeviceProperties(DysonManifestDevice device) {
        Map<String, String> properties = editProperties();
        putProperty(properties, Thing.PROPERTY_SERIAL_NUMBER, device.serialNumber);
        putProperty(properties, Thing.PROPERTY_MODEL_ID, device.model);
        putProperty(properties, PROPERTY_PRODUCT_TYPE, device.type);
        putProperty(properties, PROPERTY_VARIANT, device.variant);
        putProperty(properties, PROPERTY_CATEGORY, device.category);
        DysonManifestDevice.Firmware firmware = device.getFirmware();
        if (firmware != null) {
            putProperty(properties, Thing.PROPERTY_FIRMWARE_VERSION, firmware.version);
        }
        putProperty(properties, PROPERTY_MQTT_PREFIX, mqttPrefix);
        putProperty(properties, PROPERTY_CAPABILITIES, String.join(",", capabilities));
        updateProperties(properties);
    }

    private static void putProperty(Map<String, String> properties, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }

    /**
     * Called by the account bridge whenever the cloud device list has been refreshed.
     */
    public void onCloudInfoUpdated(DysonManifestDevice device) {
        DysonManifestDevice.Firmware firmware = device.getFirmware();
        if (firmware != null) {
            String version = firmware.version;
            updateChannel(CHANNEL_FIRMWARE_VERSION, version != null ? new StringType(version) : UnDefType.UNDEF);
            updateChannel(CHANNEL_FIRMWARE_AUTO_UPDATE, OnOffType.from(firmware.autoUpdateEnabled));
            if (version != null) {
                updateProperty(Thing.PROPERTY_FIRMWARE_VERSION, version);
            }
        }
        DysonAccountHandler account = getAccountHandler();
        if (account != null && getThing().getChannel(CHANNEL_FIRMWARE_LATEST_VERSION) != null) {
            scheduler.execute(() -> {
                DysonPendingRelease release = account.getPendingRelease(config.serialNumber);
                String latest = release != null ? release.version : null;
                String current = firmware != null ? firmware.version : null;
                boolean available = (firmware != null && firmware.newVersionAvailable)
                        || (latest != null && !latest.isBlank() && !latest.equals(current));
                updateChannel(CHANNEL_FIRMWARE_LATEST_VERSION,
                        latest != null && !latest.isBlank() ? new StringType(latest)
                                : current != null ? new StringType(current) : UnDefType.UNDEF);
                updateChannel(CHANNEL_FIRMWARE_UPDATE_AVAILABLE, OnOffType.from(available));
            });
        }
    }

    // ---- Connection management ----

    private List<DysonMqttConnection.Type> getConnectionOrder() {
        boolean localPossible = localPassword != null;
        boolean cloudPossible = getAccountHandler() != null;
        List<DysonMqttConnection.Type> order = new ArrayList<>();
        switch (config.connectionType) {
            case CONNECTION_LOCAL:
                order.add(DysonMqttConnection.Type.LOCAL);
                break;
            case CONNECTION_CLOUD:
                order.add(DysonMqttConnection.Type.CLOUD);
                break;
            case CONNECTION_CLOUD_LOCAL_FALLBACK:
                order.add(DysonMqttConnection.Type.CLOUD);
                order.add(DysonMqttConnection.Type.LOCAL);
                break;
            default:
                order.add(DysonMqttConnection.Type.LOCAL);
                order.add(DysonMqttConnection.Type.CLOUD);
                break;
        }
        order.removeIf(t -> (t == DysonMqttConnection.Type.LOCAL && !localPossible)
                || (t == DysonMqttConnection.Type.CLOUD && !cloudPossible));
        return order;
    }

    private String getLocalHost() {
        return config.hostname.isBlank() ? config.serialNumber + ".local" : config.hostname.trim();
    }

    /**
     * (Re)connects, trying the connection types in the configured order of preference.
     */
    protected void connect() {
        synchronized (connectionLock) {
            disconnect();
            List<DysonMqttConnection.Type> order = getConnectionOrder();
            lastPreferredAttempt = System.currentTimeMillis();
            String lastError = "";
            for (DysonMqttConnection.Type type : order) {
                try {
                    DysonMqttConnection newConnection = createConnection(type);
                    newConnection.connect();
                    connection = newConnection;
                    usingFallback = type != order.get(0);
                    reconnectDelaySeconds = 5;
                    logger.debug("Connected to Dyson device {} via {}", config.serialNumber, type);
                    onConnected(newConnection);
                    return;
                } catch (MqttException | DysonApiException | IllegalArgumentException e) {
                    lastError = type + ": " + e.getMessage();
                    logger.debug("Connecting to Dyson device {} via {} failed: {}", config.serialNumber, type,
                            e.getMessage());
                }
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, lastError);
            updateChannel(CHANNEL_CONNECTION_STATUS, new StringType("DISCONNECTED"));
            scheduleReconnect();
        }
    }

    private DysonMqttConnection createConnection(DysonMqttConnection.Type type) throws DysonApiException {
        if (type == DysonMqttConnection.Type.LOCAL) {
            String password = localPassword;
            if (password == null) {
                throw new DysonApiException("No local password");
            }
            return DysonMqttConnection.local(getLocalHost(), config.serialNumber, password, mqttPrefix, isRobot(),
                    this);
        }
        DysonAccountHandler account = getAccountHandler();
        if (account == null) {
            throw new DysonApiException("No Dyson account bridge");
        }
        DysonIotCredentials credentials = account.getIotCredentials(config.serialNumber);
        return DysonMqttConnection.cloud(credentials, config.serialNumber, mqttPrefix, this);
    }

    private void disconnect() {
        DysonMqttConnection oldConnection = connection;
        connection = null;
        if (oldConnection != null) {
            oldConnection.disconnect();
        }
    }

    private void scheduleReconnect() {
        ScheduledFuture<?> job = reconnectJob;
        if (job != null && !job.isDone()) {
            return;
        }
        int delay = reconnectDelaySeconds;
        reconnectDelaySeconds = Math.min(MAX_RECONNECT_DELAY_SECONDS, reconnectDelaySeconds * 2);
        logger.debug("Reconnecting to Dyson device {} in {} s", config.serialNumber, delay);
        reconnectJob = scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void onConnected(DysonMqttConnection newConnection) {
        updateStatus(ThingStatus.ONLINE);
        updateChannel(CHANNEL_CONNECTION_STATUS, new StringType(newConnection.getType().name()));
        updateChannel(CHANNEL_HOST, new StringType(newConnection.getHost()));
        try {
            sendHeartbeat();
        } catch (MqttException e) {
            logger.debug("Initial state request to {} failed: {}", config.serialNumber, e.getMessage());
        }
        if (config.removeUnsupportedChannels && !pruned) {
            ScheduledFuture<?> job = pruneJob;
            if (job == null || job.isDone()) {
                pruneJob = scheduler.schedule(this::pruneChannels, PRUNE_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private void heartbeat() {
        DysonMqttConnection current = connection;
        if (current == null || !current.isConnected()) {
            ScheduledFuture<?> job = reconnectJob;
            if (job == null || job.isDone()) {
                connect();
            }
            return;
        }
        if (usingFallback && System.currentTimeMillis() - lastPreferredAttempt > PREFERRED_RETRY_MS) {
            logger.debug("Trying to switch Dyson device {} back to its preferred connection", config.serialNumber);
            connect();
            return;
        }
        try {
            sendHeartbeat();
        } catch (MqttException e) {
            logger.debug("Heartbeat to {} failed: {}", config.serialNumber, e.getMessage());
        }
    }

    @Override
    public void onConnectionLost(DysonMqttConnection lostConnection, @Nullable Throwable cause) {
        if (!lostConnection.equals(connection)) {
            return;
        }
        connection = null;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                cause != null ? cause.getMessage() : null);
        updateChannel(CHANNEL_CONNECTION_STATUS, new StringType("DISCONNECTED"));
        scheduleReconnect();
    }

    @Override
    public void onMessage(DysonMqttConnection source, String topic, String payload) {
        if (!source.equals(connection)) {
            return;
        }
        logger.trace("Dyson {} message on {}: {}", config.serialNumber, topic, payload);
        JsonObject message;
        try {
            JsonElement element = JsonParser.parseString(payload);
            if (!element.isJsonObject()) {
                return;
            }
            message = element.getAsJsonObject();
        } catch (JsonParseException e) {
            logger.debug("Ignoring invalid message from {}: {}", config.serialNumber, payload);
            return;
        }
        MessageType type = state.process(message);
        if (type == MessageType.OTHER) {
            return;
        }
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
        if (type == MessageType.CURRENT_STATE) {
            String rssi = state.getTopLevelString("rssi");
            if (rssi != null) {
                updateChannel(CHANNEL_WIFI_SIGNAL, DysonChannelUtil.quantity(rssi, 1, Units.DECIBEL_MILLIWATTS));
            }
        }
        updateChannels(type);
    }

    // ---- Commands ----

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getIdWithoutGroup();
        if (command instanceof RefreshType) {
            if (CHANNEL_CONNECTION_STATUS.equals(channelId)) {
                DysonMqttConnection current = connection;
                updateChannel(channelId, new StringType(
                        current != null && current.isConnected() ? current.getType().name() : "DISCONNECTED"));
            } else {
                updateChannels(MessageType.OTHER);
            }
            return;
        }
        try {
            switch (channelId) {
                case CHANNEL_RECONNECT:
                    if (command == OnOffType.ON) {
                        scheduler.execute(this::connect);
                        updateChannel(channelId, OnOffType.OFF);
                    }
                    break;
                case CHANNEL_FIRMWARE_UPDATE:
                    if (command == OnOffType.ON) {
                        triggerFirmwareUpdate();
                        updateChannel(channelId, OnOffType.OFF);
                    }
                    break;
                default:
                    handleDeviceCommand(channelId, command);
                    break;
            }
        } catch (MqttException e) {
            logger.warn("Failed to send command {} to Dyson device {}: {}", command, config.serialNumber,
                    e.getMessage());
        }
    }

    private void triggerFirmwareUpdate() {
        DysonAccountHandler account = getAccountHandler();
        if (account == null) {
            logger.warn("A firmware update requires a Dyson account bridge");
            return;
        }
        scheduler.execute(() -> {
            try {
                account.triggerFirmwareUpdate(config.serialNumber);
                logger.info("Firmware update triggered for Dyson device {}", config.serialNumber);
            } catch (DysonApiException e) {
                logger.warn("Failed to trigger the firmware update of {}: {}", config.serialNumber, e.getMessage());
            }
        });
    }

    protected String timestamp() {
        return TIMESTAMP.format(Instant.now());
    }

    protected void sendRequest(String msg) throws MqttException {
        JsonObject request = new JsonObject();
        request.addProperty("msg", msg);
        request.addProperty("time", timestamp());
        if (!isRobot()) {
            request.addProperty("mode-reason", "RAPP");
        }
        publish(request);
    }

    /**
     * Sends a STATE-SET command and reflects the new values right away.
     */
    protected void sendStateSet(Map<String, String> data) throws MqttException {
        JsonObject command = new JsonObject();
        command.addProperty("msg", "STATE-SET");
        command.addProperty("time", timestamp());
        command.addProperty("mode-reason", "RAPP");
        command.add("data", gson.toJsonTree(data));
        publish(command);
        state.applyCommand(data);
        updateChannels(MessageType.STATE_CHANGE);
    }

    protected void sendStateSet(String key, String value) throws MqttException {
        Map<String, String> data = new HashMap<>();
        data.put(key, value);
        sendStateSet(data);
    }

    protected void publish(JsonObject message) throws MqttException {
        DysonMqttConnection current = connection;
        if (current == null) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        current.publish(message.toString());
    }

    // ---- Channel helpers ----

    protected void updateChannel(String channelId, State newState) {
        if (getThing().getChannel(channelId) != null) {
            updateState(channelId, newState);
        }
    }

    private void pruneChannels() {
        if (pruned || !state.isCurrentStateReceived()) {
            return;
        }
        Set<String> unsupported = getUnsupportedChannels();
        List<Channel> toRemove = getThing().getChannels().stream()
                .filter(c -> unsupported.contains(c.getUID().getIdWithoutGroup())).toList();
        pruned = true;
        if (!toRemove.isEmpty()) {
            logger.debug("Removing unsupported channels of Dyson device {}: {}", config.serialNumber,
                    toRemove.stream().map(c -> c.getUID().getId()).collect(Collectors.joining(", ")));
            updateThing(editThing().withoutChannels(toRemove).build());
        }
    }

    private void cancelJobs() {
        for (ScheduledFuture<?> job : new ScheduledFuture<?>[] { heartbeatJob, reconnectJob, pruneJob }) {
            if (job != null) {
                job.cancel(true);
            }
        }
        heartbeatJob = null;
        reconnectJob = null;
        pruneJob = null;
    }
}
