package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tasks.TaskStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Drives the two mojos directly (U-091): plain instantiation with a recording {@link Log} and
 * reflective field injection replaces the Maven plugin test harness the module deliberately does
 * not carry. Covers the full execute() decision tree: store resolution, the not-a-directory guard,
 * empty stores, fix/strict escalation, unfixable failure, sprint warnings and the counters quoted
 * in the final consistency line.
 */
public class MojosTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path root;
  private Path dir;
  private RecordingLog log;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("store").toPath();
    dir = root.resolve("demo");
    TaskStore store = new TaskStore(root);
    store.create("demo", TaskStore.CreateSpec.of("First ticket"));
    store.create("demo", TaskStore.CreateSpec.of("Second ticket"));
    store.planSprint("demo", null, List.of("T-001", "T-002"), "Ship the mojo slice");
    log = new RecordingLog();
  }

  // -- PlanMojo -------------------------------------------------------------

  @Test
  public void planWritesBoardFilesAndLogsTheRender() throws Exception {
    Path out = tmp.newFolder("out").toPath();
    PlanMojo mojo = plan(root, out);

    mojo.execute();

    String md = Files.readString(out.resolve("board.md"), StandardCharsets.UTF_8);
    String html = Files.readString(out.resolve("board.html"), StandardCharsets.UTF_8);
    assertTrue(md.contains("# opencode-tasks board"));
    assertTrue(md.contains("Ship the mojo slice"));
    assertTrue(html.startsWith("<!DOCTYPE html>"));
    assertTrue(html.contains("Ship the mojo slice"));
    assertTrue(log.infos.toString().contains("Rendered 1 project board(s)"));
  }

  @Test
  public void planWithoutStoreLogsAndReturns() throws Exception {
    Path isolated = tmp.newFolder("isolated").toPath();
    PlanMojo mojo = plan(null, tmp.newFolder("unused-out").toPath());
    set(mojo, "basedir", isolated.toFile());

    mojo.execute();

    assertTrue(log.infos.toString().contains("No .opencode/tasks store found walking up"));
  }

  @Test
  public void planWithFileAsRootFails() throws Exception {
    Path file = tmp.getRoot().toPath().resolve("plain.txt");
    Files.writeString(file, "not a directory");

    MojoExecutionException e =
        assertThrows(MojoExecutionException.class, () -> plan(file, null).execute());
    assertTrue(e.getMessage().contains("not a directory"));
  }

  @Test
  public void planWithEmptyStoreLogsNothingToRender() throws Exception {
    Path empty = tmp.newFolder("empty-store").toPath();

    plan(empty, tmp.newFolder("unused-out").toPath()).execute();

    assertTrue(log.infos.toString().contains("No project directories with task files found under"));
    assertTrue(log.infos.toString().contains("nothing to render"));
  }

  // -- SyncMojo -------------------------------------------------------------

  @Test
  public void syncCleanStoreIsConsistent() throws Exception {
    sync(root, false, false).execute();

    String infos = log.infos.toString();
    assertTrue(infos.contains("Syncing opencode task store at "));
    assertFalse("no (fix on) marker when fix is off", infos.contains("(fix on)"));
    assertFalse("no (strict) marker when strict is off", infos.contains("(strict)"));
    assertTrue(infos.contains("Project 'demo': 2 task file(s) checked"));
    assertTrue(infos.contains("is consistent (0 fixable finding(s), 0 warning(s))"));
    assertTrue(log.warns.isEmpty());
    assertTrue(log.errors.isEmpty());
  }

  @Test
  public void syncLogsFixableFindingsAsWarnings() throws Exception {
    crlf("T-001.md");

    sync(root, false, false).execute();

    assertTrue(log.infos.toString().contains("is consistent (1 fixable finding(s), 1 warning(s))"));
    String warns = log.warns.toString();
    assertTrue(warns.contains("CRLF line endings"));
    assertTrue(warns.contains("(fixable with -Dopencode.tasks.fix=true)"));
    assertTrue(log.errors.isEmpty());
  }

  @Test
  public void syncFixModeAppliesFixesAndLogsThem() throws Exception {
    crlf("T-001.md");

    sync(root, true, false).execute();

    String infos = log.infos.toString();
    assertTrue("marker for fix mode", infos.contains("(fix on)"));
    // both the appliedFixes listing and the per-finding fixed line are logged
    assertTrue(infos.contains("[fixed] demo/T-001.md: re-encoded to canonical LF form"));
    assertTrue(infos.contains("[fixed] demo/T-001.md: CRLF line endings"));
    assertTrue(infos.contains("is consistent (1 fixable finding(s), 0 warning(s))"));
    assertFalse(Files.readString(dir.resolve("T-001.md"), StandardCharsets.UTF_8).contains("\r"));
  }

  @Test
  public void syncStrictOnCleanStoreDoesNotFail() throws Exception {
    // strict only escalates when a fixable finding exists; a clean store must
    // pass (pins the fixables > 0 boundary of the strict throw)
    sync(root, false, true).execute();

    String infos = log.infos.toString();
    assertTrue("marker for strict mode", infos.contains("(strict)"));
    assertTrue(infos.contains("is consistent (0 fixable finding(s), 0 warning(s))"));
    assertTrue(log.errors.isEmpty());
  }

  @Test
  public void syncStrictFailsOnFixableFindings() throws Exception {
    crlf("T-001.md");

    MojoExecutionException e =
        assertThrows(MojoExecutionException.class, () -> sync(root, false, true).execute());
    assertTrue(e.getMessage().contains("(strict) found 1 fixable problem(s)"));
    assertTrue("marker for strict mode", log.infos.toString().contains("(strict)"));
    assertTrue(log.errors.toString().contains("(fixable; run with -Dopencode.tasks.fix=true)"));
  }

  @Test
  public void syncFailsOnUnfixableFindings() throws Exception {
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
                """,
        StandardCharsets.UTF_8);

    MojoExecutionException e =
        assertThrows(MojoExecutionException.class, () -> sync(root, false, false).execute());
    assertTrue(e.getMessage().contains("found 1 unfixable problem(s)"));
    assertTrue(log.errors.toString().contains("invalid status 'bogus'"));
    // the sprint reference of the broken ticket is a warning next to the error
    assertTrue(log.warns.toString().contains("sprint 'S-42'"));
  }

  @Test
  public void syncSprintWarningIsCountedButDoesNotFail() throws Exception {
    // keep the counters ahead of the new ticket so the sprint warning is the
    // only finding (SPRINT-X is not S-<digits>, so it does not move counter)
    Path metaFile = dir.resolve("_meta.json");
    JsonObject meta =
        JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8))
            .getAsJsonObject();
    meta.getAsJsonObject("seq").addProperty("T", 3);
    Files.writeString(metaFile, meta.toString(), StandardCharsets.UTF_8);
    writeRawTicket(dir, "T-003", "Sprint warning ticket", "SPRINT-X");

    sync(root, false, false).execute();

    assertTrue(log.warns.toString().contains("sprint 'SPRINT-X'"));
    assertTrue(log.infos.toString().contains("is consistent (0 fixable finding(s), 1 warning(s))"));
    assertTrue(log.errors.isEmpty());
  }

  @Test
  public void syncWithoutStoreLogsAndReturns() throws Exception {
    Path isolated = tmp.newFolder("isolated").toPath();
    SyncMojo mojo = sync(null, false, false);
    set(mojo, "basedir", isolated.toFile());

    mojo.execute();

    assertTrue(log.infos.toString().contains("No .opencode/tasks store found walking up"));
  }

  @Test
  public void syncWithFileAsRootFails() throws Exception {
    Path file = tmp.getRoot().toPath().resolve("plain.txt");
    Files.writeString(file, "not a directory");

    MojoExecutionException e =
        assertThrows(MojoExecutionException.class, () -> sync(file, false, false).execute());
    assertTrue(e.getMessage().contains("not a directory"));
  }

  @Test
  public void syncWithEmptyStoreLogsNothingToRender() throws Exception {
    Path empty = tmp.newFolder("empty-store").toPath();

    sync(empty, false, false).execute();

    assertTrue(log.infos.toString().contains("No project directories with task files found under"));
    assertFalse(log.infos.toString().contains("is consistent"));
  }

  // -- helpers --------------------------------------------------------------

  private PlanMojo plan(Path tasksRoot, Path outDir) {
    PlanMojo mojo = new PlanMojo();
    mojo.setLog(log);
    set(mojo, "tasksRoot", tasksRoot == null ? null : tasksRoot.toFile());
    set(mojo, "outDir", outDir == null ? null : outDir.toFile());
    set(mojo, "basedir", tmp.getRoot());
    return mojo;
  }

  private SyncMojo sync(Path tasksRoot, boolean fix, boolean strict) {
    SyncMojo mojo = new SyncMojo();
    mojo.setLog(log);
    set(mojo, "tasksRoot", tasksRoot == null ? null : tasksRoot.toFile());
    set(mojo, "fix", fix);
    set(mojo, "strict", strict);
    set(mojo, "basedir", tmp.getRoot());
    return mojo;
  }

  private static void set(Object mojo, String field, Object value) {
    try {
      java.lang.reflect.Field f = mojo.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(mojo, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot inject " + field, e);
    }
  }

  private void crlf(String name) throws IOException {
    Path file = dir.resolve(name);
    String content = Files.readString(file, StandardCharsets.UTF_8);
    Files.writeString(file, content.replace("\n", "\r\n"), StandardCharsets.UTF_8);
  }

  private static void writeRawTicket(Path dir, String id, String title, String sprint)
      throws IOException {
    Files.writeString(
        dir.resolve(id + ".md"),
        "---\n"
            + "id: "
            + id
            + "\n"
            + "title: "
            + title
            + "\n"
            + "type: task\n"
            + "status: product-backlog\n"
            + "priority: medium\n"
            + "role: developer\n"
            + "stage: null\n"
            + "model: null\n"
            + "story_points: 0\n"
            + "sprint: "
            + sprint
            + "\n"
            + "epic: null\n"
            + "assignee: null\n"
            + "resume_to: null\n"
            + "blocker: null\n"
            + "labels: []\n"
            + "acceptance_criteria: []\n"
            + "created_at: 2026-08-01T00:00:00.000Z\n"
            + "updated_at: 2026-08-01T00:00:00.000Z\n"
            + "---\n\n"
            + title
            + "\n",
        StandardCharsets.UTF_8);
  }

  /** In-memory Log so the tests can pin what the mojos report. */
  static final class RecordingLog implements Log {
    final List<String> infos = new ArrayList<>();
    final List<String> warns = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> debugs = new ArrayList<>();

    @Override
    public boolean isDebugEnabled() {
      return true;
    }

    @Override
    public void debug(CharSequence content) {
      debugs.add(String.valueOf(content));
    }

    @Override
    public void debug(Throwable error) {
      debugs.add("EXCEPTION: " + error);
    }

    @Override
    public void debug(CharSequence content, Throwable error) {
      debugs.add(content + " EXCEPTION: " + error);
    }

    @Override
    public boolean isInfoEnabled() {
      return true;
    }

    @Override
    public void info(CharSequence content) {
      infos.add(String.valueOf(content));
    }

    @Override
    public void info(Throwable error) {
      infos.add("EXCEPTION: " + error);
    }

    @Override
    public void info(CharSequence content, Throwable error) {
      infos.add(content + " EXCEPTION: " + error);
    }

    @Override
    public boolean isWarnEnabled() {
      return true;
    }

    @Override
    public void warn(CharSequence content) {
      warns.add(String.valueOf(content));
    }

    @Override
    public void warn(Throwable error) {
      warns.add("EXCEPTION: " + error);
    }

    @Override
    public void warn(CharSequence content, Throwable error) {
      warns.add(content + " EXCEPTION: " + error);
    }

    @Override
    public boolean isErrorEnabled() {
      return true;
    }

    @Override
    public void error(CharSequence content) {
      errors.add(String.valueOf(content));
    }

    @Override
    public void error(Throwable error) {
      errors.add("EXCEPTION: " + error);
    }

    @Override
    public void error(CharSequence content, Throwable error) {
      errors.add(content + " EXCEPTION: " + error);
    }
  }
}
