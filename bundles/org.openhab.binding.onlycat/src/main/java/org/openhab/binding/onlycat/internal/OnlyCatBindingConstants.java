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
package org.openhab.binding.onlycat.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link OnlyCatBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class OnlyCatBindingConstants {

    private static final String BINDING_ID = "onlycat";

    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "device");

    public static final String CONFIG_API_KEY = "apiKey";
    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CHANNEL_CONNECTED = "connected";
    public static final String CHANNEL_DESCRIPTION = "description";
    public static final String CHANNEL_ACTIVE_POLICY = "active-policy";
}
