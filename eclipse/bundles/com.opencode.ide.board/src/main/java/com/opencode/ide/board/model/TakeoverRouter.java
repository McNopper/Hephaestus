package com.opencode.ide.board.model;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import java.util.Map;

/**
 * The SWT-free decision behind the Board/Fleet views' "Take over" action (ROADMAP H5 item 3): hand
 * the ticket's session to a human in the opencode TUI over the official {@code POST /tui/<action>}
 * control channel, falling back to the pre-TUI behavior (open the worktree) when no TUI is
 * attached. Extracted so the routing is testable without SWT.
 *
 * <p>Sequence: a {@code show-toast} probe doubles as the takeover notification; when a TUI answers,
 * {@code select-session} first navigates that TUI to the ticket's session (body key {@code
 * sessionID}, per the server's {@code tui.session.select} schema), then {@code append-prompt}
 * queues the standard {@link #takeoverPrompt(String, String)} text in the TUI's input box and
 * {@code submit-prompt} (a no-payload action) sends it. Routing never throws: any refused action
 * ({@code tuiAction} returns false — HTTP 404 when no TUI is attached), missing input, or exception
 * along the way yields {@link Outcome#CHAT}.
 *
 * <p>Chosen semantics for a refused submit after a successful append: the prompt stays appended
 * (unsubmitted) in the TUI input, ready for the human to submit manually — the router still reports
 * CHAT so the caller keeps its fallback. A blank session id or blank prompt also routes to CHAT
 * without any HTTP: the TUI channel is not session-scoped, so without a session there is nothing to
 * hand over.
 */
public final class TakeoverRouter {

  private TakeoverRouter() {}

  /** How a takeover reached the human. */
  public enum Outcome {
    /** Routed through the TUI control channel (a TUI answered and took the prompt). */
    TUI,
    /**
     * Fallback — no TUI attached or routing failed; the caller keeps its chat-resume/worktree
     * behavior.
     */
    CHAT
  }

  /** One routing decision: the {@link Outcome} plus a human-readable detail. */
  public record Result(Outcome outcome, String detail) {

    public static Result tui(String detail) {
      return new Result(Outcome.TUI, detail);
    }

    public static Result chat(String detail) {
      return new Result(Outcome.CHAT, detail);
    }
  }

  /**
   * Routes one takeover, never throws.
   *
   * @param client the opencode client ({@code null} → CHAT)
   * @param sessionId the session to hand over ({@code null}/blank → CHAT)
   * @param prompt the prompt to append and submit ({@code null}/blank → CHAT)
   */
  public static Result route(OpencodeClient client, String sessionId, String prompt) {
    if (client == null) {
      return Result.chat("no opencode client");
    }
    if (sessionId == null || sessionId.isBlank()) {
      return Result.chat("no session to hand to a TUI");
    }
    if (prompt == null || prompt.isBlank()) {
      return Result.chat("no takeover prompt");
    }
    String session = sessionId.strip();
    try {
      boolean attached =
          client.tuiAction(
              "show-toast",
              Map.of(
                  "title", "Take over",
                  "message", "Human takeover of session " + session + " from the Eclipse board",
                  "variant", "info"));
      if (!attached) {
        return Result.chat("no TUI attached");
      }
      if (!client.tuiAction("select-session", Map.of("sessionID", session))) {
        return Result.chat("TUI refused select-session (session left unselected)");
      }
      if (!client.tuiAction("append-prompt", Map.of("text", prompt))) {
        return Result.chat("TUI refused append-prompt");
      }
      if (!client.tuiAction("submit-prompt", null)) {
        return Result.chat("submit-prompt refused (prompt stays appended in the TUI input)");
      }
      return Result.tui("prompt appended and submitted in the TUI for session " + session);
    } catch (OpencodeException | RuntimeException e) {
      return Result.chat("TUI routing failed: " + e.getMessage());
    }
  }

  /**
   * The standard takeover prompt handed to the TUI — the single source of truth for what a
   * taken-over session is told: a human now steers it. Null-safe: a missing ticket id/title reads
   * as "this ticket".
   */
  public static String takeoverPrompt(String ticketId, String title) {
    String subject =
        ((ticketId == null ? "" : ticketId.strip()) + " " + (title == null ? "" : title.strip()))
            .strip();
    String head = subject.isEmpty() ? "this ticket" : subject;
    return "Human takeover of "
        + head
        + ": a human is now steering this session from the attached TUI. "
        + "Stop autonomous work and follow the human's instructions from here on.";
  }
}
