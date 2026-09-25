package com.opencode.ide.tasks;

import java.util.List;
import java.util.Map;

/**
 * The V-model pipeline ladder: the canonical stage order (definition stages
 * down, verification stages up), the role that owns each stage, and the skill
 * family that works it. The V-model here is an <b>async pipeline</b>, not a
 * phase gate: several stages run concurrently, work advances when the stage's
 * own work is done, and anything that cannot proceed is sent back to the
 * previous stage with a reason.
 *
 * <p>Stages are a stored ticket field ({@code stage:} in the frontmatter);
 * {@code null} means a legacy/untracked ticket (display code may derive a
 * provisional stage from the role via {@link #deriveFromRole(String)}).</p>
 */
public final class VStages {

    /** Canonical order: definition left leg, verification right leg. */
    public static final List<String> STAGES = List.of(
            "requirements",
            "system",
            "architecture",
            "design",
            "implementation",
            "test-implementation",
            "test-design",
            "test-architecture",
            "test-system",
            "test-requirements");

    /** The role that owns each stage (dispatch follows the ticket role). */
    private static final Map<String, String> ROLE = Map.of(
            "requirements", "pm",
            "system", "architect",
            "architecture", "architect",
            "design", "developer",
            "implementation", "developer",
            "test-implementation", "tester",
            "test-design", "tester",
            "test-architecture", "tester",
            "test-system", "tester",
            "test-requirements", "tester");

    /** The skill family that works each stage (skill name = family + descriptor). */
    private static final Map<String, String> SKILL = Map.of(
            "requirements", "software-requirements",
            "system", "software-system",
            "architecture", "software-architecture",
            "design", "software-design",
            "implementation", "software-implementation",
            "test-implementation", "test-software-implementation",
            "test-design", "test-software-design",
            "test-architecture", "test-software-architecture",
            "test-system", "test-software-system",
            "test-requirements", "test-software-requirements");

    private VStages() {
    }

    /** @return true when {@code stage} is one of the canonical stages. */
    public static boolean isValid(String stage) {
        return stage != null && STAGES.contains(stage);
    }

    /** @return the owning role of a stage, or {@code null} for unknown stages. */
    public static String roleOf(String stage) {
        return stage == null ? null : ROLE.get(stage);
    }

    /** @return the skill family working a stage, or {@code null} for unknown stages. */
    public static String skillOf(String stage) {
        return stage == null ? null : SKILL.get(stage);
    }

    /** @return true when the stage is on the verification (right) leg of the V. */
    public static boolean isVerification(String stage) {
        return stage != null && STAGES.indexOf(stage) >= STAGES.indexOf("test-implementation");
    }

    /**
     * The stage's V-LEVEL PAIR: the verification leg of a definition stage and
     * vice versa ({@code design} &harr; {@code test-design}, ...). U-029/U-031:
     * the horizontal resolution route reports a blocked ticket to this stage.
     *
     * @return the pair stage, or {@code null} when the stage is unknown
     */
    public static String pairOf(String stage) {
        if (!isValid(stage)) {
            return null;
        }
        return isVerification(stage) ? stage.substring("test-".length()) : "test-" + stage;
    }

    /** @return the next stage down/up the V, or {@code null} at either end. */
    public static String next(String stage) {
        if (!isValid(stage)) {
            return null;
        }
        int i = STAGES.indexOf(stage);
        return i + 1 < STAGES.size() ? STAGES.get(i + 1) : null;
    }

    /** @return the previous stage, or {@code null} at the start (requirements). */
    public static String previous(String stage) {
        if (!isValid(stage)) {
            return null;
        }
        int i = STAGES.indexOf(stage);
        return i > 0 ? STAGES.get(i - 1) : null;
    }

    /** @return the last verification stage (the V tip — acceptance). */
    public static String last() {
        return STAGES.get(STAGES.size() - 1);
    }

    /** @return the first definition stage (the V start). */
    public static String first() {
        return STAGES.get(0);
    }

    /**
     * Provisional stage for legacy tickets without a stored stage (display
     * only — never persisted): role-based fallback.
     */
    public static String deriveFromRole(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "pm" -> "requirements";
            case "architect" -> "architecture";
            case "developer" -> "implementation";
            case "tester" -> "test-implementation";
            default -> null;
        };
    }
}
