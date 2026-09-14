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

## Current state (2026-09-14)

The fleet has dispatch, watchdog, merge-back, actuals, store sync and recovery.
The follow-up review found cross-engine reset and shutdown gaps despite the
earlier readiness claim. Fixes now reserve reset against live peer dispatch,
reject dispatch after close, preserve active claims during reconciliation,
resolve linked-worktree reservations through the common git directory, and
fail verification when the AC-path diff cannot be read.

Auto-dispatch policy/scheduler/cost aggregation now live in the headless fleet
bundle and serve both Board and chat (`fleet_auto_start/status/stop`). GUI
Batch B adds session delete/abort and primary-agent chat launch. Verification
and remaining live acceptance work are tracked below; model write reliability
is still an observed limitation, not solved by a path-presence check.

## Open work

| Item | Size | Notes |
|---|---|---|
| **Milestone U — UI verification pass** | M | The deferred Eclipse checklist + CDT marker round trip + first-launch live check. `glm-5.3-flash` (multimodal) is now available: automate per-view screenshots and verify panel contents with it instead of human eyeballs. |
| **Live automatic-dispatch acceptance** | S | Chat controls and shared reservations implemented. Exercise against a real model after the real-git failure/reset/retry regression; model engagement remains variable. |
| **Linux integration verification** | S/M | Classpath/launcher/path fixes and native GCC/Clang discovery implemented. WSL GCC fixture passes with Ninja and Makefiles; full Linux Java/Tycho run awaits a JDK-equipped environment or CI after the human pushes. Ubuntu remains non-blocking. |

## Completed this session (2026-09-14)

- ✅ opencode pin bumped to 1.18.30 (live-smoked against the running 1.18.30 server)
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
