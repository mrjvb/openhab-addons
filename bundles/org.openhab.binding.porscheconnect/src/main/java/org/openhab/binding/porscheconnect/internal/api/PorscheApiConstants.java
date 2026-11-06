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
package org.openhab.binding.porscheconnect.internal.api;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Constants of the Porsche Connect cloud API (based on pyporscheconnectapi/const.py).
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public final class PorscheApiConstants {

    private PorscheApiConstants() {
    }

    public static final String AUTHORIZATION_SERVER = "identity.porsche.com";
    public static final String AUTH_BASE_URL = "https://" + AUTHORIZATION_SERVER;
    public static final String REDIRECT_URI = "my-porsche-app://auth0/callback";
    public static final String AUDIENCE = "https://api.porsche.com";
    public static final String CLIENT_ID = "XhygisuebbrqQ80byOuU5VncxLIm8E6H";
    public static final String X_CLIENT_ID = "41843fb4-691d-4970-85c7-2673e8ecef40";
    public static final String USER_AGENT = "de.porsche.one/20.26.36-row+186611 (android)";

    public static final String API_BASE_URL = "https://api.ppa.porsche.com/app";
    public static final String AUTHORIZATION_URL = AUTH_BASE_URL + "/authorize";
    public static final String TOKEN_URL = AUTH_BASE_URL + "/oauth/token";
    public static final String LOGIN_IDENTIFIER_URL = AUTH_BASE_URL + "/u/login/identifier";
    public static final String LOGIN_PASSWORD_URL = AUTH_BASE_URL + "/u/login/password";
    public static final String PASSKEY_ENROLLMENT_PATH = "/u/passkey-enrollment";

    public static final int TIMEOUT_SECONDS = 90;

    public static final String VEHICLES_PATH = "/connect/v1/vehicles";

    public static final List<String> SCOPES = List.of("openid", "profile", "email", "offline_access", "mbb", "ssodb",
            "badge", "vin", "dealers", "cars", "charging", "manageCharging", "plugAndCharge", "climatisation",
            "manageClimatisation", "pid:user_profile.porscheid:read", "pid:user_profile.name:read",
            "pid:user_profile.vehicles:read", "pid:user_profile.dealers:read", "pid:user_profile.emails:read",
            "pid:user_profile.phones:read", "pid:user_profile.addresses:read", "pid:user_profile.birthdate:read",
            "pid:user_profile.locale:read", "pid:user_profile.legal:read");

    public static final String SCOPE = String.join(" ", SCOPES);

    /** Tyre pressure deviation (bar) that is still considered OK. */
    public static final double TIRE_PRESSURE_TOLERANCE = 0.3;

    public static final List<String> MEASUREMENTS = List.of("ALARM_STATE", "BATTERY_CONDITION", "BATTERY_LEVEL",
            "BEM_LEVEL", "BLEID_DDADATA", "CHARGING_PROFILES", "CHARGING_RATE", "CHARGING_SESSION", "CHARGING_SETTINGS",
            "CHARGING_SUMMARY", "CONNECT_CONTRACT", "DEPARTURES", "DIRECT_CHARGING", "E_RANGE", "FUEL_LEVEL",
            "FUEL_RESERVE", "GLOBAL_PRIVACY_MODE", "GLOBAL_TIMESTAMP", "GPS_LOCATION", "HVAC_SUMMARY",
            "INSTRUMENT_CLUSTER_ALERTS", "INTERMEDIATE_SERVICE_RANGE", "INTERMEDIATE_SERVICE_TIME", "LOCATION_ALARMS",
            "LOCK_STATE_VEHICLE", "MAIN_SERVICE_RANGE", "MAIN_SERVICE_TIME", "MDK_ACTIVATION_STATE", "MDK_CARD_STATE",
            "MDK_PAIRING_PASSWORD", "MDK_PAIRING_STATE", "MILEAGE", "OIL_LEVEL_CURRENT", "OIL_LEVEL_MAX",
            "OIL_LEVEL_MIN_WARNING", "OIL_SERVICE_RANGE", "OIL_SERVICE_TIME", "OPEN_STATE_DOOR_FRONT_LEFT",
            "OPEN_STATE_DOOR_FRONT_RIGHT", "OPEN_STATE_DOOR_REAR_LEFT", "OPEN_STATE_DOOR_REAR_RIGHT",
            "OPEN_STATE_LID_FRONT", "OPEN_STATE_LID_REAR", "OPEN_STATE_SERVICE_FLAP", "OPEN_STATE_SPOILER",
            "OPEN_STATE_SUNROOF", "OPEN_STATE_SUNROOF_REAR", "OPEN_STATE_TOP", "OPEN_STATE_WINDOW_FRONT_LEFT",
            "OPEN_STATE_WINDOW_FRONT_RIGHT", "OPEN_STATE_WINDOW_REAR_LEFT", "OPEN_STATE_WINDOW_REAR_RIGHT",
            "OTA_CONSENT_STATUS", "OTA_UPDATE_DETAILS", "PAIRING_CODE", "PARKING_BRAKE", "PARKING_LIGHT", "RANGE",
            "REMOTE_ACCESS_AUTHORIZATION", "SERVICE_PREDICTIONS", "SPEED_ALARMS", "THEFT_STATE", "TIMERS", "TIMEZONE",
            "TIRE_PRESSURE_FRONT_LEFT", "TIRE_PRESSURE_FRONT_RIGHT", "TIRE_PRESSURE_REAR_LEFT",
            "TIRE_PRESSURE_REAR_RIGHT", "VALET_ALARM", "VTS_CERTIFICATE_LIST", "VTS_CONFIGURATION", "VTS_MODES");

    /** Only the command capabilities this binding actually uses are requested. */
    public static final List<String> COMMANDS = List.of("CHARGING_PROFILES_EDIT", "CHARGING_SETTINGS_EDIT",
            "DIRECT_CHARGING_START", "DIRECT_CHARGING_STOP", "HONK_FLASH", "LOCK", "REMOTE_CLIMATIZER_START",
            "REMOTE_CLIMATIZER_STOP", "SPIN_CHALLENGE", "UNLOCK");

    // command keys
    public static final String CMD_HONK_FLASH = "HONK_FLASH";
    public static final String CMD_LOCK = "LOCK";
    public static final String CMD_UNLOCK = "UNLOCK";
    public static final String CMD_SPIN_CHALLENGE = "SPIN_CHALLENGE";
    public static final String CMD_CLIMATIZER_START = "REMOTE_CLIMATIZER_START";
    public static final String CMD_CLIMATIZER_STOP = "REMOTE_CLIMATIZER_STOP";
    public static final String CMD_DIRECT_CHARGING_START = "DIRECT_CHARGING_START";
    public static final String CMD_DIRECT_CHARGING_STOP = "DIRECT_CHARGING_STOP";
    public static final String CMD_CHARGING_SETTINGS_EDIT = "CHARGING_SETTINGS_EDIT";
    public static final String CMD_CHARGING_PROFILES_EDIT = "CHARGING_PROFILES_EDIT";
}
