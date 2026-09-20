# Hephaestus ROADMAP

## About this document
- **Kind:** `doc` / the forward-looking plan — open work only.
- **Read by:** any agent or human planning the next step; **written by:**
  maintainers with the product owner.
- **Related:** `docs/HISTORY.md` (the archive of completed work and
  findings); the task store `.opencode/tasks/` (the status source of record
  once work is on the board).

**Vision.** One repository. Intelligence lives in `.opencode/` (agents,
skills) and MCP servers; the Eclipse plugin (`eclipse/`) is the harness: it
hosts, services, and observes headless agents working in isolated git
worktrees. The human uses Eclipse as PM + reviewer; agents self-organize via
the task board. **Prime rule: never build in the plugin what Hephaestus
already provides.** For a simple project the plain opencode TUI suffices —
this harness is deliberate weight for complex projects, chosen on purpose.

## Current state (2026-09-20)

**The opencode v1 → v2 migration is landed and reactor-green** (27/27 modules,
1480 tests, commit `df28c93`). The harness now speaks the v2 API end-to-end —
`/api` paths with Basic auth, `{data:[…]}` envelopes, the async
`POST /session/:id/prompt` + reply polling (chat and fleet keep their
synchronous contract), v2 event names (`session.text.delta`,
`session.tool.*`, `session.execution.*`) over the single `/api/event` stream,
the v2 DTOs (`Agent` without `native`, `Model.cost` as a price-tier array),
OAuth over `/api/integration`, async shell, `/fs/list` + `/fs/find`, and the
version pin at 2.0.10. The interactive connection **attaches to the shared v2
background service** by default (registration-file discovery + probe +
auto-start, spawn as fallback; new preference); the **fleet deliberately keeps
its own spawned server** — v2 makes session *state* global per user (the
Server view scopes by `?directory=`) but keeps event *streams* per-process,
which the fleet's completion detection and password/budget isolation rely on.
Feature deltas v2 forces: session share removed, TUI steering no-ops,
text/symbol search empty (no v2 equivalent). The repo default model moved to
`kimi-code-plan-global/k3-256k` (the Z.AI plan is rate-limited until
2026-09-23).

## Current state (2026-09-16)

All headless-verifiable work is landed and reactor-green; what remains is
deliberately **in-Eclipse testing only**. The 2026-09-16 session landed: the
agent workstreams (peer-job Fleet view, pristine Board labels/icons, tasks
store hardening with quarantine + `task_doctor`, chat-web polish), chat
late-reply recovery (timed-out sends watch the still-busy session instead of
stranding it), the model pin (`enabled_providers` whitelist), MCP endpoint
token auth (G-003), Board type badges + peer-write visibility + live
session-busy icons (U-005/B-002/U-007), full menu batch C (U-002), the
tuning-table sweep (G-004), and the V-006 fleet daemon **slices a+b**
(daemon core + stdio proxy/launcher, opt-in via `FLEET_DAEMON=auto|always`,
default `off`). An independent clean-architecture review ran over the
session's diff; every MUST/SHOULD finding (UI-thread store locks, quarantine
gap, x-friends coupling, mutable knob statics, read-side directory
materialization) is fixed with regression tests.

## Open work

| Item | Size | Notes |
|---|---|---|
| **Fleet & agent observability — no hidden work (HIGHEST PRIORITY)** | L | Today the fleet runs workers on its own spawned server and the user cannot see *what they are doing*. Goal: every agent — chat session, fleet worker, subagent, subprocess — is visible live in Eclipse: current activity (text/tool/file), full transcript, tokens/cost. The mechanics exist (the engine's `GlobalEventsAggregator` already sees the fleet server's stream; sessions/messages are pollable) — the work is surfacing it: per-worker live activity in the Fleet view (parity with the Server view's "what is it doing"), a transcript drill-down per job, and publishing the fleet server's endpoint so it can appear as a managed connection. **Tickets: U-015 (watch live), U-024 (daemon rows), U-026 (V-flow), U-040 (Fleet view as a tree), U-041 (subagents + console tasks); Eclipse-reuse cross-check: `docs/fleet-observability-eclipse-reuse.md`** |
| **v2 background tasks** | M | v2 can push a session to the background (`POST /api/session/:id/background`) — expose it: send a chat/fleet task to the background, list what's running there (they are ordinary sessions on the stream), and bring it back. **Ticket: U-042** |
| **v2 subagents & subprocesses in the UI** | M | The TUI shows subagents and subprocesses; Eclipse should match. Subagent nesting exists (parentID); add the v2 shell/PTY surfaces (`/api/shell`, `/api/pty`, `shell.*` events) so per-session subprocess activity (what ran, status, output tail) is visible too. **Ticket: U-041** |
| **In-Eclipse verification pass** | M | The deferred UI checklist: board/fleet live try (peer refresh, badges, busy icons, menus), CDT marker round trip, per-view screenshots (`glm-5.3-flash` multimodal). Everything else is done — this is the remaining gate. |
| **V-006 slice (c): default flip** | S | Flip `FLEET_DAEMON` default to `auto` after the in-Eclipse validation run exercises the daemon opt-in. Slices a+b are shipped and tested; design + as-built note on ticket V-006. **Ticket: U-036** |
| **Graceful full-stack shutdown** | M | One action: park admissions, checkpoint in-flight workers, stop the daemon, maintenance flag, shutdown report + resume. Trigger: host JDK upgrade; includes the engine-addressing bug (chat MCP stop hit the chat's local engine, not the daemon). **Ticket: U-038** |
| **Live automatic-dispatch acceptance** | S | Exercise against a real model during the Eclipse pass; chat controls, shared reservations and the daemon are in place. |
| **Clean Eclipse reinstall / p2 profile repair** | S | The recovered install runs on hand-maintained `bundles.info` lines (see `docs/eclipse-deploy-recovery.md` caveat). |
| **Linux integration verification** | S/M | WSL GCC fixture passes; full Linux Java/Tycho run awaits a JDK-equipped environment or CI. Non-blocking. |

## Completed this session (2026-09-14)

- ✅ opencode pin bumped to 1.18.30 (live-smoked against the running 1.18.30 server) —
  **superseded by the opencode v2 migration: the pin is now 2.0.10**
- ✅ SessionEvents dead wiring cut (interface deleted, tests renamed to watchdog)
- ✅ launchGuarded decomposed into named stages (claimAndCommit → runSession → mergeAndRecord)
- ✅ WatchingClient → runner-level onSessionCreated callback (net −488 lines)
- ✅ All production-readiness P1s and P2s (G-001..G-006)
- ✅ Worker reliability: AC-path enforcement (the engine verifies AC-named file paths in the diff before merge — analysis-only runs now refuse with "expected X, got Y")
- ✅ GUI Batch A: context menus on all 5 views (Board: Launch/Take over/Open/Copy; Fleet: diff/folder/take-over/copy/abort; Server: Open-in-Chat/Copy/Agent-details; Repo: Copy path; SessionDetails: Copy text)
- ✅ Tuning config surface: env-var overrides for all knob tables (FLEET_*, CLIENT_*, GIT_*)
- Follow-up: GUI Batch B implemented; HTTP failures surface, remote mutations target the selected server, and async selector loading retains requested agent/model.
- Follow-up: shared scheduler and chat start/status/stop controls implemented; scheduler admissions account for peer reservations and in-flight cost estimates.
- Follow-up verification: see `docs/status-quo-review.md` for evidence and outstanding live checks.
- Parallel hardening: cross-process Git/admission gates, persistent project ownership,
  atomic stale rework, calibrated budget revalidation, and async deferral feedback.
- UI architecture: extracted selection/catalog/dispatch models, kept admission/stop
  waits off SWT, and added native menu smoke plus queued cancellation tests.
- Verification: final independent review approved; full 27-module clean reactor
  passed at 14:08 local on 2026-09-14. Full desktop/live-model acceptance remains open.
- First live deployment (second session): four first-launch bugs fixed (activator
  preferences, SCR circular reference, spawn-password 401, stale-port reuse — see
  `docs/status-quo-review.md`), install recovered and deploy script hardened
  (`docs/eclipse-deploy-recovery.md`); full reactor green after each fix; live
  chat round-trip verified against `glm-5.3`.

## Parked / non-goals

PR/CI closure as harness machinery (gh + Actions cover a solo repo);
sandboxing beyond permission gating; further remote-access investment
(distributed fleets are done-and-dormant); M4 CodingAgent port;
`tools.cpp` bundle extraction; multi-level board rollup; TUI take-over
into chat (the Board's take-over serves the operator); a cpp stdio tool
pack (the cpp-tools agent runs commands via bash).

## Standing rules

- Refactor cadence: every second session.
- On opencode upgrade: rerun the endpoint smoke (assert the OpenAPI `/doc`
  covers our surface), then bump the pin.
- The task store, not this file, is the status source once work runs on
  the board.
