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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * The most recent sighting of an RFID code at a flap.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class RfidLastSeen {
    public @Nullable String deviceId;
    public @Nullable String rfidCode;
    public @Nullable Long eventId;
    public @Nullable JsonElement eventTimestamp;
    public EventSummary.@Nullable SubEvent lastSubevent;
    public @Nullable String location;
    public @Nullable JsonElement hiddenAt;

    public @Nullable Instant getEventTimestamp() {
        return Timestamps.parse(eventTimestamp);
    }

    public boolean isHidden() {
        JsonElement hidden = hiddenAt;
        return hidden != null && !hidden.isJsonNull();
    }
}
