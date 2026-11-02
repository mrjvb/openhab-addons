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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Snapshot of one vehicle: base data from the vehicle list plus the enabled measurements of the last overview,
 * flattened to {@code MEASUREMENT_KEY -> value object}. Port of the data handling and properties of
 * pyporscheconnectapi/vehicle.py.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class VehicleData {

    public static final String ENGINE_BEV = "BEV";
    public static final String ENGINE_PHEV = "PHEV";
    public static final String ENGINE_COMBUSTION = "COMBUSTION";

    private final JsonObject base;
    private final Map<String, JsonObject> measurements;

    private VehicleData(JsonObject base, Map<String, JsonObject> measurements) {
        this.base = base;
        this.measurements = measurements;
    }

    /** Creates a vehicle from an entry of the {@code /connect/v1/vehicles} list (no measurements yet). */
    public static VehicleData fromListEntry(JsonObject entry) {
        return new VehicleData(entry.deepCopy(), Map.of());
    }

    /**
     * Merges an overview response ({@code /connect/v1/vehicles/{vin}?mf=...}) into this snapshot and returns the
     * result. Only measurements reported as enabled and carrying a value are taken over.
     */
    public VehicleData merge(JsonObject overview) {
        JsonObject newBase = base.deepCopy();
        for (String key : new String[] { "vin", "modelName", "modelType", "systemInfo", "timestamp", "customName" }) {
            JsonElement value = overview.get(key);
            if (value != null && !value.isJsonNull()) {
                newBase.add(key, value.deepCopy());
            }
        }
        Map<String, JsonObject> newMeasurements = new LinkedHashMap<>(measurements);
        JsonElement list = overview.get("measurements");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject m = element.getAsJsonObject();
                String key = PorscheAuth.getString(m, "key");
                JsonObject status = PorscheAuth.getObject(m, "status");
                JsonObject value = PorscheAuth.getObject(m, "value");
                if (key != null && status != null && bool(status, "isEnabled") == Boolean.TRUE && value != null) {
                    newMeasurements.put(key, value.deepCopy());
                }
            }
        }
        return new VehicleData(newBase, newMeasurements);
    }

    // ---------------------------------------------------------------- base data

    public String getVin() {
        String vin = PorscheAuth.getString(base, "vin");
        return vin != null ? vin : "";
    }

    public @Nullable String getModelName() {
        return PorscheAuth.getString(base, "modelName");
    }

    public String getName() {
        String custom = PorscheAuth.getString(base, "customName");
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        String model = getModelName();
        return model != null ? model : "Porsche " + getVin();
    }

    public @Nullable String getModelYear() {
        return PorscheAuth.getString(PorscheAuth.getObject(base, "modelType"), "year");
    }

    public @Nullable String getEngine() {
        return PorscheAuth.getString(PorscheAuth.getObject(base, "modelType"), "engine");
    }

    public boolean hasElectricDrivetrain() {
        String engine = getEngine();
        return ENGINE_BEV.equals(engine) || ENGINE_PHEV.equals(engine);
    }

    public boolean hasCombustionEngine() {
        String engine = getEngine();
        return ENGINE_PHEV.equals(engine) || ENGINE_COMBUSTION.equals(engine);
    }

    // ---------------------------------------------------------------- measurements

    public boolean has(String measurement) {
        return measurements.containsKey(measurement);
    }

    public @Nullable JsonObject get(String measurement) {
        return measurements.get(measurement);
    }

    public @Nullable Double number(String measurement, String field) {
        return num(measurements.get(measurement), field);
    }

    public @Nullable Boolean bool(String measurement, String field) {
        return bool(measurements.get(measurement), field);
    }

    public @Nullable String string(String measurement, String field) {
        return PorscheAuth.getString(measurements.get(measurement), field);
    }

    public @Nullable Instant timestamp(String measurement, String field) {
        return parseTimestamp(string(measurement, field));
    }

    /** All {@code OPEN_STATE_*} measurements: suffix (e.g. {@code DOOR_FRONT_LEFT}) -> open. */
    public Map<String, Boolean> openStates() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Entry<String, JsonObject> entry : measurements.entrySet()) {
            if (entry.getKey().startsWith("OPEN_STATE_")) {
                Boolean open = bool(entry.getValue(), "isOpen");
                if (open != null) {
                    result.put(entry.getKey().substring("OPEN_STATE_".length()), open);
                }
            }
        }
        return result;
    }

    /** {@code null} if no door/lid state is known, otherwise whether everything is closed. */
    public @Nullable Boolean isClosed() {
        Map<String, Boolean> states = openStates();
        return states.isEmpty() ? null : states.values().stream().noneMatch(Boolean::booleanValue);
    }

    public boolean hasRemoteServices() {
        return bool("REMOTE_ACCESS_AUTHORIZATION", "isEnabled") == Boolean.TRUE;
    }

    public boolean hasRemoteClimatisation() {
        return has("HVAC_SUMMARY");
    }

    public boolean isDirectChargeOn() {
        return "DIRECT".equals(string("CHARGING_SUMMARY", "mode"));
    }

    public @Nullable Boolean isClimatisationOn() {
        String status = string("HVAC_SUMMARY", "status");
        return status == null ? null : !"OFF".equals(status);
    }

    /** Target cabin temperature in °C. The API reports Kelvin. */
    public @Nullable Double getClimatisationTargetCelsius() {
        Double kelvin = number("HVAC_SUMMARY", "targetTemperature");
        return kelvin == null ? null : Math.round((kelvin - 273.15) * 10) / 10.0;
    }

    /** Charging speed in km/h; 0 while a CHARGING_RATE is reported but no charging is going on. */
    public @Nullable Double getChargingRateKmh() {
        JsonObject rate = get("CHARGING_RATE");
        if (rate == null) {
            return null;
        }
        Double perMinute = num(rate, "chargingRate");
        // the API reports km per minute
        return perMinute == null || perMinute == 0 ? 0.0 : perMinute * 60;
    }

    public @Nullable Double getChargingPowerKw() {
        JsonObject rate = get("CHARGING_RATE");
        if (rate == null) {
            return null;
        }
        Double power = num(rate, "chargingPower");
        Double perMinute = num(rate, "chargingRate");
        return perMinute == null || perMinute == 0 ? Double.valueOf(0) : power;
    }

    /**
     * Effective target state of charge, resolved the same way as pyporscheconnectapi: explicit targetSoC, the active
     * charging profile, the departure charging settings, or 100 while direct charging.
     */
    public @Nullable Double getChargingTarget() {
        JsonObject summary = get("CHARGING_SUMMARY");
        if (summary == null) {
            return null;
        }
        Double target = num(summary, "targetSoC");
        if (target != null) {
            return target;
        }
        target = num(summary, "minSoC");
        if ("PROFILE".equals(PorscheAuth.getString(summary, "mode"))) {
            target = num(PorscheAuth.getObject(summary, "chargingProfile"), "minSoC");
        }
        if (has("DEPARTURES")) {
            Double settingsTarget = number("CHARGING_SETTINGS", "targetSoc");
            if (settingsTarget != null && settingsTarget != 0) {
                target = settingsTarget;
            }
        } else if (target == null && "DIRECT".equals(PorscheAuth.getString(summary, "mode"))) {
            target = 100.0;
        }
        return target;
    }

    /** Charging profiles list as reported (used for CHARGING_PROFILES_EDIT). */
    public @Nullable JsonArray getChargingProfiles() {
        JsonObject profiles = get("CHARGING_PROFILES");
        JsonElement list = profiles != null ? profiles.get("list") : null;
        return list != null && list.isJsonArray() ? list.getAsJsonArray() : null;
    }

    /** GPS position as {@code lat,lon}, or {@code null}. */
    public double @Nullable [] getLocation() {
        String loc = string("GPS_LOCATION", "location");
        if (loc == null) {
            return null;
        }
        String[] parts = loc.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[] { Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return {@code null} if no tyre pressures are reported, otherwise whether any deviation exceeds the tolerance
     */
    public @Nullable Boolean isTirePressureWarning() {
        boolean any = false;
        double maxDiff = 0;
        for (String pos : new String[] { "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT" }) {
            JsonObject tire = get("TIRE_PRESSURE_" + pos);
            if (tire == null) {
                continue;
            }
            any = true;
            Double diff = num(tire, "differenceBar");
            if (diff != null) {
                maxDiff = Math.max(maxDiff, Math.abs(diff));
            }
        }
        return any ? maxDiff > PorscheApiConstants.TIRE_PRESSURE_TOLERANCE : null;
    }

    // ---------------------------------------------------------------- helpers

    static @Nullable Double num(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    static @Nullable Boolean bool(@Nullable JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }

    static @Nullable Instant parseTimestamp(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
