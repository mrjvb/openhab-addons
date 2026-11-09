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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.dyson.internal.api.dto.DysonIotCredentials;
import org.openhab.binding.dyson.internal.api.dto.DysonLoginResponse;
import org.openhab.binding.dyson.internal.api.dto.DysonManifestDevice;
import org.openhab.binding.dyson.internal.api.dto.DysonPendingRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Client for the Dyson cloud REST API, as used by the MyDyson app.
 *
 * <p>
 * The authentication flow is: {@link #provision()}, {@link #getUserStatus(String, boolean)},
 * {@link #beginLogin(String, boolean)} (Dyson sends a one-time password by e-mail or SMS) and
 * {@link #completeLogin(String, String, String, String, boolean)}, which returns a bearer token.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonCloudApi {
    private static final String USER_AGENT = "DysonLink/205298 Darwin/27.0.0";
    private static final String HOST_GLOBAL = "https://appapi.cp.dyson.com";
    private static final String HOST_CHINA = "https://appapi.cp.dyson.cn";
    private static final long TIMEOUT_SECONDS = 20;

    private final Logger logger = LoggerFactory.getLogger(DysonCloudApi.class);
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String country;
    private final String culture;
    private @Nullable String token;

    public DysonCloudApi(HttpClient httpClient, String country, String culture) {
        this.httpClient = httpClient;
        this.country = country.isBlank() ? "US" : country.toUpperCase(Locale.ROOT);
        this.culture = culture.isBlank() ? "en-US" : culture;
        this.baseUrl = "CN".equals(this.country) ? HOST_CHINA : HOST_GLOBAL;
    }

    public void setToken(@Nullable String token) {
        this.token = token == null || token.isBlank() ? null : token;
    }

    public @Nullable String getToken() {
        return token;
    }

    /**
     * Mandatory first call; the API ignores clients which have not provisioned recently.
     */
    public void provision() throws DysonApiException {
        execute(HttpMethod.GET, "/v1/provisioningservice/application/Android/version", null, null, false);
    }

    public @Nullable String getUserStatus(String login, boolean mobile) throws DysonApiException {
        String path = "/v3/userregistration/" + (mobile ? "mobile" : "email") + "/userstatus";
        DysonLoginResponse response = parse(execute(HttpMethod.POST, path, Map.of("country", country),
                Map.of(mobile ? "mobile" : "email", login), false), DysonLoginResponse.class);
        return response.accountStatus;
    }

    /**
     * Starts a login. Dyson sends a one-time password to the e-mail address or mobile number.
     *
     * @return the challenge id required to complete the login
     */
    public String beginLogin(String login, boolean mobile) throws DysonApiException {
        String path = "/v3/userregistration/" + (mobile ? "mobile" : "email") + "/auth";
        DysonLoginResponse response = parse(execute(HttpMethod.POST, path,
                Map.of("country", country, "culture", culture), Map.of(mobile ? "mobile" : "email", login), false),
                DysonLoginResponse.class);
        String challengeId = response.challengeId;
        if (challengeId == null || challengeId.isBlank()) {
            throw new DysonApiException("Dyson API did not return a login challenge");
        }
        return challengeId;
    }

    /**
     * Completes a login with the one-time password.
     *
     * @return the bearer token, which is also stored in this client
     */
    public String completeLogin(String challengeId, String login, String password, String otp, boolean mobile)
            throws DysonApiException {
        String path = "/v3/userregistration/" + (mobile ? "mobile" : "email") + "/verify";
        Map<String, String> payload = mobile ? Map.of("challengeId", challengeId, "mobile", login, "otpCode", otp)
                : Map.of("challengeId", challengeId, "email", login, "otpCode", otp, "password", password);
        DysonLoginResponse response = parse(
                execute(HttpMethod.POST, path, Map.of("country", country, "culture", culture), payload, false),
                DysonLoginResponse.class);
        String newToken = response.token;
        if (newToken == null || newToken.isBlank()) {
            throw new DysonAuthException("Dyson API did not return a token");
        }
        this.token = newToken;
        return newToken;
    }

    public List<DysonManifestDevice> getDevices() throws DysonApiException {
        DysonManifestDevice[] devices = parse(execute(HttpMethod.GET, "/v3/manifest", null, null, true),
                DysonManifestDevice[].class);
        return Arrays.stream(devices).filter(d -> d.serialNumber != null).toList();
    }

    public DysonIotCredentials getIotCredentials(String serialNumber) throws DysonApiException {
        return parse(
                execute(HttpMethod.POST, "/v2/authorize/iot-credentials", null, Map.of("Serial", serialNumber), true),
                DysonIotCredentials.class);
    }

    public @Nullable DysonPendingRelease getPendingRelease(String serialNumber) throws DysonApiException {
        try {
            return parse(
                    execute(HttpMethod.GET, "/v1/assets/devices/" + serialNumber + "/pendingrelease", null, null, true),
                    DysonPendingRelease.class);
        } catch (DysonAuthException e) {
            throw e;
        } catch (DysonApiException e) {
            // 404 and empty responses simply mean that there is no pending release
            logger.debug("No pending release for {}: {}", serialNumber, e.getMessage());
            return null;
        }
    }

    public void triggerFirmwareUpdate(String serialNumber) throws DysonApiException {
        execute(HttpMethod.POST, "/v1/assets/devices/" + serialNumber + "/pendingrelease", null, null, true);
    }

    private <T> T parse(String json, Class<T> clazz) throws DysonApiException {
        try {
            @Nullable
            T result = gson.fromJson(json, clazz);
            if (result == null) {
                throw new DysonApiException("Empty response from Dyson API");
            }
            return result;
        } catch (JsonParseException e) {
            throw new DysonApiException("Invalid response from Dyson API: " + e.getMessage(), e);
        }
    }

    private String execute(HttpMethod method, String path, @Nullable Map<String, String> query,
            @Nullable Object payload, boolean authenticated) throws DysonApiException {
        String url = baseUrl + path;
        if (query != null && !query.isEmpty()) {
            url += "?" + query.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
        }
        Request request = httpClient.newRequest(url).method(method).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .header(HttpHeader.USER_AGENT, USER_AGENT).header(HttpHeader.ACCEPT, "application/json");
        if (authenticated) {
            String currentToken = token;
            if (currentToken == null) {
                throw new DysonAuthException("Not logged in to the Dyson cloud");
            }
            request.header(HttpHeader.AUTHORIZATION, "Bearer " + currentToken);
        }
        if (payload != null) {
            request.content(new StringContentProvider(gson.toJson(payload), StandardCharsets.UTF_8),
                    "application/json");
        } else if (method == HttpMethod.POST) {
            request.header(HttpHeader.CACHE_CONTROL, "no-cache");
        }

        logger.trace("Dyson API request {} {}", method, path);
        ContentResponse response;
        try {
            response = request.send();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DysonApiException("Interrupted while calling the Dyson API", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new DysonApiException("Unable to reach the Dyson API: " + e.getMessage(), e);
        }

        int status = response.getStatus();
        String body = response.getContentAsString();
        logger.trace("Dyson API response {} {}: {}", status, path, body);
        if (status == HttpStatus.UNAUTHORIZED_401 || status == HttpStatus.FORBIDDEN_403) {
            throw new DysonAuthException("Dyson API rejected the request (HTTP " + status + ")");
        }
        if (status == HttpStatus.BAD_REQUEST_400 && path.startsWith("/v3/userregistration")) {
            throw new DysonAuthException("Dyson API rejected the login (HTTP 400): " + body);
        }
        if (status < 200 || status >= 300) {
            throw new DysonApiException("Dyson API returned HTTP " + status + " for " + path);
        }
        return body;
    }
}
