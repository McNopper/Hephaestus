package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import org.junit.Test;

/**
 * U-058 declaration parsing (the comment convention documented on {@link StageDeclarations}): a
 * well-formed {@code pass-declare:} comment carries stage, reason and author; the newest
 * declaration governs; withdrawals cancel; unattributable or malformed lines never declare.
 */
public class StageDeclarationsTest {

  private static final Instant T1 = Instant.parse("2026-10-14T08:00:00.000Z");

  private static Task ticket(Task.Comment... comments) {
    Task t = new Task();
    t.id = "T-001";
    t.stage = "design";
    for (Task.Comment comment : comments) {
      t.comments.add(comment);
    }
    return t;
  }

  private static Task.Comment line(String text, String by) {
    return new Task.Comment(T1, by, text);
  }

  private static StageDeclarations.Declaration forArchitecture(Task t) {
    return StageDeclarations.forStage(t, "architecture");
  }

  @Test
  public void aDeclaredStageCarriesReasonStageAndAuthor() {
    Task t =
        ticket(
            line("pass-declare: architecture - no architecture impact: local change", "dev-arch"));

    StageDeclarations.Declaration declaration = forArchitecture(t);

    assertEquals("architecture", declaration.stage());
    assertEquals("no architecture impact: local change", declaration.reason());
    assertEquals("dev-arch", declaration.by());
    assertNull(
        "other stages of the same ticket stay dispatchable",
        StageDeclarations.forStage(t, "design"));
  }

  @Test
  public void theNewestDeclarationForAStageGoverns() {
    Task t =
        ticket(
            line("pass-declare: architecture - first call", "worker"),
            line("pass-declare: architecture - the newest rationale", "reviewer"));

    StageDeclarations.Declaration declaration = forArchitecture(t);

    assertEquals("the newest rationale", declaration.reason());
    assertEquals("reviewer", declaration.by());
  }

  @Test
  public void aWithdrawalCancelsEveryEarlierDeclaration() {
    Task t =
        ticket(
            line("pass-declare: architecture - no impact", "worker"),
            line("pass-withdraw: architecture", "pm"));

    assertNull(forArchitecture(t));

    // ... and a fresh declaration after the withdrawal governs again
    t.comments.add(line("pass-declare: architecture - reconsidered: still no impact", "pm"));
    assertEquals("reconsidered: still no impact", forArchitecture(t).reason());
  }

  @Test
  public void anAuthorlessDeclarationIsIgnored() {
    Task t = ticket(line("pass-declare: architecture - no impact", null));

    assertNull("an unattributable pass is never honored (NFR-AUDIT-001)", forArchitecture(t));
    Task blank = ticket(line("pass-declare: architecture - no impact", "   "));
    assertNull(forArchitecture(blank));
  }

  @Test
  public void malformedAndOrdinaryCommentsNeverDeclare() {
    assertNull("no reason", forArchitecture(ticket(line("pass-declare: architecture", "dev"))));
    assertNull(
        "blank reason", forArchitecture(ticket(line("pass-declare: architecture - ", "dev"))));
    assertNull(
        "unknown stage token",
        forArchitecture(ticket(line("pass-declare: waterfall - no impact", "dev"))));
    assertNull(
        "declaration buried in prose is not a declaration",
        forArchitecture(
            ticket(line("I think we can pass-declare: architecture - no impact", "dev"))));
    assertNull(
        "plain comments are plain",
        forArchitecture(ticket(line("looks like no architectural impact to me", "dev"))));
    assertNull("no comments at all", forArchitecture(ticket()));
  }

  @Test
  public void anInvalidLookupStageDeclaresNothing() {
    Task t = ticket(line("pass-declare: architecture - no impact", "dev"));

    assertNull(StageDeclarations.forStage(t, "waterfall"));
    assertNull(StageDeclarations.forStage(t, null));
    assertNull(StageDeclarations.forStage(null, "architecture"));
  }
}
