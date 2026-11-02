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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.onlycat.internal.dto.Device;
import org.openhab.binding.onlycat.internal.dto.DeviceEvent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link OnlyCatGateway}.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
class OnlyCatGatewayTest {

    private static OnlyCatGateway answering(String json) {
        JsonElement reply = JsonParser.parseString(json);
        return new OnlyCatGateway((event, data) -> CompletableFuture.completedFuture(reply));
    }

    @Test
    void readsDevices() throws OnlyCatApiException {
        List<Device> devices = answering("[{\"deviceId\":\"OC-1\"},{\"deviceId\":\"OM-2\"}]").getDevices();
        assertEquals(2, devices.size());
        assertEquals("OM-2", devices.get(1).deviceId);
    }

    @Test
    void reportsRejectedKey() {
        OnlyCatGateway gateway = answering("{\"code\":401,\"message\":\"Unauthorized\"}");
        assertThrows(OnlyCatAuthenticationException.class, gateway::getDevices);
    }

    @Test
    void reportsOtherFailures() {
        OnlyCatGateway gateway = answering("{\"code\":500,\"message\":\"Boom\"}");
        OnlyCatApiException exception = assertThrows(OnlyCatApiException.class,
                () -> gateway.getEvent("OC-1", 1, false));
        assertFalse(exception instanceof OnlyCatAuthenticationException);
        assertTrue(String.valueOf(exception.getMessage()).contains("Boom"));
    }

    @Test
    void reportsFailedConnection() {
        OnlyCatGateway gateway = new OnlyCatGateway((event, data) -> {
            CompletableFuture<JsonElement> future = new CompletableFuture<>();
            future.completeExceptionally(new OnlyCatApiException("gone"));
            return future;
        });
        assertThrows(OnlyCatApiException.class, gateway::getDevices);
    }

    @Test
    void keepsManualTriggerSource() throws OnlyCatApiException {
        OnlyCatGateway gateway = answering("{\"eventId\":5,\"eventTriggerSource\":0,\"eventClassification\":0}");
        DeviceEvent event = gateway.getEvent("OC-1", 5, false);
        assertNotNull(event);
        assertEquals(0, event.eventTriggerSource);
        assertEquals(0, event.eventClassification);
    }

    @Test
    void sendsSubscriptionRequest() throws OnlyCatApiException {
        JsonObject[] sent = new JsonObject[1];
        new OnlyCatGateway((event, data) -> {
            sent[0] = data;
            return CompletableFuture.completedFuture(JsonParser.parseString("[]"));
        }).getDeviceEvents("OC-1");
        assertEquals("OC-1", sent[0].get("deviceId").getAsString());
        assertTrue(sent[0].get("subscribe").getAsBoolean());
    }
}
