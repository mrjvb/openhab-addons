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
package org.openhab.binding.dyson.internal.discovery;

import static org.openhab.binding.dyson.internal.DysonBindingConstants.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.dyson.internal.api.dto.DysonManifestDevice;
import org.openhab.binding.dyson.internal.handler.DysonAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Wi-Fi connected devices of a Dyson account.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = DysonDiscoveryService.class)
public class DysonDiscoveryService extends AbstractThingHandlerDiscoveryService<DysonAccountHandler> {
    private static final int SEARCH_TIME_SECONDS = 10;

    private final Logger logger = LoggerFactory.getLogger(DysonDiscoveryService.class);

    public DysonDiscoveryService() {
        super(DysonAccountHandler.class, DEVICE_THING_TYPES, SEARCH_TIME_SECONDS);
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
        onDevicesUpdated(thingHandler.getDevices());
        thingHandler.startScan();
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan(), thingHandler.getThing().getUID());
    }

    public void onDevicesUpdated(Collection<DysonManifestDevice> devices) {
        for (DysonManifestDevice device : devices) {
            String serial = device.serialNumber;
            ThingTypeUID thingType = getThingType(device);
            if (serial == null || thingType == null) {
                logger.debug("Skipping unsupported Dyson device {} (category {}, connection {})", serial,
                        device.category, device.connectionCategory);
                continue;
            }
            ThingUID bridgeUID = thingHandler.getThing().getUID();
            ThingUID thingUID = new ThingUID(thingType, bridgeUID, serial.replaceAll("[^A-Za-z0-9_-]", "_"));

            Map<String, Object> properties = new HashMap<>();
            properties.put(CONFIG_SERIAL_NUMBER, serial);
            putIfNotNull(properties, "model", device.model);
            putIfNotNull(properties, PROPERTY_PRODUCT_TYPE, device.type);
            putIfNotNull(properties, PROPERTY_VARIANT, device.variant);
            putIfNotNull(properties, PROPERTY_CATEGORY, device.category);

            thingDiscovered(DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withProperties(properties)
                    .withRepresentationProperty(CONFIG_SERIAL_NUMBER).withLabel(device.getDisplayName()).build());
        }
    }

    private static @Nullable ThingTypeUID getThingType(DysonManifestDevice device) {
        DysonManifestDevice.Mqtt mqtt = device.getMqtt();
        if (mqtt == null || mqtt.localBrokerCredentials == null) {
            // Bluetooth-only or non-connected devices cannot be controlled over MQTT
            return null;
        }
        if (CATEGORY_EC.equals(device.category)) {
            return THING_TYPE_FAN;
        } else if (CATEGORY_ROBOT.equals(device.category)) {
            return THING_TYPE_ROBOT;
        }
        return null;
    }

    private static void putIfNotNull(Map<String, Object> properties, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }
}
