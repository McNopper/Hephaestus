# Feature matrix — Hephaestus harness

## About this document

- **Kind:** doc / live inventory; **read by:** the maintainer and any agent planning the next wave; **written by:** the orchestrator, **v2 after rubberduck review** (findings applied, see notes inline).
- **Goal:** see at a glance what the harness already *does*, what is built but unaccepted, and what is still missing — in service of "the harness gets stable and usable".
- **Source of truth:** the task store (project `hephaestus`) plus AGENTS.md and the shipped code; the store wins where they disagree. Counts are **read from the store, never restated here**: `task_list(project=hephaestus[, status=…])` / `task_board` (or the Board's columns) answer "how many today", `task_get` answers for one ticket. A number in this file rots within days - the 2026-11-17-evening capture (343 done / 21 in-review / 34 open / 1 blocked - ticker halted on owner directive, verdicts resume next armed pass) was obsolete by the 2026-11-26 archive sweep, and the working set moved again twice within minutes that same afternoon, so anything counted here is a dated fact about its date, never the live figure. Evidence ticket ids are pointers, not status: an id missing from the working set and `_archive/` was purged with the wave-era work (`docs/HISTORY.md` records what it delivered). This file carries the *shape* - shipped / unaccepted / missing - and never a count that can rot.
- **Legend:** ✅ shipped + verified · 🔶 built, acceptance pending (in-review) · 🟡 backlog · ⛔ blocked (ticket or decision) · 🅿️ parked / non-goal · 👤 maintainer action (approval / live pass)

## 1. V-model engine & task store

| Feature | Status | Evidence / ticket |
|---|---|---|
| Task store (file-based blackboard, one md per ticket) | ✅ | E-001; `tasks` MCP serves all agents |
| V-stage field (immutable ownership) + basis links + readiness (READY/WAIT_UPSTREAM/STALE/BLOCKED/RUNNING) | ✅ | V-001; H6 readiness; U-110 store contract |
| V-enforcement: stage artifacts, top-down coverage injection, DONE as gate verdict | ✅ | U-078..U-082 (v0.1.4); done-time guard (U-110) |
| Stageless verdict lane (in-review tickets with no stage get the step-3 engine verdict, both carriages: SKILL + tick prompt) | ✅ verdict landed (store: T-174 `done`) | T-174 (StagelessVerdictContractTest 2/2) |
| Stage encoded in the ticket name (`[stage]` title tag, store-owned at every title/stage write) | ✅ verdict landed (store: T-175 `done`) | T-175 (StageInTitleTest 10/10) |
| `review: human \| ai` verdict-kind field (first/last-stage-only human review, engine skips `human`) | ✅ verdict landed (store: T-155 `done`) | T-155 (ReviewFieldTest 6/6 + skill contract 2/2) |
| ~~Stage pass-through: `task_pass_stage` + `pass-declare:` pump path~~ | ✗ retired (U-110) | superseded by the linked stage pipeline (stationary tickets, division on completion) |
| ~~Horizontal loop `task_report_horizontal`~~ | ✗ retired (U-110) | superseded by blocked escalation to the basis |
| Blocked as a status (schema + resumeTo + surfaces) | ✅ | U-067/U-068/U-069 (done) |
| Five V-chains walked to test-requirements | ✅ | T-026 (v0.1.9) |
| `task_traceability` | ✅ | U-070 done; **D-001 decided** (three signals) |
| Archive-aware readiness + auto_archive | ✅ | U-059/U-060 (done; archive pool feeds all three production readiness sites) |
| Wave digest (record + board render) | ✅ | U-061/U-062 (done) |
| Store glitches (wave timestamps, malformed id, artifactless done rows) | ✅ | T-028/T-029 (done) |
| planSprint meta counter quirk | ✅ | U-094 (done) |

## 2. PM workflow & waves

| Feature | Status | Evidence / ticket |
|---|---|---|
| Scrum-like waves (plan/claim/review/accept; chat-first) | ✅ | waves S-01..S-13 closed |
| `project-manager-*` skill set (8) + PM agent | ✅ | curated set |
| Recurring waves + auto-dispatch machinery (budgets, chat-triggerable) | ✅ machinery | U-021/U-022/P-003 |
| **Live auto-dispatch acceptance run** | ✅ (acceptance run recorded 2026-11-17, §11; store: U-037 `done`, purged `6248c00`) | U-037 (spends real tokens) |
| Clarification loop (NEEDS-HUMAN last) + reviewer auto-accept | ✅ | U-023/U-021 |
| Per-stage dispatch config - stage -> agent map (`task_stage_agents` / `task_set_stage_agent`, settable from the chat without file edits) + one agent definition per V-stage (`.opencode/agent/hephaestus-*.md`) | ✅ **landed (U-104):** the ticker spawns with the STAGE-AGENT MAP; precedence ticket.`model` > store row > injected agent `model:` (local, never committed) > default; the fleet-era stage-models policy is gone (U-110) | U-104 (StageAgentsTest 4/4) |
| STALE semantics decision (any write → STALE?) | ✅ decided (D-003 option B landed as U-052: the `inputs_changed_at` stamp - see §2; ROADMAP *Decisions* records **none** open) | D-003 / U-052 |

## 3. Chat surface

| Feature | Status | Evidence / ticket |
|---|---|---|
| Streaming chat (progressive markdown, inline Stop, tool parts) | ✅ **gate-verified; live pass landed (store: T-002 `done`, owner pass 2026-11-14; recurrence rides T-039)** | U-003 |
| v2 parity: permission asks, forms, questions, undo/redo, fork at any message | ✅ | T-004/U-010/U-011/U-014/U-044 |
| Chat TUI parity: compaction divider row (full summary streamed + usage suffix, live and across reload) | ✅ | T-145/T-147 (ViewIntercept live pass) |
| Chat TUI parity: status strip — context window usage, session cost, goal chip + dialog (pause/resume/clear) | ✅ **live-accepted + deployed (v0.4.0)** | T-148/T-149 (intercept `status strip rendered …`, `goal dialog opened: status=complete`) |
| Governed `/goal` intake - `<objective>` captured CLIENT-SIDE as a requirements ticket (objective verbatim), `pause/resume/clear` → store semantics (paused / back to the pump / close-or-split), completion engine-only | ✅ **landed (U-105):** the server's session self-review loop is never driven; the split keeps every basis chain valid (done + artifact) | U-105 (GoalIntakeTest 6/6) |
| Chat TUI parity: Session Details **Context** block (`1,234 tokens` / `57% used` / `$9.96 spent`) over the shared `ContextUsage` seam | ✅ | T-151 (intercept `session-details … context=…`) |
| Chat TUI parity: Providers **Usage limits** — `Budget` / `Spent` / `Left` per model + `Usage: Workspace: … · Resets …` description (`usage.show`) | ✅ **live-accepted 2026-11-17 (store: T-152 `done`)** | T-152 (intercept `providers` -> `Usage: Workspace: Default · $0 used of $100 · $100 left · Resets Dec 1, 1:00 AM cells=76/138`, gate green, contract 5/5) |
| Chat TUI parity: model combo **detail** — `price · context window · Reasoning` beside the selector | ✅ **live-accepted + deployed** | T-153 (intercept `model-detail=… detail=Free · 1,048,576 tokens`) |
| @ file references, queued composer (steer/queue), Alt+Enter inbox | ✅ | U-012/T-005 |
| **Steer a running turn (Enter injects; Alt+Enter queues) + `Steer…` menu on any session incl. subagents** | ✅ | U-092 (v0.1.8; live-probed) |
| Background sessions (Ctrl+B, list, bring back) | ✅ | U-042/T-005/U-064 (store: U-064 `done`, purged `6248c00`) |
| Model selector commit discipline | ✅ | U-008 |
| Session continuity across restarts — opens on the **project's most recently active session** (one ground of truth, whichever client wrote it), remembered id as fallback | ✅ | U-039, T-180 |
| **Progress-aware reply budget (long healthy turns must not fail)** | ✅ (landed in the 2026-10-07 round - HISTORY H14 "no more *Send failed 300s*"; B-024 purged from the store, so read HISTORY, not a status here) | **B-024** (high) |
| Queue as a proper table | ✅ (store: U-071 `done`, purged `6248c00`) | U-071 |
| Mid-turn settle at tool-call boundary | ✅ | T-010 |

## 4. Server view & session surfaces

| Feature | Status | Evidence / ticket |
|---|---|---|
| Tree: server → categories (agents/sessions/active files/working set/MCP/skills) | ✅ | |
| Sessions: top-level nesting, busy-first, subagent nesting | ✅ | U-041 |
| **Machine-wide scope toggle (repo ⇄ all 211 sessions, persisted, LIST_LIMIT=500)** | ✅ | U-066 (**v0.1.5**; probe-cited — version string corrected in review) |
| **Emoji language: entity kinds + state glyphs (○/🏃/🔴) + tool glyphs + live cost** | ✅ | U-093 (v0.1.8) + one-icon/colorful iterations |
| Live activity labels (tracker + SSE snippet) | ✅ | U-093 + pre-existing tracker lane |
| `Steer…` context menu on any session row | ✅ | U-092 |
| Session details view (messages/parts/tools/tokens, live) | ✅ | H3-003 |
| **Collected agent-questions surface (subagent questions + one list of all pending asks)** | ✅ (store: U-096 `done`; review verdict PASS 2026-11-15, purged `6248c00`) | U-096 |
| Session todo state (needs v2 endpoint) | 🅿️ | U-013 upstream-blocked |

## 5. Board

**Currency (T-250, 2026-11-26):** the two *fleet* rows in this table - the Fleet jobs tree and the fleet diff in the compare editor - went away with the fleet in the U-110 arc (no `FleetView`/`FleetTree` in the tree, verified by T-215), so their ✅ reads as *shipped and verified at the time*; the auto-dispatch toggle row is likewise not a live surface (T-251, see its cell); the rest of the section is the live Board.

| Feature | Status | Evidence / ticket |
|---|---|---|
| Kanban over the store; pipeline + V-model layouts (10 stage columns, chevrons) | ✅ | H2-001/U-016/U-026/U-028 |
| Readiness surfacing (badges, freshness, refresh on activation) | ✅ **gate-verified; live pass landed (store: T-002 `done`, owner pass 2026-11-14; recurrence rides T-039)** | U-018/U-035/H6 |
| Auto-dispatch toggle + shared reservations | ✗ not present at HEAD (the fleet-era Auto pump removed U-110, `prepareAutoDispatch` removed `c5dfb07`; the chunk-5 Auto/Tickers switch never built) | P-003 (acceptance: U-037 — the run is recorded 2026-11-17, §11; store: U-037 `done`, purged `6248c00`) |
| Fleet jobs tree incl. peer engines + engine-state dump (G-006) | ✅ | U-040/F-004/U-024/G-006 |
| Watch a running job's session live; fleet diff in Eclipse compare editor | ✅ | U-015/U-019 |
| Columnar cards + consistent icons in every Group-by | ✅ (store: U-065 `done`, purged `6248c00`) | U-065 |
| Emoji consistency across harness UI | ✅ (store: U-074 `done`, purged `6248c00`) | U-074 (pairs with U-093) |
| WIP indicators / bug decorations / ~~sprint selection~~ | ✅ **gate-verified; live pass landed (store: T-002 `done`, owner pass 2026-11-14; recurrence rides T-039)** | U-005/U-006/U-028; ~~sprint selection~~ removed (T-202) — superseded by the Board's stage filter (`BoardView.stageFilterAction`) |

## 6. Fleet engine

**Currency (T-250, 2026-11-26):** the engine this whole section describes - the fleet, its `fleet_*` tools (`fleet_job_activity`, the permissions pair), the daemon and the Fleet view - was **removed with the fleet in the U-110 arc (2026-11-14)**, so ✅ here means *shipped and verified at the time*, never a surface today. Two things genuinely survive the removal: the worktree-isolation layer (`.git/opencode-fleet/`, now the ticket-isolation layer - see `docs/new-machine-setup.md`, *Machine-local state*) and the rules re-homed by the rows that carry their own note (resolution-first → the `ticker-v` pass; `inputs changed:` → `task_readiness`).

| Feature | Status | Evidence / ticket |
|---|---|---|
| Worktree isolation + merge-back + settle reaps | ✅ | B-006/B-011 |
| Daemon mode + graceful full-stack shutdown | ✅ | V-006/U-036/U-038/U-045 |
| Progress-aware watchdog + abort snapshots | ✅ | B-008/B-015 |
| Cross-engine dispatch guard + startup reconciliation | ✅ | F-003/F-002/G-001 |
| Failure-path truth + generated server password | ✅ | F-001/P-002 |
| Cost/token budgets + per-run model lever | ✅ | P-003 |
| fleet permissions tools (chat answers unattended asks) | ✅ | P-001 |
| `fleet_job_activity` observability + artifact correction + session-title prefixes | ✅ | B-013/T-001/U-053/U-054/U-055 |
| Resolution-first ticks (blocked first) | ✅ (store: U-050 `done`, review ACCEPT 2026-10-16 - the fleet-era schedulers it wired are gone with U-110; the ticker-v pass carries the rule today) | U-050 |
| `inputs changed:` markers | ✅ **landed (U-052):** the production `task_readiness` tick records exactly one marker per STALE verdict, decided on the D-003 option-B `inputs_changed_at` stamp (structured writes only; comments never stale) | U-052 (InputsChangedStampTest 5/5) |
| StageAwareAcceptance settle check (load-sensitive flake) | 🅿️ obsolete (store: B-025 `done` - closed 2026-11-14: the test went with the fleet bundle in U-110, no remaining subject; purged `6248c00`) | B-025 |

## 7. Quality gates (the final check, project-local)

| Lane | Status | Evidence |
|---|---|---|
| Tycho `verify` = 28/28 | ✅ | every round |
| Checkstyle + clean-architecture exec check | ✅ | every build |
| **eclipse-free import scans (per-bundle exec checks)** | ✅ | build-failing every build (found by review) |
| Spotless (GJF applied + check) | ✅ | U-087 |
| SpotBugs + PMD-CPD (`-Pquality`) | ✅ | |
| ArchUnit layer graph + namespace rules | ✅ | U-083 |
| osv-scanner SCA + rationale baseline | ✅ | U-084 |
| JaCoCo per-module + aggregate + 55% check | ✅ | U-085/U-088 |
| PIT mutation (mojo, 255/263 = 97%) | ✅ | U-089/U-091 |
| Error Prone (plain-javac modules) | ✅ | U-089 (zero findings) |
| Markdownlint + PSScriptAnalyzer | ✅ | U-086 |
| Build guard: concurrent-build cap + commit-headroom floor in `build.ps1` | ✅ (store: T-177 `done`; owner directive 2026-11-17, four live proofs: solo / contention-refusal / floor-refusal / skip bypass) | T-177 |
| **chat-web Node checks (renderer/bridge/mermaid)** | ✅ | QUALITY row (found by review) |
| osv `--licenses` inventory + policy | ✅ | `-Licenses` |
| Linux CI leg | ✅ | landed + blocking (T-020); ubuntu leg cleared - store: T-027 `done`, `verify.yml` runs on every push with both legs green (owner directive 2026-11-14, `61b9434`) |

## 8. Upstream operations

| Feature | Status | Evidence |
|---|---|---|
| ServerVersionPin + mismatch warning | ✅ | H-002 |
| Standing cross-check round (diff + review + pin + baseline) | ✅ | U-063 (2.0.28), U-095 (2.0.29), **U-097 (2.0.30)**, then continuous rounds through **2.2.0** (2.1.0/2.1.3/2.2.0 — the 2.2.0 round added the behavioral-scan rule; U-098/U-100 design hooks recorded) — three identical-surface rounds before 2.1.0 |
| Live-contract probing before adoption | ✅ | steer probe, session list probe |
| v2 surface remainder adopted | ✅ | U-046/U-048 |

## 9. Onboarding, deploy & docs

| Feature | Status | Evidence |
|---|---|---|
| **Repo-driven self-configuration (open repo → everything sets up)** | ✅ | O-001 (found by review) |
| Board reset / new-project actions (never hand-delete store files) | ✅ | O-002 |
| Auto-deploy (merge-back builds main + refreshes dropins) | ✅ | U-034 |
| CDT integration: ProjectContext + markers bridge | ✅ | H4-002 |
| Agent Tools console (live verification) | ⛔ | T-040 `blocked` (owner disposition per its blocker - rides the eclipse-build deprecation, po comment 2026-11-18); the earlier verification ticket U-009 is `done`/purged - it was the store's only blocked ticket *at the time*, the live blocked set is read with `task_list(status=blocked)` |
| V-model-only repo set + "flow vs final check" strategy docs | ✅ | curation `1a317e6` + strategy `500a4e2`; **doc-sync fixed 2026-10-16 post-review (AGENTS.md/QUALITY/adoption header)** |
| Docs currency discipline (AGENTS/ROADMAP/QUALITY/adoption) | ✅ | T-011..T-025 series + post-curation sync today |
| Historical evolution in docs/HISTORY.md | ✅ | T-018 |
| Competitive landscape review | ✅ (store: U-076 `done`, purged `6248c00`) | U-076 |
| README animated V-model GIF | ✅ (store: U-077 `done`; the GIF is live at README:22, purged `6248c00`) | U-077 |
| Greenfield proof: fresh repo + fleet end-to-end | 🅿️ obsolete (store: U-090 `done` - closed 2026-11-14: the fleet was removed in U-110, so there is no fleet to enable; greenfield flow = stage tickers + goal intake) | U-090 |
| Build perf: Defender exclusions | ✅ | T-006 |
| Build perf phases 1+2 (target diet, dev profile, hybrid test lane) | ✅ (store: T-007 + T-008 `done` - accepted 2026-11-18 / 2026-11-23) | T-007 / T-008 |
| Release machinery (set-version → tag → bump script per D-002) | ✅ (store: T-030 `done`, purged `6248c00`; `eclipse/releng/release.ps1` ran every tag through v0.4.6) | T-030 |

## 10. What is NOT (deliberately)

| Item | Status |
|---|---|
| `/share` (no server route) | 🅿️ upstream-blocked; **upstream removed sharing entirely in 2.0.30 (#55716) — audit trail matches our honest notice** |
| Interactive PTY host | 🅿️ platform-blocked |
| Sandboxing, remote fleets, M4 port, cpp extraction, multi-level rollup, cpp stdio pack | 🅿️ parked / non-goals |
| Pre-curation history rewrite | 🅿️ forward-only rule accepted |

## 11. Stability & usability assessment (post-rubberduck, re-checked 2026-11-17; store re-read 2026-11-26)

**Stable where measured:** engine, store, fleet, gates carry gate-grade evidence (28/28, arch rules, PIT 97%, probed pins, three identical-surface upstream rounds).

**The original rough-edge list is drained** - all five tickets closed: B-024 (chat send stability) ✅, the 13-ticket in-review drain ✅ (that wave landed; the store then ran to 343 done), T-002 + U-009 live pass ✅ (2026-11-15/16, interception evidence), U-037 acceptance run ✅ (real tokens spent, waves cut), U-075/U-096 daily-feel items ✅ (report + follow-up **T-156** - the store keeps it `ready`, not parked: read `task_get(T-156)` for its state; its three probe steps need a running Eclipse plus a human interaction session, so it waits on the owner's live pass and becomes claimable the moment that session happens).

**What actually gates "usable" (assessment dated 2026-11-17; statuses re-read from the store 2026-11-26):**

1. **The live acceptance loop (owner at the keyboard)** - the `owner-acceptance` instances are read from the store (`task_list(status=in-review)` / the Board's in-review column - never a count in this file): on 2026-11-26 all six sit `in-review` (T-161/T-162/T-165/T-167/T-171/T-173), three of them carrying live evidence dated 2026-11-20 (T-161 REST shape, T-171 openapi paths, T-173 live WS round-trip) and three still awaiting a live run (T-162/T-165/T-167); T-040 stays `blocked` (owner deprecation of the `eclipse-build` endpoint, po comment 2026-11-18). Non-blocking per T-039.
2. **The in-review drain** - the live set is read with `task_list(status=in-review)`, never restated here: the snapshot of 21 (2026-11-17) went stale within days, and reads taken minutes apart on 2026-11-26 already disagreed with each other, so this file describes the set **by id, not by number** - the six `owner-acceptance` instances (T-161/T-162/T-165/T-167/T-171/T-173) are its standing part (`task_list(status=in-review)` on 2026-11-26 returned exactly those six plus T-230/T-232/T-238, the docs-consolidation tickets awaiting their verdict at that moment; ask the verb for today's total). The ticker has stayed off since the owner directive of 2026-11-17 (`.harness/ticker-state.json` still records `enabled:false`), so verdicts resume when it is re-armed (`ticker-setup.ps1`, the owner's call) or through an owner-directed review.
3. **The release cut** - human-only mechanics (`eclipse/releng/release.ps1`, DENY-5): a release is cut only where a human review accepts (Standing rules) - the current release state is read from `git tag` / `docs/HISTORY.md`, never asserted here (this row's earlier "v0.4.1 … the owner runs the cut when ready" snapshot rotted: tags now run through v0.4.6).

**Single most valuable next move:** drain the in-review set with a directed review - read it with `task_list(status=in-review)`, don't take a number from this file - then cut the next release when the nine conditions hold.
