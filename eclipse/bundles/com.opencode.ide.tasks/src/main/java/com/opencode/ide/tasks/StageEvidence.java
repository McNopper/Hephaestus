package com.opencode.ide.tasks;

import java.util.List;

/**
 * B-007: the per-stage ACCEPTANCE EVIDENCE MATRIX - the single source every
 * acceptance checkpoint consults (FR-005: the settle check, the merge gate
 * and the reviewer prompt/verdict all derive their expectations from this
 * class; no checkpoint invents its own notion of "produced work").
 *
 * <p>The matrix answers one question per V stage: <b>what counts as evidence
 * that this stage's work happened?</b> A definition-leg run's correct output
 * is docs, ticket-body/AC updates and store records - never a code diff
 * (FR-002); the implementation stage expects code plus its tests (FR-003);
 * the verification leg expects tests and, where used, golden/reference
 * outputs (FR-004). An unstaged ticket keeps the code-diff expectation as
 * the default row (FR-001).</p>
 *
 * <p>Placement (B-007 Q-001): a code-level mapping beside {@link VStages} -
 * it is as stable as the stage set itself and needs no config channel. Retry
 * bookkeeping (Q-003) lives in {@link TaskStore#routeReviewDoubt}; the
 * reviewer prompt quotes {@link #describe(String)} verbatim (Q-004,
 * NFR-CONSIST-001); U-029 pass-throughs never reach this matrix (Q-005:
 * a passed stage produced no run to judge).</p>
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class StageEvidence {

    /** The four evidence regimes the ten V stages map onto (FR-001). */
    public enum Leg {
        /** requirements, system, architecture, design - docs + ticket/store work. */
        DEFINITION,
        /** implementation - code plus tests. */
        IMPLEMENTATION,
        /** the five test-* stages - tests and golden/reference outputs. */
        VERIFICATION,
        /** no stage field - the default row: code-diff expectation. */
        UNSTAGED
    }

    /** Path-like strings inside acceptance criteria (e.g. {@code src/Foo.java}) - the AC-path gate's expected set. */
    private static final java.util.regex.Pattern AC_PATH =
            java.util.regex.Pattern.compile("[\\w/.-]+\\.\\w{1,5}");

    /** Words that look path-shaped to the regex but are prose, never files (B-010). */
    private static final java.util.Set<String> PROSE_ABBREVIATIONS = java.util.Set.of(
            "e.g.", "i.e.", "etc.", "vs.", "cf.", "e.g", "i.e", "etc", "vs", "cf");

    private StageEvidence() {
    }

    /** The evidence regime of a stage ({@code null}/unknown stages read as unstaged). */
    public static Leg legOf(String stage) {
        if (stage == null) {
            return Leg.UNSTAGED;
        }
        return switch (stage) {
            case "requirements", "system", "architecture", "design" -> Leg.DEFINITION;
            case "implementation" -> Leg.IMPLEMENTATION;
            case "test-implementation", "test-design", "test-architecture", "test-system",
                    "test-requirements" -> Leg.VERIFICATION;
            default -> Leg.UNSTAGED;
        };
    }

    /**
     * The matrix row as one plain line for the reviewer prompt
     * (NFR-CONSIST-001: the judging model sees the same contract the gates
     * enforce). Deterministic - stable for tests.
     */
    public static String describe(String stage) {
        return switch (legOf(stage)) {
            case DEFINITION -> "definition-leg run (stage " + stage + "): valid evidence is a ticket"
                    + " body/acceptance-criteria update, recorded doc/path/url artifacts, and"
                    + " doc/store paths in the diff (the stage's named artifact paths, docs/**,"
                    + " .opencode/tasks/**). Code changes are permitted but never required.";
            case IMPLEMENTATION -> "implementation run: code plus tests are expected - at least one"
                    + " acceptance-criterion-named path in the diff, with test changes alongside.";
            case VERIFICATION -> "verification-leg run (stage " + stage + "): verification artifacts"
                    + " are expected - tests and, where the stage uses them, golden/reference"
                    + " outputs. An implementation-only change is suspect, not accepted.";
            case UNSTAGED -> "unstaged ticket: code-diff evidence is expected - at least one"
                    + " acceptance-criterion-named path in the diff.";
        };
    }

    /**
     * Path-like strings named by a ticket's acceptance criteria, first-seen
     * order, deduplicated (B-010: prose abbreviations like {@code e.g.} are
     * never paths).
     */
    public static List<String> acPathsOf(List<String> acceptanceCriteria) {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        if (acceptanceCriteria != null) {
            for (String criterion : acceptanceCriteria) {
                if (criterion == null) {
                    continue;
                }
                java.util.regex.Matcher m = AC_PATH.matcher(criterion);
                while (m.find()) {
                    String candidate = m.group();
                    if (candidate.endsWith(".")
                            || PROSE_ABBREVIATIONS.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                        continue;
                    }
                    paths.add(candidate);
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Whether a changed file satisfies an acceptance-criterion-named path:
     * exact, or path-segment suffix (an AC naming {@code Foo.java} is
     * satisfied by {@code src/Foo.java}).
     */
    public static boolean matchesAcPath(String file, List<String> acPaths) {
        if (file == null || acPaths == null) {
            return false;
        }
        String normalized = file.replace('\\', '/');
        return acPaths.stream()
                .anyMatch(path -> normalized.equals(path) || normalized.endsWith("/" + path));
    }

    /**
     * Definition-leg output class (FR-002c): the ticket store and docs are
     * the stage's own deliverable territory - {@code .opencode/tasks/**},
     * {@code docs/**}, and Markdown anywhere (ticket bodies, requirements
     * and architecture docs, skills, agent prompts).
     */
    public static boolean isStoreOrDocPath(String file) {
        if (file == null) {
            return false;
        }
        String normalized = file.replace('\\', '/');
        return normalized.startsWith(".opencode/tasks/")
                || normalized.startsWith("docs/")
                || normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
    }

    /**
     * Verification-leg output class (FR-004): test sources, check scripts
     * and golden/reference fixtures, wherever the repo keeps them.
     */
    public static boolean isVerificationPath(String file) {
        if (file == null) {
            return false;
        }
        String normalized = file.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.contains("test") || name.contains("check") || name.contains("spec")
                || name.contains("golden")) {
            return true;
        }
        for (String segment : normalized.split("/")) {
            if (segment.contains("test") || segment.equals("golden") || segment.equals("goldens")
                    || segment.equals("testdata") || segment.equals("fixtures")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The MERGE GATE decision (FR-001..005, FR-007): given the ticket's
     * stage, its acceptance-criterion paths and the branch's changed files,
     * accept ({@code null}) or refuse with the stage-appropriate reason.
     *
     * <p>Rows: the definition leg accepts any produced change - doc/store/AC
     * paths are its expected output and code is permitted (never required),
     * so this gate never fires there and quality is judged at review
     * (FR-002/007). Implementation and unstaged tickets keep the exact
     * today rule: when the criteria name paths, one must appear (FR-003).
     * The verification leg expects test/golden-shaped paths (FR-004). An
     * EMPTY diff never fires here - the settle check owns that case.</p>
     *
     * @return {@code null} to accept, else the refusal message
     */
    public static String mergeGateVerdict(String stage, List<String> acPaths, List<String> changed) {
        if (changed == null || changed.isEmpty()) {
            return null;
        }
        return switch (legOf(stage)) {
            case DEFINITION -> null;
            case IMPLEMENTATION, UNSTAGED -> {
                List<String> expected = acPaths == null ? List.of() : acPaths;
                if (expected.isEmpty()) {
                    yield null;
                }
                yield changed.stream().anyMatch(file -> matchesAcPath(file, expected))
                        ? null
                        : "analysis-only run: no acceptance-criterion path in the diff (expected one of: "
                                + String.join(", ", expected) + ", got: " + String.join(", ", changed) + ")";
            }
            case VERIFICATION -> changed.stream().anyMatch(StageEvidence::isVerificationPath)
                    ? null
                    : "verification-stage run changed no tests/goldens (expected verification"
                            + " artifacts, got: " + String.join(", ", changed) + ")";
        };
    }

    /**
     * Whether a merge failure detail is the SETTLE CHECK's heuristic refusal
     * ("worker produced no changes", see
     * {@code com.opencode.ide.git.internal.GitWorktreeManager}) rather than a
     * real merge failure - the distinction FR-011 needs to route heuristic
     * refusals into the originator-retry path instead of blocking.
     */
    public static boolean isSettleRefusal(String detail) {
        return detail != null && detail.startsWith("worker produced no changes");
    }
}
