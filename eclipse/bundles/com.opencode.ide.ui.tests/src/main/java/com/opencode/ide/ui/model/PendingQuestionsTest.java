package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.Emojis;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Session;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Unit tests for {@link PendingQuestions}: the collected agent-questions registry (U-096) -
 * parse-free (events arrive pre-parsed), dedup by permission id, first-seen ages, attribution
 * against the session list, clear-on-reply, several pending at once. No SWT, no Display - plain
 * model fixtures ({@code ui.tests} convention).
 */
public class PendingQuestionsTest {

  private static final long NOW = 1_700_000_000_000L;

  private static Session session(String id, String parent, String title, String agent) {
    return new Session(id, null, title, agent, parent, null, null, null, null, null, null);
  }

  private static PermissionRequest asked(String sessionId, String permissionId, String title) {
    return new PermissionRequest(
        sessionId,
        permissionId,
        "bash",
        List.of("git push"),
        title,
        PermissionRequest.Status.PENDING);
  }

  private static PermissionRequest replied(String sessionId, String permissionId) {
    return new PermissionRequest(
        sessionId, permissionId, null, List.of(), null, PermissionRequest.Status.ANSWERED);
  }

  @Test
  public void askedRegistersOnePendingQuestion() {
    PendingQuestions questions = new PendingQuestions();

    assertTrue(questions.apply(asked("ses_1", "per_1", "git push"), NOW));

    assertEquals(1, questions.pendingCount());
    assertEquals(1, questions.pending().size());
    assertEquals("per_1", questions.pending().get(0).permissionId());
    assertEquals("bash: git push", questions.pending().get(0).display());
  }

  @Test
  public void severalQuestionsPendAtOnceOldestFirst() {
    PendingQuestions questions = new PendingQuestions();

    questions.apply(asked("ses_1", "per_1", "one"), NOW);
    questions.apply(asked("ses_2", "per_2", "two"), NOW + 1);
    questions.apply(asked("ses_3", "per_3", "three"), NOW + 2);

    assertEquals("N can be pending at once", 3, questions.pendingCount());
    assertEquals("per_1", questions.pending().get(0).permissionId());
    assertEquals("per_3", questions.pending().get(2).permissionId());
  }

  @Test
  public void repliedClearsTheQuestion() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "one"), NOW);
    questions.apply(asked("ses_1", "per_2", "two"), NOW + 1);

    assertTrue(questions.apply(replied("ses_1", "per_1"), NOW + 2));

    assertEquals(1, questions.pendingCount());
    assertEquals("per_2", questions.pending().get(0).permissionId());
    assertFalse(
        "a replied for an unknown id changes nothing",
        questions.apply(replied("ses_1", "per_9"), NOW + 3));
  }

  @Test
  public void redeliveredAskKeepsItsFirstSeenAge() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "git push"), NOW);

    assertFalse(
        "an identical re-delivery is no change",
        questions.apply(asked("ses_1", "per_1", "git push"), NOW + 5 * 60_000L));

    List<PendingQuestions.Row> rows = questions.rows(List.of(), NOW + 5 * 60_000L);
    assertEquals(1, rows.size());
    assertEquals("the age is first-seen, never reset", NOW, rows.get(0).askedAtMillis());
    assertEquals("5m ago", rows.get(0).ageLabel());
    assertEquals("waiting 5m", rows.get(0).waitingLabel());
  }

  @Test
  public void redeliveredAskWithNewPayloadRefreshes() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "old title"), NOW);

    assertTrue(questions.apply(asked("ses_1", "per_1", "new title"), NOW + 1));

    assertEquals(1, questions.pendingCount());
    assertEquals("bash: new title", questions.pending().get(0).display());
  }

  @Test
  public void reconcileIsAFloorNotAReplacement() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "streamed"), NOW);

    int gained =
        questions.reconcile(
            List.of(asked("ses_2", "per_2", "missed by the stream"), replied("ses_1", "per_9")));

    assertEquals("only unknown pending asks are gained", 1, gained);
    assertEquals("the partial list drops nothing", 2, questions.pendingCount());
    assertEquals(0, questions.reconcile(null));
  }

  @Test
  public void markAnsweredDropsLocallyAfterASuccessfulAnswer() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "one"), NOW);

    assertTrue(questions.markAnswered("per_1"));

    assertEquals(0, questions.pendingCount());
    assertFalse(questions.markAnswered("per_1"));
  }

  @Test
  public void dropSessionVoidsItsQuestions() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "one"), NOW);
    questions.apply(asked("ses_2", "per_2", "two"), NOW + 1);

    assertTrue(questions.dropSession("ses_1"));

    assertEquals(1, questions.pendingCount());
    assertEquals("per_2", questions.pending().get(0).permissionId());
    assertFalse(questions.dropSession("ses_1"));
    assertFalse(questions.dropSession(null));
  }

  @Test
  public void rowsAttributeASubagentQuestionToItsParent() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_child", "per_1", "git push"), NOW);
    List<Session> sessions =
        List.of(
            session("ses_parent", null, "Fix the widget", "build"),
            session("ses_child", "ses_parent", "child A", "explore"));

    List<PendingQuestions.Row> rows = questions.rows(sessions, NOW);

    assertEquals(1, rows.size());
    PendingQuestions.Row row = rows.get(0);
    assertEquals("ses_child", row.sessionId());
    assertEquals("child A", row.sessionLabel());
    assertTrue("raised by a subagent (parentID set)", row.subagent());
    assertEquals("ses_parent", row.parentSessionId());
    assertEquals("Fix the widget", row.parentLabel());
    assertEquals(
        "child A  \u2022  subagent of Fix the widget  \u2022  waiting <1m", row.attribution());
    assertEquals("subagent of Fix the widget  \u2022  ses_chil", row.detailLabel());
  }

  @Test
  public void rowsLabelRootAndUnknownSessions() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_root", "per_1", "one"), NOW);
    questions.apply(asked("ses_alien", "per_2", "two"), NOW + 1);
    List<Session> sessions = List.of(session("ses_root", null, null, "executor"));

    List<PendingQuestions.Row> rows = questions.rows(sessions, NOW + 2 * 60_000L);

    assertEquals(2, rows.size());
    assertEquals(
        "a title-less root falls back to its agent", "<executor>", rows.get(0).sessionLabel());
    assertFalse(rows.get(0).subagent());
    assertEquals("<executor>  \u2022  waiting 2m", rows.get(0).attribution());
    assertEquals("ses_root", rows.get(0).detailLabel());
    assertEquals(
        "a session the list does not know keeps its bare id",
        "ses_alien",
        rows.get(1).sessionLabel());
  }

  @Test
  public void ageLabelsComeFromTheInjectedClock() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "old"), NOW);
    questions.apply(asked("ses_1", "per_2", "fresh"), NOW + 3 * 3_600_000L);

    List<PendingQuestions.Row> rows = questions.rows(List.of(), NOW + 4 * 3_600_000L);

    assertEquals("4h ago", rows.get(0).ageLabel());
    assertEquals("waiting 4h", rows.get(0).waitingLabel());
    assertEquals("1h ago", rows.get(1).ageLabel());
    assertEquals("just now", questions.rows(List.of(), NOW).get(0).ageLabel());
  }

  @Test
  public void orphanSubagentReadsAsBareSubagent() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_child", "per_1", "one"), NOW);
    // the parent session is not in the loaded list (another project, a pruned list)
    List<Session> sessions = List.of(session("ses_child", "ses_gone", "child A", "explore"));

    PendingQuestions.Row row = questions.rows(sessions, NOW).get(0);

    assertTrue(row.subagent());
    assertEquals("ses_gone", row.parentSessionId());
    assertNull(row.parentLabel());
    assertEquals("child A  \u2022  subagent  \u2022  waiting <1m", row.attribution());
  }

  @Test
  public void markerEscalatesWithThePendingCount() {
    assertEquals("", PendingQuestions.marker(0));
    assertEquals(" " + Emojis.QUESTION, PendingQuestions.marker(1));
    assertEquals(" " + Emojis.QUESTION + "x3", PendingQuestions.marker(3));
  }

  @Test
  public void categoryDetailCountsAndAgesTheWait() {
    PendingQuestions questions = new PendingQuestions();
    assertEquals("", PendingQuestions.categoryDetail(List.of()));

    questions.apply(asked("ses_1", "per_1", "one"), NOW);
    questions.apply(asked("ses_1", "per_2", "two"), NOW + 60_000L);
    List<PendingQuestions.Row> rows = questions.rows(List.of(), NOW + 5 * 60_000L);

    assertEquals(
        "2 questions waiting  \u2022  oldest 5m ago", PendingQuestions.categoryDetail(rows));
    assertEquals(
        "1 question waiting  \u2022  oldest 5m ago",
        PendingQuestions.categoryDetail(rows.subList(0, 1)));
  }

  @Test
  public void listenersFireOnlyOnChange() {
    PendingQuestions questions = new PendingQuestions();
    AtomicInteger fired = new AtomicInteger();
    questions.addListener(fired::incrementAndGet);

    questions.apply(asked("ses_1", "per_1", "one"), NOW);
    questions.apply(asked("ses_1", "per_1", "one"), NOW + 1); // identical: no change
    questions.apply(replied("ses_1", "per_1"), NOW + 2);
    questions.apply(replied("ses_1", "per_1"), NOW + 3); // already gone: no change
    questions.markAnswered(null);

    assertEquals(2, fired.get());
  }

  @Test
  public void malformedRequestsAreIgnored() {
    PendingQuestions questions = new PendingQuestions();

    assertFalse(questions.apply(null, NOW));
    assertFalse(questions.apply(asked(null, "per_1", "one"), NOW));
    assertFalse(questions.apply(asked("ses_1", null, "one"), NOW));
    assertEquals(0, questions.pendingCount());
  }

  @Test
  public void firstSeenAtFallsBackForUnknownIds() {
    PendingQuestions questions = new PendingQuestions();
    questions.apply(asked("ses_1", "per_1", "one"), NOW);

    assertEquals(NOW, questions.firstSeenAt("per_1", NOW + 9));
    assertEquals(NOW + 9, questions.firstSeenAt("per_9", NOW + 9));
    assertEquals(NOW + 9, questions.firstSeenAt(null, NOW + 9));
  }
}
