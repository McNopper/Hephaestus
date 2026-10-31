package com.opencode.ide.ui.model;

import com.opencode.ide.client.Emojis;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Session;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The collected agent-questions registry (U-096): EVERY pending permission ask of the primary
 * opencode server, across all sessions - chat sessions, fleet workers and their subagents alike -
 * so several questions can wait at once and every surface (Server view's Questions category, the
 * Background view's Questions tab, the Session Details questions section) renders the SAME truth.
 * "Question" is the TUI-parity word for what the wire calls a permission request: the agent stalls
 * until the human answers (the fleet watchdog pauses its stall clock while one pends - a session
 * waiting for an answer is WAITING, not hung).
 *
 * <p>Feeding (see {@code PendingQuestionsFeed} for the wiring): the opencode {@code /api/event} SSE
 * stream, parsed by the client bundle's {@code PermissionEvents} (reused - no second parser), lands
 * in {@link #apply(PermissionRequest)}; {@code permission.asked} registers or refreshes the entry
 * (deduplicated by permission id, the first-seen age is kept), {@code permission.replied} -
 * answered by ANY client, so an answer given anywhere clears it everywhere - drops it. {@link
 * #reconcile(List)} is the REST safety net ({@code GET /permission/request}): asks the stream
 * missed are added, but the list is a FLOOR, never a replacement - removal rides replied events and
 * {@link #dropSession(String)} ({@code session.deleted}). {@link #markAnswered(String)} is the
 * local fast-path after THIS Eclipse instance answered successfully, so the surfaces clear without
 * waiting for the event round-trip.
 *
 * <p>Answering stays with the views (the standard reply route, {@code
 * OpencodeClient#respondToPermission}, once/always/reject) - this registry is observational, like
 * {@code ActivityTracker}; change notification via {@link Runnable} listeners, invoked on the
 * mutating thread. Thread-safe; pure Java, no SWT, so the mapping is unit-testable ({@code
 * ui.tests} convention).
 */
public final class PendingQuestions {

  /**
   * One pending question as the surfaces render it: identity for answering ({@code sessionId} +
   * {@code permissionId}), the question line ({@link PermissionRequest#display()}), the agent
   * attribution (asking session's title/agent, whether it is a subagent and of which parent) and
   * the age language. Display rows never carry {@code null} text except {@code parentSessionId} /
   * {@code parentLabel} (absent for root sessions and unknown parents).
   */
  public record Row(
      String sessionId,
      String permissionId,
      String question,
      String sessionLabel,
      boolean subagent,
      String parentSessionId,
      String parentLabel,
      long askedAtMillis,
      String ageLabel,
      String waitingLabel) {

    /**
     * The full one-line attribution, e.g. {@code "child A \u2022 subagent of Fix the widget \u2022
     * waiting 5m"} or {@code "Fix the widget \u2022 waiting <1m"} (blank parts skipped).
     */
    public String attribution() {
      StringBuilder sb = new StringBuilder();
      append(sb, sessionLabel);
      if (subagent) {
        append(
            sb,
            parentLabel == null || parentLabel.isBlank()
                ? "subagent"
                : "subagent of " + parentLabel);
      }
      append(sb, waitingLabel);
      return sb.toString();
    }

    /**
     * The short detail for table columns: {@code "subagent of Fix the widget \u2022 ses_1a2b"} for
     * a subagent ask, the bare short session id for a root ask.
     */
    public String detailLabel() {
      String id = ServerLabels.shortId(sessionId);
      if (!subagent) {
        return id;
      }
      String parent =
          parentLabel == null || parentLabel.isBlank() ? "subagent" : "subagent of " + parentLabel;
      return id.isEmpty() ? parent : parent + "  \u2022  " + id;
    }

    private static void append(StringBuilder sb, String part) {
      if (part != null && !part.isBlank()) {
        sb.append(sb.length() == 0 ? "" : "  \u2022  ").append(part);
      }
    }
  }

  /** One queued question; the first-seen timestamp is immutable (re-delivery never resets age). */
  private static final class Entry {

    final long sequence;
    final String sessionId;
    final String permissionId;
    final long askedAtMillis;
    volatile PermissionRequest request;

    Entry(long sequence, PermissionRequest request, long askedAtMillis) {
      this.sequence = sequence;
      this.sessionId = request.sessionId();
      this.permissionId = request.permissionId();
      this.request = request;
      this.askedAtMillis = askedAtMillis;
    }
  }

  private static final PendingQuestions DEFAULT = new PendingQuestions();

  /** The shared registry every surface renders (the ONE questions pane's data source). */
  public static PendingQuestions getDefault() {
    return DEFAULT;
  }

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong sequencer = new AtomicLong();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  /** Registers a change notification (runs on whichever thread mutates the registry). */
  public void addListener(Runnable listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  /**
   * Applies one parsed SSE event with the arrival clock; see {@link #apply(PermissionRequest,
   * long)}.
   */
  public boolean apply(PermissionRequest request) {
    return apply(request, System.currentTimeMillis());
  }

  /**
   * Registers, refreshes or drops one question (deduplicated by permission id): a pending ask
   * inserts (first-seen age) or refreshes its payload (age kept), an answered one drops the entry
   * (answered by ANY client). Ignored when the request or its ids are {@code null}.
   *
   * @return whether the registry changed (listeners fire only then)
   */
  public boolean apply(PermissionRequest request, long nowMillis) {
    if (request == null || request.sessionId() == null || request.permissionId() == null) {
      return false;
    }
    boolean[] changed = {false};
    entries.compute(
        request.permissionId(),
        (id, existing) -> {
          if (!request.pending()) {
            changed[0] = existing != null;
            return null; // answered by anyone: gone from every surface
          }
          if (existing == null) {
            changed[0] = true;
            return new Entry(sequencer.incrementAndGet(), request, nowMillis);
          }
          if (!sameAsk(existing.request, request)) {
            existing.request = request;
            changed[0] = true;
          }
          return existing;
        });
    if (changed[0]) {
      fire();
    }
    return changed[0];
  }

  /**
   * Reconciliation with the service's own permission list ({@code GET /permission/request}): asks
   * the event stream missed (an event gap, a reconnect, an ask raised before the feed installed)
   * are offered exactly like an {@code asked} event. The list is a FLOOR, never a replacement -
   * nothing already queued is dropped by a partial list.
   *
   * @return how many questions the registry gained
   */
  public int reconcile(List<PermissionRequest> requests) {
    if (requests == null) {
      return 0;
    }
    int gained = 0;
    for (PermissionRequest request : requests) {
      if (request != null
          && request.pending()
          && !entries.containsKey(request.permissionId())
          && apply(request)) {
        gained++;
      }
    }
    return gained;
  }

  /**
   * The local fast-path after a successful answer POST from THIS instance: drops the entry without
   * waiting for the {@code replied} event (which clears it anyway when it arrives).
   *
   * @return whether an entry was dropped
   */
  public boolean markAnswered(String permissionId) {
    if (permissionId == null) {
      return false;
    }
    boolean removed = entries.remove(permissionId) != null;
    if (removed) {
      fire();
    }
    return removed;
  }

  /**
   * Drops every question of a session - call on {@code session.deleted}: unanswered asks are void
   * once nobody waits for them (the fleet queue's {@code remove} rule).
   *
   * @return whether anything was removed
   */
  public boolean dropSession(String sessionId) {
    if (sessionId == null) {
      return false;
    }
    boolean removed = entries.values().removeIf(entry -> sessionId.equals(entry.sessionId));
    if (removed) {
      fire();
    }
    return removed;
  }

  /** The pending questions, oldest first (copy-on-read, never {@code null}). */
  public List<PermissionRequest> pending() {
    return entries.values().stream()
        .sorted(Comparator.comparingLong(entry -> entry.sequence))
        .map(entry -> entry.request)
        .toList();
  }

  /**
   * @return how many questions currently await an answer - the badge count of every surface
   */
  public int pendingCount() {
    return entries.size();
  }

  /**
   * The collected list's rows: every pending question, oldest first, attributed against the given
   * session list (the surface's already-loaded sessions; {@code null}-tolerant). Age labels are
   * computed against {@code nowMillis} so the mapping is deterministic in tests.
   */
  public List<Row> rows(List<Session> sessions, long nowMillis) {
    Map<String, Session> byId = byId(sessions);
    return entries.values().stream()
        .sorted(Comparator.comparingLong(entry -> entry.sequence))
        .map(entry -> rowOf(entry.request, byId, entry.askedAtMillis, nowMillis))
        .toList();
  }

  /**
   * The first-seen timestamp of one question, or {@code fallbackMillis} when this registry never
   * saw it (lets {@link SessionQuestions}' projection of a freshly fetched list reuse the feed's
   * older first-seen age when it has one - one question, one age, on every surface).
   */
  public long firstSeenAt(String permissionId, long fallbackMillis) {
    Entry entry = permissionId == null ? null : entries.get(permissionId);
    return entry == null ? fallbackMillis : entry.askedAtMillis;
  }

  /**
   * Builds one attributed row (shared by this registry and {@link SessionQuestions}' subtree
   * projection, so both surfaces label one question identically).
   */
  static Row rowOf(
      PermissionRequest request,
      Map<String, Session> sessionsById,
      long askedAtMillis,
      long nowMillis) {
    Session session = sessionsById.get(request.sessionId());
    Session parent =
        session == null || session.parentID() == null ? null : sessionsById.get(session.parentID());
    boolean subagent = session != null && session.parentID() != null;
    String sessionLabel = session == null ? request.sessionId() : BackgroundModel.name(session);
    String parentLabel = parent == null ? null : BackgroundModel.name(parent);
    return new Row(
        request.sessionId(),
        request.permissionId(),
        request.display(),
        sessionLabel,
        subagent,
        subagent ? session.parentID() : null,
        parentLabel,
        askedAtMillis,
        ServerLabels.relative(askedAtMillis, nowMillis),
        "waiting " + waitingText(nowMillis - askedAtMillis));
  }

  /** Sessions indexed by id (null/blank-id entries skipped). */
  static Map<String, Session> byId(List<Session> sessions) {
    Map<String, Session> byId = new ConcurrentHashMap<>();
    if (sessions != null) {
      for (Session session : sessions) {
        if (session != null && session.id() != null) {
          byId.put(session.id(), session);
        }
      }
    }
    return byId;
  }

  /** The "waiting" duration word: {@code <1m} / {@code 5m} / {@code 3h} / {@code 2d}. */
  private static String waitingText(long ageMillis) {
    long age = Math.max(0L, ageMillis);
    long minutes = age / 60_000L;
    if (minutes < 1) {
      return "<1m";
    }
    if (minutes < 60) {
      return minutes + "m";
    }
    long hours = minutes / 60;
    return hours < 24 ? hours + "h" : (hours / 24) + "d";
  }

  /**
   * The session-row marker while questions wait inside it: {@code ""} when none, the question emoji
   * alone for one, emoji + {@code xN} for several (name-column suffix, U-096's badge).
   */
  public static String marker(int pendingCount) {
    if (pendingCount <= 0) {
      return "";
    }
    return " " + Emojis.QUESTION + (pendingCount == 1 ? "" : "x" + pendingCount);
  }

  /**
   * The Questions category's Details column, e.g. {@code "2 questions waiting \u2022 oldest 5m
   * ago"} ({@code ""} when nothing waits).
   */
  public static String categoryDetail(List<Row> rows) {
    if (rows == null || rows.isEmpty()) {
      return "";
    }
    int count = rows.size();
    return count
        + (count == 1 ? " question waiting" : " questions waiting")
        + "  \u2022  oldest "
        + rows.get(0).ageLabel();
  }

  /** Whether two deliveries of one ask carry the same payload (refresh detection). */
  private static boolean sameAsk(PermissionRequest a, PermissionRequest b) {
    return java.util.Objects.equals(a.permission(), b.permission())
        && java.util.Objects.equals(a.title(), b.title())
        && a.patterns().equals(b.patterns());
  }

  private void fire() {
    for (Runnable listener : listeners) {
      listener.run();
    }
  }
}
