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
package org.openhab.binding.onlycat.internal.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the conversions of the handlers.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
class HandlerConversionsTest {

    private static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void plannedRebootsAreNoErrors() {
        assertFalse(OnlyCatFlapHandler.containsError(array("[]")));
        assertFalse(OnlyCatFlapHandler.containsError(array("[{\"isError\":false}]")));
        assertTrue(OnlyCatFlapHandler.containsError(array("[{\"isError\":false},{\"isError\":true}]")));
    }

    @Test
    void anUnclassifiedRebootCountsAsError() {
        assertTrue(OnlyCatFlapHandler.containsError(array("[{\"isError\":null}]")));
        assertTrue(OnlyCatFlapHandler.containsError(array("[{\"summary\":\"?\"}]")));
    }

    @Test
    void namesEventValues() {
        assertEquals("MANUAL", OnlyCatFlapHandler.triggerName(0));
        assertEquals("OUTDOOR_MOTION", OnlyCatFlapHandler.triggerName(3));
        assertNull(OnlyCatFlapHandler.triggerName(4));
        assertNull(OnlyCatFlapHandler.triggerName(null));
        assertEquals("UNKNOWN", OnlyCatFlapHandler.classificationName(0));
        assertEquals("HUMAN_ACTIVITY", OnlyCatFlapHandler.classificationName(4));
        assertEquals("REMOTE_UNLOCK", OnlyCatFlapHandler.classificationName(10));
        assertNull(OnlyCatFlapHandler.classificationName(5));
    }

    @Test
    void keepsTheLatestValueOfEachMetric() {
        JsonObject latest = OnlyCatAccountHandler.latestMetrics(array("""
                [{"measureName":"rssi","time":"2026-11-01T10:00:00Z","value":-60},
                 {"measureName":"rssi","time":"2026-11-01T11:00:00Z","value":-50},
                 {"measureName":"rssi","time":"2026-11-01T09:00:00Z","value":-70},
                 {"measureName":"heap","time":"2026-11-01T09:00:00Z","value":1},
                 {"time":"2026-11-01T09:00:00Z","value":2}]"""));
        assertEquals(2, latest.size());
        assertEquals(-50, latest.get("rssi").getAsInt());
    }
}
