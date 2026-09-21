package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Post-merge telemetry of {@link TaskFleet} (constructor with a telemetry
 * client supplier), against the in-memory fakes and a real {@link TaskStore}:
 * the exact cost-actuals comment on MERGED tickets and telemetry failures
 * never failing the launch. Reuses the {@link TaskFleetTest} fixtures.
 */
public class TaskFleetTelemetryTest {

    private static final Path REPO = Path.of("repo");
    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private FakeWorktreeManager worktrees;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
    }

    private TaskFleet fleetWithTelemetry() {
        return new TaskFleet(new FleetRunner(client, worktrees, () -> { }),
                store, new RoleAgents(), () -> client);
    }

    private String sprintTicket(String role) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", role, "high", 3,
                List.of("ac one", "ac two"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    /** Adds a final assistant message carrying actuals, as a real session would report them. */
    private void finalAssistantCarries(Double cost, Session.Tokens tokens, String agent,
            String provider, String model) {
        worktrees.onMergeBack = () -> {
            ChatMessageInfo info = new ChatMessageInfo(
                    "msg_final", "ses_1", "assistant", null, agent, null, null,
                    cost, tokens, provider, model, null, null, 1_700_000_000_000L);
            client.messagesBySession.get("ses_1")
                    .add(new ChatEntry(info, List.of(new ChatPart("text", "done", null, null))));
        };
    }

    private String actualsComment(Task ticket) {
        return ticket.comments.stream()
                .map(Task.Comment::text)
                .filter(c -> c.startsWith("fleet actuals:"))
                .findFirst().orElse(null);
    }

    @Test
    public void mergedTicketCarriesTheExactCostActualsComment() {
        String id = sprintTicket("developer");
        sessionCompletes();
        finalAssistantCarries(0.0123, new Session.Tokens(6736, 3, 22,
                new Session.Cache(100, 5)), "executor", "zai-coding-plan", "glm-5.2");

        FleetJob job = fleetWithTelemetry().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22),"
                + " agent executor, model zai-coding-plan/glm-5.2", actualsComment(after));
        assertTrue("recorded by the fleet",
                after.comments.stream().anyMatch(c ->
                        "fleet".equals(c.by()) && c.text().startsWith("fleet actuals:")));
    }

    @Test
    public void absentCostAndTokensAreOmittedFromTheComment() {
        String id = sprintTicket("developer");
        sessionCompletes();
        finalAssistantCarries(null, null, "executor", "zai-coding-plan", "glm-5.2");

        FleetJob job = fleetWithTelemetry().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        String comment = actualsComment(store.get(PROJECT, id));
        assertEquals("cost and tokens are simply absent", comment,
                "fleet actuals: agent executor, model zai-coding-plan/glm-5.2");
    }

    @Test
    public void telemetryClientFailureNeverFailsTheLaunch() {
        String id = sprintTicket("developer");
        sessionCompletes();
        // getMessages breaks right before telemetry; the launch must not care
        worktrees.onMergeBack = () -> client.failGetMessages = true;

        FleetJob job = fleetWithTelemetry().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertFalse("telemetry failure must not block the ticket", after.blocked);
        assertEquals("in-review", after.status);
        assertEquals("no actuals comment could be built", null, actualsComment(after));
    }

    @Test
    public void withoutATelemetryClientTelemetryIsSkipped() {
        String id = sprintTicket("developer");
        sessionCompletes();
        finalAssistantCarries(0.0123, new Session.Tokens(6736, 3, 22, null),
                "executor", "zai-coding-plan", "glm-5.2");
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store);

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("in-review", after.status);
        assertEquals("no cost actuals recorded", null, actualsComment(after));
    }
}
