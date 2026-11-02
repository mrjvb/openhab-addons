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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Works out the lock state of a flap from its door policy, following the published door policy schema: rules are
 * checked in order and the first matching one decides.
 *
 * The gateway does not report the lock state, so this is an estimate. The estimate is {@link LockVerdict#UNKNOWN}
 * rather than a guess when the deciding rule depends on something that is not known: the flap and motion sensor states
 * are never reported, and the RFID code and classification may simply not have arrived yet. Disabling a rule type with
 * {@link Options} declares that such rules do not affect the outcome.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class DoorPolicy {

    /**
     * What to leave out of the evaluation.
     *
     * @param ignoreFlapStateRules whether to skip rules on the flap state
     * @param ignoreMotionSensorRules whether to skip rules on the motion sensor state
     */
    public record Options(boolean ignoreFlapStateRules, boolean ignoreMotionSensorRules) {
    }

    private enum Match {
        NO,
        PENDING,
        YES;

        Match and(Match other) {
            return compareTo(other) < 0 ? this : other;
        }
    }

    private record TimeRange(LocalTime start, LocalTime end) {
        /** Both ends are whole minutes and the end minute is included. */
        boolean contains(LocalTime time) {
            LocalTime minute = time.truncatedTo(ChronoUnit.MINUTES);
            if (start.isAfter(end)) {
                return !minute.isBefore(start) || !minute.isAfter(end);
            }
            return !minute.isBefore(start) && !minute.isAfter(end);
        }
    }

    private record Rule(boolean enabled, List<Integer> triggerSources, List<Integer> classifications,
            List<String> rfidCodes, List<TimeRange> timeRanges, boolean validTimeRanges, boolean onFlapState,
            boolean onMotionSensor, @Nullable Boolean lock) {
    }

    private final boolean idleLock;
    private final List<Rule> rules;

    private DoorPolicy(boolean idleLock, List<Rule> rules) {
        this.idleLock = idleLock;
        this.rules = rules;
    }

    /**
     * Reads a policy.
     *
     * @param transitPolicy the {@code transitPolicy} object of the gateway
     */
    public static DoorPolicy parse(JsonObject transitPolicy) {
        boolean idleLock = transitPolicy.get("idleLock") instanceof JsonPrimitive value && value.isBoolean()
                && value.getAsBoolean();
        List<Rule> rules = new ArrayList<>();
        if (transitPolicy.get("rules") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (element instanceof JsonObject rule) {
                    rules.add(parseRule(rule));
                }
            }
        }
        return new DoorPolicy(idleLock, rules);
    }

    private static Rule parseRule(JsonObject rule) {
        JsonObject criteria = rule.get("criteria") instanceof JsonObject object ? object : new JsonObject();
        JsonObject action = rule.get("action") instanceof JsonObject object ? object : new JsonObject();
        boolean enabled = !(rule.get("enabled") instanceof JsonPrimitive value) || !value.isBoolean()
                || value.getAsBoolean();

        List<TimeRange> timeRanges = new ArrayList<>();
        boolean validTimeRanges = true;
        for (String text : strings(criteria.get("timeRange"))) {
            TimeRange range = parseTimeRange(text);
            if (range == null) {
                validTimeRanges = false;
            } else {
                timeRanges.add(range);
            }
        }
        Boolean lock = action.get("lock") instanceof JsonPrimitive value && value.isBoolean() ? value.getAsBoolean()
                : null;
        return new Rule(enabled, integers(criteria.get("eventTriggerSource")),
                integers(criteria.get("eventClassification")), strings(criteria.get("rfidCode")), timeRanges,
                validTimeRanges, !integers(criteria.get("flapState")).isEmpty(),
                !integers(criteria.get("motionSensorState")).isEmpty(), lock);
    }

    private static @Nullable TimeRange parseTimeRange(String text) {
        String[] ends = text.split("-");
        if (ends.length != 2) {
            return null;
        }
        try {
            return new TimeRange(parseTime(ends[0]), parseTime(ends[1]));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String text) {
        String trimmed = text.trim();
        return LocalTime.parse(trimmed.length() == 4 ? "0" + trimmed : trimmed);
    }

    private static List<Integer> integers(@Nullable JsonElement value) {
        List<Integer> result = new ArrayList<>();
        for (JsonElement element : elements(value)) {
            if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
                result.add(primitive.getAsInt());
            }
        }
        return result;
    }

    private static List<String> strings(@Nullable JsonElement value) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : elements(value)) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    /** The schema allows a single value wherever it allows a list. */
    private static List<JsonElement> elements(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (value instanceof JsonArray array) {
            return array.asList();
        }
        return List.of(value);
    }

    /**
     * @return the state of the flap when no event is going on
     */
    public LockVerdict idleState() {
        return idleLock ? LockVerdict.LOCKED : LockVerdict.UNLOCKED;
    }

    /**
     * @return the state the policy puts the flap in during the given event
     */
    public LockVerdict evaluate(DeviceEvent event, ZoneId zone, Options options) {
        Integer triggerSource = event.eventTriggerSource;
        if (triggerSource != null && triggerSource == DeviceEvent.TRIGGER_REMOTE) {
            return LockVerdict.UNLOCKED;
        }
        for (Rule rule : rules) {
            if (!rule.enabled() || (options.ignoreFlapStateRules() && rule.onFlapState())
                    || (options.ignoreMotionSensorRules() && rule.onMotionSensor())) {
                continue;
            }
            Match match = matches(rule, event, zone);
            if (match == Match.PENDING) {
                return LockVerdict.UNKNOWN;
            }
            if (match == Match.YES) {
                Boolean lock = rule.lock();
                if (lock == null) {
                    return idleState();
                }
                return lock ? LockVerdict.LOCKED : LockVerdict.UNLOCKED;
            }
        }
        return idleState();
    }

    private Match matches(Rule rule, DeviceEvent event, ZoneId zone) {
        Match match = rule.onFlapState() || rule.onMotionSensor() ? Match.PENDING : Match.YES;
        return match.and(matchesTrigger(rule, event)).and(matchesClassification(rule, event))
                .and(matchesRfid(rule, event)).and(matchesTime(rule, event, zone));
    }

    private Match matchesTrigger(Rule rule, DeviceEvent event) {
        if (rule.triggerSources().isEmpty()) {
            return Match.YES;
        }
        Integer source = event.eventTriggerSource;
        if (source == null) {
            return Match.PENDING;
        }
        return rule.triggerSources().contains(source) ? Match.YES : Match.NO;
    }

    private Match matchesClassification(Rule rule, DeviceEvent event) {
        if (rule.classifications().isEmpty()) {
            return Match.YES;
        }
        Integer classification = event.eventClassification;
        if (classification != null && rule.classifications().contains(classification)) {
            return Match.YES;
        }
        boolean undecided = classification == null || classification == DeviceEvent.CLASSIFICATION_UNKNOWN;
        return undecided && !event.isConcluded() ? Match.PENDING : Match.NO;
    }

    private Match matchesRfid(Rule rule, DeviceEvent event) {
        if (rule.rfidCodes().isEmpty()) {
            return Match.YES;
        }
        List<String> codes = event.getRfidCodes();
        if (codes.stream().anyMatch(rule.rfidCodes()::contains)) {
            return Match.YES;
        }
        return codes.isEmpty() && !event.isConcluded() ? Match.PENDING : Match.NO;
    }

    private Match matchesTime(Rule rule, DeviceEvent event, ZoneId zone) {
        if (!rule.validTimeRanges()) {
            return Match.PENDING;
        }
        if (rule.timeRanges().isEmpty()) {
            return Match.YES;
        }
        Instant timestamp = event.getTimestamp();
        if (timestamp == null) {
            return Match.PENDING;
        }
        LocalTime time = timestamp.atZone(zone).toLocalTime();
        return rule.timeRanges().stream().anyMatch(range -> range.contains(time)) ? Match.YES : Match.NO;
    }
}
