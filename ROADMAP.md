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
resolution-first ticks, clarification loop, autonomous acceptance with a
stage-aware evidence matrix and originator doubt retries, archive-aware
readiness, maintenance shutdown, stage-aware acceptance), and the strict reuse policy
governs every new capability (`docs/opencode-v2-adoption.md`). The approach is
validated by two hard rounds; the dominant risk is upstream churn (daily
releases, ~25 new operations in four days), mitigated by the standing
cross-check below.

## Open work

| Item | Size | Notes |
|---|---|---|
| **Fleet & agent observability** | L | Landed (2026-09-29): per-worker live activity + token/cost rollups in the Fleet view's tree (U-040: engine → wave → job → session → subagents → console tasks), subagent nesting + per-session shell tasks with output tails for every session (U-041: Server view + Session Details + the tree), V-flow visibility (U-026: per-card stage progress, stage-journey trace in ticket details, wave digest). Remaining: the fleet server as a managed connection. **Tickets: U-026, U-040, U-041** |
| **Interactive PTY host** | S | An Eclipse TM Terminal widget over the adopted `pty.*` verbs and the WebSocket connect stream. The lifecycle verbs and the read-only terminal screen are already in. **Finding (2026-09-29, probed):** `org.eclipse.tm.terminal` is **absent from the 2026-06 target platform** (verified by scanning the resolved p2 cache, same method as the jface.notifications check), and interactive input has no REST route — `PUT /api/pty/{id}` is title/resize only, stdin rides the WebSocket connect stream. Shipping this needs a target-platform addition (build-infra work, pairs with T-007); a custom terminal emulator would reinvent the wheel. Parked on that. |
| **Graceful shutdown: Board button** | S | **Done** (2026-09-29): U-038 engine core, U-045 chat tool + `fleet-stop.ps1`, and the Board Shutdown button (confirm dialog → `TaskFleetLauncher.shutdownForMaintenance` → gate/checkpoint/pause/serve-kill → board refresh). **Ticket: U-045** |
| **Live automatic-dispatch acceptance** | S | Exercise the auto-pump against a real model; the controls, reservations and budgets are in place. **Ticket: U-037** |
| **Session todos from a verified source** | S/M | The v2 API has no session-todo endpoint or todo field. Establish and verify a supported source before displaying or importing it. **Ticket: U-013** |
| **In-Eclipse verification pass** | M | The live UI checklist: board/fleet live try (peer refresh, badges, busy icons, menus), CDT marker round trip, per-view screenshots. **Ticket: T-002** |
| **Board & panel polish** | S/M | Landed (2026-09-29 rounds): WIP indicators + V chevrons (U-028), equal column growth (U-033), refresh-on-activate + freshness stamp (U-035), Board Shutdown (U-045), `@alias` reference roots + question-form cards (U-047/U-014), repo adoption with the repo nature + Adopt-repo action (O-001). Remaining: blocked as a state rather than a flag (U-043 - needs a design decision, it changes core store semantics). |
| **Upstream parity round** | M | **Complete** (2026-09-29, tickets U-046 + U-048): the v2.0.19 surface is adopted end to end - client verbs, saved-permission manager, integrations catalog + real connect flows (command/key/oauth with abort), migration banner, attach-skill (verified `{"skill": id}` body), title suggestions, watchdog session/wait long-poll with poll fallback, view-marking, and the deliberate-UX adoptions (revert/undo/commit two-phase, environment full-replace, session import with preview). Standing rule below keeps watching upstream drift. |
| **TUI parity gaps** | S | **Complete for everything the platform allows** (2026-09-29): commands (`/init` `/help` `/thinking`), `@`-refs incl. aliases, continuity, attention (popups + sound, off by default), question prompts answerable in chat (form cards), inbox queue/steer, subagent nesting, share as honest not-implemented notices (the v2 TUI itself toasts the same). Not implementable today: `/share` (no server route), the attention QUESTION popup kind (no form-ask SSE type - chat polls instead), session todos (no endpoint - U-013). **Ticket: U-047** |
| **Review-leg rework remainder** | S | The 2026-09-29 review leg accepted 13 of 17 and the parallel rework round closed three more (T-004 reconnect test, T-005 inbox surface, T-009 auto-refresh param - all re-accepted). Remaining: **U-009** (live Agent-Tools-console verification - needs the human's Eclipse restart) and the small terminology residue (Cost overview label - landed). |
| **Clean Eclipse reinstall / p2 profile repair** | S | The recovered install runs on hand-maintained `bundles.info` lines (`docs/eclipse-deploy-recovery.md`). |
| **Linux integration verification** | S/M | The WSL GCC fixture passes; a full Linux Java/Tycho run awaits a JDK-equipped environment or CI. Non-blocking. |

## Parked / non-goals

Sandboxing beyond permission gating; further remote-access investment
(distributed fleets are done-and-dormant); M4 CodingAgent port;
`tools.cpp` bundle extraction; multi-level board rollup; TUI take-over
into chat (the Board's take-over serves the operator); a cpp stdio tool
pack (the cpp-tools agent runs commands via bash). *(PR/CI closure left this
list: opencode v2 now provides GitHub/GitLab integrations natively, so under
the strict reuse policy it is tier-1 adoption — see the upstream parity round.)*

## Standing rules

- **Every wave starts with the upstream cross-check:** diff the live
  `GET /openapi.json` against the adoption matrix
  (`docs/opencode-v2-adoption.md`). Upstream ships daily and the matrix
  drifts silently otherwise — that drift is this project's dominant risk.
  Never adopt an unprobed call: probe the live contract first.
- TUI parity is the floor, not the goal: hold "same or more features than
  the official TUI" and use the harness where it is genuinely better.
- Refactor cadence: every second session.
- On opencode upgrade: rerun the endpoint smoke (assert the OpenAPI covers
  our surface), then bump the pin.
- The task store, not this file, is the status source once work runs on
  the board.
- Historical evolution is recorded only in the changelog
  (`docs/HISTORY.md`).
