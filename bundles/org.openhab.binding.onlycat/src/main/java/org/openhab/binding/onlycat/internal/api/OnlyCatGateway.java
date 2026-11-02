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

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.onlycat.internal.dto.Device;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;
import org.openhab.binding.onlycat.internal.dto.DeviceTransitPolicy;
import org.openhab.binding.onlycat.internal.dto.EventSummary;
import org.openhab.binding.onlycat.internal.dto.RfidLastSeen;
import org.openhab.binding.onlycat.internal.dto.RfidProfile;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;

/**
 * The requests of the OnlyCat gateway, on top of a function that performs a Socket.IO call.
 *
 * Every request is bounded by a timeout because the gateway may leave a call unanswered, and every reply is checked
 * for the status object the gateway answers with on failure: an invalid API key does not fail the Socket.IO handshake
 * but only the first request.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class OnlyCatGateway {

    /**
     * Performs a call on the connection.
     */
    @FunctionalInterface
    public interface Caller {
        CompletableFuture<JsonElement> call(String event, JsonObject data);
    }

    public static final String HOST = "gateway.onlycat.com";

    private static final long CALL_TIMEOUT_SECONDS = 20;
    private static final int STATUS_OK = 200;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int LOG_LIMIT = 100;
    private static final Type DEVICE_LIST = new TypeToken<List<Device>>() {
    }.getType();
    private static final Type EVENT_LIST = new TypeToken<List<DeviceEvent>>() {
    }.getType();
    private static final Type POLICY_LIST = new TypeToken<List<DeviceTransitPolicy>>() {
    }.getType();
    private static final Type LAST_SEEN_LIST = new TypeToken<List<RfidLastSeen>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final Caller caller;

    public OnlyCatGateway(Caller caller) {
        this.caller = caller;
    }

    public List<Device> getDevices() throws OnlyCatApiException {
        JsonObject data = new JsonObject();
        data.addProperty("subscribe", true);
        return readList(request("getDevices", data), DEVICE_LIST);
    }

    public @Nullable Device getDevice(String deviceId) throws OnlyCatApiException {
        return read(request("getDevice", device(deviceId, true)), Device.class);
    }

    public List<DeviceEvent> getDeviceEvents(String deviceId) throws OnlyCatApiException {
        return readList(request("getDeviceEvents", device(deviceId, true)), EVENT_LIST);
    }

    public @Nullable DeviceEvent getEvent(String deviceId, long eventId, boolean subscribe)
            throws OnlyCatApiException {
        return read(request("getEvent", event(deviceId, eventId, subscribe)), DeviceEvent.class);
    }

    public @Nullable EventSummary getEventSummary(String deviceId, long eventId, String accessToken, boolean subscribe)
            throws OnlyCatApiException {
        JsonObject data = event(deviceId, eventId, subscribe);
        data.addProperty("accessToken", accessToken);
        return EventSummary.fromJson(request("getEventSummary", data), gson);
    }

    public List<DeviceTransitPolicy> getDeviceTransitPolicies(String deviceId) throws OnlyCatApiException {
        return readList(request("getDeviceTransitPolicies", device(deviceId)), POLICY_LIST);
    }

    public @Nullable DeviceTransitPolicy getDeviceTransitPolicy(long policyId) throws OnlyCatApiException {
        JsonObject data = new JsonObject();
        data.addProperty("deviceTransitPolicyId", policyId);
        return read(request("getDeviceTransitPolicy", data), DeviceTransitPolicy.class);
    }

    public void activateDeviceTransitPolicy(String deviceId, long policyId) throws OnlyCatApiException {
        JsonObject data = device(deviceId);
        data.addProperty("deviceTransitPolicyId", policyId);
        request("activateDeviceTransitPolicy", data);
    }

    public void updateDeviceTransitPolicy(JsonObject policy) throws OnlyCatApiException {
        request("updateDeviceTransitPolicy", policy);
    }

    public void runDeviceCommand(String deviceId, String command) throws OnlyCatApiException {
        JsonObject data = device(deviceId);
        data.addProperty("command", command);
        request("runDeviceCommand", data);
    }

    public List<RfidLastSeen> getLastSeenRfidCodes(String deviceId) throws OnlyCatApiException {
        return readList(request("getLastSeenRfidCodesByDevice", device(deviceId)), LAST_SEEN_LIST);
    }

    public List<RfidLastSeen> getRfidLastSeen(String deviceId) throws OnlyCatApiException {
        return readList(request("getRfidLastSeenByDevice", device(deviceId)), LAST_SEEN_LIST);
    }

    public @Nullable RfidProfile getRfidProfile(String deviceId, String rfidCode) throws OnlyCatApiException {
        JsonObject data = device(deviceId);
        data.addProperty("rfidCode", rfidCode);
        return read(request("getRfidProfile", data), RfidProfile.class);
    }

    public JsonArray getDeviceRebootLogs(String deviceId, int hours) throws OnlyCatApiException {
        JsonObject data = device(deviceId);
        data.addProperty("limit", LOG_LIMIT);
        data.addProperty("hours", hours);
        return request("getDeviceRebootLogs", data) instanceof JsonArray array ? array : new JsonArray();
    }

    public JsonArray getDeviceTelemetryMetrics(String deviceId) throws OnlyCatApiException {
        return request("getDeviceTelemetryMetrics", device(deviceId)) instanceof JsonArray array ? array
                : new JsonArray();
    }

    private JsonObject device(String deviceId) {
        JsonObject data = new JsonObject();
        data.addProperty("deviceId", deviceId);
        return data;
    }

    private JsonObject device(String deviceId, boolean subscribe) {
        JsonObject data = device(deviceId);
        data.addProperty("subscribe", subscribe);
        return data;
    }

    private JsonObject event(String deviceId, long eventId, boolean subscribe) {
        JsonObject data = device(deviceId, subscribe);
        data.addProperty("eventId", eventId);
        return data;
    }

    private JsonElement request(String event, JsonObject data) throws OnlyCatApiException {
        try {
            JsonElement reply = caller.call(event, data).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            checkStatus(event, reply);
            return reply;
        } catch (TimeoutException e) {
            throw new OnlyCatApiException("No reply to " + event, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new OnlyCatApiException(event + " failed: " + (cause == null ? e : cause).getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OnlyCatApiException("Interrupted during " + event, e);
        }
    }

    private void checkStatus(String event, JsonElement reply) throws OnlyCatApiException {
        if (reply instanceof JsonObject object && object.get("code") instanceof JsonPrimitive code
                && code.isNumber()) {
            int status = code.getAsInt();
            if (status == STATUS_UNAUTHORIZED) {
                throw new OnlyCatAuthenticationException("The API key was rejected");
            }
            if (status != STATUS_OK) {
                JsonElement message = object.get("message");
                throw new OnlyCatApiException(event + " failed with status " + status
                        + (message != null && message.isJsonPrimitive() ? ": " + message.getAsString() : ""));
            }
        }
    }

    private <T> @Nullable T read(JsonElement reply, Class<T> type) throws OnlyCatApiException {
        try {
            return reply.isJsonObject() ? gson.fromJson(reply, type) : null;
        } catch (JsonParseException e) {
            throw new OnlyCatApiException("Unexpected reply: " + e.getMessage(), e);
        }
    }

    private <T> List<T> readList(JsonElement reply, Type listType) throws OnlyCatApiException {
        try {
            List<T> result = reply.isJsonArray() ? gson.fromJson(reply, listType) : null;
            return result == null ? List.of() : result;
        } catch (JsonParseException e) {
            throw new OnlyCatApiException("Unexpected reply: " + e.getMessage(), e);
        }
    }
}
