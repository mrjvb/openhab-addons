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
package org.openhab.binding.dyson.internal.api;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

/**
 * Decrypts the local MQTT broker credentials that the Dyson cloud manifest returns for each device.
 *
 * <p>
 * The credentials are AES-256-CBC encrypted with a fixed key (bytes 1..32) and a zero IV. The plain text is a JSON
 * object holding the MQTT password in {@code apPasswordHash}. Some robot vacuums append extra data after the JSON.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class DysonCredentialDecoder {

    private DysonCredentialDecoder() {
    }

    public static String decryptLocalCredentials(String encrypted) throws DysonApiException {
        if (encrypted.isBlank()) {
            throw new DysonApiException("Device has no local MQTT credentials");
        }
        try {
            byte[] key = new byte[32];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (i + 1);
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted.trim()));
            String text = stripPadding(new String(plain, StandardCharsets.UTF_8));

            // parseReader reads leniently and stops after the first JSON value, ignoring trailing data
            JsonReader reader = new JsonReader(new StringReader(text));
            JsonElement element = JsonParser.parseReader(reader);
            if (element instanceof JsonObject object && object.has("apPasswordHash")) {
                return object.get("apPasswordHash").getAsString();
            }
            throw new DysonApiException("Decrypted credentials do not contain a password");
        } catch (GeneralSecurityException | IllegalArgumentException | JsonParseException | IllegalStateException e) {
            throw new DysonApiException("Failed to decrypt local credentials: " + e.getMessage(), e);
        }
    }

    private static String stripPadding(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) < 0x20) {
            end--;
        }
        return text.substring(0, end);
    }
}
