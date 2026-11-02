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

import static org.openhab.binding.onlycat.internal.OnlyCatBindingConstants.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.onlycat.internal.OnlyCatStateDescriptionProvider;
import org.openhab.binding.onlycat.internal.action.OnlyCatFlapActions;
import org.openhab.binding.onlycat.internal.api.OnlyCatGateway;
import org.openhab.binding.onlycat.internal.config.OnlyCatFlapConfiguration;
import org.openhab.binding.onlycat.internal.dto.Device;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;
import org.openhab.binding.onlycat.internal.dto.DeviceTransitPolicy;
import org.openhab.binding.onlycat.internal.dto.EventSummary;
import org.openhab.binding.onlycat.internal.policy.DoorPolicy;
import org.openhab.binding.onlycat.internal.policy.LockVerdict;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.StateOption;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Represents a flap. The Thing status tells whether the account can reach the flap's data; whether the flap itself is
 * connected to the OnlyCat cloud is the {@code connected} channel, so that an offline flap stays observable.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class OnlyCatFlapHandler extends BaseThingHandler {

    private static final String[] TRIGGER_NAMES = { "MANUAL", "REMOTE", "INDOOR_MOTION", "OUTDOOR_MOTION" };
    private static final String[] CLASSIFICATION_NAMES = { "UNKNOWN", "CLEAR", "SUSPICIOUS", "CONTRABAND",
            "HUMAN_ACTIVITY" };
    private static final int CLASSIFICATION_REMOTE_UNLOCK = 10;
    private static final long IMAGE_TIMEOUT_SECONDS = 15;
    private static final String DEFAULT_IMAGE_TYPE = "image/jpeg";

    private final Logger logger = LoggerFactory.getLogger(OnlyCatFlapHandler.class);
    private final Gson gson = new Gson();
    private final HttpClient httpClient;
    private final OnlyCatStateDescriptionProvider stateDescriptionProvider;

    private OnlyCatFlapConfiguration config = new OnlyCatFlapConfiguration();
    private volatile @Nullable Device device;
    private volatile List<DeviceTransitPolicy> policies = List.of();
    private volatile @Nullable DeviceEvent event;
    private volatile @Nullable EventSummary summary;
    private volatile @Nullable String imageKey;

    public OnlyCatFlapHandler(Thing thing, HttpClient httpClient,
            OnlyCatStateDescriptionProvider stateDescriptionProvider) {
        super(thing);
        this.httpClient = httpClient;
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    public String getDeviceId() {
        return config.deviceId;
    }

    @Override
    public void initialize() {
        config = getConfigAs(OnlyCatFlapConfiguration.class);
        if (config.deviceId.isBlank()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error.missing-device-id");
            return;
        }
        if (getAccount() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
        // when the bridge is back, the account replays its data, which sets this Thing online
    }

    @Override
    public Set<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(OnlyCatFlapActions.class);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            publishAll();
            return;
        }
        switch (channelUID.getId()) {
            case CHANNEL_UNLOCK -> {
                if (command == OnOffType.ON) {
                    unlock();
                }
            }
            case CHANNEL_REBOOT -> {
                if (command == OnOffType.ON) {
                    reboot();
                }
            }
            case CHANNEL_DOOR_POLICY -> {
                if (!activateDoorPolicy(command.toString())) {
                    logger.warn("Flap {} has no door policy '{}'", config.deviceId, command);
                }
            }
            default -> logger.debug("Channel {} takes no commands", channelUID.getId());
        }
    }

    public void unlock() {
        OnlyCatAccountHandler account = getAccount();
        if (account != null) {
            account.runDeviceCommand(config.deviceId, "unlock");
        }
    }

    public void reboot() {
        OnlyCatAccountHandler account = getAccount();
        if (account != null) {
            account.runDeviceCommand(config.deviceId, "reboot");
        }
    }

    /**
     * @param policy the name or id of a door policy
     * @return false if the flap has no such policy
     */
    public boolean activateDoorPolicy(String policy) {
        OnlyCatAccountHandler account = getAccount();
        return account != null && account.activatePolicy(config.deviceId, policy);
    }

    /**
     * @return all door policies as JSON, in the form {@link #updateDoorPolicy(String)} accepts
     */
    public String getDoorPolicies() {
        return gson.toJson(policies);
    }

    /**
     * Changes an existing door policy.
     *
     * @param policyJson a JSON object with at least {@code deviceTransitPolicyId} and {@code transitPolicy}
     * @return false if the JSON is unusable or does not refer to a policy of this flap
     */
    public boolean updateDoorPolicy(String policyJson) {
        OnlyCatAccountHandler account = getAccount();
        if (account == null) {
            return false;
        }
        try {
            if (JsonParser.parseString(policyJson) instanceof JsonObject policy
                    && policy.get("deviceTransitPolicyId") instanceof JsonPrimitive id && id.isNumber()
                    && policy.get("transitPolicy") instanceof JsonObject && policies.stream()
                            .anyMatch(known -> Objects.equals(known.deviceTransitPolicyId, id.getAsLong()))) {
                account.updatePolicy(config.deviceId, policy);
                return true;
            }
        } catch (JsonParseException e) {
            logger.debug("Cannot read the door policy: {}", e.getMessage());
        }
        return false;
    }

    void deviceNotFound() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
            "@text/offline.config-error.unknown-flap");
    }

    void updateDevice(Device update) {
        device = update;
        updateStatus(ThingStatus.ONLINE);
        updateProperty(PROPERTY_TIME_ZONE, update.getZone().getId());
        updateProperty(PROPERTY_FIRMWARE_CHANNEL, update.firmwareChannel);
        publishDevice();
        publishPolicy();
    }

    void updatePolicies(List<DeviceTransitPolicy> update) {
        policies = update;
        stateDescriptionProvider.setStateOptions(new ChannelUID(getThing().getUID(), CHANNEL_DOOR_POLICY),
                update.stream().map(policy -> new StateOption(policy.getDisplayName(), policy.getDisplayName()))
                        .collect(Collectors.toList()));
        publishPolicy();
    }

    void updateEvent(DeviceEvent update) {
        EventSummary currentSummary = summary;
        if (currentSummary != null && !Objects.equals(currentSummary.eventId, update.eventId)) {
            summary = null;
            updateState(CHANNEL_EVENT_DIRECTION, UnDefType.UNDEF);
            updateState(CHANNEL_EVENT_ACTION, UnDefType.UNDEF);
        }
        event = update;
        publishEvent();
        publishLock();
        fetchImage(update);
    }

    void updateSummary(EventSummary update) {
        DeviceEvent current = event;
        if (current == null || !Objects.equals(current.eventId, update.eventId)) {
            return;
        }
        summary = update;
        publishSummary();
    }

    void updateDiagnostics(JsonArray rebootLog, @Nullable JsonObject telemetry) {
        updateState(CHANNEL_ERROR, OnOffType.from(containsError(rebootLog)));
        updateState(CHANNEL_REBOOT_LOG, new StringType(gson.toJson(rebootLog)));
        if (telemetry != null) {
            updateState(CHANNEL_TELEMETRY, new StringType(gson.toJson(telemetry)));
        }
    }

    /**
     * A planned reboot is logged like a crash, but explicitly flagged as not being an error.
     */
    static boolean containsError(JsonArray rebootLog) {
        for (JsonElement entry : rebootLog) {
            if (!(entry instanceof JsonObject log) || !(log.get("isError") instanceof JsonPrimitive isError)
                    || !isError.isBoolean() || isError.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private void publishAll() {
        publishDevice();
        publishPolicy();
        publishEvent();
        publishSummary();
    }

    private void publishDevice() {
        Device current = device;
        Device.Connectivity connectivity = current == null ? null : current.connectivity;
        if (connectivity == null) {
            return;
        }
        Boolean connected = connectivity.connected;
        updateState(CHANNEL_CONNECTED, connected == null ? UnDefType.UNDEF : OnOffType.from(connected));
        updateState(CHANNEL_DISCONNECT_REASON, stringState(connectivity.disconnectReason));
        Instant changed = connectivity.getTimestamp();
        updateState(CHANNEL_CONNECTIVITY_CHANGED, changed == null ? UnDefType.UNDEF : new DateTimeType(changed));
    }

    private @Nullable DeviceTransitPolicy activePolicy() {
        Device current = device;
        Long policyId = current == null ? null : current.deviceTransitPolicyId;
        return policyId == null ? null
                : policies.stream().filter(policy -> policyId.equals(policy.deviceTransitPolicyId)).findFirst()
                        .orElse(null);
    }

    private void publishPolicy() {
        DeviceTransitPolicy active = activePolicy();
        updateState(CHANNEL_DOOR_POLICY, active == null ? UnDefType.UNDEF : new StringType(active.getDisplayName()));
        JsonObject definition = active == null ? null : active.transitPolicy;
        updateState(CHANNEL_DOOR_POLICY_DEFINITION,
                definition == null ? UnDefType.UNDEF : new StringType(gson.toJson(definition)));
        publishLock();
    }

    private void publishLock() {
        updateState(CHANNEL_LOCKED, switch (lockVerdict()) {
            case LOCKED -> OnOffType.ON;
            case UNLOCKED -> OnOffType.OFF;
            case UNKNOWN -> UnDefType.UNDEF;
        });
    }

    private LockVerdict lockVerdict() {
        DeviceTransitPolicy active = activePolicy();
        JsonObject definition = active == null ? null : active.transitPolicy;
        Device current = device;
        if (definition == null || current == null) {
            return LockVerdict.UNKNOWN;
        }
        DoorPolicy policy = DoorPolicy.parse(definition);
        DeviceEvent currentEvent = event;
        if (currentEvent == null || currentEvent.isConcluded()) {
            return policy.idleState();
        }
        return policy.evaluate(currentEvent, current.getZone(),
                new DoorPolicy.Options(config.ignoreFlapStateRules, config.ignoreMotionSensorRules));
    }

    private void publishEvent() {
        DeviceEvent current = event;
        if (current == null) {
            return;
        }
        Long eventId = current.eventId;
        Integer classification = current.eventClassification;
        Instant timestamp = current.getTimestamp();
        List<String> codes = current.getRfidCodes();
        OnlyCatAccountHandler account = getAccount();

        updateState(CHANNEL_EVENT_ACTIVE, OnOffType.from(!current.isConcluded()));
        updateState(CHANNEL_EVENT_ID, eventId == null ? UnDefType.UNDEF : new DecimalType(eventId));
        updateState(CHANNEL_EVENT_TIME, timestamp == null ? UnDefType.UNDEF : new DateTimeType(timestamp));
        updateState(CHANNEL_EVENT_TRIGGER, stringState(triggerName(current.eventTriggerSource)));
        updateState(CHANNEL_EVENT_CLASSIFICATION, stringState(classificationName(classification)));
        updateState(CHANNEL_EVENT_RFID, new StringType(String.join(",", codes)));
        updateState(CHANNEL_EVENT_CAT, new StringType(codes.stream()
                .map(code -> account == null ? code : account.getCatName(code)).collect(Collectors.joining(", "))));
        updateState(CHANNEL_CONTRABAND,
                OnOffType.from(classification != null && classification == DeviceEvent.CLASSIFICATION_CONTRABAND));
        updateState(CHANNEL_HUMAN,
                OnOffType.from(classification != null && classification == DeviceEvent.CLASSIFICATION_HUMAN_ACTIVITY));
        String token = current.accessToken;
        updateState(CHANNEL_VIDEO_URL,
                token == null ? UnDefType.UNDEF
                        : new StringType("https://" + OnlyCatGateway.HOST + "/sharing/video/" + current.deviceId + "/"
                                + eventId + "?t=" + token));
    }

    private void publishSummary() {
        EventSummary current = summary;
        if (current == null) {
            return;
        }
        List<EventSummary.SubEvent> subEvents = current.getSubevents();
        EventSummary.SubEvent last = subEvents.isEmpty() ? null : subEvents.get(subEvents.size() - 1);
        updateState(CHANNEL_EVENT_DIRECTION, stringState(last == null ? null : last.direction));
        updateState(CHANNEL_EVENT_ACTION, stringState(last == null ? null : last.action));
    }

    static @Nullable String triggerName(@Nullable Integer source) {
        return source == null || source < 0 || source >= TRIGGER_NAMES.length ? null : TRIGGER_NAMES[source];
    }

    static @Nullable String classificationName(@Nullable Integer classification) {
        if (classification == null) {
            return null;
        }
        if (classification == CLASSIFICATION_REMOTE_UNLOCK) {
            return "REMOTE_UNLOCK";
        }
        return classification < 0 || classification >= CLASSIFICATION_NAMES.length ? null
                : CLASSIFICATION_NAMES[classification];
    }

    /**
     * Still frames are served without authentication. The frame that represents an event can change until the event
     * concludes, and the first attempt can be too early, so a frame is only remembered once it has been received.
     */
    private void fetchImage(DeviceEvent current) {
        if (!isLinked(CHANNEL_IMAGE) || current.eventId == null) {
            return;
        }
        String key = current.deviceId + "/" + current.eventId + "/" + current.getPosterFrame();
        if (key.equals(imageKey)) {
            return;
        }
        scheduler.execute(() -> {
            try {
                ContentResponse response = httpClient.newRequest("https://" + OnlyCatGateway.HOST + "/events/" + key)
                        .timeout(IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS).send();
                if (response.getStatus() == HttpStatus.OK_200 && response.getContent().length > 0) {
                    String type = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
                    imageKey = key;
                    updateState(CHANNEL_IMAGE, new RawType(response.getContent(),
                            type != null && type.startsWith("image/") ? type : DEFAULT_IMAGE_TYPE));
                } else {
                    logger.debug("Frame {} is not available: HTTP {}", key, response.getStatus());
                }
            } catch (TimeoutException | ExecutionException e) {
                logger.debug("Cannot fetch frame {}: {}", key, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static State stringState(@Nullable String value) {
        return value == null ? UnDefType.UNDEF : new StringType(value);
    }

    private @Nullable OnlyCatAccountHandler getAccount() {
        var bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof OnlyCatAccountHandler account ? account : null;
    }
}
