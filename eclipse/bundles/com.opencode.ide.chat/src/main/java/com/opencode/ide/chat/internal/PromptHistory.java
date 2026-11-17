package com.opencode.ide.chat.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The composer's prompt history (CP-08 / FR-CP-08): ArrowUp walks back over the prompts this view
 * sent, ArrowDown walks forward again, and the in-progress draft the user was typing when recall
 * started is stashed and restored on the way back - so recalling can never lose a half-typed prompt
 * (the FR's optional stash, implemented because it is trivial to prove).
 *
 * <p>SWT-free and session-scoped (per view, not persisted): the TUI's history is per-session too,
 * and a restart starting clean matches "recall the previous sent prompt" rather than resurrecting a
 * list the user cannot see. Blank lines and an immediate repeat of the newest entry are not
 * recorded; the list is capped so a long-lived view cannot grow without bound. Walking stops at the
 * oldest entry (no wrap-around: a wrapped list silently loses the draft's position).
 */
public final class PromptHistory {

  /** How many prompts the composer remembers (older ones drop off the back). */
  public static final int LIMIT = 100;

  private final List<String> entries = new ArrayList<>();
  private int back; // how many steps back the next ArrowUp goes (0 = at the live edge)
  private String draft = "";

  /** Records a sent prompt (blank/adjacent-duplicate ignored) and resets the walk + stash. */
  public void record(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return;
    }
    if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(prompt.trim())) {
      back = 0; // an immediate repeat is still a position reset (the user IS at the edge again)
      draft = "";
      return;
    }
    entries.add(prompt.trim());
    if (entries.size() > LIMIT) {
      entries.remove(0);
    }
    back = 0;
    draft = "";
  }

  /**
   * ArrowUp: the previous sent prompt; the first step stashes {@code current} so ArrowDown can put
   * it back. With no history the composer is left untouched.
   */
  public String up(String current) {
    if (entries.isEmpty()) {
      return current == null ? "" : current;
    }
    if (back == 0) {
      draft = current == null ? "" : current;
    }
    if (back < entries.size()) {
      back++;
    }
    return entries.get(entries.size() - back);
  }

  /**
   * ArrowDown: the next newer prompt; the step back onto the live edge returns the stashed draft -
   * the in-progress text survives the round trip (AC2).
   */
  public String down(String current) {
    if (back == 0) {
      return current == null ? "" : current;
    }
    back--;
    return back == 0 ? draft : entries.get(entries.size() - back);
  }

  /** How many prompts are remembered (0 = nothing to recall). */
  public int size() {
    return entries.size();
  }

  /** Forgets everything (the view is closing or the composer was reset). */
  public void clear() {
    entries.clear();
    back = 0;
    draft = "";
  }
}
