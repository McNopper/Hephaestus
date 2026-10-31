package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Session;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for {@link SessionQuestions}: the in-session projection of the collected questions
 * (U-096) - the subtree join that makes a SUBAGENT's question visible on the parent session's
 * surfaces without opening the subagent. SWT-free.
 */
public class SessionQuestionsTest {

  private static final long NOW = 1_700_000_000_000L;

  private static Session session(String id, String parent, String title) {
    return new Session(id, null, title, "explore", parent, null, null, null, null, null, null);
  }

  private static PermissionRequest asked(String sessionId, String permissionId) {
    return new PermissionRequest(
        sessionId,
        permissionId,
        "bash",
        List.of("git push"),
        "git push",
        PermissionRequest.Status.PENDING);
  }

  /** parent - child - grandchild, plus a foreign root with its own child. */
  private static List<Session> tree() {
    return List.of(
        session("ses_p", null, "Parent"),
        session("ses_c", "ses_p", "Child"),
        session("ses_g", "ses_c", "Grandchild"),
        session("ses_x", null, "Foreign"),
        session("ses_xc", "ses_x", "Foreign child"));
  }

  @Test
  public void subtreeIdsIncludeDescendantsRecursively() {
    Set<String> ids = SessionQuestions.subtreeIds("ses_p", tree());

    assertEquals(Set.of("ses_p", "ses_c", "ses_g"), ids);
    assertFalse(ids.contains("ses_x"));
  }

  @Test
  public void subtreeIdsTolerateNullsBlanksAndCycles() {
    assertTrue(SessionQuestions.subtreeIds(null, tree()).isEmpty());
    assertTrue(SessionQuestions.subtreeIds(" ", tree()).isEmpty());
    assertEquals(Set.of("ses_p"), SessionQuestions.subtreeIds("ses_p", null));

    // a parentID cycle cannot loop the collector
    List<Session> cycle = List.of(session("ses_a", "ses_b", "A"), session("ses_b", "ses_a", "B"));
    assertEquals(Set.of("ses_a", "ses_b"), SessionQuestions.subtreeIds("ses_a", cycle));
  }

  @Test
  public void rowsKeepOnlyTheSubtreeOldestFirst() {
    List<PermissionRequest> pending =
        List.of(asked("ses_g", "per_1"), asked("ses_x", "per_2"), asked("ses_p", "per_3"));

    List<PendingQuestions.Row> rows = SessionQuestions.rows("ses_p", tree(), pending, null, NOW);

    assertEquals(2, rows.size());
    assertEquals("the pending order (oldest first) is kept", "per_1", rows.get(0).permissionId());
    assertEquals("per_3", rows.get(1).permissionId());
    assertEquals("Grandchild", rows.get(0).sessionLabel());
    assertTrue(rows.get(0).subagent());
    assertEquals("Child", rows.get(0).parentLabel());
  }

  @Test
  public void theAc1FlowASubagentAskRendersOnTheParent() {
    // the ticket's core: one question, raised by the grandchild, visible from
    // the parent session's surface with full attribution - without opening it
    PendingQuestions registry = new PendingQuestions();
    registry.apply(asked("ses_g", "per_1"), NOW);

    List<PendingQuestions.Row> rows =
        SessionQuestions.rows("ses_p", tree(), registry.pending(), registry, NOW + 5 * 60_000L);

    assertEquals(1, rows.size());
    assertEquals(
        "Grandchild  \u2022  subagent of Child  \u2022  waiting 5m", rows.get(0).attribution());
    assertEquals("bash: git push", rows.get(0).question());
    assertEquals("5m ago", rows.get(0).ageLabel());
  }

  @Test
  public void rowsReuseTheRegistryFirstSeenAge() {
    PendingQuestions registry = new PendingQuestions();
    registry.apply(asked("ses_c", "per_1"), NOW);

    // a freshly fetched list (the Session Details controller's load) carries
    // no age of its own; the shared registry's first-seen wins
    List<PendingQuestions.Row> rows =
        SessionQuestions.rows("ses_p", tree(), registry.pending(), registry, NOW + 3_600_000L);

    assertEquals("1h ago", rows.get(0).ageLabel());

    List<PendingQuestions.Row> unstamped =
        SessionQuestions.rows("ses_p", tree(), registry.pending(), null, NOW + 3_600_000L);
    assertEquals(
        "without the registry every ask is brand new", "just now", unstamped.get(0).ageLabel());
  }

  @Test
  public void countForSubtreeCountsDescendants() {
    List<PermissionRequest> pending =
        List.of(asked("ses_g", "per_1"), asked("ses_c", "per_2"), asked("ses_x", "per_3"));

    assertEquals(2, SessionQuestions.countForSubtree("ses_p", tree(), pending));
    assertEquals(
        "ses_c's subtree = ses_c + ses_g: per_2 (own) + per_1 (descendant)",
        2,
        SessionQuestions.countForSubtree("ses_c", tree(), pending));
    assertEquals(
        "ses_g's subtree = ses_g alone: its own ask counts too",
        1,
        SessionQuestions.countForSubtree("ses_g", tree(), pending));
    assertEquals(0, SessionQuestions.countForSubtree("ses_p", tree(), null));
  }

  @Test
  public void firstForSubtreeReturnsTheOldestWaitingQuestion() {
    List<PermissionRequest> pending = List.of(asked("ses_c", "per_1"), asked("ses_g", "per_2"));

    PermissionRequest first = SessionQuestions.firstForSubtree("ses_p", tree(), pending);

    assertEquals("per_1", first.permissionId());
    assertNull(SessionQuestions.firstForSubtree("ses_x", tree(), pending));
    assertNull(SessionQuestions.firstForSubtree("ses_p", tree(), null));
  }

  @Test
  public void answeredAsksNeverEnterTheSubtreeRows() {
    PermissionRequest answered =
        new PermissionRequest(
            "ses_c", "per_1", null, List.of(), null, PermissionRequest.Status.ANSWERED);

    assertTrue(SessionQuestions.rows("ses_p", tree(), List.of(answered), null, NOW).isEmpty());
    assertEquals(0, SessionQuestions.countForSubtree("ses_p", tree(), List.of(answered)));
  }
}
