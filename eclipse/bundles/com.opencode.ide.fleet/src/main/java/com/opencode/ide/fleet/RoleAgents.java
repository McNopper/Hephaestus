package com.opencode.ide.fleet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a ticket {@code role} to the opencode agent name that should execute
 * it, mirroring the dispatch hints in {@code .opencode/docs/domains.md}.
 * Unknown roles (and {@code null}) map to {@code null}, meaning the server's
 * default agent.
 *
 * <p>U-021 adds the {@code reviewer} role: the autonomous-acceptance pass
 * resolves its review session through this table like any stage role, so an
 * operator can retarget or remove it with a constructor override (the
 * cross-vendor principle prefers a different model family than the
 * executors).</p>
 *
 * <p>Overridable by constructor: override entries win over the defaults, an
 * override with a {@code null} value removes the mapping (server default),
 * and new keys add mappings. Pure Java, no Eclipse/OSGi.</p>
 */
public final class RoleAgents {

    private static final Map<String, String> DEFAULTS = Map.of(
            "developer", "executor",
            "tester", "executor",
            "architect", "manifest-author",
            "pm", "project-manager",
            "reviewer", "reviewer",
            "cpp-engineer", "cpp-tools",
            "graphics-engineer", "graphics-expert");

    private final Map<String, String> agents;

    /** The default dispatch table. */
    public RoleAgents() {
        this(Map.of());
    }

    /**
     * @param overrides role -&gt; agent entries layered over the defaults; a
     *                  {@code null} value removes the role's mapping (server
     *                  default)
     */
    public RoleAgents(Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULTS);
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (e.getValue() == null) {
                merged.remove(e.getKey());
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        this.agents = Map.copyOf(merged);
    }

    /** The default dispatch table (unmodifiable). */
    public static Map<String, String> defaults() {
        return DEFAULTS;
    }

    /**
     * @return the agent name for the role, or {@code null} for unknown/null
     *         roles (= the server default agent)
     */
    public String agentFor(String role) {
        return role == null ? null : agents.get(role);
    }
}
