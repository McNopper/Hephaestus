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
worktrees. The maintainer uses Eclipse as PM + reviewer; agents self-organize via
the task board. **Prime rule: never build in the plugin what Hephaestus
already provides.** For a simple project the plain opencode TUI suffices —
this harness is deliberate weight for complex projects, chosen on purpose.

**Fleet scope (decision D-005, 2026-11-09).** The durable core is: the task
store (coordination blackboard), the V-model pipeline semantics, the
budget-admission pump, and the Eclipse host integration. Everything below
that layer — worktree lifecycle, session retry/resume, model selection,
scheduling, background-job accounting, the permission-reply protocol — is
adopted from upstream as it lands, never rebuilt (evidence and per-vendor
verdicts: `docs/fleet-redundancy-review-2026-11.md`). First adoption chunk:
U-106 (FleetRunner onto the server worktree APIs).

**Where we are.** `v0.1.10` is released (first cut through
`eclipse/releng/release.ps1`, gate green 28/28) and the history was squashed
to a single commit (2026-10-31) so that provider/model choices are LOCAL
settings only (D-004; enforced by the CI gate `releng/check-no-provider-ids.ps1`,
bootstrap generator `releng/init-local-settings.ps1`). The reactor is
`0.1.11-SNAPSHOT`, deployed to the live install, pin 2.2.6 == live service.
The store (`task_board`) is the status source; `task_doctor` is ok.
History: docs/HISTORY.md.

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
   one commit per round, amended within the round, pushed only on explicit
   maintainer approval.
5. **Standing hygiene:** UTF-8 only (never round-trip text through Windows
   PowerShell 5.1 `Get-Content`/`Set-Content` — the gate rejects the
   resulting mojibake); every wave starts with the upstream cross-check
   (below).

## Now

Latest release: **v0.1.10** (single-commit history, D-004-clean by design).
Reactor: `0.1.11-SNAPSHOT`, deployed to the live install; upstream
cross-checks are continuous (standing rule below), currently through
**2.2.6** (+1 op = `experimental.job.list`, W13 watch). The active campaign
is **queue 1** (sprint S-08): the self-contained backlog executed strictly
sequentially — store/tool fixes (B-026 done: `task_create` honors
blocked/blocker; U-094, T-029, D-003 closure, T-028, B-025), the Batch-A
engine chunks (U-052/059/060/061/062), the upstream-adoption tickets
(U-100, U-102, U-104, U-106, U-099), build perf (T-007/T-008) and the
responsiveness spike (U-075). Anything needing the owner at the keyboard or
real-model budget (the T-002 live pass incl. U-037, the T-027 blocking
flip, U-090) is deliberately planned last.

## Next — queue 1 (sprint S-08; the store is the status source)

| Ticket | Chunk |
|---|---|
| ~~U-057~~ | ✅ done (S-12): `task_pass_stage` tool shipped — U-049/1 |
| ~~U-058~~ | ✅ done (S-12): pump passes a stage via `pass-declare:` comment without launching — U-049/2, completes U-029 |
| ~~U-050~~ | ✅ done (2026-10-16): resolution-first ticks wired in all three production schedulers |
| ~~B-026~~ | ✅ done (2026-11-09): `task_create` honors blocked/blocker (U-067 state at birth) |
| U-094 | explicit sprint ids teach the planSprint counter (fix designed) |
| U-052 | `inputs changed:` markers recorded by the pump (carries the decided D-003) |
| U-059 | archive pool into every production readiness call — U-051/1 |
| U-060 | `auto_archive` on `task_close_sprint`, wave-scoped — U-051/2, completes U-027 |
| U-061 | wave digest recorded on plan/close in the store — U-056/1 |
| U-062 | Board renders the shared digest; settle Q-001/C-003 — U-056/2 |
| T-027 | diagnose the ubuntu CI leg — leg green since 2026-10-29; the blocking flip is the owner's call |
| T-028 | store audit: artifacts for done tickets |
| T-029 | store audit: wave timestamps + the malformed id |
| U-106 | NEW (fleet review 2026-11-09): FleetRunner onto the server worktree APIs — adopt-and-remove; merge-back stays ours |
| U-100 | wave lessons via upstream instruction entries (substrate confirmed by the fleet review) |
| U-102 | adopt upstream `fs.watch` for store watching; remove TaskStoreWatcher |
| U-104 | stage→agent mapping + governing-definitions injection (mechanism = native agent definitions, D-004-local models) |
| U-099 | retry re-enters the existing session — policy over the native steering route |
| T-007 | build perf phase 1: target-platform diet, dev profile, stable qualifier |
| T-008 | build perf phase 2: hybrid test lane (queued behind T-007) |
| U-075 | TUI-vs-Eclipse responsiveness — measured spike, report-only |

## Decisions needed

| Ticket | Question | Recommendation |
|---|---|---|
| D-001 | what `task_traceability` counts as a verified pair | DECIDED 2026-10-07 — widen to three signals (role+epic, own V-journey, test artifacts); implementation U-070 (in-review) |
| D-002 | release versioning (both tags built as `0.1.0-SNAPSHOT`) | DECIDED 2026-10-07 — set the version on the release commit, tag, bump to next `-SNAPSHOT` (script: T-030) |
| D-003 | which upstream writes make downstream work STALE (today: any write) | DECIDED 2026-10-07 — option B: only status/stage/artifact changes count (`inputs_changed_at` stamp); implementation folded into U-052 |
| D-004 | tier→model mapping + the rubberduck's cross-vendor model | DECIDED 2026-10-07 — tiers stay guidance; no committed model ids |
| **D-005** | is the fleet still required, given upstream's native fleet primitives (worktrees, teams, budgets)? | DECIDED **2026-11-09** — keep store + V-model + admission pump + Eclipse host as the core; adopt upstream mechanics as they land (evidence: `docs/fleet-redundancy-review-2026-11.md`; first chunk U-106) |
| U-043 | "blocked" as a store state instead of an orthogonal flag | DECIDED 2026-10-07 — children U-067/U-068/U-069 landing (in-review) |
| - | public GitHub Pages landing site for Hephaestus | DECIDED **NO** - README/GIF suffice |
| U-078..U-082 | V-enforcement contract: stage artifacts required, coverage delivered automatically, done as a four-box gate verdict, horizontal/vertical loop routing | DECIDED + LANDED (process rules; wave S-07) |

## Human actions

- **T-002** — the standing end-of-arc live pass (re-scoped 2026-11-09; the
  checklist lives in docs/TESTING.md). Includes **U-037** (the live
  auto-dispatch acceptance, folded in the same day — it spends real-model
  tokens and runs when the pass is armed), the U-096 follow-ups (live
  streaming text, Board glyphs, mid-pipeline readiness badges) and
  U-009 (Agent Tools console live verification).
- **T-027** — the ubuntu CI leg has held green since 2026-10-29; flipping
  it to blocking is the owner's call.
- **U-090** — greenfield fleet project (owner decision: target project +
  budget; mechanics per D-005 on native worktree APIs).
- **T-003 residue** — after the next opencode service restart, a repo-scoped
  `GET /api/mcp` should show the fleet server disabled.
- **Recovery copy** — keep or delete the pre-squash history bundle
  (`%TEMP%\hephaestus-pre-squash-2026-10-31.bundle`); it is the only
  complete copy of the pre-squash history.

## Coherence debt (known divergences)

Every open E-006 child is exactly one row here; the section disappears when
E-006 closes. **Where a row below contradicts `AGENTS.md`, the row is right.**

- Three documented mechanisms are still library-level only (archive-aware
  readiness U-059, auto-archive U-060, `inputs changed:` markers U-052) —
  the docs say so and point at the tickets. (Resolution-first U-050 **wired
  2026-10-16**: all three production schedulers set the resolver; pass-through
  U-057/U-058 wired 2026-10-14 — AGENTS.md and the skills carry the truth.)
- STALE fires on any upstream write, even bookkeeping — the DECISION is in
  (D-003, option B); the implementation (the `inputs_changed_at` stamp) is
  U-052, still open.

## Parked / non-goals

**U-098** (adopt upstream model fallback chains) — parked by the owner
2026-10-31: "on hold, not ready, will not be implemented"; only the owner
re-opens it. Sandboxing beyond permission gating; further remote-access investment
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
  Never adopt an unprobed call: probe the live contract first. The route
  diff alone is NOT enough: session-internal behavior changes never show in
  openapi (the model fallback chain merged upstream 2026-05 was invisible to
  the 2.0.28→30 route diffs, U-098). Each cross-check round therefore also
  scans upstream release notes/commits for behavioral changes.
- **Model selection is a split, never a rebuild:** the per-stage policy
  (U-072) selects each stage's chain HEAD. The failover-chain ticket U-098
  is PARKED (owner 2026-10-31 — see Parked/non-goals); upstream hardened
  the transport side meanwhile (2.2.6 retries provider requests while the
  host is unreachable, #61880). Should the owner ever re-open chains, they
  are a LOCAL-config concern (D-004), never committed.
- **Keep the harness thin — the durable core is the store, not the pump**
  (adopted from the self-organization outlook; re-affirmed as decision
  D-005, 2026-11-09, evidence `docs/fleet-redundancy-review-2026-11.md`):
  as upstream absorbs
  platform layers and agents increasingly self-organize, every ENGINE layer
  (dispatch, waves, watchdog) is adopt-or-retire at each cross-check — no
  parallel mechanism for what the tier above provides. The parts that do not
  dissolve: the task store as the coordination blackboard, the maintainer
  accountability surfaces (HITL questions, NEEDS-HUMAN, traceability) and
  project-owned quality gates. Autonomy increases the need for those, not
  the need for a controller.
- **The flow and the final check**: Hephaestus
  owns the V-model flow — how work passes (definitions → verification,
  artifacts, coverage, DONE as a gate verdict). The final check = the
  per-project quality/architecture gate (global skills + project-owned gate
  config) invoked at each stage's acceptance; project-specific, never owned
  by Hephaestus. Specialized skills and metrics never get committed here;
  they come from the user or live in the project. New checks belong in the
  project's own files, not as harness features.
  Repo therefore carries V-model content only: skills (`software-*`,
  `test-software-*`, `project-manager-*`) and agents (orchestrator,
  manifest-author, executor, reviewer, rubberduck, research,
  project-manager); MCP servers are only `tasks` and `fleet`. Previously
  removed content is quarantined at `C:\Temp\Hephaestus` (code-dependency,
  code-licenses, code-repo-map, code-quality-matrix, cpp-tools,
  graphics-render-comparison, research-artificial-analysis-models,
  cpp-tools agent, graphics-expert agent, graphics MCP server).
- TUI parity is the floor, not the goal: hold "same or more features than
  the official TUI" and use the harness where it is genuinely better.
- Refactor cadence: every second session.
- On opencode upgrade: rerun the endpoint smoke (assert the OpenAPI covers
  our surface), then bump the pin.
- The task store, not this file, is the status source once work runs on the
  board.
- Historical evolution is recorded only in the changelog
  (`docs/HISTORY.md`).
