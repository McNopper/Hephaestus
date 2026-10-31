package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.board.model.TakeoverRouter.Outcome;
import com.opencode.ide.board.model.TakeoverRouter.Result;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for the {@link TakeoverRouter} decision: an attached TUI is probed, navigated to the
 * session (select-session), appended to and submitted to in order; every refusal, missing input or
 * exception falls back to CHAT. The refused-submit case documents the chosen semantics (prompt
 * stays appended in the TUI input).
 */
public class TakeoverRouterTest {

  private static final String PROMPT =
      TakeoverRouter.takeoverPrompt("T-7", "Fix the flux capacitor");

  /** Records every tuiAction call; the flags decide each action's answer. */
  private static final class FakeClient implements OpencodeClient {
    final List<String> actions = new ArrayList<>();
    final List<Map<String, Object>> bodies = new ArrayList<>();
    boolean tuiAttached = true;
    boolean refuseSelect;
    boolean refuseAppend;
    boolean refuseSubmit;
    OpencodeException failure;
    RuntimeException runtimeFailure;

    @Override
    public boolean tuiAction(String action, Map<String, Object> body) throws OpencodeException {
      actions.add(action);
      bodies.add(body);
      if (failure != null) {
        throw failure;
      }
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      return switch (action) {
        case "show-toast" -> tuiAttached;
        case "select-session" -> !refuseSelect;
        case "append-prompt" -> !refuseAppend;
        case "submit-prompt" -> !refuseSubmit;
        default -> true;
      };
    }

    @Override
    public HealthStatus getHealth() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Agent> getAgents() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProviderList getProviders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ConfigInfo getConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Session> getSessions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Session createSession(String title, Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void tuiAttachedRoutesProbeSelectAppendSubmitInOrder() {
    FakeClient client = new FakeClient();
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.TUI, result.outcome());
    assertEquals(
        List.of("show-toast", "select-session", "append-prompt", "submit-prompt"), client.actions);
    assertTrue(result.detail(), result.detail().contains("ses_1"));
  }

  @Test
  public void probeToastNotifiesWithInfoVariant() {
    FakeClient client = new FakeClient();
    TakeoverRouter.route(client, "ses_1", PROMPT);
    Map<String, Object> body = client.bodies.get(0);
    assertEquals("info", body.get("variant"));
    assertTrue(
        String.valueOf(body.get("message")), String.valueOf(body.get("message")).contains("ses_1"));
  }

  @Test
  public void selectSessionNavigatesBeforeAnythingIsAppended() {
    FakeClient client = new FakeClient();
    TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(
        "the body key is the server schema's camelCase sessionID",
        Map.of("sessionID", "ses_1"),
        client.bodies.get(1));
  }

  @Test
  public void appendPromptCarriesThePromptText() {
    FakeClient client = new FakeClient();
    TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Map.of("text", PROMPT), client.bodies.get(2));
  }

  @Test
  public void submitPromptSendsNoBody() {
    FakeClient client = new FakeClient();
    TakeoverRouter.route(client, "ses_1", PROMPT);
    assertNull(client.bodies.get(3));
  }

  @Test
  public void noTuiAttachedFallsBackToChatWithoutAppend() {
    FakeClient client = new FakeClient();
    client.tuiAttached = false;
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertEquals(List.of("show-toast"), client.actions);
    assertTrue(result.detail(), result.detail().contains("no TUI"));
  }

  @Test
  public void refusedSelectFallsBackToChatWithoutAppend() {
    FakeClient client = new FakeClient();
    client.refuseSelect = true;
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertEquals(List.of("show-toast", "select-session"), client.actions);
    assertTrue(result.detail(), result.detail().contains("select-session"));
  }

  @Test
  public void refusedAppendFallsBackToChatWithoutSubmit() {
    FakeClient client = new FakeClient();
    client.refuseAppend = true;
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertEquals(List.of("show-toast", "select-session", "append-prompt"), client.actions);
  }

  @Test
  public void refusedSubmitIsChatButLeavesPromptAppended() {
    // chosen semantics: the prompt stays appended (unsubmitted) in the TUI
    // input — CHAT keeps the caller's fallback while the human may submit
    FakeClient client = new FakeClient();
    client.refuseSubmit = true;
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertEquals(
        List.of("show-toast", "select-session", "append-prompt", "submit-prompt"), client.actions);
    assertTrue(result.detail(), result.detail().contains("appended"));
  }

  @Test
  public void opencodeExceptionFallsBackToChat() {
    FakeClient client = new FakeClient();
    client.failure = new OpencodeException("server unreachable");
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertTrue(result.detail(), result.detail().contains("server unreachable"));
  }

  @Test
  public void runtimeExceptionFallsBackToChat() {
    FakeClient client = new FakeClient();
    client.runtimeFailure = new IllegalStateException("boom");
    Result result = TakeoverRouter.route(client, "ses_1", PROMPT);
    assertEquals(Outcome.CHAT, result.outcome());
    assertTrue(result.detail(), result.detail().contains("boom"));
  }

  @Test
  public void nullClientFallsBackToChat() {
    assertEquals(Outcome.CHAT, TakeoverRouter.route(null, "ses_1", PROMPT).outcome());
  }

  @Test
  public void blankSessionIdIsChatWithoutAnyCall() {
    FakeClient client = new FakeClient();
    assertEquals(Outcome.CHAT, TakeoverRouter.route(client, "  ", PROMPT).outcome());
    assertEquals(List.of(), client.actions);
  }

  @Test
  public void blankPromptIsChatWithoutAnyCall() {
    FakeClient client = new FakeClient();
    assertEquals(Outcome.CHAT, TakeoverRouter.route(client, "ses_1", null).outcome());
    assertEquals(List.of(), client.actions);
  }

  @Test
  public void promptNamesTicketAndTitle() {
    String prompt = TakeoverRouter.takeoverPrompt("T-7", "Fix the flux capacitor");
    assertTrue(prompt, prompt.startsWith("Human takeover of T-7"));
    assertTrue(prompt, prompt.contains("Fix the flux capacitor"));
    assertTrue(prompt, prompt.contains("TUI"));
  }

  @Test
  public void promptToleratesMissingParts() {
    assertTrue(TakeoverRouter.takeoverPrompt("T-7", null).startsWith("Human takeover of T-7"));
    assertTrue(TakeoverRouter.takeoverPrompt(null, "Only a title").contains("Only a title"));
    assertTrue(TakeoverRouter.takeoverPrompt(null, null).contains("this ticket"));
  }
}
