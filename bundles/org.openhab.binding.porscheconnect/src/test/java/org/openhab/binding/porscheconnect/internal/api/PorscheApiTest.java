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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the pure logic ported from pyporscheconnectapi. Expected values were computed with the Python library's
 * algorithms.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
class PorscheApiTest {

    @Test
    void pkceChallengeMatchesPythonImplementation() {
        // base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).rstrip(b"=")
        assertEquals("i92HHyIxhO5fV0eX_HwW1cZwAVWmoc4-cp6gn7iPN7g", PorscheAuth.buildPkceChallenge("abc123_-XYZ"));
    }

    @Test
    void spinHashMatchesPythonImplementation() {
        // sha512(bytes.fromhex(pin + challenge)).hexdigest().upper()
        assertEquals(
                "A2FF259F4A05ABD367BF7375B8A03DD3B8028684D1F7688B2321C4575651F290"
                        + "A5292C21CE0EA86F0ACBB29A29F100F961E08E1A0F139F2E561A1F98CF9A1E9B",
                PorscheApi.spinHash("1234", "a1b2c3d4"));
    }

    @Test
    void retryDelayPrefersRetryAfterHeader() {
        assertEquals(7, PorscheApi.retryDelaySeconds("7", 0));
        assertEquals(30, PorscheApi.retryDelaySeconds("120", 0));
        assertEquals(2, PorscheApi.retryDelaySeconds(null, 0));
        assertEquals(4, PorscheApi.retryDelaySeconds("Wed, 21 Oct 2015 07:28:00 GMT", 1));
        assertEquals(8, PorscheApi.retryDelaySeconds(null, 2));
    }

    @Test
    void queryParamsFromCallbackAndRelativeUrls() throws PorscheApiException {
        Map<String, String> params = PorscheAuth.queryParams("my-porsche-app://auth0/callback?code=abc%2Fdef&state=x");
        assertEquals("abc/def", params.get("code"));
        assertEquals("x", params.get("state"));
        assertEquals("https://identity.porsche.com/authorize/resume?state=s1", PorscheAuth
                .resolve("https://identity.porsche.com/u/login/password?state=s1", "/authorize/resume?state=s1"));
        assertEquals("my-porsche-app://auth0/callback?code=1", PorscheAuth
                .resolve("https://identity.porsche.com/authorize/resume", "my-porsche-app://auth0/callback?code=1"));
    }

    @Test
    void captchaFromAculContext() {
        String context = "{\"screen\":{\"name\":\"login-id\",\"captcha\":{\"image\":\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=\"}}}";
        String html = "<html><script>window.ctx = JSON.parse(atob(\""
                + Base64.getEncoder().encodeToString(context.getBytes(StandardCharsets.UTF_8))
                + "\"));</script></html>";
        assertEquals("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=", PorscheAuth.extractCaptchaImage(html));
    }

    @Test
    void captchaFromLegacyImgTag() {
        String html = "<div><img class=\"x\" alt=\"captcha\" src=\"data:image/svg+xml;base64,AAAA\"></div>";
        assertEquals("data:image/svg+xml;base64,AAAA", PorscheAuth.extractCaptchaImage(html));
        assertNull(PorscheAuth.extractCaptchaImage("<html>no captcha here</html>"));
    }

    @Test
    void vehicleDataParsing() {
        JsonObject list = JsonParser.parseString("""
                {"vin":"WP0ZZZY1ZPSA00001","modelName":"Taycan 4S","customName":"Blue",
                 "modelType":{"year":"2023","engine":"BEV"}}
                """).getAsJsonObject();
        JsonObject overview = JsonParser
                .parseString(
                        """
                                {"vin":"WP0ZZZY1ZPSA00001","modelName":"Taycan 4S","modelType":{"year":"2023","engine":"BEV"},
                                 "measurements":[
                                  {"key":"BATTERY_LEVEL","status":{"isEnabled":true},"value":{"percent":81}},
                                  {"key":"MILEAGE","status":{"isEnabled":true},"value":{"kilometers":12345}},
                                  {"key":"FUEL_LEVEL","status":{"isEnabled":false,"cause":"NOT_SUPPORTED"}},
                                  {"key":"CHARGING_RATE","status":{"isEnabled":true},"value":{"chargingRate":1.5,"chargingPower":11.2}},
                                  {"key":"CHARGING_SUMMARY","status":{"isEnabled":true},"value":{"mode":"PROFILE","status":"CHARGING",
                                     "chargingProfile":{"minSoC":80},"targetDateTimeWithOffset":"2026-11-01T18:30:00.000+01:00"}},
                                  {"key":"HVAC_SUMMARY","status":{"isEnabled":true},"value":{"status":"ON","targetTemperature":294.15}},
                                  {"key":"GPS_LOCATION","status":{"isEnabled":true},"value":{"location":"52.18,5.29","direction":90,
                                     "lastModified":"2026-11-01T07:00:00Z"}},
                                  {"key":"OPEN_STATE_DOOR_FRONT_LEFT","status":{"isEnabled":true},"value":{"isOpen":false}},
                                  {"key":"OPEN_STATE_LID_REAR","status":{"isEnabled":true},"value":{"isOpen":true}},
                                  {"key":"TIRE_PRESSURE_FRONT_LEFT","status":{"isEnabled":true},"value":{"actualPressureBar":2.6,"differenceBar":-0.4}},
                                  {"key":"LOCK_STATE_VEHICLE","status":{"isEnabled":true},"value":{"isLocked":true}}
                                 ]}
                                """)
                .getAsJsonObject();

        VehicleData data = VehicleData.fromListEntry(list).merge(overview);

        assertEquals("WP0ZZZY1ZPSA00001", data.getVin());
        assertEquals("Blue", data.getName());
        assertTrue(data.hasElectricDrivetrain());
        assertFalse(data.hasCombustionEngine());
        assertEquals(81.0, data.number("BATTERY_LEVEL", "percent"));
        assertEquals(12345.0, data.number("MILEAGE", "kilometers"));
        assertFalse(data.has("FUEL_LEVEL"), "disabled measurements are skipped");
        assertEquals(90.0, data.getChargingRateKmh());
        assertEquals(11.2, data.getChargingPowerKw());
        assertEquals(80.0, data.getChargingTarget());
        assertEquals(Boolean.TRUE, data.isClimatisationOn());
        assertEquals(21.0, data.getClimatisationTargetCelsius());
        assertArrayEquals(new double[] { 52.18, 5.29 }, data.getLocation());
        assertEquals(Boolean.FALSE, data.isClosed());
        assertEquals(Boolean.TRUE, data.isTirePressureWarning());
        assertEquals(Boolean.TRUE, data.bool("LOCK_STATE_VEHICLE", "isLocked"));
        assertNotNull(data.timestamp("CHARGING_SUMMARY", "targetDateTimeWithOffset"));
        assertNotNull(data.timestamp("GPS_LOCATION", "lastModified"));
    }

    @Test
    void chargingTargetVariants() {
        VehicleData direct = VehicleData.fromListEntry(new JsonObject()).merge(JsonParser.parseString("""
                {"measurements":[{"key":"CHARGING_SUMMARY","status":{"isEnabled":true},"value":{"mode":"DIRECT"}},
                 {"key":"CHARGING_RATE","status":{"isEnabled":true},"value":{"chargingRate":0,"chargingPower":0}}]}
                """).getAsJsonObject());
        assertEquals(100.0, direct.getChargingTarget());
        assertEquals(0.0, direct.getChargingRateKmh());
        assertTrue(direct.isDirectChargeOn());

        VehicleData departures = VehicleData.fromListEntry(new JsonObject()).merge(JsonParser.parseString("""
                {"measurements":[{"key":"CHARGING_SUMMARY","status":{"isEnabled":true},"value":{"mode":"TIMER"}},
                 {"key":"DEPARTURES","status":{"isEnabled":true},"value":{"list":[]}},
                 {"key":"CHARGING_SETTINGS","status":{"isEnabled":true},"value":{"targetSoc":70}}]}
                """).getAsJsonObject());
        assertEquals(70.0, departures.getChargingTarget());

        VehicleData explicit = VehicleData.fromListEntry(new JsonObject())
                .merge(JsonParser
                        .parseString(
                                """
                                        {"measurements":[{"key":"CHARGING_SUMMARY","status":{"isEnabled":true},"value":{"targetSoC":90,"mode":"DIRECT"}}]}
                                        """)
                        .getAsJsonObject());
        assertEquals(90.0, explicit.getChargingTarget());
    }
}
