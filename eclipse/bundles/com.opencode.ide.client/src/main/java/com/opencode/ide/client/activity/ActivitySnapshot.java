package com.opencode.ide.client.activity;

import java.util.Map;

/**
 * Immutable view of the live fleet activity derived from the opencode {@code /event} stream:
 * per-session tool/thinking state plus the set of files currently being worked on.
 */
public record ActivitySnapshot(
    Map<String, SessionActivity> sessions, Map<String, FileActivity> files) {

  public static final ActivitySnapshot EMPTY = new ActivitySnapshot(Map.of(), Map.of());

  public ActivitySnapshot {
    sessions = sessions == null ? Map.of() : Map.copyOf(sessions);
    files = files == null ? Map.of() : Map.copyOf(files);
  }
}
