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
package org.openhab.binding.porscheconnect.internal.handler;

import static org.openhab.binding.porscheconnect.internal.PorscheConnectBindingConstants.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.porscheconnect.internal.api.PorscheApi;
import org.openhab.binding.porscheconnect.internal.api.PorscheApiException;
import org.openhab.binding.porscheconnect.internal.api.PorscheAuthException;
import org.openhab.binding.porscheconnect.internal.api.PorscheCaptchaRequiredException;
import org.openhab.binding.porscheconnect.internal.api.PorscheCaptchaRequiredException.PendingLogin;
import org.openhab.binding.porscheconnect.internal.api.PorscheToken;
import org.openhab.binding.porscheconnect.internal.api.VehicleData;
import org.openhab.binding.porscheconnect.internal.config.AccountConfiguration;
import org.openhab.binding.porscheconnect.internal.discovery.PorscheVehicleDiscoveryService;
import org.openhab.core.OpenHAB;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.RawType;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Bridge for one Porsche ID: owns the HTTP client, the tokens and the polling of all vehicles of the account.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheAccountHandler extends BaseBridgeHandler {

    private static final String HTTP_CLIENT_NAME = "porscheconnect";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PENDING_LOGIN = "pendingLogin";
    private static final int MIN_REFRESH_MINUTES = 5;

    private final Logger logger = LoggerFactory.getLogger(PorscheAccountHandler.class);
    private final Gson gson = new Gson();
    private final HttpClientFactory httpClientFactory;
    private final Storage<String> storage;

    private final Map<String, VehicleData> vehicles = new ConcurrentHashMap<>();
    private final Set<PorscheVehicleDiscoveryService> discoveryServices = ConcurrentHashMap.newKeySet();

    private @Nullable HttpClient httpClient;
    private volatile @Nullable PorscheApi api;
    private volatile @Nullable ScheduledFuture<?> pollJob;

    public PorscheAccountHandler(Bridge bridge, HttpClientFactory httpClientFactory, Storage<String> storage) {
        super(bridge);
        this.httpClientFactory = httpClientFactory;
        this.storage = storage;
    }

    @Override
    public void initialize() {
        AccountConfiguration config = getConfigAs(AccountConfiguration.class);
        if (config.email.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.credentials");
            return;
        }

        HttpClient client = httpClientFactory.createHttpClient(HTTP_CLIENT_NAME);
        // redirects are handled explicitly by the login flow
        client.setFollowRedirects(false);
        try {
            client.start();
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR,
                    "Could not start HTTP client: " + e.getMessage());
            return;
        }
        httpClient = client;

        PorscheApi newApi = new PorscheApi(client, config.email, config.password, loadToken(config.email),
                token -> saveToken(config.email, token));
        api = newApi;

        updateStatus(ThingStatus.UNKNOWN);
        String captcha = config.captcha.trim();
        scheduler.execute(() -> connect(newApi, captcha.isEmpty() ? null : captcha));
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        if (!configurationParameters.containsKey("captcha") || !isInitialized()
                || configurationParameters.entrySet().stream().anyMatch(entry -> !"captcha".equals(entry.getKey())
                        && !Objects.equals(getThing().getConfiguration().get(entry.getKey()), entry.getValue()))) {
            super.handleConfigurationUpdate(configurationParameters);
            return;
        }
        if (!isModifyingCurrentConfig(configurationParameters)) {
            return;
        }
        validateConfigurationParameters(configurationParameters);
        Configuration configuration = editConfiguration();
        configurationParameters.forEach(configuration::put);
        updateConfiguration(configuration);

        PorscheApi currentApi = api;
        if (currentApi != null) {
            String captcha = getConfigAs(AccountConfiguration.class).captcha.trim();
            scheduler.execute(() -> connect(currentApi, captcha.isEmpty() ? null : captcha));
        }
    }

    @Override
    public void dispose() {
        api = null;
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(true);
            pollJob = null;
        }
        HttpClient client = httpClient;
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.debug("Error stopping HTTP client: {}", e.getMessage());
            }
            httpClient = null;
        }
        vehicles.clear();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType && CH_CAPTCHA.equals(channelUID.getId())) {
            loadCaptchaImageState();
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(PorscheVehicleDiscoveryService.class);
    }

    // ---------------------------------------------------------------- connection & polling

    private void connect(PorscheApi currentApi, @Nullable String captcha) {
        if (currentApi != api) {
            return;
        }
        try {
            PendingLogin pending = captcha != null ? loadPendingLogin() : null;
            if (captcha != null && pending == null) {
                logger.debug("Captcha configured but no login is waiting for it, clearing stale answer");
                clearCaptcha();
                if (currentApi == api) {
                    scheduler.execute(() -> connect(currentApi, null));
                }
                return;
            }
            currentApi.authenticate(captcha, pending);
            if (currentApi != api) {
                return;
            }
            if (captcha != null) {
                clearCaptcha();
                if (currentApi != api) {
                    return;
                }
            }
            refreshVehicleList(currentApi);
            updateStatus(ThingStatus.ONLINE);
            int interval = Math.max(MIN_REFRESH_MINUTES, getConfigAs(AccountConfiguration.class).refreshInterval);
            pollJob = scheduler.scheduleWithFixedDelay(() -> poll(currentApi), 0, interval, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (PorscheApiException e) {
            handleApiError(currentApi, e);
        }
    }

    private void poll(PorscheApi currentApi) {
        try {
            for (Thing child : getThing().getThings()) {
                if (currentApi != api) {
                    return;
                }
                if (child.getHandler() instanceof PorscheVehicleHandler vehicleHandler) {
                    try {
                        refreshVehicle(currentApi, vehicleHandler.getVin(), false);
                    } catch (PorscheApiException e) {
                        if (!isVehicleSpecific(e)) {
                            throw e;
                        }
                        // e.g. vehicle not found, mobile access disabled or vehicle unreachable
                        vehicleHandler.vehicleError(getErrorMessage(e));
                    }
                }
            }
            if (currentApi == api && getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (PorscheApiException e) {
            handleApiError(currentApi, e);
        }
    }

    private void refreshVehicleList(PorscheApi currentApi) throws PorscheApiException, InterruptedException {
        List<VehicleData> list = currentApi.getVehicles();
        logger.debug("Porsche account has {} vehicle(s)", list.size());
        for (VehicleData vehicle : list) {
            vehicles.merge(vehicle.getVin(), vehicle, (old, fresh) -> old);
        }
        discoveryServices.forEach(service -> service.vehiclesFound(list));
    }

    /**
     * Fetches the overview of a vehicle and pushes it to its handler.
     *
     * @param wakeUp ask the car for live data instead of the values cached in the cloud
     */
    private void refreshVehicle(PorscheApi currentApi, String vin, boolean wakeUp)
            throws PorscheApiException, InterruptedException {
        JsonObject overview = currentApi.getOverview(vin, wakeUp);
        if (currentApi != api) {
            return;
        }
        VehicleData data = Objects.requireNonNull(vehicles.compute(vin,
                (key, old) -> (old != null ? old : VehicleData.fromListEntry(new JsonObject())).merge(overview)));
        for (Thing child : getThing().getThings()) {
            if (child.getHandler() instanceof PorscheVehicleHandler vehicleHandler
                    && vin.equals(vehicleHandler.getVin())) {
                vehicleHandler.vehicleUpdated(data);
            }
        }
    }

    /** Errors that concern one vehicle only and must not take the whole account offline. */
    private static boolean isVehicleSpecific(PorscheApiException e) {
        int status = e.getHttpStatus();
        return status == 403 || status == 404 || status == 405 || status == 408;
    }

    private static String getErrorMessage(Throwable exception) {
        String message = exception.getMessage();
        return message != null ? message : "Porsche Connect API error";
    }

    private void handleApiError(PorscheApi currentApi, PorscheApiException e) {
        if (currentApi != api) {
            return;
        }
        if (e instanceof PorscheCaptchaRequiredException captchaException) {
            stopPolling();
            savePendingLogin(captchaException.getPendingLogin());
            String hint = publishCaptcha(captchaException.getCaptchaImage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Captcha required: " + hint + " Enter the text in the account's 'Captcha' parameter.");
        } else if (e instanceof PorscheAuthException) {
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.wrong-credentials");
        } else if (e.getHttpStatus() == 423) {
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, getErrorMessage(e));
        } else {
            logger.debug("Porsche Connect API error: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, getErrorMessage(e));
            if (pollJob == null) {
                // not connected yet: retry the whole connect in a while
                scheduler.schedule(() -> connect(currentApi, null), 5, TimeUnit.MINUTES);
            }
        }
    }

    private void stopPolling() {
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(false);
            pollJob = null;
        }
    }

    // ---------------------------------------------------------------- API for vehicle handlers

    /** The API client, or {@code null} while the bridge is not initialized. */
    @Nullable
    PorscheApi getApi() {
        return api;
    }

    @Nullable
    VehicleData getVehicleData(String vin) {
        return vehicles.get(vin);
    }

    /** Asynchronously fetches fresh data for one vehicle. */
    void requestVehicleRefresh(PorscheVehicleHandler vehicleHandler, boolean wakeUp) {
        PorscheApi currentApi = api;
        if (currentApi == null || getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        scheduler.execute(() -> {
            try {
                refreshVehicle(currentApi, vehicleHandler.getVin(), wakeUp);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (PorscheApiException e) {
                if (isVehicleSpecific(e)) {
                    vehicleHandler.vehicleError(getErrorMessage(e));
                } else {
                    handleApiError(currentApi, e);
                }
            }
        });
    }

    // ---------------------------------------------------------------- discovery

    public void addDiscoveryService(PorscheVehicleDiscoveryService service) {
        discoveryServices.add(service);
    }

    public void removeDiscoveryService(PorscheVehicleDiscoveryService service) {
        discoveryServices.remove(service);
    }

    /** Re-reads the vehicle list and reports it to the discovery services. */
    public void scanForVehicles() {
        PorscheApi currentApi = api;
        if (currentApi == null || getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        scheduler.execute(() -> {
            try {
                refreshVehicleList(currentApi);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (PorscheApiException e) {
                handleApiError(currentApi, e);
            }
        });
    }

    public Collection<VehicleData> getKnownVehicles() {
        return List.copyOf(vehicles.values());
    }

    // ---------------------------------------------------------------- persistence

    private String storageKey(String key) {
        return getThing().getUID().getAsString() + "." + key;
    }

    /** Token is only reused for the same e-mail address. */
    private PorscheToken loadToken(String email) {
        String json = storage.get(storageKey(KEY_TOKEN));
        if (json == null) {
            return PorscheToken.EMPTY;
        }
        try {
            StoredToken stored = gson.fromJson(json, StoredToken.class);
            if (stored != null && email.equalsIgnoreCase(stored.email)) {
                return new PorscheToken(stored.accessToken, stored.refreshToken, stored.expiresAt);
            }
        } catch (JsonParseException e) {
            logger.debug("Ignoring unreadable stored token");
        }
        return PorscheToken.EMPTY;
    }

    private void saveToken(String email, PorscheToken token) {
        if (!token.hasAccessToken() && !token.hasRefreshToken()) {
            storage.remove(storageKey(KEY_TOKEN));
            return;
        }
        StoredToken stored = new StoredToken();
        stored.email = email;
        stored.accessToken = token.accessToken();
        stored.refreshToken = token.refreshToken();
        stored.expiresAt = token.expiresAt();
        storage.put(storageKey(KEY_TOKEN), gson.toJson(stored));
    }

    private @Nullable PendingLogin loadPendingLogin() {
        String json = storage.remove(storageKey(KEY_PENDING_LOGIN));
        if (json == null) {
            return null;
        }
        try {
            return gson.fromJson(json, PendingLogin.class);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private void savePendingLogin(PendingLogin pending) {
        storage.put(storageKey(KEY_PENDING_LOGIN), gson.toJson(pending));
    }

    private void clearCaptcha() {
        Configuration config = editConfiguration();
        config.put("captcha", "");
        updateConfiguration(config);
        updateState(CH_CAPTCHA, UnDefType.UNDEF);
        try {
            Files.deleteIfExists(captchaFile());
        } catch (IOException e) {
            logger.debug("Could not delete captcha file: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- captcha display

    private Path captchaFile() {
        return Path.of(OpenHAB.getConfigFolder(), "html",
                "porscheconnect-captcha-" + getThing().getUID().getId() + ".svg");
    }

    /**
     * Makes the captcha visible: as state of the {@code captcha} image channel and as a file served by openHAB under
     * {@code /static/}.
     *
     * @return a hint for the status description telling the user where to look
     */
    private String publishCaptcha(String captcha) {
        RawType image = decodeDataUri(captcha);
        if (image == null) {
            // not a data URI, probably a plain URL
            return "open " + captcha + ".";
        }
        updateState(CH_CAPTCHA, image);
        if ("image/svg+xml".equals(image.getMimeType())) {
            try {
                Path file = captchaFile();
                Files.createDirectories(file.getParent());
                Files.write(file, image.getBytes());
                return "open http://<openhab-host>:8080/static/" + file.getFileName()
                        + " or link an Image item to the account's captcha channel.";
            } catch (IOException e) {
                logger.debug("Could not write captcha file: {}", e.getMessage());
            }
        }
        return "link an Image item to the account's captcha channel to see it.";
    }

    private void loadCaptchaImageState() {
        try {
            Path file = captchaFile();
            if (Files.exists(file)) {
                updateState(CH_CAPTCHA, new RawType(Files.readAllBytes(file), "image/svg+xml"));
            }
        } catch (IOException e) {
            logger.debug("Could not read captcha file: {}", e.getMessage());
        }
    }

    static @Nullable RawType decodeDataUri(String uri) {
        if (!uri.startsWith("data:")) {
            return null;
        }
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = uri.substring(5, comma);
        String data = uri.substring(comma + 1);
        String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        if (mime.isEmpty()) {
            mime = "text/plain";
        }
        try {
            byte[] bytes = meta.endsWith(";base64") ? Base64.getDecoder().decode(data)
                    : URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            return new RawType(bytes, mime);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Persisted form of the token, bound to the account's e-mail address. */
    private static class StoredToken {
        @Nullable
        String email;
        @Nullable
        String accessToken;
        @Nullable
        String refreshToken;
        long expiresAt;
    }
}
