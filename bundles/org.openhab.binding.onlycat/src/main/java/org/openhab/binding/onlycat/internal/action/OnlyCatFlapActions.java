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
package org.openhab.binding.onlycat.internal.action;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.onlycat.internal.handler.OnlyCatFlapHandler;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.ActionOutput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * The rule actions of a flap.
 *
 * @author Marcel van Bergen - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = OnlyCatFlapActions.class)
@ThingActionsScope(name = "onlycat")
public class OnlyCatFlapActions implements ThingActions {

    private @Nullable OnlyCatFlapHandler handler;

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        this.handler = handler instanceof OnlyCatFlapHandler flap ? flap : null;
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @RuleAction(label = "@text/action.unlock.label", description = "@text/action.unlock.description")
    public void unlock() {
        OnlyCatFlapHandler flap = handler;
        if (flap != null) {
            flap.unlock();
        }
    }

    @RuleAction(label = "@text/action.reboot.label", description = "@text/action.reboot.description")
    public void reboot() {
        OnlyCatFlapHandler flap = handler;
        if (flap != null) {
            flap.reboot();
        }
    }

    @RuleAction(label = "@text/action.activate-door-policy.label", //
            description = "@text/action.activate-door-policy.description")
    public @ActionOutput(type = "java.lang.Boolean", label = "@text/action.output.success") Boolean activateDoorPolicy(
            @ActionInput(name = "policy", label = "@text/action.input.policy") String policy) {
        OnlyCatFlapHandler flap = handler;
        return flap != null && flap.activateDoorPolicy(policy);
    }

    @RuleAction(label = "@text/action.get-door-policies.label", //
            description = "@text/action.get-door-policies.description")
    public @ActionOutput(type = "java.lang.String", label = "@text/action.output.policies") String getDoorPolicies() {
        OnlyCatFlapHandler flap = handler;
        return flap == null ? "[]" : flap.getDoorPolicies();
    }

    @RuleAction(label = "@text/action.update-door-policy.label", //
            description = "@text/action.update-door-policy.description")
    public @ActionOutput(type = "java.lang.Boolean", label = "@text/action.output.success") Boolean updateDoorPolicy(
            @ActionInput(name = "policyJson", label = "@text/action.input.policy-json") String policyJson) {
        OnlyCatFlapHandler flap = handler;
        return flap != null && flap.updateDoorPolicy(policyJson);
    }

    public static void unlock(@Nullable ThingActions actions) {
        ((OnlyCatFlapActions) requireActions(actions)).unlock();
    }

    public static void reboot(@Nullable ThingActions actions) {
        ((OnlyCatFlapActions) requireActions(actions)).reboot();
    }

    public static Boolean activateDoorPolicy(@Nullable ThingActions actions, String policy) {
        return ((OnlyCatFlapActions) requireActions(actions)).activateDoorPolicy(policy);
    }

    public static String getDoorPolicies(@Nullable ThingActions actions) {
        return ((OnlyCatFlapActions) requireActions(actions)).getDoorPolicies();
    }

    public static Boolean updateDoorPolicy(@Nullable ThingActions actions, String policyJson) {
        return ((OnlyCatFlapActions) requireActions(actions)).updateDoorPolicy(policyJson);
    }

    private static ThingActions requireActions(@Nullable ThingActions actions) {
        if (actions instanceof OnlyCatFlapActions) {
            return actions;
        }
        throw new IllegalArgumentException("Actions are not of type OnlyCatFlapActions");
    }
}
