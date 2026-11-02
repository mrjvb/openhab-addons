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
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openhab.binding.onlycat.internal.discovery.OnlyCatDiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the authenticated connection and discovered flaps for one OnlyCat account. */
@NonNullByDefault
public class OnlyCatAccountHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(OnlyCatAccountHandler.class);
    private final Map<String, JSONObject> devices = new ConcurrentHashMap<>();
    private final Set<OnlyCatDiscoveryService> discoveryServices = ConcurrentHashMap.newKeySet();
    private volatile @Nullable OnlyCatApiClient client;

    public OnlyCatAccountHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        OnlyCatConfiguration config = getConfigAs(OnlyCatConfiguration.class);
        if (config.apiKey.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "An OnlyCat API key is required");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        OnlyCatApiClient newClient = new OnlyCatApiClient(
                () -> scheduler.execute(() -> loadDevices(newClientReference())),
                message -> scheduler.execute(() -> onConnectionError(message)),
                () -> scheduler.execute(() -> onDisconnected(newClientReference())),
                update -> scheduler.execute(() -> onDeviceUpdate(update)));
        client = newClient;
        try {
            newClient.connect(config.apiKey.trim());
        } catch (URISyntaxException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Invalid OnlyCat gateway URL: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        OnlyCatApiClient oldClient = client;
        client = null;
        if (oldClient != null) {
            oldClient.disconnect();
        }
        devices.clear();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(OnlyCatDiscoveryService.class);
    }

    @Override
    public void handleCommand(org.openhab.core.thing.ChannelUID channelUID, org.openhab.core.types.Command command) {
    }

    public void addDiscoveryService(OnlyCatDiscoveryService service) {
        discoveryServices.add(service);
        service.devicesFound(devices.values());
    }

    public void removeDiscoveryService(OnlyCatDiscoveryService service) {
        discoveryServices.remove(service);
    }

    public void scanForDevices() {
        OnlyCatApiClient currentClient = client;
        if (currentClient != null) {
            scheduler.execute(() -> loadDevices(currentClient));
        }
    }

    @Nullable
    JSONObject getDeviceData(String deviceId) {
        return devices.get(deviceId);
    }

    void requestDeviceRefresh(String deviceId) {
        OnlyCatApiClient currentClient = client;
        if (currentClient == null) {
            return;
        }
        scheduler.execute(() -> {
            try {
                Object response = currentClient.sendMessage("getDevice",
                        new JSONObject().put("deviceId", deviceId).put("subscribe", true));
                if (response instanceof JSONObject device) {
                    onDeviceData(device);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                logger.debug("Could not refresh OnlyCat device {}: {}", deviceId, e.getMessage());
            }
        });
    }

    private OnlyCatApiClient newClientReference() {
        OnlyCatApiClient currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException("OnlyCat client is no longer active");
        }
        return currentClient;
    }

    private void loadDevices(OnlyCatApiClient currentClient) {
        if (currentClient != client) {
            return;
        }
        try {
            Object response = currentClient.sendMessage("getDevices", new JSONObject().put("subscribe", true));
            if (isUnauthorized(response)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "OnlyCat API key was rejected");
                return;
            }
            if (!(response instanceof JSONArray deviceList)) {
                throw new IllegalStateException("OnlyCat getDevices returned an unexpected response");
            }
            for (int index = 0; index < deviceList.length(); index++) {
                JSONObject summary = deviceList.optJSONObject(index);
                if (summary == null) {
                    continue;
                }
                String deviceId = summary.optString("deviceId", "");
                if (deviceId.isBlank()) {
                    continue;
                }
                Object deviceResponse = currentClient.sendMessage("getDevice",
                        new JSONObject().put("deviceId", deviceId).put("subscribe", true));
                onDeviceData(deviceResponse instanceof JSONObject device ? device : summary);
            }
            if (currentClient == client) {
                updateStatus(ThingStatus.ONLINE);
                discoveryServices.forEach(service -> service.devicesFound(devices.values()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (currentClient == client) {
                logger.debug("OnlyCat account request failed: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        }
    }

    private static boolean isUnauthorized(@Nullable Object response) {
        return response instanceof JSONObject json && json.optInt("code", -1) == 401;
    }

    private void onDeviceUpdate(JSONObject update) {
        String deviceId = update.optString("deviceId", "");
        JSONObject body = update.optJSONObject("body");
        if (deviceId.isBlank() && body != null) {
            deviceId = body.optString("deviceId", "");
        }
        if (deviceId.isBlank()) {
            return;
        }
        JSONObject merged = new JSONObject();
        JSONObject existing = devices.get(deviceId);
        if (existing != null) {
            existing.keys().forEachRemaining(key -> merged.put(key, existing.opt(key)));
        }
        JSONObject values = body != null ? body : update;
        values.keys().forEachRemaining(key -> merged.put(key, values.opt(key)));
        merged.put("deviceId", deviceId);
        onDeviceData(merged);
    }

    private void onDeviceData(JSONObject device) {
        String deviceId = device.optString("deviceId", "");
        if (deviceId.isBlank()) {
            return;
        }
        devices.put(deviceId, device);
        for (Thing child : getThing().getThings()) {
            if (child.getHandler() instanceof OnlyCatDeviceHandler handler && deviceId.equals(handler.getDeviceId())) {
                handler.deviceUpdated(device);
            }
        }
        discoveryServices.forEach(service -> service.devicesFound(Set.of(device)));
    }

    private void onConnectionError(String message) {
        if (client != null) {
            logger.debug("OnlyCat connection error: {}", message);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        }
    }

    private void onDisconnected(OnlyCatApiClient source) {
        if (source == client) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Disconnected from OnlyCat");
        }
    }
}
