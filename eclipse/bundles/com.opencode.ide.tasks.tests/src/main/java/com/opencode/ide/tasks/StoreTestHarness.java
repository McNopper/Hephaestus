package com.opencode.ide.tasks;

import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 * Shared real-store fixture (2026-09-23 CPD findings: AdvanceGateTest and
 * VPipelineTest copy-pasted it): a {@link TaskStore} on a temp directory and
 * the {@link #staged} ticket builder both use.
 */
public abstract class StoreTestHarness {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    protected TaskStore store;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    }

    /** A staged ticket with an assignee, in the given status. */
    protected String staged(String stage, String status) {
        Task t = store.create("p", TaskStore.CreateSpec.of("t"), stage);
        store.update("p", t.id, Map.of("status", status, "assignee", "worker"));
        return t.id;
    }
}
