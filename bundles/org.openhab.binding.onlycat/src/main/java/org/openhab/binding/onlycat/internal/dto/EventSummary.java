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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * The analysis of an event: who did what at the flap. A summary may be revised while frames are being processed, for
 * instance when a transit turns out to be a peek, so the most recent one is authoritative.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class EventSummary {

    /**
     * One action of one animal within an event.
     */
    public static class SubEvent {
        public @Nullable Integer startFrameIndex;
        public @Nullable Integer endFrameIndex;
        public @Nullable String rfidCode;
        public @Nullable String direction;
        public @Nullable String action;
    }

    public @Nullable String deviceId;
    public @Nullable Long eventId;
    public @Nullable Integer processedFrameCount;
    public @Nullable List<SubEvent> subevents;

    public List<SubEvent> getSubevents() {
        List<SubEvent> events = subevents;
        return events == null ? List.of() : events;
    }

    /**
     * Reads a summary, which comes bare in replies and wrapped in a {@code body} object in updates.
     *
     * @return the summary, or null if the message does not contain a usable one
     */
    public static @Nullable EventSummary fromJson(@Nullable JsonElement message, Gson gson) {
        if (!(message instanceof JsonObject object)) {
            return null;
        }
        JsonElement body = object.get("body");
        try {
            EventSummary summary = gson.fromJson(body instanceof JsonObject ? body : object, EventSummary.class);
            return summary == null || summary.deviceId == null || summary.eventId == null ? null : summary;
        } catch (JsonParseException e) {
            return null;
        }
    }
}
