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

import static org.openhab.binding.porscheconnect.internal.api.PorscheApiConstants.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.openhab.binding.porscheconnect.internal.api.PorscheCaptchaRequiredException.PendingLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Porsche ID (Auth0) login: authorization code flow with PKCE using the "identifier first" universal login screens,
 * including captcha and passkey-enrollment handling based on pyporscheconnectapi/oauth2.py.
 * 
 * The {@link HttpClient} passed in must not follow redirects on its own for these requests (every request here
 * disables it explicitly) and should be dedicated to one account, because the Auth0 session lives in its cookie
 * store.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheAuth {

    private static final int MAX_RESUME_REDIRECTS = 10;
    private static final long DELAY_AFTER_PASSWORD_MS = 2500;
    private static final String ACUL_SDK = "@auth0/auth0-acul-js@1.2.0";

    private static final Pattern ATOB_PATTERN = Pattern.compile("atob\\(\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_CAPTCHA_PATTERN = Pattern.compile("\\balt\\s*=\\s*\"captcha\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_PATTERN = Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_DATA_PATTERN = Pattern.compile("(data:image/svg[^\\s\"']+)");

    private final Logger logger = LoggerFactory.getLogger(PorscheAuth.class);
    private final SecureRandom random = new SecureRandom();

    private final HttpClient httpClient;
    private final String email;
    private final String password;

    public PorscheAuth(HttpClient httpClient, String email, String password) {
        this.httpClient = httpClient;
        this.email = email;
        this.password = password;
    }

    /**
     * Runs the complete login and returns a fresh token.
     *
     * @param captchaCode answer to a captcha that was presented earlier, or {@code null}
     * @param pendingLogin the state belonging to that captcha, or {@code null}
     * @throws PorscheCaptchaRequiredException when the identity server asks for a captcha
     * @throws PorscheAuthException when the credentials are rejected
     */
    public PorscheToken login(@Nullable String captchaCode, @Nullable PendingLogin pendingLogin)
            throws PorscheApiException, InterruptedException {
        String authorizationCode;
        String codeVerifier;

        if (captchaCode != null && !captchaCode.isBlank() && pendingLogin != null) {
            logger.debug("Resuming Porsche ID login with captcha answer");
            codeVerifier = pendingLogin.codeVerifier();
            String resumePath = loginWithIdentifier(pendingLogin.state(), codeVerifier, captchaCode.trim());
            authorizationCode = resumeAuthorizationCodeFlow(resolve(AUTH_BASE_URL + "/", resumePath));
        } else {
            codeVerifier = generatePkceVerifier();
            authorizationCode = fetchAuthorizationCode(codeVerifier);
        }
        return fetchAccessToken(authorizationCode, codeVerifier);
    }

    /**
     * Exchanges the refresh token for a new access token.
     *
     * @return the new token, or {@link PorscheToken#EMPTY} if the refresh token was rejected and a full login is
     *         required
     */
    public PorscheToken refresh(PorscheToken token) throws PorscheApiException, InterruptedException {
        String refreshToken = token.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return PorscheToken.EMPTY;
        }
        Fields form = new Fields();
        form.put("client_id", CLIENT_ID);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);

        logger.debug("Refreshing Porsche Connect access token");
        ContentResponse response = send(newRequest(TOKEN_URL, HttpMethod.POST).content(new FormContentProvider(form)));
        int status = response.getStatus();
        if (status == HttpStatus.BAD_REQUEST_400 || status == HttpStatus.UNAUTHORIZED_401
                || status == HttpStatus.FORBIDDEN_403) {
            // refresh token expired or revoked: caller has to log in again
            logger.debug("Refresh token rejected ({}), full login required", status);
            return PorscheToken.EMPTY;
        }
        if (status != HttpStatus.OK_200) {
            throw new PorscheApiException(status, "Token refresh failed");
        }
        return parseToken(response.getContentAsString(), refreshToken);
    }

    private String fetchAuthorizationCode(String codeVerifier) throws PorscheApiException, InterruptedException {
        logger.debug("Fetching authorization code");
        Request request = newRequest(AUTHORIZATION_URL, HttpMethod.GET) //
                .param("response_type", "code") //
                .param("client_id", CLIENT_ID) //
                .param("redirect_uri", REDIRECT_URI) //
                .param("audience", AUDIENCE) //
                .param("scope", SCOPE) //
                .param("code_challenge", buildPkceChallenge(codeVerifier)) //
                .param("code_challenge_method", "S256") //
                .param("state", "openhab");
        ContentResponse response = send(request);
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        if (response.getStatus() != HttpStatus.FOUND_302 || location == null) {
            throw new PorscheApiException(response.getStatus(), "Could not fetch authorization code");
        }
        Map<String, String> params = queryParams(location);
        String code = params.get("code");
        if (code != null) {
            // still logged in at Auth0 (session cookie), no credentials needed
            return code;
        }
        String state = params.get("state");
        if (state == null) {
            throw new PorscheApiException("Authorization redirect did not contain a login state");
        }
        logger.debug("No Auth0 session, running identifier first login");
        String resumePath = loginWithIdentifier(state, codeVerifier, null);
        return resumeAuthorizationCodeFlow(resolve(AUTH_BASE_URL + "/", resumePath));
    }

    /**
     * Posts e-mail address (plus captcha answer) and password to the universal login screens.
     *
     * @return the (usually relative) URL to resume the authorization request
     */
    private String loginWithIdentifier(String state, String codeVerifier, @Nullable String captchaCode)
            throws PorscheApiException, InterruptedException {
        Fields form = new Fields();
        form.put("state", state);
        form.put("username", email);
        form.put("js-available", "true");
        form.put("webauthn-available", "false");
        form.put("is-brave", "false");
        form.put("webauthn-platform-available", "false");
        form.put("action", "default");
        if (captchaCode != null) {
            form.put("captcha", captchaCode);
        }
        logger.debug("Submitting e-mail address{}", captchaCode != null ? " and captcha answer" : "");
        ContentResponse response = send(newRequest(LOGIN_IDENTIFIER_URL, HttpMethod.POST).param("state", state)
                .content(new FormContentProvider(form)));

        if (response.getStatus() == HttpStatus.UNAUTHORIZED_401) {
            throw new PorscheAuthException("Wrong credentials");
        }
        if (response.getStatus() == HttpStatus.BAD_REQUEST_400) {
            String captcha = extractCaptchaImage(response.getContentAsString());
            if (captcha == null) {
                throw new PorscheApiException("Captcha required but no captcha image found in login page");
            }
            logger.debug("Porsche ID login requires a captcha");
            throw new PorscheCaptchaRequiredException(captcha, new PendingLogin(state, codeVerifier));
        }

        form = new Fields();
        form.put("state", state);
        form.put("username", email);
        form.put("password", password);
        form.put("action", "default");
        logger.debug("Submitting password");
        response = send(newRequest(LOGIN_PASSWORD_URL, HttpMethod.POST).param("state", state)
                .content(new FormContentProvider(form)));
        if (response.getStatus() == HttpStatus.BAD_REQUEST_400) {
            throw new PorscheAuthException("Wrong credentials");
        }
        String resumeUrl = response.getHeaders().get(HttpHeader.LOCATION);
        if (resumeUrl == null) {
            throw new PorscheApiException(response.getStatus(), "Login did not return a resume location");
        }
        // the identity server needs a moment before the session is usable (same delay as the Python library)
        Thread.sleep(DELAY_AFTER_PASSWORD_MS);
        return resumeUrl;
    }

    /**
     * Follows the Auth0 redirect chain until the redirect to the app callback carries the authorization code.
     */
    private String resumeAuthorizationCodeFlow(String url) throws PorscheApiException, InterruptedException {
        String currentUrl = url;
        for (int i = 0; i < MAX_RESUME_REDIRECTS; i++) {
            String code = queryParams(currentUrl).get("code");
            if (code != null) {
                return code;
            }
            ContentResponse response = send(newRequest(currentUrl, HttpMethod.GET));
            int status = response.getStatus();
            String location = response.getHeaders().get(HttpHeader.LOCATION);
            if (HttpStatus.isRedirection(status) && location != null) {
                currentUrl = resolve(currentUrl, location);
                continue;
            }
            if (status == HttpStatus.OK_200 && pathOf(currentUrl).contains(PASSKEY_ENROLLMENT_PATH)) {
                currentUrl = skipPasskeyEnrollment(currentUrl, response.getContentAsString());
                continue;
            }
            logger.debug("Unexpected response {} at {} while resuming authorization", status, pathOf(currentUrl));
            throw new PorscheApiException(status, "Could not fetch authorization code");
        }
        throw new PorscheApiException("Authorization code redirect loop");
    }

    /** Declines the optional "create a passkey" screen Auth0 may interleave. */
    private String skipPasskeyEnrollment(String url, String html) throws PorscheApiException, InterruptedException {
        JsonObject context = extractUniversalLoginContext(html);
        if (context == null) {
            throw new PorscheApiException("Passkey enrollment page without login context");
        }
        String transactionState = getString(getObject(context, "transaction"), "state");
        if (transactionState == null) {
            throw new PorscheApiException("Passkey enrollment page without transaction state");
        }
        Fields form = new Fields();
        JsonObject submitted = getObject(getObject(context, "untrustedData"), "submittedFormData");
        if (submitted != null) {
            for (Entry<String, JsonElement> entry : submitted.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    form.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        form.put("state", transactionState);
        form.put("action", "abort-passkey-enrollment");
        form.put("acul-sdk", ACUL_SDK);

        logger.debug("Declining passkey enrollment");
        ContentResponse response = send(newRequest(url, HttpMethod.POST).content(new FormContentProvider(form)));
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        if ((response.getStatus() != HttpStatus.FOUND_302 && response.getStatus() != HttpStatus.SEE_OTHER_303)
                || location == null) {
            throw new PorscheApiException(response.getStatus(), "Skipping passkey enrollment failed");
        }
        return resolve(url, location);
    }

    private PorscheToken fetchAccessToken(String authorizationCode, String codeVerifier)
            throws PorscheApiException, InterruptedException {
        Fields form = new Fields();
        form.put("client_id", CLIENT_ID);
        form.put("grant_type", "authorization_code");
        form.put("code", authorizationCode);
        form.put("redirect_uri", REDIRECT_URI);
        form.put("code_verifier", codeVerifier);

        logger.debug("Exchanging authorization code for an access token");
        ContentResponse response = send(newRequest(TOKEN_URL, HttpMethod.POST).content(new FormContentProvider(form)));
        if (response.getStatus() != HttpStatus.OK_200) {
            throw new PorscheApiException(response.getStatus(), "Token request failed");
        }
        return parseToken(response.getContentAsString(), null);
    }

    private PorscheToken parseToken(String json, @Nullable String previousRefreshToken) throws PorscheApiException {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String accessToken = getString(obj, "access_token");
            if (accessToken == null) {
                throw new PorscheApiException("Token response without access_token");
            }
            String refreshToken = getString(obj, "refresh_token");
            long expiresIn = obj.has("expires_in") ? obj.get("expires_in").getAsLong() : 0;
            return new PorscheToken(accessToken, refreshToken != null ? refreshToken : previousRefreshToken,
                    Instant.now().getEpochSecond() + expiresIn);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new PorscheApiException("Invalid token response", e);
        }
    }

    /** Captcha from the Auth0 ACUL context, a legacy {@code <img alt="captcha">} or an inline SVG data URI. */
    static @Nullable String extractCaptchaImage(String html) {
        JsonObject context = extractUniversalLoginContext(html);
        if (context != null) {
            String image = getString(getObject(getObject(context, "screen"), "captcha"), "image");
            if (image != null) {
                return image;
            }
        }
        Matcher imgTags = IMG_TAG_PATTERN.matcher(html);
        while (imgTags.find()) {
            String tag = imgTags.group();
            if (ALT_CAPTCHA_PATTERN.matcher(tag).find()) {
                Matcher src = SRC_PATTERN.matcher(tag);
                if (src.find()) {
                    return src.group(1).replace("&amp;", "&");
                }
            }
        }
        Matcher svg = SVG_DATA_PATTERN.matcher(html);
        return svg.find() ? svg.group(1) : null;
    }

    static @Nullable JsonObject extractUniversalLoginContext(String html) {
        Matcher matcher = ATOB_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(decoded);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IllegalArgumentException | JsonParseException e) {
            return null;
        }
    }

    private String generatePkceVerifier() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String buildPkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Request newRequest(String url, HttpMethod method) {
        return httpClient.newRequest(url).method(method).followRedirects(false).agent(USER_AGENT)
                .header("X-Client-ID", X_CLIENT_ID).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private ContentResponse send(Request request) throws PorscheApiException, InterruptedException {
        try {
            return request.send();
        } catch (TimeoutException | ExecutionException e) {
            throw new PorscheApiException("Request to " + request.getHost() + " failed: " + e.getMessage(), e);
        }
    }

    static Map<String, String> queryParams(String url) {
        Map<String, String> result = new HashMap<>();
        String query;
        try {
            query = new URI(url).getRawQuery();
        } catch (URISyntaxException e) {
            int idx = url.indexOf('?');
            query = idx >= 0 ? url.substring(idx + 1) : null;
        }
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = URLDecoder.decode(idx >= 0 ? pair.substring(0, idx) : pair, StandardCharsets.UTF_8);
            String value = idx >= 0 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";
            result.putIfAbsent(key, value);
        }
        return result;
    }

    static String resolve(String base, String location) throws PorscheApiException {
        try {
            return new URI(base).resolve(location).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new PorscheApiException("Invalid redirect location", e);
        }
    }

    private static String pathOf(String url) {
        try {
            String path = new URI(url).getPath();
            return path != null ? path : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }

    static @Nullable JsonObject getObject(@Nullable JsonObject parent, String member) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static @Nullable String getString(@Nullable JsonObject parent, String member) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
