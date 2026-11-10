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
package org.openhab.binding.icloud.internal.utilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonSyntaxException;

/**
 * Parses responses of Apple's {@code idmsa.apple.com/appleauth/auth} endpoints, following the behaviour of pyicloud
 * (https://github.com/timlaing/pyicloud).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class AuthResponseParser {

    /** HTTP status Apple uses for "2-FA required", also returned by some successful 2-FA steps. */
    public static final int STATUS_TWO_FACTOR_REQUIRED = 409;

    /** Matches the first {@code <script>} whose class list contains the token {@code boot_args}. */
    private static final Pattern BOOT_ARGS_SCRIPT = Pattern.compile(
            "<script\\b[^>]*\\bclass\\s*=\\s*[\"'](?:[^\"']*\\s)?boot_args(?:\\s[^\"']*)?[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AuthResponseParser() {
    }

    /**
     * Extract the 2-FA (MFA) options from the response of {@code GET /appleauth/auth}.
     *
     * Apple answers either with JSON or with an HTML page that embeds the options as JSON in a
     * {@code <script class="boot_args">} tag. In the HTML variant the options are nested below
     * {@code direct.twoSV.bridgeInitiateData}; they are flattened so that {@code phoneNumberVerification} and
     * {@code trustedPhoneNumber} are top-level keys like in the JSON variant.
     *
     * @param body response body (JSON or HTML)
     * @return auth options, or {@code null} if the body contains none
     */
    public static @Nullable Map<String, Object> parseMfaAuthOptions(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String json = body.strip();
        if (!json.startsWith("{")) {
            Matcher matcher = BOOT_ARGS_SCRIPT.matcher(body);
            if (!matcher.find()) {
                return null;
            }
            json = matcher.group(1).strip();
        }

        Map<String, Object> payload;
        try {
            payload = JsonUtils.toMap(json);
        } catch (JsonSyntaxException e) {
            return null;
        }
        if (payload == null) {
            return null;
        }

        Map<String, Object> direct = asMap(payload.get("direct"));
        return direct.isEmpty() ? payload : fromBootArgs(direct);
    }

    /**
     * Check whether an HTTP 409 on code verification means the security code was accepted. Apple sometimes answers
     * a valid code with 409 and {@code {"securityCode": {"valid": true}}} in the body.
     *
     * @param statusCode HTTP status code of the verification response
     * @param body response body
     * @return {@code true} if the code was accepted despite the error status
     */
    public static boolean isAcceptedSecurityCodeConflict(int statusCode, @Nullable String body) {
        if (statusCode != STATUS_TWO_FACTOR_REQUIRED || body == null || body.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> payload = JsonUtils.toMap(body);
            if (payload == null) {
                return false;
            }
            return Boolean.TRUE.equals(asMap(payload.get("securityCode")).get("valid"));
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    private static Map<String, Object> fromBootArgs(Map<String, Object> direct) {
        Map<String, Object> result = new HashMap<>();
        Object authInitialRoute = direct.get("authInitialRoute");
        result.put("authInitialRoute", authInitialRoute != null ? authInitialRoute.toString() : "");
        result.put("hasTrustedDevices", Boolean.TRUE.equals(direct.get("hasTrustedDevices")));

        Map<String, Object> twoSV = asMap(direct.get("twoSV"));
        if (twoSV.get("authFactors") instanceof List<?> authFactors && !authFactors.isEmpty()) {
            result.put("authFactors", authFactors.stream().filter(String.class::isInstance).toList());
        }
        Object sourceAppId = twoSV.get("sourceAppId");
        if (sourceAppId != null) {
            // Gson parses all JSON numbers as Double; keep integral ids free of a ".0" suffix.
            result.put("sourceAppId",
                    sourceAppId instanceof Double number && number == Math.rint(number)
                            ? Long.toString(number.longValue())
                            : sourceAppId.toString());
        }

        Map<String, Object> bridgeInitiateData = asMap(twoSV.get("bridgeInitiateData"));
        if (!bridgeInitiateData.isEmpty()) {
            result.put("bridgeInitiateData", bridgeInitiateData);
        }

        Map<String, Object> phoneNumberVerification = asMap(bridgeInitiateData.get("phoneNumberVerification"));
        if (!phoneNumberVerification.isEmpty()) {
            result.put("phoneNumberVerification", phoneNumberVerification);
            Map<String, Object> trustedPhoneNumber = asMap(phoneNumberVerification.get("trustedPhoneNumber"));
            if (!trustedPhoneNumber.isEmpty()) {
                result.put("trustedPhoneNumber", trustedPhoneNumber);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
