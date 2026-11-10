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
package org.openhab.binding.onlycat.internal.handler;

import static org.openhab.binding.onlycat.internal.OnlyCatBindingConstants.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.onlycat.internal.config.OnlyCatCatConfiguration;
import org.openhab.binding.onlycat.internal.dto.CatLocation;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Represents a cat.
 *
 * A cat that leaves or returns by another way than the flap leaves the presence state wrong, so the presence channel
 * accepts a command to correct it. The correction is kept in a Thing property, and it holds until the cat is next seen
 * at a flap.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class OnlyCatCatHandler extends BaseThingHandler {

    private String rfidCode = "";
    private volatile @Nullable CatState cat;
    private volatile @Nullable CatLocation manualLocation;
    private volatile @Nullable Instant manualTime;

    public OnlyCatCatHandler(Thing thing) {
        super(thing);
    }

    public String getRfidCode() {
        return rfidCode;
    }

    @Override
    public void initialize() {
        rfidCode = getConfigAs(OnlyCatCatConfiguration.class).rfidCode;
        if (rfidCode.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error.missing-rfid-code");
            return;
        }
        restoreManualLocation();
        updateStatus(ThingStatus.UNKNOWN);
        OnlyCatAccountHandler account = getAccount();
        if (account != null) {
            account.requestReplay(this);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            publish();
        } else if (CHANNEL_PRESENCE.equals(channelUID.getId()) && command instanceof OnOffType onOff) {
            setManualLocation(onOff == OnOffType.ON ? CatLocation.INSIDE : CatLocation.OUTSIDE, Instant.now());
            publish();
        }
    }

    void updateCat(CatState state) {
        cat = state;
        Instant lastSeen = state.lastSeen;
        Instant manual = manualTime;
        if (manual != null && lastSeen != null && lastSeen.isAfter(manual)) {
            setManualLocation(null, null);
        }
        updateStatus(ThingStatus.ONLINE);
        publish();
    }

    void catNotFound() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.config-error.unknown-cat");
    }

    private void publish() {
        CatState state = cat;
        CatLocation manual = manualLocation;
        CatLocation location = manual != null ? manual : state == null ? null : state.location;
        updateState(CHANNEL_PRESENCE,
                location == null ? UnDefType.UNDEF : OnOffType.from(location == CatLocation.INSIDE));
        if (state != null) {
            Instant lastSeen = state.lastSeen;
            updateState(CHANNEL_LAST_SEEN, lastSeen == null ? UnDefType.UNDEF : new DateTimeType(lastSeen));
            updateState(CHANNEL_LAST_FLAP, stringState(state.lastDeviceId));
            updateState(CHANNEL_LAST_DIRECTION, stringState(state.lastDirection));
            updateState(CHANNEL_LAST_ACTION, stringState(state.lastAction));
        }
    }

    private static State stringState(@Nullable String value) {
        return value == null ? UnDefType.UNDEF : new StringType(value);
    }

    private void setManualLocation(@Nullable CatLocation location, @Nullable Instant time) {
        manualLocation = location;
        manualTime = time;
        updateProperty(PROPERTY_MANUAL_LOCATION, location == null ? null : location.name());
        updateProperty(PROPERTY_MANUAL_LOCATION_TIME, time == null ? null : time.toString());
    }

    private @Nullable OnlyCatAccountHandler getAccount() {
        var bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof OnlyCatAccountHandler account ? account : null;
    }

    private void restoreManualLocation() {
        String location = getThing().getProperties().get(PROPERTY_MANUAL_LOCATION);
        String time = getThing().getProperties().get(PROPERTY_MANUAL_LOCATION_TIME);
        if (location != null && time != null) {
            try {
                manualLocation = CatLocation.valueOf(location);
                manualTime = Instant.parse(time);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                setManualLocation(null, null);
            }
        }
    }
}
