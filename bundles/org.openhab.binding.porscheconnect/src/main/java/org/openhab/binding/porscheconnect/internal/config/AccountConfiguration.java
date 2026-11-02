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
package org.openhab.binding.porscheconnect.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of the account bridge.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class AccountConfiguration {
    public String email = "";
    public String password = "";
    /** Answer to the captcha shown after a login attempt was blocked; cleared once used. */
    public String captcha = "";
    /** Polling interval in minutes. */
    public int refreshInterval = 30;
}
