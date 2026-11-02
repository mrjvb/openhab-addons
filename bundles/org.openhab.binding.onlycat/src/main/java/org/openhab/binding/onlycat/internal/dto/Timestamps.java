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
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Reads the timestamps of the gateway, which are ISO 8601 strings or epoch milliseconds depending on the message.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class Timestamps {

    private Timestamps() {
    }

    public static @Nullable Instant parse(@Nullable JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive)) {
            return null;
        }
        if (primitive.isNumber()) {
            return Instant.ofEpochMilli(primitive.getAsLong());
        }
        try {
            return Instant.parse(primitive.getAsString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
