package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.ShellTask;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * T-179 component tests for the server-browser terminal inventory: the server -&gt; session
 * grouping (session-bound only), the row labels/status the tree renders, and the lazy
 * expand-to-poll contract - the model fetches ONLY when {@link SessionTerminals#load} is called
 * (the view calls it on first expansion / on a session.shell.* event; it owns no timers).
 */
public class SessionTerminalsTest {

  private static ShellTask task(String id, String status, String command, String sessionId) {
    return new ShellTask(
        id,
        status,
        command,
        "/repo",
        "exited".equals(status) ? 0 : null,
        7L,
        null,
        new ShellTask.Meta(sessionId, false));
  }

  @Test
  public void groupNestsSessionBoundTasksAndDropsOrphans() {
    // Arrays.asList (not List.of): the fixture intentionally contains null
    Map<String, List<SessionTerminals.Row>> groups =
        SessionTerminals.group(
            java.util.Arrays.asList(
                task("sh_1", "running", "mvn verify", "ses_a"),
                task("sh_2", "exited", "git status", "ses_a"),
                task("sh_3", "running", "ls", "ses_b"),
                task("sh_global", "exited", "make", null), // orphans are dropped
                null));

    assertEquals(2, groups.size());
    assertEquals(2, groups.get("ses_a").size());
    assertEquals("input order preserved", "sh_1", groups.get("ses_a").get(0).shellId());
    assertEquals("sh_3", groups.get("ses_b").get(0).shellId());
    assertFalse("a session-less shell is never nested", groups.containsKey("null"));
  }

  @Test
  public void rowLabelsCarryCommandAndState() {
    SessionTerminals.Row running = new SessionTerminals.Row("sh_1", "mvn verify", "running", null);
    assertTrue(running.running());
    assertEquals("$ mvn verify · running", running.label());

    SessionTerminals.Row failed = new SessionTerminals.Row("sh_2", "make", "exited", 1);
    assertFalse(failed.running());
    assertEquals("$ make · exit 1", failed.label());

    SessionTerminals.Row stateOnly = new SessionTerminals.Row("sh_3", null, "killed", null);
    assertEquals("$ (no command) · killed", stateOnly.label());
  }

  @Test
  public void loadFetchesExactlyOncePerCallSoExpansionIsTheOnlyTrigger() throws Exception {
    AtomicInteger fetches = new AtomicInteger();
    OpencodeClient fake =
        new com.opencode.ide.client.OpencodeClient() {
          @Override
          public List<ShellTask> listShellTasks() {
            fetches.incrementAndGet();
            return List.of(task("sh_1", "running", "mvn verify", "ses_a"));
          }

          @Override
          public com.opencode.ide.client.model.HealthStatus getHealth() {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<com.opencode.ide.client.model.Agent> getAgents() {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.opencode.ide.client.model.ProviderList getProviders() {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.opencode.ide.client.model.ConfigInfo getConfig() {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<com.opencode.ide.client.model.Session> getSessions() {
            throw new UnsupportedOperationException();
          }

          @Override
          public Map<String, com.opencode.ide.client.model.SessionStatus> getSessionStatus() {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.opencode.ide.client.model.Session createSession(
              String title, java.nio.file.Path directory) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void registerMcp(String name, com.opencode.ide.client.McpServerConfig config) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<com.opencode.ide.client.model.ChatEntry> getMessages(String sessionId) {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.opencode.ide.client.model.ChatEntry sendMessage(
              com.opencode.ide.client.ChatRequest request) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void log(String service, String level, String message, Map<String, Object> extra) {
            throw new UnsupportedOperationException();
          }
        };

    // nothing fetched by merely building groupings of nothing
    assertEquals(0, fetches.get());
    // one explicit load (the view's expand-to-poll trigger) = exactly one fetch
    Map<String, List<SessionTerminals.Row>> groups = SessionTerminals.load(fake);
    assertEquals(1, fetches.get());
    assertEquals(1, groups.get("ses_a").size());
    // a second load (a session.shell.* event arrived) fetches again - by call, never by timer
    SessionTerminals.load(fake);
    assertEquals(2, fetches.get());
  }

  @Test
  public void nullAndEmptyInputAreSafe() {
    assertTrue(SessionTerminals.group(null).isEmpty());
    assertTrue(SessionTerminals.group(List.of()).isEmpty());
  }
}
