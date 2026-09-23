package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.ShellResult;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Optional pre-prompt bootstrap (ROADMAP H5 leftover): a {@link Bootstrap}
 * command on a {@link FleetTask} runs in the freshly created session BEFORE
 * the main prompt ({@code runShell}), is best-effort - any failure or error
 * status is logged and the launch proceeds - and absent/blank means no shell
 * call at all. Uses the same in-memory fakes as the other fleet tests;
 * outcome logging is behavior-only here (the suite has no log capture).
 */
public class FleetBootstrapTest extends FleetTestHarness {

    private String sprintTicket() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    @Test
    public void bootstrapRunsInNewSessionBeforeThePrompt() {
        String id = sprintTicket();
        sessionCompletes();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, Bootstrap.of("build", "npm install"));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals(List.of("ses_1|build|npm install"), client.shellCalls);
        assertEquals("the bootstrap must run in the new session before the prompt",
                List.of("shell ses_1", "message ses_1"), client.callLog);
    }

    @Test
    public void bootstrapTransportFailureStillSendsPromptAndMerges() {
        String id = sprintTicket();
        sessionCompletes();
        client.failRunShell = true;

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, Bootstrap.of("build", "npm ci"));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("the shell was attempted", 1, client.shellCalls.size());
        assertEquals(1, client.sentRequests.size());
        assertTrue(client.sentRequests.get(0).text().contains("task_get(\"" + id + "\")"));
    }

    @Test
    public void bootstrapRuntimeFailureStillSendsPromptAndMerges() {
        String id = sprintTicket();
        sessionCompletes();
        client.failRunShellRuntime = true;

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, Bootstrap.of("build", "npm ci"));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals(1, client.shellCalls.size());
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void bootstrapErrorStatusStillSendsPromptAndMerges() {
        String id = sprintTicket();
        sessionCompletes();
        client.shellResult = new ShellResult("msg_1", "build", "npm ci", "error", "EPERM");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, Bootstrap.of("build", "npm ci"));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals(1, client.shellCalls.size());
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void noBootstrapByDefaultMakesNoShellCalls() {
        String id = sprintTicket();
        sessionCompletes();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue("no runShell call may happen", client.shellCalls.isEmpty());
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void blankBootstrapCommandIsTreatedAsNone() {
        String id = sprintTicket();
        sessionCompletes();
        assertNull("blank command normalizes to no bootstrap", Bootstrap.of("build", "   "));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, new Bootstrap("build", " "));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue("a raw blank-command bootstrap must also be skipped", client.shellCalls.isEmpty());
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void bootstrapOfNormalizesBlankAgentToServerDefault() {
        Bootstrap bootstrap = Bootstrap.of("   ", "npm install");

        assertEquals("npm install", bootstrap.command());
        assertNull(bootstrap.agent());
    }

    @Test
    public void runnerLevelTaskWithoutBootstrapMakesNoShellCalls() {
        sessionCompletes();

        FleetJob job = new FleetRunner(client, worktrees, () -> { })
                .submit(new FleetTask("t1", "Fleet task", "do the thing", null, null, REPO));

        assertEquals(FleetJob.State.RUNNING, job.state());
        assertTrue(client.shellCalls.isEmpty());
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void runnerPassesSessionAgentAndCommandToTheShell() {
        sessionCompletes();

        FleetJob job = new FleetRunner(client, worktrees, () -> { })
                .submit(new FleetTask("t1", "Fleet task", "do the thing", null, null,
                        new Bootstrap("build", "npm install"), REPO));

        assertEquals(FleetJob.State.RUNNING, job.state());
        assertEquals(List.of("ses_1|build|npm install"), client.shellCalls);
        assertEquals(1, client.sentRequests.size());
    }

    @Test
    public void bootstrapRunsUnderTheRunnerLevelSessionWatch() {
        String id = sprintTicket();
        sessionCompletes();
        // mirrors TaskFleetLauncher: the runner watches its sessions through
        // the session-created callback, so the bootstrap shell call runs in
        // an already permission-watched session
        FleetPermissionBridge bridge = new FleetPermissionBridge(new PermissionQueue(null));
        TaskFleet fleet = new TaskFleet(
                new FleetRunner(client, worktrees, () -> { }, bridge::sessionStarted), store);

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT, Bootstrap.of("build", "npm install"));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals(List.of("ses_1|build|npm install"), client.shellCalls);
        assertEquals(List.of("shell ses_1", "message ses_1"), client.callLog);
    }
}
