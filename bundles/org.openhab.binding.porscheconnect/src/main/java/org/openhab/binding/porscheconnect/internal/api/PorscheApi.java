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

import static org.openhab.binding.porscheconnect.internal.api.PorscheApiConstants.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.porscheconnect.internal.api.PorscheCaptchaRequiredException.PendingLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Porsche Connect REST API client for one account: token handling, retries, vehicle data and remote commands. Thread
 * safe.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheApi {

    private static final Set<Integer> RETRY_STATUS = Set.of(429, 502, 503, 504);
    private static final int MAX_RETRIES = 3;
    private static final long MAX_RETRY_DELAY_SECONDS = 30;
    private static final long TOKEN_LEEWAY_SECONDS = 60;

    private static final Duration COMMAND_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(240);

    private final Logger logger = LoggerFactory.getLogger(PorscheApi.class);
    private final HttpClient httpClient;
    private final PorscheAuth auth;
    private final Consumer<PorscheToken> tokenListener;
    private final Object tokenLock = new Object();

    private volatile PorscheToken token;

    /**
     * @param initialToken previously persisted token, or {@link PorscheToken#EMPTY}
     * @param tokenListener called whenever a new token was obtained, so it can be persisted
     */
    public PorscheApi(HttpClient httpClient, String email, String password, PorscheToken initialToken,
            Consumer<PorscheToken> tokenListener) {
        this.httpClient = httpClient;
        this.auth = new PorscheAuth(httpClient, email, password);
        this.token = initialToken;
        this.tokenListener = tokenListener;
    }

    /**
     * Makes sure a valid access token is available, refreshing or logging in as needed.
     *
     * @param captchaCode captcha answer for a pending login, or {@code null}
     * @param pendingLogin state of the pending login the captcha belongs to, or {@code null}
     */
    public void authenticate(@Nullable String captchaCode, @Nullable PendingLogin pendingLogin)
            throws PorscheApiException, InterruptedException {
        synchronized (tokenLock) {
            PorscheToken current = token;
            if (current.hasAccessToken() && !current.isExpired(TOKEN_LEEWAY_SECONDS)) {
                return;
            }
            PorscheToken newToken = PorscheToken.EMPTY;
            if (current.hasRefreshToken()) {
                newToken = auth.refresh(current);
            }
            if (!newToken.hasAccessToken()) {
                newToken = auth.login(captchaCode, pendingLogin);
            }
            setToken(newToken);
        }
    }

    private void invalidateAccessToken() {
        synchronized (tokenLock) {
            PorscheToken current = token;
            setToken(new PorscheToken(null, current.refreshToken(), 0));
        }
    }

    private void setToken(PorscheToken newToken) {
        token = newToken;
        tokenListener.accept(newToken);
    }

    // ---------------------------------------------------------------- vehicle data

    /** {@code GET /connect/v1/vehicles}: list of vehicles of the account. */
    public List<VehicleData> getVehicles() throws PorscheApiException, InterruptedException {
        JsonElement result = request(HttpMethod.GET, VEHICLES_PATH, null);
        if (!result.isJsonArray()) {
            throw new PorscheApiException("Unexpected vehicle list response");
        }
        return result.getAsJsonArray().asList().stream().filter(JsonElement::isJsonObject)
                .map(e -> VehicleData.fromListEntry(e.getAsJsonObject())).toList();
    }

    /**
     * Overview with all known measurements.
     *
     * @param wakeUp {@code false} returns the values last stored in the cloud, {@code true} asks the vehicle to send
     *            fresh data (drains the 12V battery a little, use sparingly)
     */
    public JsonObject getOverview(String vin, boolean wakeUp) throws PorscheApiException, InterruptedException {
        StringBuilder path = new StringBuilder(vehiclePath(vin)).append('?').append(filter("mf", MEASUREMENTS));
        if (wakeUp) {
            path.append("&wakeUpJob=").append(UUID.randomUUID());
        }
        return asObject(request(HttpMethod.GET, path.toString(), null));
    }

    /** Measurements plus the availability of the commands in {@link PorscheApiConstants#COMMANDS}. */
    public JsonObject getCapabilities(String vin) throws PorscheApiException, InterruptedException {
        String path = vehiclePath(vin) + "?" + filter("mf", MEASUREMENTS) + "&" + filter("cf", COMMANDS);
        return asObject(request(HttpMethod.GET, path, null));
    }

    /** Picture URLs by view name, e.g. {@code frontView}. */
    public JsonArray getPictures(String vin) throws PorscheApiException, InterruptedException {
        JsonElement result = request(HttpMethod.GET, vehiclePath(vin) + "/pictures", null);
        return result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
    }

    /** Downloads a vehicle picture (public CDN URL, no authentication). */
    public @Nullable ContentResponse downloadPicture(String url) throws InterruptedException {
        try {
            ContentResponse response = httpClient.newRequest(url).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).send();
            return response.getStatus() == HttpStatus.OK_200 ? response : null;
        } catch (TimeoutException | ExecutionException | IllegalArgumentException e) {
            logger.debug("Could not download vehicle picture: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- remote commands

    public void flashIndicators(String vin, boolean honk) throws PorscheApiException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("mode", honk ? "HONK_AND_FLASH" : "FLASH");
        payload.add("spin", JsonNull.INSTANCE);
        sendCommand(vin, CMD_HONK_FLASH, payload);
    }

    public void lock(String vin) throws PorscheApiException, InterruptedException {
        sendCommand(vin, CMD_LOCK, spinNull());
    }

    /**
     * Unlocks with the 4 digit Porsche ID PIN. The PIN is never sent; a SHA-512 over PIN and a server challenge is.
     */
    public void unlock(String vin, String pin) throws PorscheApiException, InterruptedException {
        if (!pin.matches("\\d{4}")) {
            throw new PorscheApiException("PIN must consist of 4 digits");
        }
        JsonObject response = asObject(
                request(HttpMethod.POST, vehiclePath(vin) + "/commands", command(CMD_SPIN_CHALLENGE, spinNull())));
        String challenge = PorscheAuth.getString(PorscheAuth.getObject(response, "data"), "challenge");
        if (challenge == null) {
            throw new PorscheRemoteServiceException("No SPIN challenge received");
        }
        JsonObject spin = new JsonObject();
        spin.addProperty("challenge", challenge);
        spin.addProperty("hash", spinHash(pin, challenge));
        JsonObject payload = new JsonObject();
        payload.add("spin", spin);
        sendCommand(vin, CMD_UNLOCK, payload);
    }

    static String spinHash(String pin, String challenge) {
        try {
            byte[] input = HexFormat.of().parseHex(pin + challenge);
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(input);
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }

    public void setDirectCharging(String vin, boolean on) throws PorscheApiException, InterruptedException {
        sendCommand(vin, on ? CMD_DIRECT_CHARGING_START : CMD_DIRECT_CHARGING_STOP, spinNull());
    }

    public void stopClimatisation(String vin) throws PorscheApiException, InterruptedException {
        sendCommand(vin, CMD_CLIMATIZER_STOP, new JsonObject());
    }

    /**
     * Starts remote climatisation after checking the command is available and the temperature is in the range the
     * vehicle accepts.
     */
    public void startClimatisation(String vin, double targetCelsius) throws PorscheApiException, InterruptedException {
        double targetKelvin = Math.round((targetCelsius + 273.15) * 100) / 100.0;
        JsonObject capability = findCommandCapability(getCapabilities(vin), CMD_CLIMATIZER_START);
        if (capability == null) {
            throw new PorscheRemoteServiceException("Vehicle does not report remote climatisation capability");
        }
        JsonObject status = PorscheAuth.getObject(capability, "status");
        if (VehicleData.bool(status, "isEnabled") != Boolean.TRUE) {
            String cause = PorscheAuth.getString(status, "cause");
            throw new PorscheRemoteServiceException(
                    "Remote climatisation is not available (cause: " + (cause != null ? cause : "unknown") + ")");
        }
        JsonObject metadata = PorscheAuth.getObject(capability, "metadata");
        Double min = VehicleData.num(metadata, "minTemperature");
        Double max = VehicleData.num(metadata, "maxTemperature");
        if (min != null && max != null && (targetKelvin < min || targetKelvin > max)) {
            throw new PorscheRemoteServiceException(String.format("Target temperature %.1f °C outside %.1f-%.1f °C",
                    targetCelsius, min - 273.15, max - 273.15));
        }
        JsonObject zones = new JsonObject();
        zones.addProperty("frontLeft", false);
        zones.addProperty("frontRight", false);
        zones.addProperty("rearLeft", false);
        zones.addProperty("rearRight", false);
        JsonObject payload = new JsonObject();
        payload.add("spin", JsonNull.INSTANCE);
        payload.add("climateZonesEnabled", zones);
        payload.addProperty("targetTemperature", targetKelvin);
        sendCommand(vin, CMD_CLIMATIZER_START, payload);
    }

    /**
     * Sets the target state of charge (25-100 %). Vehicles with departure timers use the charging settings, others
     * the active charging profile.
     */
    public void setTargetSoc(VehicleData vehicle, int targetSoc) throws PorscheApiException, InterruptedException {
        int soc = Math.min(Math.max(targetSoc, 25), 100);
        String vin = vehicle.getVin();
        if (vehicle.has("DEPARTURES")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("targetSoc", soc);
            payload.add("spin", JsonNull.INSTANCE);
            sendCommand(vin, CMD_CHARGING_SETTINGS_EDIT, payload);
            return;
        }
        JsonArray profiles = vehicle.getChargingProfiles();
        if (profiles == null) {
            throw new PorscheRemoteServiceException("Vehicle reports no charging profiles");
        }
        JsonArray updated = profiles.deepCopy();
        boolean found = false;
        for (JsonElement element : updated) {
            if (element.isJsonObject() && VehicleData.bool(element.getAsJsonObject(), "isEnabled") == Boolean.TRUE) {
                element.getAsJsonObject().addProperty("minSoc", soc);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new PorscheRemoteServiceException("No active charging profile");
        }
        JsonObject payload = new JsonObject();
        payload.add("list", updated);
        sendCommand(vin, CMD_CHARGING_PROFILES_EDIT, payload);
    }

    /**
     * Sends a command and, if the backend accepted it, waits until the vehicle confirms or rejects it.
     */
    private void sendCommand(String vin, String key, JsonObject payload)
            throws PorscheApiException, InterruptedException {
        logger.debug("Sending command {} to {}", key, vin);
        JsonObject response = asObject(request(HttpMethod.POST, vehiclePath(vin) + "/commands", command(key, payload)));
        JsonObject status = PorscheAuth.getObject(response, "status");
        String statusId = PorscheAuth.getString(status, "id");
        String result = PorscheAuth.getString(status, "result");
        logger.debug("Command {} result: {} ({})", key, result, statusId);
        if (statusId == null || !"ACCEPTED".equals(result)) {
            if ("ERROR".equals(result)) {
                throw new PorscheRemoteServiceException("Command " + key + " failed: " + response);
            }
            return;
        }
        Instant failAfter = Instant.now().plus(COMMAND_TIMEOUT);
        String state = "UNKNOWN";
        while (Instant.now().isBefore(failAfter)) {
            Thread.sleep(COMMAND_POLL_INTERVAL.toMillis());
            JsonObject statusMsg = asObject(request(HttpMethod.GET, vehiclePath(vin) + "/commands/" + statusId, null));
            String current = PorscheAuth.getString(PorscheAuth.getObject(statusMsg, "status"), "result");
            state = current != null ? current : "UNKNOWN";
            logger.trace("Command {} state: {}", key, state);
            switch (state) {
                case "PERFORMED":
                    return;
                case "ERROR":
                    throw new PorscheRemoteServiceException("Command " + key + " failed: " + statusMsg);
                default:
                    // ACCEPTED, IN_PROGRESS, UNKNOWN...: keep waiting
            }
        }
        throw new PorscheRemoteServiceException(
                "No result for command " + key + " within " + COMMAND_TIMEOUT.toSeconds() + "s (last: " + state + ")");
    }

    private static @Nullable JsonObject findCommandCapability(JsonObject capabilities, String key) {
        JsonElement commands = capabilities.get("commands");
        if (commands == null || !commands.isJsonArray()) {
            return null;
        }
        for (JsonElement element : commands.getAsJsonArray()) {
            if (element.isJsonObject() && key.equals(PorscheAuth.getString(element.getAsJsonObject(), "key"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject command(String key, JsonObject payload) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.add("payload", payload);
        return body;
    }

    private static JsonObject spinNull() {
        JsonObject payload = new JsonObject();
        payload.add("spin", JsonNull.INSTANCE);
        return payload;
    }

    // ---------------------------------------------------------------- HTTP

    private JsonElement request(HttpMethod method, String path, @Nullable JsonObject body)
            throws PorscheApiException, InterruptedException {
        boolean reauthenticated = false;
        for (int attempt = 0;; attempt++) {
            authenticate(null, null);
            Request request = httpClient.newRequest(API_BASE_URL + path).method(method).agent(USER_AGENT)
                    .header("X-Client-ID", X_CLIENT_ID)
                    .header(HttpHeader.AUTHORIZATION, "Bearer " + token.accessToken())
                    .header(HttpHeader.ACCEPT, "application/json").timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (body != null) {
                request.content(new StringContentProvider("application/json", body.toString(), StandardCharsets.UTF_8));
            }
            ContentResponse response;
            try {
                response = request.send();
            } catch (TimeoutException | ExecutionException e) {
                throw new PorscheApiException(
                        "Request " + method + " " + stripQuery(path) + " failed: " + e.getMessage(), e);
            }
            int status = response.getStatus();
            if (HttpStatus.isSuccess(status)) {
                String content = response.getContentAsString();
                if (content == null || content.isBlank()) {
                    return JsonNull.INSTANCE;
                }
                try {
                    return JsonParser.parseString(content);
                } catch (JsonParseException e) {
                    throw new PorscheApiException("Invalid JSON from " + stripQuery(path), e);
                }
            }
            if (status == HttpStatus.UNAUTHORIZED_401 && !reauthenticated) {
                // access token revoked server side: get a new one and try once more
                logger.debug("API returned 401, renewing access token");
                invalidateAccessToken();
                reauthenticated = true;
                continue;
            }
            if (RETRY_STATUS.contains(status) && attempt < MAX_RETRIES) {
                long delay = retryDelaySeconds(response.getHeaders().get(HttpHeader.RETRY_AFTER), attempt);
                logger.debug("Transient HTTP {} on {}, retrying in {}s ({}/{})", status, stripQuery(path), delay,
                        attempt + 1, MAX_RETRIES);
                Thread.sleep(delay * 1000);
                continue;
            }
            String content = response.getContentAsString();
            throw new PorscheApiException(status,
                    stripQuery(path) + (content != null && !content.isBlank() ? " " + abbreviate(content, 500) : ""));
        }
    }

    static long retryDelaySeconds(@Nullable String retryAfter, int attempt) {
        if (retryAfter != null && retryAfter.matches("\\d+")) {
            return Math.min(Long.parseLong(retryAfter), MAX_RETRY_DELAY_SECONDS);
        }
        return Math.min(1L << (attempt + 1), MAX_RETRY_DELAY_SECONDS);
    }

    private static JsonObject asObject(JsonElement element) throws PorscheApiException {
        if (!element.isJsonObject()) {
            throw new PorscheApiException("Unexpected response, expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static String vehiclePath(String vin) {
        return VEHICLES_PATH + "/" + vin;
    }

    private static String filter(String name, List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(name).append('=').append(value);
        }
        return sb.toString();
    }

    private static String stripQuery(String path) {
        int idx = path.indexOf('?');
        return idx >= 0 ? path.substring(0, idx) : path;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
