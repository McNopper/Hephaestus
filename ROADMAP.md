# Hephaestus ROADMAP

## About this document

- **Kind:** `doc` / the forward-looking plan — open work only. The past
  lives in the changelog (`docs/HISTORY.md`); live status lives in the task
  store (`task_board`, project `hephaestus`).
- **Read by:** anyone planning the next step — human or agent; **written
  by:** maintainers with the product owner.
- **Related:** `AGENTS.md` (the process doctrine — where a row here
  contradicts it, `AGENTS.md` is right); `docs/HISTORY.md` (changelog);
  `docs/feature-matrix.md` (the shipped-state matrix); the task store
  (the status source of record — this file points at tickets, it does not
  duplicate their state).

## Vision

**Product Goal.** Any objective, stated in plain language, becomes verified
software: agents expand it along a V-model — every definition stage creates
its own verification pair, nothing is *done* until a gate proves it — and
walk it end-to-end, unattended, on opencode's substrate. The store keeps
every step an auditable event; the maintainer stays the exception handler,
never the dispatcher. Every run sits on the **autonomy spectrum**
(`AGENTS.md`, the operating model): the harness runs until this goal is
reached, releases are cut only where a human review accepts, and the owner
sets each run's position with two dials — the goal horizon (time-boxed vs
run-to-goal) and the review cadence.

**This section is the Product Goal** (the Scrum-Guide commitment for the
backlog): the long-term objective the whole store is ordered toward. GOAL
tickets are its governed decomposition - goal intake turns an owner
objective into one ticket, division expands it into the derivation tree.

One repository. Intelligence lives in `.opencode/` (agents, skills) and MCP
servers; the Eclipse plugin (`eclipse/`) is the harness: it hosts, services,
and observes agents working through the linked stage pipeline — stationary
tickets, the unified ticker-v schedule, store-mediated coordination. The maintainer uses
Eclipse as PM + reviewer; agents self-organize via the task store. **Prime
rule: never build in the plugin what Hephaestus or upstream opencode already
provides.** For a simple project the plain opencode TUI suffices — this
harness is deliberate weight for complex projects, chosen on purpose.

## The durable core (D-005, amended by U-110)

The task store (coordination blackboard), the engine verdicts (done is a
gate verdict, not an agent's claim), the spawn cap, and the Eclipse host
integration. Everything below that layer — worktree lifecycle, session
retry/resume, model selection, scheduling, background-job accounting, the
permission-reply protocol — is adopted from upstream as it lands, never
rebuilt (evidence and per-vendor verdicts:
`docs/fleet-redundancy-review-2026-11.md`). The fleet engine was removed
outright in U-110; the live `WorktreeManager` keeps the server-worktree-API
usage for the `/worktrees` feature.

## Release definition — the initial release

The first coherent release of the post-pipeline harness. Version number is
set on the release commit per D-002 (script: T-030). The release is cut
when all of the following hold:

1. **Store at steady state** — the working set carries open tickets only;
   done work flows to `_archive/` via the opt-in `auto_archive` on
   `task_close_sprint` (U-060, landed), and readiness consults the archive
   (U-059).
2. **Store correctness** — U-109 (nextId POST-FILTER prefix validation)
   closed; no known id/malformation bypasses.
3. **Docs coherent** — README, `eclipse/` docs, `AGENTS.md` and the skills
   read as documentation and match the code (2026-11-14 alignment pass;
   keep it true through the cut).
4. **Quality gate green** — `cd eclipse; .\build.ps1 clean verify
   -Pquality` on the release commit.
5. **CI decided** — GitHub Actions runs `.github/workflows/verify.yml` on
   every push/PR (restored by owner directive 2026-11-14, commit `61b9434`,
   closing T-027) and mirrors the local `releng/pre-push.ps1` steps; the
   local `clean verify -Pquality` gate stays the green-light authority for
   a cut.
6. **Live pass** — T-002, the standing end-of-arc Eclipse verification
   (owner at the keyboard).
7. **Arc closed** — U-111's engine verdict lands; the pipeline's acceptance
   chain (AE-6) is the release's process evidence.
8. **Cut** — `eclipse/releng/release.ps1` (the v0.1.10 path), tag, bump to
   the next `-SNAPSHOT`.

## Release path (ordered)

| Ticket | Why it gates the release |
|---|---|
| U-109 | ✅ done: store id correctness - the composed malformed-id bypass is closed |
| U-059 | ✅ done: archive-aware production readiness (all three production sites + `staleCause` pass the archive pool; per-host tests green) |
| T-007 | ✅ built, in-review: build perf phase 1 (target-platform diet, dev/gate profiles, jgit qualifier) - verdict pending |
| T-008 | ⬜ build perf phase 2: hybrid test lane + coverage manifest (released back to sprint-backlog 2026-11-17; **not started** - if the cut proceeds without it, record the deferral) |
| T-027 | ✅ resolved: CI runs `verify.yml` on push (owner directive 2026-11-14, `61b9434`) |
| T-177 | ✅ built, in-review: build guard - concurrent-build cap + commit-headroom floor in `build.ps1` (owner directive 2026-11-17, landed so the gate and the cut can run without starving the machine) |
| - | store steady state (condition 1): run the opt-in `auto_archive` on the next wave close - U-060 is landed but has **never been run** (`_archive/` does not exist, 343 done tickets still in the working set) |
| U-110 / U-111 | ✅ the stage-pipeline arc closed: engine verdicts landed (U-111 done) |
| T-002 | the owner's live Eclipse verification pass (recurring, T-039 - non-blocking pattern) |
| - | release cut via `eclipse/releng/release.ps1` (**human-only edge**, DENY-5: no machine actor runs it; owner runs e.g. `eclipse\releng\release.ps1 -ReleaseVersion 0.4.1` once the quality gate below is green on a clean tree) |

## Post-release backlog (adopt-and-govern)

Landed 2026-11-17 and **awaiting the engine's verdict** (store `in-review`, staged: verdict by the stage's review step; the stageless ones by the new stageless lane of T-174):

- **U-102** - `fs.watch` probe-first verdict: the store is blind to the whole `fs.*` family, `TaskStoreWatcher` stays the single notification path.
- **U-104** - per-stage dispatch: ten `hephaestus-*` stage agent definitions + `task_stage_agents` / `task_set_stage_agent` (the store-settable STAGE-AGENT MAP).
- **U-105** - governed `/goal`: client-side intake to a requirements ticket, store-mapped pause/resume/clear, engine-only completion.
- **U-052** - `inputs changed:` markers (D-003 option B) - stamp + idempotent production-tick marker.
- **U-075** - TUI-vs-Eclipse responsiveness spike (report-only; interactive half parked as T-156).

Still open in this lane:

- **Pipeline continuation** - the ONE schedule ran the whole V and **halted on owner directive 2026-11-17** ("stop the ticker, implement here without subagents to become stable"): schedule deleted, durable disable in `.harness/ticker-state.json`, steward replied `HALTED`. Re-arming is `ticker-setup.ps1` (owner's call). Six stageless verdicts were owed when it stopped (T-091, U-052, U-075, U-102, U-104, U-105 - recorded as deferred-with-reason on T-177) but **all six sit in `in-review` with artifacts**, so the T-174 stageless lane drains them on the next armed pass. A-002/A-003/A-004: **done**.
- **T-176** - `eclipse-build` MCP endpoint never starts in a deployed Eclipse (minted from T-040's failed live pass; unblocks T-040's owner-only blocker clear).
- **Launched shells in the server browser** - keep the Server view's
  visibility of a session's launched shells first-class (owner requirement
  2026-11-16: "i also see the launched shells in the server browser"). Today
  that visibility rides the session activity/status plus Session Details'
  *Shell tasks* (U-041) and the chat's inline `!` strips (CP-02) - the Fleet
  tree that nested shells under sessions went away in U-110; add a dedicated
  shell row in the Server view if the activity feed is not enough. Wording
  per T-150 §4b (`Shell tasks` in tabs, `shells` only in the TUI-counts row).

## Active product lanes (pointers into the store)

The store is the status source; these are the lanes the open tickets cluster
into, so "what is still to do" is readable here without re-counting tickets:

- **Chat TUI parity** — ✅ implementation lane closed: `T-059…T-070` (CP-04…CP-14) all **done** with their paired unit tests `T-071…T-082` (CP-01/T-057 and CP-02/T-058 landed earlier); their criteria tickets `T-170` / `T-172` (CP-03 `/btw`, CP-13 PTY probes) are built and `in-review`. *TUI parity is the floor* (Standing rules).
- **Acceptance-run / autonomy spectrum** — ✅ closed: `T-132`/`T-134`/`T-136`/`T-138` done with their test pairs `T-133`/`T-135`/`T-137`/`T-139` and the criteria tickets `T-108`, `T-124`, `T-126`, `T-128`, `T-131` - all nine FAIL round-trips reworked and re-verdicted PASS 2026-11-17.
- **Build perf** — `T-007` built/in-review (phase 1); `T-008` (phase 2) back in sprint-backlog (also unblocks the interactive PTY host).
- **Docs de-fleet & coherence** — `T-087…T-092` done except `T-091` (verdict deferred with the ticker halt), `T-037` closed (rework PASS), umbrella `E-006` waits on **U-052's** verdict + the Coherence section removal (its AC2).
- **Gaps still to ticket** — G2 (assistant footer parity), G3 (subagent/shell live counts) from `docs/tui-visible-surface-review.md`.
- **Owner-gated** — T-040 (blocked; owner clears after T-176 lands + deploy), T-156 (interactive instrumentation), U-098 (paused), U-013 (paused), the six `owner-acceptance` instances (T-161/T-162/T-165/T-167/T-171/T-173).
- **Awaiting a verdict** (store `in-review`, 21): `T-007`, `T-091`, `T-155`, `T-157`, `T-158`, `T-159`, `T-160`, `T-163`, `T-164`, `T-166`, `T-168`, `T-169`, `T-170`, `T-172`, `T-175`, `T-177`, `U-052`, `U-075`, `U-102`, `U-104`, `U-105` - verdicts resume with the next armed pass or an owner-directed review.

## Decisions

All decision records are settled (D-001..D-006, U-043, U-078..U-082) and
live in `AGENTS.md`, `docs/HISTORY.md` and the tickets (D-006: the store
substrate stays git-versioned Markdown; SQLite rejected as source of
record). New decisions get a `D-xxx` ticket and a record in the same
places; this file only lists the one currently open question: none.

## Parked / non-goals

- **U-098** (upstream model-fallback chains) — parked by the owner
  2026-10-31: "on hold, not ready, will not be implemented"; only the owner
  re-opens it.
- No public GitHub Pages landing site (README suffices).
- No sandboxing beyond permission gating; no further remote-access
  investment; no M4 CodingAgent port; no `tools.cpp` bundle extraction; no
  multi-level board rollup; no cpp stdio tool pack (the cpp-tools agent runs
  commands via bash).
- The retired machinery (fleet engine, dispatch waves, the wandering-V
  verbs) stays retired; its history is in `docs/HISTORY.md` and the
  archive, not here.

## Upstream-blocked (opencode v2)

- `/share` - no server route; the chat shows the same honest notice as the
  official TUI.
- U-013 - session todos: no endpoint; parked until v2 provides one. Re-probed
  against the live 2.3.0 contract (2026-11-17): still ZERO todo routes or
  operationIds, the event enum carries no todo type, `GET /session/{id}/context`
  returns compaction messages only (schema: `data: Session.Message.Info[]`,
  live-verified), and the contract's single `todo` is the
  `Config.experimental.todo` boolean flag of #56947 - no data source to render
  from; ticket paused in-store as the explicit park.
- `/btw` side questions - no side-question verb exists in the live contract
  (T-069 probe-first pass, 2026-11-17: zero `aside` hits anywhere in the 2.3.0
  `/openapi.json`); parked until upstream exposes one - the chat's capability
  map reports it as upstream-blocked, and no workaround was improvised (the
  FR's IF/THEN makes the recording the outcome).
- Interactive PTY host - stdin is **metadata-only over REST** (live 2.3.0 probe
  2026-11-17: `PUT /api/pty/{ptyID}` body = `title`/resize fields), and
  interactive input rides the ticket-gated stream `GET /api/pty/{ptyID}/connect`
  (query `cursor`/`ticket`) + `POST /api/pty/{ptyID}/connect-token` (`x-
  opencode-ticket`) - a flow the REST-only client cannot reach: blocked on a
  **client-side WebSocket transport piece plus a live frame-protocol probe**
  (T-070 records the evidence), NOT on the terminal widget - the earlier claim
  that `org.eclipse.tm.terminal` was absent is stale: `org.eclipse.terminal
  .feature.feature.group` is declared in `eclipse/releng/opencode-eclipse.target`.
  (pairs with T-007/T-008 for the pane work)

*(Resolved since the last pass: the Attention QUESTION popup kind - the live
SSE event-type enum carries `form.created`, mapped by T-067.)*

## Coherence debt (known divergences)

Every **open `E-006` child** is a row here (checked 2026-11-17: `U-052` is
the only child left open — its other 49 children are done) plus the
divergences still open under another umbrella; the section disappears when
E-006 closes (that is its second AC). **Where a row below contradicts
`AGENTS.md`, the row is right.**

- **U-052** - `inputs changed:` markers (epic E-006, carrying D-003 option
  B): shipped 2026-11-17 - the `inputs_changed_at` stamp plus the production
  readiness tick's idempotent marker (InputsChangedStampTest 5/5); the row
  retires with E-006 on its own done.
  Auto-archive is tool-exposed as of U-060.
- **U-059** - archive-aware production readiness: **RESOLVED 2026-11-17** (row kept
  only until the section disappears with E-006) - all three production
  `StageReadiness.evaluate` sites plus the `staleCause` lookup now pass the
  archive pool (per-host tests: tool 10/10, recorder 6/6, board 9/9), so
  readiness consults the archive in PRODUCTION, not only at library level;
  the remaining release condition (archives in real use, condition 1) is a
  release-checklist item, not a divergence.
- **T-037 residue** - superseded *per-stage-schedule* wording inside done
  V-walk artifacts (`acceptance-run-system.md` "one steward session per
  stage", `acceptance-run-architecture.md` "one schedule per stage",
  `autonomy-spectrum-architecture.md` "one schedule per stage",
  `requirements-stage-pipeline.md` "one steward session per stage", and the
  `.tests.md` rows that pin them): those artifacts' paired verdicts cite the
  wording, so they are corrected by their own epics on a re-walk; the
  supersession authority is `docs/unified-ticker-architecture.md` (the
  ten→one row) plus AGENTS.md/README.md, which T-037 aligned 2026-11-17.

## Standing rules

- **The upstream cross-check precedes every work session:** diff the live
  `GET /openapi.json` against the adoption matrix
  (`docs/opencode-v2-adoption.md`). Upstream ships daily and the matrix
  drifts silently otherwise — that drift is this project's dominant risk.
  Never adopt an unprobed call: probe the live contract first. The route
  diff alone is NOT enough: session-internal behavior changes never show in
  openapi. Each cross-check also scans upstream release notes/commits for
  behavioral changes. On opencode upgrade: rerun the endpoint smoke, then
  bump the pin.
- **Keep the harness thin** (D-005): as upstream absorbs platform layers,
  every engine-layer mechanism is adopt-or-retire at each cross-check — no
  parallel mechanism for what the tier above provides. The parts that do
  not dissolve: the task store as the coordination blackboard, the
  maintainer accountability surfaces (HITL questions, NEEDS-HUMAN,
  traceability), and project-owned quality gates.
- **TUI parity is the floor, not the goal:** hold "same or more features
  than the official TUI" and use the harness where it is genuinely better.
- **Chunk small** (AGENTS.md): one ticket = one independently verifiable
  change; the store, not this file, is the status source once work runs on
  the board.
- **A human review unlocks a release; otherwise commit and push** (owner
  requirement, 2026-11-15, refining the 2026-11-14 wave-version rule):
  when a wave completes — all its tickets done with engine verdicts — its
  result is committed and pushed. The version is cut only when the wave's
  work carries a human review: cut it via `eclipse/releng/release.ps1`
  (tag per D-002, bump to the next `-SNAPSHOT`). Waves that complete
  without a human review end at commit + push — no version, no tag; the
  release stays pending until a review unlocks it, and pending is an
  **absence, not a datum** (`docs/autonomy-spectrum-design.md` DS-4): no
  store field, no timer, no machine state, no retry — nothing to clean
  up, nothing to time out. The release path is human-only by structure:
  no machine actor's surface contains `eclipse/releng/release.ps1` (the
  denied release edge — T-041 as structure, not policy), so no dial
  setting, no component and no script path inside the machine can cut a
  release. The wave, not the
  calendar, is the release cadence — and the human review, not the wave
  alone, is the release trigger. The recurring live-in-Eclipse acceptance
  hardens the work without gating it (T-039): engine verdicts are
  necessary for a release, never sufficient. Ticker micro-records (the
  per-stage rolling records) are not waves in this sense; substantive
  planned waves are.
- **History discipline:** past events are recorded only in
  `docs/HISTORY.md` — this file carries open work only.
