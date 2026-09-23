package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.TaskStore;

/**
 * The shared fleet end-to-end fixture (2026-09-23 CPD findings: six test
 * classes copy-pasted it): a real {@link TaskStore} on a temp directory plus
 * the in-memory client/worktree fakes, wired into a {@link TaskFleet}.
 * Subclasses override {@link #build(TaskFleet)} for fleet variants and add
 * their own ticket builders and tests.
 */
public abstract class FleetTestHarness {

    protected static final Path REPO = Path.of("repo");
    protected static final String PROJECT = "p";
    protected static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Suite-wide waits (2026-09-23: no magic numbers outside central
     * tables). Generous on purpose: git spawns cost 10-100x on a machine
     * without the Defender exclusions (T-006). Future release: these join
     * the same config surface as the runtime knobs.
     */
    protected static final Duration SETTLE_WAIT = Duration.ofSeconds(240);
    protected static final Duration EVENT_WAIT = Duration.ofSeconds(60);
    protected static final Duration LATCH_WAIT = Duration.ofSeconds(10);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    protected TaskStore store;
    protected FakeClient client;
    protected FakeWorktreeManager worktrees;
    protected TaskFleet fleet;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
        fleet = build(new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store));
    }

    /** Hook for fleet variants (e.g. {@link TaskFleet#withAutonomousAcceptance()}). */
    protected TaskFleet build(TaskFleet fleet) {
        return fleet;
    }
}
