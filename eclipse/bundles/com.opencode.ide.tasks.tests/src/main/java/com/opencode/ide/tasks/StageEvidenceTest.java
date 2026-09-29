package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * B-007 FR-013: the per-stage acceptance evidence matrix is PINNED - one
 * row per V stage asserting which evidence classes satisfy acceptance and
 * which refuse - plus the FR-008 regression cases (the 2026-09-19 U-026
 * false rejection) and the B-010 prose rule. The matrix is the single
 * source (FR-005) for the settle check, the merge gate and the reviewer
 * prompt, so these rows are the contract all three share.
 */
public class StageEvidenceTest {

    private static final List<String> AC_CODE_PATHS = List.of("src/Foo.java");

    /** FR-013: every V stage maps onto exactly one evidence regime. */
    @Test
    public void everyStageHasExactlyOneLeg() {
        assertEquals(StageEvidence.Leg.DEFINITION, StageEvidence.legOf("requirements"));
        assertEquals(StageEvidence.Leg.DEFINITION, StageEvidence.legOf("system"));
        assertEquals(StageEvidence.Leg.DEFINITION, StageEvidence.legOf("architecture"));
        assertEquals(StageEvidence.Leg.DEFINITION, StageEvidence.legOf("design"));
        assertEquals(StageEvidence.Leg.IMPLEMENTATION, StageEvidence.legOf("implementation"));
        assertEquals(StageEvidence.Leg.VERIFICATION, StageEvidence.legOf("test-implementation"));
        assertEquals(StageEvidence.Leg.VERIFICATION, StageEvidence.legOf("test-design"));
        assertEquals(StageEvidence.Leg.VERIFICATION, StageEvidence.legOf("test-architecture"));
        assertEquals(StageEvidence.Leg.VERIFICATION, StageEvidence.legOf("test-system"));
        assertEquals(StageEvidence.Leg.VERIFICATION, StageEvidence.legOf("test-requirements"));
        assertEquals("the default row (FR-001)", StageEvidence.Leg.UNSTAGED, StageEvidence.legOf(null));
        assertEquals("unknown stages read as unstaged", StageEvidence.Leg.UNSTAGED,
                StageEvidence.legOf("nonsense"));
        for (String stage : VStages.STAGES) {
            assertNotNull("every canonical stage has a leg: " + stage, StageEvidence.legOf(stage));
        }
    }

    /** FR-013: the accepted/refused evidence classes, one row per stage. */
    @Test
    public void everyStageRowAcceptsAndRefusesItsEvidenceClasses() {
        for (String stage : VStages.STAGES) {
            List<String> docAndStore = List.of(
                    ".opencode/tasks/hephaestus/" + stage + "-t1.md", "docs/requirements/feature.md");
            List<String> codeOnly = List.of("eclipse/bundles/x/src/main/Foo.java");
            List<String> testsOnly = List.of("eclipse/bundles/x.tests/src/main/FooTest.java");
            List<String> implOnly = List.of("eclipse/bundles/x/src/main/Bar.java");
            switch (StageEvidence.legOf(stage)) {
                case DEFINITION -> {
                    assertNull(stage + " accepts ticket/store/doc evidence",
                            StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, docAndStore));
                    assertNull(stage + " permits code but never requires it (FR-002)",
                            StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, codeOnly));
                }
                case IMPLEMENTATION -> {
                    assertNull(stage + " accepts an acceptance-criterion path (FR-003)",
                            StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, List.of("src/Foo.java")));
                    assertNull(stage + " accepts code WITH its tests (FR-003: code plus tests)",
                            StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS,
                                    List.of("src/Foo.java", "eclipse/bundles/x.tests/src/main/FooTest.java")));
                    String refused = StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, docAndStore);
                    assertNotNull(stage + " still refuses analysis-only runs", refused);
                    assertTrue(refused, refused.startsWith("analysis-only run:"));
                    assertNotNull(stage + " tests alone are not the implementation's evidence (FR-003)",
                            StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, testsOnly));
                }
                case VERIFICATION -> {
                    assertNull(stage + " accepts verification artifacts (FR-004)",
                            StageEvidence.mergeGateVerdict(stage, List.of(), testsOnly));
                    String refused = StageEvidence.mergeGateVerdict(stage, List.of(), implOnly);
                    assertNotNull(stage + " refuses implementation-only changes (FR-004)", refused);
                    assertTrue(refused, refused.startsWith("verification-stage run changed no tests"));
                }
                default -> {
                }
            }
        }
    }

    /** FR-008 regression (the live U-026/B-005 false rejection) - AC-002. */
    @Test
    public void theU026DefinitionLegCaseIsNeverRefused() {
        List<String> changed = List.of(
                ".opencode/tasks/hephaestus/U-026.md",
                "docs/requirements/_req-u026-v-flow-visibility.md");
        assertNull("ticket + requirements doc is exactly the correct stage-1 output",
                StageEvidence.mergeGateVerdict("requirements", List.of("e.g", "docs/x.md"), changed));
        assertNull("an AC-less definition run is equally accepted",
                StageEvidence.mergeGateVerdict("requirements", List.of(), changed));
    }

    /** FR-001 default row: unstaged tickets keep the code-diff expectation. */
    @Test
    public void unstagedTicketsKeepTheCodeDiffExpectation() {
        assertNull(StageEvidence.mergeGateVerdict(null, AC_CODE_PATHS, List.of("src/Foo.java")));
        String refused = StageEvidence.mergeGateVerdict(null, AC_CODE_PATHS, List.of("README.md"));
        assertNotNull(refused);
        assertTrue(refused, refused.startsWith("analysis-only run:"));
        assertNull("behavioral criteria without paths skip the gate",
                StageEvidence.mergeGateVerdict(null, List.of(), List.of("README.md")));
    }

    /** The empty-diff case belongs to the settle check, never this gate (FR-006). */
    @Test
    public void anEmptyDiffIsNeverThisGatesRefusal() {
        for (String stage : VStages.STAGES) {
            assertNull(stage, StageEvidence.mergeGateVerdict(stage, AC_CODE_PATHS, List.of()));
        }
        assertNull(StageEvidence.mergeGateVerdict(null, AC_CODE_PATHS, List.of()));
    }

    /** AC-path matching: exact or path-segment suffix; prose never (B-010). */
    @Test
    public void acPathExtractionSkipsProseAbbreviations() {
        assertEquals(List.of("docs/x.md"), StageEvidence.acPathsOf(List.of("see e.g. docs/x.md")));
        assertEquals(List.of("src/Foo.java"),
                StageEvidence.acPathsOf(List.of("i.e. src/Foo.java and cf. other")));
        assertEquals("first-seen order, deduplicated",
                List.of("a.txt", "b.txt"), StageEvidence.acPathsOf(List.of("a.txt", "b.txt", "a.txt")));
        assertTrue(StageEvidence.matchesAcPath("src/Foo.java", AC_CODE_PATHS));
        assertTrue("suffix match on a path segment", StageEvidence.matchesAcPath("x/src/Foo.java", AC_CODE_PATHS));
        assertFalse(StageEvidence.matchesAcPath("src/Bar.java", AC_CODE_PATHS));
    }

    /** Evidence path classes (FR-002c, FR-004). */
    @Test
    public void evidencePathClassesAreStable() {
        assertTrue(StageEvidence.isStoreOrDocPath(".opencode/tasks/hephaestus/U-1.md"));
        assertTrue(StageEvidence.isStoreOrDocPath("docs/requirements/x.md"));
        assertTrue(StageEvidence.isStoreOrDocPath("README.md"));
        assertFalse(StageEvidence.isStoreOrDocPath("src/main/Foo.java"));
        assertTrue(StageEvidence.isVerificationPath("eclipse/bundles/x.tests/src/main/FooTest.java"));
        assertTrue(StageEvidence.isVerificationPath("goldens/frame.png"));
        assertFalse(StageEvidence.isVerificationPath("src/main/Foo.java"));
    }

    /** The settle-refusal matcher distinguishes heuristic refusals from real merge failures (FR-011). */
    @Test
    public void settleRefusalMatching() {
        assertTrue(StageEvidence.isSettleRefusal(
                "worker produced no changes (no commits on opencode/t1 and no pending worktree edits)"));
        assertFalse(StageEvidence.isSettleRefusal("merge conflicts: a.txt"));
        assertFalse(StageEvidence.isSettleRefusal(null));
    }

    /** NFR-CONSIST-001: the prompt row exists for every stage and is deterministic. */
    @Test
    public void everyStageDescribesItsRowDeterministically() {
        for (String stage : VStages.STAGES) {
            String row = StageEvidence.describe(stage);
            assertTrue(stage + " has a non-blank matrix row", row != null && !row.isBlank());
            assertEquals(stage, row, StageEvidence.describe(stage));
        }
        assertTrue(StageEvidence.describe(null).contains("unstaged"));
    }
}
