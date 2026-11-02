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
 * The Porsche identity server asks for a captcha before it accepts the login. The user has to solve
 * {@link #getCaptchaImage()} and the login is then resumed with {@link #getPendingLogin()}.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheCaptchaRequiredException extends PorscheApiException {

    private static final long serialVersionUID = 1L;

    private final String captchaImage;
    private final PendingLogin pendingLogin;

    public PorscheCaptchaRequiredException(String captchaImage, PendingLogin pendingLogin) {
        super("Captcha required");
        this.captchaImage = captchaImage;
        this.pendingLogin = pendingLogin;
    }

    /** The captcha, usually as a {@code data:image/svg+xml;base64,...} URI. */
    public String getCaptchaImage() {
        return captchaImage;
    }

    public PendingLogin getPendingLogin() {
        return pendingLogin;
    }

    /**
     * Auth0 transaction state and PKCE verifier that must be reused when the captcha answer is submitted.
     */
    public record PendingLogin(String state, String codeVerifier) {
    }
}
