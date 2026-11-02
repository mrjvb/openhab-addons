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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A single text frame of the Engine.IO 4 / Socket.IO 5 wire protocol.
 *
 * Only what a client of the default namespace needs is modelled. Binary packets are recognised so that they can be
 * skipped, but their attachments are not decoded because the OnlyCat gateway has no use for them.
 *
 * @param kind the packet kind
 * @param ackId the acknowledgement id, if the packet carries one
 * @param payload the JSON payload, if the packet carries one
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public record SocketIoFrame(Kind kind, @Nullable Integer ackId, @Nullable String payload) {

    /**
     * The packet kinds a client can receive.
     */
    public enum Kind {
        ENGINE_OPEN,
        ENGINE_CLOSE,
        ENGINE_PING,
        ENGINE_PONG,
        ENGINE_NOOP,
        CONNECT,
        DISCONNECT,
        EVENT,
        ACK,
        CONNECT_ERROR,
        UNSUPPORTED
    }

    private static final char ENGINE_MESSAGE = '4';

    /**
     * Parses a text frame.
     *
     * @param text the frame as received from the WebSocket
     * @return the frame, of kind {@link Kind#UNSUPPORTED} if it is not understood
     */
    public static SocketIoFrame parse(String text) {
        if (text.isEmpty()) {
            return new SocketIoFrame(Kind.UNSUPPORTED, null, null);
        }
        String rest = text.substring(1);
        return switch (text.charAt(0)) {
            case '0' -> new SocketIoFrame(Kind.ENGINE_OPEN, null, rest);
            case '1' -> new SocketIoFrame(Kind.ENGINE_CLOSE, null, null);
            case '2' -> new SocketIoFrame(Kind.ENGINE_PING, null, null);
            case '3' -> new SocketIoFrame(Kind.ENGINE_PONG, null, null);
            case ENGINE_MESSAGE -> parseSocketPacket(rest);
            case '6' -> new SocketIoFrame(Kind.ENGINE_NOOP, null, null);
            default -> new SocketIoFrame(Kind.UNSUPPORTED, null, null);
        };
    }

    private static SocketIoFrame parseSocketPacket(String packet) {
        if (packet.isEmpty()) {
            return new SocketIoFrame(Kind.UNSUPPORTED, null, null);
        }
        Kind kind = switch (packet.charAt(0)) {
            case '0' -> Kind.CONNECT;
            case '1' -> Kind.DISCONNECT;
            case '2' -> Kind.EVENT;
            case '3' -> Kind.ACK;
            case '4' -> Kind.CONNECT_ERROR;
            default -> Kind.UNSUPPORTED;
        };
        int position = 1;
        if (position < packet.length() && packet.charAt(position) == '/') {
            int comma = packet.indexOf(',', position);
            if (comma < 0) {
                return new SocketIoFrame(kind, null, null);
            }
            position = comma + 1;
        }
        int digitsEnd = position;
        while (digitsEnd < packet.length() && Character.isDigit(packet.charAt(digitsEnd))) {
            digitsEnd++;
        }
        Integer ackId = null;
        if (digitsEnd > position) {
            try {
                ackId = Integer.valueOf(packet.substring(position, digitsEnd));
            } catch (NumberFormatException e) {
                return new SocketIoFrame(Kind.UNSUPPORTED, null, null);
            }
        }
        String payload = digitsEnd < packet.length() ? packet.substring(digitsEnd) : null;
        return new SocketIoFrame(kind, ackId, payload);
    }

    /**
     * @return the frame that opens the default namespace, carrying the given JSON authentication object
     */
    public static String connect(String authJson) {
        return "40" + authJson;
    }

    /**
     * @return the frame that emits an event, expecting an acknowledgement with the given id
     */
    public static String event(int ackId, String argumentsJson) {
        return "42" + ackId + argumentsJson;
    }

    /**
     * @return the frame that acknowledges an event received from the server
     */
    public static String ack(int ackId) {
        return "43" + ackId + "[]";
    }

    /**
     * @return the frame that answers a ping of the server
     */
    public static String pong() {
        return "3";
    }
}
