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
