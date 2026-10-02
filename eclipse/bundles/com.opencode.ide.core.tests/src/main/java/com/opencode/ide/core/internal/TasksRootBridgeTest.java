package com.opencode.ide.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.core.OpencodePreferences;
import com.opencode.ide.core.TasksRootResolution;

/**
 * Component test of the ONE task-store root (B-016): the value the core
 * activator bridges into {@code opencode.tasks.root} — what the
 * eclipse-build MCP endpoint serves its {@code task_*} tools from — must be
 * the Board surface's resolution ({@link TasksRootResolution#
 * resolveForWorkspace(String)} with an empty override) for the same
 * workspace and preferences. With the old raw-preference bridge a blank
 * {@code tasksRoot} preference left the property unset, so the endpoint
 * fell back to {@code ~/.opencode/tasks} while the Board adopted the open
 * repo's store — two surfaces, two stores.
 *
 * <p>Runs inside the OSGi test runtime (workspace location, resources and
 * instance preferences all live). That runtime's workspace nests in this
 * repository, so its live resolution is the <b>workspace climb</b> - the
 * preference and historical-guess fallbacks further down the order are
 * pinned by the pure order tests in {@code TasksRootResolutionTest}.</p>
 */
public class TasksRootBridgeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OpencodePreferences prefs = new OpencodePreferences();

    private String priorRoot;
    private String priorPreference;

    @Before
    public void setUp() {
        priorRoot = System.getProperty("opencode.tasks.root");
        System.clearProperty("opencode.tasks.root");
        // the instance-scope node is shared with other tests: start from the
        // unset state and restore afterwards
        priorPreference = prefs.raw().get(OpencodePreferences.KEY_TASKS_ROOT, null);
        prefs.raw().remove(OpencodePreferences.KEY_TASKS_ROOT);
    }

    @After
    public void tearDown() {
        if (priorPreference == null) {
            prefs.raw().remove(OpencodePreferences.KEY_TASKS_ROOT);
        } else {
            prefs.raw().put(OpencodePreferences.KEY_TASKS_ROOT, priorPreference);
        }
        if (priorRoot == null) {
            System.clearProperty("opencode.tasks.root");
        } else {
            System.setProperty("opencode.tasks.root", priorRoot);
        }
    }

    @Test
    public void bridgeServesTheBoardsResolutionForTheSameWorkspace() {
        Path boardSurface = TasksRootResolution.resolveForWorkspace("");

        CoreActivator.bridgeTasksRootPreference();

        String bridged = System.getProperty("opencode.tasks.root");
        assertNotNull("the bridge must set the property once the workspace"
                + " location is known (the OSGi test runtime always has one)",
                bridged);
        assertEquals("endpoint property must equal the Board's resolution"
                + " for the same workspace and preferences",
                boardSurface.toString(), bridged);
    }

    @Test
    public void blankPreferenceStillBridgesTheSharedOrder() {
        // THE B-016 failure: a blank tasksRoot preference must NOT leave the
        // property unset (the endpoint would watch ~/.opencode/tasks while
        // the Board shows the climbed/adopted store)
        prefs.raw().remove(OpencodePreferences.KEY_TASKS_ROOT);

        CoreActivator.bridgeTasksRootPreference();

        String bridged = System.getProperty("opencode.tasks.root");
        assertNotNull("a blank preference still bridges the resolved root", bridged);
        assertEquals(TasksRootResolution.resolveForWorkspace("").toString(), bridged);
    }

    @Test
    public void configuredPreferenceDoesNotOverrideTheSharedOrder() throws IOException {
        // The preference is the order's LAST fallback (pinned purely by
        // TasksRootResolutionTest.preferenceIsTheFallbackWhenNothingIsAdoptable):
        // when the workspace itself nests in a repo - as the Tycho runtime's
        // does, target/work/data inside this repository - the workspace climb
        // outranks it and the bridge must follow the SAME order, not the
        // preference.
        Path store = tmp.getRoot().toPath().resolve("pref-store");
        Files.createDirectories(store.resolve(".opencode").resolve("tasks"));
        prefs.raw().put(OpencodePreferences.KEY_TASKS_ROOT,
                store.resolve(".opencode").resolve("tasks").toString());

        CoreActivator.bridgeTasksRootPreference();

        assertEquals("the bridge follows the shared order for the same"
                        + " workspace and preferences",
                TasksRootResolution.resolveForWorkspace("").toString(),
                System.getProperty("opencode.tasks.root"));
        assertNotEquals("the preference's store must not leak past the workspace climb",
                store.resolve(".opencode").resolve("tasks").toString(),
                System.getProperty("opencode.tasks.root"));
    }
}
