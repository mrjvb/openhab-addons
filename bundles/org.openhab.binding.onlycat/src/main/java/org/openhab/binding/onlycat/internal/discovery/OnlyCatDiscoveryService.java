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

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.json.JSONObject;
import org.openhab.binding.onlycat.internal.OnlyCatAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/** Discovers OnlyCat flaps belonging to an account. */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = OnlyCatDiscoveryService.class)
public class OnlyCatDiscoveryService extends AbstractThingHandlerDiscoveryService<OnlyCatAccountHandler> {

    private static final int SCAN_TIMEOUT_SECONDS = 20;

    public OnlyCatDiscoveryService() {
        super(OnlyCatAccountHandler.class, Set.of(THING_TYPE_DEVICE), SCAN_TIMEOUT_SECONDS, true);
    }

    @Override
    public void initialize() {
        thingHandler.addDiscoveryService(this);
        super.initialize();
    }

    @Override
    public void dispose() {
        super.dispose();
        thingHandler.removeDiscoveryService(this);
    }

    @Override
    protected void startScan() {
        thingHandler.scanForDevices();
    }

    public void devicesFound(Collection<JSONObject> devices) {
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        for (JSONObject device : devices) {
            String deviceId = device.optString("deviceId", "");
            if (deviceId.isBlank()) {
                continue;
            }
            String description = device.optString("description", "OnlyCat Flap");
            thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(THING_TYPE_DEVICE, bridgeUID, deviceId))
                    .withBridge(bridgeUID).withLabel(description).withProperties(Map.of(CONFIG_DEVICE_ID, deviceId))
                    .withRepresentationProperty(CONFIG_DEVICE_ID).build());
        }
    }
}
