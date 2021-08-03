/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.zmote;

import java.util.Collections;
import java.util.Set;

import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link ZMoteBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Alexander Maret-Huskinson - Initial contribution
 * @author Marcel van Bergen - Update to OH3
 */
@NonNullByDefault
public class ZMoteBindingConstants {

    public static final String BINDING_ID = "zmote";

    public final static ThingTypeUID THING_TYPE_ZMT2 = new ThingTypeUID(BINDING_ID, "zmt2");

    public final static int DEFAULT_TIMEOUT = 5;
    public final static int DEFAULT_RETRY = 3;

    public final static int DISCOVERY_INTERVAL = 60;
    public final static int DISCOVERY_TIMEOUT = 20;
    public final static int DISCOVERY_UPDATE_INTERVAL = 30;

    public final static String CHANNEL_ONLINE = "online";
    public final static String CHANNEL_SENDCODE = "sendcode";
    public final static String CHANNEL_SENDKEY = "sendkey";

    public final static String CONFIG_UUID = "uuid";
    public final static String CONFIG_FILE = "configFile";
    public final static String CONFIG_URL = "overrideUrl";
    public final static String CONFIG_RETRY = "retry";
    public final static String CONFIG_TIMEOUT = "timeout";
    public final static String CONFIG_REMOTE = "remote";

    public static final String PROP_UUID = CONFIG_UUID;
    public static final String PROP_URL = "url";
    public static final String PROP_MAKE = "make";
    public static final String PROP_MODEL = "model";
    public static final String PROP_REVISION = "revision";
    public static final String PROP_TYPE = "type";

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_ZMT2);
}