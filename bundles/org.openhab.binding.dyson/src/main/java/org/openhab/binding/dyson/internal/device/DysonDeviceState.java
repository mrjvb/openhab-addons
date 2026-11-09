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
package org.openhab.binding.dyson.internal.device;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Thread-safe store of the last known state of a Dyson device, built from its MQTT messages.
 *
 * <ul>
 * <li>{@code CURRENT-STATE} carries a {@code product-state} object with string values (snapshot).</li>
 * <li>{@code STATE-CHANGE} carries a {@code product-state} object with {@code [previous, current]} arrays.</li>
 * <li>{@code ENVIRONMENTAL-CURRENT-SENSOR-DATA} carries the sensor values in {@code data}.</li>
 * <li>{@code CURRENT-FAULTS} carries nested objects with fault codes.</li>
 * <li>Robot vacuums report most values at the top level of the message.</li>
 * </ul>
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonDeviceState {

    public enum MessageType {
        CURRENT_STATE,
        STATE_CHANGE,
        ENVIRONMENT,
        FAULTS,
        OTHER
    }

    /** How long a value sent with STATE-SET is protected from stale CURRENT-STATE replies. */
    private static final long PENDING_TTL_MS = 10000;
    private static final Set<String> IGNORED_TOP_LEVEL_KEYS = Set.of("msg", "time", "product-state", "data",
            "mode-reason", "state-reason");

    private record PendingValue(String value, long sentAt) {
    }

    private final Map<String, String> productState = new HashMap<>();
    private final Map<String, String> environment = new HashMap<>();
    private final Map<String, JsonElement> topLevel = new HashMap<>();
    private final Map<String, String> faults = new LinkedHashMap<>();
    private final Map<String, PendingValue> pending = new HashMap<>();
    private boolean currentStateReceived;
    private boolean environmentReceived;
    private boolean faultsReceived;

    public synchronized MessageType process(JsonObject message) {
        String msg = message.has("msg") && message.get("msg").isJsonPrimitive() ? message.get("msg").getAsString() : "";
        switch (msg) {
            case "CURRENT-STATE":
                mergeProductState(message, false);
                mergeTopLevel(message);
                currentStateReceived = true;
                return MessageType.CURRENT_STATE;
            case "STATE-CHANGE":
                mergeProductState(message, true);
                mergeTopLevel(message);
                return MessageType.STATE_CHANGE;
            case "ENVIRONMENTAL-CURRENT-SENSOR-DATA":
                JsonElement data = message.get("data");
                if (data instanceof JsonObject dataObject) {
                    dataObject.entrySet().forEach(e -> {
                        String value = asString(e.getValue());
                        if (value != null) {
                            environment.put(e.getKey(), value);
                        }
                    });
                }
                environmentReceived = true;
                return MessageType.ENVIRONMENT;
            case "CURRENT-FAULTS":
                faults.clear();
                collectFaults(message);
                faultsReceived = true;
                return MessageType.FAULTS;
            default:
                return MessageType.OTHER;
        }
    }

    private void mergeProductState(JsonObject message, boolean stateChange) {
        JsonElement element = message.get("product-state");
        if (!(element instanceof JsonObject state)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
            String key = entry.getKey();
            JsonElement raw = entry.getValue();
            if (raw instanceof JsonArray array && !array.isEmpty()) {
                // STATE-CHANGE: [previous, current]
                raw = array.get(array.size() - 1);
            }
            String value = asString(raw);
            if (value == null) {
                continue;
            }
            PendingValue pendingValue = pending.get(key);
            if (pendingValue != null) {
                if (now - pendingValue.sentAt() > PENDING_TTL_MS) {
                    pending.remove(key);
                } else if (stateChange) {
                    // An authoritative device transition: keep guarding against stale snapshots with the new value
                    pending.put(key, new PendingValue(value, pendingValue.sentAt()));
                } else if (!pendingValue.value().equals(value)) {
                    // Stale snapshot that contradicts a command we just sent
                    continue;
                } else {
                    pending.remove(key);
                }
            }
            productState.put(key, value);
        }
    }

    private void mergeTopLevel(JsonObject message) {
        for (Map.Entry<String, JsonElement> entry : message.entrySet()) {
            if (!IGNORED_TOP_LEVEL_KEYS.contains(entry.getKey())) {
                topLevel.put(entry.getKey(), entry.getValue());
            }
        }
        // Robot vacuums send "state" in CURRENT-STATE and "newstate" in STATE-CHANGE
        JsonElement newState = message.get("newstate");
        if (newState != null && !newState.isJsonNull()) {
            topLevel.put("state", newState);
        }
        // traverseTargetId is only meaningful while traversing
        if (!message.has("traverseTargetId") && (message.has("state") || message.has("newstate"))) {
            topLevel.remove("traverseTargetId");
        }
    }

    private void collectFaults(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if ("msg".equals(key) || "time".equals(key)) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value instanceof JsonObject nested) {
                collectFaults(nested);
            } else {
                String text = asString(value);
                if (text != null) {
                    faults.put(key, text);
                }
            }
        }
    }

    private static @Nullable String asString(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    /**
     * Reflects the values sent with a STATE-SET command right away and guards them against stale replies.
     */
    public synchronized void applyCommand(Map<String, String> data) {
        long now = System.currentTimeMillis();
        data.forEach((key, value) -> {
            if (!"rstf".equals(key)) {
                productState.put(key, value);
                pending.put(key, new PendingValue(value, now));
            }
        });
    }

    public synchronized @Nullable String get(String key) {
        return productState.get(key);
    }

    public synchronized String get(String key, String defaultValue) {
        String value = productState.get(key);
        return value != null ? value : defaultValue;
    }

    public synchronized boolean has(String key) {
        return productState.containsKey(key);
    }

    public synchronized @Nullable String getEnvironment(String key) {
        return environment.get(key);
    }

    public synchronized boolean hasEnvironment(String key) {
        return environment.containsKey(key);
    }

    public synchronized Map<String, String> getEnvironment() {
        return Map.copyOf(environment);
    }

    /**
     * Returns a value from the top level of the state messages, falling back to {@code product-state}.
     */
    public synchronized @Nullable JsonElement getTopLevel(String key) {
        JsonElement element = topLevel.get(key);
        if (element == null && productState.containsKey(key)) {
            return new JsonPrimitive(productState.get(key));
        }
        return element;
    }

    public synchronized @Nullable String getTopLevelString(String key) {
        return asString(getTopLevel(key));
    }

    public synchronized boolean hasTopLevel(String key) {
        return topLevel.containsKey(key) || productState.containsKey(key);
    }

    public synchronized Map<String, String> getFaults() {
        return Map.copyOf(faults);
    }

    public synchronized boolean isCurrentStateReceived() {
        return currentStateReceived;
    }

    public synchronized boolean isEnvironmentReceived() {
        return environmentReceived;
    }

    public synchronized boolean isFaultsReceived() {
        return faultsReceived;
    }

    /**
     * Returns the key used for power control: modern devices use {@code fpwr}, older "Link" devices
     * (e.g. HP02, TP02) use {@code fmod}.
     */
    public synchronized String getPowerControlKey() {
        if (productState.containsKey("fpwr")) {
            return "fpwr";
        }
        return productState.containsKey("fmod") ? "fmod" : "fpwr";
    }
}
