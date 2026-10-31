package com.opencode.ide.tasks;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * U-029 forward declarations (U-058, FR-004/FR-005): which V stages of a ticket have NO applicable
 * work, so the launch path can record a {@link TaskStore#passStage} pass instead of starting a
 * session.
 *
 * <p><b>The declaration source is a ticket comment</b> - this is the design decision of U-058. A
 * stage worker (or the reviewer, or the PM) names a downstream stage by adding a comment of the
 * form {@code pass-declare: <stage> - <reason>}; {@code pass-withdraw: <stage>} withdraws one
 * again. Comments were chosen over a ticket frontmatter field or artifact markers because they are
 * the store's EXISTING append-only, attributable channel ({@link Task.Comment} carries {@code ts}
 * and {@code by}, newest last): no schema/codec change (NFR-COMPAT-001 - a store that never sees a
 * pass behaves byte-for-byte like before), "the newest declaration governs" (FR-005) falls out of
 * the chronological order for free, and every declaring authority can already write one with {@code
 * task_add_comment} - no new write verb, no protected-field bookkeeping.
 *
 * <p>Rules (pinned by {@code StageDeclarationsTest}):
 *
 * <ul>
 *   <li>only comments whose trimmed text <b>starts</b> with one of the two documented prefixes are
 *       declarations - anything else (including a declaration buried in prose) is an ordinary
 *       comment, ignored, never a partial one;
 *   <li>declarations are applied in comment order, so the newest one for a stage wins and a
 *       withdrawal cancels every earlier declaration for that stage (FR-005);
 *   <li>a declaration without an author ({@code by}) is ignored: NFR-AUDIT-001 forbids an
 *       unattributable pass, and the safe default of an unhonored declaration is a dispatched
 *       worker (FR-005 - the machinery never invents a pass);
 *   <li>the stage token must be a valid {@link VStages} name; the reason must be non-blank - the
 *       blank-reason rejection belongs to {@link TaskStore#passStage} (FR-003), the parser keeps
 *       such lines out before they can reach it.
 * </ul>
 *
 * <p>The launch side lives in {@code TaskFleet} (the one front end every dispatch path runs
 * through); this class is pure parsing over store data - no I/O, no clock.
 */
public final class StageDeclarations {

  /** Comment prefix that declares one stage no-impact: {@code pass-declare: <stage> - <reason>}. */
  public static final String DECLARE_PREFIX = "pass-declare:";

  /** Comment prefix that withdraws an earlier declaration: {@code pass-withdraw: <stage>}. */
  public static final String WITHDRAW_PREFIX = "pass-withdraw:";

  /** An active no-impact declaration: the stage it covers, the rationale and who declared it. */
  public record Declaration(String stage, String reason, String by) {}

  private StageDeclarations() {}

  /**
   * The newest active declaration covering {@code stage}, or {@code null} when none applies (no
   * declaration, a withdrawn one, or an unattributable/malformed line). Pure: comments are read in
   * their stored order, which is their chronological order.
   *
   * @param ticket the ticket whose comments carry the declarations
   * @param stage the stage being considered for dispatch (a valid {@link VStages} name)
   */
  public static Declaration forStage(Task ticket, String stage) {
    if (ticket == null || ticket.comments == null || stage == null || !VStages.isValid(stage)) {
      return null;
    }
    Map<String, Declaration> active = new LinkedHashMap<>();
    for (Task.Comment comment : ticket.comments) {
      if (comment == null || comment.text() == null) {
        continue;
      }
      String line = comment.text().trim();
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.startsWith(DECLARE_PREFIX)) {
        if (comment.by() == null || comment.by().isBlank()) {
          continue; // unattributable: never a pass (NFR-AUDIT-001)
        }
        Declaration parsed = parseDeclare(line, comment.by());
        if (parsed != null) {
          active.put(parsed.stage(), parsed);
        }
      } else if (lower.startsWith(WITHDRAW_PREFIX)) {
        String withdrawn = stageToken(line.substring(WITHDRAW_PREFIX.length()));
        if (withdrawn != null && VStages.isValid(withdrawn)) {
          active.remove(withdrawn);
        }
      }
    }
    return active.get(stage);
  }

  /**
   * Parses one {@code pass-declare: <stage> <rest>} line: {@code rest} may open with a dash or
   * colon ("{@code - the reason}") and is otherwise taken verbatim.
   *
   * @return the declaration, or {@code null} when the line is not a well-formed declaration naming
   *     a valid stage
   */
  private static Declaration parseDeclare(String line, String by) {
    String rest = line.substring(DECLARE_PREFIX.length()).trim();
    String stage = stageToken(rest);
    if (stage == null || !VStages.isValid(stage)) {
      return null;
    }
    int split = rest.indexOf(' ');
    String reason = split < 0 ? "" : rest.substring(split + 1).trim();
    while (reason.startsWith("-") || reason.startsWith(":")) {
      reason = reason.substring(1).trim();
    }
    if (reason.isEmpty()) {
      return null;
    }
    return new Declaration(stage, reason, by);
  }

  /** The first whitespace-delimited token of {@code text}, lower-cased; {@code null} when blank. */
  private static String stageToken(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int split = trimmed.indexOf(' ');
    String token = split < 0 ? trimmed : trimmed.substring(0, split);
    return token.toLowerCase(Locale.ROOT);
  }
}
