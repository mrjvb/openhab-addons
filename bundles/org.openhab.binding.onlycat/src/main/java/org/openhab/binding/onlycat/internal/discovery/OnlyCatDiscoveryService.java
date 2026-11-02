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
package org.openhab.binding.onlycat.internal.discovery;

import static org.openhab.binding.onlycat.internal.OnlyCatBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.onlycat.internal.dto.Device;
import org.openhab.binding.onlycat.internal.handler.CatState;
import org.openhab.binding.onlycat.internal.handler.OnlyCatAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Offers the flaps and cats of an account. Results arrive as the account learns about them, also in the background.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = OnlyCatDiscoveryService.class)
public class OnlyCatDiscoveryService extends AbstractThingHandlerDiscoveryService<OnlyCatAccountHandler> {

    private static final int SCAN_TIMEOUT_SECONDS = 5;

    public OnlyCatDiscoveryService() {
        super(OnlyCatAccountHandler.class, DISCOVERABLE_THING_TYPES, SCAN_TIMEOUT_SECONDS);
    }

    @Override
    public void initialize() {
        thingHandler.setDiscoveryService(this);
        super.initialize();
    }

    @Override
    public void dispose() {
        super.dispose();
        thingHandler.setDiscoveryService(null);
    }

    @Override
    protected void startScan() {
        thingHandler.discover();
    }

    public void flapFound(Device device) {
        String deviceId = device.deviceId;
        if (deviceId == null) {
            return;
        }
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        String description = device.description;
        thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(THING_TYPE_FLAP, bridgeUID, uidSegment(deviceId)))
                .withBridge(bridgeUID).withLabel(description == null ? "OnlyCat Flap " + deviceId : description)
                .withProperty(CONFIG_DEVICE_ID, deviceId).withRepresentationProperty(CONFIG_DEVICE_ID).build());
    }

    public void catFound(CatState cat) {
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        thingDiscovered(
                DiscoveryResultBuilder.create(new ThingUID(THING_TYPE_CAT, bridgeUID, uidSegment(cat.rfidCode)))
                        .withBridge(bridgeUID).withLabel(cat.getName()).withProperty(CONFIG_RFID_CODE, cat.rfidCode)
                        .withRepresentationProperty(CONFIG_RFID_CODE).build());
    }

    private static String uidSegment(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
