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
package org.openhab.binding.dyson.internal.handler;

import static org.openhab.binding.dyson.internal.DysonBindingConstants.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.dyson.internal.api.DysonApiException;
import org.openhab.binding.dyson.internal.api.DysonAuthException;
import org.openhab.binding.dyson.internal.api.DysonCloudApi;
import org.openhab.binding.dyson.internal.api.DysonCredentialDecoder;
import org.openhab.binding.dyson.internal.api.dto.DysonIotCredentials;
import org.openhab.binding.dyson.internal.api.dto.DysonManifestDevice;
import org.openhab.binding.dyson.internal.api.dto.DysonPendingRelease;
import org.openhab.binding.dyson.internal.config.DysonAccountConfiguration;
import org.openhab.binding.dyson.internal.discovery.DysonDiscoveryService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge handler for a Dyson cloud account. Takes care of the login (with one-time password), the device list used
 * for discovery and the cloud information (credentials, firmware) needed by the device handlers.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonAccountHandler extends BaseBridgeHandler {
    private static final long RETRY_DELAY_MINUTES = 5;

    private final Logger logger = LoggerFactory.getLogger(DysonAccountHandler.class);
    private final HttpClient httpClient;
    private final Map<String, DysonManifestDevice> devices = new ConcurrentHashMap<>();

    private DysonAccountConfiguration config = new DysonAccountConfiguration();
    private @Nullable DysonCloudApi api;
    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> retryJob;
    private @Nullable DysonDiscoveryService discoveryService;

    public DysonAccountHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        config = getConfigAs(DysonAccountConfiguration.class);
        if (config.email.isBlank() || (!config.isMobileLogin() && config.password.isBlank())) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-credentials");
            return;
        }
        api = new DysonCloudApi(httpClient, config.country, config.culture);
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::login);
    }

    @Override
    public void dispose() {
        cancel(refreshJob);
        refreshJob = null;
        cancel(retryJob);
        retryJob = null;
        api = null;
        devices.clear();
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The bridge has no channels
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(DysonDiscoveryService.class);
    }

    public void setDiscoveryService(@Nullable DysonDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    private synchronized void login() {
        DysonCloudApi localApi = api;
        if (localApi == null) {
            return;
        }
        boolean mobile = config.isMobileLogin();
        try {
            localApi.provision();

            // 1. Re-use a stored token
            if (!config.token.isBlank()) {
                localApi.setToken(config.token);
                try {
                    refreshDevices();
                    goOnline();
                    return;
                } catch (DysonAuthException e) {
                    logger.debug("Stored Dyson token was rejected, a new login is required");
                    localApi.setToken(null);
                    storeConfig(CONFIG_TOKEN, null);
                }
            }

            // 2. Complete a pending login with the one-time password
            String challengeId = getThing().getProperties().get(PROPERTY_CHALLENGE_ID);
            if (!config.otp.isBlank() && challengeId != null && !challengeId.isBlank()) {
                try {
                    String token = localApi.completeLogin(challengeId, config.email, config.password, config.otp.trim(),
                            mobile);
                    storeConfig(CONFIG_TOKEN, token);
                    storeConfig(CONFIG_OTP, null);
                    updateProperty(PROPERTY_CHALLENGE_ID, null);
                    refreshDevices();
                    goOnline();
                } catch (DysonAuthException e) {
                    storeConfig(CONFIG_OTP, null);
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "@text/offline.conf-error-otp-rejected");
                }
                return;
            }

            // 3. Start a new login; Dyson sends a one-time password by e-mail or SMS
            String accountStatus = localApi.getUserStatus(config.email, mobile);
            if (accountStatus != null && !"ACTIVE".equals(accountStatus)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.conf-error-account-status [\"" + accountStatus + "\"]");
                return;
            }
            String newChallengeId = localApi.beginLogin(config.email, mobile);
            updateProperty(PROPERTY_CHALLENGE_ID, newChallengeId);
            storeConfig(CONFIG_OTP, null);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "@text/offline.conf-pending-otp [\"" + config.email + "\"]");
        } catch (DysonAuthException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        } catch (DysonApiException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            scheduleRetry();
        }
    }

    private void goOnline() {
        updateStatus(ThingStatus.ONLINE);
        cancel(refreshJob);
        int interval = Math.max(5, config.refreshInterval);
        refreshJob = scheduler.scheduleWithFixedDelay(this::scheduledRefresh, interval, interval, TimeUnit.MINUTES);
    }

    private void scheduleRetry() {
        cancel(retryJob);
        retryJob = scheduler.schedule(this::login, RETRY_DELAY_MINUTES, TimeUnit.MINUTES);
    }

    private void scheduledRefresh() {
        try {
            refreshDevices();
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (DysonAuthException e) {
            logger.debug("Dyson token expired: {}", e.getMessage());
            cancel(refreshJob);
            refreshJob = null;
            DysonCloudApi localApi = api;
            if (localApi != null) {
                localApi.setToken(null);
            }
            storeConfig(CONFIG_TOKEN, null);
            config.token = "";
            login();
        } catch (DysonApiException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    /**
     * Reloads the device list from the cloud, updates discovery and informs the device handlers.
     */
    public void refreshDevices() throws DysonApiException {
        DysonCloudApi localApi = api;
        if (localApi == null) {
            return;
        }
        List<DysonManifestDevice> manifest = localApi.getDevices();
        devices.clear();
        for (DysonManifestDevice device : manifest) {
            String serial = device.serialNumber;
            if (serial != null) {
                devices.put(serial, device);
            }
        }
        logger.debug("Dyson account has {} device(s)", devices.size());

        DysonDiscoveryService localDiscovery = discoveryService;
        if (localDiscovery != null) {
            localDiscovery.onDevicesUpdated(manifest);
        }
        for (Thing thing : getThing().getThings()) {
            if (thing.getHandler() instanceof DysonBaseDeviceHandler handler) {
                DysonManifestDevice device = devices.get(handler.getSerialNumber());
                if (device != null) {
                    handler.onCloudInfoUpdated(device);
                }
            }
        }
    }

    /**
     * Refreshes the device list for a discovery scan.
     */
    public void startScan() {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        scheduler.execute(() -> {
            try {
                refreshDevices();
            } catch (DysonApiException e) {
                logger.debug("Failed to refresh Dyson devices: {}", e.getMessage());
            }
        });
    }

    public Collection<DysonManifestDevice> getDevices() {
        return devices.values();
    }

    public @Nullable DysonManifestDevice getDevice(String serialNumber) {
        return devices.get(serialNumber);
    }

    /**
     * Returns the decrypted local MQTT password of a device, or null if it is not available.
     */
    public @Nullable String getLocalPassword(String serialNumber) {
        DysonManifestDevice device = devices.get(serialNumber);
        DysonManifestDevice.Mqtt mqtt = device != null ? device.getMqtt() : null;
        String encrypted = mqtt != null ? mqtt.localBrokerCredentials : null;
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return DysonCredentialDecoder.decryptLocalCredentials(encrypted);
        } catch (DysonApiException e) {
            logger.warn("Unable to decrypt the local credentials of {}: {}", serialNumber, e.getMessage());
            return null;
        }
    }

    public DysonIotCredentials getIotCredentials(String serialNumber) throws DysonApiException {
        DysonCloudApi localApi = api;
        if (localApi == null || getThing().getStatus() != ThingStatus.ONLINE) {
            throw new DysonApiException("Dyson account is not online");
        }
        return localApi.getIotCredentials(serialNumber);
    }

    public @Nullable DysonPendingRelease getPendingRelease(String serialNumber) {
        DysonCloudApi localApi = api;
        if (localApi == null || getThing().getStatus() != ThingStatus.ONLINE) {
            return null;
        }
        try {
            return localApi.getPendingRelease(serialNumber);
        } catch (DysonApiException e) {
            logger.debug("Failed to get pending firmware release for {}: {}", serialNumber, e.getMessage());
            return null;
        }
    }

    public void triggerFirmwareUpdate(String serialNumber) throws DysonApiException {
        DysonCloudApi localApi = api;
        if (localApi == null || getThing().getStatus() != ThingStatus.ONLINE) {
            throw new DysonApiException("Dyson account is not online");
        }
        localApi.triggerFirmwareUpdate(serialNumber);
    }

    private void storeConfig(String key, @Nullable String value) {
        Configuration configuration = editConfiguration();
        configuration.put(key, value);
        updateConfiguration(configuration);
    }

    private static void cancel(@Nullable ScheduledFuture<?> job) {
        if (job != null) {
            job.cancel(true);
        }
    }
}
