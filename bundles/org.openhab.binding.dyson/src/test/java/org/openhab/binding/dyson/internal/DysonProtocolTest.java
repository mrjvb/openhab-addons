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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.dyson.internal.api.DysonApiException;
import org.openhab.binding.dyson.internal.api.DysonCredentialDecoder;
import org.openhab.binding.dyson.internal.device.DysonAirQuality;
import org.openhab.binding.dyson.internal.device.DysonDeviceState;
import org.openhab.binding.dyson.internal.device.DysonDeviceState.MessageType;
import org.openhab.binding.dyson.internal.device.DysonOscillation;
import org.openhab.binding.dyson.internal.device.DysonOscillation.Angles;
import org.openhab.binding.dyson.internal.handler.DysonChannelUtil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the Dyson protocol helpers.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonProtocolTest {

    private static String encrypt(String plain) throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        int padding = 16 - data.length % 16;
        byte[] padded = Arrays.copyOf(data, data.length + padding);
        Arrays.fill(padded, data.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static JsonObject json(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void decryptLocalCredentials() throws Exception {
        String encrypted = encrypt("{\"serial\":\"VS6-EU-HJA1234A\",\"apPasswordHash\":\"abc123==\"}");
        assertEquals("abc123==", DysonCredentialDecoder.decryptLocalCredentials(encrypted));
    }

    @Test
    public void decryptLocalCredentialsWithTrailingData() throws Exception {
        String encrypted = encrypt("{\"apPasswordHash\":\"robot\"}{\"extra\":1}");
        assertEquals("robot", DysonCredentialDecoder.decryptLocalCredentials(encrypted));
    }

    @Test
    public void decryptInvalidCredentials() {
        assertThrows(DysonApiException.class, () -> DysonCredentialDecoder.decryptLocalCredentials("bm90LWFlcw=="));
        assertThrows(DysonApiException.class, () -> DysonCredentialDecoder.decryptLocalCredentials(""));
    }

    @Test
    public void processCurrentStateAndStateChange() {
        DysonDeviceState state = new DysonDeviceState();
        assertEquals(MessageType.CURRENT_STATE, state.process(json(
                "{\"msg\":\"CURRENT-STATE\",\"rssi\":\"-52\",\"product-state\":{\"fpwr\":\"OFF\",\"fnsp\":\"0004\"}}")));
        assertEquals("OFF", state.get("fpwr"));
        assertEquals("-52", state.getTopLevelString("rssi"));
        assertEquals("fpwr", state.getPowerControlKey());

        assertEquals(MessageType.STATE_CHANGE, state.process(json(
                "{\"msg\":\"STATE-CHANGE\",\"product-state\":{\"fpwr\":[\"OFF\",\"ON\"],\"fnsp\":[\"0004\",\"AUTO\"]}}")));
        assertEquals("ON", state.get("fpwr"));
        assertEquals("AUTO", state.get("fnsp"));
    }

    @Test
    public void pendingCommandIgnoresStaleSnapshot() {
        DysonDeviceState state = new DysonDeviceState();
        state.process(json("{\"msg\":\"CURRENT-STATE\",\"product-state\":{\"nmod\":\"OFF\"}}"));
        state.applyCommand(Map.of("nmod", "ON"));
        state.process(json("{\"msg\":\"CURRENT-STATE\",\"product-state\":{\"nmod\":\"OFF\"}}"));
        assertEquals("ON", state.get("nmod"));
        // An authoritative STATE-CHANGE always applies
        state.process(json("{\"msg\":\"STATE-CHANGE\",\"product-state\":{\"nmod\":[\"ON\",\"OFF\"]}}"));
        assertEquals("OFF", state.get("nmod"));
    }

    @Test
    public void processEnvironmentFaultsAndRobotState() {
        DysonDeviceState state = new DysonDeviceState();
        assertEquals(MessageType.ENVIRONMENT, state.process(json(
                "{\"msg\":\"ENVIRONMENTAL-CURRENT-SENSOR-DATA\",\"data\":{\"tact\":\"2951\",\"hact\":\"0045\",\"p25r\":\"0012\"}}")));
        assertEquals("2951", state.getEnvironment("tact"));
        assertEquals(MessageType.FAULTS, state.process(json(
                "{\"msg\":\"CURRENT-FAULTS\",\"product-errors\":{\"tnke\":\"FAIL\"},\"product-warnings\":{\"fltr\":\"OK\"}}")));
        assertEquals("FAIL", state.getFaults().get("tnke"));
        assertEquals("OK", state.getFaults().get("fltr"));

        state.process(json("{\"msg\":\"STATE-CHANGE\",\"oldstate\":\"INACTIVE_CHARGED\","
                + "\"newstate\":\"FULL_CLEAN_RUNNING\",\"batteryChargeLevel\":95}"));
        assertEquals("FULL_CLEAN_RUNNING", state.getTopLevelString("state"));
        assertEquals("95", state.getTopLevelString("batteryChargeLevel"));
    }

    @Test
    public void legacyPowerControlKey() {
        DysonDeviceState state = new DysonDeviceState();
        state.process(json("{\"msg\":\"CURRENT-STATE\",\"product-state\":{\"fmod\":\"AUTO\"}}"));
        assertEquals("fmod", state.getPowerControlKey());
    }

    @Test
    public void temperatureConversion() {
        assertEquals("22.0", String.valueOf(DysonChannelUtil.deciKelvinToCelsius("2951")));
        assertEquals("2952", DysonChannelUtil.celsiusToDeciKelvin(22));
        assertNull(DysonChannelUtil.deciKelvinToCelsius("OFF"));
    }

    @Test
    public void airQualityIndex() {
        DysonAirQuality.Result result = DysonAirQuality
                .calculate(Map.of("p25r", "0012", "p10r", "0080", "va10", "INIT"));
        assertNotNull(result);
        assertEquals(DysonAirQuality.POOR, result.category());
        assertEquals("PM10", result.dominantPollutants().get(0));
        assertNull(DysonAirQuality.calculate(Map.of("pm25", "OFF")));
    }

    @Test
    public void oscillationAngles() {
        Angles preset = DysonOscillation.forPreset(90, 175, 0, 350);
        assertEquals(130, preset.lower());
        assertEquals(220, preset.upper());
        Angles atEdge = DysonOscillation.forPreset(180, 20, 0, 350);
        assertEquals(0, atEdge.lower());
        assertEquals(180, atEdge.upper());
        assertEquals(new Angles(0, 350), DysonOscillation.forPreset(350, 100, 0, 350));
        Angles moved = DysonOscillation.withCenter(new Angles(100, 200), 300, 0, 350);
        assertEquals(250, moved.lower());
        assertEquals(350, moved.upper());
        Angles widened = DysonOscillation.withSpan(new Angles(150, 200), 100, 0, 350);
        assertEquals(125, widened.lower());
        assertEquals(225, widened.upper());
    }
}
