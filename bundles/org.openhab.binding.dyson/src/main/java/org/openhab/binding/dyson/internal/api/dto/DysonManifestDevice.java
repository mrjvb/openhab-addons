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
package org.openhab.binding.dyson.internal.api.dto;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A device entry of the Dyson cloud manifest ({@code GET /v3/manifest}).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonManifestDevice {
    public @Nullable String serialNumber;
    public @Nullable String name;
    public @Nullable String type;
    public @Nullable String variant;
    public @Nullable String model;
    public @Nullable String category;
    public @Nullable String connectionCategory;
    public @Nullable ConnectedConfiguration connectedConfiguration;

    public static class ConnectedConfiguration {
        public @Nullable Firmware firmware;
        public @Nullable Mqtt mqtt;
    }

    public static class Firmware {
        public @Nullable String version;
        public boolean autoUpdateEnabled;
        public boolean newVersionAvailable;
        public @Nullable List<String> capabilities;
    }

    public static class Mqtt {
        public @Nullable String localBrokerCredentials;
        public @Nullable String mqttRootTopicLevel;
        public @Nullable String remoteBrokerType;
    }

    public @Nullable Firmware getFirmware() {
        ConnectedConfiguration config = connectedConfiguration;
        return config == null ? null : config.firmware;
    }

    public @Nullable Mqtt getMqtt() {
        ConnectedConfiguration config = connectedConfiguration;
        return config == null ? null : config.mqtt;
    }

    public List<String> getCapabilities() {
        Firmware firmware = getFirmware();
        List<String> capabilities = firmware == null ? null : firmware.capabilities;
        return capabilities == null ? List.of() : capabilities;
    }

    public String getDisplayName() {
        String displayName = name;
        if (displayName == null || displayName.isBlank()) {
            String displayModel = model;
            return "Dyson " + (displayModel != null ? displayModel : serialNumber);
        }
        return displayName;
    }
}
