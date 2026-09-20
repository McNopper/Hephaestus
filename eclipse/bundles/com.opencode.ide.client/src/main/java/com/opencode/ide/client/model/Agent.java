package com.opencode.ide.client.model;

import java.util.List;

/**
 * An agent definition as returned by {@code GET /api/agent}.
 *
 * <p>Modelled against opencode v2 ({@code Agent.Info}), verified against a live
 * 2.0.10 server. v2 reshaped this record substantially: the agent gained an
 * {@code id} distinct from its display {@code name}, the prompt moved to
 * {@code system}, {@code maxSteps} became {@code steps}, and per-request model
 * knobs (temperature, top-p, tool toggles) moved into {@code request}, which
 * the IDE does not need.</p>
 *
 * <p>{@code permissions} (plural) is now a list of {@code {action, resource,
 * effect}} rules — v1 had {@code permission} with {@code {permission, pattern,
 * action}}.</p>
 */
public record Agent(
        String id,
        String name,
        String description,
        String mode,
        Boolean hidden,
        List<PermissionRule> permissions,
        Integer steps,
        String color,
        ModelRef model,
        String system) {

    public static final String MODE_PRIMARY = "primary";
    public static final String MODE_SUBAGENT = "subagent";
    public static final String MODE_ALL = "all";

    /**
     * @return false — v2 dropped the built-in/user-defined distinction that v1
     *         exposed as {@code native}. Kept so callers that rendered a
     *         "native" marker still compile; the marker is no longer meaningful
     *         and should be retired from the UI.
     */
    public boolean isNative() {
        return false;
    }

    /** @return true when the server hides this agent from user-facing pickers. */
    public boolean isHidden() {
        return hidden != null && hidden.booleanValue();
    }

    /** Convenience: true when this agent is user-facing (primary or all). */
    public boolean isPrimary() {
        return MODE_PRIMARY.equals(mode()) || MODE_ALL.equals(mode());
    }

    /**
     * One permission rule. {@code action} is the tool/category (e.g.
     * {@code "edit"}, {@code "bash"}, {@code "external_directory"}, {@code "*"}),
     * {@code resource} is a glob, {@code effect} is {@code allow}/{@code ask}/{@code deny}.
     */
    public record PermissionRule(String action, String resource, String effect) {
    }

    /**
     * A model reference ({@code Model.Ref}). v2 names the model {@code id}
     * (v1 used {@code modelID}); {@code variant} is opencode's reasoning-effort
     * selector (e.g. {@code high}, {@code thinking}), {@code null} = model default.
     */
    public record ModelRef(String id, String providerID, String variant) {
    }
}
