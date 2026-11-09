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
import static org.openhab.binding.dyson.internal.device.DysonOscillation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.openhab.binding.dyson.internal.DysonStateDescriptionProvider;
import org.openhab.binding.dyson.internal.device.DysonAirQuality;
import org.openhab.binding.dyson.internal.device.DysonDeviceState.MessageType;
import org.openhab.binding.dyson.internal.device.DysonOscillation;
import org.openhab.binding.dyson.internal.device.DysonOscillation.Angles;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateOption;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Dyson environment cleaners: purifiers, fans, heaters (Hot+Cool) and humidifiers.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonFanHandler extends DysonBaseDeviceHandler {
    private static final int LEGACY_FILTER_LIFE_MAX_HOURS = 4300;
    private static final Map<String, String> WATER_HARDNESS = Map.of("2025", "SOFT", "1350", "MEDIUM", "0675", "HARD");
    private static final Map<String, String> TILT_ANGLES = Map.of("0000", "0", "0025", "25", "0050", "50");

    private final Logger logger = LoggerFactory.getLogger(DysonFanHandler.class);

    public DysonFanHandler(Thing thing, DysonStateDescriptionProvider stateDescriptionProvider) {
        super(thing, stateDescriptionProvider);
    }

    @Override
    protected boolean isRobot() {
        return false;
    }

    private boolean isDay0() {
        return capabilities.contains(CAPABILITY_ADVANCE_OSCILLATION_DAY0)
                && !capabilities.contains(CAPABILITY_ADVANCE_OSCILLATION_DAY1);
    }

    private int angleMin() {
        return isDay0() ? DAY0_MIN : DAY1_MIN;
    }

    private int angleMax() {
        return isDay0() ? DAY0_MAX : DAY1_MAX;
    }

    @Override
    protected void onCapabilitiesKnown() {
        List<StateOption> options = new ArrayList<>();
        if (isDay0()) {
            options.add(new StateOption("OFF", "Off"));
            for (String preset : List.of("15", "40", "70")) {
                options.add(new StateOption(preset, preset + "°"));
            }
        } else {
            for (String preset : List.of("45", "90", "180", "350")) {
                options.add(new StateOption(preset, preset + "°"));
            }
            if (capabilities.contains(CAPABILITY_HUMIDIFIER)) {
                options.add(new StateOption("BREEZE", "Breeze"));
            }
            options.add(new StateOption("CUSTOM", "Custom"));
        }
        stateDescriptionProvider.setStateOptions(new ChannelUID(getThing().getUID(), CHANNEL_OSCILLATION_MODE),
                options);
    }

    @Override
    protected void sendHeartbeat() throws MqttException {
        super.sendHeartbeat();
        sendRequest("REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA");
    }

    // ---- State ----

    @Override
    protected void updateChannels(MessageType type) {
        if (type != MessageType.ENVIRONMENT && type != MessageType.FAULTS) {
            updateProductStateChannels();
        }
        if (type == MessageType.ENVIRONMENT || type == MessageType.OTHER) {
            updateEnvironmentChannels();
        }
        if (type == MessageType.FAULTS || type == MessageType.OTHER) {
            updateFaultChannels();
        }
    }

    private void updateProductStateChannels() {
        updateChannel(CHANNEL_POWER, getPower());
        String fnsp = state.get("fnsp");
        updateChannel(CHANNEL_FAN_SPEED,
                "AUTO".equals(fnsp) ? DysonChannelUtil.decimal(state.get("nmdv")) : DysonChannelUtil.decimal(fnsp));
        if (state.has("auto") || state.has("fmod")) {
            updateChannel(CHANNEL_AUTO_MODE,
                    OnOffType.from("AUTO".equals(state.get("fmod")) || "ON".equals(state.get("auto"))));
        }
        updateChannel(CHANNEL_NIGHT_MODE, DysonChannelUtil.onOff(state.get("nmod"), "ON"));
        updateChannel(CHANNEL_OSCILLATION, DysonChannelUtil.onOff(state.get("oson"), "ON", "OION"));
        updateOscillationChannels();
        updateChannel(CHANNEL_TILT_OSCILLATION, getTiltOscillation());
        String sltm = state.get("sltm");
        updateChannel(CHANNEL_SLEEP_TIMER, "OFF".equals(sltm) ? new QuantityType<>(0, Units.MINUTE)
                : DysonChannelUtil.quantity(sltm, 1, Units.MINUTE));
        updateChannel(CHANNEL_CONTINUOUS_MONITORING, DysonChannelUtil.onOff(state.get("rhtm"), "ON"));
        updateChannel(CHANNEL_FRONT_AIRFLOW, DysonChannelUtil.onOff(state.get("fdir"), "ON"));
        updateChannel(CHANNEL_FOCUS_MODE, DysonChannelUtil.onOff(state.get("ffoc"), "ON"));
        updateChannel(CHANNEL_FIND_FOLLOW, DysonChannelUtil.onOff(state.get("soon"), "ON", "SCAN"));
        updateChannel(CHANNEL_FIND_FOLLOW_STATUS, DysonChannelUtil.string(state.get("sost")));

        // Heating
        updateChannel(CHANNEL_HEATING_MODE, DysonChannelUtil.string(state.get("hmod")));
        BigDecimal target = DysonChannelUtil.deciKelvinToCelsius(state.get("hmax"));
        updateChannel(CHANNEL_TARGET_TEMPERATURE,
                target != null ? new QuantityType<>(target, SIUnits.CELSIUS) : UnDefType.UNDEF);
        updateChannel(CHANNEL_HEATING_ACTIVE, DysonChannelUtil.onOff(state.get("hsta"), "HEAT"));

        // Humidifier
        updateChannel(CHANNEL_HUMIDIFIER, DysonChannelUtil.onOff(state.get("hume"), "HUMD"));
        updateChannel(CHANNEL_HUMIDIFIER_AUTO, DysonChannelUtil.onOff(state.get("haut"), "ON"));
        updateChannel(CHANNEL_TARGET_HUMIDITY, DysonChannelUtil.quantity(state.get("humt"), 1, Units.PERCENT));
        String wath = state.get("wath");
        String hardness = wath != null ? WATER_HARDNESS.get(wath) : null;
        updateChannel(CHANNEL_WATER_HARDNESS, hardness != null ? new StringType(hardness) : UnDefType.UNDEF);
        updateChannel(CHANNEL_NEXT_DEEP_CLEAN, DysonChannelUtil.quantity(state.get("cltr"), 1, Units.HOUR));
        updateChannel(CHANNEL_DEEP_CLEAN_REMAINING, DysonChannelUtil.quantity(state.get("cdrr"), 1, Units.MINUTE));

        // Filters
        Integer hepaLife = getHepaFilterLife();
        updateChannel(CHANNEL_HEPA_FILTER_LIFE,
                hepaLife != null ? new QuantityType<>(hepaLife, Units.PERCENT) : UnDefType.UNDEF);
        updateChannel(CHANNEL_HEPA_FILTER_TYPE, DysonChannelUtil.string(state.get("hflt")));
        updateChannel(CHANNEL_CARBON_FILTER_LIFE, DysonChannelUtil.quantity(state.get("cflr"), 1, Units.PERCENT));
        updateChannel(CHANNEL_CARBON_FILTER_TYPE, DysonChannelUtil.string(state.get("cflt")));
        updateChannel(CHANNEL_FILTER_REPLACEMENT,
                hasHepaFilter() && hepaLife != null ? OnOffType.from(hepaLife <= 10) : UnDefType.UNDEF);
    }

    private State getPower() {
        if ("fmod".equals(state.getPowerControlKey())) {
            return DysonChannelUtil.onOff(state.get("fmod"), "FAN", "AUTO");
        }
        String fpwr = state.get("fpwr");
        if (fpwr != null) {
            return OnOffType.from("ON".equals(fpwr));
        }
        return DysonChannelUtil.onOff(state.get("fnst"), "FAN");
    }

    private Angles getAngles() {
        int lower = DysonOscillation.parse(state.get("osal", ""), angleMin());
        int upper = DysonOscillation.parse(state.get("osau", ""), angleMax());
        return new Angles(lower, Math.max(lower, upper));
    }

    private void updateOscillationChannels() {
        if (state.has("osal") && state.has("osau")) {
            Angles angles = getAngles();
            updateChannel(CHANNEL_OSCILLATION_LOWER_ANGLE, new QuantityType<>(angles.lower(), Units.DEGREE_ANGLE));
            updateChannel(CHANNEL_OSCILLATION_UPPER_ANGLE, new QuantityType<>(angles.upper(), Units.DEGREE_ANGLE));
            updateChannel(CHANNEL_OSCILLATION_CENTER_ANGLE, new QuantityType<>(angles.center(), Units.DEGREE_ANGLE));
            updateChannel(CHANNEL_OSCILLATION_SPAN, new QuantityType<>(angles.span(), Units.DEGREE_ANGLE));
        }
        updateChannel(CHANNEL_OSCILLATION_MODE, getOscillationMode());
    }

    private State getOscillationMode() {
        String ancp = state.get("ancp", "");
        if (isDay0()) {
            if (!"ON".equals(state.get("oson")) && !"OION".equals(state.get("oson"))) {
                return new StringType("OFF");
            }
            return switch (ancp) {
                case "0015" -> new StringType("15");
                case "0070" -> new StringType("70");
                default -> new StringType("40");
            };
        }
        if ("BRZE".equals(ancp)) {
            return new StringType("BREEZE");
        }
        switch (ancp) {
            case "0045":
                return new StringType("45");
            case "0090":
                return new StringType("90");
            case "0180":
                return new StringType("180");
            case "0350":
                return new StringType("350");
            default:
                break;
        }
        if (!state.has("osal") || !state.has("osau")) {
            return UnDefType.UNDEF;
        }
        // Custom angles: snap to a preset if the span matches one, as the Dyson app does
        int span = getAngles().span();
        return switch (span) {
            case 45, 90, 180, 350 -> new StringType(String.valueOf(span));
            default -> new StringType("CUSTOM");
        };
    }

    private State getTiltOscillation() {
        if (!state.has("oton") && !state.has("anct")) {
            return UnDefType.UNDEF;
        }
        if ("BRZE".equals(state.get("anct"))) {
            return new StringType("BREEZE");
        }
        String otal = state.get("otal");
        String tilt = otal != null ? TILT_ANGLES.get(otal) : null;
        return tilt != null ? new StringType(tilt) : UnDefType.UNDEF;
    }

    private boolean hasHepaFilter() {
        String hflt = state.get("hflt");
        return (hflt != null && !"NONE".equals(hflt)) || (hflt == null && state.has("filf"));
    }

    private @Nullable Integer getHepaFilterLife() {
        String hflt = state.get("hflt");
        String cflt = state.get("cflt");
        if ("GCOM".equals(hflt) || "GCOM".equals(cflt)) {
            BigDecimal fflr = DysonChannelUtil.number(state.get("fflr"));
            if (fflr != null) {
                return fflr.intValue();
            }
        }
        String hflr = state.get("hflr");
        if (hflr != null) {
            BigDecimal value = DysonChannelUtil.number(hflr);
            return value != null ? value.intValue() : null;
        }
        BigDecimal legacyHours = DysonChannelUtil.number(state.get("filf"));
        if (legacyHours == null) {
            return null;
        }
        int hours = Math.max(0, Math.min(LEGACY_FILTER_LIFE_MAX_HOURS, legacyHours.intValue()));
        return hours * 100 / LEGACY_FILTER_LIFE_MAX_HOURS;
    }

    private void updateEnvironmentChannels() {
        BigDecimal temperature = DysonChannelUtil.deciKelvinToCelsius(state.getEnvironment("tact"));
        updateChannel(CHANNEL_TEMPERATURE,
                temperature != null ? new QuantityType<>(temperature, SIUnits.CELSIUS) : UnDefType.UNDEF);
        updateChannel(CHANNEL_HUMIDITY, DysonChannelUtil.quantity(state.getEnvironment("hact"), 1, Units.PERCENT));
        updateChannel(CHANNEL_PM25, density(firstEnvironment("p25r", "pm25")));
        updateChannel(CHANNEL_PM10, density(firstEnvironment("p10r", "pm10")));
        updateChannel(CHANNEL_PARTICULATES, density(state.getEnvironment("pact")));
        // VOC and formaldehyde are reported in µg/m³ (the Dyson app shows them divided by 1000 as mg/m³)
        updateChannel(CHANNEL_VOC, density(firstEnvironment("va10", "vact")));
        updateChannel(CHANNEL_NO2, density(state.getEnvironment("noxl")));
        updateChannel(CHANNEL_CO2, DysonChannelUtil.quantity(state.getEnvironment("co2r"), 1, Units.PARTS_PER_MILLION));
        updateChannel(CHANNEL_FORMALDEHYDE, density(firstEnvironment("hchr", "hcho")));

        DysonAirQuality.Result aqi = DysonAirQuality.calculate(state.getEnvironment());
        if (aqi != null) {
            updateChannel(CHANNEL_AQI, new DecimalType(aqi.index()));
            updateChannel(CHANNEL_AQI_CATEGORY, new StringType(aqi.category()));
            updateChannel(CHANNEL_DOMINANT_POLLUTANT, new StringType(String.join(", ", aqi.dominantPollutants())));
        } else {
            updateChannel(CHANNEL_AQI, UnDefType.UNDEF);
            updateChannel(CHANNEL_AQI_CATEGORY, UnDefType.UNDEF);
            updateChannel(CHANNEL_DOMINANT_POLLUTANT, UnDefType.UNDEF);
        }
    }

    /**
     * Returns the first environment value that is a number, skipping keys that report e.g. NONE.
     */
    private @Nullable String firstEnvironment(String... keys) {
        String fallback = null;
        for (String key : keys) {
            String value = state.getEnvironment(key);
            if (value != null) {
                if (DysonChannelUtil.number(value) != null) {
                    return value;
                }
                fallback = value;
            }
        }
        return fallback;
    }

    private static State density(@Nullable String raw) {
        return DysonChannelUtil.quantity(raw, 1, Units.MICROGRAM_PER_CUBICMETRE);
    }

    private void updateFaultChannels() {
        Map<String, String> faults = state.getFaults();
        List<String> active = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : FAULT_TRANSLATIONS.entrySet()) {
            String code = entry.getKey();
            String value = faults.get(code);
            boolean isFault = value != null && !FAULT_OK_VALUES.contains(value.toUpperCase(Locale.ROOT));
            if (isFault) {
                active.add(entry.getValue().getOrDefault(value, code.toUpperCase(Locale.ROOT) + ": " + value));
            }
            if (state.isFaultsReceived()) {
                updateChannel(CHANNEL_FAULT_PREFIX + code, OnOffType.from(isFault));
            }
        }
        if (state.isFaultsReceived()) {
            updateChannel(CHANNEL_FAULT, OnOffType.from(!active.isEmpty()));
            updateChannel(CHANNEL_ACTIVE_FAULTS, new StringType(active.isEmpty() ? "" : String.join("; ", active)));
            if (!active.isEmpty()) {
                logger.debug("Dyson device {} reports faults: {}", config.serialNumber, active);
            }
        }
    }

    // ---- Commands ----

    @Override
    protected void handleDeviceCommand(String channelId, Command command) throws MqttException {
        switch (channelId) {
            case CHANNEL_POWER:
                if (command instanceof OnOffType onOff) {
                    setPower(onOff == OnOffType.ON);
                }
                break;
            case CHANNEL_FAN_SPEED:
                if (command instanceof DecimalType || command instanceof QuantityType<?>) {
                    setFanSpeed(((Number) command).intValue());
                } else if (command instanceof OnOffType onOff) {
                    setPower(onOff == OnOffType.ON);
                }
                break;
            case CHANNEL_AUTO_MODE:
                if (command instanceof OnOffType onOff) {
                    boolean on = onOff == OnOffType.ON;
                    if ("fmod".equals(state.getPowerControlKey())) {
                        sendStateSet("fmod", on ? "AUTO" : "FAN");
                    } else {
                        sendStateSet("auto", on ? "ON" : "OFF");
                    }
                }
                break;
            case CHANNEL_NIGHT_MODE:
                sendOnOff(command, "nmod", "ON", "OFF");
                break;
            case CHANNEL_OSCILLATION:
                sendOnOff(command, "oson", "ON", "OFF");
                break;
            case CHANNEL_OSCILLATION_MODE:
                setOscillationMode(command.toString());
                break;
            case CHANNEL_OSCILLATION_LOWER_ANGLE:
            case CHANNEL_OSCILLATION_UPPER_ANGLE:
            case CHANNEL_OSCILLATION_CENTER_ANGLE:
            case CHANNEL_OSCILLATION_SPAN:
                Integer angle = toAngle(command);
                if (angle != null) {
                    setOscillationAngle(channelId, angle);
                }
                break;
            case CHANNEL_TILT_OSCILLATION:
                setTiltOscillation(command.toString());
                break;
            case CHANNEL_SLEEP_TIMER:
                Integer minutes = toMinutes(command);
                if (minutes != null) {
                    sendStateSet("sltm", minutes <= 0 ? "OFF"
                            : String.format(Locale.ROOT, "%04d", Math.max(15, Math.min(540, minutes))));
                }
                break;
            case CHANNEL_CONTINUOUS_MONITORING:
                if (command instanceof OnOffType onOff) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("rhtm", onOff == OnOffType.ON ? "ON" : "OFF");
                    String fmod = state.get("fmod");
                    if (fmod != null) {
                        // Link devices require fmod alongside rhtm
                        data.put("fmod", fmod);
                    }
                    sendStateSet(data);
                }
                break;
            case CHANNEL_FRONT_AIRFLOW:
                sendOnOff(command, "fdir", "ON", "OFF");
                break;
            case CHANNEL_FOCUS_MODE:
                sendOnOff(command, "ffoc", "ON", "OFF");
                break;
            case CHANNEL_FIND_FOLLOW:
                sendOnOff(command, "soon", "ON", "OFF");
                break;
            case CHANNEL_FIND_FOLLOW_SCAN:
                if (command == OnOffType.ON) {
                    sendStateSet("soon", "SCAN");
                    updateChannel(channelId, OnOffType.OFF);
                }
                break;
            case CHANNEL_HEATING_MODE:
                String mode = command.toString().toUpperCase(Locale.ROOT);
                if (Set.of("OFF", "HEAT", "AUTO").contains(mode)) {
                    sendStateSet("hmod", mode);
                }
                break;
            case CHANNEL_TARGET_TEMPERATURE:
                setTargetTemperature(command);
                break;
            case CHANNEL_HUMIDIFIER:
                if (command instanceof OnOffType onOff) {
                    sendStateSet(Map.of("hume", onOff == OnOffType.ON ? "HUMD" : "OFF", "haut", "OFF"));
                }
                break;
            case CHANNEL_HUMIDIFIER_AUTO:
                if (command instanceof OnOffType onOff) {
                    sendStateSet(Map.of("hume", "HUMD", "haut", onOff == OnOffType.ON ? "ON" : "OFF"));
                }
                break;
            case CHANNEL_TARGET_HUMIDITY:
                if (command instanceof Number number) {
                    int humidity = (int) Math.round(number.doubleValue() / 10.0) * 10;
                    humidity = Math.max(30, Math.min(70, humidity));
                    sendStateSet("humt", String.format(Locale.ROOT, "%04d", humidity));
                }
                break;
            case CHANNEL_WATER_HARDNESS:
                String value = switch (command.toString().toUpperCase(Locale.ROOT)) {
                    case "SOFT" -> "2025";
                    case "MEDIUM" -> "1350";
                    case "HARD" -> "0675";
                    default -> null;
                };
                if (value != null) {
                    sendStateSet("wath", value);
                }
                break;
            case CHANNEL_RESET_HEPA_FILTER:
                if (command == OnOffType.ON) {
                    if (!state.has("hflr") && !state.has("fflr") && state.has("filf")) {
                        sendStateSet("rstf", "RSTF");
                    } else {
                        sendStateSet("hflr", "0100");
                    }
                    updateChannel(channelId, OnOffType.OFF);
                }
                break;
            case CHANNEL_RESET_CARBON_FILTER:
                if (command == OnOffType.ON) {
                    sendStateSet("cflr", "0100");
                    updateChannel(channelId, OnOffType.OFF);
                }
                break;
            default:
                logger.debug("Unsupported command {} for channel {}", command, channelId);
                break;
        }
    }

    private void sendOnOff(Command command, String key, String on, String off) throws MqttException {
        if (command instanceof OnOffType onOff) {
            sendStateSet(key, onOff == OnOffType.ON ? on : off);
        }
    }

    private void setPower(boolean on) throws MqttException {
        if ("fmod".equals(state.getPowerControlKey())) {
            sendStateSet("fmod", on ? "FAN" : "OFF");
        } else {
            sendStateSet("fpwr", on ? "ON" : "OFF");
        }
    }

    private void setFanSpeed(int speed) throws MqttException {
        if (speed <= 0) {
            setPower(false);
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fnsp", String.format(Locale.ROOT, "%04d", Math.min(10, speed)));
        if ("fmod".equals(state.getPowerControlKey())) {
            // Selecting a speed switches Link devices to manual fan mode
            data.put("fmod", "FAN");
        } else {
            if (!"ON".equals(state.get("fpwr"))) {
                data.put("fpwr", "ON");
            }
            if ("ON".equals(state.get("auto"))) {
                data.put("auto", "OFF");
            }
        }
        sendStateSet(data);
    }

    private void setOscillationMode(String option) throws MqttException {
        String mode = option.toUpperCase(Locale.ROOT);
        Map<String, String> data = new LinkedHashMap<>();
        if (isDay0()) {
            if ("OFF".equals(mode)) {
                sendStateSet("oson", "OFF");
                return;
            }
            if (!Set.of("15", "40", "70").contains(mode)) {
                return;
            }
            data.put("osal", format(DAY0_PRESET_LOWER));
            data.put("osau", format(DAY0_PRESET_UPPER));
            data.put("ancp", format(Integer.parseInt(mode)));
            data.put("oson", "ON");
            sendStateSet(data);
            return;
        }
        switch (mode) {
            case "BREEZE":
                data.put("ancp", "BRZE");
                data.put("oson", "ON");
                break;
            case "CUSTOM":
                Angles current = getAngles();
                data.put("osal", format(current.lower()));
                data.put("osau", format(current.upper()));
                data.put("ancp", "CUST");
                break;
            case "45":
            case "90":
            case "180":
            case "350":
                int span = Integer.parseInt(mode);
                Angles angles = DysonOscillation.forPreset(span, getAngles().center(), DAY1_MIN, DAY1_MAX);
                data.put("ancp", format(span));
                data.put("oson", "ON");
                data.put("osal", format(angles.lower()));
                data.put("osau", format(angles.upper()));
                break;
            default:
                return;
        }
        sendStateSet(data);
    }

    private void setOscillationAngle(String channelId, int angle) throws MqttException {
        Angles current = getAngles();
        int min = angleMin();
        int max = angleMax();
        Angles target = switch (channelId) {
            case CHANNEL_OSCILLATION_LOWER_ANGLE ->
                new Angles(Math.max(min, Math.min(angle, current.upper())), current.upper());
            case CHANNEL_OSCILLATION_UPPER_ANGLE ->
                new Angles(current.lower(), Math.min(max, Math.max(angle, current.lower())));
            case CHANNEL_OSCILLATION_CENTER_ANGLE -> DysonOscillation.withCenter(current, angle, min, max);
            default -> DysonOscillation.withSpan(current, angle, min, max);
        };
        Map<String, String> data = new LinkedHashMap<>();
        data.put("osal", format(target.lower()));
        data.put("osau", format(target.upper()));
        if (!isDay0()) {
            // CUST makes the device respect the explicit angles instead of its own preset position
            data.put("ancp", "CUST");
            data.put("oson", "ON");
        }
        sendStateSet(data);
    }

    private void setTiltOscillation(String option) throws MqttException {
        Map<String, String> data = new LinkedHashMap<>();
        switch (option.toUpperCase(Locale.ROOT)) {
            case "0":
                data.put("anct", "CUST");
                data.put("otal", "0000");
                data.put("otau", "0000");
                break;
            case "25":
                data.put("anct", "CUST");
                data.put("otal", "0025");
                data.put("otau", "0025");
                break;
            case "50":
                data.put("anct", "CUST");
                data.put("otal", "0050");
                data.put("otau", "0050");
                break;
            case "BREEZE":
                data.put("oton", "ON");
                data.put("anct", "BRZE");
                data.put("otal", "0359");
                data.put("otau", "0359");
                break;
            default:
                return;
        }
        sendStateSet(data);
    }

    private void setTargetTemperature(Command command) throws MqttException {
        double celsius;
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> inCelsius = quantity.toUnit(SIUnits.CELSIUS);
            if (inCelsius == null) {
                return;
            }
            celsius = inCelsius.doubleValue();
        } else if (command instanceof DecimalType decimal) {
            celsius = decimal.doubleValue();
        } else {
            return;
        }
        celsius = Math.max(1, Math.min(37, celsius));
        sendStateSet(Map.of("hmod", "HEAT", "hmax", DysonChannelUtil.celsiusToDeciKelvin(celsius)));
    }

    private static @Nullable Integer toAngle(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> degrees = quantity.toUnit(Units.DEGREE_ANGLE);
            return degrees != null ? degrees.intValue() : null;
        } else if (command instanceof DecimalType decimal) {
            return decimal.intValue();
        }
        return null;
    }

    private static @Nullable Integer toMinutes(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> minutes = quantity.toUnit(Units.MINUTE);
            return minutes != null ? minutes.intValue() : null;
        } else if (command instanceof DecimalType decimal) {
            return decimal.intValue();
        } else if (command == OnOffType.OFF) {
            return 0;
        }
        return null;
    }

    // ---- Channel pruning ----

    @Override
    protected Set<String> getUnsupportedChannels() {
        Set<String> unsupported = new HashSet<>();
        removeUnless(unsupported, state.has("auto") || state.has("fmod"), CHANNEL_AUTO_MODE);
        removeUnless(unsupported, state.has("nmod"), CHANNEL_NIGHT_MODE);
        removeUnless(unsupported, state.has("oson"), CHANNEL_OSCILLATION);
        boolean advancedOscillation = capabilities.contains(CAPABILITY_ADVANCE_OSCILLATION_DAY0)
                || capabilities.contains(CAPABILITY_ADVANCE_OSCILLATION_DAY1)
                || (capabilities.isEmpty() && state.has("ancp"));
        removeUnless(unsupported, advancedOscillation, CHANNEL_OSCILLATION_MODE);
        removeUnless(unsupported, advancedOscillation && state.has("osal") && state.has("osau"),
                CHANNEL_OSCILLATION_LOWER_ANGLE, CHANNEL_OSCILLATION_UPPER_ANGLE, CHANNEL_OSCILLATION_CENTER_ANGLE,
                CHANNEL_OSCILLATION_SPAN);
        removeUnless(unsupported, state.has("oton"), CHANNEL_TILT_OSCILLATION);
        removeUnless(unsupported, state.has("sltm"), CHANNEL_SLEEP_TIMER);
        removeUnless(unsupported, state.has("rhtm"), CHANNEL_CONTINUOUS_MONITORING);
        removeUnless(unsupported, state.has("fdir"), CHANNEL_FRONT_AIRFLOW);
        removeUnless(unsupported, state.has("ffoc"), CHANNEL_FOCUS_MODE);
        removeUnless(unsupported, state.has("soon"), CHANNEL_FIND_FOLLOW, CHANNEL_FIND_FOLLOW_SCAN);
        removeUnless(unsupported, state.has("sost"), CHANNEL_FIND_FOLLOW_STATUS);
        removeUnless(unsupported, state.has("hmod"), CHANNEL_HEATING_MODE);
        removeUnless(unsupported, state.has("hmax"), CHANNEL_TARGET_TEMPERATURE);
        removeUnless(unsupported, state.has("hsta"), CHANNEL_HEATING_ACTIVE);
        removeUnless(unsupported, state.has("hume"), CHANNEL_HUMIDIFIER, CHANNEL_HUMIDIFIER_AUTO);
        removeUnless(unsupported, state.has("humt"), CHANNEL_TARGET_HUMIDITY);
        removeUnless(unsupported, state.has("wath"), CHANNEL_WATER_HARDNESS);
        removeUnless(unsupported, state.has("cltr"), CHANNEL_NEXT_DEEP_CLEAN);
        removeUnless(unsupported, state.has("cdrr"), CHANNEL_DEEP_CLEAN_REMAINING);

        boolean hepa = state.has("hflt") || state.has("hflr") || state.has("filf");
        removeUnless(unsupported, hepa, CHANNEL_HEPA_FILTER_LIFE, CHANNEL_FILTER_REPLACEMENT,
                CHANNEL_RESET_HEPA_FILTER);
        removeUnless(unsupported, state.has("hflt"), CHANNEL_HEPA_FILTER_TYPE);
        String cflt = state.get("cflt");
        boolean carbon = cflt != null && !"NONE".equalsIgnoreCase(cflt)
                && !cflt.toUpperCase(Locale.ROOT).startsWith("SCO");
        removeUnless(unsupported, carbon, CHANNEL_CARBON_FILTER_LIFE, CHANNEL_CARBON_FILTER_TYPE,
                CHANNEL_RESET_CARBON_FILTER);

        if (state.isEnvironmentReceived()) {
            removeUnless(unsupported, state.hasEnvironment("tact"), CHANNEL_TEMPERATURE);
            removeUnless(unsupported, state.hasEnvironment("hact"), CHANNEL_HUMIDITY);
            removeUnless(unsupported, state.hasEnvironment("p25r") || state.hasEnvironment("pm25"), CHANNEL_PM25);
            removeUnless(unsupported, state.hasEnvironment("p10r") || state.hasEnvironment("pm10"), CHANNEL_PM10);
            removeUnless(unsupported, state.hasEnvironment("pact"), CHANNEL_PARTICULATES);
            removeUnless(unsupported, state.hasEnvironment("va10") || state.hasEnvironment("vact"), CHANNEL_VOC);
            removeUnless(unsupported, state.hasEnvironment("noxl"), CHANNEL_NO2);
            removeUnless(unsupported, state.hasEnvironment("co2r"), CHANNEL_CO2);
            removeUnless(unsupported, state.hasEnvironment("hchr") || state.hasEnvironment("hcho"),
                    CHANNEL_FORMALDEHYDE);
            removeUnless(unsupported, DysonAirQuality.calculate(state.getEnvironment()) != null, CHANNEL_AQI,
                    CHANNEL_AQI_CATEGORY, CHANNEL_DOMINANT_POLLUTANT);
        }

        // Fault channels: keep the codes relevant for the capabilities and any code the device reports
        Set<String> relevantFaults = new HashSet<>(EC_FAULT_CODES);
        capabilities.forEach(c -> relevantFaults.addAll(CAPABILITY_FAULT_CODES.getOrDefault(c, Set.of())));
        if (capabilities.isEmpty() && !state.isFaultsReceived()) {
            relevantFaults.addAll(FAULT_TRANSLATIONS.keySet());
        }
        relevantFaults.addAll(state.getFaults().keySet());
        for (String code : FAULT_TRANSLATIONS.keySet()) {
            removeUnless(unsupported, relevantFaults.contains(code), CHANNEL_FAULT_PREFIX + code);
        }

        if (getAccountHandler() == null) {
            unsupported.addAll(Set.of(CHANNEL_FIRMWARE_UPDATE_AVAILABLE, CHANNEL_FIRMWARE_LATEST_VERSION,
                    CHANNEL_FIRMWARE_AUTO_UPDATE, CHANNEL_FIRMWARE_UPDATE));
        }
        return unsupported;
    }

    private static void removeUnless(Set<String> unsupported, boolean supported, String... channelIds) {
        if (!supported) {
            unsupported.addAll(List.of(channelIds));
        }
    }
}
