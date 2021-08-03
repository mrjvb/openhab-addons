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
package org.openhab.binding.zmote.internal.service;

/**
 * @author Alexander Maret-Huskinson - Initial contribution
 * @author Marcel van Bergen - Update to OH3
 */
public interface IZMoteClient {

    /**
     * Checks if the device is online and if we are talking to the correct device.
     */
    void check(int timeout);

    /**
     * @return the URL used by this client.
     */
    String getUrl();

    /**
     * Sends the given IR code to the device.
     *
     * @param code The IR code to send.
     * @param timeout The timeout we wait for a response.
     */
    void sendir(String code, int timeout);
}
