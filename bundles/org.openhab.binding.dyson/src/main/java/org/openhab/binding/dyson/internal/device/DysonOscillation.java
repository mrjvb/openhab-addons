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
package org.openhab.binding.dyson.internal.device;

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Oscillation angle calculations for devices with the {@code AdvanceOscillationDay1} capability (0-350°) and the
 * {@code AdvanceOscillationDay0} capability (fixed window of 142-212°).
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class DysonOscillation {

    public static final int DAY1_MIN = 0;
    public static final int DAY1_MAX = 350;
    public static final int DAY0_MIN = 142;
    public static final int DAY0_MAX = 212;
    public static final int DAY0_PRESET_LOWER = 157;
    public static final int DAY0_PRESET_UPPER = 197;

    /** Lower and upper oscillation angle. */
    public record Angles(int lower, int upper) {
        public int center() {
            return (lower + upper) / 2;
        }

        public int span() {
            return upper - lower;
        }
    }

    private DysonOscillation() {
    }

    /**
     * Returns the angles for a named preset span, centred as close as possible to the given midpoint.
     */
    public static Angles forPreset(int span, int midpoint, int min, int max) {
        if (span >= max - min) {
            return new Angles(min, max);
        }
        int lower = (int) Math.round(midpoint - span / 2.0);
        return clamp(lower, lower + span, min, max);
    }

    /**
     * Returns the angles for a new centre while keeping the span.
     */
    public static Angles withCenter(Angles current, int center, int min, int max) {
        int span = current.span();
        int lower = (int) Math.round(center - span / 2.0);
        return clamp(lower, lower + span, min, max);
    }

    /**
     * Returns the angles for a new span while keeping the centre.
     */
    public static Angles withSpan(Angles current, int span, int min, int max) {
        int boundedSpan = Math.max(0, Math.min(span, max - min));
        int lower = (int) Math.round(current.center() - boundedSpan / 2.0);
        return clamp(lower, lower + boundedSpan, min, max);
    }

    /**
     * Shifts the range into [min, max] without changing its span.
     */
    private static Angles clamp(int lower, int upper, int min, int max) {
        if (lower < min) {
            upper += min - lower;
            lower = min;
        }
        if (upper > max) {
            lower -= upper - max;
            upper = max;
        }
        return new Angles(Math.max(min, lower), upper);
    }

    public static String format(int value) {
        return String.format(Locale.ROOT, "%04d", value);
    }

    public static int parse(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
