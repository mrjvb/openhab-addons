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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Generic failure talking to the Porsche Connect API. {@link #getHttpStatus()} is 0 when the error is not tied to an
 * HTTP status (network error, unexpected response structure, ...).
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public PorscheApiException(String message) {
        this(0, message, null);
    }

    public PorscheApiException(String message, @Nullable Throwable cause) {
        this(0, message, cause);
    }

    public PorscheApiException(int httpStatus, String message) {
        this(httpStatus, message, null);
    }

    public PorscheApiException(int httpStatus, String message, @Nullable Throwable cause) {
        super(httpStatus > 0 ? statusName(httpStatus) + ": " + message : message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Mirrors the status code naming of pyporscheconnectapi so log messages are comparable. */
    static String statusName(int code) {
        return switch (code) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "MOBILE_ACCESS_DISABLED";
            case 408 -> "VEHICLE_UNAVAILABLE";
            case 423 -> "ACCOUNT_LOCKED";
            case 429 -> "TOO_MANY_REQUESTS";
            case 500 -> "SERVER_ERROR";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_MAINTENANCE";
            case 504 -> "UPSTREAM_TIMEOUT";
            default -> "HTTP_" + code;
        };
    }
}
