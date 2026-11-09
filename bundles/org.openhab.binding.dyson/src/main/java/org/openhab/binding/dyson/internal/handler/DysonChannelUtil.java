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
package org.openhab.binding.dyson.internal.handler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import javax.measure.Quantity;
import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Conversions from Dyson state values (mostly zero padded strings) to openHAB states.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class DysonChannelUtil {

    private DysonChannelUtil() {
    }

    /**
     * Parses a Dyson numeric value. Returns null for non-numeric states such as OFF, INIT, FAIL, NONE or INV.
     */
    public static @Nullable BigDecimal number(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static <Q extends Quantity<Q>> State quantity(@Nullable String raw, double divisor, Unit<Q> unit) {
        BigDecimal value = number(raw);
        if (value == null) {
            return UnDefType.UNDEF;
        }
        if (divisor != 1) {
            value = value.divide(BigDecimal.valueOf(divisor), 3, RoundingMode.HALF_UP);
        }
        return new QuantityType<>(value, unit);
    }

    public static State decimal(@Nullable String raw) {
        BigDecimal value = number(raw);
        return value == null ? UnDefType.UNDEF : new DecimalType(value);
    }

    public static State onOff(@Nullable String raw, String... onValues) {
        if (raw == null) {
            return UnDefType.UNDEF;
        }
        for (String on : onValues) {
            if (on.equals(raw)) {
                return OnOffType.ON;
            }
        }
        return OnOffType.OFF;
    }

    public static State string(@Nullable String raw) {
        return raw == null ? UnDefType.UNDEF : new StringType(raw);
    }

    /**
     * Converts a temperature in tenths of Kelvin (e.g. 2951 = 295.1 K) to degrees Celsius.
     */
    public static @Nullable BigDecimal deciKelvinToCelsius(@Nullable String raw) {
        BigDecimal value = number(raw);
        if (value == null) {
            return null;
        }
        return value.divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP).subtract(new BigDecimal("273.15")).setScale(1,
                RoundingMode.HALF_UP);
    }

    /**
     * Converts degrees Celsius to the four digit tenths of Kelvin representation used by Dyson.
     */
    public static String celsiusToDeciKelvin(double celsius) {
        return String.format(Locale.ROOT, "%04d", Math.round((celsius + 273.15) * 10));
    }
}
