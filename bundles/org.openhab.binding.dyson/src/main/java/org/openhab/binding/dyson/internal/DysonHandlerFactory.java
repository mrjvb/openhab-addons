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
package org.openhab.binding.dyson.internal;

import static org.openhab.binding.dyson.internal.DysonBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.dyson.internal.handler.DysonAccountHandler;
import org.openhab.binding.dyson.internal.handler.DysonFanHandler;
import org.openhab.binding.dyson.internal.handler.DysonRobotHandler;
import org.openhab.core.io.net.http.HttpClientFactory;
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
 * The {@link DysonHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.dyson", service = ThingHandlerFactory.class)
public class DysonHandlerFactory extends BaseThingHandlerFactory {

    private final HttpClientFactory httpClientFactory;
    private final DysonStateDescriptionProvider stateDescriptionProvider;

    @Activate
    public DysonHandlerFactory(final @Reference HttpClientFactory httpClientFactory,
            final @Reference DysonStateDescriptionProvider stateDescriptionProvider) {
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

        if (THING_TYPE_ACCOUNT.equals(thingTypeUID)) {
            return new DysonAccountHandler((Bridge) thing, httpClientFactory.getCommonHttpClient());
        } else if (THING_TYPE_FAN.equals(thingTypeUID)) {
            return new DysonFanHandler(thing, stateDescriptionProvider);
        } else if (THING_TYPE_ROBOT.equals(thingTypeUID)) {
            return new DysonRobotHandler(thing, stateDescriptionProvider);
        }

        return null;
    }
}
