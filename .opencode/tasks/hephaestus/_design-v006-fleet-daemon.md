# V-006 Design — Fleet daemon mode: the engine outlives any client session

## About this document
- **Kind:** `design` (how a component is built)
- **Read by:** the implementer of V-006 and reviewers; **written by:** a chat design session (2026-09-16)
- **Related:** ticket V-006 in this store (`.opencode/tasks/hephaestus/V-006.md`); grounded in
  `FleetControl`/`FleetStdioMain`/`DispatchGuard` (fleet bundle), `OpencodeServerLauncher` (client
  bundle), `eclipse/fleet-tools.ps1`, and AGENTS.md "Chat-first control plane (H7)".

---

## Goals / Non-goals

**Goals**

1. The fleet engine — one `FleetControl` (lazily spawned `opencode serve`, job map, permission
   queue, auto-dispatch loop) — becomes a **detached per-repo daemon** that outlives any client
   session (opencode TUI stdio server, Eclipse, nothing at all).
2. A **reconnecting client sees running jobs** dispatched by a previous session (V-006 AC3).
3. Start/stop **headlessly from bash/pwsh** (AC2), no Eclipse.
4. The **Auto-dispatch loop runs inside the daemon** (AC4) — it is already SWT-free Tier 1 code
   (`FleetControl.startAuto`, `dispatch/DispatchScheduler`); hosting it in the daemon JVM is what
   makes it persistent.
5. **Peer engines keep working unchanged**: Eclipse's own `FleetControl`, a TUI session with the
   daemon disabled — `DispatchGuard` markers + `admission.lock` already serialize cross-engine
   dispatch; the daemon is *one more peer, the persistent one*, not an exclusive owner.

**Non-goals (explicitly deferred)**

- Multi-machine / remote access, TLS (loopback-only MVP).
- A merged cross-engine job view (that is F-004's store-based reconstruction; the daemon view
  covers only daemon-owned runs).
- Event push / streaming notifications to attached clients (tools stay pull-based).
- Per-client identity/ACLs (one shared daemon password).
- Auto-start of the daemon at boot/login; idle auto-exit is an open question, not a goal.

## Transport

**Decision: TCP socket on 127.0.0.1, ephemeral port, advertised in the pidfile; same line-based
JSON-RPC framing as the stdio server.**

- Pure JDK (`ServerSocket`/`Socket`) — keeps the fleet bundle Tier 1, Eclipse-free, no new
  dependency. **Named pipes are rejected for MVP**: pure Java can *connect* to
  `\\.\pipe\*` but cannot *serve* one (no `CreateNamedPipe` in the JDK) — serving would need
  JNI/JNA or an external helper, exactly the kind of weight MVP avoids.
- The daemon reuses `McpDispatcher.handle(String)` per line — the same minimal MCP JSON-RPC
  subset (`initialize`, `ping`, `tools/list`, `tools/call`) the stdio loop speaks. The stdio
  proxy then degenerates to a verbatim line pump (stdin → socket, socket → stdout).
- Loopback bind = machine-local by construction; auth (below) is defense in depth against other
  local processes, not a network boundary.
- Linux refinement (not MVP): swap the accept socket for a Unix-domain
  `ServerSocketChannel` (JDK 16+ `UnixDomainSocketAddress`) behind the same transport seam —
  filesystem ACLs then double as auth. Windows named pipe can be revisited then.

## Discovery + Pidfile

**Decision: `<repo>/.git/opencode-fleet/daemon.json`, one daemon per repository.**

Evaluation of the location (asked by AC1):

- **Pro — inherits per-repo scoping for free:** engines are per-repo today
  (`FleetControl.repoRootOf(storeRoot)`: `.opencode/tasks` → repo root; markers, claims,
  worktrees are all repo-scoped). A daemon per repo matches that grain; no cross-repo port
  registry is needed because each repo's pidfile carries its own port.
- **Pro — it is already the cross-engine coordination directory:** `FleetGit.fleetRoot(repo)` =
  `<repo>/.git/opencode-fleet` hosts `<ticket>.dispatch` markers, `admission.lock`,
  `repository.lock`, `<id>.project` claims and the fleet worktrees. The pidfile joins its
  natural family, and the liveness convention is already proven there (`pid=<N> start=<instant>`
  checked against `ProcessHandle`, see `DispatchGuard.sweepMarker`).
- **Pro — never committed/merged:** `.git` is outside every worktree and outside StoreSync's
  store-scoped sync path; the pidfile creates zero git noise.
- **Con (accepted):** a re-clone wipes `.git` — acceptable, a re-clone invalidates fleet
  worktrees anyway. A linked-worktree checkout has a `.git` *file* — the daemon keys on the
  **main** repo root (same assumption `GitWorktreeManager` already makes).

Pidfile content (written atomically, temp-file + move):

```json
{ "pid": 1234, "start": "2026-09-16T09:00:00Z", "port": 51321,
  "password": "<64-hex>", "started": "2026-09-16T09:00:00Z",
  "serverPid": 5678, "serverPort": 51322 }
```

- `pid` + `start` (start instant) = liveness, identical semantics to `DispatchGuard`'s OWNER
  marker parsing — reuse that pattern in a small `DaemonPidfile` helper (read / atomic-write /
  isAlive / remove).
- `serverPid`/`serverPort` record the spawned `opencode serve` **once the lazy engine starts**,
  so a successor can kill an orphaned server after a daemon crash (below).
- Stale pidfile (pid dead, or pid alive with different start instant — pid reuse) = residue;
  the next starter cleans it up **under `admission.lock`** to avoid two starters racing.

Discovery = read the pidfile; there is no port scanning and no registry. A client (or the pwsh
script) answers "is there a daemon for this repo?" with one file read + one `ProcessHandle`
check.

## Auth

**Decision: reuse the generated-password path — the daemon's password is generated at start
(`FleetControl.resolvePassword`, 32 random bytes → 64 hex; `FLEET_DAEMON_PASSWORD` env wins if
set) and published inside `daemon.json`.**

- **How a second client learns it: it reads the pidfile.** The trust boundary is therefore
  *filesystem access to `.git/opencode-fleet/`* — which is already the fleet's trust boundary:
  a process that can write there can forge dispatch markers and take `admission.lock` anyway.
  The password's job is only to stop read-less local processes from drive-by-connecting to a
  scanned port (defense in depth, and meaningful on multi-user Linux loopback).
- Same hygiene rule as `resolvePassword` today: never logged, never in an exception message.
- **Handshake:** the first line on a new connection must be
  `{"jsonrpc":"2.0","id":1,"method":"daemon/hello","params":{"password":"…"}}`. Wrong or
  missing → the daemon closes the socket before dispatching anything. Per-request auth was
  considered and rejected (complicates proxy forwarding for no gain).
- Rotation = restart: every daemon start generates fresh (unless env-pinned); stop/start is
  revocation. Deferred: TLS, per-client identities, multi-machine.

## Lifecycle & Orphans

- **Start (headless, AC2):** `eclipse/fleet-daemon.ps1 start [-Root <store>]` — modeled on
  `fleet-tools.ps1` (same jar/gson resolution), launches
  `java -cp … com.opencode.ide.fleet.FleetDaemonMain --root <store>` **detached**
  (`Start-Process -WindowStyle Hidden`; Linux: `setsid`/`nohup`). The daemon binds loopback
  port 0, writes `daemon.json`, and idles **without spawning the engine** — the engine stays
  lazy exactly as today (first `fleet_dispatch`/`startAuto` spawns `opencode serve`), and
  `serverPid`/`serverPort` are backfilled into the pidfile then.
- **Stop (graceful):** a `daemon/shutdown` request over the socket (authed) — sent by
  `fleet-daemon.ps1 stop` or a new `fleet_daemon_shutdown` tool. The daemon runs
  `FleetControl.close()`, i.e. the existing R3 drain-then-kill: stop admission, bounded grace
  (`FleetTuning.SHUTDOWN_GRACE`, 30 s) for in-flight merges to settle, then hard-cancel, kill
  the spawned server tree (`OpencodeServerLauncher.stop()` destroys descendants), remove the
  pidfile. **Semantic to document:** graceful stop *does* end in-flight runs (after the grace
  window) — "disconnect does not kill runs" is about *client* detach, not daemon shutdown.
  Protocol-level shutdown is preferred over `taskkill` because Windows has no portable
  graceful-signal story; `taskkill /T` on the recorded pid remains a last-resort fallback in
  the script when the port does not answer.
- **Crash (SIGKILL / power):** no hook runs; children may orphan. Recovery reuses the existing
  machinery — the next engine start (any peer, including a new daemon) already runs
  `DispatchGuard.sweepStale` (dead-pid markers) and F-002 `reconcileOrphanedClaims` (release
  claims whose worktree/branch no longer exists). New in this design: a starter finding a
  dead-pid `daemon.json` first kills the recorded `serverPid` if it is still alive (pid +
  start-instant verified), then sweeps — so an orphaned `opencode serve` cannot leak.
- **Bookkeeping invariant (unchanged):** every launch settles on the ticket (F-001); the
  daemon's job map is a projection, the store is the truth. A hard daemon death mid-run leaves
  the ticket claimed until the next engine's reconciliation — same as a crashed stdio engine
  today.
- **Watchdog (unchanged):** the stall watchdog (idle `STALL_TIMEOUT` ≈ 5 min, with
  permission-pause) and per-ticket budget abort hung sessions *inside* the daemon.
- **Client detach:** dropping a proxied client closes one TCP connection; the daemon notices
  and keeps everything. The daemon holds no client state beyond the socket (tools are
  pull-based).
- Deferred hardening: Windows Job Object so even a SIGKILLed daemon takes the server tree down.

## Attach / Detach contract (what FleetStdioMain becomes)

- **Modes via `FLEET_DAEMON` env** (settable per MCP server in `opencode.json`):
  - `off` — today's behavior: the stdio process owns a local `FleetControl` (shutdown hook
    kills engine + jobs). **Initial default**; flipped to `auto` only after the V-004
    validation run (see Rollout).
  - `auto` — read `daemon.json`; live daemon → **thin proxy**; none → local engine (today's
    behavior).
  - `always` — proxy or a clean error naming the pidfile (for sessions that must not
    double-spawn engines).
- **Proxy behavior:** read pidfile → connect → `daemon/hello` handshake → pump JSON-RPC lines
  verbatim both ways. Tool surface is unchanged because the daemon serves the same
  `FleetToolProvider` via `McpDispatcher`: `fleet_jobs` from a *reconnecting* session shows the
  daemon's running jobs (AC3), `fleet_auto_*` drives the daemon's loop (AC4),
  `fleet_permissions*` reaches the daemon's queue.
- **Non-exclusivity (constraint):** attach is visibility + control of the *daemon's* runs, not
  exclusivity. Peer engines (Eclipse's FleetControl, `off`-mode sessions) keep dispatching;
  `DispatchGuard.admit` + `runningIds` count them cross-engine as today. Cross-engine
  *visibility* stays store-mediated (F-004) — the daemon does not merge peers' job maps.
- **Failure modes:** daemon dies while attached → the proxy sees EOF and exits non-zero (the
  MCP server "crashed" — opencode surfaces it and restarts the stdio server, which then
  re-evaluates the mode). Mid-call daemon death is indistinguishable from a crashed MCP
  server, which clients already tolerate. Daemon port hijack/parse failure → fall back per
  mode (`auto` → local engine + warning; `always` → error).
- **New tools** (served by both daemon and local engine for a uniform surface):
  `fleet_daemon_status` (pid, port, uptime, engine up?, auto loop, attached-client count),
  `fleet_daemon_shutdown` (= `daemon/shutdown`). Starting the daemon stays a *script* action
  (`fleet-daemon.ps1 start`) — no bootstrap-from-inside-a-session.

## Auto-dispatch in the daemon (without Eclipse)

No code motion: `FleetControl.startAuto` (FleetControl.java:103) already hosts
`DispatchScheduler.withFeedback(...)` + `AutoDispatch` + `CostOverview` + `StageReadiness` on a
daemon thread, SWT-free in the Tier-1 fleet bundle — the Board merely calls the same methods.
Inside the daemon JVM that loop simply outlives clients: `fleet_auto_start` from session A →
disconnect → `fleet_auto_status` from session B reports it running. Concurrent Board and daemon
loops remain safe: admission goes through `DispatchGuard.admit` with cross-engine
`runningIds`, and each `FleetControl` replaces only *its own* prior loop (generation bump).
One documented semantic: `fleet_auto_stop` from **any** attached client stops the daemon's
loop (single loop per engine, by design — single owner).

## Rollout / Compat

Slices (each an implementable ticket):

1. **Daemon core:** `FleetDaemonMain` + `FleetDaemon` (accept loop, `daemon/hello`,
   line-dispatch over `FleetToolProvider`, `daemon/shutdown`), `DaemonPidfile` (atomic write,
   pid+start liveness, stale cleanup incl. recorded server pid, all under `admission.lock`),
   `eclipse/fleet-daemon.ps1` start/stop/status. Tests over the fake-engine seam
   (`FleetControl(Path, Function<Path,Engine>)`): start/stop, crash-recovery (kill -9 style:
   dead pidfile → successor cleans serverPid + markers), pidfile atomicity.
2. **Proxy mode:** `FleetStdioMain` attach logic + `FLEET_DAEMON` env; `fleet_daemon_status` /
   `fleet_daemon_shutdown` tools. Tests: two sequential stdio attaches see the same job map;
   daemon-death mid-attach exits non-zero; `off` mode byte-identical to today.
3. **Default flip + docs:** `FLEET_DAEMON` default `off` → `auto` **after the V-004 validation
   run lands** (V-006 is gated on it); update AGENTS.md H7 and the fleet-tools.ps1 header.

Compat invariants: `opencode.json` config unchanged (same stdio command — the daemon is
opt-in via env until slice 3); the store format, `DispatchGuard` marker protocol, and
StoreSync discipline are untouched (the daemon writes markers with its own pid; peers' sweeps
keep working because liveness is ProcessHandle-based, not session-based).

**Linux (one paragraph):** the design is pure JDK and runs as-is — loopback TCP, same pidfile
under `.git/opencode-fleet/`, `ProcessHandle` liveness; the pwsh launcher exists but the
native path is `setsid java … FleetDaemonMain` under `nohup`. The only platform refinement
worth doing later is the Unix-domain accept socket (JDK 16+), which adds filesystem-ACL auth
for free; it slots behind the same `FleetDaemon` transport seam.

## Open questions

1. **Direct streamable-HTTP MCP exposure** (opencode `remote` server type) instead of the
   stdio proxy — removes one hop; `com.sun.net.httpserver` + `McpDispatcher` would do it in
   Tier 1. Defer until the proxy proves the daemon; decide then.
2. **Idle auto-exit** — should the daemon exit after N minutes with no jobs, no auto loop and
   no clients? Candidate `FleetTuning` knob (`FLEET_DAEMON_IDLE_EXIT_MS`, 0 = never). Decide
   at implementation.
3. **Permission-ask push** — attached clients learn about pending asks only by polling
   `fleet_permissions`; a `notifications/` channel over the daemon socket would improve
   unattended-run UX. Deferred (pull-only MVP).
4. **`fleet-daemon.ps1 list`** — multiple repos ⇒ multiple daemons/pidfiles; a machine-wide
   list helper may be wanted once people run more than one repo.
5. **Windows Job Object** hardening (guaranteed child-server cleanup on daemon SIGKILL) —
   implement or accept the successor-cleanup path? Lean: accept for MVP.
6. **Eclipse auto-attach** — should the Fleet view prefer the daemon when one exists? UI
   ticket, decided separately; either way cross-engine visibility stays F-004's store-based
   reconstruction.

## As built (2026-09-16, slices a+b)

Shipped and reactor-green; opt-in only (`FLEET_DAEMON` default `off`). deltas
against the design above, discovered during implementation:

- **Pidfile shape**: the 4-field form - `{"port","pid","token","startedAt"}`. The
  design's `serverPid`/`serverPort` fields (orphaned spawned-server reap) are
  **deferred**; the successor-cleanup path covers the MVP (open question 5:
  accepted). `startedAt` is the process-start instant (DispatchGuard
  OWNER-marker semantics) so the pid-reuse check works.
- **Hello response**: `{"jsonrpc":"2.0","id":1,"result":{"ok":true,"port":...,"pid":...}}`;
  refusals are `-32000` + connection close. Wrong-token `daemon/shutdown` keeps serving.
- **Daemon drain**: `FleetTuning.DAEMON_DRAIN_WAIT` (`FLEET_DAEMON_DRAIN_MS`, 5 s).
- **Launcher**: `eclipse/fleet-daemon.ps1` passes `--root <repo>/.opencode/tasks`
  (the STORE root - `--root <repo>` would mis-root the task store), appends to
  `.git/opencode-fleet/daemon.log` via a hidden pwsh wrapper (paths travel as
  `FLEET_LAUNCH_*` env vars), and quotes `-Dfile.encoding=UTF-8` (pwsh splits
  bare `-Dfile`).
- **Mode semantics**: `off`/unset/blank/garbage -> own engine (garbage warns);
  `auto`+live -> proxy, `auto`+dead -> own engine; `always`+dead -> fail fast
  (exit 1). Proxy exits 0 on client EOF (detach), 1 on daemon death.
- **Not built (deferred with the design)**: `fleet_daemon_status`/`fleet_daemon_shutdown`
  tools, launcher `stop`/`status` subcommands, permission-ask push. Slice (c) -
  flipping the default to `auto` - waits for the in-Eclipse validation run.
- Known duplication (accepted, NOTE-level): `FleetDaemon.probe` (start guard) and
  `DaemonProwl` (client-side liveness) implement the same probe against the same
  pidfile; consolidate if a third consumer appears.
