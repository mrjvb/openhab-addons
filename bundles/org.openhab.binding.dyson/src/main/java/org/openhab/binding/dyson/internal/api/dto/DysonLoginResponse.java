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
package org.openhab.binding.dyson.internal.api.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Responses of the Dyson login endpoints. Only the fields used by the binding are mapped.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonLoginResponse {
    /** {@code /userstatus} */
    public @Nullable String accountStatus;
    public @Nullable String authenticationMethod;
    /** {@code /auth} */
    public @Nullable String challengeId;
    /** {@code /verify} */
    public @Nullable String account;
    public @Nullable String token;
    public @Nullable String tokenType;
}
