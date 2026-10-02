# Hephaestus ROADMAP

## About this document
- **Kind:** `doc` / the forward-looking plan — open work only. The past
  lives in the changelog (`docs/HISTORY.md`).
- **Read by:** anyone planning the next step — human or agent; **written
  by:** maintainers with the product owner.
- **Related:** `docs/HISTORY.md` (the changelog); the task store
  `.opencode/tasks/hephaestus/` (the status source of record — this file
  points at tickets, it does not duplicate their state).

**Vision.** One repository. Intelligence lives in `.opencode/` (agents,
skills) and MCP servers; the Eclipse plugin (`eclipse/`) is the harness: it
hosts, services, and observes headless agents working in isolated git
worktrees. The human uses Eclipse as PM + reviewer; agents self-organize via
the task board. **Prime rule: never build in the plugin what Hephaestus
already provides.** For a simple project the plain opencode TUI suffices —
this harness is deliberate weight for complex projects, chosen on purpose.

**Where we are.** `v0.1.1` is released and pushed. The 2026-10-02 coherence
review (docs/HISTORY.md, H12) compared docs, skills, agents and the store
against the engine; everything it found is now split into the tickets of
**E-006** (*Coherence debt*), cut along **exclusive file lanes** so several
agents can work in parallel. Wave 1 (25 tickets, docs + small engine
defects) landed the same day and is in orchestrator review; five mechanisms
the docs promise are still not wired into production (stage pass-through,
resolution-first ticks, archive-aware readiness, auto-archive on close,
`inputs changed:` markers — U-057..U-060, U-050, U-052); until they land,
the docs describe them as unwired.

## How to take over (for agents)

1. **Pick from the wave.** `task_board` (project `hephaestus`) shows
   `wave-2026-10-02`. One ticket = one exclusive file lane, so parallel
   workers never conflict. Work the todos on the ticket — each cites the
   review's file:line evidence; **re-verify every claim at HEAD before
   editing.**
2. **Store discipline** (AGENTS.md): write the store only through `task_*`
   tools; record artifacts before moving to `in-review`; the ticket is the
   hand-off contract.
3. **Staged vs unstaged.** The E-006 tickets are deliberately unstaged:
   take them by explicit assignment or per-ticket `fleet_dispatch`. The
   auto/waves loops only launch READY tickets of staged V-chains — and the
   stage pass-through that would let a chain flow cheaply is U-049.
4. **Verification is central.** Workers edit; the orchestrator runs the one
   quality gate (`cd eclipse; .\build.ps1 verify -Pquality`) and commits —
   one commit per round, amended within the round, pushed only on the
   owner's go.
5. **Standing hygiene:** UTF-8 only (never round-trip text through Windows
   PowerShell 5.1 `Get-Content`/`Set-Content` — the gate rejects the
   resulting mojibake); every wave starts with the upstream cross-check
   (below).

## Now — wave-2026-10-02 (E-006, coherence debt wave 1)

Landed 2026-10-02: all 25 tickets (docs truth passes T-011..T-025, engine
defects B-014..B-023, store/client/UI gains U-053..U-055) are **in
orchestrator review** — the store (`task_board`) is their status source.
History: docs/HISTORY.md, H13.

## Next — wave 2 (after wave 1's gate is green)

Decomposed per the chunk-small rule (umbrellas U-049/U-051/U-056/T-020/T-021
are closed as indexes; the work lives in the rows below):

| Ticket | Chunk |
|---|---|
| U-057 | `task_pass_stage` tool (store history event + tests) — U-049/1 |
| U-058 | pump passes a stage without launching a session — U-049/2, completes U-029 |
| U-050 | resolution-first ticks in the production schedulers (U-031; one mechanism, one lane) |
| U-052 | `inputs changed:` markers recorded by the pump (pairs with D-003) |
| U-059 | archive pool into every production readiness call — U-051/1 |
| U-060 | `auto_archive` on `task_close_sprint`, wave-scoped — U-051/2, completes U-027 |
| U-061 | wave digest recorded on plan/close in the store — U-056/1 |
| U-062 | Board renders the shared digest; settle Q-001/C-003 — U-056/2 |
| T-027 | diagnose the ubuntu CI leg (spike; one small fix ticket per root cause) |
| T-028 | store audit: artifacts for done tickets |
| T-029 | store audit: wave timestamps + the malformed id |
| T-026 | walk the five V-chains to `test-requirements` (needs U-058) |
| U-063 | upstream round: opencode 2.0.21 — endpoint smoke + pin bump (standing rule) |

## Decisions needed (Product Owner)

| Ticket | Question | Recommendation |
|---|---|---|
| D-001 | what `task_traceability` counts as a verified pair (today: role+epic only — 101/121 tickets read as orphans) | own V-journey **and** verification artifacts, with V-level matching |
| D-002 | release versioning (both tags built as `0.1.0-SNAPSHOT`) | set the version on the release commit, tag, bump to next `-SNAPSHOT` |
| D-003 | which upstream writes make downstream work STALE (today: any write) | only status/stage/artifact changes |
| D-004 | tier→model mapping + the rubberduck's cross-vendor model (none pinned today) | tiers stay guidance; pin a named other-vendor rubberduck model |
| U-043 | "blocked" as a store state instead of an orthogonal flag | needs the owner's design call |

## Human actions

- **T-002** — the live in-Eclipse verification pass (the deep dive); its
  todos include the T-010 follow-ups: live streaming text in the chat, the
  repaired Board glyphs, the mid-pipeline readiness badges.
- **U-037** — live automatic-dispatch acceptance run; needs an explicit go
  (it spends real-model tokens).
- **U-009** — Agent Tools console live verification (rides with T-002).
- **T-003 residue** — after the next opencode service restart, a repo-scoped
  `GET /api/mcp` should show the fleet server disabled.

## Coherence debt (known divergences)

Every open E-006 child is exactly one row here; the section disappears when
E-006 closes. **Where a row below contradicts `AGENTS.md`, the row is right.**

- Five documented mechanisms are still library-level only (pass-through
  U-057/U-058, resolution-first U-050, archive-aware readiness U-059,
  auto-archive U-060, `inputs changed:` markers U-052) — the docs say so and
  point at the tickets.
- `task_traceability` pairs by role+epic, not artifacts or V level — D-001.
- Release versioning: both tags built as `0.1.0-SNAPSHOT` — D-002.
- STALE fires on any upstream write, even bookkeeping — D-003.
- No per-tier model map; rubberduck runs on the default vendor — D-004.

## Parked / non-goals

Sandboxing beyond permission gating; further remote-access investment
(distributed fleets are done-and-dormant); M4 CodingAgent port;
`tools.cpp` bundle extraction; multi-level board rollup; a cpp stdio tool
pack (the cpp-tools agent runs commands via bash).

## Upstream-blocked (opencode v2)

- `/share` — no server route; the chat shows the same honest notice as the
  official TUI.
- Attention QUESTION popup kind — no form-ask SSE event type; the chat
  polls forms instead.
- U-013 — session todos: no endpoint; parked until v2 provides one.
- Interactive PTY host — `org.eclipse.tm.terminal` is absent from the 2026-06
  target platform and stdin has no REST route; needs a target-platform
  addition first (pairs with T-007/T-008, build perf).

## Standing rules

- **Chunk small.** One ticket = one independently verifiable change (one
  file lane, one mechanism, one doc pass). If a ticket grows past ~3 story
  points or mixes concerns, split it — smaller chunks are easier to track,
  verify, review and re-run. Todos list the steps *within* one change; they
  are not a substitute for decomposition.
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
- The task store, not this file, is the status source once work runs on the
  board.
- Historical evolution is recorded only in the changelog
  (`docs/HISTORY.md`).
