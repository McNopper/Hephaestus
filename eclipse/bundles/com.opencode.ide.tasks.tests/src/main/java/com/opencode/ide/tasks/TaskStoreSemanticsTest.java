package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Semantics-parity suite: every rule the retired Python pm MCP server enforced (claim
 * ordering/null, lax update, board columns, traceability pairing, id minting) must hold one-for-one
 * on the Markdown store - except the status model, which is the owner's 2026-11-20 seven-state pump
 * (no backlogs, no sprints).
 */
public class TaskStoreSemanticsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  private String mkReady(String role, String priority) {
    Task t = store.create("p", TaskStore.CreateSpec.of("t-" + System.nanoTime()));
    store.update("p", t.id, Map.of("role", role, "priority", priority));
    return t.id;
  }

  /**
   * The {@link TaskStore.CreateSpec#of} defaults with an EXPLICIT role (staged fixtures need the
   * stage's own role - T-224's role/stage doctor check).
   */
  private static TaskStore.CreateSpec specWithRole(String title, String role) {
    return new TaskStore.CreateSpec(
        title, "", "task", role, "medium", 0, List.of(), List.of(), "T");
  }

  /**
   * The divided-left-leg + joiner fixture shared by the archive-pool done-evidence flip and the
   * verification-stage flip (both T-242): architecture done WITH its product, the design child on
   * the architecture's basis (the division), the test-design verifier on the architecture's basis
   * (the join-back). Returns the joiner - the ticket both flips then close bare or archive; the
   * left-leg ids stay fixture-internal (neither test names them after the prologue).
   */
  private Task createDividedLeftLegWithVerifier() {
    Task arch = store.create("p", specWithRole("architecture", "architect"), "architecture");
    store.addArtifact("p", arch.id, "doc", "docs/architecture-product.md", "stage product", "dev");
    store.update("p", arch.id, Map.of("status", "done"));
    Task design = store.create("p", specWithRole("design child", "developer"), "design");
    store.update("p", design.id, Map.of("basis", arch.id)); // I1: division to the next stage
    Task verifier = store.create("p", specWithRole("right leg evidence", "tester"), "test-design");
    store.update("p", verifier.id, Map.of("basis", arch.id)); // I3: the join-back
    return verifier;
  }

  /**
   * The archived-sweep fixture shared by the file-stem fork flip (T-226) and the out-of-enum
   * archived twin (T-253): a done "sweep me" ticket, archived quiet with the baseline asserted.
   * Returns the ticket - each caller resolves its file under {@code _archive} itself (the second
   * flip still names the ticket id in its finding messages) and reads the original content for the
   * hand edit.
   */
  private Task archiveQuietSweepTicket() {
    Task t = store.create("p", TaskStore.CreateSpec.of("sweep me"));
    store.update("p", t.id, Map.of("status", "done"));
    store.archive("p", t.id, "wave close");
    assertEquals("baseline: the archived fixture is quiet", List.of(), store.inconsistencies("p"));
    return t;
  }

  @Test
  public void archiveDoneIsChainSafeScopedAndRecencyAware() {
    // chain-protected: a LIVE parent blocks archiving its done child
    Task parent = store.create("p", TaskStore.CreateSpec.of("parent"));
    Task child = store.create("p", TaskStore.CreateSpec.of("child"));
    store.update("p", child.id, Map.of("status", "done", "basis", parent.id));
    // plain history
    Task old = store.create("p", TaskStore.CreateSpec.of("old"));
    store.update("p", old.id, Map.of("status", "done"));
    // recent arc work
    Task recent = store.create("p", TaskStore.CreateSpec.of("recent"));
    store.update("p", recent.id, Map.of("status", "done"));

    // past cutoff: nothing is a candidate -> full skip report, nothing moves
    Map<String, Object> past = store.archiveDone("p", "test", "2000-01-01T00:00:00Z");
    assertEquals(List.of(), past.get("archived"));
    assertTrue(past.get("skipped").toString().contains(recent.id + ":recent"));

    // future cutoff: candidates = every done (recent INCLUDED - it is older
    // than the cutoff); the chain rule still holds
    Map<String, Object> future = store.archiveDone("p", "test", "2999-01-01T00:00:00Z");
    List<String> moved = (List<String>) future.get("archived");
    assertEquals(2, moved.size());
    assertTrue("old history moved: " + moved, moved.contains(old.id));
    assertTrue("recent moves under a future cutoff: " + moved, moved.contains(recent.id));
    String skipped = future.get("skipped").toString();
    assertTrue("chain-needed stays: " + skipped, skipped.contains(child.id + ":chain-needed"));

    // live visibility: the parent remains; both moved tickets are archived
    List<String> live = store.list("p", null, null, null).stream().map(t -> t.id).toList();
    assertTrue(live.contains(parent.id));
    assertFalse(live.contains(old.id));
    assertFalse(live.contains(recent.id));
    List<String> archivedIds = store.archived("p").stream().map(t -> t.id).toList();
    assertTrue("the archive keeps the full record: " + archivedIds, archivedIds.contains(old.id));
    assertTrue(archivedIds.contains(recent.id));

    // loud validation for a malformed cutoff
    assertThrows(TaskStore.Invalid.class, () -> store.archiveDone("p", "test", "not-a-date"));
  }

  @Test
  public void modelOverrideRoundTripsThroughTheStoreAndCodec() {
    Task t = store.create("p", TaskStore.CreateSpec.of("modelled"));
    assertNull("no model by default", t.model);

    store.update("p", t.id, Map.of("model", "prov-x/m7#high"));

    Task reloaded = new TaskStore(tmp.getRoot().toPath().resolve("tasks")).get("p", t.id);
    assertEquals("prov-x/m7#high", reloaded.model);
    assertTrue(
        "frontmatter carries the field",
        TaskFileCodec.write(reloaded).contains("model: prov-x/m7#high"));
    assertEquals(
        "toJson exposes it",
        "prov-x/m7#high",
        reloaded.toJson().getAsJsonPrimitive("model").getAsString());

    store.update("p", t.id, Map.of("model", com.google.gson.JsonNull.INSTANCE));
    assertNull("an explicit null clears the override", store.get("p", t.id).model);
  }

  @Test
  public void createMintsSequentialIdsPerPrefix() {
    Task a = store.create("p", TaskStore.CreateSpec.of("first"));
    Task b = store.create("p", TaskStore.CreateSpec.of("second"));
    assertEquals("T-001", a.id);
    assertEquals("T-002", b.id);
    Task fr =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "third", "", "task", "pm", "low", 1, List.of(), List.of(), "FR"));
    assertEquals("FR-001", fr.id);
    assertEquals("ready", a.status);
    assertEquals(1, a.history.size());
    assertEquals("created", a.history.get(0).action());
    // prefix sanitization: "x y!" -> "xy"
    Task odd =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "odd", "", "task", "pm", "low", 0, List.of(), List.of(), "x y!"));
    assertEquals("xy-001", odd.id);
  }

  @Test
  public void createValidatesTypePriorityRole() {
    try {
      store.create(
          "p", new TaskStore.CreateSpec("t", "", "banana", "dev", "low", 0, null, null, "T"));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("type"));
    }
    try {
      store.create(
          "p", new TaskStore.CreateSpec("t", "", "task", "dev", "urgent", 0, null, null, "T"));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("priority"));
    }
    try {
      store.create("p", new TaskStore.CreateSpec("t", "", "task", "  ", "low", 0, null, null, "T"));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("role"));
    }
  }

  @Test
  public void getMissingFailsWithPmMessage() {
    try {
      store.get("p", "T-999");
      fail("expected NotFound");
    } catch (TaskStore.NotFound expected) {
      assertEquals("ticket T-999 not found in project p", expected.getMessage());
    }
  }

  @Test
  public void listFilters() {
    store.create("p", TaskStore.CreateSpec.of("a"));
    Task b = store.create("p", TaskStore.CreateSpec.of("b"));
    store.update("p", b.id, Map.of("role", "tester"));
    assertEquals(2, store.list("p", null, null, null).size());
    assertEquals(1, store.list("p", "tester", null, null).size());
    assertEquals(2, store.list("p", null, "ready", null).size());
    store.update("p", b.id, Map.of("status", "in-review"));
    assertEquals(1, store.list("p", null, "ready", null).size());
    store.setBlocked("p", b.id, "x", null);
    assertEquals(1, store.list("p", null, null, true).size());
    assertEquals(1, store.list("p", null, null, false).size());
  }

  @Test
  public void updateIsLaxAndProtectsFields() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    int historyBefore = t.history.size();
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("title", "renamed");
    changes.put(
        "status",
        "done"); // entry stays lax (ready -> done); only EXITING terminal is refused (T-225)
    changes.put("type", "spike"); // T-253: the enum gate refuses out-of-enum now (pinned in
    // TaskToolsDispatchTest); the lax path keeps its in-enum write
    changes.put("story_points", 13);
    changes.put("id", "HACKED"); // protected -> dropped
    changes.put("created_at", "2000-01-01"); // protected -> dropped
    changes.put("history", "gone"); // protected -> dropped
    changes.put("comments", "gone"); // protected -> dropped
    changes.put("unknown_field", "x"); // unknown -> dropped
    Task updated = store.update("p", t.id, changes);
    assertEquals("renamed", updated.title);
    assertEquals("done", updated.status);
    assertEquals("spike", updated.type);
    assertEquals(13, updated.storyPoints);
    assertEquals(t.id, updated.id);
    assertEquals(t.createdAt, updated.createdAt);
    assertEquals(
        "updated:title,status,type,story_points", updated.history.get(historyBefore).action());
  }

  @Test
  public void updateValidatesRoleAndStatus() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    try {
      store.update("p", t.id, Map.of("role", " "));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("role"));
    }
    try {
      store.update("p", t.id, Map.of("status", "limbo"));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("status"));
    }
  }

  @Test
  public void updateToDoneClearsBlockedFlagAndBlocker() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    store.setBlocked("p", t.id, "waiting on review", null);
    Task done = store.update("p", t.id, Map.of("status", "done"));
    assertEquals("done", done.status);
    assertFalse(done.isBlocked());
    assertNull(done.blocker);
    assertTrue(done.history.stream().anyMatch(e -> "unblocked (done)".equals(e.action())));
    Task reloaded = new TaskStore(store.root()).get("p", t.id);
    assertFalse("the clear is persisted, not just in-memory", reloaded.isBlocked());
    assertNull(reloaded.blocker);
    // U-067: done is terminal - the state refuses entry (the old
    // "drift heals on the next write" case became structurally impossible)
    TaskStore.Invalid refused =
        assertThrows(TaskStore.Invalid.class, () -> store.setBlocked("p", t.id, "drift", null));
    assertTrue(refused.getMessage().contains("done"));
    assertFalse(store.get("p", t.id).isBlocked());
  }

  /**
   * T-225 (S4): update() enforces no ENTRY graph, but terminal is a one-way door - a
   * done/not-implemented ticket cannot be moved back to a live status (Task.isTerminal: "neither is
   * ever claimable"), the refusal names the ticket and BOTH statuses, and every intentional status
   * change still passes (AC3: blocked -&gt; resume_to, paused -&gt; ready, live -&gt;
   * done/not-implemented) - including the chain-blocked hop, whose terminal verdict lives in
   * resume_to (T-221's deliberate re-open).
   */
  @Test
  public void updateRefusesToExitATerminalStatusButEveryLegalMoveStillPasses() {
    Task t = store.create("p", TaskStore.CreateSpec.of("finished"));
    store.update("p", t.id, Map.of("status", "done"));

    TaskStore.Invalid refused =
        assertThrows(
            TaskStore.Invalid.class, () -> store.update("p", t.id, Map.of("status", "ready")));
    assertTrue("names the ticket: " + refused.getMessage(), refused.getMessage().contains(t.id));
    assertTrue(
        "names the status it left: " + refused.getMessage(),
        refused.getMessage().contains("terminal status 'done'"));
    assertTrue(
        "names the status it wanted: " + refused.getMessage(),
        refused.getMessage().contains("to 'ready'"));
    assertEquals("the refusal leaves the verdict alone", "done", store.get("p", t.id).status);

    Task ni = store.create("p", TaskStore.CreateSpec.of("retired"));
    store.update("p", ni.id, Map.of("status", "not-implemented"));
    assertThrows(
        TaskStore.Invalid.class, () -> store.update("p", ni.id, Map.of("status", "in-progress")));
    assertEquals("not-implemented stays terminal", "not-implemented", store.get("p", ni.id).status);

    // AC3 proof: the intentional status changes are untouched by the guard
    Task blocked = store.create("p", TaskStore.CreateSpec.of("waiting"));
    store.setBlocked("p", blocked.id, "missing information", "tester");
    assertEquals(
        "blocked -> its resume_to (task_clear_blocked, its own verb)",
        "ready",
        store.clearBlocked("p", blocked.id, "tester").status);
    Task parked = store.create("p", TaskStore.CreateSpec.of("parked"));
    store.setPaused("p", parked.id, "maintenance", "shutdown");
    assertEquals(
        "paused -> ready (the auto-resume pump's move)",
        "ready",
        store.update("p", parked.id, Map.of("status", "ready")).status);
    Task live = store.create("p", TaskStore.CreateSpec.of("live"));
    assertEquals(
        "non-terminal -> done stays legal",
        "done",
        store.update("p", live.id, Map.of("status", "done")).status);
    Task stillLive = store.create("p", TaskStore.CreateSpec.of("still live"));
    assertEquals(
        "non-terminal -> not-implemented stays legal",
        "not-implemented",
        store.update("p", stillLive.id, Map.of("status", "not-implemented")).status);

    // the chain walk's deliberate re-open (T-221): the chain-blocked done hop keeps its
    // terminal verdict in resume_to - no bare status write and no blocked-flag retarget
    // may wipe it either (the same exit refusal, verdict-based)
    Task root = store.create("p", TaskStore.CreateSpec.of("chain root"));
    store.update("p", root.id, Map.of("status", "done"));
    Task leaf =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "chain leaf",
                null,
                "task",
                "developer",
                "medium",
                null,
                null,
                null,
                null,
                null,
                null,
                root.id));
    store.setBlocked("p", leaf.id, "missing information", "tester");
    Task chainBlocked = store.get("p", root.id);
    assertEquals("the chain walk re-opened the done hop", "blocked", chainBlocked.status);
    assertEquals("its verdict waits in resume_to", "done", chainBlocked.resumeTo);
    assertThrows(
        TaskStore.Invalid.class, () -> store.update("p", root.id, Map.of("status", "ready")));
    assertThrows(
        TaskStore.Invalid.class,
        () -> store.update("p", root.id, Map.of("blocked", true, "status", "ready")));
    Task afterRefusal = store.get("p", root.id);
    assertEquals("still blocked", "blocked", afterRefusal.status);
    assertEquals("verdict intact after both refusals", "done", afterRefusal.resumeTo);
  }

  @Test
  public void corruptHistoryLineKeepsTicketVisible() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("live incident"));
    store.update("p", t.id, Map.of("status", "in-progress"));
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    String corrupted =
        Files.readString(file)
            .replace(
                "## History\n",
                "## History\n`n{\"ts\":\"2026-09-14T11:00:00.000Z\",\"action\":\"injected\"}\n");
    Files.writeString(file, corrupted);
    Task got = store.get("p", t.id);
    assertEquals(t.id, got.id);
    assertEquals("in-progress", got.status);
    assertEquals(1, got.quarantinedLines.size());
    assertTrue(got.quarantinedLines.get(0).startsWith("History: `n"));
    assertTrue(
        "the ticket stays listed",
        store.list("p", null, null, null).stream().anyMatch(x -> t.id.equals(x.id)));
  }

  @Test
  public void inconsistenciesReportsUnknownStatusAndGoesQuietWhenHealed() throws IOException {
    // owner model 2026-11-20: a status outside the seven-state model is
    // doctor-visible drift (hand edit / legacy file); rewriting it heals the report
    Task hand = store.create("p", TaskStore.CreateSpec.of("hand-edited"));
    Path file = store.root().resolve("p").resolve(hand.id + ".md");
    Files.writeString(file, Files.readString(file).replace("status: ready", "status: limbo"));
    Task clean = store.create("p", TaskStore.CreateSpec.of("clean"));
    store.update("p", clean.id, Map.of("status", "done"));
    List<String> drift = store.inconsistencies("p");
    assertEquals(1, drift.size());
    assertEquals(
        hand.id + ": unknown status 'limbo' (not in " + Task.VALID_STATUSES + ")", drift.get(0));
    Files.writeString(file, Files.readString(file).replace("status: limbo", "status: ready"));
    assertEquals(List.of(), store.inconsistencies("p"));
  }

  @Test
  public void inconsistenciesReportsAnUnparsableTicketFileThatEveryOtherReadSkips()
      throws IOException {
    Task kept = store.create("p", TaskStore.CreateSpec.of("kept"));
    Files.writeString(
        store.root().resolve("p").resolve("X-001.md"), "not a ticket - no frontmatter fence\n");
    assertEquals(
        "list skips the broken file",
        List.of(kept.id),
        store.list("p", null, null, null).stream().map(t -> t.id).toList());
    List<String> drift = store.inconsistencies("p");
    assertEquals("only the doctor makes the skipped file visible", 1, drift.size());
    assertTrue(drift.get(0), drift.get(0).startsWith("X-001.md: unparsable ticket file"));
    assertTrue(drift.get(0), drift.get(0).contains("frontmatter fence"));
  }

  /**
   * T-224 check 1 (rubberduck S2): TaskFileCodec reads {@code stage} with NO VStages validation, so
   * a hand-edited stage silently drops the ticket to NOT_APPLICABLE in readiness while the doctor
   * stayed ok:true. Flip: quiet -> {@code unknown stage} -> quiet after the heal.
   */
  @Test
  public void doctorReportsAnUnknownStageThatSilentlyDropsTheTicketOutOfThePipeline()
      throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("staged"), "implementation");
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    assertTrue(
        "the fixture carries the stage line the hand edit corrupts",
        Files.readString(file).contains("stage: implementation"));
    assertEquals(
        "baseline: a well-formed staged ticket is quiet", List.of(), store.inconsistencies("p"));

    Files.writeString(
        file, Files.readString(file).replace("stage: implementation", "stage: requerments"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the hand edit is the only report: " + drift, 1, drift.size());
    assertEquals(t.id + ": unknown stage 'requerments' (not in VStages)", drift.get(0));

    Files.writeString(
        file, Files.readString(file).replace("stage: requerments", "stage: implementation"));
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-224 check 2 (rubberduck S8): load() keys by t.id with no filename comparison, so editing the
   * id in T-001.md to T-007 forks identity (one record overwrites the other in the sorted load).
   * Flip: quiet -> {@code file stem != frontmatter id} -> quiet after the heal.
   */
  @Test
  public void doctorReportsAFileStemThatDivergesFromItsFrontmatterId() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("fork me"));
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    assertEquals("the fixture is the store's first ticket", "T-001", t.id);
    assertEquals("baseline: stem == id is quiet", List.of(), store.inconsistencies("p"));

    Files.writeString(file, Files.readString(file).replace("id: T-001", "id: T-007"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the fork is reported: " + drift, 1, drift.size());
    assertEquals("T-001.md: file stem 'T-001' != frontmatter id 'T-007'", drift.get(0));

    Files.writeString(file, Files.readString(file).replace("id: T-007", "id: T-001"));
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-224 check 3 (rubberduck S9a): encodeStageTag re-derives the store-owned {@code [stage]} tag
   * only at title/stage writes - the 2026-11-18 heal needed a manual 137-ticket re-derivation (git
   * 09cb97a). Flip: quiet -> {@code stage title-tag drift} -> quiet after the heal.
   */
  @Test
  public void doctorReportsStageTitleTagDriftAndGoesQuietWhenHealed() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("tagged"), "implementation");
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    assertTrue(
        "create encodes the birth tag",
        Files.readString(file).contains("title: [implementation] tagged"));
    assertEquals("baseline: the tag matches the stage", List.of(), store.inconsistencies("p"));

    Files.writeString(
        file, Files.readString(file).replace("title: [implementation] tagged", "title: tagged"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the hand edit is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id
            + ": stage title-tag drift - title 'tagged' does not carry the leading"
            + " [implementation] tag (expected '[implementation] tagged')",
        drift.get(0));

    Files.writeString(
        file, Files.readString(file).replace("title: tagged", "title: [implementation] tagged"));
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-224 check 4 (rubberduck S9b): the store never enforced role == VStages.roleOf(stage), so a
   * wrong role means the stage's role-claim never matches -> READY forever. The documented override
   * mechanism is a non-blank {@code role_override:} frontmatter line ON THE TICKET. Flip: quiet ->
   * {@code role != VStages.roleOf(stage)} -> quiet via the recorded override, and quiet again after
   * the heal (the owning role restored).
   */
  @Test
  public void doctorReportsRoleStageMismatchUnlessTheOverrideIsRecordedOnTheTicket()
      throws IOException {
    Task t = store.create("p", specWithRole("acceptance ticket", "tester"), "test-requirements");
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    String original = Files.readString(file);
    assertEquals("baseline: role == roleOf(stage) is quiet", List.of(), store.inconsistencies("p"));

    Files.writeString(file, original.replace("role: tester", "role: developer"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the mismatch is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id
            + ": role 'developer' != VStages.roleOf(stage) 'tester'"
            + " (stage=test-requirements; if this override is intentional, record a non-blank"
            + " role_override: line on the ticket)",
        drift.get(0));

    // the explicit allow-list mechanism: a non-blank role_override: line recorded on the ticket
    // exempts the intentional mismatch (the codec preserves unknown frontmatter keys verbatim)
    Files.writeString(
        file,
        original
            .replace("role: tester", "role: developer")
            .replace(
                "role: developer",
                "role: developer\n"
                    + "role_override: hand-off agreed with the owner (T-224 fixture)"));
    assertEquals(
        "the override recorded on the ticket is the exemption",
        List.of(),
        store.inconsistencies("p"));

    Files.writeString(file, original);
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-224 check 5 (rubberduck N4): doctor validated only file/path artifact refs - a doc ref to a
   * deleted/renamed ticket stayed silent. Only ticket-id-shaped doc refs are validated, and they
   * resolve live OR archived. Flip: quiet -> {@code doc artifact ref} -> quiet after the heal.
   */
  @Test
  public void doctorReportsADocArtifactRefThatNoLongerResolvesToAnyTicket() {
    Task t = store.create("p", TaskStore.CreateSpec.of("doc refs"));
    store.addArtifact("p", t.id, "doc", t.id, "a ticket-id-shaped ref that resolves", "dev");
    assertEquals("a doc ref to a LIVE ticket is quiet", List.of(), store.inconsistencies("p"));

    // the archived half of "live-or-archived": a swept ticket's id still resolves
    Task swept = store.create("p", TaskStore.CreateSpec.of("swept away"));
    store.update("p", swept.id, Map.of("status", "done"));
    store.archive("p", swept.id, "sweep");
    store.addArtifact("p", t.id, "doc", swept.id, "a ticket-id-shaped ref into the archive", "dev");
    assertEquals("a doc ref to an ARCHIVED ticket is quiet", List.of(), store.inconsistencies("p"));

    store.addArtifact("p", t.id, "doc", "T-999", "points at a deleted ticket", "dev");
    List<String> drift = store.inconsistencies("p");
    assertEquals("the dead ref is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id + ": doc artifact ref 'T-999' does not resolve to a live-or-archived ticket",
        drift.get(0));

    // the heal: drop the dead ref; non-ticket-shaped doc refs (paths, TEMP logs) were never
    // in scope and stay quiet
    store.removeArtifact("p", t.id, 2, "dev");
    store.addArtifact("p", t.id, "doc", "docs/TESTING.md", "a path-shaped doc ref", "dev");
    store.addArtifact("p", t.id, "doc", "C:/Temp/run log.txt", "a TEMP log", "dev");
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-226 (T-224 follow-up): the stem/id check walks BOTH pools now - an id fork parked in {@code
   * _archive/} is live damage (task_get/link/doc-ref resolution READ archived tickets - the forked
   * record can shadow its twin in every archive-backed lookup). The archive findings carry an
   * {@code _archive/} prefix so the pool is identifiable. Flip: quiet -&gt; one prefixed finding
   * -&gt; quiet after the heal.
   */
  @Test
  public void doctorReportsAFileStemForkInsideTheArchivePool() throws IOException {
    Task t = archiveQuietSweepTicket();
    Path archivedFile = store.root().resolve("p").resolve("_archive").resolve(t.id + ".md");
    String original = Files.readString(archivedFile);
    Files.writeString(archivedFile, original.replace("id: T-001", "id: T-007"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the archived fork is the only report: " + drift, 1, drift.size());
    assertEquals("_archive/T-001.md: file stem 'T-001' != frontmatter id 'T-007'", drift.get(0));

    Files.writeString(archivedFile, original);
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-226 (T-224 follow-up): the doc-ref check inspects the WHOLE pool - an archived ticket's own
   * ticket-id-shaped doc refs resolve against live+archive too, so a dangling ref inside {@code
   * _archive/} surfaces instead of waiting for a restore nobody performs. Flip: quiet -&gt; one
   * finding -&gt; quiet after the heal (the archive file's ref dropped by hand, the heal shape a
   * restored ticket would take).
   */
  @Test
  public void doctorReportsADanglingDocRefInsideTheArchivePool() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("carries a dead ref"));
    store.addArtifact("p", t.id, "doc", "T-999", "points at a deleted ticket", "dev");
    store.update("p", t.id, Map.of("status", "done"));
    store.archive("p", t.id, "wave close");

    List<String> drift = store.inconsistencies("p");
    assertEquals("the archived dangling ref is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id + ": doc artifact ref 'T-999' does not resolve to a live-or-archived ticket",
        drift.get(0));

    Path archivedFile = store.root().resolve("p").resolve("_archive").resolve(t.id + ".md");
    String healed =
        Files.readString(archivedFile).replace("\"ref\":\"T-999\"", "\"ref\":\"docs/notes.md\"");
    Files.writeString(archivedFile, healed);
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-242 (AC1/AC2): the done-evidence row rides the ARCHIVE pool too - artifact-less history is
   * damage, not era-typical (T-243 had to backfill exactly that shape out of {@code _archive}:
   * T-190/T-191). Because the write verbs refuse archived ids (T-240), the archived finding names
   * the hand-edit heal path instead of prescribing an impossible {@code task_add_artifact}. Flip:
   * quiet (archived WITH product) -&gt; one archived finding after a hand strip -&gt; quiet when
   * the section is restored.
   */
  @Test
  public void doctorScansTheArchivePoolForDoneTicketsWithoutArtifacts() throws IOException {
    Task verifier = createDividedLeftLegWithVerifier();
    store.addArtifact(
        "p", verifier.id, "doc", "docs/test-design-product.md", "test evidence", "dev");
    store.update("p", verifier.id, Map.of("status", "done"));
    assertEquals("baseline: doctor ok:true", List.of(), store.inconsistencies("p"));

    store.archive("p", verifier.id, "wave close");
    assertEquals(
        "archived WITH its product - still quiet: " + store.inconsistencies("p"),
        List.of(),
        store.inconsistencies("p"));

    // the damage: the artifact section stripped from the ARCHIVED file by hand - the historic
    // shape T-243 found in _archive, which no write verb can produce OR heal there (T-240)
    Path archivedFile = store.root().resolve("p").resolve("_archive").resolve(verifier.id + ".md");
    String original = Files.readString(archivedFile);
    List<String> kept = new ArrayList<>();
    boolean inArtifacts = false;
    for (String line : Files.readAllLines(archivedFile)) {
      if (line.equals("## Artifacts")) {
        inArtifacts = true;
        continue;
      }
      if (inArtifacts && line.startsWith("## ")) {
        inArtifacts = false;
      }
      if (!inArtifacts) {
        kept.add(line);
      }
    }
    Files.write(archivedFile, kept);

    List<String> drift = store.inconsistencies("p");
    assertEquals("the archived product gap is the only report: " + drift, 1, drift.size());
    assertTrue(
        "ticket + stage + status named: " + drift,
        drift.get(0).contains(verifier.id + ": stage=test-design (done) has no recorded artifact"));
    assertTrue(
        "the message names the archived heal path: " + drift,
        drift.get(0).contains("ARCHIVED record"));

    Files.writeString(archivedFile, original);
    assertEquals("restored: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-253 (AC2, the DETECTION half of T-249's class): a hand-edited out-of-enum priority was
   * invisible to the doctor - the codec reads priority with no enum validation, and every write
   * verb refuses the value now (T-249), so no verb could ever surface it. A LIVE row heals the
   * other way - the healing write is an in-enum task_update, and a verb refuses only the
   * out-of-enum value being written, never an allowed one. Flip: quiet -&gt; one finding naming
   * field, value, allowed set and the task_update heal -&gt; quiet again.
   */
  @Test
  public void doctorReportsAnOutOfEnumPriorityAndGoesQuietWhenHealed() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("hand-edited priority"));
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    assertTrue(
        "the fixture carries the priority line the hand edit corrupts",
        Files.readString(file).contains("priority: medium"));
    assertEquals("baseline: an in-enum priority is quiet", List.of(), store.inconsistencies("p"));

    Files.writeString(file, Files.readString(file).replace("priority: medium", "priority: urgent"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the hand edit is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id
            + ": unknown priority 'urgent' (not in [low, medium, high, critical]) - heal by"
            + " task_update priority=<an allowed value> (the stored out-of-enum value is"
            + " replaced with an allowed one), then re-run task_doctor",
        drift.get(0));

    Files.writeString(file, Files.readString(file).replace("priority: urgent", "priority: medium"));
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-253 (AC2): the type twin of the priority row above - same codec blindness (type is read with
   * no validation), same refusal era (T-253's shared gate blocks every write verb now), and the
   * same in-enum task_update heal for a LIVE row. Flip: quiet -&gt; one finding naming field,
   * value, allowed set and the task_update heal -&gt; quiet again.
   */
  @Test
  public void doctorReportsAnOutOfEnumTypeAndGoesQuietWhenHealed() throws IOException {
    Task t = store.create("p", TaskStore.CreateSpec.of("hand-edited type"));
    Path file = store.root().resolve("p").resolve(t.id + ".md");
    assertTrue(
        "the fixture carries the type line the hand edit corrupts",
        Files.readString(file).contains("type: task"));
    assertEquals("baseline: an in-enum type is quiet", List.of(), store.inconsistencies("p"));

    Files.writeString(file, Files.readString(file).replace("type: task", "type: chore"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("the hand edit is the only report: " + drift, 1, drift.size());
    assertEquals(
        t.id
            + ": unknown type 'chore' (not in [story, task, bug, spike]) - heal by task_update"
            + " type=<an allowed value> (the stored out-of-enum value is replaced with an allowed"
            + " one), then re-run task_doctor",
        drift.get(0));

    Files.writeString(file, Files.readString(file).replace("type: chore", "type: task"));
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-253 (AC2 archived twin, in T-242's done-evidence twin style): the enum rows scan BOTH pools -
   * an out-of-enum value was never era-expected in history either, and an archived record is out of
   * every write verb's reach twice (archived ids refused - T-240 - and out-of-enum values refused -
   * T-249/T-253), so the archived message names the hand-edit heal of the archive file. Two flips
   * in one fixture: quiet -&gt; flagged -&gt; quiet for each field.
   */
  @Test
  public void doctorScansTheArchivePoolForOutOfEnumPriorityAndType() throws IOException {
    Task t = archiveQuietSweepTicket();
    Path archivedFile = store.root().resolve("p").resolve("_archive").resolve(t.id + ".md");
    String original = Files.readString(archivedFile);

    Files.writeString(archivedFile, original.replace("priority: medium", "priority: urgent"));
    List<String> priorityDrift = store.inconsistencies("p");
    assertEquals(
        "the archived bad priority is the only report: " + priorityDrift, 1, priorityDrift.size());
    assertEquals(
        t.id
            + ": unknown priority 'urgent' (not in [low, medium, high, critical]) - ARCHIVED"
            + " record: write verbs refuse archived ids (T-240) and out-of-enum values (T-249),"
            + " so heal by the smallest hand edit of the archive file, then re-run task_doctor",
        priorityDrift.get(0));

    Files.writeString(archivedFile, original.replace("type: task", "type: chore"));
    List<String> typeDrift = store.inconsistencies("p");
    assertEquals("the archived bad type is the only report: " + typeDrift, 1, typeDrift.size());
    assertEquals(
        t.id
            + ": unknown type 'chore' (not in [story, task, bug, spike]) - ARCHIVED record: write"
            + " verbs refuse archived ids (T-240) and out-of-enum values (T-253), so heal by the"
            + " smallest hand edit of the archive file, then re-run task_doctor",
        typeDrift.get(0));

    Files.writeString(archivedFile, original);
    assertEquals("restored: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  @Test
  public void readsOnAMissingProjectNeverMaterializeIt() {
    // Review S5: task_doctor/task_list on a typo'd project used to
    // create the directory as a side effect of the read path.
    assertTrue(store.list("ghost", null, null, null).isEmpty());
    assertEquals(List.of(), store.inconsistencies("ghost"));
    try {
      store.get("ghost", "T-001");
      fail("get on a missing project must be NotFound, not an empty success");
    } catch (TaskStore.NotFound expected) {
      // wanted
    }
    assertFalse(
        "reads must not create the project directory",
        java.nio.file.Files.isDirectory(store.root().resolve("ghost")));
  }

  @Test
  public void claimOrdersByPriorityThenCreatedThenReturnsNull() {
    String low = mkReady("developer", "low");
    String high = mkReady("developer", "high");
    String mid = mkReady("developer", "medium");
    Task first = store.claim("p", "developer", null, "agent-a");
    assertEquals(high, first.id);
    assertEquals("in-progress", first.status);
    assertEquals("agent-a", first.assignee);
    Task second = store.claim("p", "developer", "ready", null);
    assertEquals(mid, second.id);
    assertEquals("developer", second.assignee); // by defaults to role
    Task third = store.claim("p", "developer", null, null);
    assertEquals(low, third.id);
    assertNull(
        "nothing left -> null (worker loops stop on this)",
        store.claim("p", "developer", null, null));
  }

  @Test
  public void claimFiltersRoleAndBlockedAndRejectsOtherStatus() {
    mkReady("tester", "high");
    assertNull("role mismatch", store.claim("p", "developer", null, null));
    String id = mkReady("developer", "high");
    store.setBlocked("p", id, "waiting", null);
    assertNull("blocked tickets are not claimable", store.claim("p", "developer", null, null));
    try {
      store.claim("p", "developer", "in-progress", null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("ready"));
    }
  }

  @Test
  public void releaseOnlyFromReadyOrInProgress() {
    String id = mkReady("developer", "high");
    store.release("p", id, null);
    assertEquals("ready", store.get("p", id).status);
    assertNull(store.get("p", id).assignee);
    store.update("p", id, Map.of("status", "in-review"));
    try {
      store.release("p", id, null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("in-review"));
    }
  }

  @Test
  public void todosAddToggleRemoveWithIndexChecks() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    store.addTodo("p", t.id, "one", false, "pm");
    store.addTodo("p", t.id, "two", false, "pm");
    Task after = store.toggleTodo("p", t.id, 1, "pm");
    assertTrue(after.todos.get(1).done());
    assertFalse(after.todos.get(0).done());
    after = store.removeTodo("p", t.id, 0, "pm");
    assertEquals(1, after.todos.size());
    assertEquals("two", after.todos.get(0).text());
    try {
      store.toggleTodo("p", t.id, 9, null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertEquals("todo index 9 out of range (have 1)", expected.getMessage());
    }
  }

  @Test
  public void multilineTodoTextIsRejectedNotCorrupting() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    try {
      store.addTodo("p", t.id, "step\n- [x] injected todo", false, null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("single line"));
    }
    // the file must still load cleanly afterwards
    assertEquals(0, store.get("p", t.id).todos.size());
  }

  @Test
  public void nullListItemIsRejectedNotCorrupting() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    try {
      store.update("p", t.id, Map.of("labels", java.util.Arrays.asList("ok", null)));
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("non-null"));
    }
    assertEquals(0, store.get("p", t.id).labels.size());
  }

  @Test
  public void importMalformedJsonThrowsBeforeAnyWrite() throws IOException {
    try {
      store.importPmJson("fresh", "{not json");
      fail("expected a parse failure");
    } catch (RuntimeException expected) {
      // clean failure, not a half-import
    }
    assertFalse(
        "no project directory may be created for a failed import",
        Files.exists(store.root().resolve("fresh")));
  }

  @Test
  public void artifactKindValidated() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    store.addArtifact("p", t.id, "git", "abc123", "the commit", "dev");
    assertEquals(1, store.get("p", t.id).artifacts.size());
    try {
      store.addArtifact("p", t.id, "sms", "x", "", null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("kind"));
    }
  }

  @Test
  public void artifactRemoveWithIndexChecksAndHistory() {
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    store.addArtifact("p", t.id, "file", "docs/wrong.md", "typo'd ref", "dev");
    store.addArtifact("p", t.id, "git", "abc123", "the commit", "dev");
    Task after = store.removeArtifact("p", t.id, 0, "dev");
    assertEquals("the indexed artifact is gone, the other shifts down", 1, after.artifacts.size());
    assertEquals("abc123", after.artifacts.get(0).ref());
    assertEquals(
        "the correction is history-recorded like every other mutation",
        "artifact removed:file:docs/wrong.md",
        after.history.get(after.history.size() - 1).action());
    assertEquals("persisted, not just in-memory", 1, store.get("p", t.id).artifacts.size());
    try {
      store.removeArtifact("p", t.id, 9, null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertEquals("artifact index 9 out of range (have 1)", expected.getMessage());
    }
    try {
      store.removeArtifact("p", t.id, -1, null);
      fail("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("out of range"));
    }
  }

  @Test
  public void doctorReportsFileAndPathArtifactRefsThatDoNotResolveInTheRepo() throws IOException {
    Path repo = tmp.newFolder("repo").toPath();
    Files.createDirectories(repo.resolve("docs"));
    Files.writeString(repo.resolve("docs").resolve("real.md"), "# real\n");
    // the convention the doctor derives the repo root from: <repo>/.harness/tasks
    TaskStore repoStore = new TaskStore(repo.resolve(".harness").resolve("tasks"));
    Task t = repoStore.create("p", TaskStore.CreateSpec.of("artifacts"));
    repoStore.addArtifact("p", t.id, "file", "docs/real.md", "", "dev");
    repoStore.addArtifact("p", t.id, "path", "docs/missing.md", "", "dev");
    repoStore.addArtifact("p", t.id, "git", "abc123", "", "dev"); // not a path kind
    repoStore.addArtifact("p", t.id, "url", "https://example.com/x", "", "dev"); // not a path kind
    List<String> drift = repoStore.inconsistencies("p");
    assertEquals(
        List.of(
            t.id
                + ": artifact path ref does not resolve under "
                + repo.toAbsolutePath().normalize()
                + ": docs/missing.md"),
        drift);
  }

  @Test
  public void doctorStaysQuietForGlobBraceAndProseArtifactRefs() throws IOException {
    Path repo = tmp.newFolder("repo").toPath();
    TaskStore repoStore = new TaskStore(repo.resolve(".harness").resolve("tasks"));
    Task t = repoStore.create("p", TaskStore.CreateSpec.of("a"));
    repoStore.addArtifact("p", t.id, "path", "docs/*.md", "", "dev");
    repoStore.addArtifact("p", t.id, "path", "eclipse/{bundles,features}/**", "", "dev");
    repoStore.addArtifact("p", t.id, "path", "see the docs folder", "", "dev");
    repoStore.addArtifact("p", t.id, "file", "a.md,b.md", "", "dev");
    repoStore.addArtifact("p", t.id, "file", "docs/vision?.md", "", "dev");
    assertEquals(
        "none of these is a plain path - nothing to report",
        List.of(),
        repoStore.inconsistencies("p"));
  }

  @Test
  public void doctorSkipsArtifactResolutionWhenTheRepoRootIsUnknown() {
    // this fixture's root (<tmp>/tasks) does not match the
    // <repo>/.harness/tasks convention: the check must skip rather than
    // resolve refs against an accidental parent directory
    Task t = store.create("p", TaskStore.CreateSpec.of("a"));
    store.addArtifact("p", t.id, "file", "docs/missing.md", "", "dev");
    assertEquals(List.of(), store.inconsistencies("p"));
  }

  /**
   * U-080: "the architecture was not done" is visible - done definition stage, no product. U-110:
   * the API refuses a bare done (done-time enforcement), so the bare state is written directly as a
   * ticket file - exactly what the doctor exists to catch in hand-edited stores.
   */
  @Test
  public void doctorReportsADoneDefinitionStageWithoutItsProduct() throws Exception {
    Task bare = store.create("p", specWithRole("done but bare", "architect"), "architecture");
    store.addArtifact("p", bare.id, "doc", "docs/architecture-product.md", "stage product", "dev");
    store.update("p", bare.id, Map.of("status", "done"));
    // the legacy bare-done state is produced by hand-stripping the artifact section from the
    // ticket file - exactly the hand-edited-store case the doctor exists to catch
    Path file = store.root().resolve("p").resolve(bare.id + ".md");
    List<String> lines = Files.readAllLines(file);
    List<String> kept = new ArrayList<>();
    boolean inArtifacts = false;
    for (String line : lines) {
      if (line.equals("## Artifacts")) {
        inArtifacts = true;
        continue;
      }
      if (inArtifacts && line.startsWith("## ")) {
        inArtifacts = false;
      }
      if (!inArtifacts) {
        kept.add(line);
      }
    }
    Files.write(file, kept);
    assertEquals(
        "the hand edit recreated the bare-done state",
        "architecture",
        new TaskStore(store.root()).get("p", bare.id).stage);
    Task verified = store.create("p", specWithRole("right leg evidence", "tester"), "test-design");
    // V-walk (2026-11-19): the right leg joins back 1:1 - through the new basis-on-update
    // verb - which also gives `bare` its right-leg joiner. T-242: the doctor scans ALL nine
    // stages now, so the joiner carries its own product - keeping THIS fixture focused on the
    // definition-stage product gap below (the bare test-* case gets its own flip test).
    store.update("p", verified.id, Map.of("basis", bare.id));
    store.addArtifact(
        "p", verified.id, "doc", "docs/test-design-product.md", "test evidence", "dev");
    store.update("p", verified.id, Map.of("status", "done"));
    Task untracked = store.create("p", TaskStore.CreateSpec.of("unstaged done"));
    store.update("p", untracked.id, Map.of("status", "done"));

    List<String> drift = store.inconsistencies("p");

    // With the join in place: what remains is the product gap + the forward-division gap.
    assertEquals("product gap + forward-division gap: " + drift, 2, drift.size());
    assertTrue(
        "the product gap stays precise: " + drift,
        drift.stream()
            .anyMatch(
                s -> s.contains(bare.id + ": stage=architecture (done) has no recorded artifact")));
    assertTrue(
        "the forward-division gap: " + drift,
        drift.stream().anyMatch(s -> s.contains("has no link to the next stage (design)")));
    assertTrue(
        "the joined right leg and its left-leg target stay quiet: " + drift,
        drift.stream().noneMatch(s -> s.contains("not joined") || s.contains("has no basis")));
    store.addArtifact("p", bare.id, "doc", "docs/architecture-product.md", "stage product", "dev");
    assertEquals(
        "recording the product leaves the forward-division gap",
        1,
        store.inconsistencies("p").size());
    store.addComment("p", bare.id, "division SATISFIED (legacy fixture)", "po");
    assertEquals("the SATISFIED comment heals the report", List.of(), store.inconsistencies("p"));
  }

  /**
   * T-242 (AC1): the done-evidence scan covers the TEST-* legs too - the write guard exempts
   * verification stages on purpose, so a bare closed right-leg ticket can only surface here. Flip:
   * quiet -&gt; one precise finding naming ticket + stage + status -&gt; quiet after the evidence
   * is recorded through the API (the AC's synthetic ok:true -&gt; false -&gt; ok:true fixture).
   */
  @Test
  public void doctorFlagsAVerificationStageDoneWithoutItsProductAndQuietsWhenHealed() {
    // baseline: a divided + joined left leg WITH its product, the joiner still open - quiet
    Task verifier = createDividedLeftLegWithVerifier();
    assertEquals("baseline: doctor ok:true", List.of(), store.inconsistencies("p"));

    // the flip: close the right leg BARE - the write guard allows it (verification is exempt),
    // so only the doctor scan can catch it
    store.update("p", verifier.id, Map.of("status", "done"));
    List<String> drift = store.inconsistencies("p");
    assertEquals("exactly the product gap, nothing else: " + drift, 1, drift.size());
    assertTrue(
        "ticket + stage + status named: " + drift,
        drift.get(0).contains(verifier.id + ": stage=test-design (done) has no recorded artifact"));

    // heal through the API (a LIVE id): ok:true again
    store.addArtifact(
        "p", verifier.id, "doc", "docs/test-design-product.md", "test evidence", "dev");
    assertEquals("healed: doctor ok:true again", List.of(), store.inconsistencies("p"));
  }

  @Test
  public void readyQueueSortedByPriorityThenCreated() {
    Task low = store.create("p", TaskStore.CreateSpec.of("low"));
    store.update("p", low.id, Map.of("priority", "low"));
    Task high = store.create("p", TaskStore.CreateSpec.of("high"));
    store.update("p", high.id, Map.of("priority", "high"));
    Task done = store.create("p", TaskStore.CreateSpec.of("done"));
    store.update("p", done.id, Map.of("status", "done"));
    List<Task> ready = store.list("p", null, "ready", null);
    ready.sort(TaskStore.prioritized());
    assertEquals(2, ready.size());
    assertEquals(high.id, ready.get(0).id);
    assertEquals(low.id, ready.get(1).id);
  }

  @Test
  public void boardAlwaysHasAllSevenColumns() {
    String id = mkReady("developer", "high");
    store.claim("p", "developer", null, null);
    Map<String, List<Task>> board = store.board("p");
    assertEquals(Task.VALID_STATUSES, List.copyOf(board.keySet()));
    assertEquals(1, board.get("in-progress").size());
    assertNotNull(id);
  }

  @Test
  public void traceabilityPairsDefinitionAndVerification() {
    Task parent = store.create("p", TaskStore.CreateSpec.of("arch work"));
    store.update("p", parent.id, Map.of("role", "architect"));
    Task test = store.create("p", TaskStore.CreateSpec.of("verifies arch"));
    store.update("p", test.id, Map.of("role", "tester", "basis", parent.id));
    Task orphanV = store.create("p", TaskStore.CreateSpec.of("orphan verification"));
    store.update("p", orphanV.id, Map.of("role", "tester"));

    Map<String, Object> out = store.traceability("p");
    assertEquals(3, out.get("ticket_count"));
    assertEquals(
        "parent has a verification child -> not an orphan",
        List.of(),
        out.get("orphan_definitions"));
    assertEquals(List.of(orphanV.id), out.get("orphan_verifications"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matrix = (List<Map<String, Object>>) out.get("matrix");
    for (Map<String, Object> row : matrix) {
      if (parent.id.equals(row.get("id"))) {
        assertEquals(List.of(test.id), row.get("verified_by"));
      }
      if (test.id.equals(row.get("id"))) {
        assertEquals(parent.id, row.get("verifies"));
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void traceabilityCountsTestArtifactsAndPmDefinitions() {
    // U-110: the wandering journey signal retired with the model - pairing now rides the
    // basis links created at division; the artifact signal is pinned here
    Task withTests = store.create("p", TaskStore.CreateSpec.of("records its tests"));
    store.addArtifact(
        "p",
        withTests.id,
        "file",
        "eclipse/bundles/com.opencode.ide.tasks.tests/src/main/java/com/opencode/ide/tasks/ProbeTest.java",
        "unit probe",
        null);
    // pm requirements are definitions now (D-001), but signals still decide pairing
    Task pmReq = store.create("p", TaskStore.CreateSpec.of("pm requirements"));
    store.update("p", pmReq.id, Map.of("role", "pm"));
    Task bare = store.create("p", TaskStore.CreateSpec.of("no signal at all"));

    Map<String, Object> out = store.traceability("p");
    List<String> orphans = (List<String>) out.get("orphan_definitions");
    assertTrue("test artifact must not orphan: " + orphans, !orphans.contains(withTests.id));
    assertTrue(
        "a pm definition with no signal stays an orphan: " + orphans, orphans.contains(pmReq.id));
    assertTrue(
        "a definition with no signal stays an orphan: " + orphans, orphans.contains(bare.id));

    List<Map<String, Object>> matrix = (List<Map<String, Object>>) out.get("matrix");
    for (Map<String, Object> row : matrix) {
      if (withTests.id.equals(row.get("id"))) {
        assertTrue("artifact row must be self_verified", (Boolean) row.get("self_verified"));
        assertTrue(
            "via must carry the artifact signal: " + row.get("via"),
            ((List<String>) row.get("via")).contains("artifacts"));
      }
    }
  }

  @Test
  public void metaLossRecoversSeqFromFiles() {
    Task a = store.create("p", TaskStore.CreateSpec.of("a"));
    Task b = store.create("p", TaskStore.CreateSpec.of("b"));
    assertEquals("T-002", b.id);
    // simulate a lost sidecar: delete _meta.json, then create again
    try {
      Files.delete(store.root().resolve("p").resolve("_meta.json"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    Task c = store.create("p", TaskStore.CreateSpec.of("c"));
    assertEquals("ids are never reused after meta loss", "T-003", c.id);
    // and the old files survive untouched
    assertEquals(a.id, store.get("p", a.id).id);
  }

  @Test
  public void projectNamesSanitizedAndTraversalRejected() {
    TaskStore.Invalid ex = null;
    try {
      TaskStore.sanitizeProject("..");
    } catch (TaskStore.Invalid e) {
      ex = e;
    }
    assertNotNull(ex);
    assertEquals("a_b", TaskStore.sanitizeProject("a/b"));
    assertEquals("win_path", TaskStore.sanitizeProject("win\\path"));
    // traversal is impossible: separators fold to underscores (".." is rejected, "../escape" folds)
    assertEquals(".._escape", TaskStore.sanitizeProject("../escape"));
    assertEquals(
        0, new TaskStore(tmp.getRoot().toPath()).list(".._escape", null, null, null).size());
  }

  @Test
  public void unparsableFileIsSkippedNotFatal() throws IOException {
    Task good = store.create("p", TaskStore.CreateSpec.of("good"));
    Path dir = store.root().resolve("p");
    Files.writeString(dir.resolve("T-900.md"), "not a task file at all");
    List<Task> all = store.list("p", null, null, null);
    assertEquals(1, all.size());
    assertEquals(good.id, all.get(0).id);
  }

  @Test
  public void persistenceAcrossStoreInstances() {
    Task t =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "persisted",
                "with description",
                "story",
                "tester",
                "critical",
                5,
                List.of("GIVEN x"),
                List.of("l1"),
                "T"));
    TaskStore fresh = new TaskStore(store.root());
    Task back = fresh.get("p", t.id);
    assertEquals("persisted", back.title);
    assertEquals("with description", back.description);
    assertEquals("critical", back.priority);
    assertEquals(List.of("GIVEN x"), back.acceptanceCriteria);
    assertEquals(List.of("l1"), back.labels);
  }
}
