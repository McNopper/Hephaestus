# Feature matrix — Hephaestus harness

## About this document

- **Kind:** doc / live inventory; **read by:** the owner and any agent planning the next wave; **written by:** the orchestrator (2026-10-16), **v2 after rubberduck review** (findings applied, see notes inline).
- **Goal:** see at a glance what the harness already *does*, what is built but unaccepted, and what is still missing — in service of "the harness gets stable and usable".
- **Source of truth:** the task store (170 done / 13 in-review / 22 backlog / **1 blocked** at capture) plus AGENTS.md and the v0.1.9 codebase; the store wins where they disagree. Counts verified by the rubberduck pass.
- **Legend:** ✅ shipped + verified · 🔶 built, acceptance pending (in-review) · 🟡 backlog · ⛔ blocked (ticket or decision) · 🅿️ parked / non-goal · 👤 human action (owner's go / live pass)

## 1. V-model engine & task store

| Feature | Status | Evidence / ticket |
|---|---|---|
| Task store (file-based blackboard, one md per ticket) | ✅ | E-001; `tasks` MCP serves all agents |
| V-stage field + advance/send-back + readiness (READY/STALE/BLOCKED/RUNNING) | ✅ | V-001; H6 readiness |
| V-enforcement: stage artifacts, top-down coverage injection, DONE as gate verdict | ✅ | U-078..U-082 (v0.1.4) |
| Stage pass-through: `task_pass_stage` + `pass-declare:` pump path (zero sessions) | ✅ | U-057/U-058 (v0.1.9) |
| Horizontal loop `task_report_horizontal` | ✅ | U-082 |
| Blocked as a status (schema + resumeTo + surfaces) | 🔶 | U-067/U-068/U-069 (in-review) |
| Five V-chains walked to test-requirements | ✅ | T-026 (v0.1.9) |
| `task_traceability` | 🔶 | U-070 in-review; **D-001 decided** (three signals) |
| Archive-aware readiness + auto_archive | 🟡 | U-059/U-060 |
| Wave digest (record + board render) | 🟡 | U-061/U-062 |
| Store glitches (wave timestamps, malformed id, artifactless done rows) | 🟡 | T-028/T-029 |
| planSprint meta counter quirk | 🟡 | U-094 |

## 2. PM workflow & waves

| Feature | Status | Evidence / ticket |
|---|---|---|
| Scrum-like waves (plan/claim/review/accept; chat-first) | ✅ | waves S-01..S-13 closed |
| `project-manager-*` skill set (8) + PM agent | ✅ | curated set |
| Recurring waves + auto-dispatch machinery (budgets, chat-triggerable) | ✅ machinery | U-021/U-022/P-003 |
| **Live auto-dispatch acceptance run** | 👤 | U-037 (owner go; spends real tokens) |
| Clarification loop (NEEDS-HUMAN last) + reviewer auto-accept | ✅ | U-023/U-021 |
| Stage model policy (per-stage model+variant) | 🔶 | U-072/U-073 (in-review) |
| STALE semantics decision (any write → STALE?) | ⛔ | D-003 (only genuinely open decision) |

## 3. Chat surface

| Feature | Status | Evidence / ticket |
|---|---|---|
| Streaming chat (progressive markdown, inline Stop, tool parts) | ✅ **gate-verified; live pass pending (T-002)** | U-003 |
| v2 parity: permission asks, forms, questions, undo/redo, fork at any message | ✅ | T-004/U-010/U-011/U-014/U-044 |
| @ file references, queued composer (steer/queue), Alt+Enter inbox | ✅ | U-012/T-005 |
| **Steer a running turn (Enter injects; Alt+Enter queues) + `Steer…` menu on any session incl. subagents** | ✅ | U-092 (v0.1.8; live-probed) |
| Background sessions (Ctrl+B, list, bring back) | ✅ | U-042/T-005; 🔶 keybinding U-064 |
| Model selector commit discipline | ✅ | U-008 |
| Session continuity across restarts | ✅ | U-039 |
| **Progress-aware reply budget (long healthy turns must not fail)** | 🔶 | **B-024 (high, in-review)** |
| Queue as a proper table | 🔶 | U-071 |
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
| **Collected agent-questions surface (subagent questions + one list of all pending asks)** | 🟡 | U-096 |
| Session todo state (needs v2 endpoint) | 🅿️ | U-013 upstream-blocked |

## 5. Board

| Feature | Status | Evidence / ticket |
|---|---|---|
| Kanban over the store; pipeline + V-model layouts (10 stage columns, chevrons) | ✅ | H2-001/U-016/U-026/U-028 |
| Readiness surfacing (badges, freshness, refresh on activation) | ✅ **gate-verified; live pass pending (T-002)** | U-018/U-035/H6 |
| Auto-dispatch toggle + shared reservations | ✅ machinery | P-003 (acceptance: U-037 👤) |
| Fleet jobs tree incl. peer engines + engine-state dump (G-006) | ✅ | U-040/F-004/U-024/G-006 |
| Watch a running job's session live; fleet diff in Eclipse compare editor | ✅ | U-015/U-019 |
| Columnar cards + consistent icons in every Group-by | 🔶 | U-065 |
| Emoji consistency across harness UI | 🔶 | U-074 (pairs with U-093) |
| WIP indicators / bug decorations / sprint selection | ✅ **gate-verified; live pass pending (T-002)** | U-005/U-006/U-028 |

## 6. Fleet engine

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
| Resolution-first ticks (blocked first) | 🟡 | U-050 (production schedulers) |
| `inputs changed:` markers | 🟡 | U-052 |
| StageAwareAcceptance settle check (load-sensitive flake) | 🟡 | B-025 (stability watch) |

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
| **chat-web Node checks (renderer/bridge/mermaid)** | ✅ | QUALITY row (found by review) |
| osv `--licenses` inventory + policy | ✅ | `-Licenses` |
| Linux CI leg | 🔶 | landed + blocking (T-020); ubuntu leg under re-diagnosis (T-027) |

## 8. Upstream operations

| Feature | Status | Evidence |
|---|---|---|
| ServerVersionPin + mismatch warning | ✅ | H-002 |
| Standing cross-check round (diff + review + pin + baseline) | ✅ | U-063 (2.0.28), U-095 (2.0.29), **U-097 (2.0.30, in flight today)** — three identical-surface rounds |
| Live-contract probing before adoption | ✅ | steer probe, session list probe |
| v2 surface remainder adopted | ✅ | U-046/U-048 |

## 9. Onboarding, deploy & docs

| Feature | Status | Evidence |
|---|---|---|
| **Repo-driven self-configuration (open repo → everything sets up)** | ✅ | O-001 (found by review) |
| Board reset / new-project actions (never hand-delete store files) | ✅ | O-002 |
| Auto-deploy (merge-back builds main + refreshes dropins) | ✅ | U-034 |
| CDT integration: ProjectContext + markers bridge | ✅ | H4-002 |
| Agent Tools console (live verification) | ⛔ | **U-009 — the store's only blocked ticket** (needs owner's restart; rides T-002) |
| V-model-only repo set + "flow vs final check" strategy docs | ✅ | curation `1a317e6` + strategy `500a4e2`; **doc-sync fixed 2026-10-16 post-review (AGENTS.md/QUALITY/adoption header)** |
| Docs currency discipline (AGENTS/ROADMAP/QUALITY/adoption) | ✅ | T-011..T-025 series + post-curation sync today |
| Historical evolution in docs/HISTORY.md | ✅ | T-018 |
| Competitive landscape review | 🔶 | U-076 (in-review) |
| README animated V-model GIF | 🔶 | U-077 |
| Greenfield proof: fresh repo + fleet end-to-end | 🟡 | U-090 |
| Build perf: Defender exclusions | ✅ | T-006 |
| Build perf phases 1+2 (target diet, dev profile, hybrid test lane) | 🟡 | T-007/T-008 |
| Release machinery (set-version → tag → bump script per D-002) | 🟡 | T-030 |

## 10. What is NOT (deliberately)

| Item | Status |
|---|---|
| `/share` (no server route) | 🅿️ upstream-blocked; **upstream removed sharing entirely in 2.0.30 (#55716) — audit trail matches our honest notice** |
| Interactive PTY host | 🅿️ platform-blocked |
| Sandboxing, remote fleets, M4 port, cpp extraction, multi-level rollup, cpp stdio pack | 🅿️ parked / non-goals |
| Pre-curation history rewrite | 🅿️ forward-only rule accepted |

## 11. Stability & usability assessment (post-rubberduck)

**Stable where measured:** engine, store, fleet, gates carry gate-grade evidence (28/28, arch rules, PIT 97%, probed pins, three identical-surface upstream rounds).

**The rough edges that gate "usable" (re-ordered after review):**

1. 🔶 **B-024** (high): long healthy turns must not fail the chat send — a stability bug *built, unaccepted*.
2. 🔶 **The in-review drain**: 12 of 13 in-review tickets = the U-064..U-077 UX wave + B-024 (U-075 never left backlog) — acceptance costs no human time (reviewer auto-accept exists).
3. 👤 **T-002 (high) + ⛔ U-009**: the daily-driver UI has **never had its live in-Eclipse verification pass**; three ✅ rows above are gate-asserted, not observed. Blocked on the owner's restart.
4. 👤 **U-037**: the autonomy loop's acceptance run has never spent a real token.
5. **U-075** (responsiveness question, unmeasured) + **U-096** (collected questions) — the daily-feel items.

**Single most valuable next move (from the critic):** drain the 13-ticket in-review wave (ships B-024 + the blocked-state schema), then schedule the owner's T-002 live pass.
