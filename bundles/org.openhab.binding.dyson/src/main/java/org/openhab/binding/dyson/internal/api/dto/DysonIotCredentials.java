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

import com.google.gson.annotations.SerializedName;

/**
 * AWS IoT connection data returned by {@code POST /v2/authorize/iot-credentials}.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonIotCredentials {
    @SerializedName("Endpoint")
    public @Nullable String endpoint;
    @SerializedName("IoTCredentials")
    public @Nullable Credentials credentials;

    public static class Credentials {
        @SerializedName("ClientId")
        public @Nullable String clientId;
        @SerializedName("CustomAuthorizerName")
        public @Nullable String customAuthorizerName;
        @SerializedName("TokenKey")
        public @Nullable String tokenKey;
        @SerializedName("TokenSignature")
        public @Nullable String tokenSignature;
        @SerializedName("TokenValue")
        public @Nullable String tokenValue;
    }
}
