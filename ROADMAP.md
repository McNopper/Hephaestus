# Hephaestus ROADMAP

## About this document
- **Kind:** `doc` / the forward-looking plan — open work only. The past
  lives in the changelog (`docs/HISTORY.md`).
- **Read by:** anyone planning the next step; **written by:** maintainers
  with the product owner.
- **Related:** `docs/HISTORY.md` (the changelog); the task store
  `.opencode/tasks/` (the status source of record for board work).

**Vision.** One repository. Intelligence lives in `.opencode/` (agents,
skills) and MCP servers; the Eclipse plugin (`eclipse/`) is the harness: it
hosts, services, and observes headless agents working in isolated git
worktrees. The human uses Eclipse as PM + reviewer; agents self-organize via
the task board. **Prime rule: never build in the plugin what Hephaestus
already provides.** For a simple project the plain opencode TUI suffices —
this harness is deliberate weight for complex projects, chosen on purpose.

**Where we are.** The v2 capability alignment is complete: the harness
adopts what an opencode session can do (rename, background, shells,
snapshots, forms, fork, move/switch/compact, export/log/stats, a read-only
terminal, plugins, MCP management, credentials, worktrees), the pump
semantics the conventions promise are engine-side (stage pass-through,
resolution-first ticks, clarification loop, autonomous acceptance,
archive-aware readiness, maintenance shutdown), and the strict reuse policy
governs every new capability (`docs/opencode-v2-adoption.md`).

## Open work

| Item | Size | Notes |
|---|---|---|
| **Fleet & agent observability** | L | Per-worker live activity in the Fleet view (parity with the Server view's "what is it doing"), a transcript drill-down per job, and the fleet server as a managed connection. **Tickets: U-015, U-024, U-026, U-040** |
| **Interactive PTY host** | S | An Eclipse TM Terminal widget over the adopted `pty.*` verbs and the WebSocket connect stream. The lifecycle verbs and the read-only terminal screen are already in. |
| **Graceful shutdown: user surfaces** | S | The engine core is done (maintenance gate, WIP checkpointing, `paused` tickets, shutdown report). Remaining: the chat tool `fleet_shutdown`, a Board button, `eclipse/fleet-stop.ps1`, and the engine-addressing chooser (a stop aimed at the daemon can never no-op against a local engine). **Ticket: U-038** |
| **Live automatic-dispatch acceptance** | S | Exercise the auto-pump against a real model; the controls, reservations and budgets are in place. **Ticket: U-037** |
| **Session todos from a verified source** | S/M | The v2 API has no session-todo endpoint or todo field. Establish and verify a supported source before displaying or importing it. **Ticket: U-013** |
| **In-Eclipse verification pass** | M | The live UI checklist: board/fleet live try (peer refresh, badges, busy icons, menus), CDT marker round trip, per-view screenshots. **Ticket: T-002** |
| **Board & panel polish** | S/M | WIP indicators + V chevrons (U-028), board resize + freshness (U-033/U-035), chat continuity across restarts (U-039), composer `@`-references (U-012), blocked as a state rather than a flag (U-043), repo-driven self-configuration (O-001/O-002), panel IA cleanup (T-009). |
| **Clean Eclipse reinstall / p2 profile repair** | S | The recovered install runs on hand-maintained `bundles.info` lines (`docs/eclipse-deploy-recovery.md`). |
| **Linux integration verification** | S/M | The WSL GCC fixture passes; a full Linux Java/Tycho run awaits a JDK-equipped environment or CI. Non-blocking. |

## Parked / non-goals

PR/CI closure as harness machinery (gh + Actions cover a solo repo);
sandboxing beyond permission gating; further remote-access investment
(distributed fleets are done-and-dormant); M4 CodingAgent port;
`tools.cpp` bundle extraction; multi-level board rollup; TUI take-over
into chat (the Board's take-over serves the operator); a cpp stdio tool
pack (the cpp-tools agent runs commands via bash).

## Standing rules

- Refactor cadence: every second session.
- On opencode upgrade: rerun the endpoint smoke (assert the OpenAPI covers
  our surface), then bump the pin.
- The task store, not this file, is the status source once work runs on
  the board.
- Historical evolution is recorded only in the changelog
  (`docs/HISTORY.md`).
