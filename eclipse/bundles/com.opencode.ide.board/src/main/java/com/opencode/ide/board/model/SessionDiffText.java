package com.opencode.ide.board.model;

import com.opencode.ide.client.model.FileDiff;
import java.util.List;
import java.util.Objects;

/**
 * Formats a server-side session diff ({@code GET /session/:id/diff}) into the readable text shown
 * in the Fleet view's diff dialog: one header line per file ({@code <path> (<before> → <after>)}),
 * the unified patch under it, and a {@code N file(s) changed} summary line at the end. SWT-free.
 *
 * <p>Null-tolerant by design (the server omits fields at will): a missing path renders as {@code
 * (unknown path)}, a missing revision as {@code ?} (the parenthesis pair is dropped only when
 * <b>both</b> revisions are absent), a missing patch body is skipped, and null entries in the list
 * are ignored. A null or empty list yields just {@code 0 file(s) changed}.
 */
public final class SessionDiffText {

  private SessionDiffText() {}

  /**
   * Formats {@code diffs} (never null). Header lines and patches are separated by blank lines; the
   * document ends with the summary line.
   */
  public static String format(List<FileDiff> diffs) {
    List<FileDiff> files =
        diffs == null ? List.of() : diffs.stream().filter(Objects::nonNull).toList();
    StringBuilder text = new StringBuilder();
    int count = 0;
    for (FileDiff file : files) {
      if (count++ > 0) {
        text.append('\n');
      }
      text.append(header(file)).append('\n').append('\n');
      String content = file.content();
      if (content != null && !content.isBlank()) {
        text.append(content.stripTrailing()).append('\n').append('\n');
      }
    }
    text.append(count).append(" file(s) changed");
    return text.toString();
  }

  private static String header(FileDiff file) {
    String path = file.path() == null || file.path().isBlank() ? "(unknown path)" : file.path();
    String before = file.before();
    String after = file.after();
    if (before == null && after == null) {
      return path;
    }
    return path + "  (" + revision(before) + " → " + revision(after) + ")";
  }

  private static String revision(String value) {
    return value == null || value.isBlank() ? "?" : value;
  }
}
