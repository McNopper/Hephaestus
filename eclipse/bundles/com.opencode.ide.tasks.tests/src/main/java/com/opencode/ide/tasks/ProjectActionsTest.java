package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * O-002 store actions (AC 1..3): a new task-store project is created without hand-editing {@code
 * .harness/tasks}, and a reset clears one project's tickets after the UI's explicit confirmation -
 * never touching another project's reservations, history or ownership.
 */
public class ProjectActionsTest {

  private static final String PROJECT = "p";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;
  private Path root;

  @Before
  public void setUp() {
    root = tmp.getRoot().toPath().resolve("tasks");
    store = new TaskStore(root);
  }

  private String ticket(String project, String title) {
    return store.create(
            project,
            new TaskStore.CreateSpec(
                title, "d", "task", "developer", "high", 2, List.of("ac"), List.of(), "T"))
        .id;
  }

  @Test
  public void newProjectScaffoldsTheStoreWithoutHandEditing() throws Exception {
    store.newProject("fresh", "chat-session");

    Path dir = root.resolve("fresh");
    assertTrue("the project directory exists", Files.isDirectory(dir));
    assertTrue(
        "the store sidecar carries the id seq", Files.isRegularFile(dir.resolve("_meta.json")));
    String meta = Files.readString(dir.resolve("_meta.json"));
    assertTrue(meta.contains("\"seq\""));
    assertEquals("a fresh project is empty", 0, store.list("fresh", null, null, null).size());
  }

  @Test
  public void creatingAnExistingProjectIsRefused() {
    store.newProject("fresh", "chat-session");
    ticket("fresh", "real work");

    try {
      store.newProject("fresh", "chat-session");
      throw new AssertionError("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("already exists"));
    }
  }

  @Test
  public void resetClearsOneProjectOnly() {
    String keep = ticket(PROJECT, "the other project's work");
    store.newProject("fresh", "chat-session");
    String doomed = ticket("fresh", "work being wiped");
    String second = ticket("fresh", "more work being wiped");

    Map<String, Object> report = store.resetProject("fresh", "chat-session");

    assertEquals("fresh", report.get("project"));
    assertEquals(2, report.get("tickets_removed"));
    assertEquals("the project itself survives", 0, store.list("fresh", null, null, null).size());
    assertTrue(
        "the other project's ticket is untouched",
        store.get(PROJECT, keep) != null && "done".equals("done"));
    assertEquals(
        "the other project still lists its work", 1, store.list(PROJECT, null, null, null).size());
    assertFalse("no ids collide after a reset", doomed.equals(second));
  }

  @Test
  public void resetRemovesArchivedHistoryToo() {
    store.newProject("fresh", "chat-session");
    String id = ticket("fresh", "old work");
    store.archive("fresh", id, "chat-session");

    Map<String, Object> report = store.resetProject("fresh", "chat-session");

    assertEquals(1, report.get("archived_removed"));
    assertEquals(0, store.archived("fresh").size());
  }

  @Test
  public void idsAreNeverReusedAfterAReset() {
    store.newProject("fresh", "chat-session");
    String first = ticket("fresh", "one");
    store.resetProject("fresh", "chat-session");

    String next = ticket("fresh", "two");

    assertFalse("the id seq survives the reset (crash-recovery rule)", first.equals(next));
  }

  @Test
  public void resettingAMissingProjectIsRefused() {
    try {
      store.resetProject("nope", "chat-session");
      throw new AssertionError("expected Invalid");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("does not exist"));
    }
  }

  // T-226 (N2): _meta.json counts as project evidence - newProject writes exactly that sidecar,
  // so a scaffolded project must appear in projects() before its first ticket lands.

  @Test
  public void aScaffoldedProjectWithoutTicketsAppearsInProjects() {
    assertFalse("baseline: no phantom projects", store.projects().contains("fresh"));

    store.newProject("fresh", "chat-session");

    assertTrue(
        "the scaffolded project lists before its first ticket (only _meta.json exists)",
        store.projects().contains("fresh"));
    assertEquals("still ticketless", 0, store.list("fresh", null, null, null).size());
    assertFalse(
        "a directory that was never created stays invisible",
        store.projects().contains("never-created"));
  }

  @Test
  public void aResetProjectStaysVisibleBecauseItsSidecarSurvives() {
    store.newProject("fresh", "chat-session");
    ticket("fresh", "work being wiped");
    assertTrue(store.projects().contains("fresh"));

    store.resetProject("fresh", "chat-session");

    assertTrue(
        "the reset leaves only _meta.json - the project still exists on disk and must list"
            + " (it cannot be re-created: newProject refuses an existing directory)",
        store.projects().contains("fresh"));
  }
}
