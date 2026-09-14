package com.opencode.ide.board.fleet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.opencode.ide.chat.ChatPermissionSink;
import com.opencode.ide.chat.ChatPermissions;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.fleet.Bootstrap;
import com.opencode.ide.fleet.FleetJob;
import com.opencode.ide.fleet.FleetPermissionBridge;
import com.opencode.ide.fleet.FleetRunner;
import com.opencode.ide.fleet.PermissionQueue;
import com.opencode.ide.fleet.RoleAgents;
import com.opencode.ide.fleet.SseSessionEvents;
import com.opencode.ide.fleet.TaskFleet;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.tasks.TaskStore;

/**
 * Production {@link FleetLauncher}: adapts the headless {@link TaskFleet}
 * engine to the board's one-call seam.
 *
 * <p>Process-wide by design: one shared daemon executor and one
 * {@link TaskFleet} — and therefore one merge-back lock — per store root, so
 * parallel launches from multiple Board view instances still merge serially.
 * The executor is never shut down (daemon threads, Eclipse-session lifetime,
 * mirroring {@link com.opencode.ide.board.model.FleetJobsModel#getDefault()}).
 * The most recently constructed launcher's client/worktree suppliers feed
 * fleet creation (the Board view re-constructs the launcher when its inputs
 * change). An optional per-launch {@link Bootstrap} (supplier-injected,
 * re-read at launch time) rides every fleet launch while set.</p>
 *
 * <p>Fleet cache: entries are keyed by {@code (root, suppliers-generation)}.
 * Every constructor publishes a new generation, so a per-root fleet built
 * from old suppliers is never served afterwards — the next launch rebuilds
 * with the current suppliers (stale-generation entries are pruned on the
 * next creation for that root). Creation uses one monitor per root
 * (double-checked get/lock/create), so a launch for root B never waits
 * behind a seconds-long server spawn for root A.</p>
 *
 * <p>Completion is the fleet watchdog's probe loop (see TaskFleet); the
 * {@link SseSessionEvents} built here rides the primary
 * connection's single {@code /event} stream via {@link OpencodeConnection}
 * listeners (with a one-poll fallback on stream drop), replacing the 1 Hz
 * status polling of the default engine path (ROADMAP H3.4).</p>
 */
public final class TaskFleetLauncher implements FleetLauncher {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "board-fleet-launch");
        thread.setDaemon(true);
        return thread;
    });

    /** How long a launched agent session may run before the fleet times it out. */
    private static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(30);

    private static final Map<CacheKey, TaskFleet> FLEETS_BY_ROOT = new ConcurrentHashMap<>();

    /** One monitor per store root, so creations for different roots never queue behind each other. */
    private static final ConcurrentHashMap<Path, Object> ROOT_LOCKS = new ConcurrentHashMap<>();

    private static final AtomicLong GENERATION = new AtomicLong();

    /**
     * Process-wide queue of permission requests raised by unattended fleet
     * sessions (ROADMAP H5 item 1): the bridge feeds it from the primary
     * connection's event stream, the Fleet view's "Permissions (n)" action
     * drains it. Answers post through the primary connection's client (which
     * may block while it spawns the server - answer off the UI thread).
     */
    private static final PermissionQueue PERMISSIONS = new PermissionQueue(
            (sessionId, permissionId, response, remember) -> OpencodeConnection.getInstance()
                    .getClient().respondToPermission(sessionId, permissionId, response, remember));

    /** Feeds {@link #PERMISSIONS} from the primary event stream; sessions are watched via the wrapped runner client. */
    private static final FleetPermissionBridge PERMISSION_BRIDGE = new FleetPermissionBridge(PERMISSIONS);

    /** Guards the one-time bridge subscription (Eclipse-session lifetime, never unsubscribed). */
    private static final AtomicBoolean PERMISSION_BRIDGE_SUBSCRIBED = new AtomicBoolean();

    /**
     * Feeds chat-session (non-fleet) permission asks into {@link #PERMISSIONS}
     * — the same queue the Fleet view's "Permissions (n)" action drains; a
     * {@code replied} event marks the request answered so stale entries drop
     * from {@code pending()}.
     */
    private static final ChatPermissionSink CHAT_SINK = new ChatPermissionSink() {
        @Override
        public void asked(PermissionRequest request, OpencodeClient client) {
            PERMISSIONS.offer(request);
        }

        @Override
        public void replied(String sessionId, String requestId) {
            PERMISSIONS.offer(new PermissionRequest(sessionId, requestId, null, null, null,
                    PermissionRequest.Status.ANSWERED));
        }
    };

    /** Guards the one-time chat-sink registration (Eclipse-session lifetime, never cleared). */
    private static final AtomicBoolean CHAT_SINK_SET = new AtomicBoolean();

    /** Suppliers snapshot + its generation, published as one immutable value per construction. */
    private record SuppliersState(long generation, Suppliers suppliers) {
    }

    private record CacheKey(Path root, long generation) {
    }

    private static volatile SuppliersState state = new SuppliersState(0, Suppliers.unset());

    private record Suppliers(Supplier<OpencodeClient> clients, Supplier<WorktreeManager> worktrees) {

        static Suppliers unset() {
            return new Suppliers(
                    () -> { throw new IllegalStateException("no TaskFleetLauncher constructed yet"); },
                    () -> { throw new IllegalStateException("no TaskFleetLauncher constructed yet"); });
        }
    }

    private final Supplier<Path> storeRootSupplier;

    /** Supplies the optional per-launch bootstrap (re-read at launch time; see the 4-arg constructor). */
    private final Supplier<Bootstrap> bootstrapSupplier;

    /**
     * The per-launch engine call
     * ({@link TaskFleet#launch(String, String, Path, Duration, Bootstrap)}
     * with the board's timeout); a swappable seam so tests can capture what
     * the launcher hands to the fleet (see {@link #useEngineLaunchForTests}).
     */
    @FunctionalInterface
    public interface EngineLaunch {

        FleetJob launch(TaskFleet fleet, String project, String taskId, Path repoRoot,
                Duration timeout, Bootstrap bootstrap);
    }

    private static final EngineLaunch REAL_ENGINE_LAUNCH =
            (fleet, project, taskId, repoRoot, timeout, bootstrap)
                    -> fleet.launch(project, taskId, repoRoot, timeout, bootstrap);

    /** The engine call used per launch; volatile so an injected test seam applies immediately. */
    private static volatile EngineLaunch engineLaunch = REAL_ENGINE_LAUNCH;

    /**
     * @param clientSupplier    supplies the opencode client; may block (spawn) —
     *                          called on the executor thread only
     * @param worktreesSupplier supplies the worktree manager (git CLI backed)
     * @param storeRootSupplier supplies the current task-store root
     *                          ({@code <repo>/.opencode/tasks}); called at
     *                          launch time so toolbar changes take effect
     */
    public TaskFleetLauncher(Supplier<OpencodeClient> clientSupplier,
            Supplier<WorktreeManager> worktreesSupplier,
            Supplier<Path> storeRootSupplier) {
        this(clientSupplier, worktreesSupplier, storeRootSupplier, () -> null);
    }

    /**
     * @param bootstrapSupplier supplies the optional per-launch bootstrap
     *                          (see {@link Bootstrap}); re-read at launch
     *                          time so store edits apply without a restart;
     *                          a {@code null} supply or a failing supplier
     *                          degrades to no bootstrap and never blocks the
     *                          launch
     */
    public TaskFleetLauncher(Supplier<OpencodeClient> clientSupplier,
            Supplier<WorktreeManager> worktreesSupplier,
            Supplier<Path> storeRootSupplier,
            Supplier<Bootstrap> bootstrapSupplier) {
        state = new SuppliersState(GENERATION.incrementAndGet(),
                new Suppliers(clientSupplier, worktreesSupplier));
        this.storeRootSupplier = storeRootSupplier;
        this.bootstrapSupplier = bootstrapSupplier;
    }

    /** The process-wide queue of pending permission requests raised by fleet sessions. */
    public static PermissionQueue permissions() {
        return PERMISSIONS;
    }

    @Override
    public FleetJobHandle launch(String project, String ticketId) {
        Path storeRoot = storeRootSupplier.get();
        Path repoRoot = repoRootOf(storeRoot);
        String worktreeGuess = repoRoot == null ? null
                : com.opencode.ide.git.FleetGit.worktreePath(repoRoot, ticketId).toString();
        if (repoRoot == null || !Files.isDirectory(repoRoot.resolve(".git"))) {
            FleetJobHandle failed = new FleetJobHandle(
                    ticketId, null, worktreeGuess, FleetJobHandle.State.FAILED,
                    "no git repository at " + repoRoot + " (store root " + storeRoot + ")");
            FleetJobsModelHolder.model().add(failed);
            return failed;
        }
        // P1-3: the Board's launch path rides the SAME cross-engine dispatch
        // marker as FleetControl.dispatch — a Board launch and a chat
        // fleet_dispatch of the same ticket can never both pre-claim.
        Path marker = repoRoot.resolve(".git").resolve("opencode-fleet")
                .resolve(ticketId + ".dispatch");
        try {
            Files.createDirectories(marker.getParent());
            Files.createFile(marker);
            Files.writeString(marker, "pid=" + ProcessHandle.current().pid()
                    + " board at " + java.time.Instant.now() + "\n");
        } catch (java.nio.file.FileAlreadyExistsException e) {
            FleetJobHandle refused = new FleetJobHandle(
                    ticketId, null, worktreeGuess, FleetJobHandle.State.FAILED,
                    "ticket " + ticketId + " is being dispatched by another engine"
                            + " (marker " + marker + " exists) - fleet_reset removes stale markers");
            FleetJobsModelHolder.model().add(refused);
            return refused;
        } catch (java.io.IOException e) {
            FleetJobHandle failed = new FleetJobHandle(
                    ticketId, null, worktreeGuess, FleetJobHandle.State.FAILED,
                    "cannot create the dispatch guard: " + e.getMessage());
            FleetJobsModelHolder.model().add(failed);
            return failed;
        }
        FleetJobHandle running = new FleetJobHandle(
                ticketId, null, worktreeGuess, FleetJobHandle.State.RUNNING, "launching…");
        FleetJobsModelHolder.model().add(running);
        Path launchRepoRoot = repoRoot;
        EXECUTOR.execute(() -> {
            FleetJobHandle result;
            try {
                TaskFleet fleet = fleetFor(storeRoot);
                result = map(engineLaunch.launch(fleet, project, ticketId, launchRepoRoot,
                        LAUNCH_TIMEOUT, currentBootstrap()));
            } catch (RuntimeException e) {
                result = new FleetJobHandle(ticketId, null, worktreeGuess,
                        FleetJobHandle.State.FAILED, String.valueOf(e.getMessage()));
            } catch (Error e) {
                // never funnel an Error (OOM & co.) into a UI row — rethrow, the
                // daemon executor dies with a log, the JVM decides
                throw e;
            } finally {
                // always release the marker (crash leaves it for reconciliation)
                try {
                    Files.deleteIfExists(marker);
                } catch (java.io.IOException ignored) {
                    // best-effort; a stale marker is recoverable via fleet_reset
                }
            }
            FleetJobsModelHolder.model().update(result);
        });
        return running;
    }

    /**
     * The fleet for {@code storeRoot}, built from the CURRENT suppliers
     * generation. Per-root monitor (never the map), double-checked: waiting
     * for root A's spawn does not stall a creation for root B.
     */
    private static TaskFleet fleetFor(Path storeRoot) {
        Path root = storeRoot.toAbsolutePath().normalize();
        SuppliersState current = state;
        TaskFleet fleet = FLEETS_BY_ROOT.get(new CacheKey(root, current.generation()));
        if (fleet != null) {
            return fleet;
        }
        Object lock = ROOT_LOCKS.computeIfAbsent(root, r -> new Object());
        synchronized (lock) {
            current = state; // a new launcher may have been constructed while we waited
            CacheKey key = new CacheKey(root, current.generation());
            fleet = FLEETS_BY_ROOT.get(key);
            if (fleet != null) {
                return fleet;
            }
            fleet = createFleet(current.suppliers(), root);
            FLEETS_BY_ROOT.put(key, fleet);
            prune(root, key.generation());
            return fleet;
        }
    }

    /** Drops this root's entries from older suppliers generations (lazy eviction). */
    private static void prune(Path root, long keepGeneration) {
        for (CacheKey key : FLEETS_BY_ROOT.keySet()) {
            if (key.root().equals(root) && key.generation() != keepGeneration) {
                FLEETS_BY_ROOT.remove(key);
            }
        }
    }

    private static TaskFleet createFleet(Suppliers current, Path storeRoot) {
        OpencodeClient client = current.clients().get();
        // SSE completion rides the primary connection's single /event stream;
        // the client doubles as the one-poll fallback when that stream drops.
        SseSessionEvents events = new SseSessionEvents(primaryEventSubscriber(), client);
        connectPermissionBridge();
        // The runner's client is wrapped so its sessions are permission-watched
        // from creation - the blocking prompt call is where unattended asks wait.
        return new TaskFleet(
                new FleetRunner(PERMISSION_BRIDGE.watching(client), current.worktrees().get()),
                new TaskStore(storeRoot),
                new RoleAgents(),
                events,
                null,
                PERMISSION_BRIDGE);
    }

    /**
     * Subscribes the permission bridge to the primary connection's SSE fan-out
     * exactly once per process (idempotent across fleet rebuilds).
     */
    private static void connectPermissionBridge() {
        if (PERMISSION_BRIDGE_SUBSCRIBED.compareAndSet(false, true)) {
            primaryEventSubscriber().subscribe(PERMISSION_BRIDGE::onEvent);
        }
    }

    /**
     * Registers {@link #CHAT_SINK} with the chat bundle's permission seam,
     * once per process (idempotent; last-set-wins registry, so later explicit
     * sets still win).
     */
    public static void connectChatPermissions() {
        if (CHAT_SINK_SET.compareAndSet(false, true)) {
            ChatPermissions.setSink(CHAT_SINK);
        }
    }

    /** @return the sink feeding chat-session asks into {@link #permissions()} */
    public static ChatPermissionSink chatPermissionSink() {
        return CHAT_SINK;
    }

    /** Subscribes to the primary connection's SSE fan-out; the handle unsubscribes. */
    private static SseSessionEvents.Subscriber primaryEventSubscriber() {
        return listener -> {
            OpencodeConnection connection = OpencodeConnection.getInstance();
            com.opencode.ide.client.OpencodeEventListener adapter = listener::accept;
            connection.addEventListener(adapter);
            return () -> connection.removeEventListener(adapter);
        };
    }

    private static FleetJobHandle map(FleetJob job) {
        return new FleetJobHandle(job.taskId(), job.sessionId(), job.worktree() == null ? null
                : job.worktree().toString(), FleetJobHandle.State.valueOf(job.state().name()),
                job.detail());
    }

    private static Path repoRootOf(Path storeRoot) {
        if (storeRoot == null) {
            return null;
        }
        Path opencode = storeRoot.getParent();
        return opencode == null ? null : opencode.getParent();
    }

    /** The stored bootstrap for this launch, or {@code null} when unset/unavailable — never blocks a launch. */
    private Bootstrap currentBootstrap() {
        if (bootstrapSupplier == null) {
            return null;
        }
        try {
            return bootstrapSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Test seam: replaces the per-launch engine call ({@link #resetForTests()} restores the real one). */
    public static void useEngineLaunchForTests(EngineLaunch override) {
        engineLaunch = Objects.requireNonNull(override, "override");
    }

    /** Test seam: clear the process-wide fleet cache, root locks, supplier state and engine seam (isolates launcher tests). */
    public static void resetForTests() {
        FLEETS_BY_ROOT.clear();
        ROOT_LOCKS.clear();
        state = new SuppliersState(0, Suppliers.unset());
        engineLaunch = REAL_ENGINE_LAUNCH;
    }

    /** Indirection so tests can substitute the observed registry if ever needed. */
    static final class FleetJobsModelHolder {
        static com.opencode.ide.board.model.FleetJobsModel model() {
            return com.opencode.ide.board.model.FleetJobsModel.getDefault();
        }
    }
}
