package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.opencode.ide.client.model.ShellTask;
import org.junit.Test;

/**
 * T-179: {@code ShellTask} maps the v2 {@code metadata} binding ({@code metadata.sessionID}) the
 * server browser nests terminals under - plus the pre-T-179 compatibility constructor.
 */
public class ShellTaskTest {

  private static final Gson GSON = new Gson();

  /** The live v2 {@code Shell.Info} shape (captured 2026-11-18). */
  private static final String WIRE =
      """
      {"id":"sh_0c36546a","status":"exited","command":"git status",
       "cwd":"C:\\\\repo","shell":"pwsh","file":"C:\\\\tmp\\\\sh.out","pid":29884,"exit":0,
       "metadata":{"sessionID":"ses_f3c9abab","background":true},
       "time":{"started":1789984589478,"completed":1789984590201}}
      """;

  @Test
  public void metadataCarriesTheOwningSessionForTheInventoryNesting() {
    ShellTask task = GSON.fromJson(WIRE, ShellTask.class);

    assertEquals("sh_0c36546a", task.id());
    assertEquals("exited", task.status());
    assertEquals("git status", task.command());
    assertEquals(Integer.valueOf(0), task.exit());
    assertEquals("ses_f3c9abab", task.sessionId());
    assertTrue(
        "background flag maps through metadata", Boolean.TRUE.equals(task.metadata().background()));
    assertTrue(task.time().completed() > 0);
    assertTrue("a finished task is not running", !task.isRunning());
  }

  @Test
  public void tasksWithoutMetadataYieldNoOwnerAndTheCompatConstructorStillBuilds() {
    ShellTask bare =
        GSON.fromJson(
            "{\"id\":\"sh_1\",\"status\":\"running\",\"command\":\"ls\"}", ShellTask.class);
    assertNull(bare.sessionId());
    assertNull(bare.metadata());
    assertTrue(bare.isRunning());

    ShellTask legacy = new ShellTask("sh_2", "exited", "pwd", "/repo", 0, 1L, null);
    assertNull("the pre-T-179 7-arg constructor binds no owner", legacy.metadata());
    assertNull(legacy.sessionId());
  }
}
