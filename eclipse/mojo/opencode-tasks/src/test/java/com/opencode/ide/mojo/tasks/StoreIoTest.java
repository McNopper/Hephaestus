package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Root resolution: explicit property wins, otherwise walk up for .opencode/tasks. */
public class StoreIoTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void walksUpToDotOpencodeTasks() throws Exception {
    Path base = tmp.getRoot().toPath();
    Path store = base.resolve(".opencode").resolve("tasks");
    Files.createDirectories(store);
    Path deep = base.resolve("a").resolve("b").resolve("c");
    Files.createDirectories(deep);
    assertEquals(store, StoreIo.resolveRoot(null, deep));
  }

  @Test
  public void returnsNullWhenNoStoreAbove() throws Exception {
    Path deep = tmp.getRoot().toPath().resolve("a").resolve("b");
    Files.createDirectories(deep);
    assertNull(StoreIo.resolveRoot(null, deep));
  }

  @Test
  public void explicitRootWinsOverWalkUp() {
    File elsewhere = new File(tmp.getRoot(), "elsewhere");
    assertEquals(elsewhere.toPath(), StoreIo.resolveRoot(elsewhere, tmp.getRoot().toPath()));
  }
}
