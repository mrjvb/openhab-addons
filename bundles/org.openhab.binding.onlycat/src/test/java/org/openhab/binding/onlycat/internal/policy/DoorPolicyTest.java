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
package org.openhab.binding.onlycat.internal.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Tests {@link DoorPolicy}.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
class DoorPolicyTest {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final DoorPolicy.Options NONE = new DoorPolicy.Options(false, false);

    private static DoorPolicy policy(boolean idleLock, String rules) {
        String json = "{\"idleLock\":" + idleLock + ",\"rules\":[" + rules + "]}";
        return DoorPolicy.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static DeviceEvent event(String utcTime) {
        DeviceEvent event = new DeviceEvent();
        event.eventTriggerSource = 3;
        event.eventClassification = 1;
        event.rfidCodes = List.of("cat");
        event.timestamp = new JsonPrimitive("2026-11-01T" + utcTime + ":00Z");
        return event;
    }

    @Test
    void idleStateFollowsIdleLock() {
        assertEquals(LockVerdict.LOCKED, policy(true, "").idleState());
        assertEquals(LockVerdict.UNLOCKED, policy(false, "").idleState());
    }

    @Test
    void withoutMatchTheIdleStateApplies() {
        DoorPolicy policy = policy(true, "{\"criteria\":{\"rfidCode\":\"other\"},\"action\":{\"lock\":false}}");
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }

    @Test
    void firstMatchingRuleDecides() {
        DoorPolicy policy = policy(true, """
                {"criteria":{"rfidCode":["x","cat"]},"action":{"lock":false}},
                {"criteria":{"rfidCode":"cat"},"action":{"lock":true}}""");
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }

    @Test
    void disabledRulesAreSkipped() {
        DoorPolicy policy = policy(false,
                "{\"enabled\":false,\"criteria\":{\"rfidCode\":\"cat\"},\"action\":{\"lock\":true}}");
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }

    @Test
    void remoteUnlockOverridesRules() {
        DoorPolicy policy = policy(true, "{\"criteria\":{},\"action\":{\"lock\":true}}");
        DeviceEvent event = event("10:00");
        event.eventTriggerSource = DeviceEvent.TRIGGER_REMOTE;
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event, AMSTERDAM, NONE));
    }

    @Test
    void manualTriggerSourceZeroIsMatched() {
        DoorPolicy policy = policy(true, "{\"criteria\":{\"eventTriggerSource\":0},\"action\":{\"lock\":false}}");
        DeviceEvent event = event("10:00");
        event.eventTriggerSource = 0;
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event, AMSTERDAM, NONE));
    }

    @Test
    void timeRangesUseTheZoneOfTheFlapAndWrapMidnight() {
        DoorPolicy policy = policy(false, "{\"criteria\":{\"timeRange\":\"22:00-06:00\"},\"action\":{\"lock\":true}}");
        // Amsterdam is at UTC+1 on this date
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event("21:30"), AMSTERDAM, NONE));
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event("04:30"), AMSTERDAM, NONE));
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event("20:30"), AMSTERDAM, NONE));
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event("06:00"), AMSTERDAM, NONE));
    }

    @Test
    void timeRangeIncludesItsLastMinute() {
        DoorPolicy policy = policy(false,
                "{\"criteria\":{\"timeRange\":[\"08:00-08:30\"]},\"action\":{\"lock\":true}}");
        DeviceEvent event = event("07:30");
        event.timestamp = new JsonPrimitive("2026-11-01T07:30:59Z");
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event, AMSTERDAM, NONE));
    }

    @Test
    void waitsForTheRfidCodeOfARunningEvent() {
        DoorPolicy policy = policy(true, "{\"criteria\":{\"rfidCode\":\"cat\"},\"action\":{\"lock\":false}}");
        DeviceEvent event = event("10:00");
        event.rfidCodes = List.of();
        assertEquals(LockVerdict.UNKNOWN, policy.evaluate(event, AMSTERDAM, NONE));
        event.frameCount = 12;
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event, AMSTERDAM, NONE));
    }

    @Test
    void waitsForTheClassificationOfARunningEvent() {
        DoorPolicy policy = policy(false, "{\"criteria\":{\"eventClassification\":3},\"action\":{\"lock\":true}}");
        DeviceEvent event = event("10:00");
        event.eventClassification = DeviceEvent.CLASSIFICATION_UNKNOWN;
        assertEquals(LockVerdict.UNKNOWN, policy.evaluate(event, AMSTERDAM, NONE));
    }

    @Test
    void aRuleThatCannotMatchNeverBlocks() {
        DoorPolicy policy = policy(false, """
                {"criteria":{"rfidCode":"other","flapState":1},"action":{"lock":true}},
                {"criteria":{"rfidCode":"cat"},"action":{"lock":false}}""");
        assertEquals(LockVerdict.UNLOCKED, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }

    @Test
    void unobservableCriteriaMakeTheOutcomeUnknownUnlessIgnored() {
        DoorPolicy flap = policy(false, "{\"criteria\":{\"flapState\":[1,2]},\"action\":{\"lock\":true}}");
        DoorPolicy motion = policy(false, "{\"criteria\":{\"motionSensorState\":3},\"action\":{\"lock\":true}}");
        assertEquals(LockVerdict.UNKNOWN, flap.evaluate(event("10:00"), AMSTERDAM, NONE));
        assertEquals(LockVerdict.UNKNOWN, motion.evaluate(event("10:00"), AMSTERDAM, NONE));
        assertEquals(LockVerdict.UNLOCKED,
                flap.evaluate(event("10:00"), AMSTERDAM, new DoorPolicy.Options(true, false)));
        assertEquals(LockVerdict.UNLOCKED,
                motion.evaluate(event("10:00"), AMSTERDAM, new DoorPolicy.Options(false, true)));
    }

    @Test
    void aRuleWithoutLockActionLeavesTheIdleState() {
        DoorPolicy policy = policy(true, "{\"criteria\":{\"rfidCode\":\"cat\"},\"action\":{\"sound\":\"bell\"}}");
        assertEquals(LockVerdict.LOCKED, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }

    @Test
    void anUnreadableTimeRangeIsNotGuessed() {
        DoorPolicy policy = policy(false, "{\"criteria\":{\"timeRange\":\"soon\"},\"action\":{\"lock\":true}}");
        assertEquals(LockVerdict.UNKNOWN, policy.evaluate(event("10:00"), AMSTERDAM, NONE));
    }
}
