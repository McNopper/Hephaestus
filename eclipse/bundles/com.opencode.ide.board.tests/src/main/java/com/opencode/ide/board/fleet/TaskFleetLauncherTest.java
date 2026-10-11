package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.board.model.FleetJobsModel;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.fleet.Bootstrap;
import com.opencode.ide.fleet.FleetJob;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link TaskFleetLauncher} wiring tests: the fast validation paths run synchronously; the engine
 * path is covered by the failure propagation test (the supplier throws) — the happy path is the
 * fleet bundle's TaskFleetTest. The fleet-cache tests pin the reviewer follow-up #4 semantics:
 * suppliers changes evict per-root fleets (generation-keyed cache) and fleet creation locks per
 * root, so a spawn for one root never stalls another root's launch. The bootstrap tests capture the
 * engine-call seam: the stored bootstrap (supplier-injected, re-read per launch) must reach the
 * {@code TaskFleet.launch} call — as the value while set, as {@code null} when unset, and degraded
 * to {@code null} when the supplier fails.
 */
public class TaskFleetLauncherTest {

  private static final long TIMEOUT_NANOS = 10_000_000_000L;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @org.junit.Before
  public void resetProcessWideState() {
    TaskFleetLauncher.resetForTests();
  }

  @org.junit.After
  public void tearDown() {
    TaskFleetLauncher.resetForTests();
  }

  @Test
  public void nonGitRepoFailsFastWithClearDetail() throws IOException {
    Path storeRoot = tmp.newFolder("repo", ".opencode", "tasks").toPath();
    TaskFleetLauncher launcher =
        new TaskFleetLauncher(
            () -> {
              throw new AssertionError("client must not be requested");
            },
            () -> {
              throw new AssertionError("worktrees must not be requested");
            },
            () -> storeRoot);

    FleetJobHandle handle = launcher.launch("hephaestus", "T-001");

    assertTrue(handle.failed());
    assertTrue(handle.detail(), handle.detail().contains("no git repository"));
  }

  @Test
  public void engineFailureIsPublishedToTheModel() throws Exception {
    Path repo = tmp.newFolder("repo2").toPath();
    Files.createDirectories(repo.resolve(".git"));
    Path storeRoot = Files.createDirectories(repo.resolve(".opencode").resolve("tasks"));
    String boom = "boom: no opencode server";
    TaskFleetLauncher launcher =
        new TaskFleetLauncher(
            () -> {
              throw new IllegalStateException(boom);
            },
            () -> {
              throw new IllegalStateException("worktrees unreachable");
            },
            () -> storeRoot);

    FleetJobHandle handle = launcher.launch("hephaestus", "T-002");

    assertEquals(FleetJobHandle.State.RUNNING, handle.state());
    FleetJobHandle finalHandle = awaitFailure("T-002", null);
    assertEquals(boom, finalHandle.detail());
  }

  @Test
  public void suppliersChangeEvictsPerRootFleet() throws Exception {
    Path repo = tmp.newFolder("repo3").toPath();
    Files.createDirectories(repo.resolve(".git"));
    Path storeRoot = Files.createDirectories(repo.resolve(".opencode").resolve("tasks"));
    AtomicBoolean s1Used = new AtomicBoolean();
    AtomicBoolean s2Used = new AtomicBoolean();
    TaskFleetLauncher first =
        new TaskFleetLauncher(
            () -> {
              s1Used.set(true);
              return throwingClient("s1-client-rejected");
            },
            () -> null,
            () -> storeRoot);
    first.launch("hephaestus", "T-101");
    assertTrue("suppliers s1 never used to build the fleet", awaitFlag(s1Used));
    awaitFailure("T-101", null); // settle: the s1 fleet is cached and its launch finished

    TaskFleetLauncher second =
        new TaskFleetLauncher(
            () -> {
              s2Used.set(true);
              throw new IllegalStateException("s2-client-unavailable");
            },
            () -> null,
            () -> storeRoot);
    second.launch("hephaestus", "T-102");

    FleetJobHandle rebuilt = awaitFailure("T-102", "s2-client-unavailable");
    assertTrue("stale fleet reused — s2 suppliers never consulted", s2Used.get());
    assertNotNull(rebuilt);
  }

  @Test
  public void launchForRootBDoesNotWaitBehindRootASpawn() throws Exception {
    Path repoA = tmp.newFolder("repoA").toPath();
    Files.createDirectories(repoA.resolve(".git"));
    Path rootA = Files.createDirectories(repoA.resolve(".opencode").resolve("tasks"));
    Path repoB = tmp.newFolder("repoB").toPath();
    Files.createDirectories(repoB.resolve(".git"));
    Path rootB = Files.createDirectories(repoB.resolve(".opencode").resolve("tasks"));

    CountDownLatch spawnA = new CountDownLatch(1);
    AtomicBoolean enteredA = new AtomicBoolean();
    TaskFleetLauncher launcherA =
        new TaskFleetLauncher(
            () -> {
              enteredA.set(true);
              try {
                spawnA.await();
              } catch (InterruptedException e) {
                throw new IllegalStateException("root A spawn interrupted", e);
              }
              return throwingClient("client-a");
            },
            () -> null,
            () -> rootA);

    launcherA.launch("hephaestus", "T-201");
    assertTrue("root A's client supplier (spawn) never entered", awaitFlag(enteredA));

    // constructed only now — A's in-flight creation already captured the older suppliers
    TaskFleetLauncher launcherB =
        new TaskFleetLauncher(
            () -> {
              throw new IllegalStateException("client-b-unavailable");
            },
            () -> null,
            () -> rootB);
    launcherB.launch("hephaestus", "T-202");

    FleetJobHandle failedB = awaitFailure("T-202", "client-b-unavailable");
    assertEquals(1, spawnA.getCount()); // B settled while A is still spawning
    spawnA.countDown();
    awaitFailure("T-201", null); // A settles too — nothing left spinning
  }

  // ------------------------------------------------------------------
  // Bootstrap wiring (stored bootstrap reaches the fleet launch call)
  // ------------------------------------------------------------------

  @Test
  public void storedBootstrapReachesTheFleetLaunchCall() throws Exception {
    Path storeRoot = engineCallStoreRoot("repo4");
    Bootstrap bootstrap = Bootstrap.of("build", "npm install");
    AtomicReference<Bootstrap> seen = new AtomicReference<>();
    CountDownLatch captured = captureEngineLaunch(seen);
    TaskFleetLauncher launcher =
        new TaskFleetLauncher(
            () -> throwingClient("engine-call-not-made"),
            () -> null,
            () -> storeRoot,
            () -> bootstrap);

    FleetJobHandle handle = launcher.launch("hephaestus", "T-301");

    assertEquals(FleetJobHandle.State.RUNNING, handle.state());
    assertTrue("the engine launch was never called", captured.await(10, TimeUnit.SECONDS));
    assertEquals("the stored bootstrap must reach the fleet launch call", bootstrap, seen.get());
  }

  @Test
  public void unsetBootstrapReachesTheFleetLaunchAsNone() throws Exception {
    Path storeRoot = engineCallStoreRoot("repo5");
    AtomicReference<Bootstrap> seen = new AtomicReference<>(Bootstrap.of("stale", "leftover"));
    CountDownLatch captured = captureEngineLaunch(seen);
    TaskFleetLauncher launcher =
        new TaskFleetLauncher(
            () -> throwingClient("engine-call-not-made"), () -> null, () -> storeRoot);

    launcher.launch("hephaestus", "T-302");

    assertTrue("the engine launch was never called", captured.await(10, TimeUnit.SECONDS));
    assertNull("no bootstrap supplied means none reaches the fleet", seen.get());
  }

  @Test
  public void failingBootstrapSupplierDegradesToNoBootstrap() throws Exception {
    Path storeRoot = engineCallStoreRoot("repo6");
    AtomicReference<Bootstrap> seen = new AtomicReference<>();
    CountDownLatch captured = captureEngineLaunch(seen);
    TaskFleetLauncher launcher =
        new TaskFleetLauncher(
            () -> throwingClient("engine-call-not-made"),
            () -> null,
            () -> storeRoot,
            () -> {
              throw new IllegalStateException("store unreadable");
            });

    launcher.launch("hephaestus", "T-303");

    assertTrue(
        "a failing bootstrap supplier must not block the launch",
        captured.await(10, TimeUnit.SECONDS));
    assertNull("the launch proceeds without a bootstrap", seen.get());
  }

  /**
   * A git repo + store root whose fleet creation succeeds with a throwing client (no server
   * needed).
   */
  private Path engineCallStoreRoot(String folder) throws IOException {
    Path repo = tmp.newFolder(folder).toPath();
    Files.createDirectories(repo.resolve(".git"));
    return Files.createDirectories(repo.resolve(".opencode").resolve("tasks"));
  }

  /**
   * Replaces the engine call with a capture returning a settled job; {@link
   * TaskFleetLauncher#resetForTests()} (before/after each test) restores the real one.
   */
  private static CountDownLatch captureEngineLaunch(AtomicReference<Bootstrap> seen) {
    CountDownLatch captured = new CountDownLatch(1);
    TaskFleetLauncher.useEngineLaunchForTests(
        (fleet, project, taskId, repoRoot, timeout, bootstrap) -> {
          seen.set(bootstrap);
          captured.countDown();
          return new FleetJob(taskId, "sess-1", null, FleetJob.State.MERGED, null);
        });
    return captured;
  }

  /**
   * An {@link OpencodeClient} whose every method throws — fleet creation succeeds, any use fails
   * fast.
   */
  private static OpencodeClient throwingClient(String marker) {
    return (OpencodeClient)
        Proxy.newProxyInstance(
            OpencodeClient.class.getClassLoader(),
            new Class<?>[] {OpencodeClient.class},
            (proxy, method, args) -> {
              throw new IllegalStateException(marker);
            });
  }

  private static boolean awaitFlag(AtomicBoolean flag) throws InterruptedException {
    long deadline = System.nanoTime() + TIMEOUT_NANOS;
    while (System.nanoTime() < deadline) {
      if (flag.get()) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  /**
   * Polls the shared model for the ticket's FAILED handle (optionally matching a detail fragment).
   */
  private static FleetJobHandle awaitFailure(String taskId, String detailPart)
      throws InterruptedException {
    AtomicReference<FleetJobHandle> found = new AtomicReference<>();
    long deadline = System.nanoTime() + TIMEOUT_NANOS;
    while (System.nanoTime() < deadline) {
      for (FleetJobHandle job : FleetJobsModel.getDefault().jobs()) {
        if (taskId.equals(job.taskId())
            && job.failed()
            && (detailPart == null || job.detail().contains(detailPart))) {
          found.set(job);
        }
      }
      if (found.get() != null) {
        return found.get();
      }
      Thread.sleep(50);
    }
    assertNotNull("failure handle for " + taskId + " never published", found.get());
    throw new AssertionError("unreachable");
  }
}
