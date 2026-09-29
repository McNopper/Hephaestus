package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * U-034 auto-deploy (AC: Eclipse-facing merge-backs build + refresh the
 * dropins; red builds never deploy and surface as NEEDS-HUMAN; the user is
 * only told "restart Eclipse" when jars actually landed; non-Eclipse
 * changes never trigger a build).
 */
public class AutoDeployTest {

    private static final String PROJECT = "p";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private String ticketId;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        ticketId = store.create(PROJECT, new TaskStore.CreateSpec(
                "Eclipse work", "d", "task", "developer", "high", 2,
                List.of(), List.of(), null, "D")).id;
    }

    /** Only eclipse/-prefixed paths are Eclipse-facing. */
    @Test
    public void onlyEclipseFacingChangesTriggerTheBuild() {
        assertTrue(AutoDeploy.touchesEclipseCode(List.of("eclipse/bundles/x/src/main/java/A.java")));
        assertTrue("path separators normalize",
                AutoDeploy.touchesEclipseCode(List.of("eclipse\\bundles\\x\\A.java")));
        assertFalse(AutoDeploy.touchesEclipseCode(List.of("README.md", "docs/x.md")));
        assertFalse(AutoDeploy.touchesEclipseCode(List.of()));
        assertFalse(AutoDeploy.touchesEclipseCode(null));
        assertFalse(AutoDeploy.touchesEclipseCode(java.util.Arrays.asList("README.md", null)));
    }

    /** The auto-deploy.ps1 output contract, pinned. */
    @Test
    public void parseFollowsTheScriptContract() {
        assertTrue(AutoDeploy.parse("DEPLOYED 2026-09-29T00:00:00Z", 0).deployed());
        assertFalse(AutoDeploy.parse("DEPLOYED 2026-09-29T00:00:00Z", 0).redBuild());

        AutoDeploy.Result nothing = AutoDeploy.parse("NOTHING no jars", 0);
        assertFalse(nothing.deployed());
        assertFalse(nothing.redBuild());

        AutoDeploy.Result red = AutoDeploy.parse("RED build (exit 1) - nothing deployed; needs a human", 2);
        assertTrue(red.redBuild());
        assertFalse(red.deployed());
        assertTrue("an unparsable non-zero exit is still a red build",
                AutoDeploy.parse("boom", 1).redBuild());

        assertEquals("the last non-blank line is the contract line",
                "DEPLOYED x", AutoDeploy.parse("noise\n\nDEPLOYED x\n\n", 0).line());
    }

    /** A green deploy lands exactly the restart notice on the ticket. */
    @Test
    public void greenDeployLandsTheRestartComment() {
        List<List<String>> calls = new ArrayList<>();
        TaskFleet.MergeObserver observer = AutoDeploy.mergeObserver(store, tmp.getRoot().toPath(),
                Runnable::run, (command, out) -> {
                    calls.add(command);
                    out.accept("DEPLOYED 2026-09-29T08:00:00Z");
                    return 0;
                });

        observer.merged(PROJECT, ticketId, List.of("eclipse/bundles/x/src/main/java/A.java"));

        assertEquals("the build ran once", 1, calls.size());
        assertTrue("the script is invoked", calls.get(0).contains(
                tmp.getRoot().toPath().resolve("eclipse").resolve("auto-deploy.ps1").toString()));
        Task after = store.get(PROJECT, ticketId);
        assertTrue("the user is told 'restart Eclipse' only when jars landed",
                after.comments.stream().anyMatch(c -> AutoDeploy.BY.equals(c.by())
                        && c.text().contains("deploy: jars refreshed - restart Eclipse")));
        assertFalse(after.blocked);
    }

    /** AC: red builds do NOT deploy and surface as NEEDS-HUMAN (blocked). */
    @Test
    public void redBuildSurfacesAsNeedsHuman() {
        TaskFleet.MergeObserver observer = AutoDeploy.mergeObserver(store, tmp.getRoot().toPath(),
                Runnable::run, (command, out) -> {
                    out.accept("RED build (exit 1) - nothing deployed; needs a human");
                    return 2;
                });

        observer.merged(PROJECT, ticketId, List.of("eclipse/bundles/x/src/main/java/A.java"));

        Task after = store.get(PROJECT, ticketId);
        assertTrue("blocked is the needs-a-human signal", after.blocked);
        assertTrue(after.blocker, after.blocker.startsWith("auto-deploy: RED build"));
    }

    /** AC: a deploy tool failure is a human decision, never a launch failure. */
    @Test
    public void runnerFailureBlocksInsteadOfThrowing() {
        TaskFleet.MergeObserver observer = AutoDeploy.mergeObserver(store, tmp.getRoot().toPath(),
                Runnable::run, (command, out) -> {
                    throw new Exception("pwsh missing");
                });

        observer.merged(PROJECT, ticketId, List.of("eclipse/build.ps1"));

        Task after = store.get(PROJECT, ticketId);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.startsWith("auto-deploy failed:"));
    }

    /** Non-Eclipse merge-backs never spend a build. */
    @Test
    public void nonEclipseChangesNeverRunTheBuild() {
        List<List<String>> calls = new ArrayList<>();
        TaskFleet.MergeObserver observer = AutoDeploy.mergeObserver(store, tmp.getRoot().toPath(),
                Runnable::run, (command, out) -> {
                    calls.add(command);
                    return 0;
                });

        observer.merged(PROJECT, ticketId, List.of("README.md", "docs/requirements/x.md"));

        assertTrue("no build for doc/store-only merges", calls.isEmpty());
        assertFalse(store.get(PROJECT, ticketId).blocked);
    }
}
