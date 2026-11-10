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
package org.openhab.binding.icloud.internal.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AuthResponseParser}.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class AuthResponseParserTest {

    private static final String BOOT_ARGS_HTML = """
            <html><head>
            <script type="text/javascript">var x = 1;</script>
            <script type="application/json" class="boot_args">
            {"direct": {"authInitialRoute": "auth/verify/phone", "hasTrustedDevices": true,
              "twoSV": {"authFactors": ["sms", "voice", 3], "sourceAppId": 1159,
                "bridgeInitiateData": {"phoneNumberVerification": {
                  "trustedPhoneNumber": {"id": 1, "pushMode": "sms", "numberWithDialCode": "+31 •• •••• ••12"},
                  "trustedPhoneNumbers": [{"id": 1, "pushMode": "sms"}]}}}}}
            </script></head><body></body></html>
            """;

    @Test
    public void parsesHtmlBootArgs() {
        Map<String, Object> options = AuthResponseParser.parseMfaAuthOptions(BOOT_ARGS_HTML);
        assertNotNull(options);
        assertEquals("auth/verify/phone", options.get("authInitialRoute"));
        assertEquals(Boolean.TRUE, options.get("hasTrustedDevices"));
        assertEquals(List.of("sms", "voice"), options.get("authFactors"));
        assertEquals("1159", options.get("sourceAppId"));

        Map<?, ?> phoneNumberVerification = assertInstanceOf(Map.class, options.get("phoneNumberVerification"));
        assertTrue(phoneNumberVerification.containsKey("trustedPhoneNumbers"));
        Map<?, ?> trustedPhoneNumber = assertInstanceOf(Map.class, options.get("trustedPhoneNumber"));
        assertEquals("sms", trustedPhoneNumber.get("pushMode"));
    }

    @Test
    public void passesThroughPlainJson() {
        Map<String, Object> options = AuthResponseParser
                .parseMfaAuthOptions("{\"phoneNumberVerification\": {\"trustedPhoneNumber\": {\"id\": 2}}}");
        assertNotNull(options);
        assertTrue(options.containsKey("phoneNumberVerification"));
    }

    @Test
    public void returnsNullWithoutOptions() {
        assertNull(AuthResponseParser.parseMfaAuthOptions(null));
        assertNull(AuthResponseParser.parseMfaAuthOptions(""));
        assertNull(AuthResponseParser.parseMfaAuthOptions("<html><script>var a;</script></html>"));
        assertNull(AuthResponseParser.parseMfaAuthOptions("<script class=\"boot_args\">not json</script>"));
        assertNull(AuthResponseParser.parseMfaAuthOptions("<script class=\"no_boot_args\">{}</script>"));
    }

    @Test
    public void acceptsConflictOnlyWithValidSecurityCode() {
        assertTrue(AuthResponseParser.isAcceptedSecurityCodeConflict(409, "{\"securityCode\": {\"valid\": true}}"));
        assertFalse(AuthResponseParser.isAcceptedSecurityCodeConflict(409, "{\"securityCode\": {\"valid\": false}}"));
        assertFalse(AuthResponseParser.isAcceptedSecurityCodeConflict(409, "{\"service_errors\": []}"));
        assertFalse(AuthResponseParser.isAcceptedSecurityCodeConflict(409, "<html></html>"));
        assertFalse(AuthResponseParser.isAcceptedSecurityCodeConflict(409, ""));
        assertFalse(AuthResponseParser.isAcceptedSecurityCodeConflict(412, "{\"securityCode\": {\"valid\": true}}"));
    }
}
