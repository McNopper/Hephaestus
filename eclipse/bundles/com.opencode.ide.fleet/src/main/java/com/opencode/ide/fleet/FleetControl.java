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
import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.fleet.dispatch.DispatchScheduler;
import com.opencode.ide.fleet.dispatch.RecurringWaves;

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
 * server process. B-004 adds two lifecycle guarantees for the spawned
 * serve: a launch that settles FAILED recycles the engine when idle (the
 * serve is killed so it cannot lock the failed worktree's files - no orphan
 * survives the job end), and a cached engine whose serve died is detected
 * and respawned before its recorded endpoint is reused (a dead serve never
 * fails every later dispatch with "Cannot reach opencode server").</p>
 */
public final class FleetControl implements AutoCloseable {

    /** The default per-ticket run budget, mirroring {@link TaskFleet}'s own default. */
    public static final Duration DEFAULT_TIMEOUT = FleetTuning.DEFAULT_TICKET_BUDGET;

    /** The assembled engine plus whatever resources must be released with it. */
    public interface Engine extends AutoCloseable {
        TaskFleet fleet();

        /**
         * B-004: whether the engine's spawned {@code opencode serve} is still
         * alive - the liveness probe consulted before the recorded endpoint
         * is REUSED ({@link FleetControl#engine()} respawns when it is dead,
         * so one killed serve can never fail every later dispatch with
         * "Cannot reach opencode server"). Default {@code true}: fakes and
         * engine-less implementations have nothing that can die.
         */
        default boolean serverAlive() {
            return true;
        }

        /**
         * B-004: the OS pid of the spawned serve while it is alive, for
         * diagnostics ({@code fleet_reset} names it when worktree removal
         * keeps failing). Default {@code null} = unknown.
         */
        default Long serverPid() {
            return null;
        }

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
    private boolean closed;
    private DispatchScheduler scheduler;
    private String autoProject;
    private String autoSprint;
    private long autoGeneration;
    private RecurringWaves waves;
    private long wavesGeneration;

    /** Starts the same readiness/cost policy used by the Board, over an explicit sprint. */
    public void startAuto(String project, String sprint, int maxConcurrent,
            double budgetUsd, boolean includeStale) {
        stopWaves(); // the one-sprint loop and the recurring mode are exclusive
        if (project == null || project.isBlank() || sprint == null || sprint.isBlank()) {
            throw new IllegalArgumentException("project and sprint must be explicit nonblank names");
        }
        TaskStore store = new TaskStore(storeRoot);
        var tasks = store.list(project, null, null, sprint, null);
        if (tasks.isEmpty()) {
            throw new IllegalStateException("no tickets in sprint " + sprint + " of " + project);
        }
        var policy = AutoDispatch.of(maxConcurrent, budgetUsd, includeStale);
        DispatchScheduler previous;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("fleet control is closed");
            }
            previous = scheduler;
            long generation = ++autoGeneration;
            scheduler = DispatchScheduler.withFeedback(policy,
                    () -> store.list(project, null, null, sprint, null),
                    () -> store.list(project, null, null, null, null),
                    () -> CostOverview.of(store.list(project, null, null, null, null)),
                    () -> DispatchGuard.runningIds(repoRoot),
                    (id, attempt) -> dispatchAuto(store, project, sprint, id, policy, generation, attempt), null)
                    .withCalibratedCosts();
            autoProject = project;
            autoSprint = sprint;
            scheduler.start(Duration.ofSeconds(5));
        }
        if (previous != null) {
            previous.requestStop();
        }
    }

    private void dispatchAuto(TaskStore store, String project, String sprint, String id,
            AutoDispatch policy, long generation, DispatchScheduler.LaunchAttempt attempt) {
        DispatchGuard.admit(repoRoot, policy.maxConcurrent(), () -> {
            synchronized (this) {
                if (closed || generation != autoGeneration) {
                    throw new DispatchGuard.AdmissionDeferred("auto-dispatch stopped");
                }
                // Plans are advisory. Revalidate readiness and spend under the
                // same process lock used to count and reserve capacity.
                var scope = store.list(project, null, null, null, null);
                var candidate = scope.stream()
                        .filter(t -> id.equals(t.id) && sprint.equals(t.sprint)).toList();
                var overview = CostOverview.of(scope);
                var current = policy.withEstimateUsd(AutoDispatch.calibratedEstimate(overview)).plan(candidate,
                        com.opencode.ide.tasks.StageReadiness.evaluate(scope),
                        overview, DispatchGuard.runningIds(repoRoot));
                if (!current.launch().contains(id)) {
                    throw new DispatchGuard.AdmissionDeferred("ticket no longer admissible: " + id);
                }
                dispatchLocked(project, id, DEFAULT_TIMEOUT, policy, attempt);
            }
            return null;
        });
    }

    /** Stops admission; accepted workers continue. Never waits while holding the control monitor. */
    public void stopAuto() {
        DispatchScheduler previous;
        synchronized (this) {
            autoGeneration++;
            previous = scheduler;
            scheduler = null;
        }
        if (previous != null) {
            previous.requestStop();
        }
    }

    public synchronized Map<String, Object> autoStatus() {
        return Map.of("running", scheduler != null && scheduler.isRunning(),
                "project", autoProject == null ? "" : autoProject,
                "sprint", autoSprint == null ? "" : autoSprint);
    }

    /**
     * U-022: enables the recurring-waves mode for one project — the
     * always-on wave-to-wave pump. When the active wave drains, the next
     * wave is planned automatically from the prioritized product backlog
     * (top-priority READY tickets, concurrency + cost budget respected) —
     * no human click between waves. The loop parks while NEEDS-HUMAN
     * tickets wait (clearing a blocker resumes it automatically) and stops
     * cleanly on budget exhaustion or when nothing is plannable. Deliberately
     * OFF by default: only this explicit call (or the Board's toggle) turns
     * it on; the cost budget is a hard stop. The loop lives in this engine,
     * so under the fleet daemon it survives every client disconnect.
     *
     * @param project    the task store project to pump
     * @param initialWave an existing sprint adopted as the first wave
     *                   ({@code null}/blank plans wave 1 from the backlog
     *                   immediately)
     * @param maxConcurrent the concurrency cap (also the wave size bound)
     * @param budgetUsd  the hard-stop cost budget; 0 means unlimited
     * @param includeStale whether STALE re-runs are admitted
     */
    public void startWaves(String project, String initialWave, int maxConcurrent,
            double budgetUsd, boolean includeStale) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must be an explicit nonblank name");
        }
        TaskStore store = new TaskStore(storeRoot);
        if (store.list(project, null, null, null, null).isEmpty()) {
            throw new IllegalStateException("no tickets in project " + project);
        }
        String wave = initialWave == null || initialWave.isBlank() ? null : initialWave;
        if (wave != null && store.list(project, null, null, wave, null).isEmpty()) {
            throw new IllegalStateException("no tickets in wave " + wave + " of " + project);
        }
        stopAuto();
        AutoDispatch policy = AutoDispatch.of(maxConcurrent, budgetUsd, includeStale);
        RecurringWaves previous;
        RecurringWaves loop;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("fleet control is closed");
            }
            previous = waves;
            waves = null;
            long generation = ++wavesGeneration;
            loop = new RecurringWaves(store, project, policy, wave,
                    () -> DispatchGuard.runningIds(repoRoot),
                    (id, attempt) -> dispatchWave(store, project, id, policy, generation, attempt),
                    notice -> com.opencode.ide.client.ClientLog.warning("fleet waves: " + notice),
                    null);
            waves = loop;
        }
        // started OUTSIDE the control monitor: the loop's cycles take this
        // monitor from their launch seam (dispatchWave), so starting it
        // while holding it would invert the lock order.
        loop.start(FleetTuning.WAVE_LOOP_PERIOD);
        if (previous != null) {
            previous.stop();
        }
    }

    /**
     * The wave-admission seam (the recurring twin of {@link #dispatchAuto}):
     * revalidates the ticket against the ACTIVE wave at admission time —
     * the wave may have moved on since the drain planned it.
     */
    private void dispatchWave(TaskStore store, String project, String id,
            AutoDispatch policy, long generation, DispatchScheduler.LaunchAttempt attempt) {
        DispatchGuard.admit(repoRoot, policy.maxConcurrent(), () -> {
            synchronized (this) {
                RecurringWaves loop = waves;
                if (closed || generation != wavesGeneration || loop == null) {
                    throw new DispatchGuard.AdmissionDeferred("recurring waves stopped or replaced");
                }
                String wave = loop.activeWave();
                // Plans are advisory. Revalidate readiness and spend under the
                // same process lock used to count and reserve capacity.
                var scope = store.list(project, null, null, null, null);
                var candidate = scope.stream()
                        .filter(t -> id.equals(t.id) && wave != null && wave.equals(t.sprint)).toList();
                var overview = CostOverview.of(scope);
                var current = policy.withEstimateUsd(AutoDispatch.calibratedEstimate(overview)).plan(candidate,
                        com.opencode.ide.tasks.StageReadiness.evaluate(scope),
                        overview, DispatchGuard.runningIds(repoRoot));
                if (!current.launch().contains(id)) {
                    throw new DispatchGuard.AdmissionDeferred("ticket no longer admissible: " + id);
                }
                dispatchLocked(project, id, DEFAULT_TIMEOUT, policy, attempt);
            }
            return null;
        });
    }

    /** Disables the recurring-waves mode; accepted workers settle normally. */
    public void stopWaves() {
        RecurringWaves previous;
        synchronized (this) {
            previous = waves;
            waves = null;
            wavesGeneration++;
        }
        if (previous != null) {
            previous.stop();
        }
    }

    /**
     * The recurring-waves state for the {@code fleet_waves_status} tool:
     * enabled/running, project, active wave, waves planned, stop reason,
     * budget vs. spend, and the NEEDS-HUMAN rows (blocked tickets with no
     * in-flight retry — the human's only regular duty).
     */
    public Map<String, Object> wavesStatus() {
        RecurringWaves loop;
        synchronized (this) {
            loop = waves;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (loop == null) {
            out.put("enabled", false);
            out.put("running", false);
            out.put("hint", "recurring waves are OFF by default - fleet_waves_start enables them per project");
            return out;
        }
        RecurringWaves.Status s = loop.status();
        out.put("enabled", true);
        out.put("running", s.running());
        out.put("project", s.project());
        out.put("wave", s.wave() == null ? "" : s.wave());
        out.put("waves_planned", s.wavesPlanned());
        out.put("stop_reason", s.stopReason() == null ? "" : s.stopReason().name());
        out.put("budget_usd", s.budgetUsd());
        out.put("spend_usd", s.spendUsd());
        java.util.List<Map<String, String>> needsHuman = new java.util.ArrayList<>();
        for (com.opencode.ide.fleet.dispatch.NeedsHuman.Escalation e : s.needsHuman()) {
            needsHuman.add(Map.of("id", e.id(), "blocker", e.blocker() == null ? "" : e.blocker()));
        }
        out.put("needs_human", needsHuman);
        out.put("last_notice", s.lastNotice());
        return out;
    }

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
                bridge)
                // U-021 autonomous acceptance: a merged run that settles
                // in-review gets a reviewer session whose verdict drives
                // done+advance / send-back through the store — the V
                // pipeline drives itself per stage
                .withAutonomousAcceptance();
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
            public boolean serverAlive() {
                return server.isRunning();
            }

            @Override
            public Long serverPid() {
                return server.getProcessId();
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
        this(storeRoot, engineFactory, Executors.newCachedThreadPool(daemons()));
    }

    /** Injected dispatch executor; ownership transfers to this control. */
    public FleetControl(Path storeRoot, Function<Path, Engine> engineFactory, ExecutorService executor) {
        this.storeRoot = storeRoot;
        this.repoRoot = repoRootOf(storeRoot);
        this.engineFactory = engineFactory;
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
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
    public Engine engine() {
        return DispatchGuard.exclusive(repoRoot, () -> {
            synchronized (this) {
                return engineLocked();
            }
        });
    }

    private Engine engineLocked() {
        if (closed) {
            throw new IllegalStateException("fleet control is closed");
        }
        if (engine == null) {
            engine = engineFactory.apply(storeRoot);
        } else if (!engine.serverAlive()) {
            // B-004 stale endpoint: PROBE BEFORE REUSE. A serve that died
            // (killed by an operator, an OOM kill, a crashed child) must
            // never make every later dispatch fail with "Cannot reach
            // opencode server at <recorded endpoint>" - close the dead
            // engine (its event stream) and spawn a fresh one, exactly like
            // the lazy first spawn. Live-found 2026-09-17: only a full
            // daemon restart used to recover from this.
            com.opencode.ide.client.ClientLog.warning(
                    "fleet: the spawned opencode serve is dead - respawning before reuse"
                            + " (the recorded endpoint went stale)");
            try {
                engine.close();
            } catch (RuntimeException e) {
                com.opencode.ide.client.ClientLog.warning(
                        "closing the dead engine failed (ignored): " + e.getMessage());
            }
            engine = engineFactory.apply(storeRoot);
        }
        return engine;
    }

    /**
     * B-004: closes the engine - killing the spawned {@code opencode serve}
     * process tree it owns - when nothing is in flight, so the serve's locks
     * release: its file watchers and bash-tool children hold handles INSIDE
     * the task worktrees, which is what made {@code fleet_reset} fail with
     * "Permission denied" and left the branch behind. The next dispatch
     * spawns a fresh serve lazily via {@link #engineLocked()}. Never spawns
     * an engine and never throws.
     *
     * @return the OS pid of the killed serve when a LIVE serve was killed,
     *         else {@code null} (no engine, serve already dead, or other
     *         launches still in flight)
     */
    public Long recycleEngineIfIdle(String reason) {
        Engine current;
        Long pid;
        synchronized (this) {
            if (closed || engine == null || !inFlight.isEmpty()) {
                return null;
            }
            current = engine;
            pid = engine.serverAlive() ? engine.serverPid() : null;
            engine = null;
        }
        com.opencode.ide.client.ClientLog.info("fleet: recycling the engine (" + reason + ")"
                + (pid != null ? " - killing the spawned opencode serve pid " + pid : ""));
        try {
            current.close();
        } catch (RuntimeException e) {
            com.opencode.ide.client.ClientLog.warning(
                    "closing the recycled engine failed (ignored): " + e.getMessage());
        }
        return pid;
    }

    /**
     * B-004: the live serve's OS pid for diagnostics - {@code fleet_reset}
     * names it in the error when worktree removal keeps failing. {@code null}
     * when there is no engine, the serve is dead, or the pid is unknown.
     */
    public Long engineServePid() {
        synchronized (this) {
            return engine != null && engine.serverAlive() ? engine.serverPid() : null;
        }
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
        DispatchGuard.exclusive(repoRoot, () -> {
            synchronized (this) {
                dispatchLocked(project, ticketId, timeout);
            }
            return null;
        });
    }

    private void dispatchLocked(String project, String ticketId, Duration timeout) {
        dispatchLocked(project, ticketId, timeout, null, null);
    }

    private void dispatchLocked(String project, String ticketId, Duration timeout, AutoDispatch autoPolicy,
            DispatchScheduler.LaunchAttempt attempt) {
        if (closed) {
            throw new IllegalStateException("fleet control is closed");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        DispatchGuard guard = DispatchGuard.acquire(repoRoot, project, ticketId);
        try {
            Engine e = engineLocked();
            inFlight.add(ticketId);
            com.opencode.ide.client.ClientLog.info(
                    "fleet dispatch " + ticketId + " accepted (budget " + timeout + ")");
            executor.execute(() -> {
                FleetJob settled = null;
                try {
                    try {
                        try {
                            if (autoPolicy == null) {
                                settled = e.fleet().launch(project, ticketId, repoRoot, timeout);
                            } else {
                                settled = e.fleet().launchAuto(project, ticketId, repoRoot, timeout, guard, autoPolicy.includeStale());
                            }
                        } finally {
                            com.opencode.ide.client.ClientLog.info("fleet dispatch " + ticketId + " settled");
                        }
                    } catch (DispatchGuard.AdmissionDeferred ex) {
                        if (attempt != null) {
                            attempt.deferred();
                        }
                        com.opencode.ide.client.ClientLog.info("fleet launch of " + ticketId + " deferred: " + ex.getMessage());
                    } catch (RuntimeException ex) {
                        // the launch violated its never-throws contract: the
                        // engine's state is unknown - treat it as a failure
                        // for the recycle decision below
                        settled = new FleetJob(ticketId, null, null, FleetJob.State.FAILED, ex.getMessage());
                        com.opencode.ide.client.ClientLog.warning(
                                "fleet launch of " + ticketId + " threw: " + ex.getMessage());
                    } finally {
                        inFlight.remove(ticketId);
                        guard.close();
                    }
                    // B-004, after the in-flight marker is gone so sibling
                    // launches still holding it protect their serve
                    recycleEngineAfterFailure(ticketId, settled);
                    StoreSync.sync(storeRoot, "opencode fleet: store sync after " + ticketId);
                } catch (RuntimeException ex) {
                    com.opencode.ide.client.ClientLog.warning(
                            "store auto-sync failed for " + ticketId + ": " + ex.getMessage());
                }
            });
        } catch (RuntimeException | Error ex) {
            inFlight.remove(ticketId);
            guard.close();
            throw ex;
        }
    }

    /**
     * B-004 leaked-serve fix: a launch that settles FAILED leaves nothing on
     * the spawned serve worth keeping - and the serve actively harms recovery:
     * its watchers and bash-tool children hold handles INSIDE the failed
     * ticket's worktree, so {@code fleet_reset} hit "Permission denied" and
     * the branch survived to block the re-dispatch ("branch already exists").
     * When no other launch is in flight (this ticket's marker is already
     * gone), the engine - and with it the serve it owns - is closed; no
     * orphan survives the job end. The next dispatch spawns a fresh serve
     * lazily. A MERGED launch keeps the serve alive for healthy reuse.
     */
    private void recycleEngineAfterFailure(String ticketId, FleetJob settled) {
        if (settled == null || settled.state() != FleetJob.State.FAILED) {
            return;
        }
        Long killed = recycleEngineIfIdle("ticket " + ticketId + " failed");
        if (killed != null) {
            com.opencode.ide.client.ClientLog.info("fleet: killed the spawned opencode serve (pid "
                    + killed + ") after ticket " + ticketId + " failed - the next dispatch respawns it");
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
        return DispatchGuard.sweepStale(repoRoot);
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
            FleetJob previous = merged.get(id);
            if (previous == null || previous.state() != FleetJob.State.RUNNING) {
                merged.put(id, new FleetJob(id, null, null, FleetJob.State.RUNNING, "launch accepted"));
            }
        }
        return Map.copyOf(merged);
    }

    @Override
    public void close() {
        Engine closing;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            autoGeneration++;
            closing = engine;
        }
        stopAuto();
        stopWaves();
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
        if (closing != null) {
            closing.close();
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
