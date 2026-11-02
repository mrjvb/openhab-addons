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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * A passage event of a flap. An event is still running as long as its frame count is unknown.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DeviceEvent {

    public static final int TRIGGER_REMOTE = 1;
    public static final int CLASSIFICATION_UNKNOWN = 0;
    public static final int CLASSIFICATION_CONTRABAND = 3;
    public static final int CLASSIFICATION_HUMAN_ACTIVITY = 4;

    public @Nullable String deviceId;
    public @Nullable Long eventId;
    public @Nullable JsonElement timestamp;
    public @Nullable Integer frameCount;
    public @Nullable Integer eventTriggerSource;
    public @Nullable Integer eventClassification;
    public @Nullable Integer posterFrameIndex;
    public @Nullable String accessToken;
    public @Nullable List<String> rfidCodes;

    public @Nullable Instant getTimestamp() {
        return Timestamps.parse(timestamp);
    }

    public boolean isConcluded() {
        Integer frames = frameCount;
        return frames != null && frames > 0;
    }

    public List<String> getRfidCodes() {
        List<String> codes = rfidCodes;
        return codes == null ? List.of() : codes;
    }

    /**
     * @return the index of the frame that best represents this event
     */
    public int getPosterFrame() {
        Integer poster = posterFrameIndex;
        if (poster != null) {
            return poster;
        }
        Integer frames = frameCount;
        return frames != null && frames > 1 ? frames / 2 : 1;
    }
}
