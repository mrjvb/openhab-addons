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
package org.openhab.binding.porscheconnect.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Identifiers used across the binding.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public final class PorscheConnectBindingConstants {

    private PorscheConnectBindingConstants() {
    }

    public static final String BINDING_ID = "porscheconnect";

    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_VEHICLE = new ThingTypeUID(BINDING_ID, "vehicle");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_VEHICLE);

    // configuration / properties
    public static final String CONFIG_VIN = "vin";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_MODEL_YEAR = "modelYear";
    public static final String PROPERTY_ENGINE = "engine";

    // channel groups
    public static final String GROUP_STATUS = "status";
    public static final String GROUP_RANGE = "range";
    public static final String GROUP_CHARGE = "charge";
    public static final String GROUP_CLIMATE = "climate";
    public static final String GROUP_DOORS = "doors";
    public static final String GROUP_WINDOWS = "windows";
    public static final String GROUP_TIRES = "tires";
    public static final String GROUP_SERVICE = "service";
    public static final String GROUP_LOCATION = "location";
    public static final String GROUP_CONTROL = "control";

    // status
    public static final String CH_MILEAGE = GROUP_STATUS + "#mileage";
    public static final String CH_CLOSED = GROUP_STATUS + "#closed";
    public static final String CH_PRIVACY_MODE = GROUP_STATUS + "#privacy-mode";
    public static final String CH_REMOTE_ACCESS = GROUP_STATUS + "#remote-access";
    public static final String CH_PARKING_BRAKE = GROUP_STATUS + "#parking-brake";
    public static final String CH_PARKING_LIGHT = GROUP_STATUS + "#parking-light";
    public static final String CH_LAST_UPDATE = GROUP_STATUS + "#last-update";
    public static final String CH_IMAGE = GROUP_STATUS + "#image";

    // range
    public static final String CH_RANGE_TOTAL = GROUP_RANGE + "#total";
    public static final String CH_RANGE_ELECTRIC = GROUP_RANGE + "#electric";
    public static final String CH_FUEL_LEVEL = GROUP_RANGE + "#fuel-level";
    public static final String CH_FUEL_RESERVE = GROUP_RANGE + "#fuel-reserve";

    // charge
    public static final String CH_SOC = GROUP_CHARGE + "#soc";
    public static final String CH_CHARGE_STATUS = GROUP_CHARGE + "#status";
    public static final String CH_CHARGE_MODE = GROUP_CHARGE + "#mode";
    public static final String CH_CHARGE_RATE = GROUP_CHARGE + "#rate";
    public static final String CH_CHARGE_POWER = GROUP_CHARGE + "#power";
    public static final String CH_TARGET_SOC = GROUP_CHARGE + "#target-soc";
    public static final String CH_CHARGE_FINISH = GROUP_CHARGE + "#finish-time";
    public static final String CH_DIRECT_CHARGING = GROUP_CHARGE + "#direct-charging";

    // climate
    public static final String CH_CLIMATE_ACTIVE = GROUP_CLIMATE + "#active";
    public static final String CH_CLIMATE_TEMPERATURE = GROUP_CLIMATE + "#target-temperature";
    public static final String CH_CLIMATE_REMAINING = GROUP_CLIMATE + "#remaining-time";

    /** OPEN_STATE_* measurement suffix to channel. */
    public static final Map<String, String> OPEN_STATE_CHANNELS = Map.ofEntries( //
            Map.entry("DOOR_FRONT_LEFT", GROUP_DOORS + "#front-left"),
            Map.entry("DOOR_FRONT_RIGHT", GROUP_DOORS + "#front-right"),
            Map.entry("DOOR_REAR_LEFT", GROUP_DOORS + "#rear-left"),
            Map.entry("DOOR_REAR_RIGHT", GROUP_DOORS + "#rear-right"), Map.entry("LID_FRONT", GROUP_DOORS + "#hood"), //
            Map.entry("LID_REAR", GROUP_DOORS + "#trunk"), Map.entry("SERVICE_FLAP", GROUP_DOORS + "#service-flap"),
            Map.entry("SUNROOF", GROUP_DOORS + "#sunroof"), //
            Map.entry("SUNROOF_REAR", GROUP_DOORS + "#sunroof-rear"), Map.entry("TOP", GROUP_DOORS + "#top"), //
            Map.entry("SPOILER", GROUP_DOORS + "#spoiler"),
            Map.entry("WINDOW_FRONT_LEFT", GROUP_WINDOWS + "#front-left"),
            Map.entry("WINDOW_FRONT_RIGHT", GROUP_WINDOWS + "#front-right"),
            Map.entry("WINDOW_REAR_LEFT", GROUP_WINDOWS + "#rear-left"),
            Map.entry("WINDOW_REAR_RIGHT", GROUP_WINDOWS + "#rear-right"));

    /** TIRE_PRESSURE_* measurement suffix to channel. */
    public static final Map<String, String> TIRE_CHANNELS = Map.of( //
            "FRONT_LEFT", GROUP_TIRES + "#front-left", //
            "FRONT_RIGHT", GROUP_TIRES + "#front-right", //
            "REAR_LEFT", GROUP_TIRES + "#rear-left", //
            "REAR_RIGHT", GROUP_TIRES + "#rear-right");
    public static final String CH_TIRE_WARNING = GROUP_TIRES + "#warning";

    // service
    public static final String CH_SERVICE_MAIN_DISTANCE = GROUP_SERVICE + "#main-distance";
    public static final String CH_SERVICE_MAIN_TIME = GROUP_SERVICE + "#main-time";
    public static final String CH_SERVICE_OIL_DISTANCE = GROUP_SERVICE + "#oil-distance";
    public static final String CH_SERVICE_OIL_TIME = GROUP_SERVICE + "#oil-time";
    public static final String CH_SERVICE_INTERMEDIATE_DISTANCE = GROUP_SERVICE + "#intermediate-distance";
    public static final String CH_SERVICE_INTERMEDIATE_TIME = GROUP_SERVICE + "#intermediate-time";
    public static final String CH_OIL_LEVEL = GROUP_SERVICE + "#oil-level";

    // location
    public static final String CH_POSITION = GROUP_LOCATION + "#position";
    public static final String CH_HEADING = GROUP_LOCATION + "#heading";
    public static final String CH_LOCATION_UPDATE = GROUP_LOCATION + "#last-update";

    // control
    public static final String CH_LOCK = GROUP_CONTROL + "#lock";
    public static final String CH_FLASH = GROUP_CONTROL + "#flash";
    public static final String CH_HONK_FLASH = GROUP_CONTROL + "#honk-flash";
    public static final String CH_WAKE_UP = GROUP_CONTROL + "#wake-up";

    // account (bridge) channels
    public static final String CH_CAPTCHA = "captcha";
}
