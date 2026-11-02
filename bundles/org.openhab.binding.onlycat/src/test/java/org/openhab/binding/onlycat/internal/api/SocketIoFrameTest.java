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
package org.openhab.binding.onlycat.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.onlycat.internal.api.SocketIoFrame.Kind;

/**
 * Tests {@link SocketIoFrame}.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
class SocketIoFrameTest {

    @Test
    void parsesEngineFrames() {
        SocketIoFrame open = SocketIoFrame.parse("0{\"sid\":\"a\",\"pingInterval\":25000}");
        assertEquals(Kind.ENGINE_OPEN, open.kind());
        assertEquals("{\"sid\":\"a\",\"pingInterval\":25000}", open.payload());
        assertEquals(Kind.ENGINE_PING, SocketIoFrame.parse("2").kind());
        assertEquals(Kind.ENGINE_CLOSE, SocketIoFrame.parse("1").kind());
    }

    @Test
    void parsesEventWithoutAckId() {
        SocketIoFrame frame = SocketIoFrame.parse("42[\"deviceUpdate\",{\"deviceId\":\"OC-1\"}]");
        assertEquals(Kind.EVENT, frame.kind());
        assertNull(frame.ackId());
        assertEquals("[\"deviceUpdate\",{\"deviceId\":\"OC-1\"}]", frame.payload());
    }

    @Test
    void parsesAckWithMultipleDigits() {
        SocketIoFrame frame = SocketIoFrame.parse("43127[{\"code\":401}]");
        assertEquals(Kind.ACK, frame.kind());
        assertEquals(127, frame.ackId());
        assertEquals("[{\"code\":401}]", frame.payload());
    }

    @Test
    void parsesNamespacePrefix() {
        SocketIoFrame frame = SocketIoFrame.parse("42/other,5[\"x\"]");
        assertEquals(Kind.EVENT, frame.kind());
        assertEquals(5, frame.ackId());
        assertEquals("[\"x\"]", frame.payload());
    }

    @Test
    void recognisesConnectAndError() {
        assertEquals(Kind.CONNECT, SocketIoFrame.parse("40{\"sid\":\"x\"}").kind());
        assertEquals(Kind.CONNECT_ERROR, SocketIoFrame.parse("44{\"message\":\"no\"}").kind());
    }

    @Test
    void doesNotChokeOnRubbish() {
        assertEquals(Kind.UNSUPPORTED, SocketIoFrame.parse("").kind());
        assertEquals(Kind.UNSUPPORTED, SocketIoFrame.parse("4").kind());
        assertEquals(Kind.UNSUPPORTED, SocketIoFrame.parse("9x").kind());
        assertEquals(Kind.UNSUPPORTED, SocketIoFrame.parse("451-[\"binary\"]").kind());
    }

    @Test
    void writesFrames() {
        assertEquals("40{\"token\":\"t\"}", SocketIoFrame.connect("{\"token\":\"t\"}"));
        assertEquals("427[\"getDevices\",{}]", SocketIoFrame.event(7, "[\"getDevices\",{}]"));
        assertEquals("437[]", SocketIoFrame.ack(7));
        assertEquals("3", SocketIoFrame.pong());
    }
}
