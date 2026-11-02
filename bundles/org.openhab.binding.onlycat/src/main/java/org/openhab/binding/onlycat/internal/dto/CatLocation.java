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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Where a cat is, seen from the flap.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public enum CatLocation {
    INSIDE,
    OUTSIDE;

    /**
     * Derives the location from what a cat did at the flap: it ends up on the far side if it went through, and stays
     * on the near side if it only looked or was refused. A breach is a passage the flap did not allow, but a passage.
     *
     * @return the location, or null if the sub-event says nothing about it
     */
    public static @Nullable CatLocation fromSubEvent(EventSummary.@Nullable SubEvent subEvent) {
        if (subEvent == null) {
            return null;
        }
        String action = subEvent.action;
        String direction = subEvent.direction;
        if (action == null || direction == null) {
            return null;
        }
        boolean inward = "INWARD".equals(direction);
        if (!inward && !"OUTWARD".equals(direction)) {
            return null;
        }
        return switch (action) {
            case "TRANSIT", "BREACH" -> inward ? INSIDE : OUTSIDE;
            case "PEEK", "DENY" -> inward ? OUTSIDE : INSIDE;
            default -> null;
        };
    }

    /**
     * Reads the location the gateway reports, falling back to the one implied by the last sub-event.
     */
    public static @Nullable CatLocation fromLastSeen(RfidLastSeen lastSeen) {
        String location = lastSeen.location;
        if ("INSIDE".equals(location)) {
            return INSIDE;
        }
        if ("OUTSIDE".equals(location)) {
            return OUTSIDE;
        }
        return fromSubEvent(lastSeen.lastSubevent);
    }
}
