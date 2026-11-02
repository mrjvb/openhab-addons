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
package org.openhab.binding.porscheconnect.internal.api;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A remote command was not available, rejected or not confirmed by the vehicle.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheRemoteServiceException extends PorscheApiException {

    private static final long serialVersionUID = 1L;

    public PorscheRemoteServiceException(String message) {
        super(message);
    }
}
