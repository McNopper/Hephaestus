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
| U-109 | store id correctness: close the composed malformed-id bypass |
| U-059 | archive-aware production readiness — safe with U-060 alone (the archiver never archives an upstream a live ticket needs), required once archives are in real use |
| T-007 | build perf phase 1: target-platform diet, dev profile, stable qualifier (also unblocks the PTY-host adoption) |
| T-008 | build perf phase 2: hybrid test lane + coverage manifest |
| T-027 | ✅ resolved: CI runs `verify.yml` on push (owner directive 2026-11-14, `61b9434`) |
| — | store steady state (condition 1): run the opt-in `auto_archive` on the next wave close — U-060 is landed but has **never been run** (`_archive/` does not exist, 290 done tickets still in the working set) |
| U-110 / U-111 | ✅ the stage-pipeline arc closed: engine verdicts landed (U-111 done) |
| T-002 | the owner's live Eclipse verification pass (recurring, T-039) |
| — | release cut via `eclipse/releng/release.ps1` |

## Post-release backlog (adopt-and-govern)

- **U-102** — adopt upstream `fs.watch` for store watching; remove
  TaskStoreWatcher if covered.
- **U-104** — stage launch configuration via native opencode agent
  definitions (provider, model chain, sub-agent per stage; D-004-local
  models).
- **U-105** — goal intake: session objectives become governed V-model work.
- **U-052** — record `inputs changed:` markers from production readiness
  (carries decided D-003, option B: only status/stage/artifact writes make
  work STALE).
- **Pipeline continuation** - the ticker covers the architecture and test
  stages under the ONE armed schedule (`ticker-setup.ps1` - no per-stage
  arming); of the three children the review left READY, **A-002 (test-system) and A-003
  (architecture) are done** - only **A-004** (test-architecture, READY on
  its done basis A-001) is still queued.
- **U-075** — TUI-vs-Eclipse responsiveness, measured spike, report-only.
- **Launched shells in the server browser** — keep the Server view's
  visibility of a session's launched shells first-class (owner requirement
  2026-11-16: "i also see the launched shells in the server browser"). Today
  that visibility rides the session activity/status plus Session Details'
  *Shell tasks* (U-041) and the chat's inline `!` strips (CP-02) — the Fleet
  tree that nested shells under sessions went away in U-110; add a dedicated
  shell row in the Server view if the activity feed is not enough. Wording
  per T-150 §4b (`Shell tasks` in tabs, `shells` only in the TUI-counts row).

## Active product lanes (pointers into the store)

The store is the status source; these are the lanes the open tickets cluster
into, so "what is still to do" is readable here without re-counting tickets:

- **Chat TUI parity** — CP-04…CP-14 implementations `T-059…T-070` with their
  paired unit tests `T-071…T-082` (CP-01/T-057 and CP-02/T-058 landed
  earlier); *TUI parity is the floor* (Standing rules).
- **Acceptance-run / autonomy spectrum** — `T-132` (DS-006 prompt
  supersession), `T-134` (amended attribution), `T-136` (recurrence mint),
  `T-138` (release cadence) plus their test pairs `T-133`/`T-135`/`T-137`/
  `T-139` and the criteria tickets `T-108`, `T-124`, `T-126`, `T-128`,
  `T-131`.
- **Build perf** — `T-007` / `T-008` (release-gated; also unblocks the
  interactive PTY host).
- **Docs de-fleet & coherence** — `T-087…T-092`, `T-037`, umbrella `E-006`.
- **Gaps still to ticket** — G2 (assistant footer parity), G3 (`N subagents ·
  M shells` live counts) from `docs/tui-visible-surface-review.md`.
- **Awaiting a verdict** (store `in-review`): `T-100`, `T-125`, `T-136`,
  `U-009`.

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
- U-013 - session todos: no endpoint; parked until v2 provides one.
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

- **U-052** — `inputs changed:` markers (epic E-006, carrying D-003 option
  B): still library-level only — the docs say so and point at the ticket.
  Auto-archive is tool-exposed as of U-060.
- **U-059** - archive-aware production readiness (epic **U-112**, not
  E-006): same shape - readiness consults the archive only at library level
  today; becomes required the moment archives are in real use (release
  condition 1).
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
  release stays pending until a review unlocks it. The wave, not the
  calendar, is the release cadence — and the human review, not the wave
  alone, is the release trigger. The recurring live-in-Eclipse acceptance
  hardens the work without gating it (T-039): engine verdicts are
  necessary for a release, never sufficient. Ticker micro-records (the
  per-stage rolling records) are not waves in this sense; substantive
  planned waves are.
- **History discipline:** past events are recorded only in
  `docs/HISTORY.md` — this file carries open work only.
