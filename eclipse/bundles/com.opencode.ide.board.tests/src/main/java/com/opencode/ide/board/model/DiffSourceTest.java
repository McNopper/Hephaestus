package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.model.DiffSource.Source;
import org.junit.Test;

/**
 * Unit tests for the {@link DiffSource} decision: a job with a session id diffs from the server,
 * everything else (no job, no session, blank session) from local git.
 */
public class DiffSourceTest {

  private static FleetJobHandle job(String sessionId) {
    return new FleetJobHandle("T-1", sessionId, "C:/wt/T-1", FleetJobHandle.State.COMPLETED, null);
  }

  @Test
  public void sessionIdChoosesServer() {
    assertEquals(Source.SERVER, DiffSource.of(job("ses_1")));
  }

  @Test
  public void missingSessionIdChoosesLocalGit() {
    assertEquals(Source.LOCAL_GIT, DiffSource.of(job(null)));
  }

  @Test
  public void blankSessionIdChoosesLocalGit() {
    assertEquals(Source.LOCAL_GIT, DiffSource.of(job("  ")));
  }

  @Test
  public void nullJobChoosesLocalGit() {
    assertEquals(Source.LOCAL_GIT, DiffSource.of(null));
  }
}
