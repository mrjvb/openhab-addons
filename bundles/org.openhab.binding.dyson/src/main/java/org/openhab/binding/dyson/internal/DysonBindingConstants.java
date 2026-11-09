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
package org.openhab.binding.dyson.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link DysonBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonBindingConstants {

    public static final String BINDING_ID = "dyson";

    // Thing types
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_FAN = new ThingTypeUID(BINDING_ID, "fan");
    public static final ThingTypeUID THING_TYPE_ROBOT = new ThingTypeUID(BINDING_ID, "robot");

    public static final Set<ThingTypeUID> DEVICE_THING_TYPES = Set.of(THING_TYPE_FAN, THING_TYPE_ROBOT);
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_FAN,
            THING_TYPE_ROBOT);

    // Dyson device categories (as reported by the cloud manifest)
    public static final String CATEGORY_EC = "ec";
    public static final String CATEGORY_ROBOT = "robot";

    // Configuration / property keys
    public static final String CONFIG_SERIAL_NUMBER = "serialNumber";
    public static final String CONFIG_OTP = "otp";
    public static final String CONFIG_TOKEN = "token";
    public static final String PROPERTY_CHALLENGE_ID = "challengeId";
    public static final String PROPERTY_PRODUCT_TYPE = "productType";
    public static final String PROPERTY_VARIANT = "variant";
    public static final String PROPERTY_CATEGORY = "category";
    public static final String PROPERTY_CAPABILITIES = "capabilities";
    public static final String PROPERTY_MQTT_PREFIX = "mqttPrefix";

    // Connection types
    public static final String CONNECTION_LOCAL = "LOCAL";
    public static final String CONNECTION_LOCAL_CLOUD_FALLBACK = "LOCAL_CLOUD_FALLBACK";
    public static final String CONNECTION_CLOUD_LOCAL_FALLBACK = "CLOUD_LOCAL_FALLBACK";
    public static final String CONNECTION_CLOUD = "CLOUD";

    // Capabilities
    public static final String CAPABILITY_ADVANCE_OSCILLATION_DAY0 = "AdvanceOscillationDay0";
    public static final String CAPABILITY_ADVANCE_OSCILLATION_DAY1 = "AdvanceOscillationDay1";
    public static final String CAPABILITY_SCHEDULING = "Scheduling";
    public static final String CAPABILITY_ENVIRONMENTAL_DATA = "EnvironmentalData";
    public static final String CAPABILITY_EXTENDED_AQ = "ExtendedAQ";
    public static final String CAPABILITY_HEATING = "Heating";
    public static final String CAPABILITY_HUMIDIFIER = "Humidifier";
    public static final String CAPABILITY_FORMALDEHYDE = "Formaldehyde";
    public static final String CAPABILITY_VOC = "VOC";

    // Common channels
    public static final String CHANNEL_WIFI_SIGNAL = "wifi-signal";
    public static final String CHANNEL_CONNECTION_STATUS = "connection-status";
    public static final String CHANNEL_HOST = "host";
    public static final String CHANNEL_RECONNECT = "reconnect";
    public static final String CHANNEL_FIRMWARE_VERSION = "firmware-version";
    public static final String CHANNEL_FIRMWARE_UPDATE_AVAILABLE = "firmware-update-available";
    public static final String CHANNEL_FIRMWARE_LATEST_VERSION = "firmware-latest-version";
    public static final String CHANNEL_FIRMWARE_AUTO_UPDATE = "firmware-auto-update";
    public static final String CHANNEL_FIRMWARE_UPDATE = "firmware-update";

    // Fan / purifier / heater / humidifier channels
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_FAN_SPEED = "fan-speed";
    public static final String CHANNEL_AUTO_MODE = "auto-mode";
    public static final String CHANNEL_NIGHT_MODE = "night-mode";
    public static final String CHANNEL_OSCILLATION = "oscillation";
    public static final String CHANNEL_OSCILLATION_MODE = "oscillation-mode";
    public static final String CHANNEL_OSCILLATION_LOWER_ANGLE = "oscillation-lower-angle";
    public static final String CHANNEL_OSCILLATION_UPPER_ANGLE = "oscillation-upper-angle";
    public static final String CHANNEL_OSCILLATION_CENTER_ANGLE = "oscillation-center-angle";
    public static final String CHANNEL_OSCILLATION_SPAN = "oscillation-span";
    public static final String CHANNEL_TILT_OSCILLATION = "tilt-oscillation";
    public static final String CHANNEL_SLEEP_TIMER = "sleep-timer";
    public static final String CHANNEL_CONTINUOUS_MONITORING = "continuous-monitoring";
    public static final String CHANNEL_FRONT_AIRFLOW = "front-airflow";
    public static final String CHANNEL_FOCUS_MODE = "focus-mode";
    public static final String CHANNEL_FIND_FOLLOW = "find-follow";
    public static final String CHANNEL_FIND_FOLLOW_STATUS = "find-follow-status";
    public static final String CHANNEL_FIND_FOLLOW_SCAN = "find-follow-scan";
    public static final String CHANNEL_HEATING_MODE = "heating-mode";
    public static final String CHANNEL_TARGET_TEMPERATURE = "target-temperature";
    public static final String CHANNEL_HEATING_ACTIVE = "heating-active";
    public static final String CHANNEL_HUMIDIFIER = "humidifier";
    public static final String CHANNEL_HUMIDIFIER_AUTO = "humidifier-auto";
    public static final String CHANNEL_TARGET_HUMIDITY = "target-humidity";
    public static final String CHANNEL_WATER_HARDNESS = "water-hardness";
    public static final String CHANNEL_NEXT_DEEP_CLEAN = "next-deep-clean";
    public static final String CHANNEL_DEEP_CLEAN_REMAINING = "deep-clean-remaining";
    public static final String CHANNEL_TEMPERATURE = "temperature";
    public static final String CHANNEL_HUMIDITY = "humidity";
    public static final String CHANNEL_PM25 = "pm25";
    public static final String CHANNEL_PM10 = "pm10";
    public static final String CHANNEL_PARTICULATES = "particulates";
    public static final String CHANNEL_VOC = "voc";
    public static final String CHANNEL_NO2 = "no2";
    public static final String CHANNEL_CO2 = "co2";
    public static final String CHANNEL_FORMALDEHYDE = "formaldehyde";
    public static final String CHANNEL_AQI = "aqi";
    public static final String CHANNEL_AQI_CATEGORY = "aqi-category";
    public static final String CHANNEL_DOMINANT_POLLUTANT = "dominant-pollutant";
    public static final String CHANNEL_HEPA_FILTER_LIFE = "hepa-filter-life";
    public static final String CHANNEL_HEPA_FILTER_TYPE = "hepa-filter-type";
    public static final String CHANNEL_CARBON_FILTER_LIFE = "carbon-filter-life";
    public static final String CHANNEL_CARBON_FILTER_TYPE = "carbon-filter-type";
    public static final String CHANNEL_FILTER_REPLACEMENT = "filter-replacement";
    public static final String CHANNEL_RESET_HEPA_FILTER = "reset-hepa-filter";
    public static final String CHANNEL_RESET_CARBON_FILTER = "reset-carbon-filter";
    public static final String CHANNEL_FAULT = "fault";
    public static final String CHANNEL_ACTIVE_FAULTS = "active-faults";
    public static final String CHANNEL_FAULT_PREFIX = "fault-";

    // Robot vacuum channels
    public static final String CHANNEL_ROBOT_ACTIVITY = "activity";
    public static final String CHANNEL_ROBOT_STATE = "state";
    public static final String CHANNEL_ROBOT_COMMAND = "command";
    public static final String CHANNEL_ROBOT_BATTERY = "battery-level";
    public static final String CHANNEL_ROBOT_CHARGING = "charging";
    public static final String CHANNEL_ROBOT_POWER_MODE = "power-mode";
    public static final String CHANNEL_ROBOT_CLEAN_TYPE = "clean-type";
    public static final String CHANNEL_ROBOT_CLEAN_ID = "clean-id";
    public static final String CHANNEL_ROBOT_CLEAN_DURATION = "clean-duration";
    public static final String CHANNEL_ROBOT_POSITION = "position";
    public static final String CHANNEL_ROBOT_DOCK_STATE = "dock-state";
    public static final String CHANNEL_ROBOT_ACTIVE_FAULT = "active-fault";
    public static final String CHANNEL_ROBOT_ACTION_REQUIRED = "action-required";
    public static final String CHANNEL_ROBOT_CLEANING_SOLUTION_REFILL = "cleaning-solution-refill";
    public static final String CHANNEL_ROBOT_FAULT_PREFIX = "robot-fault-";

    /**
     * Fault codes reported in CURRENT-FAULTS messages, mapped to a human readable translation of their values.
     */
    public static final Map<String, Map<String, String>> FAULT_TRANSLATIONS = Map.ofEntries(
            Map.entry("aqs",
                    Map.of("FAIL", "Air quality sensor failure", "WARN", "Air quality sensor warning", "OFF",
                            "Air quality sensor disabled")),
            Map.entry("fltr",
                    Map.of("FAIL", "Filter failure - replace filter", "WARN", "Filter warning - low life remaining",
                            "CHNG", "Filter needs replacement")),
            Map.entry("hflr",
                    Map.of("FAIL", "HEPA filter failure", "WARN", "HEPA filter warning - low life remaining", "CHNG",
                            "HEPA filter needs replacement")),
            Map.entry("cflr",
                    Map.of("FAIL", "Carbon filter failure", "WARN", "Carbon filter warning - low life remaining",
                            "CHNG", "Carbon filter needs replacement")),
            Map.entry("mflr",
                    Map.of("FAIL", "Motor failure - device malfunction", "STLL", "Motor stall detected", "WRNG",
                            "Motor warning")),
            Map.entry("temp",
                    Map.of("FAIL", "Temperature sensor failure", "HIGH", "Temperature too high", "LOW",
                            "Temperature too low")),
            Map.entry("humi",
                    Map.of("FAIL", "Humidity sensor failure", "HIGH", "Humidity too high", "LOW", "Humidity too low")),
            Map.entry("tnke", Map.of("FAIL", "Water tank empty - please refill")),
            Map.entry("tnkp", Map.of("FAIL", "Water tank not detected - check that the tank is seated correctly")),
            Map.entry("cldu", Map.of("FAIL", "Humidifier cleaning required")),
            Map.entry("etwd", Map.of("FAIL", "Humidifier maintenance required")),
            Map.entry("pwr",
                    Map.of("FAIL", "Power supply failure", "VOLT", "Voltage fault detected", "CURR",
                            "Current fault detected")),
            Map.entry("wifi",
                    Map.of("FAIL", "WiFi connection failure", "WEAK", "WiFi signal weak", "DISC", "WiFi disconnected")),
            Map.entry("sys", Map.of("FAIL", "System failure - contact support", "OVHT", "Device overheating", "LBAT",
                    "Low battery warning")));

    /** Fault codes that are relevant for every environment cleaner. */
    public static final Set<String> EC_FAULT_CODES = Set.of("mflr", "pwr", "wifi", "sys");

    /** Fault codes that are relevant when a device reports a given capability. */
    public static final Map<String, Set<String>> CAPABILITY_FAULT_CODES = Map.of(CAPABILITY_EXTENDED_AQ,
            Set.of("aqs", "fltr", "hflr"), CAPABILITY_HEATING, Set.of("temp"), CAPABILITY_VOC, Set.of("aqs"),
            CAPABILITY_FORMALDEHYDE, Set.of("cflr", "aqs"), CAPABILITY_HUMIDIFIER,
            Set.of("humi", "fltr", "tnke", "tnkp", "cldu", "etwd"));

    /** Fault values that indicate a healthy state. */
    public static final Set<String> FAULT_OK_VALUES = Set.of("OK", "NONE", "PASS", "GOOD");

    /** Robot vacuum fault subsystems reported in the {@code faults} object of STATE-CHANGE messages. */
    public static final Map<String, String> ROBOT_FAULT_SUBSYSTEMS = Map.of("AIRWAYS", "airways", "BATTERY", "battery",
            "BRUSH_BAR_AND_TRACTION", "brush-bar-and-traction", "CHARGE_STATION", "charge-station", "LIFT", "lift",
            "LOST", "lost", "OPTICS", "optics");

    /** Robot consumables reported in the {@code consumables} list, mapped to their channel id. */
    public static final Map<String, String> ROBOT_CONSUMABLE_CHANNELS = Map.of("brushBar", "brush-bar-life",
            "mopRoller", "mop-roller-life", "sideBrushes", "side-brushes-life", "robotFilter", "robot-filter-life",
            "dockFilter", "dock-filter-life", "ioniserCartridge", "ioniser-cartridge-life");

    /** Numeric robot fault codes with a human readable description. */
    public static final Map<String, String> ROBOT_NUMERIC_FAULT_NAMES = Map.ofEntries(
            Map.entry("500", "LiDAR sensor obstructed"), Map.entry("501", "Wheels lifted"),
            Map.entry("502", "Battery is low"), Map.entry("503", "Robot's bin not detected"),
            Map.entry("504", "Gyroscopic sensor error"), Map.entry("507", "Unable to determine position"),
            Map.entry("508", "Unable to climb slope"), Map.entry("509", "Drop sensor obstructed"),
            Map.entry("510", "Collision sensor obstructed"), Map.entry("511", "Unable to return to dock"),
            Map.entry("513", "Robot stuck"), Map.entry("514", "Robot stuck"),
            Map.entry("516", "Battery temperature high"), Map.entry("518", "Battery is low"),
            Map.entry("521", "Dock's clean water tank not detected"), Map.entry("522", "Wet roller not detected"),
            Map.entry("560", "Side sweeper stuck"), Map.entry("561", "Camera obstructed"),
            Map.entry("562", "Wall follow sensor obstructed"), Map.entry("563", "Robot's bin not detected"),
            Map.entry("566", "Robot's dirty water tank not detected"), Map.entry("567", "Brush bar error"),
            Map.entry("568", "Left wheel stuck"), Map.entry("569", "Right wheel stuck"),
            Map.entry("570", "Brush bar error"), Map.entry("572", "Robot stuck"),
            Map.entry("581", "Dock's clean water tank empty"), Map.entry("582", "Dock's dirty water tank full"),
            Map.entry("583", "Dock's clean water tank not detected"),
            Map.entry("584", "Dock's dirty water tank not detected"), Map.entry("586", "Robot's dirty water tank full"),
            Map.entry("587", "Communication failure"), Map.entry("591", "Dock's bin full"),
            Map.entry("592", "Dock's filter error"), Map.entry("594", "Unable to empty robot's bin"),
            Map.entry("595", "Communication failure"), Map.entry("596", "Unable to empty robot's bin"),
            Map.entry("597", "Unable to empty robot's bin"), Map.entry("611", "Mapping failed"),
            Map.entry("612", "Mapping failed"), Map.entry("620", "Dock's cleaning solution empty"),
            Map.entry("627", "Something went wrong"), Map.entry("629", "Wet roller not detected"),
            Map.entry("630", "Wet roller stuck"), Map.entry("634", "Unable to return to dock"),
            Map.entry("636", "Robot stuck"), Map.entry("637", "Dock's clean water tank not detected"),
            Map.entry("639", "Wet roller not detected"), Map.entry("645", "Wet roller not detected"),
            Map.entry("646", "Wet roller error"), Map.entry("650", "Robot's dirty water tank not detected"),
            Map.entry("2000", "Dock's bin full"), Map.entry("2003", "Unable to start scheduled clean"),
            Map.entry("2007", "Mapping failed"), Map.entry("2012", "Unable to reach area"),
            Map.entry("2119", "Unable to start scheduled clean"), Map.entry("2123", "Dock's clean water pump error"),
            Map.entry("2124", "Dock's dirty water pump error"), Map.entry("2125", "Robot not charging"),
            Map.entry("2126", "Robot not charging"), Map.entry("2131", "Battery temperature too low"),
            Map.entry("2132", "Battery temperature too high"), Map.entry("2133", "Battery temperature too low"));

    /** Robot states, mapped to a simplified activity. */
    public static final Map<String, String> ROBOT_STATE_TO_ACTIVITY = Map.ofEntries(
            Map.entry("FULL_CLEAN_RUNNING", "CLEANING"), Map.entry("FULL_CLEAN_DISCOVERING", "CLEANING"),
            Map.entry("FULL_CLEAN_TRAVERSING", "CLEANING"), Map.entry("FULL_CLEAN_INITIATED", "CLEANING"),
            Map.entry("FULL_CLEAN_PAUSED", "PAUSED"), Map.entry("MAPPING_PAUSED", "PAUSED"),
            Map.entry("INACTIVE_CHARGED", "DOCKED"), Map.entry("INACTIVE_CHARGING", "DOCKED"),
            Map.entry("INACTIVE_DISCHARGING", "DOCKED"), Map.entry("FULL_CLEAN_CHARGING", "DOCKED"),
            Map.entry("MAPPING_CHARGING", "DOCKED"), Map.entry("FULL_CLEAN_FINISHED", "RETURNING"),
            Map.entry("FULL_CLEAN_ABORTED", "RETURNING"), Map.entry("FULL_CLEAN_NEEDS_CHARGE", "RETURNING"),
            Map.entry("MAPPING_NEEDS_CHARGE", "RETURNING"), Map.entry("MAPPING_ABORTED", "RETURNING"),
            Map.entry("MAPPING_RUNNING", "IDLE"), Map.entry("MAPPING_FINISHED", "IDLE"),
            Map.entry("MAPPING_INITIATED", "IDLE"), Map.entry("FULL_CLEAN_ABANDONED", "IDLE"),
            Map.entry("MACHINE_OFF", "IDLE"));

    /** Robot states in which the battery is being charged. */
    public static final Set<String> ROBOT_CHARGING_STATES = Set.of("INACTIVE_CHARGING", "FULL_CLEAN_CHARGING",
            "MAPPING_CHARGING", "FAULT_ON_DOCK_CHARGING");

    /** Robot states from which a START (instead of RESUME) is issued. */
    public static final Set<String> ROBOT_STARTABLE_STATES = Set.of("INACTIVE_CHARGED", "INACTIVE_CHARGING",
            "INACTIVE_DISCHARGING", "FULL_CLEAN_FINISHED", "FAULT_ON_DOCK", "FULL_CLEAN_ABORTED",
            "FULL_CLEAN_ABANDONED", "MACHINE_OFF");

    // Robot power options, per model family
    public static final Map<String, String> ROBOT_POWER_360_EYE = Map.of("halfPower", "Quiet (half power)", "fullPower",
            "Deep clean (full power)");
    public static final Map<String, String> ROBOT_POWER_HEURIST = Map.of("1", "Quiet", "2", "High", "3", "Maximum");
    public static final Map<String, String> ROBOT_POWER_VIS_NAV = Map.of("1", "Auto", "2", "Quick", "3", "Quiet", "4",
            "Boost");

    private DysonBindingConstants() {
    }
}
