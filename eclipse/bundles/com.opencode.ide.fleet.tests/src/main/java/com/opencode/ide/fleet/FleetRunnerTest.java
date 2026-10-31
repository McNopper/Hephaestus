package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.git.MergeResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link FleetRunner} against in-memory fakes of {@link FakeClient} and {@link
 * FakeWorktreeManager} (no git, no HTTP).
 */
public class FleetRunnerTest {

  private static final Path REPO = Path.of("repo");

  private final FakeClient client = new FakeClient();
  private final FakeWorktreeManager worktrees = new FakeWorktreeManager();

  private static FleetTask task(String agent, String model) {
    return new FleetTask("t1", "Fleet task", "do the thing", agent, model, REPO);
  }

  private FleetRunner runner() {
    return new FleetRunner(client, worktrees, () -> {});
  }

  @Test
  public void submitCreatesSessionInWorktreeAndSendsPrompt() throws Exception {
    client.replyOnSend = "done";
    FleetRunner runner = runner();

    FleetJob job = runner.submit(task("build", "anthropic/claude"));

    assertEquals(FleetJob.State.RUNNING, job.state());
    assertNull(job.detail());
    assertEquals("t1", job.taskId());
    assertEquals("ses_1", job.sessionId());
    assertEquals(REPO.resolve(".git/opencode-fleet/t1"), job.worktree());
    assertEquals(List.of(REPO.resolve(".git/opencode-fleet/t1")), client.sessionDirectories);
    assertEquals(List.of("Fleet task"), client.createdTitles);
    assertEquals(1, client.sentRequests.size());
    ChatRequest request = client.sentRequests.get(0);
    assertEquals("ses_1", request.sessionId());
    assertEquals("do the thing", request.text());
    assertEquals("build", request.agent());
    assertEquals("anthropic", request.providerId());
    assertEquals("claude", request.modelId());
  }

  @Test
  public void submitUsesServerDefaultsForNullAgentAndModel() {
    FleetJob job = runner().submit(task(null, null));

    assertEquals(FleetJob.State.RUNNING, job.state());
    ChatRequest request = client.sentRequests.get(0);
    assertNull(request.agent());
    assertFalse(request.hasModel());
  }

  @Test
  public void submitFailureAfterWorktreeCreationKeepsWorktreeForPostMortem() {
    client.failSessionCreation = true;

    FleetJob job = runner().submit(task(null, null));

    assertEquals(FleetJob.State.FAILED, job.state());
    assertNull(job.sessionId());
    assertTrue(job.detail(), job.detail().contains("session create failed"));
    assertEquals(REPO.resolve(".git/opencode-fleet/t1"), job.worktree());
    assertEquals(List.of("t1"), worktrees.createdTaskIds);
    assertTrue("no message must be sent", client.sentRequests.isEmpty());
  }

  @Test
  public void isCompleteRequiresIdleSessionStatus() throws Exception {
    client.replyOnSend = "done";
    FleetRunner runner = runner();
    FleetJob job = runner.submit(task(null, null));

    client.sessionType = "busy";
    assertFalse(runner.isComplete(job));

    client.sessionType = "idle";
    assertTrue(runner.isComplete(job));
  }

  @Test
  public void isCompleteRequiresAssistantReply() throws Exception {
    FleetRunner runner = runner();
    FleetJob job = runner.submit(task(null, null));

    client.sessionType = "idle";
    assertFalse("only the user message exists", runner.isComplete(job));
  }

  @Test
  public void isCompleteRequiresNonEmptyAssistantText() throws Exception {
    FleetRunner runner = runner();
    FleetJob job = runner.submit(task(null, null));

    client.sessionType = "idle";
    client.addEntry(job.sessionId(), "assistant", "");
    assertFalse(runner.isComplete(job));
  }

  @Test
  public void awaitCompletionReturnsCompletedJob() throws Exception {
    client.replyOnSend = "done";
    client.sessionType = "idle";
    FleetJob job = runner().submit(task(null, null));

    job = runner().awaitCompletion(job, Duration.ofSeconds(5));

    assertEquals(FleetJob.State.COMPLETED, job.state());
    assertNull(job.detail());
  }

  @Test
  public void awaitCompletionPollsUntilSessionCompletes() throws Exception {
    FleetJob submitted = runner().submit(task(null, null));
    final String sessionId = submitted.sessionId();

    FleetRunner polling =
        new FleetRunner(client, worktrees, () -> client.completeSession(sessionId, "finished"));

    FleetJob job = polling.awaitCompletion(submitted, Duration.ofSeconds(5));

    assertEquals(FleetJob.State.COMPLETED, job.state());
  }

  @Test
  public void awaitCompletionTimesOutToFailed() throws Exception {
    FleetJob job = runner().submit(task(null, null));

    job = runner().awaitCompletion(job, Duration.ofMillis(20));

    assertEquals(FleetJob.State.FAILED, job.state());
    assertTrue(job.detail(), job.detail().contains("timeout"));
    assertTrue(worktrees.mergedTaskIds.isEmpty());
  }

  @Test
  public void mergeBackMergesAndCallsManagerWithTaskId() throws Exception {
    client.replyOnSend = "done";
    client.sessionType = "idle";
    FleetRunner runner = runner();
    FleetJob job = runner.submit(task(null, null));
    job = runner.awaitCompletion(job, Duration.ofSeconds(5));

    job = runner.mergeBack(job);

    assertEquals(FleetJob.State.MERGED, job.state());
    assertNull(job.detail());
    assertEquals(List.of("t1"), worktrees.mergedTaskIds);
    assertEquals(List.of(REPO), worktrees.mergedRepoRoots);
  }

  @Test
  public void mergeBackConflictFailsWithConflictedFiles() throws Exception {
    client.replyOnSend = "done";
    client.sessionType = "idle";
    FleetRunner runner = runner();
    FleetJob job = runner.awaitCompletion(runner.submit(task(null, null)), Duration.ofSeconds(5));
    worktrees.nextMergeResult =
        new MergeResult(false, List.of("src/A.java", "README.md"), "CONFLICT");

    job = runner.mergeBack(job);

    assertEquals(FleetJob.State.FAILED, job.state());
    assertTrue(job.detail(), job.detail().contains("src/A.java"));
    assertTrue(job.detail(), job.detail().contains("README.md"));
  }

  @Test
  public void mergeBackRejectsJobThatIsNotCompleted() {
    FleetJob job = runner().submit(task(null, null));

    try {
      runner().mergeBack(job);
      fail("expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("RUNNING"));
    }
    assertTrue(worktrees.mergedTaskIds.isEmpty());
  }
}
