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

The fleet and ticketing system is **production-solid**: dispatch → watchdog →
guarded merge-back → actuals → auto-sync, with release-on-failure, repo-gated
git, reaping, cross-engine guards and crash reconciliation. The **V-model
regression suite is COMPLETE** (all 6 stages landed; `EditorCoreTest.java`
15/15 golden vectors green). A full production-readiness review (2026-09-14)
found 5 P1s — **all fixed and pushed**: `fleet_reset` actually clears the
blocked flag + stale markers; the Board's launch path rides the same
cross-engine dispatch marker; hard-coded developer paths replaced with
workspace-derived defaults; the pre-claim window is inside the
total-failure contract; Eclipse spawn mode generates a random password.
Remaining P2s are ticketed as **G-001..G-006** (stale-marker sweep, crash
cleanup gitdir resolution, MCP endpoint auth, tuning wire-up, linux CI,
observability swallow points). The core engine is READY; the surrounding
recovery tooling is NEARLY.

## Open work

| Item | Size | Notes |
|---|---|---|
| **Worker reliability** | M | ~1/6 dispatches produce files (glm-5.3 low/executor). Options: (a) pin a stronger worker model per dispatch, (b) engine-level enforcement: parse the AC-named file paths and refuse runs that don't touch them, (c) accept the retry cost (the engine handles it cleanly). |
| **Milestone U — UI verification pass** | M | The deferred Eclipse checklist + CDT marker round trip + first-launch live check. `glm-5.3-flash` (multimodal) is now available: automate per-view screenshots and verify panel contents with it instead of human eyeballs. |
| **Auto-dispatch (Board Auto ▶)** | M | Manual only until the failure-release is proven in a real failure cycle; then wire chat-start (needs a running-set shared with `fleet_dispatch`). |
| **U-002 GUI right-click batches** | S/M | Batches A (quick wins) and B (delete session, abort, agent-scoped new session); below fleet reliability in priority. |
| **Tuning config surface** | S | `FleetTuning`/`ClientTuning`/`GitTuning` are wired; make them env-var or file overridable. |

## Completed this session (2026-09-14)

- ✅ opencode pin bumped to 1.18.30 (live-smoked against the running 1.18.30 server)
- ✅ SessionEvents dead wiring cut (interface deleted, tests renamed to watchdog)
- ✅ launchGuarded decomposed into named stages (claimAndCommit → runSession → mergeAndRecord)
- ✅ WatchingClient → runner-level onSessionCreated callback (net −488 lines)
- ✅ All production-readiness P1s and P2s (G-001..G-006)

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
