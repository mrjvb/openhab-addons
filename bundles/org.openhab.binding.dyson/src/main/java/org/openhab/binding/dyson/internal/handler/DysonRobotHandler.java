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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.openhab.binding.dyson.internal.DysonStateDescriptionProvider;
import org.openhab.binding.dyson.internal.device.DysonDeviceState.MessageType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateOption;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Handler for Dyson robot vacuums (360 Eye, 360 Heurist, 360 Vis Nav and similar).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonRobotHandler extends DysonBaseDeviceHandler {

    private enum Model {
        EYE_360,
        HEURIST,
        VIS_NAV
    }

    private final Logger logger = LoggerFactory.getLogger(DysonRobotHandler.class);
    private Model model = Model.EYE_360;

    public DysonRobotHandler(Thing thing, DysonStateDescriptionProvider stateDescriptionProvider) {
        super(thing, stateDescriptionProvider);
    }

    @Override
    protected boolean isRobot() {
        return true;
    }

    @Override
    protected void onCapabilitiesKnown() {
        Map<String, String> properties = getThing().getProperties();
        String type = properties.getOrDefault(PROPERTY_PRODUCT_TYPE, "");
        String name = (getThing().getLabel() + " " + properties.getOrDefault(Thing.PROPERTY_MODEL_ID, ""))
                .toLowerCase(Locale.ROOT);
        if ("277".equals(type) || name.contains("vis nav")
                || (capabilities.contains("Mapping") && capabilities.contains("DirectedCleaning"))) {
            model = Model.VIS_NAV;
        } else if ("276".equals(type) || name.contains("heurist") || capabilities.contains("Heat")) {
            model = Model.HEURIST;
        } else {
            model = Model.EYE_360;
        }
        List<StateOption> options = new ArrayList<>();
        getPowerOptions().forEach((value, label) -> options.add(new StateOption(value, label)));
        options.sort((a, b) -> a.getValue().compareTo(b.getValue()));
        stateDescriptionProvider.setStateOptions(new ChannelUID(getThing().getUID(), CHANNEL_ROBOT_POWER_MODE),
                options);
    }

    private Map<String, String> getPowerOptions() {
        return switch (model) {
            case VIS_NAV -> ROBOT_POWER_VIS_NAV;
            case HEURIST -> ROBOT_POWER_HEURIST;
            default -> ROBOT_POWER_360_EYE;
        };
    }

    @Override
    protected void sendHeartbeat() throws MqttException {
        sendRequest("REQUEST-CURRENT-STATE");
    }

    // ---- State ----

    @Override
    protected void updateChannels(MessageType type) {
        if (type == MessageType.ENVIRONMENT || type == MessageType.FAULTS) {
            return;
        }
        String robotState = state.getTopLevelString("state");
        updateChannel(CHANNEL_ROBOT_STATE, DysonChannelUtil.string(robotState));
        String activity = null;
        if (robotState != null) {
            activity = robotState.startsWith("FAULT_") ? "ERROR" : ROBOT_STATE_TO_ACTIVITY.get(robotState);
            updateChannel(CHANNEL_ROBOT_CHARGING, OnOffType.from(ROBOT_CHARGING_STATES.contains(robotState)));
        }
        updateChannel(CHANNEL_ROBOT_ACTIVITY, activity != null ? new StringType(activity) : UnDefType.UNDEF);

        String battery = state.getTopLevelString("batteryChargeLevel");
        if (battery != null) {
            updateChannel(CHANNEL_ROBOT_BATTERY, DysonChannelUtil.quantity(battery, 1, Units.PERCENT));
        }
        String power = state.getTopLevelString("currentVacuumPowerMode");
        if (power == null) {
            power = state.getTopLevelString("fPwr");
        }
        if (power != null) {
            updateChannel(CHANNEL_ROBOT_POWER_MODE, new StringType(power));
        }
        updateOptionalString(CHANNEL_ROBOT_CLEAN_TYPE, "fullCleanType");
        updateOptionalString(CHANNEL_ROBOT_CLEAN_ID, "cleanId");
        updateOptionalString(CHANNEL_ROBOT_DOCK_STATE, "dockState");
        String duration = state.getTopLevelString("cleanDuration");
        if (duration != null) {
            updateChannel(CHANNEL_ROBOT_CLEAN_DURATION, DysonChannelUtil.quantity(duration, 1, Units.SECOND));
        }
        String position = getPosition();
        if (position != null) {
            updateChannel(CHANNEL_ROBOT_POSITION, new StringType(position));
        }
        updateFaults();
        updateConsumables();
    }

    private void updateOptionalString(String channelId, String key) {
        String value = state.getTopLevelString(key);
        if (value != null) {
            updateChannel(channelId, new StringType(value));
        }
    }

    private @Nullable String getPosition() {
        JsonElement position = state.getTopLevel("globalPosition");
        if (!(position instanceof JsonArray array) || array.isEmpty()) {
            return null;
        }
        JsonElement last = array.get(array.size() - 1);
        if (last instanceof JsonObject point && point.has("x") && point.has("y")) {
            // Spot+Scrub: list of points in metres
            return point.get("x").getAsString() + "," + point.get("y").getAsString();
        }
        if (array.size() == 2 && array.get(0).isJsonPrimitive() && array.get(1).isJsonPrimitive()) {
            return array.get(0).getAsString() + "," + array.get(1).getAsString();
        }
        return null;
    }

    private void updateFaults() {
        JsonElement activeFaults = state.getTopLevel("newActiveFaults");
        if (!(activeFaults instanceof JsonArray)) {
            activeFaults = state.getTopLevel("activeFaults");
        }
        if (activeFaults instanceof JsonArray faults) {
            String chosen = null;
            boolean actionRequired = false;
            for (JsonElement element : faults) {
                if (element instanceof JsonObject fault && fault.has("faultCode")) {
                    String nextAction = fault.has("nextActionRequired") ? fault.get("nextActionRequired").getAsString()
                            : "";
                    if (!"LOG_ONLY".equals(nextAction)) {
                        actionRequired = true;
                        if (chosen == null) {
                            String code = fault.get("faultCode").getAsString();
                            chosen = ROBOT_NUMERIC_FAULT_NAMES.getOrDefault(code, code);
                        }
                    }
                }
            }
            updateChannel(CHANNEL_ROBOT_ACTIVE_FAULT, new StringType(chosen != null ? chosen : "none"));
            updateChannel(CHANNEL_ROBOT_ACTION_REQUIRED, OnOffType.from(actionRequired));
        }

        JsonElement subsystemFaults = state.getTopLevel("faults");
        if (subsystemFaults instanceof JsonObject faults) {
            ROBOT_FAULT_SUBSYSTEMS.forEach((subsystem, channelSuffix) -> {
                JsonElement entry = faults.get(subsystem);
                boolean active = entry instanceof JsonObject object && object.has("active")
                        && object.get("active").getAsBoolean();
                updateChannel(CHANNEL_ROBOT_FAULT_PREFIX + channelSuffix, OnOffType.from(active));
            });
        }
    }

    private void updateConsumables() {
        JsonElement consumables = state.getTopLevel("consumables");
        if (!(consumables instanceof JsonArray list)) {
            return;
        }
        for (JsonElement element : list) {
            if (!(element instanceof JsonObject consumable) || !consumable.has("type")) {
                continue;
            }
            String type = consumable.get("type").getAsString();
            if ("cleaningSolution".equals(type) && consumable.has("needsRefill")) {
                updateChannel(CHANNEL_ROBOT_CLEANING_SOLUTION_REFILL,
                        OnOffType.from(consumable.get("needsRefill").getAsBoolean()));
                continue;
            }
            String channelId = ROBOT_CONSUMABLE_CHANNELS.get(type);
            if (channelId != null && consumable.has("usage") && consumable.get("usage").isJsonPrimitive()) {
                int usage = consumable.get("usage").getAsInt();
                // The app shows 100 - usage as remaining life; -1 means not applicable
                updateChannel(channelId, usage < 0 ? UnDefType.UNDEF
                        : new QuantityType<>(Math.max(0, Math.min(100, 100 - usage)), Units.PERCENT));
            }
        }
    }

    // ---- Commands ----

    @Override
    protected void handleDeviceCommand(String channelId, Command command) throws MqttException {
        switch (channelId) {
            case CHANNEL_ROBOT_COMMAND:
                handleRobotCommand(command.toString().toUpperCase(Locale.ROOT));
                break;
            case CHANNEL_ROBOT_POWER_MODE:
                setPowerMode(command.toString());
                break;
            default:
                logger.debug("Unsupported command {} for channel {}", command, channelId);
                break;
        }
    }

    private void handleRobotCommand(String command) throws MqttException {
        String robotState = state.getTopLevelString("state");
        JsonObject message = new JsonObject();
        switch (command) {
            case "START":
                if (robotState != null && robotState.endsWith("_PAUSED")) {
                    message.addProperty("msg", "RESUME");
                } else {
                    message.addProperty("msg", "START");
                    message.addProperty("cleaningMode", "global");
                    message.addProperty("fullCleanType", "immediate");
                    message.addProperty("mode-reason", "LAPP");
                }
                break;
            case "PAUSE":
            case "RESUME":
            case "ABORT":
                message.addProperty("msg", command);
                break;
            case "DOCK":
                message.addProperty("msg", "ABORT");
                break;
            default:
                logger.debug("Unknown robot command {}", command);
                return;
        }
        message.addProperty("time", timestamp());
        publish(message);
        updateChannel(CHANNEL_ROBOT_COMMAND, UnDefType.NULL);
    }

    private void setPowerMode(String value) throws MqttException {
        if (!getPowerOptions().containsKey(value)) {
            logger.debug("Unknown power mode {} for this robot, valid values are {}", value,
                    getPowerOptions().keySet());
            return;
        }
        JsonObject data = new JsonObject();
        if (model == Model.EYE_360) {
            data.addProperty("fPwr", value);
        } else {
            data.addProperty("fPwr", Integer.parseInt(value));
        }
        JsonObject message = new JsonObject();
        message.addProperty("msg", "STATE-SET");
        message.addProperty("time", timestamp());
        message.add("data", data);
        publish(message);
        updateChannel(CHANNEL_ROBOT_POWER_MODE, new StringType(value));
    }

    // ---- Channel pruning ----

    @Override
    protected Set<String> getUnsupportedChannels() {
        Set<String> unsupported = new HashSet<>();
        Set<String> reportedConsumables = new HashSet<>();
        JsonElement consumables = state.getTopLevel("consumables");
        if (consumables instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject consumable && consumable.has("type")) {
                    reportedConsumables.add(consumable.get("type").getAsString());
                }
            }
        }
        ROBOT_CONSUMABLE_CHANNELS.forEach((type, channelId) -> {
            if (!reportedConsumables.contains(type)) {
                unsupported.add(channelId);
            }
        });
        if (!reportedConsumables.contains("cleaningSolution")) {
            unsupported.add(CHANNEL_ROBOT_CLEANING_SOLUTION_REFILL);
        }
        if (!state.hasTopLevel("dockState")) {
            unsupported.add(CHANNEL_ROBOT_DOCK_STATE);
        }
        if (getAccountHandler() == null) {
            unsupported.addAll(Set.of(CHANNEL_FIRMWARE_UPDATE_AVAILABLE, CHANNEL_FIRMWARE_LATEST_VERSION,
                    CHANNEL_FIRMWARE_AUTO_UPDATE, CHANNEL_FIRMWARE_UPDATE));
        }
        return unsupported;
    }
}
