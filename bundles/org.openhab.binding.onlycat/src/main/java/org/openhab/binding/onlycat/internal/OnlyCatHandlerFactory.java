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
import org.openhab.binding.onlycat.internal.handler.OnlyCatAccountHandler;
import org.openhab.binding.onlycat.internal.handler.OnlyCatCatHandler;
import org.openhab.binding.onlycat.internal.handler.OnlyCatFlapHandler;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates the handlers of the OnlyCat binding.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.onlycat", service = ThingHandlerFactory.class)
public class OnlyCatHandlerFactory extends BaseThingHandlerFactory {

    private final WebSocketFactory webSocketFactory;
    private final HttpClientFactory httpClientFactory;
    private final OnlyCatStateDescriptionProvider stateDescriptionProvider;

    @Activate
    public OnlyCatHandlerFactory(final @Reference WebSocketFactory webSocketFactory,
            final @Reference HttpClientFactory httpClientFactory,
            final @Reference OnlyCatStateDescriptionProvider stateDescriptionProvider) {
        this.webSocketFactory = webSocketFactory;
        this.httpClientFactory = httpClientFactory;
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        if (THING_TYPE_ACCOUNT.equals(thingTypeUID) && thing instanceof Bridge bridge) {
            return new OnlyCatAccountHandler(bridge, webSocketFactory);
        }
        if (THING_TYPE_FLAP.equals(thingTypeUID)) {
            return new OnlyCatFlapHandler(thing, httpClientFactory.getCommonHttpClient(), stateDescriptionProvider);
        }
        if (THING_TYPE_CAT.equals(thingTypeUID)) {
            return new OnlyCatCatHandler(thing);
        }
        return null;
    }
}
