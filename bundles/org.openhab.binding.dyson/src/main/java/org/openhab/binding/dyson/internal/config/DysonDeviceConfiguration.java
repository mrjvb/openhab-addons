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
package org.openhab.binding.dyson.internal.config;

import static org.openhab.binding.dyson.internal.DysonBindingConstants.CONNECTION_LOCAL_CLOUD_FALLBACK;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of a Dyson Wi-Fi device (fan or robot vacuum).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonDeviceConfiguration {
    public String serialNumber = "";
    /** One of LOCAL, LOCAL_CLOUD_FALLBACK, CLOUD_LOCAL_FALLBACK, CLOUD. */
    public String connectionType = CONNECTION_LOCAL_CLOUD_FALLBACK;
    /** Host name or IP address of the device. Defaults to {serialNumber}.local. */
    public String hostname = "";
    /** Local MQTT password (from the device sticker or decrypted from the cloud). Optional with an account bridge. */
    public String localPassword = "";
    /** MQTT root topic level (product type), e.g. 438M. Optional with an account bridge. */
    public String mqttPrefix = "";
    /** Comma separated list of device capabilities. Optional with an account bridge. */
    public String capabilities = "";
    /** Interval in seconds at which the current state is requested from the device. */
    public int refreshInterval = 30;
    /** Remove channels for features the device does not report. */
    public boolean removeUnsupportedChannels = true;
}
