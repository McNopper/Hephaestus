package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.FileDiff;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link SessionDiffText}: header/patch/summary layout and null tolerance (missing
 * revisions, missing content, null entries, null or empty list).
 */
public class SessionDiffTextTest {

  @Test
  public void formatsHeaderPatchAndSummary() {
    String text =
        SessionDiffText.format(
            List.of(
                new FileDiff(
                    "src/a.cpp",
                    "main",
                    "opencode/T-1",
                    "--- a/src/a.cpp\n+++ b/src/a.cpp\n@@ -1 +1,2 @@\n line1\n+line2")));

    assertTrue(text, text.startsWith("src/a.cpp  (main → opencode/T-1)\n"));
    assertTrue(text, text.contains("+line2"));
    assertTrue(text, text.endsWith("1 file(s) changed"));
  }

  @Test
  public void twoFilesAreSeparatedAndCounted() {
    String text =
        SessionDiffText.format(
            List.of(
                new FileDiff("a.txt", null, "r2", "+a"), new FileDiff("b.txt", "r1", null, "+b")));

    assertTrue(text, text.contains("a.txt  (? → r2)"));
    assertTrue(text, text.contains("b.txt  (r1 → ?)"));
    assertTrue(text, text.endsWith("2 file(s) changed"));
  }

  @Test
  public void missingRevisionsOmitTheParenthesis() {
    String text = SessionDiffText.format(List.of(new FileDiff("a.txt", null, null, "+a")));

    assertEquals("a.txt", text.substring(0, text.indexOf('\n')));
  }

  @Test
  public void nullContentYieldsHeaderOnly() {
    String text = SessionDiffText.format(List.of(new FileDiff("a.txt", "r1", "r2", null)));

    assertEquals("a.txt  (r1 → r2)\n\n1 file(s) changed", text);
  }

  @Test
  public void missingPathRendersPlaceholder() {
    String text = SessionDiffText.format(List.of(new FileDiff(null, "r1", "r2", "+a")));

    assertTrue(text, text.startsWith("(unknown path)  (r1 → r2)"));
  }

  @Test
  public void emptyListYieldsOnlyTheSummary() {
    assertEquals("0 file(s) changed", SessionDiffText.format(List.of()));
  }

  @Test
  public void nullListYieldsOnlyTheSummary() {
    assertEquals("0 file(s) changed", SessionDiffText.format(null));
  }

  @Test
  public void nullEntriesAreIgnored() {
    String text =
        SessionDiffText.format(Arrays.asList(null, new FileDiff("a.txt", "r1", "r2", "+a"), null));

    assertTrue(text, text.endsWith("1 file(s) changed"));
  }
}
