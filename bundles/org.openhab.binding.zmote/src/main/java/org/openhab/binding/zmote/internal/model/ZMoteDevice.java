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
package org.openhab.binding.zmote.internal.model;

/**
 * A ZMote device as returned by the discovery service.
 *
 * @author Alexander Maret-Huskinson - Initial contribution
 * @author Marcel van Bergen - Update to OH3
 */
public class ZMoteDevice {

    private final String make;
    private final String model;
    private final String revision;
    private final String type;
    private final String url;
    private final String uuid;

    public ZMoteDevice(final String make, final String type, final String model, final String revision,
            final String uuid, final String url) {
        this.make = make;
        this.model = model;
        this.revision = revision;
        this.type = type;
        this.uuid = uuid;
        this.url = url;
    }

    public String getMake() {
        return make;
    }

    public String getModel() {
        return model;
    }

    public String getRevision() {
        return revision;
    }

    public String getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public String getUuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return String.format("%s %s %s %s [%s] @ %s", make, type, model, revision, uuid, url);
    }
}
