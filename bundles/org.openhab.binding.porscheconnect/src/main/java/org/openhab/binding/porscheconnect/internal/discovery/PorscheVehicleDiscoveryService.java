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
package org.openhab.binding.porscheconnect.internal.discovery;

import static org.openhab.binding.porscheconnect.internal.PorscheConnectBindingConstants.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.porscheconnect.internal.api.VehicleData;
import org.openhab.binding.porscheconnect.internal.handler.PorscheAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Adds the vehicles of a Porsche ID to the inbox.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = PorscheVehicleDiscoveryService.class)
public class PorscheVehicleDiscoveryService extends AbstractThingHandlerDiscoveryService<PorscheAccountHandler> {

    private static final int SCAN_TIMEOUT_SECONDS = 20;

    public PorscheVehicleDiscoveryService() {
        super(PorscheAccountHandler.class, Set.of(THING_TYPE_VEHICLE), SCAN_TIMEOUT_SECONDS, true);
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
        vehiclesFound(thingHandler.getKnownVehicles());
        thingHandler.scanForVehicles();
    }

    public void vehiclesFound(Collection<VehicleData> vehicles) {
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        for (VehicleData vehicle : vehicles) {
            String vin = vehicle.getVin();
            if (vin.isBlank()) {
                continue;
            }
            Map<String, Object> properties = new HashMap<>();
            properties.put(CONFIG_VIN, vin);
            String model = vehicle.getModelName();
            if (model != null) {
                properties.put(PROPERTY_MODEL, model);
            }
            String year = vehicle.getModelYear();
            if (year != null) {
                properties.put(PROPERTY_MODEL_YEAR, year);
            }
            String engine = vehicle.getEngine();
            if (engine != null) {
                properties.put(PROPERTY_ENGINE, engine);
            }
            thingDiscovered(DiscoveryResultBuilder.create(new ThingUID(THING_TYPE_VEHICLE, bridgeUID, vin))
                    .withBridge(bridgeUID).withLabel(vehicle.getName()).withProperties(properties)
                    .withRepresentationProperty(CONFIG_VIN).build());
        }
    }
}
