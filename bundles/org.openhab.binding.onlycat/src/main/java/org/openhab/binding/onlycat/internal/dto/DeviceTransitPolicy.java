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
package org.openhab.binding.onlycat.internal.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * A door policy of a flap. The policy itself stays raw JSON, so that editing one never loses a field this binding does
 * not know about.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
public class DeviceTransitPolicy {
    public @Nullable Long deviceTransitPolicyId;
    public @Nullable String deviceId;
    public @Nullable String name;
    public @Nullable JsonObject transitPolicy;

    /**
     * @return the name to show for this policy
     */
    public String getDisplayName() {
        String policyName = name;
        return policyName == null || policyName.isBlank() ? "Policy " + deviceTransitPolicyId : policyName;
    }
}
