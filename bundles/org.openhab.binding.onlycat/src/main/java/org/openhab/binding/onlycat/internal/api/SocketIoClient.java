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
package org.openhab.binding.onlycat.internal.api;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A minimal Socket.IO client on top of the Jetty WebSocket client provided by openHAB, so that the binding needs no
 * additional third party libraries. It speaks Engine.IO 4 over the WebSocket transport on the default namespace.
 *
 * Callbacks run on Jetty threads and must not block; {@link Listener} implementations hand over to their own executor.
 * One instance serves one connection attempt: create a new one to reconnect.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@WebSocket
@NonNullByDefault
public class SocketIoClient {

    /**
     * Receives what happens on the connection.
     */
    public interface Listener {
        void onConnected();

        void onDisconnected(String reason);

        void onEvent(String name, JsonElement payload);
    }

    private static final long CONNECT_TIMEOUT_SECONDS = 30;
    private static final long DEFAULT_PING_WINDOW_MILLIS = 45000;

    private final Logger logger = LoggerFactory.getLogger(SocketIoClient.class);
    private final Gson gson = new Gson();
    private final WebSocketClient client;
    private final URI uri;
    private final Map<String, String> headers;
    private final String authJson;
    private final Listener listener;
    private final ScheduledExecutorService watchdogExecutor;
    private final Map<Integer, CompletableFuture<JsonElement>> pendingCalls = new ConcurrentHashMap<>();
    private final AtomicInteger nextAckId = new AtomicInteger();

    private volatile @Nullable Session session;
    private volatile boolean connected;
    private volatile boolean terminated;
    private volatile long pingWindowMillis = DEFAULT_PING_WINDOW_MILLIS;
    private volatile @Nullable ScheduledFuture<?> watchdog;

    public SocketIoClient(WebSocketClient client, URI uri, Map<String, String> headers, JsonObject auth,
            Listener listener, ScheduledExecutorService watchdogExecutor) {
        this.client = client;
        this.uri = uri;
        this.headers = headers;
        this.authJson = gson.toJson(auth);
        this.listener = listener;
        this.watchdogExecutor = watchdogExecutor;
    }

    /**
     * Starts connecting. The result is reported to the {@link Listener}.
     *
     * @throws IOException if the connection attempt cannot be started
     */
    public void connect() throws IOException {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        headers.forEach(request::setHeader);
        client.connect(this, uri, request);
        resetWatchdog(TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS));
    }

    /**
     * Emits an event and returns a future for the acknowledgement of the server.
     *
     * @param event the event name
     * @param data the single argument of the event
     * @return a future for the first argument of the acknowledgement, {@link JsonNull} if there is none
     */
    public CompletableFuture<JsonElement> call(String event, JsonObject data) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        Session currentSession = session;
        if (currentSession == null || !connected) {
            future.completeExceptionally(new OnlyCatApiException("Not connected"));
            return future;
        }
        int ackId = nextAckId.getAndIncrement();
        JsonArray arguments = new JsonArray();
        arguments.add(event);
        arguments.add(data);
        pendingCalls.put(ackId, future);
        future.whenComplete((result, error) -> pendingCalls.remove(ackId));
        send(currentSession, SocketIoFrame.event(ackId, gson.toJson(arguments)));
        return future;
    }

    /**
     * Closes the connection. The listener is not notified any more.
     */
    public void close() {
        terminated = true;
        connected = false;
        cancelWatchdog();
        failPendingCalls("Connection closed");
        Session currentSession = session;
        session = null;
        if (currentSession != null) {
            currentSession.close();
        }
    }

    @OnWebSocketConnect
    public void onWebSocketConnect(Session newSession) {
        session = newSession;
        logger.debug("WebSocket to {} is open", uri.getHost());
    }

    @OnWebSocketMessage
    public void onWebSocketMessage(String text) {
        SocketIoFrame frame = SocketIoFrame.parse(text);
        Session currentSession = session;
        if (currentSession == null) {
            return;
        }
        switch (frame.kind()) {
            case ENGINE_OPEN -> onEngineOpen(currentSession, frame.payload());
            case ENGINE_PING -> {
                resetWatchdog(pingWindowMillis);
                send(currentSession, SocketIoFrame.pong());
            }
            case CONNECT -> {
                connected = true;
                resetWatchdog(pingWindowMillis);
                listener.onConnected();
            }
            case EVENT -> onEventPacket(currentSession, frame);
            case ACK -> onAckPacket(frame);
            case CONNECT_ERROR -> disconnect("Connection refused: " + frame.payload());
            case DISCONNECT -> disconnect("Disconnected by the server");
            case ENGINE_CLOSE -> disconnect("Closed by the server");
            case ENGINE_PONG, ENGINE_NOOP -> {
            }
            case UNSUPPORTED -> logger.debug("Ignoring unsupported packet");
        }
    }

    @OnWebSocketClose
    public void onWebSocketClose(int statusCode, @Nullable String reason) {
        disconnect("WebSocket closed: " + statusCode + " " + reason);
    }

    @OnWebSocketError
    public void onWebSocketError(@Nullable Session errorSession, @Nullable Throwable cause) {
        disconnect("WebSocket error: " + (cause == null ? "unknown" : cause.getMessage()));
    }

    private void onEngineOpen(Session currentSession, @Nullable String payload) {
        try {
            if (payload != null && JsonParser.parseString(payload) instanceof JsonObject open) {
                JsonElement interval = open.get("pingInterval");
                JsonElement timeout = open.get("pingTimeout");
                if (interval != null && timeout != null) {
                    pingWindowMillis = interval.getAsLong() + timeout.getAsLong();
                }
            }
        } catch (JsonParseException | NumberFormatException | IllegalStateException e) {
            logger.debug("Using the default ping window, cannot read the open packet: {}", e.getMessage());
        }
        send(currentSession, SocketIoFrame.connect(authJson));
    }

    private void onEventPacket(Session currentSession, SocketIoFrame frame) {
        Integer ackId = frame.ackId();
        if (ackId != null) {
            send(currentSession, SocketIoFrame.ack(ackId));
        }
        JsonArray arguments = parseArguments(frame.payload());
        if (arguments == null || arguments.isEmpty() || !arguments.get(0).isJsonPrimitive()) {
            logger.debug("Ignoring malformed event packet");
            return;
        }
        listener.onEvent(arguments.get(0).getAsString(), arguments.size() > 1 ? arguments.get(1) : JsonNull.INSTANCE);
    }

    private void onAckPacket(SocketIoFrame frame) {
        Integer ackId = frame.ackId();
        CompletableFuture<JsonElement> future = ackId == null ? null : pendingCalls.get(ackId);
        if (future == null) {
            logger.debug("Ignoring acknowledgement {} without a pending call", ackId);
            return;
        }
        JsonArray arguments = parseArguments(frame.payload());
        future.complete(arguments == null || arguments.isEmpty() ? JsonNull.INSTANCE : arguments.get(0));
    }

    private @Nullable JsonArray parseArguments(@Nullable String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return JsonParser.parseString(payload) instanceof JsonArray array ? array : null;
        } catch (JsonParseException e) {
            logger.debug("Ignoring malformed payload: {}", e.getMessage());
            return null;
        }
    }

    private void send(Session currentSession, String frame) {
        try {
            currentSession.getRemote().sendString(frame, null);
        } catch (RuntimeException e) {
            disconnect("Cannot send: " + e.getMessage());
        }
    }

    private synchronized void disconnect(String reason) {
        if (terminated) {
            return;
        }
        terminated = true;
        connected = false;
        cancelWatchdog();
        failPendingCalls(reason);
        Session currentSession = session;
        session = null;
        if (currentSession != null) {
            currentSession.close();
        }
        listener.onDisconnected(reason);
    }

    private void failPendingCalls(String reason) {
        OnlyCatApiException failure = new OnlyCatApiException(reason);
        pendingCalls.values().forEach(future -> future.completeExceptionally(failure));
    }

    private void resetWatchdog(long millis) {
        cancelWatchdog();
        if (!terminated) {
            watchdog = watchdogExecutor.schedule(
                    () -> disconnect("Nothing received for " + Duration.ofMillis(millis).toSeconds() + "s"), millis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void cancelWatchdog() {
        ScheduledFuture<?> current = watchdog;
        if (current != null) {
            current.cancel(false);
            watchdog = null;
        }
    }
}
