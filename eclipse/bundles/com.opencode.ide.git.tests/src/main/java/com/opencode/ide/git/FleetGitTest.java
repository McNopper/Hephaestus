package com.opencode.ide.git;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import org.junit.Test;

/**
 * {@link FleetGit#taskIdOfRef} — the branch-ref marker behind worktree discovery (see {@code
 * GitWorktreeManager.list}): only full fleet refs yield a task id.
 */
public class FleetGitTest {

  @Test
  public void fleetRefYieldsItsTaskId() {
    assertEquals(Optional.of("t1"), FleetGit.taskIdOfRef("refs/heads/opencode/t1"));
    assertEquals(Optional.of("V-042"), FleetGit.taskIdOfRef("refs/heads/opencode/V-042"));
  }

  @Test
  public void nonFleetRefsAreEmpty() {
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("refs/heads/main"));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("refs/heads/opencode"));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("detached"));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef(null));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef(""));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("   "));
  }

  @Test
  public void nestedOrSlashedTaskIdsAreRejected() {
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("refs/heads/opencode/a/b"));
    assertEquals(Optional.empty(), FleetGit.taskIdOfRef("refs/heads/opencode/a\\b"));
  }

  @Test
  public void roundTripsWithBranchFor() {
    String taskId = "t7";
    assertEquals(
        Optional.of(taskId), FleetGit.taskIdOfRef("refs/heads/" + FleetGit.branchFor(taskId)));
  }
}
