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
package org.openhab.binding.porscheconnect.internal.api;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * OAuth2 token set as persisted by the binding. Immutable; a refresh produces a new instance.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public record PorscheToken(@Nullable String accessToken, @Nullable String refreshToken, long expiresAt) {

    public static final PorscheToken EMPTY = new PorscheToken(null, null, 0);

    public boolean hasAccessToken() {
        String token = accessToken;
        return token != null && !token.isBlank();
    }

    public boolean hasRefreshToken() {
        String token = refreshToken;
        return token != null && !token.isBlank();
    }

    /**
     * @param leewaySeconds treat the token as expired this many seconds before it actually expires
     */
    public boolean isExpired(long leewaySeconds) {
        return expiresAt - leewaySeconds < Instant.now().getEpochSecond();
    }
}
