package com.opencode.ide.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One task (historically: ticket) of the {@link TaskStore}. A mutable POJO whose serialized JSON
 * shape mirrors the retired Python {@code pm} MCP server field-for-field (snake_case, same key
 * order) so agent-side skills and prose that read tool results keep working unchanged.
 *
 * <p>Instances are only mutated inside {@link TaskStore} transactions; every mutation bumps {@code
 * updated_at} and (where the pm server did so) appends a history event.
 */
public final class Task {

  /** Task types accepted by create/update (mirrors the pm server's VALID_TYPES). */
  public static final List<String> VALID_TYPES = List.of("story", "task", "bug", "spike");

  /**
   * The status machine (owner model 2026-11-20): NO backlogs - a ticket is born {@code ready} (or
   * {@code paused} while its basis is open), the ticker claims it into {@code in-progress}, verify
   * hands to {@code in-review}, the engine accepts into {@code done} (the only divide state) or
   * {@code not-implemented} (task complete, feature deliberately not built); {@code blocked} is a
   * STATE that escalates up the basis chain to the requirement, {@code paused} is the dependency
   * wait (U-067: {@link #resumeTo} remembers where clearing returns it). Update is deliberately lax
   * (any of these, no transition graph), as in the pm server. This order is the Board's column
   * order.
   */
  public static final List<String> VALID_STATUSES =
      List.of("ready", "in-progress", "in-review", "done", "not-implemented", "paused", "blocked");

  /** Priority weight for claim/backlog ordering (higher = first). */
  public static final Map<String, Integer> PRIORITY_ORDER =
      Map.of("low", 0, "medium", 1, "high", 2, "critical", 3);

  /** Definition-side roles (traceability pairs these with VERIFICATION_ROLES). */
  public static final Set<String> DEFINITION_ROLES = Set.of("architect", "developer", "pm");

  /** Verification-side roles. */
  public static final Set<String> VERIFICATION_ROLES = Set.of("tester");

  /**
   * Pinned timestamp format: UTC, millisecond precision, always {@code Z}. Lexicographic order
   * equals chronological order, which the claim/backlog sorts rely on when files are merged from
   * mixed writers.
   */
  public static final DateTimeFormatter TS_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** A checklist item. */
  public record Todo(String text, boolean done) {}

  /** The hand-off locator: where a produced artifact lives. */
  public record Artifact(String kind, String ref, String note, String by, Instant ts) {}

  /** A human/agent note on a task. */
  public record Comment(Instant ts, String by, String text) {}

  /** One append-only history event. */
  public record HistoryEvent(Instant ts, String action, String by) {}

  public String id;
  public String title = "";
  public String description = "";
  public String type = "task";

  /**
   * Born {@code ready} (or {@code paused} while the basis is open) - see {@link #VALID_STATUSES}.
   */
  public String status = "ready";

  /**
   * U-067: the status a {@code clear-blocked} returns to - only meaningful while {@code status ==
   * "blocked"}; {@code null} defaults the resume to {@code ready}.
   */
  public String resumeTo;

  public String blocker;
  public int storyPoints;
  public String priority = "medium";
  public String role = "developer";

  /** V-model pipeline stage (see {@link VStages}); {@code null} = legacy/untracked ticket. */
  public String stage;

  /**
   * Optional fleet model override ({@code provider/modelId[#variant]}); {@code null} = server
   * default.
   */
  public String model;

  public String assignee;

  /**
   * U-110: the BASIS work product this ticket derives from (the upstream ticket whose completion
   * spawned it) - the derivation edge of the linked stage pipeline. {@code null} for entry-stage
   * tickets (requirements) and legacy tickets. Readiness waits on the basis; traceability reports
   * the chain.
   */
  public String basis;

  public List<String> labels = new ArrayList<>();
  public List<String> acceptanceCriteria = new ArrayList<>();
  public List<Todo> todos = new ArrayList<>();
  public List<Artifact> artifacts = new ArrayList<>();
  public List<Comment> comments = new ArrayList<>();
  public List<HistoryEvent> history = new ArrayList<>();
  public Instant createdAt;
  public Instant updatedAt;

  /**
   * T-155: who must verdict this ticket - {@code human} (the OWNER's verdict is required; only
   * allowed on the first stage, the last stage, or a stageless ticket, owner rule 2026-11-17) or
   * {@code ai} (the engine review pass). {@code null} = ai = today's behavior - every existing
   * ticket stays valid. Validated at the verbs (create/update), never sanitized.
   */
  public String review;

  /**
   * D-003 option B: when the ticket's STRUCTURED inputs last changed - bumped only by writes to
   * {@code status}/{@code stage}/{@code basis}/{@code role}, to the artifact list, and by the
   * claim/plan status moves. Comments and bookkeeping never touch it (the W-006 incident: a
   * bookkeeping write on an upstream must not stale the downstream's completed work). {@code null}
   * on legacy rows - they fall back to {@link #updatedAt} via {@link #inputsEffectiveAt()} until
   * their first structured write.
   */
  public Instant inputsChangedAt;

  /**
   * The timestamp STALE comparisons use (D-003 option B): the structured-input stamp when present,
   * else {@link #updatedAt} - legacy rows keep their pre-stamp behavior until the first structured
   * write, per side independently.
   */
  public Instant inputsEffectiveAt() {
    return inputsChangedAt != null ? inputsChangedAt : updatedAt;
  }

  /** Unknown frontmatter keys, preserved verbatim across rewrite round-trips. */
  public Map<String, String> extraFrontmatter = new LinkedHashMap<>();

  /** Unknown {@code ## X} body sections, preserved verbatim (raw lines incl. heading). */
  public List<String> extraSections = new ArrayList<>();

  /**
   * Malformed record-section lines (Artifacts/Comments/History) quarantined at parse time, as
   * {@code "<Section>: <raw line>"}. Never written back: the next store rewrite of the ticket drops
   * them (the repair). Surfaced in {@link #toJson} only while non-empty so a corrupt line never
   * hides the ticket and remains visible for hand repair.
   */
  public List<String> quarantinedLines = new ArrayList<>();

  /** Formats an instant with the pinned store format; null-safe. */
  public static String formatTs(Instant t) {
    return t == null ? null : TS_FORMAT.format(t);
  }

  /** Appends a history event with the current time (millisecond precision, like the store). */
  public void history(String action, String by) {
    history.add(
        new HistoryEvent(
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), action, by));
  }

  /**
   * The blocked STATE (U-067): blocked is a status, not a flag - one state machine, nothing to keep
   * in sync. {@code true} iff the ticket sits in {@code status == "blocked"}.
   */
  public boolean isBlocked() {
    return "blocked".equals(status);
  }

  /**
   * The terminal outcomes (owner model 2026-11-20): {@code done} (built + verified) and {@code
   * not-implemented} (task complete, feature deliberately not built - a done state, per the owner).
   * Neither divides, readiness reports NOT_APPLICABLE for both, neither is ever claimable or
   * blockable.
   */
  public boolean isTerminal() {
    return "done".equals(status) || "not-implemented".equals(status);
  }

  /**
   * U-067: the ONE door into the blocked state - remembers where clearing returns (the status at
   * entry) and records the reason. Re-entering an already-blocked ticket only updates the reason.
   */
  public void enterBlocked(String reason) {
    if (!isBlocked()) {
      resumeTo = status;
      status = "blocked";
    }
    blocker = reason;
  }

  /**
   * U-067: the ONE door out of the blocked state - returns to {@code resumeTo} (default {@code
   * ready} when unset or no longer valid) and forgets the reason.
   */
  public void leaveBlocked() {
    if (isBlocked()) {
      status =
          resumeTo != null && VALID_STATUSES.contains(resumeTo) && !"blocked".equals(resumeTo)
              ? resumeTo
              : "ready";
    }
    resumeTo = null;
    blocker = null;
  }

  /** Serializes with the pm server's ticket field names and order (nulls included). */
  public JsonObject toJson() {
    JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("title", title);
    o.addProperty("description", description);
    o.addProperty("type", type);
    o.addProperty("status", status);
    o.addProperty("blocked", isBlocked());
    o.addProperty("resume_to", resumeTo);
    o.addProperty("review", review);
    o.addProperty("blocker", blocker);
    o.addProperty("story_points", storyPoints);
    o.addProperty("priority", priority);
    o.addProperty("role", role);
    o.addProperty("stage", stage);
    o.addProperty("model", model);
    o.addProperty("assignee", assignee);
    JsonArray ac = new JsonArray();
    acceptanceCriteria.forEach(ac::add);
    o.add("acceptance_criteria", ac);
    JsonArray lb = new JsonArray();
    labels.forEach(lb::add);
    o.add("labels", lb);

    o.addProperty("basis", basis);
    o.add("artifacts", artifactsJson());
    o.add("todos", todosJson());
    o.addProperty("created_at", formatTs(createdAt));
    o.addProperty("updated_at", formatTs(updatedAt));
    if (inputsChangedAt != null) {
      o.addProperty("inputs_changed_at", formatTs(inputsChangedAt));
    }
    o.add("history", historyJson());
    o.add("comments", commentsJson());
    if (!quarantinedLines.isEmpty()) {
      JsonArray q = new JsonArray();
      quarantinedLines.forEach(q::add);
      o.add("quarantined", q);
    }
    return o;
  }

  private JsonArray artifactsJson() {
    JsonArray a = new JsonArray();
    for (Artifact art : artifacts) {
      JsonObject o = new JsonObject();
      o.addProperty("kind", art.kind());
      o.addProperty("ref", art.ref());
      o.addProperty("note", art.note());
      o.addProperty("by", art.by());
      o.addProperty("ts", formatTs(art.ts()));
      a.add(o);
    }
    return a;
  }

  private JsonArray todosJson() {
    JsonArray a = new JsonArray();
    for (Todo todo : todos) {
      JsonObject o = new JsonObject();
      o.addProperty("text", todo.text());
      o.addProperty("done", todo.done());
      a.add(o);
    }
    return a;
  }

  private JsonArray historyJson() {
    JsonArray a = new JsonArray();
    for (HistoryEvent e : history) {
      JsonObject o = new JsonObject();
      o.addProperty("ts", formatTs(e.ts()));
      o.addProperty("action", e.action());
      o.add("by", e.by() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(e.by()));
      a.add(o);
    }
    return a;
  }

  private JsonArray commentsJson() {
    JsonArray a = new JsonArray();
    for (Comment c : comments) {
      JsonObject o = new JsonObject();
      o.addProperty("ts", formatTs(c.ts()));
      o.addProperty("by", c.by());
      o.addProperty("text", c.text());
      a.add(o);
    }
    return a;
  }
}
