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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The constants of the OnlyCat binding.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public final class OnlyCatBindingConstants {

    public static final String BINDING_ID = "onlycat";

    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_FLAP = new ThingTypeUID(BINDING_ID, "flap");
    public static final ThingTypeUID THING_TYPE_CAT = new ThingTypeUID(BINDING_ID, "cat");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_FLAP,
            THING_TYPE_CAT);
    public static final Set<ThingTypeUID> DISCOVERABLE_THING_TYPES = Set.of(THING_TYPE_FLAP, THING_TYPE_CAT);

    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CONFIG_RFID_CODE = "rfidCode";

    public static final String PROPERTY_TIME_ZONE = "timeZone";
    public static final String PROPERTY_FIRMWARE_CHANNEL = "firmwareChannel";
    public static final String PROPERTY_MANUAL_LOCATION = "manualLocation";
    public static final String PROPERTY_MANUAL_LOCATION_TIME = "manualLocationTime";

    // flap channels
    public static final String CHANNEL_CONNECTED = "connected";
    public static final String CHANNEL_DISCONNECT_REASON = "disconnect-reason";
    public static final String CHANNEL_CONNECTIVITY_CHANGED = "connectivity-changed";
    public static final String CHANNEL_LOCKED = "locked";
    public static final String CHANNEL_DOOR_POLICY = "door-policy";
    public static final String CHANNEL_DOOR_POLICY_DEFINITION = "door-policy-definition";
    public static final String CHANNEL_UNLOCK = "unlock";
    public static final String CHANNEL_REBOOT = "reboot";
    public static final String CHANNEL_EVENT_ACTIVE = "event-active";
    public static final String CHANNEL_EVENT_ID = "event-id";
    public static final String CHANNEL_EVENT_TIME = "event-time";
    public static final String CHANNEL_EVENT_TRIGGER = "event-trigger";
    public static final String CHANNEL_EVENT_CLASSIFICATION = "event-classification";
    public static final String CHANNEL_EVENT_RFID = "event-rfid";
    public static final String CHANNEL_EVENT_CAT = "event-cat";
    public static final String CHANNEL_EVENT_DIRECTION = "event-direction";
    public static final String CHANNEL_EVENT_ACTION = "event-action";
    public static final String CHANNEL_CONTRABAND = "contraband";
    public static final String CHANNEL_HUMAN = "human";
    public static final String CHANNEL_IMAGE = "image";
    public static final String CHANNEL_VIDEO_URL = "video-url";
    public static final String CHANNEL_ERROR = "error";
    public static final String CHANNEL_REBOOT_LOG = "reboot-log";
    public static final String CHANNEL_TELEMETRY = "telemetry";

    // cat channels
    public static final String CHANNEL_PRESENCE = "presence";
    public static final String CHANNEL_LAST_SEEN = "last-seen";
    public static final String CHANNEL_LAST_FLAP = "last-flap";
    public static final String CHANNEL_LAST_DIRECTION = "last-direction";
    public static final String CHANNEL_LAST_ACTION = "last-action";

    private OnlyCatBindingConstants() {
    }
}
