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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

/**
 * Tests the parts of the DTOs that hold logic.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
class DtoTest {

    private final Gson gson = new Gson();

    private CatLocation location(String action, String direction) {
        EventSummary.SubEvent subEvent = new EventSummary.SubEvent();
        subEvent.action = action;
        subEvent.direction = direction;
        CatLocation result = CatLocation.fromSubEvent(subEvent);
        assertNotNull(result);
        return result;
    }

    @Test
    void passagesChangeSides() {
        assertEquals(CatLocation.INSIDE, location("TRANSIT", "INWARD"));
        assertEquals(CatLocation.OUTSIDE, location("TRANSIT", "OUTWARD"));
    }

    @Test
    void aBreachIsAPassage() {
        assertEquals(CatLocation.INSIDE, location("BREACH", "INWARD"));
        assertEquals(CatLocation.OUTSIDE, location("BREACH", "OUTWARD"));
    }

    @Test
    void peekAndDenyStayOnTheNearSide() {
        assertEquals(CatLocation.OUTSIDE, location("PEEK", "INWARD"));
        assertEquals(CatLocation.OUTSIDE, location("DENY", "INWARD"));
        assertEquals(CatLocation.INSIDE, location("PEEK", "OUTWARD"));
        assertEquals(CatLocation.INSIDE, location("DENY", "OUTWARD"));
    }

    @Test
    void unknownValuesSayNothing() {
        EventSummary.SubEvent subEvent = new EventSummary.SubEvent();
        subEvent.action = "HOVER";
        subEvent.direction = "INWARD";
        assertNull(CatLocation.fromSubEvent(subEvent));
        assertNull(CatLocation.fromSubEvent(null));
    }

    @Test
    void reportedLocationWins() {
        RfidLastSeen lastSeen = gson.fromJson(
                "{\"location\":\"OUTSIDE\",\"lastSubevent\":{\"action\":\"TRANSIT\",\"direction\":\"INWARD\"}}",
                RfidLastSeen.class);
        assertNotNull(lastSeen);
        assertEquals(CatLocation.OUTSIDE, CatLocation.fromLastSeen(lastSeen));
    }

    @Test
    void readsBothTimestampForms() {
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"),
                Timestamps.parse(JsonParser.parseString("\"2026-11-01T10:00:00.000Z\"")));
        assertEquals(Instant.ofEpochMilli(1000), Timestamps.parse(JsonParser.parseString("1000")));
        assertNull(Timestamps.parse(JsonParser.parseString("\"garbage\"")));
        assertNull(Timestamps.parse(JsonParser.parseString("null")));
    }

    @Test
    void readsSummaryBareAndWrapped() {
        String bare = "{\"deviceId\":\"OC-1\",\"eventId\":7,\"subevents\":[{\"rfidCode\":\"a\",\"action\":\"PEEK\"}]}";
        EventSummary summary = EventSummary.fromJson(JsonParser.parseString(bare), gson);
        assertNotNull(summary);
        assertEquals(1, summary.getSubevents().size());
        assertNotNull(EventSummary.fromJson(JsonParser.parseString("{\"body\":" + bare + "}"), gson));
        assertNull(EventSummary.fromJson(JsonParser.parseString("{\"type\":\"update\"}"), gson));
        assertNull(EventSummary.fromJson(null, gson));
    }

    @Test
    void choosesPosterFrame() {
        DeviceEvent event = new DeviceEvent();
        assertEquals(1, event.getPosterFrame());
        event.frameCount = 40;
        assertEquals(20, event.getPosterFrame());
        event.posterFrameIndex = 0;
        assertEquals(0, event.getPosterFrame());
    }

    @Test
    void fallsBackToUtcForUnknownZone() {
        Device device = new Device();
        assertEquals(ZoneOffset.UTC, device.getZone());
        device.timeZone = "Mars/Olympus";
        assertEquals(ZoneOffset.UTC, device.getZone());
        device.timeZone = "Europe/Amsterdam";
        assertEquals(ZoneId.of("Europe/Amsterdam"), device.getZone());
    }
}
