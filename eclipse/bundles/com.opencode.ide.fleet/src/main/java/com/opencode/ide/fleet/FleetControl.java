package com.opencode.ide.fleet;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeClients;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.OpencodeServerLauncher;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.git.StoreSync;
import com.opencode.ide.tasks.TaskStore;

/**
 * Headless wiring for the task fleet: the chat-first control plane behind the
 * {@code fleet_*} MCP tools. Chat is the primary interface - every fleet
 * action must be triggerable from an opencode session, with the Board buttons
 * as optional conveniences - so this class assembles the pure-Java fleet
 * engine without any Eclipse dependency:
 *
 * <ul>
 *   <li>lazily spawns a dedicated {@code opencode serve} process in the
 *       repository root (so it loads that repo's agents/skills/MCP config),
 *   <li>builds one {@link TaskFleet} over it (worktree isolation, role
 *       dispatch, polling completion detection, telemetry),
 *   <li>collects unattended sessions' permission asks in a
 *       {@link PermissionQueue} fed from the server's global event stream
 *       (see {@link #permissions()} - the chat answering path), and
 *   <li>runs every {@link #dispatch launch} on a daemon executor because
 *       {@link TaskFleet#launch} blocks end-to-end (submit → await → merge →
 *       bookkeeping; default 30 minutes), then best-effort auto-syncs the
 *       store's git repo so fleet peers see the ticket move.
 * </ul>
 *
 * <p>Lifecycle: {@link #close()} stops accepting launches and kills the
 * spawned server. The stdio entry point ({@link FleetStdioMain}) registers
 * that as a JVM shutdown hook so the child server never outlives the MCP
 * server process.</p>
 */
public final class FleetControl implements AutoCloseable {

    /** The default per-ticket run budget, mirroring {@link TaskFleet}'s own default. */
    public static final Duration DEFAULT_TIMEOUT = FleetTuning.DEFAULT_TICKET_BUDGET;

    /** The assembled engine plus whatever resources must be released with it. */
    public interface Engine extends AutoCloseable {
        TaskFleet fleet();

        /**
         * Live progress probe for a tracked job's session (the
         * {@code fleet_job_details} tool): message count, completion and busy
         * flags. {@code null} when the engine cannot probe (unknown ticket).
         */
        default FleetRunner.Activity probe(String ticketId) {
            return null;
        }

        /**
         * The engine's permission queue: unattended fleet sessions' asks
         * collect here (fed from the server's global event stream) until the
         * human answers them.
         */
        PermissionQueue permissions();

        @Override
        void close();
    }

    /**
     * Served by {@link #permissions()} while the engine does not exist:
     * listing pending asks must never spawn a server, and before the first
     * dispatch there is nothing to answer — nothing feeds this shared
     * stand-in, so it stays empty and every answer on it fails cleanly.
     */
    private static final PermissionQueue IDLE_PERMISSIONS = new PermissionQueue(null);

    private final Path storeRoot;
    private final Path repoRoot;
    private final Function<Path, Engine> engineFactory;
    private final ExecutorService executor;
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Engine engine;

    /**
     * Real mode: the engine factory spawns its own {@code opencode serve}
     * (free port on 127.0.0.1) in the repository that owns the store.
     * {@code OPENCODE_SERVER_PASSWORD} is honored when set; otherwise a fresh
     * random password is generated (see {@link #resolvePassword}) so the
     * spawned server never runs unauthenticated.
     */
    public static FleetControl spawn(Path storeRoot) {
        return new FleetControl(storeRoot, FleetControl::spawnEngine);
    }

    static Engine spawnEngine(Path root) {
        Path repo = repoRootOf(root);
        String password = resolvePassword(System.getenv("OPENCODE_SERVER_PASSWORD"));
        OpencodeServerLauncher server = new OpencodeServerLauncher(
                null, "127.0.0.1", 0, repo, password);
        URI base;
        try {
            base = server.start(FleetTuning.SERVER_START_TIMEOUT);
        } catch (com.opencode.ide.client.OpencodeException e) {
            throw new IllegalStateException(
                    "could not start the fleet's opencode server in " + repo + ": " + e.getMessage(), e);
        }
        OpencodeClient client = OpencodeClients.http(
                new ConnectionConfig(base, "opencode", password));
        PermissionQueue queue = new PermissionQueue(PermissionQueue.responderOf(client));
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        // the runner reports every session it creates through the
        // session-created callback - watched from creation, BEFORE the
        // prompt call (the blocking prompt call is where unattended asks
        // wait); one raw client serves the whole engine (runner, polling,
        // telemetry)
        TaskFleet fleet = new TaskFleet(
                new FleetRunner(client, FleetGit.defaultManager(), bridge::sessionStarted),
                new TaskStore(root),
                new RoleAgents(),
                () -> client,
                bridge);
        FleetRunner engineRunner = new FleetRunner(client, FleetGit.defaultManager());
        OpencodeEventStream events = client.getGlobalEvents(bridge::onEvent, connected -> { });
        events.start();
        // F-002: reconcile crash residue at engine start - release fleet
        // claims whose worktree/branch no longer exists (the previous engine
        // died mid-run). Best-effort; each release lands as a blocked marker.
        try {
            for (String project : new TaskStore(root).projects()) {
                int released = fleet.reconcileOrphanedClaims(project);
                if (released > 0) {
                    com.opencode.ide.client.ClientLog.info("reconciled " + released
                            + " orphaned fleet claim(s) in project " + project);
                }
            }
        } catch (RuntimeException e) {
            com.opencode.ide.client.ClientLog.warning(
                    "startup reconciliation failed (continuing): " + e.getMessage());
        }
        // G-001: sweep stale dispatch markers — a marker whose creating pid
        // is dead is crash residue; a live pid belongs to another engine
        sweepStaleMarkers(repo);
        return new Engine() {
            @Override
            public TaskFleet fleet() {
                return fleet;
            }

            @Override
            public FleetRunner.Activity probe(String ticketId) {
                FleetJob job = fleet.jobs().get(ticketId);
                if (job == null || job.sessionId() == null) {
                    return null;
                }
                try {
                    return engineRunner.probe(job.sessionId());
                } catch (Exception e) {
                    // probe failures surface as null - the tool reports unreachable
                    return null;
                }
            }

            @Override
            public PermissionQueue permissions() {
                return queue;
            }

            @Override
            public void close() {
                events.stop();
                server.stop();
            }
        };
    }

    /**
     * Resolves the spawned server's password: the given environment value
     * when usable (non-null, non-blank), else a fresh 64-char lowercase hex
     * string from a {@link SecureRandom} (32 bytes). The value feeds the
     * launcher and the connecting client only — it is never logged and never
     * part of any exception message.
     */
    public static String resolvePassword(String envPassword) {
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    /**
     * @param storeRoot the task store root ({@code .opencode/tasks}); its
     *                  grandparent is the repository root the fleet works in
     * @param engineFactory builds the engine on first dispatch (public seam:
     *                  the test fragment runs in its own OSGi classloader, so
     *                  package privacy does not reach across bundles)
     */
    public FleetControl(Path storeRoot, Function<Path, Engine> engineFactory) {
        this.storeRoot = storeRoot;
        this.repoRoot = repoRootOf(storeRoot);
        this.engineFactory = engineFactory;
        this.executor = Executors.newCachedThreadPool(daemons());
    }

    /** {@code <repo>/.opencode/tasks} → {@code <repo>}; absolute-normalized. */
    public static Path repoRootOf(Path storeRoot) {
        Path abs = storeRoot.toAbsolutePath().normalize();
        return abs.getParent().getParent();
    }

    public Path storeRoot() {
        return storeRoot;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    /** The lazy engine; the first call spawns the server (real mode). */
    public synchronized Engine engine() {
        if (engine == null) {
            engine = engineFactory.apply(storeRoot);
        }
        return engine;
    }

    /** @return whether the engine (and in real mode the server) is up. */
    public synchronized boolean engineStarted() {
        return engine != null;
    }

    /**
     * The fleet's permission queue — the chat answering path for unattended
     * sessions' asks (the {@code fleet_permissions} tools). Serves the
     * engine's live queue once the engine exists; before that, deliberately
     * without spawning it (listing pending asks must stay cheap): before the
     * first dispatch there is nothing to answer, so the shared empty
     * {@link #IDLE_PERMISSIONS stand-in} is returned instead (every answer on
     * it fails cleanly — nothing was ever asked).
     */
    public PermissionQueue permissions() {
        Engine e;
        synchronized (this) {
            e = engine;
        }
        return e == null ? IDLE_PERMISSIONS : e.permissions();
    }

    /**
     * Launches the fleet for one ticket asynchronously and returns
     * immediately - poll {@link #jobs()} (or the {@code fleet_jobs} tool) for
     * the outcome. Callers pre-validate the ticket (exists, not blocked, not
     * done, not already in flight); {@link TaskFleet} re-checks and marks the
     * ticket {@code blocked} with the reason on failure, so an exception here
     * never surfaces to the caller after the fact. Once the launch settles —
     * either way — the store's git repo is auto-synced best-effort
     * (pull→commit→push, see {@link StoreSync#sync}): the launch's
     * bookkeeping (claim, in-review, blocked markers) deserves publishing
     * even when the launch failed. A sync failure is logged and swallowed; it
     * can never mask the launch outcome ({@code PULL_CONFLICT} is a normal
     * outcome, recoverable via {@code fleet_recover_store}, not a failure).
     */
    public void dispatch(String project, String ticketId, Duration timeout) {
        Engine e = engine();
        acquireDispatchGuard(ticketId);
        inFlight.add(ticketId);
        com.opencode.ide.client.ClientLog.info(
                "fleet dispatch " + ticketId + " accepted (budget " + timeout + ")");
        executor.execute(() -> {
            try {
                try {
                    try {
                        e.fleet().launch(project, ticketId, repoRoot, timeout);
                    } finally {
                        com.opencode.ide.client.ClientLog.info("fleet dispatch " + ticketId + " settled");
                    }
                } catch (RuntimeException ex) {
                    // TaskFleet already recorded the failure on the ticket
                    // (blocked + reason); the jobs snapshot stays authoritative.
                    com.opencode.ide.client.ClientLog.warning(
                            "fleet launch of " + ticketId + " threw: " + ex.getMessage());
                } finally {
                    inFlight.remove(ticketId);
                    releaseDispatchGuard(ticketId);
                }
                StoreSync.sync(storeRoot, "opencode fleet: store sync after " + ticketId);
            } catch (RuntimeException ex) {
                com.opencode.ide.client.ClientLog.warning(
                    "store auto-sync failed for " + ticketId + ": " + ex.getMessage());
            }
        });
    }

    /**
     * F-003 cross-engine dispatch guard: an atomically-created marker file
     * under {@code .git/opencode-fleet/} — the create-if-absent is atomic
     * ACROSS PROCESSES (Windows CREATE_NEW semantics), so the Board's engine
     * and a chat engine can never both pre-claim the same ticket. The loser
     * gets a clean refusal that touches nothing. The marker carries the
     * creating pid for diagnosis; stale markers (crashed creator) are
     * overwritten — reconciliation or fleet_reset cleans real residue.
     */
    private void acquireDispatchGuard(String ticketId) {
        Path dir = repoRoot.resolve(".git").resolve("opencode-fleet");
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path marker = dir.resolve(ticketId + ".dispatch");
            java.nio.file.attribute.FileAttribute<?>[] none = {};
            try {
                java.nio.file.Files.createFile(marker, none);
                java.nio.file.Files.writeString(marker,
                        "pid=" + ProcessHandle.current().pid()
                                + " at " + java.time.Instant.now() + "\n");
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("ticket " + ticketId
                        + " is being dispatched by another engine (marker " + marker + " exists)"
                        + " - fleet_reset removes stale markers");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create the dispatch guard for " + ticketId
                    + ": " + e.getMessage(), e);
        }
    }

    /** Releases the marker on settle; best-effort (crash leaves it for reconciliation). */
    private void releaseDispatchGuard(String ticketId) {
        try {
            java.nio.file.Files.deleteIfExists(repoRoot.resolve(".git")
                    .resolve("opencode-fleet").resolve(ticketId + ".dispatch"));
        } catch (java.io.IOException e) {
            com.opencode.ide.client.ClientLog.warning(
                    "releasing the dispatch guard of " + ticketId + " failed: " + e.getMessage());
        }
    }

    /**
     * G-001: sweeps stale {@code *.dispatch} markers under
     * {@code .git/opencode-fleet/} whose creating pid is dead. Markers carry
     * {@code pid=<N> at <instant>}; a live pid belongs to another engine and
     * is left alone; a dead pid is crash residue and is removed so the
     * ticket is dispatchable again without manual surgery.
     */
    static int sweepStaleMarkers(Path repoRoot) {
        Path dir = repoRoot.resolve(".git").resolve("opencode-fleet");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return 0;
        }
        int swept = 0;
        try (var stream = java.nio.file.Files.list(dir)) {
            for (Path marker : stream.filter(p -> p.getFileName().toString().endsWith(".dispatch")).toList()) {
                try {
                    String content = java.nio.file.Files.readString(marker);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("pid=(\\d+)").matcher(content);
                    if (!m.find()) {
                        continue; // unreadable: leave it, fleet_reset handles it
                    }
                    long pid = Long.parseLong(m.group(1));
                    if (ProcessHandle.of(pid).isPresent()) {
                        continue; // another engine is live
                    }
                    java.nio.file.Files.deleteIfExists(marker);
                    swept++;
                    com.opencode.ide.client.ClientLog.info("swept stale dispatch marker "
                            + marker.getFileName() + " (pid " + pid + " is dead)");
                } catch (Exception e) {
                    com.opencode.ide.client.ClientLog.warning(
                            "sweeping marker " + marker + " failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            com.opencode.ide.client.ClientLog.warning(
                    "marker sweep in " + dir + " failed: " + e.getMessage());
        }
        return swept;
    }

    /**
     * Live progress probe for a tracked job (the {@code fleet_job_details}
     * tool): message count, completion and busy flags. Never spawns the
     * engine; {@code null} when there is nothing to probe.
     */
    public FleetRunner.Activity jobActivity(String ticketId) {
        Engine e;
        synchronized (this) {
            e = engine;
        }
        return e == null ? null : e.probe(ticketId);
    }

    /**
     * Snapshot of the fleet's jobs, keyed by ticket id; empty before the first
     * dispatch. Tickets whose launch was accepted but whose engine entry has
     * not materialized yet (submit runs inside {@code launch}) appear as
     * synthetic RUNNING entries so the in-flight guard has no race window.
     */
    public Map<String, FleetJob> jobs() {
        Engine e;
        synchronized (this) {
            e = engine;
        }
        Map<String, FleetJob> jobs = e == null ? Map.of() : e.fleet().jobs();
        if (inFlight.isEmpty()) {
            return jobs;
        }
        Map<String, FleetJob> merged = new java.util.LinkedHashMap<>(jobs);
        for (String id : inFlight) {
            merged.putIfAbsent(id, new FleetJob(id, null, null, FleetJob.State.RUNNING, null));
        }
        return Map.copyOf(merged);
    }

    @Override
    public synchronized void close() {
        // R3 drain-then-kill: a launch mid-merge holds repo state (worktree,
        // MERGE_HEAD, the store claim) - interrupting it mid-git is exactly
        // the crash the review flagged. Give in-flight launches a bounded
        // grace window to settle (F-001 already guarantees they land in
        // blocked()/released on ANY outcome), THEN hard-cancel.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(FleetTuning.SHUTDOWN_GRACE.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private static ThreadFactory daemons() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "fleet-dispatch-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
