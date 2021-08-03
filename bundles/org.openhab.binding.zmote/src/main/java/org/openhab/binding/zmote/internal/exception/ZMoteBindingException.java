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
package org.openhab.binding.zmote.internal.exception;

/**
 * The base exception for all ZMote related errors.
 *
 * @author Alexander Maret-Huskinson - Initial contribution
 * @author Marcel van Bergen - Update to OH3
 */
public class ZMoteBindingException extends RuntimeException {

    private static final long serialVersionUID = -436906833815967484L;

    public ZMoteBindingException() {
        super();
    }

    public ZMoteBindingException(final String message) {
        super(message);
    }

    public ZMoteBindingException(final Throwable cause) {
        super(cause);
    }

    public ZMoteBindingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
