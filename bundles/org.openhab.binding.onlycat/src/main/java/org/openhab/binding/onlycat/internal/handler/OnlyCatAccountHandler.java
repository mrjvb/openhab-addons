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
package org.openhab.binding.onlycat.internal.handler;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.onlycat.internal.api.OnlyCatApiException;
import org.openhab.binding.onlycat.internal.api.OnlyCatAuthenticationException;
import org.openhab.binding.onlycat.internal.api.OnlyCatGateway;
import org.openhab.binding.onlycat.internal.api.SocketIoClient;
import org.openhab.binding.onlycat.internal.config.OnlyCatAccountConfiguration;
import org.openhab.binding.onlycat.internal.discovery.OnlyCatDiscoveryService;
import org.openhab.binding.onlycat.internal.dto.CatLocation;
import org.openhab.binding.onlycat.internal.dto.Device;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;
import org.openhab.binding.onlycat.internal.dto.DeviceTransitPolicy;
import org.openhab.binding.onlycat.internal.dto.EventSummary;
import org.openhab.binding.onlycat.internal.dto.RfidLastSeen;
import org.openhab.binding.onlycat.internal.dto.RfidProfile;
import org.openhab.core.common.NamedThreadFactory;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Holds the single gateway connection of an account. The API key is account-wide, so all flaps and cats share it.
 *
 * All gateway traffic and all changes to the state kept here run on one dedicated thread. This keeps the order of
 * updates intact, and blocking on a reply there holds up nothing but other gateway work.
 *
 * Pushes of the gateway are treated as hints that something changed: the data is fetched again rather than read from
 * the push. Subscriptions do not survive a reconnect, so everything is set up anew on each connect.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class OnlyCatAccountHandler extends BaseBridgeHandler implements SocketIoClient.Listener {

    @FunctionalInterface
    private interface GatewayTask {
        void run(OnlyCatGateway gateway) throws OnlyCatApiException;
    }

    private static final URI GATEWAY_URI = URI
            .create("wss://" + OnlyCatGateway.HOST + "/socket.io/?EIO=4&transport=websocket");
    private static final Map<String, String> CLIENT_HEADERS = Map.of("platform", "openhab", "device",
            "openhab-binding-onlycat");
    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;
    private static final int MAX_CLIENT_NAME_LENGTH = 20;
    private static final long INITIAL_RECONNECT_DELAY_SECONDS = 10;
    private static final long MAX_RECONNECT_DELAY_SECONDS = 300;

    private final Logger logger = LoggerFactory.getLogger(OnlyCatAccountHandler.class);
    private final Gson gson = new Gson();
    private final WebSocketFactory webSocketFactory;
    private final URI gatewayUri;

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, DeviceTransitPolicy>> policies = new ConcurrentHashMap<>();
    private final Map<String, DeviceEvent> events = new ConcurrentHashMap<>();
    private final Map<String, EventSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, CatState> cats = new ConcurrentHashMap<>();
    private final Set<String> hiddenCats = ConcurrentHashMap.newKeySet();

    private OnlyCatAccountConfiguration config = new OnlyCatAccountConfiguration();
    private @Nullable ScheduledExecutorService executor;
    private @Nullable WebSocketClient webSocketClient;
    private @Nullable SocketIoClient socket;
    private @Nullable OnlyCatGateway gateway;
    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> reconnectJob;
    private @Nullable OnlyCatDiscoveryService discoveryService;
    private long reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
    private volatile boolean synchronizedOnce;

    public OnlyCatAccountHandler(Bridge bridge, WebSocketFactory webSocketFactory) {
        this(bridge, webSocketFactory, GATEWAY_URI);
    }

    OnlyCatAccountHandler(Bridge bridge, WebSocketFactory webSocketFactory, URI gatewayUri) {
        super(bridge);
        this.webSocketFactory = webSocketFactory;
        this.gatewayUri = gatewayUri;
    }

    @Override
    public void initialize() {
        config = getConfigAs(OnlyCatAccountConfiguration.class);
        if (config.apiKey.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error.missing-api-key");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(thingName()));
        execute(this::startClient);
    }

    @Override
    public void dispose() {
        ScheduledExecutorService currentExecutor = executor;
        executor = null;
        cancelJobs();
        SocketIoClient currentSocket = socket;
        socket = null;
        gateway = null;
        if (currentSocket != null) {
            currentSocket.close();
        }
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        WebSocketClient client = webSocketClient;
        webSocketClient = null;
        if (client != null) {
            scheduler.execute(() -> {
                try {
                    client.stop();
                } catch (Exception e) {
                    logger.debug("Failed to stop the WebSocket client: {}", e.getMessage());
                }
            });
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // the account has no channels
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(OnlyCatDiscoveryService.class);
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        execute(() -> {
            if (synchronizedOnce) {
                if (childHandler instanceof OnlyCatFlapHandler flap) {
                    replayTo(flap);
                } else if (childHandler instanceof OnlyCatCatHandler cat) {
                    replayTo(cat);
                }
            }
        });
    }

    public void setDiscoveryService(@Nullable OnlyCatDiscoveryService service) {
        discoveryService = service;
    }

    /**
     * Reports all flaps and cats of the account to the discovery service.
     */
    public void discover() {
        execute(this::reportDiscovery);
    }

    /**
     * @return the door policies of a flap
     */
    public List<DeviceTransitPolicy> getPolicies(String deviceId) {
        Map<Long, DeviceTransitPolicy> known = policies.get(deviceId);
        return known == null ? List.of() : List.copyOf(known.values());
    }

    /**
     * @return the name of the cat with the given RFID code, or the code if there is no name
     */
    public String getCatName(String rfidCode) {
        CatState cat = cats.get(rfidCode);
        return cat == null ? rfidCode : cat.getName();
    }

    /**
     * Sends a command such as {@code unlock} or {@code reboot} to a flap.
     */
    public void runDeviceCommand(String deviceId, String command) {
        attempt("Running " + command, gateway -> gateway.runDeviceCommand(deviceId, command));
    }

    /**
     * Activates a door policy.
     *
     * @param deviceId the flap
     * @param policy the name or the id of the policy
     * @return false if the flap has no such policy
     */
    public boolean activatePolicy(String deviceId, String policy) {
        for (DeviceTransitPolicy candidate : getPolicies(deviceId)) {
            Long id = candidate.deviceTransitPolicyId;
            if (id != null && (policy.equals(candidate.getDisplayName()) || policy.equals(id.toString()))) {
                attempt("Activating door policy", gateway -> gateway.activateDeviceTransitPolicy(deviceId, id));
                return true;
            }
        }
        return false;
    }

    /**
     * Changes a door policy.
     *
     * @param deviceId the flap to refresh afterwards
     * @param policy the policy, in the form of {@code updateDeviceTransitPolicy}
     */
    public void updatePolicy(String deviceId, JsonObject policy) {
        attempt("Updating door policy", gateway -> {
            gateway.updateDeviceTransitPolicy(policy);
            refreshPolicies(gateway, deviceId);
        });
    }

    @Override
    public void onConnected() {
        execute(this::synchronize);
    }

    @Override
    public void onDisconnected(String reason) {
        execute(() -> {
            logger.debug("Disconnected from the gateway: {}", reason);
            socket = null;
            gateway = null;
            cancelJobs();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, reason);
            scheduleReconnect();
        });
    }

    @Override
    public void onEvent(String name, JsonElement payload) {
        execute(() -> handlePush(name, payload));
    }

    private void startClient() {
        WebSocketClient client = webSocketClient;
        if (client == null) {
            client = webSocketFactory.createWebSocketClient(clientName());
            client.getPolicy().setMaxTextMessageSize(MAX_MESSAGE_SIZE);
            client.setMaxIdleTimeout(0);
            webSocketClient = client;
        }
        try {
            if (!client.isStarted()) {
                client.start();
            }
            ScheduledExecutorService currentExecutor = executor;
            if (currentExecutor == null) {
                return;
            }
            JsonObject auth = new JsonObject();
            auth.addProperty("token", config.apiKey);
            SocketIoClient newSocket = new SocketIoClient(client, gatewayUri, CLIENT_HEADERS, auth, this,
                    currentExecutor);
            socket = newSocket;
            gateway = new OnlyCatGateway(newSocket::call);
            newSocket.connect();
        } catch (Exception e) {
            logger.debug("Cannot connect to the gateway: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor == null) {
            return;
        }
        ScheduledFuture<?> current = reconnectJob;
        if (current != null) {
            current.cancel(false);
        }
        reconnectJob = currentExecutor.schedule(this::startClient, reconnectDelay, TimeUnit.SECONDS);
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_SECONDS);
    }

    private void cancelJobs() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(false);
            refreshJob = null;
        }
        job = reconnectJob;
        if (job != null) {
            job.cancel(false);
            reconnectJob = null;
        }
    }

    private void synchronize() {
        OnlyCatGateway currentGateway = gateway;
        if (currentGateway == null) {
            return;
        }
        try {
            List<Device> reported = currentGateway.getDevices();
            updateStatus(ThingStatus.ONLINE);
            reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
            Set<String> deviceIds = new HashSet<>();
            for (Device device : reported) {
                String deviceId = device.deviceId;
                if (deviceId != null) {
                    deviceIds.add(deviceId);
                    synchronizeDevice(currentGateway, deviceId);
                }
            }
            devices.keySet().retainAll(deviceIds);
            synchronizedOnce = true;
            getThing().getThings().forEach(thing -> {
                if (thing.getHandler() instanceof OnlyCatFlapHandler flap) {
                    replayTo(flap);
                } else if (thing.getHandler() instanceof OnlyCatCatHandler cat) {
                    replayTo(cat);
                }
            });
            reportDiscovery();
            scheduleRefresh();
        } catch (OnlyCatAuthenticationException e) {
            // reconnecting cannot help: the key stays invalid until the configuration changes
            SocketIoClient currentSocket = socket;
            socket = null;
            gateway = null;
            if (currentSocket != null) {
                currentSocket.close();
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error.invalid-api-key");
        } catch (OnlyCatApiException e) {
            SocketIoClient currentSocket = socket;
            if (currentSocket != null) {
                currentSocket.close();
            }
            onDisconnected(String.valueOf(e.getMessage()));
        }
    }

    private void synchronizeDevice(OnlyCatGateway currentGateway, String deviceId) {
        attemptNow(deviceId, currentGateway, gatewayCall -> refreshDevice(gatewayCall, deviceId));
        attemptNow(deviceId, currentGateway, gatewayCall -> refreshPolicies(gatewayCall, deviceId));
        attemptNow(deviceId, currentGateway, gatewayCall -> {
            gatewayCall.getDeviceEvents(deviceId).stream().filter(event -> event.eventId != null)
                    .max(Comparator.comparing(event -> event.eventId)).ifPresent(latest -> {
                        Long eventId = latest.eventId;
                        if (eventId != null) {
                            attemptNow(deviceId, gatewayCall,
                                    nested -> fetchEvent(nested, deviceId, eventId, false, true));
                        }
                    });
        });
        attemptNow(deviceId, currentGateway, gatewayCall -> refreshCats(gatewayCall, deviceId));
        attemptNow(deviceId, currentGateway, gatewayCall -> refreshDiagnostics(gatewayCall, deviceId));
    }

    private void scheduleRefresh() {
        ScheduledExecutorService currentExecutor = executor;
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(false);
        }
        if (currentExecutor != null) {
            long interval = Math.max(1, config.refreshInterval);
            refreshJob = currentExecutor.scheduleWithFixedDelay(this::refreshAll, interval, interval, TimeUnit.MINUTES);
        }
    }

    /**
     * Door policies, cat names and reboot logs change without a push, and a running event may have lost its update.
     */
    private void refreshAll() {
        OnlyCatGateway currentGateway = gateway;
        if (currentGateway == null) {
            return;
        }
        for (String deviceId : devices.keySet()) {
            attemptNow(deviceId, currentGateway, gatewayCall -> refreshPolicies(gatewayCall, deviceId));
            attemptNow(deviceId, currentGateway, gatewayCall -> refreshCats(gatewayCall, deviceId));
            attemptNow(deviceId, currentGateway, gatewayCall -> refreshDiagnostics(gatewayCall, deviceId));
            DeviceEvent event = events.get(deviceId);
            Long eventId = event == null ? null : event.eventId;
            if (event != null && eventId != null && !event.isConcluded()) {
                attemptNow(deviceId, currentGateway,
                        gatewayCall -> fetchEvent(gatewayCall, deviceId, eventId, false, true));
            }
        }
        reportDiscovery();
    }

    private void refreshDevice(OnlyCatGateway currentGateway, String deviceId) throws OnlyCatApiException {
        Device device = currentGateway.getDevice(deviceId);
        if (device == null) {
            return;
        }
        device.deviceId = deviceId;
        devices.put(deviceId, device);
        OnlyCatFlapHandler flap = findFlap(deviceId);
        if (flap != null) {
            flap.updateDevice(device);
        }
    }

    private void refreshPolicies(OnlyCatGateway currentGateway, String deviceId) throws OnlyCatApiException {
        Map<Long, DeviceTransitPolicy> loaded = new LinkedHashMap<>();
        for (DeviceTransitPolicy listed : currentGateway.getDeviceTransitPolicies(deviceId)) {
            Long policyId = listed.deviceTransitPolicyId;
            if (policyId != null) {
                DeviceTransitPolicy policy = currentGateway.getDeviceTransitPolicy(policyId);
                if (policy != null) {
                    loaded.put(policyId, policy);
                }
            }
        }
        policies.put(deviceId, loaded);
        OnlyCatFlapHandler flap = findFlap(deviceId);
        if (flap != null) {
            flap.updatePolicies(List.copyOf(loaded.values()));
        }
    }

    private void refreshCats(OnlyCatGateway currentGateway, String deviceId) throws OnlyCatApiException {
        Map<String, RfidLastSeen> sightings = new HashMap<>();
        for (RfidLastSeen lastSeen : currentGateway.getRfidLastSeen(deviceId)) {
            String code = lastSeen.rfidCode;
            if (code != null) {
                sightings.put(code, lastSeen);
            }
        }
        for (RfidLastSeen entry : currentGateway.getLastSeenRfidCodes(deviceId)) {
            String code = entry.rfidCode;
            if (code == null) {
                continue;
            }
            CatState cat = catState(code);
            RfidProfile profile = currentGateway.getRfidProfile(deviceId, code);
            if (profile != null && profile.label != null) {
                cat.label = profile.label;
            }
            RfidLastSeen lastSeen = sightings.get(code);
            if (lastSeen != null) {
                applyLastSeen(cat, deviceId, lastSeen);
            }
            hiddenCats.remove(code);
            if (entry.isHidden() || (lastSeen != null && lastSeen.isHidden())) {
                hiddenCats.add(code);
            }
            OnlyCatCatHandler handler = findCat(code);
            if (handler != null) {
                handler.updateCat(cat);
            }
        }
    }

    /**
     * Applies a statement of where a cat currently is, which is only news if it is not older than what is known.
     */
    private void applyLastSeen(CatState cat, String deviceId, RfidLastSeen lastSeen) {
        var timestamp = lastSeen.getEventTimestamp();
        var known = cat.lastSeen;
        if (known != null && (timestamp == null || timestamp.isBefore(known))) {
            return;
        }
        cat.lastSeen = timestamp;
        cat.lastDeviceId = deviceId;
        cat.lastEventId = lastSeen.eventId;
        CatLocation location = CatLocation.fromLastSeen(lastSeen);
        if (location != null) {
            cat.location = location;
        }
        EventSummary.SubEvent subEvent = lastSeen.lastSubevent;
        if (subEvent != null) {
            cat.lastDirection = subEvent.direction;
            cat.lastAction = subEvent.action;
        }
    }

    private void refreshDiagnostics(OnlyCatGateway currentGateway, String deviceId) throws OnlyCatApiException {
        JsonArray rebootLog = currentGateway.getDeviceRebootLogs(deviceId, Math.max(1, config.rebootLogHours));
        JsonObject telemetry = null;
        if (config.detailedMetrics) {
            telemetry = latestMetrics(currentGateway.getDeviceTelemetryMetrics(deviceId));
        }
        OnlyCatFlapHandler flap = findFlap(deviceId);
        if (flap != null) {
            flap.updateDiagnostics(rebootLog, telemetry);
        }
    }

    /**
     * Reduces the metrics, a list of measurements over time, to the latest value of each.
     */
    static JsonObject latestMetrics(JsonArray metrics) {
        Map<String, String> latestTime = new HashMap<>();
        JsonObject latest = new JsonObject();
        for (JsonElement element : metrics) {
            if (element instanceof JsonObject metric && metric.get("measureName") instanceof JsonPrimitive measure
                    && metric.has("value")) {
                String name = measure.getAsString();
                String time = metric.get("time") instanceof JsonPrimitive primitive ? primitive.getAsString() : "";
                String known = latestTime.get(name);
                if (known == null || known.compareTo(time) <= 0) {
                    latestTime.put(name, time);
                    latest.add(name, metric.get("value"));
                }
            }
        }
        return latest;
    }

    private void fetchEvent(OnlyCatGateway currentGateway, String deviceId, long eventId, boolean subscribe,
            boolean withSummary) throws OnlyCatApiException {
        DeviceEvent event = currentGateway.getEvent(deviceId, eventId, subscribe);
        if (event == null) {
            return;
        }
        event.deviceId = deviceId;
        event.eventId = eventId;
        applyEvent(deviceId, event);
        String accessToken = event.accessToken;
        if (withSummary && accessToken != null) {
            applySummary(currentGateway.getEventSummary(deviceId, eventId, accessToken, subscribe));
        }
    }

    private void applyEvent(String deviceId, DeviceEvent event) {
        DeviceEvent current = events.get(deviceId);
        Long currentId = current == null ? null : current.eventId;
        Long eventId = event.eventId;
        if (current != null && currentId != null && eventId != null) {
            if (eventId < currentId) {
                return;
            }
            if (eventId.equals(currentId)) {
                event.rfidCodes = mergeCodes(current.getRfidCodes(), event.getRfidCodes());
            }
        }
        events.put(deviceId, event);
        for (String code : event.getRfidCodes()) {
            CatState cat = catState(code);
            var timestamp = event.getTimestamp();
            var known = cat.lastSeen;
            if (timestamp != null && (known == null || known.isBefore(timestamp))) {
                cat.lastSeen = timestamp;
                cat.lastDeviceId = deviceId;
                cat.lastEventId = eventId;
                OnlyCatCatHandler handler = findCat(code);
                if (handler != null) {
                    handler.updateCat(cat);
                }
            }
        }
        OnlyCatFlapHandler flap = findFlap(deviceId);
        if (flap != null) {
            flap.updateEvent(event);
            EventSummary summary = summaries.get(deviceId);
            if (summary != null) {
                flap.updateSummary(summary);
            }
        }
    }

    private static List<String> mergeCodes(List<String> known, List<String> added) {
        List<String> merged = new ArrayList<>(known);
        added.stream().filter(code -> !merged.contains(code)).forEach(merged::add);
        return merged;
    }

    private void applySummary(@Nullable EventSummary summary) {
        if (summary == null) {
            return;
        }
        String deviceId = summary.deviceId;
        Long eventId = summary.eventId;
        if (deviceId == null || eventId == null) {
            return;
        }
        EventSummary current = summaries.get(deviceId);
        Long currentId = current == null ? null : current.eventId;
        if (currentId != null && eventId < currentId) {
            return;
        }
        summaries.put(deviceId, summary);

        Map<String, EventSummary.SubEvent> lastByCat = new LinkedHashMap<>();
        for (EventSummary.SubEvent subEvent : summary.getSubevents()) {
            String code = subEvent.rfidCode;
            if (code != null) {
                lastByCat.put(code, subEvent);
            }
        }
        lastByCat.forEach((code, subEvent) -> {
            CatState cat = catState(code);
            Long seenEvent = cat.lastEventId;
            if (deviceId.equals(cat.lastDeviceId) && seenEvent != null && eventId < seenEvent) {
                return;
            }
            cat.lastDeviceId = deviceId;
            cat.lastEventId = eventId;
            cat.lastDirection = subEvent.direction;
            cat.lastAction = subEvent.action;
            CatLocation location = CatLocation.fromSubEvent(subEvent);
            if (location != null) {
                cat.location = location;
            }
            OnlyCatCatHandler handler = findCat(code);
            if (handler != null) {
                handler.updateCat(cat);
            }
        });
        OnlyCatFlapHandler flap = findFlap(deviceId);
        if (flap != null) {
            flap.updateSummary(summary);
        }
    }

    private void handlePush(String name, JsonElement payload) {
        OnlyCatGateway currentGateway = gateway;
        if (currentGateway == null) {
            return;
        }
        JsonObject message = payload instanceof JsonObject object ? object : new JsonObject();
        JsonObject body = message.get("body") instanceof JsonObject object ? object : new JsonObject();
        String deviceId = text(message, body, "deviceId");
        Long eventId = number(message, body, "eventId");
        switch (name) {
            case "userUpdate" -> synchronize();
            case "deviceUpdate" -> {
                if (deviceId != null) {
                    String id = deviceId;
                    attemptNow(id, currentGateway, gatewayCall -> refreshDevice(gatewayCall, id));
                    if (!policyKnown(id)) {
                        attemptNow(id, currentGateway, gatewayCall -> refreshPolicies(gatewayCall, id));
                    }
                }
            }
            case "deviceEventUpdate", "eventUpdate" -> {
                if (deviceId != null && eventId != null) {
                    String id = deviceId;
                    long event = eventId;
                    boolean subscribe = "deviceEventUpdate".equals(name);
                    attemptNow(id, currentGateway,
                            gatewayCall -> fetchEvent(gatewayCall, id, event, subscribe, subscribe));
                }
            }
            case "eventSummaryUpdate" -> onSummaryPush(currentGateway, message, deviceId, eventId);
            default -> logger.debug("Ignoring unknown push '{}'", name);
        }
    }

    private void onSummaryPush(OnlyCatGateway currentGateway, JsonObject message, @Nullable String deviceId,
            @Nullable Long eventId) {
        EventSummary summary = EventSummary.fromJson(message, gson);
        if (summary != null) {
            applySummary(summary);
        } else if (deviceId != null && eventId != null) {
            DeviceEvent event = events.get(deviceId);
            String accessToken = event == null ? null : event.accessToken;
            if (accessToken != null) {
                String id = deviceId;
                long summaryEvent = eventId;
                attemptNow(id, currentGateway,
                        gatewayCall -> applySummary(gatewayCall.getEventSummary(id, summaryEvent, accessToken, false)));
            }
        }
    }

    private boolean policyKnown(String deviceId) {
        Device device = devices.get(deviceId);
        Long policyId = device == null ? null : device.deviceTransitPolicyId;
        Map<Long, DeviceTransitPolicy> known = policies.get(deviceId);
        return policyId == null || (known != null && known.containsKey(policyId));
    }

    private static @Nullable String text(JsonObject message, JsonObject body, String key) {
        JsonElement value = message.has(key) ? message.get(key) : body.get(key);
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : null;
    }

    private static @Nullable Long number(JsonObject message, JsonObject body, String key) {
        JsonElement value = message.has(key) ? message.get(key) : body.get(key);
        return value instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsLong() : null;
    }

    private CatState catState(String rfidCode) {
        CatState cat = cats.get(rfidCode);
        if (cat == null) {
            cat = new CatState(rfidCode);
            cats.put(rfidCode, cat);
        }
        return cat;
    }

    private void replayTo(OnlyCatFlapHandler flap) {
        String deviceId = flap.getDeviceId();
        Device device = devices.get(deviceId);
        if (device == null) {
            flap.deviceNotFound();
            return;
        }
        flap.updateDevice(device);
        flap.updatePolicies(getPolicies(deviceId));
        DeviceEvent event = events.get(deviceId);
        if (event != null) {
            flap.updateEvent(event);
        }
        EventSummary summary = summaries.get(deviceId);
        if (summary != null) {
            flap.updateSummary(summary);
        }
    }

    private void replayTo(OnlyCatCatHandler handler) {
        CatState cat = cats.get(handler.getRfidCode());
        if (cat == null) {
            handler.catNotFound();
        } else {
            handler.updateCat(cat);
        }
    }

    private void reportDiscovery() {
        OnlyCatDiscoveryService service = discoveryService;
        if (service != null) {
            devices.values().forEach(service::flapFound);
            cats.values().stream().filter(
                    cat -> !hiddenCats.contains(cat.rfidCode) && (cat.label != null || cat.lastDeviceId != null))
                    .forEach(service::catFound);
        }
    }

    private @Nullable OnlyCatFlapHandler findFlap(String deviceId) {
        for (Thing thing : getThing().getThings()) {
            if (thing.getHandler() instanceof OnlyCatFlapHandler flap && deviceId.equals(flap.getDeviceId())) {
                return flap;
            }
        }
        return null;
    }

    private @Nullable OnlyCatCatHandler findCat(String rfidCode) {
        for (Thing thing : getThing().getThings()) {
            if (thing.getHandler() instanceof OnlyCatCatHandler cat && rfidCode.equals(cat.getRfidCode())) {
                return cat;
            }
        }
        return null;
    }

    private void attempt(String description, GatewayTask task) {
        execute(() -> {
            OnlyCatGateway currentGateway = gateway;
            if (currentGateway == null) {
                logger.debug("{} is not possible while disconnected", description);
                return;
            }
            try {
                task.run(currentGateway);
            } catch (OnlyCatApiException e) {
                logger.warn("{} failed: {}", description, e.getMessage());
            }
        });
    }

    /** One failing request must not keep the other values of a flap from being updated. */
    private void attemptNow(String deviceId, OnlyCatGateway currentGateway, GatewayTask task) {
        try {
            task.run(currentGateway);
        } catch (OnlyCatApiException e) {
            logger.debug("Request for {} failed: {}", deviceId, e.getMessage());
        }
    }

    private void execute(Runnable task) {
        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor == null) {
            return;
        }
        try {
            currentExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.warn("Unexpected failure while processing gateway data: {}", e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("Dropping work after shutdown");
        }
    }

    private String thingName() {
        return "onlycat-" + getThing().getUID().getId();
    }

    private String clientName() {
        String name = thingName().replaceAll("[^a-zA-Z0-9_-]", "_");
        return name.length() > MAX_CLIENT_NAME_LENGTH ? name.substring(0, MAX_CLIENT_NAME_LENGTH) : name;
    }
}
