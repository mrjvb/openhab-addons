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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.onlycat.internal.dto.CatLocation;

/**
 * What is known about a cat across all flaps of the account.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class CatState {
    public final String rfidCode;
    public @Nullable String label;
    public @Nullable CatLocation location;
    public @Nullable Instant lastSeen;
    public @Nullable String lastDeviceId;
    public @Nullable Long lastEventId;
    public @Nullable String lastDirection;
    public @Nullable String lastAction;

    public CatState(String rfidCode) {
        this.rfidCode = rfidCode;
    }

    /**
     * @return the name of the cat, or its RFID code if it has none
     */
    public String getName() {
        String name = label;
        return name == null || name.isBlank() ? rfidCode : name;
    }
}
