package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.ShellTask;

/**
 * Unit tests for {@link BackgroundModel}: the SWT-free joins behind the
 * Background view's three panes. No SWT, no Display - plain model fixtures
 * ({@code ui.tests} convention).
 */
public class BackgroundModelTest {

    private static Session session(String id, String parent, String title, String agent) {
        return new Session(id, null, title, agent, parent,
                null, null, null, null, null, null);
    }

    @Test
    public void subagentsNestUnderTheirParentInDepthFirstOrder() {
        List<Session> sessions = List.of(
                session("ses_2", "ses_1", "child A", "explore"),
                session("ses_1", null, "Fix the widget", "build"),
                session("ses_3", "ses_1", "child B", "general"));

        List<BackgroundModel.AgentRow> rows = BackgroundModel.agents(sessions,
                Map.of("ses_1", new SessionStatus("busy")), Map.of());

        assertEquals("root first", "ses_1", rows.get(0).sessionId());
        assertEquals(0, rows.get(0).depth());
        assertEquals("ses_2", rows.get(1).sessionId());
        assertEquals(1, rows.get(1).depth());
        assertEquals("ses_3", rows.get(2).sessionId());
        assertEquals("the child hangs off the root", "ses_1", rows.get(2).parentSessionId());
    }

    @Test
    public void observedSessionsCarryActivityAndUnobservedStayQuiet() {
        SessionObservation observation = new SessionObservation("ses_1", "Fix the widget", "executor",
                "kimi/k3", "busy", null, 0.5, 120L, "tool: bash mvn verify", "Now I run the build.",
                List.of(), List.of(), List.of());
        List<BackgroundModel.AgentRow> rows = BackgroundModel.agents(
                List.of(session("ses_1", null, "Fix the widget", "executor")),
                Map.of("ses_1", new SessionStatus("busy")),
                Map.of("ses_1", observation));

        assertEquals("tool: bash mvn verify", rows.get(0).activity());
        assertEquals("Now I run the build.", rows.get(0).lastText());
        assertEquals("$0.5000", rows.get(0).cost());
        assertEquals("120", rows.get(0).tokens());
        assertTrue(rows.get(0).observed());

        List<BackgroundModel.AgentRow> quiet = BackgroundModel.agents(
                List.of(session("ses_1", null, "Fix the widget", "executor")),
                Map.of(), Map.of());
        assertEquals("", quiet.get(0).activity());
        assertEquals("idle", quiet.get(0).status());
    }

    @Test
    public void runningShellsFloatUpAndCarryTheirExitCode() {
        List<ShellTask> tasks = List.of(
                new ShellTask("sh_1", "exited", "ls", "/repo", 0, 1L,
                        new ShellTask.Time(1000L, 2000L)),
                new ShellTask("sh_2", "running", "mvn verify", "/repo", null, 2L,
                        new ShellTask.Time(3000L, null)));

        List<BackgroundModel.ShellRow> rows = BackgroundModel.shells(tasks);

        assertEquals("sh_2", rows.get(0).id());
        assertEquals("mvn verify", rows.get(0).command());
        assertEquals(Integer.valueOf(0), rows.get(1).exit());
        assertEquals("3000", rows.get(0).started());
    }

    @Test
    public void pendingAsksRenderWithTheirDisplayLine() {
        List<PermissionRequest> requests = List.of(
                new PermissionRequest("ses_1", "per_1", "bash", List.of("git push"),
                        "git push", PermissionRequest.Status.PENDING),
                new PermissionRequest("ses_1", "per_2", "edit", List.of("src/A.java"),
                        null, PermissionRequest.Status.ANSWERED));

        List<BackgroundModel.AskRow> rows = BackgroundModel.asks(requests);

        assertEquals("answered asks leave the overview", 1, rows.size());
        assertEquals("per_1", rows.get(0).permissionId());
        assertEquals("git push", rows.get(0).title());
        assertTrue(rows.get(0).detail(), rows.get(0).detail().contains("git push"));
    }
}
