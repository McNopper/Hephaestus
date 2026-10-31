package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskFileCodec;
import com.opencode.ide.tasks.TaskStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Sync-engine behaviour on fixture stores: a TaskStore-written store is clean (and validation
 * writes nothing); a hand-dirtied store (CRLF, counters behind, bad status, duplicate ids, missing
 * meta) yields the right findings; fix mode normalizes exactly the safe things and is idempotent on
 * rerun.
 */
public class StoreSyncTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path root;
  private Path dir;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("store").toPath();
    dir = root.resolve("demo");
    createCleanStore(root);
  }

  private static void createCleanStore(Path root) {
    TaskStore store = new TaskStore(root);
    store.create("demo", TaskStore.CreateSpec.of("First ticket"));
    store.create(
        "demo",
        new TaskStore.CreateSpec(
            "Second ticket",
            "",
            "story",
            "developer",
            "high",
            3,
            List.of("GIVEN a clean store"),
            List.of("ui"),
            null,
            "H1"));
    store.planSprint("demo", null, List.of("T-001", "H1-001"), "Ship the first slice");
    store.update("demo", "H1-001", Map.of("status", "in-progress", "assignee", "agent-7"));
  }

  private void dirtyLineEndings() throws IOException {
    Path t1 = dir.resolve("T-001.md");
    String content = Files.readString(t1, StandardCharsets.UTF_8);
    Files.writeString(t1, content.replace("\n", "\r\n"), StandardCharsets.UTF_8);
  }

  private void dirtyMeta() throws IOException {
    Path meta = dir.resolve("_meta.json");
    JsonObject m =
        JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
    m.getAsJsonObject("seq").addProperty("T", 0);
    m.addProperty("counter", 0);
    m.addProperty("note", "hand-added");
    Files.writeString(meta, m.toString(), StandardCharsets.UTF_8);
  }

  private void writeBrokenTicket() throws IOException {
    Files.writeString(
        dir.resolve("T-099.md"),
        """
                ---
                id: T-099
                title: Broken hand edit
                type: task
                status: bogus
                priority: medium
                role: developer
                story_points: 1
                sprint: S-42
                created_at: 2026-08-01T00:00:00.000Z
                updated_at: 2026-08-01T00:00:00.000Z
                ---

                Dirty hand-written ticket.

                ## Todos
                - [ ] fix me
                """,
        StandardCharsets.UTF_8);
  }

  @Test
  public void cleanStoreHasNoFindingsAndValidationWritesNothing() throws Exception {
    Map<String, byte[]> before = snapshot(dir);
    StoreSync.Result result = new StoreSync(root).run(false);
    assertEquals(1, result.projects().size());
    assertTrue(
        "expected no findings, got: " + result.projects().get(0).findings(),
        result.projects().get(0).findings().isEmpty());
    assertFalse(result.hasUnfixableErrors());
    assertFalse(result.hasFixableFindings());
    snapshotEquals(before, snapshot(dir));
  }

  @Test
  public void detectsDirtyStoreWithoutWriting() throws Exception {
    dirtyLineEndings();
    dirtyMeta();
    writeBrokenTicket();
    Map<String, byte[]> before = snapshot(dir);

    StoreSync.Result result = new StoreSync(root).run(false);
    List<StoreSync.Finding> findings = result.projects().get(0).findings();

    assertTrue(hasFinding(findings, "T-099.md", "invalid status 'bogus'"));
    assertTrue(hasFinding(findings, "T-001.md", "CRLF line endings"));
    assertTrue(hasFinding(findings, "_meta.json", "seq['T']=0"));
    assertTrue(hasFinding(findings, "_meta.json", "counter=0"));
    assertTrue(hasFinding(findings, "T-099.md", "sprint 'S-42'"));
    assertTrue(result.hasUnfixableErrors());
    assertTrue(result.hasFixableFindings());
    assertTrue(result.projects().get(0).appliedFixes().isEmpty());
    assertEquals(before.keySet(), snapshot(dir).keySet());
    snapshotEquals(before, snapshot(dir));
  }

  @Test
  public void fixNormalizesSafeFindingsAndIsIdempotent() throws Exception {
    dirtyLineEndings();
    dirtyMeta();

    StoreSync.Result fixed = new StoreSync(root).run(true);
    assertFalse(fixed.hasUnfixableErrors());
    assertTrue(fixed.hasFixableFindings());
    assertEquals(2, fixed.projects().get(0).appliedFixes().size());

    Path t1 = dir.resolve("T-001.md");
    String content = Files.readString(t1, StandardCharsets.UTF_8);
    assertFalse(content.contains("\r"));
    assertEquals(content, TaskFileCodec.write(TaskFileCodec.read(content)));

    JsonObject meta =
        JsonParser.parseString(Files.readString(dir.resolve("_meta.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(1, meta.getAsJsonObject("seq").get("T").getAsInt());
    assertEquals(1, meta.getAsJsonObject("seq").get("H1").getAsInt());
    assertEquals(1, meta.get("counter").getAsInt());
    assertEquals("hand-added", meta.get("note").getAsString());
    assertEquals(
        "Ship the first slice",
        meta.getAsJsonObject("sprints").getAsJsonObject("S-01").get("goal").getAsString());

    Map<String, byte[]> afterFix = snapshot(dir);
    assertFalse(afterFix.keySet().stream().anyMatch(n -> n.contains(".tmp-")));
    StoreSync.Result again = new StoreSync(root).run(true);
    assertTrue(again.projects().get(0).findings().isEmpty());
    assertTrue(again.projects().get(0).appliedFixes().isEmpty());
    snapshotEquals(afterFix, snapshot(dir));
  }

  @Test
  public void fixLeavesUnfixableFilesUntouched() throws Exception {
    writeBrokenTicket();
    Map<String, byte[]> broken = snapshot(dir);
    new StoreSync(root).run(true);
    assertArrayEquals(broken.get("T-099.md"), snapshot(dir).get("T-099.md"));
    List<StoreSync.Finding> findings = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(hasFinding(findings, "T-099.md", "invalid status 'bogus'"));
  }

  private void writeBogusStageTicket() throws IOException {
    Files.writeString(
        dir.resolve("T-098.md"),
        """
                ---
                id: T-098
                title: Hand-edited stage
                type: task
                status: product-backlog
                priority: medium
                role: developer
                stage: bogon
                story_points: 1
                created_at: 2026-08-01T00:00:00.000Z
                updated_at: 2026-08-01T00:00:00.000Z
                ---

                A hand edit put a bogus V-model stage in the frontmatter.

                ## History
                {"ts":"2026-08-01T00:00:00.000Z","action":"created","by":null}
                """,
        StandardCharsets.UTF_8);
  }

  @Test
  public void stagedTicketsFromTheStorePassTheStageLint() throws Exception {
    TaskStore store = new TaskStore(root);
    Task staged = store.create("demo", TaskStore.CreateSpec.of("Staged ticket"), "design");
    store.update("demo", staged.id, Map.of("status", "in-review"));
    // U-080: a definition-stage exit requires a recorded product
    store.addArtifact(
        "demo", staged.id, "doc", "docs/design-product.md", "stage product", "fixture");
    store.advance("demo", staged.id, "walker");
    StoreSync.Result result = new StoreSync(root).run(false);
    assertTrue(
        "expected no findings, got: " + result.projects().get(0).findings(),
        result.projects().get(0).findings().isEmpty());
    assertFalse(result.hasUnfixableErrors());
  }

  @Test
  public void bogusStageIsAnUnfixableFindingThatFixModeWillNotTouch() throws Exception {
    writeBogusStageTicket();
    // keep seq['T'] ahead of the T-098 stem so the seq bump (a fixable
    // finding) does not fire and the stage error stays the only finding
    Path metaFile = dir.resolve("_meta.json");
    JsonObject meta =
        JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8))
            .getAsJsonObject();
    meta.getAsJsonObject("seq").addProperty("T", 98);
    Files.writeString(metaFile, meta.toString(), StandardCharsets.UTF_8);
    Map<String, byte[]> before = snapshot(dir);

    StoreSync.Result result = new StoreSync(root).run(false);
    List<StoreSync.Finding> findings = result.projects().get(0).findings();
    assertTrue(hasFinding(findings, "T-098.md", "invalid stage 'bogon'"));
    assertTrue(hasFinding(findings, "T-098.md", "(valid: [requirements, system"));
    assertTrue(
        "the stage finding is unfixable: only reported, never auto-fixed",
        findings.stream()
            .filter(f -> f.file().equals("T-098.md"))
            .noneMatch(StoreSync.Finding::fixable));
    assertTrue(result.hasUnfixableErrors());
    // U-091: findings exist here but none is fixable, so Result.hasFixableFindings
    // must see past the non-empty stream to the fixable flag of each finding
    assertFalse(result.hasFixableFindings());

    StoreSync.Result fixed = new StoreSync(root).run(true);
    assertArrayEquals(
        "-Dfix must not rewrite a bogus-stage file",
        before.get("T-098.md"),
        snapshot(dir).get("T-098.md"));
    assertTrue(
        "no applied fix may touch the bogus-stage file",
        fixed.projects().get(0).appliedFixes().stream().noneMatch(a -> a.startsWith("T-098.md")));
    assertTrue(
        "still reported after a fix run",
        hasFinding(
            new StoreSync(root).run(false).projects().get(0).findings(),
            "T-098.md",
            "invalid stage 'bogon'"));
  }

  @Test
  public void absentStageKeyLintsCleanAndFixReEncodeKeepsItStageClean() throws Exception {
    Path h1 = dir.resolve("H1-001.md");
    String raw = Files.readString(h1, StandardCharsets.UTF_8);
    assertTrue(
        "fixture precondition: the store writes an explicit stage line",
        raw.lines().anyMatch(l -> l.equals("stage: null")));
    Files.writeString(h1, raw.replace("stage: null\n", ""), StandardCharsets.UTF_8);

    List<StoreSync.Finding> findings = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(
        "an absent stage is fine (legacy ticket), got: " + findings,
        findings.stream().noneMatch(f -> f.message().contains("stage")));

    new StoreSync(root).run(true);
    String fixed = Files.readString(h1, StandardCharsets.UTF_8);
    assertTrue(
        "the canonical re-encode restores the explicit null stage", fixed.contains("stage: null"));
    List<StoreSync.Finding> after = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(after.isEmpty());
  }

  @Test
  public void flagsDuplicateIdsAndStemMismatch() throws Exception {
    String raw =
        """
                ---
                id: X-002
                title: Mismatched file
                type: task
                status: product-backlog
                priority: medium
                role: developer
                story_points: 1
                created_at: 2026-08-01T00:00:00.000Z
                updated_at: 2026-08-01T00:00:00.000Z
                ---

                Id disagrees with the file name.

                ## History
                {"ts":"2026-08-01T00:00:00.000Z","action":"created","by":null}
                """;
    Files.writeString(dir.resolve("X-001.md"), raw, StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("X-002.md"),
        raw.replace("Mismatched file", "Second copy"),
        StandardCharsets.UTF_8);

    List<StoreSync.Finding> findings = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(hasFinding(findings, "X-001.md", "does not match file name 'X-001'"));
    assertTrue(hasFinding(findings, "X-002.md", "duplicate id 'X-002'"));
    assertTrue(
        "ticket files themselves must have no fixable findings, got: " + findings,
        findings.stream().noneMatch(f -> f.fixable() && !f.file().equals("_meta.json")));
  }

  @Test
  public void recreatesMissingMetaWithRecoveredCounters() throws Exception {
    Files.delete(dir.resolve("_meta.json"));
    List<StoreSync.Finding> findings = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(hasFinding(findings, "_meta.json", "missing _meta.json"));

    new StoreSync(root).run(true);
    Path metaFile = dir.resolve("_meta.json");
    assertTrue(Files.isRegularFile(metaFile));
    JsonObject meta =
        JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(1, meta.getAsJsonObject("seq").get("T").getAsInt());
    assertEquals(1, meta.getAsJsonObject("seq").get("H1").getAsInt());
    assertEquals(1, meta.get("counter").getAsInt());
    assertNotNull(meta.getAsJsonObject("sprints"));

    List<StoreSync.Finding> after = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(after.stream().noneMatch(f -> f.severity() == StoreSync.Severity.ERROR));
    assertTrue(hasFinding(after, "T-001.md", "sprint 'S-01'"));
  }

  @Test
  public void unparsableTicketStillAdvancesTheSeqCounterInFixMode() throws Exception {
    // no frontmatter fence: TaskFileCodec.read throws, the file is unfixable
    Files.writeString(
        dir.resolve("T-099.md"), "this is not a ticket at all\n", StandardCharsets.UTF_8);

    StoreSync.Result checked = new StoreSync(root).run(false);
    assertTrue(hasFinding(checked.projects().get(0).findings(), "T-099.md", "does not parse"));
    assertTrue(checked.hasUnfixableErrors());

    StoreSync.Result fixed = new StoreSync(root).run(true);
    assertTrue(
        fixed.projects().get(0).appliedFixes().toString(),
        fixed.projects().get(0).appliedFixes().stream().anyMatch(a -> a.startsWith("_meta.json")));
    // U-091: the stem of the unparsable file must still bump seq['T'] to 99,
    // or the next created ticket would reuse the T-09x id range
    JsonObject meta =
        JsonParser.parseString(Files.readString(dir.resolve("_meta.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(99, meta.getAsJsonObject("seq").get("T").getAsInt());
    assertArrayEquals(
        "fix mode must not rewrite an unparsable file",
        "this is not a ticket at all\n".getBytes(StandardCharsets.UTF_8),
        snapshot(dir).get("T-099.md"));
  }

  @Test
  public void metaWithoutSprintsKeyLintsClean() throws Exception {
    Files.writeString(
        dir.resolve("_meta.json"),
        "{\"seq\":{\"T\":1,\"H1\":1},\"counter\":1}",
        StandardCharsets.UTF_8);

    StoreSync.Result result = new StoreSync(root).run(false);
    // U-091: tickets reference S-01; with no sprints object at all the sprint
    // scan and the sprint-reference check must both skip, not crash
    assertTrue(
        "expected no findings, got: " + result.projects().get(0).findings(),
        result.projects().get(0).findings().isEmpty());
  }

  @Test
  public void metaWithNonObjectSprintsLintsClean() throws Exception {
    Files.writeString(
        dir.resolve("_meta.json"),
        "{\"seq\":{\"T\":1,\"H1\":1},\"counter\":1,\"sprints\":\"legacy-import\"}",
        StandardCharsets.UTF_8);

    StoreSync.Result result = new StoreSync(root).run(false);
    // U-091: a non-object sprints value is skipped by both the max-sprint scan
    // and the sprint-reference check; neither may coerce it to an object
    assertTrue(
        "expected no findings, got: " + result.projects().get(0).findings(),
        result.projects().get(0).findings().isEmpty());
  }

  @Test
  public void metaWithoutSeqKeyIsRebuiltInFixMode() throws Exception {
    Files.writeString(dir.resolve("_meta.json"), "{\"counter\":1}", StandardCharsets.UTF_8);

    StoreSync.Result checked = new StoreSync(root).run(false);
    assertTrue(hasFinding(checked.projects().get(0).findings(), "_meta.json", "seq['T']=0"));

    new StoreSync(root).run(true);
    JsonObject meta =
        JsonParser.parseString(Files.readString(dir.resolve("_meta.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    // U-091: the rebuilt seq object must actually be attached to the meta
    // that gets written (a dropped add("seq", ...) loses all counters)
    assertEquals(1, meta.getAsJsonObject("seq").get("T").getAsInt());
    assertEquals(1, meta.getAsJsonObject("seq").get("H1").getAsInt());
    assertEquals(1, meta.get("counter").getAsInt());
  }

  @Test
  public void missingTimestampKeysAreFixableFindings() throws Exception {
    writeTicketWithoutTimestamps();

    StoreSync.Result checked = new StoreSync(root).run(false);
    List<StoreSync.Finding> findings = checked.projects().get(0).findings();
    assertTrue(hasFinding(findings, "T-090.md", "missing created_at"));
    assertTrue(hasFinding(findings, "T-090.md", "missing updated_at"));

    StoreSync.Result fixed = new StoreSync(root).run(true);
    assertTrue(
        fixed.projects().get(0).appliedFixes().toString(),
        fixed.projects().get(0).appliedFixes().stream().anyMatch(a -> a.startsWith("T-090.md")));
    String content = Files.readString(dir.resolve("T-090.md"), StandardCharsets.UTF_8);
    assertTrue(content.contains("created_at: "));
    assertTrue(content.contains("updated_at: "));
  }

  @Test
  public void leadingCrLfTicketIsACanonicalizationFinding() throws Exception {
    // a ticket whose very first byte is \r: raw.indexOf('\r') == 0, and
    // stripCr(raw) differs from the codec's canonical form (leading blank line)
    String body =
        """
            ---
            id: T-095
            title: Leading CRLF
            type: task
            status: product-backlog
            priority: medium
            role: developer
            stage: null
            model: null
            story_points: 1
            sprint: S-01
            epic: null
            assignee: null
            resume_to: null
            blocker: null
            labels: []
            acceptance_criteria: []
            created_at: 2026-08-01T00:00:00.000Z
            updated_at: 2026-08-01T00:00:00.000Z
            ---

            The file starts with a CRLF, not with the frontmatter fence.
            """;
    Files.writeString(dir.resolve("T-095.md"), "\r\n" + body, StandardCharsets.UTF_8);

    List<StoreSync.Finding> findings = new StoreSync(root).run(false).projects().get(0).findings();
    assertTrue(hasFinding(findings, "T-095.md", "CRLF line endings"));
    assertTrue(hasFinding(findings, "T-095.md", "not in canonical codec form"));
    assertFalse(
        "the ticket does have created_at, blank first line notwithstanding",
        hasFinding(findings, "T-095.md", "missing created_at"));

    new StoreSync(root).run(true);
    String fixed = Files.readString(dir.resolve("T-095.md"), StandardCharsets.UTF_8);
    assertFalse(fixed.contains("\r"));
    assertTrue(fixed.startsWith("---"));
    List<StoreSync.Finding> after = new StoreSync(root).run(false).projects().get(0).findings();
    assertFalse(hasFinding(after, "T-095.md", "CRLF line endings"));
    assertFalse(hasFinding(after, "T-095.md", "not in canonical codec form"));
  }

  // U-091 equivalent-mutant notes (PIT survivors that need no test):
  //
  // - syncProject removed-call `toWrite.add("seq", new JsonObject())` and
  //   `toWrite.addProperty("counter", 0)` in the recreate-missing-meta branch:
  //   the metaObject()/intOrZero() repair path a few lines below re-adds both
  //   keys with the same values, so the written JSON is semantically identical;
  //   only the key insertion order inside a freshly synthesized file differs.
  //
  // - hasFrontmatterKey: the blank-skip while-loop bound (i < lines.length),
  //   the opening-fence guard (i >= lines.length) with its `return false`, and
  //   the closing-fence for-loop bound. The method runs only on tickets that
  //   TaskFileCodec parsed, and a parseable file always has its opening '---'
  //   fence in bounds and a closing fence before EOF, so every one of those
  //   branches is unreachable for every reachable input (the leading-blank-line
  //   and no-timestamp tests above cover the reachable outcomes of all other
  //   branches in the method).

  private void writeTicketWithoutTimestamps() throws IOException {
    Files.writeString(
        dir.resolve("T-090.md"),
        """
                ---
                id: T-090
                title: No timestamps
                type: task
                status: product-backlog
                priority: medium
                role: developer
                story_points: 1
                created_by_hand: yes
                ---

                A hand-written ticket that never got created_at/updated_at keys.

                ## History
                {"ts":"2026-08-01T00:00:00.000Z","action":"created","by":null}
                """,
        StandardCharsets.UTF_8);
  }

  private static boolean hasFinding(
      List<StoreSync.Finding> findings, String file, String fragment) {
    return findings.stream().anyMatch(f -> f.file().equals(file) && f.message().contains(fragment));
  }

  private static Map<String, byte[]> snapshot(Path dir) throws IOException {
    Map<String, byte[]> out = new TreeMap<>();
    try (var stream = Files.walk(dir)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              p -> {
                try {
                  out.put(dir.relativize(p).toString(), Files.readAllBytes(p));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    return out;
  }

  private static void snapshotEquals(Map<String, byte[]> expected, Map<String, byte[]> actual) {
    assertEquals(expected.keySet(), actual.keySet());
    for (String name : expected.keySet()) {
      assertArrayEquals("file changed: " + name, expected.get(name), actual.get(name));
    }
  }
}
