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
package org.openhab.binding.zmote.internal;

import static org.openhab.binding.zmote.internal.ZMoteBindingConstants.*;

import java.util.Collections;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;

import org.openhab.binding.zmote.handler.ZMoteHandler;
import org.openhab.binding.zmote.internal.discovery.IZMoteDiscoveryService;
import org.openhab.binding.zmote.internal.service.IZMoteService;

import org.osgi.service.component.annotations.Component;

/**
 * The {@link ZMoteHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Alexander Maret-Huskinson - Initial contribution
 * @author Marcel van Bergen - Update to OH3
 */
@NonNullByDefault
@Component(configurationPid = "binding.zmote", service = ThingHandlerFactory.class)
public class ZMoteHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_ZMT2);

    private IZMoteService zmoteService = null;
    private IZMoteDiscoveryService zmoteDiscoveryService = null;

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        if ((zmoteService == null) || (zmoteDiscoveryService == null)) {
            throw new IllegalStateException("Cannot create ZMote thing as the required services are not available!");
        }

        final ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (thingTypeUID.equals(THING_TYPE_ZMT2)) {
            return new ZMoteHandler(thing, zmoteService, zmoteDiscoveryService);
        }

        return null;
    }

    // used by OSGI to set the service
    protected void setZMoteDiscoveryService(final IZMoteDiscoveryService zmoteDiscoveryService) {
        this.zmoteDiscoveryService = zmoteDiscoveryService;
    }

    // used by OSGI to set the service
    protected void setZMoteService(final IZMoteService service) {
        zmoteService = service;
    }

    // used by OSGI to unset the service
    protected void unsetZMoteDiscoveryService(final IZMoteDiscoveryService zmoteDiscoveryService) {
        this.zmoteDiscoveryService = null;
    }

    // used by OSGI to unset the service
    protected void unsetZMoteService(final IZMoteService service) {
        zmoteService = null;
    }

}
