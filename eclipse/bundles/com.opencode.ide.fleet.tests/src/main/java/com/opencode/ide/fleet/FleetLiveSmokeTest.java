package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeClients;
import com.opencode.ide.client.OpencodeServerLauncher;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * STAGED live smoke against a REAL spawned opencode server (Milestone V lesson: full dispatches are
 * too slow a feedback loop — one hidden defect per 25-minute run). Each test probes ONE link of the
 * fleet chain, timed, with its own short budget; a failure names the stage, not just "run failed".
 *
 * <p>Gating: stages 1-4 run only with {@code FLEET_LIVE_SMOKE=1}; the agent-file-work probe (stage
 * 5) additionally needs {@code FLEET_LIVE_SMOKE_AGENT=1} because it spends real model tokens
 * (~minutes, ~cents). All stages skip silently otherwise, so the normal reactor gate is unaffected.
 *
 * <p>Chain covered: spawn+auth ({@link OpencodeServerLauncher}), session create +
 * absence-means-idle status semantics ({@link PollingSessionEvents}), the blocking prompt round
 * trip with an explicit budget ({@code sendMessage(req, timeout)}), the 1.18.23 busy-only {@code
 * /session/status} shape, and — stage 5 — an executor agent doing real file work inside a {@code
 * .git/opencode-fleet}-style directory (the worktree-permission theory from attempts 5-8) versus a
 * normal directory.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FleetLiveSmokeTest {

  private static final String PASSWORD = "live-smoke-pw";

  private static OpencodeServerLauncher launcher;
  private static OpencodeClient client;
  private static Path repoRoot;
  private static Path normalDir;

  private static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    for (Path p = dir; p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve(".git")) && Files.exists(p.resolve("eclipse"))) {
        return p;
      }
    }
    return null;
  }

  @BeforeClass
  public static void spawnOnce() throws Exception {
    Assume.assumeTrue(
        "set FLEET_LIVE_SMOKE=1 to run the live stages",
        "1".equals(System.getenv("FLEET_LIVE_SMOKE")));
    repoRoot = findRepoRoot();
    Assume.assumeTrue(
        "could not locate the repository root from " + System.getProperty("user.dir"),
        repoRoot != null);
    launcher = new OpencodeServerLauncher(null, "127.0.0.1", 0, repoRoot, PASSWORD);
    client =
        OpencodeClients.http(
            new ConnectionConfig(launcher.start(Duration.ofSeconds(60)), "opencode", PASSWORD));
    // prompting needs a REGISTERED project: a session scoped to a random
    // temp dir lands in projectID=global and the message POST 500s before
    // any model stream starts (found via s3). So all prompting stages use
    // directories inside this repo - like the fleet's worktrees do.
    normalDir = repoRoot.resolve("target").resolve("fleet-smoke-normal");
    Files.createDirectories(normalDir);
  }

  @AfterClass
  public static void stopOnce() {
    if (launcher != null) {
      launcher.stop();
    }
  }

  @Test(timeout = 90_000)
  public void s1_spawn_health_and_auth() throws Exception {
    long start = System.nanoTime();
    HealthStatus health = client.getHealth();
    assertNotNull("health snapshot must parse", health);
    assertTrue("server must report healthy", health.healthy());
    assertNotNull("server version must be present (pin compare)", health.version());
    assertNotNull("launcher captures the health for the version pin", launcher.getLastHealth());
    System.out.println(
        "[live-smoke] s1 spawn+health+auth ok in "
            + (System.nanoTime() - start) / 1_000_000
            + "ms, server "
            + health.version());
  }

  @Test(timeout = 30_000)
  public void s2_session_create_and_absent_idle() throws Exception {
    // session CREATION works anywhere - keep the temp-dir variant as the
    // cheap status-semantics probe (no prompting here)
    Path anywhere = Files.createTempDirectory("fleet-smoke-anywhere-");
    Session session = client.createSession("live smoke s2", anywhere);
    assertNotNull(session.id());
    // freshly created, never prompted: absent from busy-only status, or idle
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});
    assertTrue(
        "a never-prompted session must read as idle (absent counts)",
        events.awaitIdle(session.id(), Duration.ofSeconds(5)));
    System.out.println("[live-smoke] s2 session+idle ok: " + session.id());
  }

  @Test(timeout = 180_000)
  public void s3_tiny_prompt_round_trip() throws Exception {
    Session session = client.createSession("live smoke s3", normalDir);
    ChatRequest prompt =
        ChatRequest.of(session.id(), "Reply with exactly the word OK and nothing else.");
    long start = System.nanoTime();
    ChatEntry reply = client.sendMessage(prompt, Duration.ofMinutes(2));
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertNotNull("the blocking POST must return the final reply", reply);
    assertTrue("reply should carry text, got: <" + reply.text() + ">", !reply.text().isBlank());
    // completion contract: once the POST returned, the session IS idle
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});
    assertTrue(
        "after the final reply the session must read idle immediately",
        events.awaitIdle(session.id(), Duration.ofSeconds(5)));
    System.out.println(
        "[live-smoke] s3 tiny prompt ok in "
            + ms
            + "ms, reply: "
            + reply.text().lines().findFirst().orElse(""));
  }

  @Test(timeout = 30_000)
  public void s4_status_shape_busy_only() throws Exception {
    Map<String, SessionStatus> status = client.getSessionStatus();
    assertNotNull(status);
    // 1.18.23 evidence: idle sessions are absent. We cannot force a busy
    // session here deterministically (s3's session is done), so record the
    // shape and assert no session ever reports a non-idle resting state
    // while nothing streams.
    status.forEach(
        (id, st) ->
            assertTrue(
                "unexpected busy entry while idle: " + id + "=" + st, "idle".equals(st.type())));
    System.out.println("[live-smoke] s4 status map ok, size=" + status.size());
  }

  /**
   * The attempts-5-8 probe: does an executor agent complete file work when its session directory
   * sits INSIDE {@code .git/opencode-fleet/} (the real fleet worktree layout — opencode may treat
   * .git-internal paths as protected, silently stalling the agent on a permission that never
   * surfaces), versus a normal directory? Two tiny agent runs, real tokens.
   */
  @Test(timeout = 600_000)
  public void s5_agent_file_work_worktree_vs_normal_dir() throws Exception {
    Assume.assumeTrue(
        "set FLEET_LIVE_SMOKE_AGENT=1 for the agent probe (spends tokens)",
        "1".equals(System.getenv("FLEET_LIVE_SMOKE_AGENT")));
    Path fleetLike = repoRoot.resolve(".git").resolve("opencode-fleet").resolve("SMOKE-PROBE");
    Files.createDirectories(fleetLike);
    try {
      long normalMs = agentWritesMarker(normalDir);
      System.out.println("[live-smoke] s5 agent in NORMAL dir completed in " + normalMs + "ms");
      long fleetMs = agentWritesMarker(fleetLike);
      System.out.println(
          "[live-smoke] s5 agent in .git/opencode-fleet dir completed in "
              + fleetMs
              + "ms  <== the fleet layout");
    } finally {
      deleteQuietly(fleetLike);
    }
  }

  /** One executor run: create marker file, reply DONE; asserts file + reply. */
  private long agentWritesMarker(Path dir) throws Exception {
    Session session = client.createSession("live smoke agent", dir);
    ChatRequest prompt =
        ChatRequest.of(
                session.id(),
                "Create a file named marker.txt in the working directory containing the single line: ok. "
                    + "Then reply with exactly: DONE")
            .withAgent("executor");
    long start = System.nanoTime();
    ChatEntry reply;
    try {
      reply = client.sendMessage(prompt, Duration.ofMinutes(4));
    } catch (com.opencode.ide.client.OpencodeException e) {
      throw new AssertionError(
          "agent run in " + dir + " failed (stall or timeout?): " + e.getMessage(), e);
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertTrue(
        "agent reply must mention DONE, got: <" + reply.text() + ">",
        reply.text().contains("DONE"));
    assertTrue("marker.txt must exist in " + dir, Files.exists(dir.resolve("marker.txt")));
    if (dir.toString().contains("opencode-fleet")) {
      assertEquals("marker content", "ok", Files.readString(dir.resolve("marker.txt")).trim());
    }
    return ms;
  }

  private static void deleteQuietly(Path root) {
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignored) {
                  // best effort cleanup
                }
              });
    } catch (Exception ignored) {
      // best effort cleanup
    }
  }
}
