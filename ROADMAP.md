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

The fleet and ticketing system is **production-shaped**: dispatch → watchdog →
guarded merge-back → actuals → auto-sync, with release-on-failure, repo-gated
git, reaping, cross-engine guards and crash reconciliation — all landed and
tested (1048 Java tests + 153 Node checks; CI green on windows-latest).
The V-model regression suite (the "Navi" editor) is **4 of 6 stages MERGED**;
the implementation stage is blocked on a worker-model engagement problem
(the agent completes with a reply but never attempts a file write — five
identical refusals; engine proven correct; diagnosis on W-005).

## Open work

| Item | Size | Notes |
|---|---|---|
| **W-005 worker engagement** | M | Five identical "worker produced no changes" refusals (executor AND build agents, decomposed AND whole tickets). Next lever: surface the worker's last assistant text via `fleet_job_details`, then bisect the smoke-test-vs-fleet context difference. The finding trail is on W-005. |
| **opencode pin bump 1.18.21 → 1.18.30** | S | API verified table-identical through 1.18.30. Smoke: assert the OpenAPI `/doc` covers our whole surface, then bump `ServerVersionPin.PINNED_VERSION`. Also verify the three undocumented behaviors (GET /skill, POST /session?directory=, busy-only status). |
| **Milestone U — UI verification pass** | M | The deferred Eclipse checklist + CDT marker round trip + first-launch live check. `glm-5.3-flash` (multimodal) is now available: automate per-view screenshots and verify panel contents with it instead of human eyeballs. |
| **Milestone H remainder — refactor cadence** | S | Every second session; due since 2026-08-18. |
| **Tuning config surface** | S | `FleetTuning`/`ClientTuning`/`GitTuning` exist as knob tables; make them env-var or file overridable. |
| **`SessionEvents` dead wiring cut** | S | The seam is retained for compatibility but unused; delete it and the board's SseSessionEvents feed (R5 from the architecture review). |
| **WatchingClient → runner hook** | M | R6: a runner-level `onSessionCreated` callback deletes 228 lines of mechanical delegation. |
| **Launch-decompose (`launchGuarded`)** | S | R8: split the god-method into named stages. |
| **Auto-dispatch (Board Auto ▶)** | M | Manual only until the failure-release (landed) is proven in a real failure cycle; then wire chat-start (needs a running-set shared with `fleet_dispatch`). |
| **U-002 GUI right-click batches** | S/M | Batches A (quick wins) and B (delete session, abort, agent-scoped new session) from the 2026-09-13 evaluation; below fleet reliability in priority. |

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
