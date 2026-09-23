package com.opencode.ide.board.model;

import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.TaskStore;

/**
 * Shared board-model fixture (2026-09-23 CPD findings: BoardModelPipelineTest
 * and BoardModelStageFilterTest copy-pasted it): a {@link TaskStore} on a temp
 * directory plus the plain task spec both fixtures build their tickets from.
 */
public abstract class BoardModelTestHarness {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    protected TaskStore store;
    protected Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    /** The plain task spec both fixtures build their tickets from. */
    protected TaskStore.CreateSpec spec(String title, String role) {
        return new TaskStore.CreateSpec(title, "", "task", role, "medium", 0, List.of(), List.of(), null, "T");
    }
}
