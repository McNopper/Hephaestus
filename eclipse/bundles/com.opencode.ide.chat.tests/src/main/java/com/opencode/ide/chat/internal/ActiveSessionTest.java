package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opencode.ide.client.model.Session;
import java.util.List;
import org.junit.Test;

/** T-180: the one-ground-of-truth resolution — the newest updated session wins, junk never does. */
public class ActiveSessionTest {

  private static Session session(String id, long updated) {
    return new Session(
        id, null, "t", null, null, null, new Session.Time(1, updated, 0), null, null, null, null);
  }

  private static Session withoutTime(String id) {
    return new Session(id, null, "t", null, null, null, null, null, null, null, null);
  }

  @Test
  public void theMostRecentlyUpdatedSessionWinsAcrossClients() {
    // whichever client wrote it (TUI thread is the newest update)
    List<Session> sessions =
        List.of(session("ses_eclipse", 100), session("ses_tui", 900), session("ses_old", 50));
    assertEquals("ses_tui", ActiveSession.mostRecent(sessions));
  }

  @Test
  public void junkAndTimestamplessSessionsNeverWin() {
    // Arrays.asList (not List.of): the fixture intentionally contains nulls
    List<Session> sessions =
        java.util.Arrays.asList(
            null, withoutTime("ses_no_time"), session("ses_zero", 0), session("real", 7));
    assertEquals("real", ActiveSession.mostRecent(sessions));
    assertNull(
        ActiveSession.mostRecent(java.util.Arrays.asList(null, withoutTime("x"), session("z", 0))));
  }

  @Test
  public void emptyOrNullListResolvesToNullSoTheCallerFallsBack() {
    assertNull(ActiveSession.mostRecent(null));
    assertNull(ActiveSession.mostRecent(List.of()));
  }

  @Test
  public void blankIdsAreSkipped() {
    assertNull(ActiveSession.mostRecent(List.of(session("  ", 999))));
    assertEquals("ses_a", ActiveSession.mostRecent(List.of(session("", 999), session("ses_a", 1))));
  }
}
