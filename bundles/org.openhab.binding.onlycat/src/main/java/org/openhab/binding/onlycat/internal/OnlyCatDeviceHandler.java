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

import static org.openhab.binding.onlycat.internal.OnlyCatBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.json.JSONObject;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

/** Handler for one discovered OnlyCat flap. */
@NonNullByDefault
public class OnlyCatDeviceHandler extends BaseThingHandler {

    private OnlyCatDeviceConfiguration config = new OnlyCatDeviceConfiguration();

    public OnlyCatDeviceHandler(Thing thing) {
        super(thing);
    }

    String getDeviceId() {
        return config.deviceId;
    }

    @Override
    public void initialize() {
        config = getConfigAs(OnlyCatDeviceConfiguration.class);
        if (config.deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Missing OnlyCat device ID");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        OnlyCatAccountHandler account = getAccountHandler();
        if (account == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        JSONObject current = account.getDeviceData(config.deviceId);
        if (current != null) {
            deviceUpdated(current);
        }
        account.requestDeviceRefresh(config.deviceId);
    }

    @Override
    public void dispose() {
    }

    @Override
    public void bridgeStatusChanged(org.openhab.core.thing.ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            OnlyCatAccountHandler account = getAccountHandler();
            if (account != null) {
                account.requestDeviceRefresh(config.deviceId);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            OnlyCatAccountHandler account = getAccountHandler();
            if (account != null) {
                account.requestDeviceRefresh(config.deviceId);
            }
        }
    }

    void deviceUpdated(JSONObject device) {
        updateStatus(ThingStatus.ONLINE);
        JSONObject connectivity = device.optJSONObject("connectivity");
        if (connectivity != null && connectivity.has("connected")) {
            updateState(CHANNEL_CONNECTED, connectivity.optBoolean("connected") ? OnOffType.ON : OnOffType.OFF);
        }
        String description = device.optString("description", "");
        if (!description.isBlank()) {
            updateState(CHANNEL_DESCRIPTION, new StringType(description));
        }
        if (!device.isNull("deviceTransitPolicyId")) {
            updateState(CHANNEL_ACTIVE_POLICY, new DecimalType(device.optInt("deviceTransitPolicyId")));
        }
    }

    private @Nullable OnlyCatAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof OnlyCatAccountHandler handler ? handler : null;
    }
}
