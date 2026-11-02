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
package org.openhab.binding.onlycat.internal;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.client.SocketOptionBuilder;

/** Socket.IO client for the OnlyCat cloud gateway. */
@NonNullByDefault
public class OnlyCatApiClient {

    private static final String GATEWAY_URL = "https://gateway.onlycat.com";
    private static final int ACK_TIMEOUT_SECONDS = 30;

    private final Runnable connected;
    private final Consumer<String> connectionError;
    private final Runnable disconnected;
    private final Consumer<JSONObject> deviceUpdate;
    private @Nullable Socket socket;

    public OnlyCatApiClient(Runnable connected, Consumer<String> connectionError, Runnable disconnected,
            Consumer<JSONObject> deviceUpdate) {
        this.connected = connected;
        this.connectionError = connectionError;
        this.disconnected = disconnected;
        this.deviceUpdate = deviceUpdate;
    }

    public void connect(String apiKey) throws URISyntaxException {
        IO.Options options = SocketOptionBuilder.builder().setForceNew(true).setTransports(new String[] { "websocket" })
                .setAuth(Map.of("token", apiKey))
                .setExtraHeaders(Map.of("platform", List.of("openhab"), "device", List.of("openhab-onlycat"))).build();
        Socket newSocket = IO.socket(GATEWAY_URL, options);
        socket = newSocket;
        newSocket.on(Socket.EVENT_CONNECT, args -> connected.run());
        newSocket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            String message = args.length == 0 ? "Unknown connection error" : String.valueOf(args[0]);
            connectionError.accept(message);
        });
        newSocket.on(Socket.EVENT_DISCONNECT, args -> disconnected.run());
        newSocket.on("deviceUpdate", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject update) {
                deviceUpdate.accept(update);
            }
        });
        newSocket.connect();
    }

    public void disconnect() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            current.off();
            current.disconnect();
        }
    }

    public @Nullable Object sendMessage(String event, JSONObject data)
            throws InterruptedException, ExecutionException, TimeoutException {
        Socket current = socket;
        if (current == null || !current.connected()) {
            throw new IllegalStateException("OnlyCat socket is not connected");
        }
        CompletableFuture<@Nullable Object> response = new CompletableFuture<>();
        current.emit(event, new Object[] { data }, args -> response.complete(args.length > 0 ? args[0] : null));
        Object result = response.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return result == JSONObject.NULL ? null : result;
    }
}
