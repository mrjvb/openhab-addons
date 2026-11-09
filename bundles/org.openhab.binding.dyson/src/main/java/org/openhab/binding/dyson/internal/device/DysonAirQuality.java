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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Calculates an overall air quality index from the Dyson environmental sensor data, using the same breakpoints as
 * the Dyson app (EPA ranges for CO2). The overall index is the highest index of all reported pollutants.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class DysonAirQuality {

    public static final String GOOD = "GOOD";
    public static final String FAIR = "FAIR";
    public static final String POOR = "POOR";
    public static final String VERY_POOR = "VERY_POOR";
    public static final String EXTREMELY_POOR = "EXTREMELY_POOR";
    public static final String SEVERE = "SEVERE";

    /** Breakpoint: concentration range [low, high] maps linearly to index range [indexLow, indexHigh]. */
    private record Range(double low, double high, int indexLow, int indexHigh, String category) {
    }

    private record Pollutant(String name, List<String> keys, List<Range> ranges, double scale) {
    }

    /** Result of the calculation. */
    public record Result(int index, String category, List<String> dominantPollutants) {
    }

    private static final List<Range> PM25 = List.of(new Range(0, 35, 0, 50, GOOD), new Range(36, 53, 51, 100, FAIR),
            new Range(54, 70, 101, 150, POOR), new Range(71, 150, 151, 200, VERY_POOR),
            new Range(151, 250, 201, 300, EXTREMELY_POOR), new Range(251, 9999, 301, 500, SEVERE));
    private static final List<Range> PM10 = List.of(new Range(0, 50, 0, 50, GOOD), new Range(51, 75, 51, 100, FAIR),
            new Range(76, 100, 101, 150, POOR), new Range(101, 350, 151, 200, VERY_POOR),
            new Range(351, 420, 201, 300, EXTREMELY_POOR), new Range(421, 9999, 301, 500, SEVERE));
    private static final List<Range> HCHO = List.of(new Range(0, 0.099, 0, 50, GOOD),
            new Range(0.100, 0.299, 51, 100, FAIR), new Range(0.300, 0.499, 101, 150, POOR),
            new Range(0.500, 9999, 151, 500, VERY_POOR));
    private static final List<Range> VOC = List.of(new Range(0, 30, 0, 50, GOOD), new Range(31, 69, 51, 100, FAIR),
            new Range(70, 89, 101, 150, POOR), new Range(90, 250, 151, 200, VERY_POOR),
            new Range(251, 500, 201, 300, EXTREMELY_POOR), new Range(501, 9999, 301, 500, SEVERE));
    private static final List<Range> NO2 = List.of(new Range(0, 53, 0, 50, GOOD), new Range(54, 100, 51, 100, FAIR),
            new Range(101, 360, 101, 150, POOR), new Range(361, 649, 151, 200, VERY_POOR),
            new Range(650, 1249, 201, 300, EXTREMELY_POOR), new Range(1250, 9999, 301, 500, SEVERE));
    private static final List<Range> CO2 = List.of(new Range(0, 440, 0, 50, GOOD), new Range(441, 940, 51, 100, FAIR),
            new Range(941, 1240, 101, 150, POOR), new Range(1241, 1540, 151, 200, VERY_POOR),
            new Range(1541, 3050, 201, 300, EXTREMELY_POOR), new Range(3051, 9999, 301, 500, SEVERE));

    private static final List<Pollutant> POLLUTANTS = List.of(
            new Pollutant("PM2.5", List.of("p25r", "pm25", "pact"), PM25, 1),
            new Pollutant("PM10", List.of("p10r", "pm10"), PM10, 1),
            new Pollutant("VOC", List.of("va10", "vact"), VOC, 1), new Pollutant("NO2", List.of("noxl"), NO2, 1),
            new Pollutant("CO2", List.of("co2r", "co2"), CO2, 1),
            new Pollutant("Formaldehyde", List.of("hcho"), HCHO, 0.001));

    private DysonAirQuality() {
    }

    public static @Nullable Result calculate(Map<String, String> environment) {
        int maxIndex = -1;
        String maxCategory = GOOD;
        List<String> dominant = new ArrayList<>();
        for (Pollutant pollutant : POLLUTANTS) {
            Double value = firstNumber(environment, pollutant.keys());
            if (value == null) {
                continue;
            }
            Range range = findRange(value * pollutant.scale(), pollutant.ranges());
            if (range == null) {
                continue;
            }
            double scaled = value * pollutant.scale();
            int index = range.high() == range.low() ? range.indexLow()
                    : (int) Math.round((range.indexHigh() - range.indexLow()) / (range.high() - range.low())
                            * (Math.min(scaled, range.high()) - range.low()) + range.indexLow());
            if (index > maxIndex) {
                maxIndex = index;
                maxCategory = range.category();
                dominant.clear();
                dominant.add(pollutant.name());
            } else if (index == maxIndex) {
                dominant.add(pollutant.name());
            }
        }
        return maxIndex < 0 ? null : new Result(maxIndex, maxCategory, List.copyOf(dominant));
    }

    private static @Nullable Range findRange(double value, List<Range> ranges) {
        if (value < 0) {
            return null;
        }
        for (Range range : ranges) {
            // Breakpoints are integers for most pollutants; values between two ranges belong to the upper one
            if (value <= range.high()) {
                return range;
            }
        }
        return ranges.get(ranges.size() - 1);
    }

    private static @Nullable Double firstNumber(Map<String, String> environment, List<String> keys) {
        for (String key : keys) {
            String raw = environment.get(key);
            if (raw != null) {
                try {
                    return Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    // OFF, INIT, FAIL, NONE: sensor not reporting
                    return null;
                }
            }
        }
        return null;
    }
}
