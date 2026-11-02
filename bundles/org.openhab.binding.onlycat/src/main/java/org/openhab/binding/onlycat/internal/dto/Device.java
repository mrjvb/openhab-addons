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
package org.openhab.binding.onlycat.internal.dto;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * A flap as reported by the gateway.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class Device {

    /**
     * The connection between the flap and the OnlyCat cloud.
     */
    public static class Connectivity {
        public @Nullable Boolean connected;
        public @Nullable JsonElement timestamp;
        public @Nullable String disconnectReason;

        public @Nullable Instant getTimestamp() {
            return Timestamps.parse(timestamp);
        }
    }

    public @Nullable String deviceId;
    public @Nullable String description;
    public @Nullable String timeZone;
    public @Nullable String firmwareChannel;
    public @Nullable Long deviceTransitPolicyId;
    public @Nullable Connectivity connectivity;

    /**
     * @return the time zone of the flap, which is the one its door policy time ranges refer to
     */
    public ZoneId getZone() {
        String zone = timeZone;
        if (zone != null) {
            try {
                return ZoneId.of(zone);
            } catch (DateTimeException e) {
                // fall through to UTC, which is what the gateway assumes without a time zone
            }
        }
        return ZoneOffset.UTC;
    }
}
