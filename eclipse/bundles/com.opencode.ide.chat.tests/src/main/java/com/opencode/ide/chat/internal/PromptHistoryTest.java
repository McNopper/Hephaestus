package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for CP-08 / FR-CP-08 - the composer's ArrowUp/ArrowDown recall: walking back over sent
 * prompts, stopping at the oldest (no wrap), and the AC2 rule that the in-progress draft is stashed
 * and returned rather than lost.
 */
public class PromptHistoryTest {

  @Test
  public void arrowUpWalksBackOverSentPromptsAndStopsAtTheOldest() {
    PromptHistory history = new PromptHistory();
    history.record("first");
    history.record("second");
    history.record("third");

    assertEquals(3, history.size());
    assertEquals("third", history.up(""));
    assertEquals("second", history.up(""));
    assertEquals("first", history.up(""));
    assertEquals("first", history.up("")); // no wrap-around: the oldest stays put
  }

  @Test
  public void arrowDownWalksForwardAndRestoresTheStashedDraft() {
    PromptHistory history = new PromptHistory();
    history.record("one");
    history.record("two");

    // the user was typing when they pressed ArrowUp - the draft must survive the round trip (AC2)
    assertEquals("two", history.up("half-typed draft"));
    assertEquals("one", history.up(""));
    assertEquals("two", history.down(""));
    assertEquals("half-typed draft", history.down(""));

    // one step down from the newest prompt lands on the draft, not on an older prompt
    PromptHistory second = new PromptHistory();
    second.record("only");
    assertEquals("only", second.up("draft"));
    assertEquals("draft", second.down(""));
  }

  @Test
  public void blankAndRepeatedPromptsAreNotRecorded() {
    PromptHistory history = new PromptHistory();
    history.record(null);
    history.record("   ");
    assertEquals(0, history.size());

    history.record("same");
    history.record("same");
    assertEquals("an immediate repeat is one entry", 1, history.size());

    history.record("  padded  ");
    assertEquals("entries are stored trimmed", 2, history.size());
  }

  @Test
  public void anEmptyHistoryLeavesTheComposerUntouched() {
    PromptHistory history = new PromptHistory();

    assertEquals("", history.up(""));
    assertEquals("typed", history.up("typed"));
    assertEquals("typed", history.down("typed"));
  }

  @Test
  public void theHistoryIsCappedSoALongLivedViewCannotGrowWithoutBound() {
    PromptHistory history = new PromptHistory();
    for (int i = 0; i < PromptHistory.LIMIT + 25; i++) {
      history.record("prompt-" + i);
    }

    assertEquals(PromptHistory.LIMIT, history.size());
    assertEquals("prompt-" + (PromptHistory.LIMIT + 24), history.up(""));
  }

  @Test
  public void clearForgetsEverything() {
    PromptHistory history = new PromptHistory();
    history.record("gone");
    history.clear();

    assertEquals(0, history.size());
    assertEquals("", history.up(""));
  }
}
