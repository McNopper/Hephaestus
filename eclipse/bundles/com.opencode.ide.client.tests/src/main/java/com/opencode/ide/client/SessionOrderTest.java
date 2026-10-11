package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.opencode.ide.client.model.Session;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link SessionOrder#MOST_RECENT_FIRST}. */
public class SessionOrderTest {

  private static final Gson GSON = new Gson();

  private static Session session(String id, long updated) {
    return GSON.fromJson(
        "{\"id\":\"" + id + "\",\"time\":{\"created\":1,\"updated\":" + updated + "}}",
        Session.class);
  }

  @Test
  public void mostRecentlyUpdatedFirst() {
    List<Session> sessions =
        new ArrayList<>(
            List.of(session("old", 1000), session("newest", 3000), session("middle", 2000)));
    sessions.sort(SessionOrder.MOST_RECENT_FIRST);
    assertEquals("newest", sessions.get(0).id());
    assertEquals("middle", sessions.get(1).id());
    assertEquals("old", sessions.get(2).id());
  }

  @Test
  public void missingTimeSortsLast() {
    List<Session> sessions =
        new ArrayList<>(
            List.of(GSON.fromJson("{\"id\":\"notime\"}", Session.class), session("timed", 1)));
    sessions.sort(SessionOrder.MOST_RECENT_FIRST);
    assertEquals("timed", sessions.get(0).id());
    assertEquals("notime", sessions.get(1).id());
  }

  @Test
  public void zeroTimeSortsLastAmongTimed() {
    List<Session> sessions = new ArrayList<>(List.of(session("zero", 0), session("one", 1)));
    sessions.sort(SessionOrder.MOST_RECENT_FIRST);
    assertEquals("one", sessions.get(0).id());
  }
}
