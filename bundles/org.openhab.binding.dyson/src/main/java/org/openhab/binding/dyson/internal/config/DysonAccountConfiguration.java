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
package org.openhab.binding.dyson.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of the Dyson account bridge.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DysonAccountConfiguration {
    /** E-mail address of the Dyson account, or a mobile number (with country code) for the China region. */
    public String email = "";
    public String password = "";
    /** ISO 3166-1 alpha-2 country code, e.g. US, GB, DE, CN. */
    public String country = "US";
    /** IETF language tag, e.g. en-US. */
    public String culture = "en-US";
    /** One-time password sent by Dyson during login. */
    public String otp = "";
    /** Bearer token obtained after a successful login. Filled in by the binding. */
    public String token = "";
    /** Interval in minutes to refresh the device list and firmware information. */
    public int refreshInterval = 60;

    public boolean isMobileLogin() {
        return !email.contains("@");
    }
}
