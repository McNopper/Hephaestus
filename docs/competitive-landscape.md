# Competitive landscape — where the Hephaestus harness stands

## About this document
- **Kind:** `doc` / market-position analysis (dated snapshot; not a plan)
- **Read by:** evaluators, contributors and the human (Product Owner) deciding where to invest;
  **written by:** the `research` agent, 2026-10-07
- **Related:** pairs with `README.md` (what the harness *is*), `docs/spice-alignment.md`
  (the V-model/SPICE basis of our pipeline) and `AGENTS.md` (the living process definition).

## TL;DR

**Confidence: Medium-High** (factual cells are sourced from official docs/papers fetched
2026-10-07; judgments about "who leads" are argued, not benchmarked).

Hephaestus occupies a thin, mostly empty niche: it is the only solution found that combines
(1) **store-mediated multi-agent coordination** (a versioned Markdown ticket blackboard with
atomic claim, no agent-to-agent messaging), (2) a **10-stage V-model pipeline with paired
definition/verification artifacts** aligned to ISO/IEC 15504/Automotive SPICE, and (3)
**budgeted autonomous "waves"** with a human as last resort [1][2][3]. Commercial harnesses
beat it decisively on maturity, cloud execution and polish; frameworks (LangGraph, CrewAI,
AutoGen/AG2, OpenAI Agents SDK, Microsoft Agent Framework, Claude Agent SDK) beat it on
runtime machinery but contain **no** requirement→test traceability at all. Closest
convergences: Claude Code's experimental *agent teams* (shared task list + file-locked
claiming) [8], GitHub's *Spec Kit* and Amazon *Kiro* (staged spec→plan→tasks pipelines)
[28][29]. Nobody found implements horizontal, level-matched verification pairs or SPICE-style
readiness gating for agent work — that is our genuine white space (and, being process
conventions, easy for a competitor to copy).

---

## Scope and method

- **Question:** how does the Hephaestus harness (opencode-native, Eclipse-integrated, ticket
  store + V-pipeline + fleet) compare to agent coding products, multi-agent frameworks, and
  PM/traceability tooling, as of **2026-10-07**?
- **What does *not* count as an answer:** marketing claims without documentation, benchmark
  numbers without a citable source, and anything not actually fetched — such items are marked
  **unverified** below rather than guessed.
- **Sources:** official product docs, official engineering blogs, peer-reviewed papers,
  official standards pages; GitHub star/activity counts from the public GitHub API
  (2026-10-07) [44]. Full dated list at the end. Wikipedia/SEO review sites were used only as
  leads and are not cited.
- **Honesty note about us:** Hephaestus is early-stage software (repo created 2026-06-06,
  3 stars, MIT; the README itself warns "expect rough edges") [1][44]. Every "we lead" claim
  below is a *capability* claim, not a market position.

---

## Comparison matrix

Columns: **Orch** = orchestration model; **Coord** = how concurrent agents coordinate;
**Verif/Trace** = verification + traceability of output; **Autonomy/Budget** = unattended runs
and spend controls; **Models** = model flexibility; **Maturity** (stars from GitHub API,
2026-10-07 [44]; vendors' products are not all public repos).

### Class A — Agent coding harnesses (CLI / IDE / cloud)

| Solution | Orch | Coord | Verif/Trace | Surfaces | Autonomy/Budget | Models | Maturity |
|---|---|---|---|---|---|---|---|
| **Hephaestus (us)** [1][2][3] | PM agent plans waves; workers self-claim by role; reviewer pass per merge | Versioned Markdown ticket store served over MCP (`task_*`); atomic `task_claim`; peers never message each other; git-worktree isolation per run | Paired definition↔verification artifacts created at V-stage entry; `task_traceability` / `task_readiness` / `task_doctor`; deterministic 27-module Tycho gate (checkstyle, tests, architecture check); NEEDS-HUMAN escalation | opencode TUI + Eclipse plugin (Board/Fleet/chat) | Recurring unattended **waves** with concurrency + cost budgets; readiness gating (READY/WAIT_UPSTREAM/STALE); model precedence: dispatch override > ticket `model` > per-stage policy > server default [3] | Model-neutral tiers; any opencode provider; per-ticket/per-stage cost lever | Early-stage: 3 stars, created 2026-06, single maintainer [44] |
| **opencode** (upstream) [4][5] | Primary agents (Build/Plan) + subagents via Task tool → child sessions | Parent/child sessions only; no shared work queue; per-agent permissions & worktree isolation | None built in (Plan mode + permissions only; quality gates are the repo's own CI) | TUI, CLI, web, IDE extension, desktop | Per-agent `steps` cap as a cost control; permission asks as human gate | Any provider; per-agent `model` override | OSS, MIT; 212k stars, created 2025-04 [44] |
| **Claude Code** [6][7][8] | Lead orchestrates subagents; experimental **agent teams**: lead assigns or teammates self-claim | Subagents: results to caller. Teams: shared task list with dependencies + file-locked claiming + mailbox messages between sessions | Hooks (incl. `TaskCompleted` that can *block* completion), plan-mode approvals, worktree isolation, CI via GitHub Actions/GitLab | Terminal, VS Code/JetBrains, desktop, web, Slack, CI | Routines (cloud-scheduled), desktop scheduled tasks, `/loop`; subagent requests count against plan usage limits | Claude aliases per subagent (`opus`/`sonnet`/`haiku`), `CLAUDE_CODE_SUBAGENT_MODEL`; third-party providers via integrations | Anthropic; public repo 150k stars, created 2025-02 [44]; teams still experimental |
| **GitHub Copilot cloud agent** [10][11] | One autonomous session per task; custom agents; no agent swarm | GitHub Issues/PRs + chat as the coordination surface; chat↔session context sharing; PRs are the hand-off | Runs tests/linters in ephemeral cloud env, opens PR, requests review; cannot approve its own PRs; automations explain changes with **rationale + confidence**, low-confidence issue edits held for review | GitHub.com (cloud), IDE agent mode, CLI, Teams/Slack integrations | **Automations**: schedule (hourly/daily/weekly) or repo events; least-privilege tool scoping; prompt-injection guards (ignores non-writer events by default); billed per run (Actions minutes + AI credits); 59-min session cap, 1 branch/PR per task | Multi-model per plan, auto model selection, BYOK [12] | Microsoft/GitHub; GA + public-preview features |
| **Cursor** [13][14][15] | Local agent + **Cloud Agents**: unlimited parallel isolated VMs; one branch per agent | Agents run independently; runs shareable with team; entry points from web/iOS/desktop/**Slack/GitHub/Linear/API** | Dev environments let agents build/test; **proof artifacts** (screenshots, videos, logs) attached; remote-desktop takeover to verify by hand; hooks run formatters/policy checks in cloud | IDE, web, mobile, Slack, issue trackers, API | Unattended parallel cloud agents; **spend limit required** on first cloud use; billed at API price of chosen model | Large curated multi-vendor list (Anthropic/OpenAI/Google/Z.ai/Moonshot/…); curated set for cloud agents | Cursor Inc.; commercial, paid plans only |
| **Devin** (Cognition; Windsurf merged in) [16][17] | Autonomous cloud sessions with embedded shell/IDE/browser; human can take over | Ticket-driven (Linear/Jira), Slack/Teams threads, sessions; `/handoff` CLI→cloud | Agent runs its own shell/IDE/browser tests; docs advise "make tasks easy to verify (CI passes)"; verification remains human review of the draft PR | Web app, Slack/Teams, CLI, API, **Devin Desktop** (Windsurf rebranded; packages renamed `devin-desktop`) | Parallel task sessions; plan/credit-based pricing | Vendor SWE models; per-model specifics **unverified** | Cognition; commercial since 2024; Windsurf acquisition visible in docs [17] |
| **Google Jules** [19][20][21] | Async per-task agent in a cloud VM; parallel tasks | Independent tasks against GitHub repos; notifications; scheduled tasks | **Plan must be reviewed/approved before code changes**; reads `AGENTS.md`; plan/code review UIs | Web (jules.google), REST API, CLI tools | Unattended async tasks + scheduled tasks; quota caps: 15/100/300 tasks per day and 3/15/60 concurrent by plan | Gemini models only (tier-gated) | Google; docs say "experimental" |
| **OpenAI Codex** [22] | Single agent locally (CLI/app) or in cloud (Codex Web) | Cloud tasks → PRs (issue/PR-centric); details of cloud concurrency **unverified** | Local sandboxing / cloud CI are documented product surfaces; specifics beyond README **unverified** | CLI, IDE extension, desktop app, chatgpt.com/codex | Unattended cloud tasks under ChatGPT plan allowances | OpenAI models; ChatGPT plan or API key | OpenAI; OSS CLI (Apache-2.0), 128k stars [44] |
| **Gemini CLI** [23] | Single terminal agent; headless mode for scripts | GitHub Action integration (PR reviews, issue triage, scheduled workflows) | No intrinsic test gate; sandboxing + trusted-folder policies; verification left to CI/human | Terminal (+ VS Code companion) | Free-tier rate limits (60 req/min, 1000/day); GitHub Action for scheduled/on-demand automation | Gemini only (OAuth, API key or Vertex) | Google; OSS Apache-2.0, 107k stars [44] |
| **aider** [24] | Single terminal pair-programmer; architect/code modes | Manages a local git repo (auto-commits); no multi-agent coordination | Automatic lint/test-fix loop; its own LLM leaderboards as evaluation | Terminal, browser, IDE watch mode | Interactive; no scheduled unattended runs documented | Many providers (OpenAI, Anthropic, Gemini, Ollama, Bedrock, Copilot, …) | OSS Apache-2.0; 49k stars, created 2023; last push 2026-05-22 [44] |
| **OpenHands** [25] | Agent Server + SDK; one or more agents behind a browser "Agent Canvas" | Automation Server handles scheduled/event-driven runs; Canvas can connect multiple backends | Sandboxed execution; evaluation repos/benchmark infra; Cloud offers **budget management** | Browser canvas, CLI, Cloud, Enterprise (self-host) | Scheduled + event-driven automations; hosted budgets | Provider-configurable (SDK); exact list **unverified** | OSS (MIT) 90k stars [44]; commercial Cloud/Enterprise |
| **SWE-agent** [26] | Research harness: single LM agent with an agent-computer interface | None (benchmark-oriented) | Evaluated on SWE-bench/HumanEvalFix (12.5% / 87.7% at publication) | CLI (research tool) | N/A | Any LM API | Princeton; OSS 20k stars [44]; academic |
| **JetBrains Junie** [27] | Autonomous IDE agent: plan → code → run inspections/tests → verify | Single-task; CLI + CI/CD modes (CLI "LLM-agnostic" beta per JetBrains blog index, 2026-03) | Runs IDE inspections and tests and verifies they passed; human reviews every change | IntelliJ/PyCharm/WebStorm, CLI (beta), CI/CD | Delegate routine tasks unattended; JetBrains AI license or BYOK | Multi-provider via JetBrains AI/BYOK (blog index); details **unverified** | JetBrains; announced 2025-01 (SWE-bench Verified 53.6% single-run, vendor-reported), out of beta 2026-06 |
| **Amazon Kiro** [29] | Spec-driven: Requirements → Design → Tasks, then per-task execution (parallel agents claimed on vendor homepage — homepage claim **unverified**) | Specs (3 Markdown files) + "steering" memory bank in the repo; human gate after each phase | Tasks **trace back to requirement numbers**; GIVEN/WHEN/THEN acceptance criteria; per-task "View changes" review | VS Code-based IDE, CLI, web, mobile (per docs nav) | Human-in-the-loop between phases; no scheduled autonomous backlog drain documented | AWS Bedrock-hosted models (widely reported; **unverified**) | AWS; launched 2025, commercial |
| **GitHub Spec Kit** [28][29] | Process *toolkit* (skills), not an agent: constitution → specify → plan → tasks → implement → **converge**; bug flow: assess → fix → test | Artifacts live in the repo; runs inside any coding agent (integration keys); coordination is human/agent chat | Checklists per stage ("definition of done" per step); bug workflow ends in a recorded verdict: **verified / partial / failed** — "missing verification is not a successful fix" | CLI scaffolding + chat slash-commands in any supported agent | Repeat implement→converge until converged; quality-gate skills (checklists, consistency analysis) | Any supported agent (Copilot, Claude Code, Codex, …) | GitHub; OSS MIT, **140k stars**, created 2025-08 [44] |

### Class B — Multi-agent orchestration frameworks (libraries you build a harness *on*)

| Solution | Orch | Coord | Verif/Trace | Surfaces | Autonomy/Budget | Models | Maturity |
|---|---|---|---|---|---|---|---|
| **LangGraph** [30] | Explicit state graphs mixing deterministic + LLM steps | Shared graph state; checkpointed/durable execution | None intrinsic; LangSmith adds tracing/evaluation (separate product) | Python library | Human-in-the-loop interrupts, durable resumption; budgets = your code | Any LangChain provider | LangChain Inc.; OSS 43k stars [44] |
| **CrewAI** [31] | Flows (event-driven process/state) delegate to Crews (role-based agents, task delegation) | Flow state + agent messaging inside a crew | None intrinsic | Python library | Long-running flows; token-efficiency is a stated goal, no spend cap primitive documented | Provider-configurable | OSS 59k stars [44] |
| **AutoGen** (Microsoft) [34] | Event-driven Core + AgentChat; Studio UI for no-code prototypes | Message-passing runtime (incl. distributed gRPC workers) | Code executors (e.g. Docker) run generated code; no requirement traceability | Python/.NET libraries + web Studio | Deterministic/dynamic workflows; human input requests | OpenAI and extension clients | Microsoft; OSS 61k stars [44]; superseded by Agent Framework [33] |
| **AG2** [35] | Async core; harness layers (assembly policies, knowledge store, sub-task delegation, middleware) | Fan-out to specialist agents as tools/subtasks; externalized state (History/Storage/Stream, e.g. Redis) | Built-in evaluation utilities (score/compare/regression-test) | Python library + UI/protocols (AG-UI, A2A, ACP) | Human-in-the-loop hooks; **token budgets as composable middleware** | OpenAI, Anthropic, Gemini, Vertex, Ollama, DashScope | OSS Apache-2.0, 5k stars; diverged from AutoGen 2024-11 [44] |
| **OpenAI Agents SDK** [32] | Agents + handoffs / agents-as-tools; guardrails | Handoff protocol + Python control flow; sandbox agents with isolated workspaces | Built-in tracing + evaluation hooks; guardrail validation of in/out | Python library | Human-in-the-loop primitives; sandbox sessions resumable | OpenAI by default; LiteLLM/Any-LLM adapters | OpenAI; OSS 30k stars [44] |
| **Microsoft Agent Framework** [33] | Agents + **Harness Agent** (planning/todos/compaction/approvals) + functional/graph **workflows** | Explicit execution paths; session state management | Middleware, telemetry, checkpoints; no requirement traceability | Python/.NET/Go libraries, Azure hosting | Checkpoint/resume, human-in-the-loop workflows | Foundry, Azure OpenAI, OpenAI, Anthropic, Ollama, more | Microsoft; successor to AutoGen + Semantic Kernel; active 2026 |
| **Claude Agent SDK** [9] | Claude Code's agent loop as a Python/TypeScript library | Subagents within the process; your app owns coordination | Hooks, permissions, sessions — verification logic is yours | Library (embedding) | Permissions/approvals; your budget logic | Anthropic models | Anthropic; production SDK |
| **Research evidence** [36][37][38] | Orchestrator–workers (Anthropic Research); SOP "assembly line" (MetaGPT, ICLR 2024 Oral); chat-chain phases design/coding/testing (ChatDev, ACL 2024) | Anthropic: subagents report to lead, artifacts written to a filesystem to avoid the "game of telephone"; MetaGPT: standardized actions / publish-subscribe; Anthropic notes agents use ~15× the tokens of chat | MetaGPT explicitly uses SOPs "to verify intermediate results" | — | Anthropic's eval: multi-agent beat single-agent 90.2% on their internal research eval (vendor-reported); same post: **coding tasks are less parallelizable** and multi-agent costs ~15× chat tokens | — | — |

### Class C — PM / traceability / verification angle

| Solution | What it gives agent work | Coordination | Traceability | Notes |
|---|---|---|---|---|
| **GitHub Issues/Projects + Copilot cloud agent** [10][11] | Issue-as-task queue; assign issue → agent plans/codes → PR → human review; automations trigger on schedule/events | The tracker *is* the blackboard; PRs are hand-offs | Issue↔PR↔commit linkage is inherent; no requirement-level model | Closest mainstream analogue to a "ticket store", but single-agent, no V-stages |
| **Linear + Cursor agent** [15] | Delegate issues to an agent from the PM tool; "My Issues" shows agent statuses (waiting input / working / done) | PM tool orchestrates multiple agents per issue, one agent per issue | Issue context → plan → PR; status surfacing only | Evidence that PM-tool-as-coordination-layer is emerging in commercial tooling |
| **ALM/requirements tools (Jama Connect, Codebeamer, Polarion, DOORS)** [41] | Bidirectional traceability, requirements traceability matrices (RTM), change-impact analysis, verification/validation chapters | Human/ALM workflows; no autonomous agents found | The strongest requirement↔test traceability on the market — for *human* engineering | None of these run fleets of coding agents; no authoritative source found combining ALM-grade traceability with agent execution |
| **Spec-driven development tools (Kiro, Spec Kit, Tessl)** [28][29] | Staged artifacts (requirements→design→tasks; specify→plan→tasks) with per-stage review and (Spec Kit) convergence/verdict steps | Repo-local artifacts; agent of choice executes | Kiro tasks carry requirement-number trace-back [29]; verification stays per-stage, **no horizontal verification leg** | Thoughtworks' evaluation warns of review overload and agents not following instructions [29] — an honest caution for us too |
| **Standards: ISO/IEC 15504 → 330xx, Automotive SPICE** [39][40] | The V-model requirement: every left-side definition process pairs with a right-side verification process (SYS.1↔SYS.5, SWE.1↔SWE.6), evidence as work products | — | ISO/IEC 15504-5:2012 is **withdrawn**, revised by ISO/IEC TS 33061:2021; ASPICE PAM is conformant with ISO/IEC 33004 | This is exactly what `docs/spice-alignment.md` operationalizes for agent tickets [2] |
| **SWE-bench / SWE-bench Verified** [42] | The de-facto verification benchmark for coding agents (real GitHub issues + tests), ICLR 2024 | — | Outcome-level verification only (did the patch pass tests) | Vendor adoption is universal (e.g. Junie's 53.6% figure [27]) but it says nothing about process traceability |

---

## Where we lead (specific and honest)

1. **Store-mediated multi-agent coordination.** Several agents (chat sessions, fleet workers,
   auto-dispatchers) coordinate *only* through a versioned Markdown ticket store with atomic
   `task_claim` — no agent messages another, so several agents and several users work the same
   project in parallel without blocking [3]. The nearest commercial equivalent, Claude Code's
   agent teams, is explicitly **experimental, session-scoped, one team per session, no nested
   teams** [8]; mainstream harnesses coordinate through Issues/PRs or not at all [10][13].
   Anthropic's own engineering post notes that "LLM agents are not yet great at coordinating
   and delegating to other agents in real time" [36] — the store is our answer to that, and no
   competitor documents an equivalent blackboard.
2. **V-model paired verification.** Tickets wander 10 stages and *must* create their paired
   verification counterpart at stage entry (requirements↔test-requirements, …,
   implementation↔test-implementation), with send-back loops — the ASPICE horizontal reporting
   line in motion [2][3]. Spec-driven tools (Kiro, Spec Kit) have staging but **no horizontal
   verification leg** [28][29]; coding agents verify by "does it build/test now", not by
   "does the acceptance test for this requirement exist and pass" [10][19]. **No authoritative
   source found for any product implementing level-matched verification pairs for agent work.**
3. **Per-stage traceability + effort record.** Artifacts live *on* the ticket
   (`task_add_artifact` before `in-review`), `task_traceability` reports paired-verification
   signals, `task_readiness` reports READY/WAIT_UPSTREAM/STALE, and `fleet actuals:` record
   cost/tokens per run [3] — a live SPICE-like process profile [2]. Jama-style RTM traceability
   exists [41] but runs no agents; agent products link issue→PR only [10].
4. **Eclipse integration.** A real IDE harness — Board (kanban + pipeline), Fleet view,
   Server/Providers views, token-authed MCP tool packs — while the whole stack still runs from
   a plain opencode TUI [1]. JetBrains has an IDE but no staged PM pipeline [27]; Eclipse-based
   teams are otherwise an unserved audience.
5. **Budgeted autonomous waves with human-as-last-resort.** Recurring waves drain the backlog
   under concurrency **and** cost budgets, with model choice as an explicit cost lever
   (dispatch override > ticket > per-stage policy > server default) [3], and NEEDS-HUMAN
   escalation as the only standing human duty. Compare: Copilot automations bill per run with
   no documented spend cap [11], Cursor requires a spend limit but only per-account [13],
   Jules caps *tasks/day* rather than cost [20], Claude Code schedules routines but caps by
   plan usage limits [6]. Ours is the only one combining autonomy + budget + escalation policy
   in one loop.

## Where we lag

1. **Maturity and community.** 3 stars / created 2026-06 / one maintainer vs opencode 212k,
   Spec Kit 140k, Codex 128k, Gemini CLI 107k, OpenHands 90k, Claude Code repo 150k [44].
   No users, no ecosystem, no support channel; bus factor = 1 [1].
2. **Platform breadth.** Local-only (TUI + Eclipse). Competitors run in managed cloud VMs with
   saved environments and artifacts (Cursor [13], Copilot [10], Jules [19], Devin [16],
   OpenHands Cloud [25]), and reach teams through Slack/Linear/GitHub/mobile/API entry points
   [13][15][16]. We have no remote execution, no web dashboard, no mobile surface.
3. **Environment & proof-of-work ergonomics.** No environment-as-first-class-artifact (Cursor's
   builds/snapshots [13]) and no standardized evidence bundle (screenshots/videos/logs) attached
   to a completed ticket — our gate is a build, our evidence is file refs.
4. **Evaluation/observability.** No built-in tracing or eval stack: LangSmith (trace → evaluate
   → propose fix) [30], AG2's evaluation utilities [35], OpenAI Agents SDK tracing [32],
   OpenHands benchmark infra [25], and Anthropic's documented LLM-judge + human-eval practice
   [36] all exist; we have history/actuals but no systematic agent-quality measurement.
5. **Polish and risk of abandonment.** Fleet ships **disabled by default** because it burns
   tokens [1]; features (recurring waves U-022, resolution pump U-050) are partially unwired
   road-map items [3]. Commercial products ship these as finished, tested UX.

## Notable ideas worth adopting

1. **Completion-veto quality gates (Claude Code agent teams).** Hooks `TaskCreated` /
   `TaskCompleted` can exit non-zero to *prevent* a task from being created or marked complete,
   plus task dependencies and file-locked claiming [8]. → Add a `task`-level gate hook so a
   reviewer-verdict or missing paired artifact physically blocks `done`, not just by convention.
2. **Rationale + confidence gating for autonomous edits (Copilot automations).** Every automated
   issue change carries an explanation and confidence rating; low-confidence changes are held
   for human review; least-privilege tool lists and "ignore events from non-writers" reduce
   prompt-injection exposure [11]. → Route low-confidence/ambiguous dispatches straight to
   NEEDS-HUMAN, and scope fleet tool permissions per ticket role.
3. **Proof-of-work artifacts (Cursor Cloud Agents).** Agents attach screenshots, videos and logs
   so a reviewer validates without checking out the branch [13]. → Make an evidence bundle
   (test output, gate log, diff summary) a required artifact kind on `in-review` tickets.
4. **Convergence + explicit verification verdicts (GitHub Spec Kit).** implement→converge loops
   until "Converged", and bug fixes ending in `verified / partial / failed` with the rule
   "missing verification is not a successful fix" [28]. → Adopt this verdict vocabulary for our
   PASS/FAIL/UNCLEAR and make "no linked test artifact ⇒ not done" mechanical.
5. **Plan-before-execute review (Google Jules).** Jules generates a plan and requires human
   review/approval before any code change [19]. → Pre-dispatch plan review for high-tier or
   high-cost tickets (cheaper models skip it) — a cost-aware analogue of our manifest/reviewer
   passes, and cheap insurance for `very-high` work.

## Gaps, negative results, and unverified items

- **No authoritative source found** for any coding-agent product implementing SPICE-style
  capability levels, horizontal verification pairs, or readiness (staleness) gating for agent
  tickets. This is our differentiator — and it is process, not code, so it is copyable.
- **Unverified:** Devin's supported models; Kiro's exact model hosting (Bedrock widely
  reported); Codex cloud concurrency/quota details; OpenHands provider list; Junie's BYOK
  specifics (only the JetBrains blog index titles were verified).
- **Vendor-reported, not independently benchmarked:** Anthropic's 90.2% multi-agent improvement
  and 15× token cost [36]; JetBrains' 53.6% SWE-bench Verified [27]. No public head-to-head
  benchmark of orchestration models exists — comparisons here are architectural, not numeric.
- **Kiro's docs pages do not render server-side** (fetch returns navigation only); Kiro workflow
  facts here rest on the Thoughtworks analysis [29] plus search-index excerpts of
  `kiro.dev/docs/specs/*` — treat as high-confidence but second-hand.
- **Automotive SPICE PAM PDFs** (VDA QMC) were confirmed via search-index excerpts of the
  official PDFs, not by a full re-fetch of the PDFs [40]; the same material is already grounded
  in `docs/spice-alignment.md` [2].
- **ReqToCode** (compile-time requirement traceability in code) is a **preprint, explicitly not
  peer-reviewed** [43]; it cites an FSE 2025 companion paper on embedding traceability in LLM
  code generation — venue taken from the preprint's reference list (secondary), not re-verified.

---

## References (all fetched/confirmed 2026-10-07 unless noted)

**Internal**
- [1] **Hephaestus README.** Maintainers, 2026. `README.md` (repo root). — harness scope, early-stage/token warning, TUI-vs-Eclipse capability table. quality: vendor-doc (self).
- [2] **SPICE/ASPICE alignment — the abstract process behind our workflow.** Maintainers, research dated 2026-10-07. `docs/spice-alignment.md`. — V-model mapping, source list for ASPICE/ISO material. quality: vendor-doc (self).
- [3] **AGENTS.md — Hephaestus opencode workflow.** Maintainers, updated 2026-10-07. `AGENTS.md` (repo root). — store mediation, ticket states, V-pipeline, waves/budgets, model precedence (dispatch > ticket > stage policy > default). quality: vendor-doc (self).

**opencode / Claude Code**
- [4] **OpenCode docs — Intro.** Anomaly (opencode), 2026. https://opencode.ai/docs/ — surfaces (TUI/CLI/web/IDE), any-provider config, Plan/Build modes. quality: vendor-doc.
- [5] **OpenCode docs — Agents.** Anomaly (opencode), 2026. https://opencode.ai/docs/agents/ — primary vs subagents, Task tool, per-agent model/permissions, `steps` cost cap. quality: vendor-doc.
- [6] **Claude Code docs — Overview.** Anthropic, 2026. https://code.claude.com/docs/en/ — surfaces, parallel subagents, Routines/scheduled tasks, third-party providers. quality: vendor-doc.
- [7] **Claude Code docs — Create custom subagents.** Anthropic, 2026. https://code.claude.com/docs/en/sub-agents — per-subagent model/tools/permissions, worktree isolation, maxTurns, usage limits. quality: vendor-doc.
- [8] **Claude Code docs — Orchestrate teams of Claude Code sessions.** Anthropic, 2026. https://code.claude.com/docs/en/agent-teams — shared task list, self-claim + file locking, mailbox messaging, `TaskCompleted` veto hooks, experimental limitations. quality: vendor-doc.
- [9] **Claude Code docs — Agent SDK overview.** Anthropic, 2026. https://code.claude.com/docs/en/agent-sdk/overview — Claude Code as a library; capabilities table. quality: vendor-doc.

**GitHub Copilot**
- [10] **GitHub Copilot on GitHub.com (cloud agent concepts).** GitHub, 2026. https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-cloud-agent — delegation from issues/PRs/chat, test+lint in ephemeral env, session limits (59 min, one branch/PR), chat↔session context, models link. quality: vendor-doc.
- [11] **About Copilot automations.** GitHub, 2026. https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-automations — schedule/event triggers, tool scoping, rationale+confidence approvals, attribution/approval rules, billing, prompt-injection guards. quality: vendor-doc.
- [12] **Models for GitHub Copilot.** GitHub, 2026. https://docs.github.com/en/copilot/concepts/models — multi-model, auto model selection, bring-your-own-key. quality: vendor-doc.

**Cursor / Linear**
- [13] **Cloud Agents.** Cursor, 2026. https://cursor.com/docs/cloud-agent — parallel cloud VMs, environments/builds, MCP, hooks, artifacts + remote desktop, spend limit, API pricing, entry points (Slack/GitHub/Linear/API). quality: vendor-doc.
- [14] **Cursor docs — home & model matrix.** Cursor, 2026. https://cursor.com/docs — multi-vendor model list (Anthropic/OpenAI/Google/Z.ai/Moonshot/…). quality: vendor-doc.
- [15] **Cursor background agents (changelog).** Linear, 2025-08-21. https://linear.app/changelog/2025-08-21-cursor-agent — issue delegation to an agent from the PM tool, agent status surfacing. quality: vendor-doc.

**Devin / Cognition**
- [16] **Introducing Devin.** Cognition, 2026. https://docs.devin.ai/get-started/devin-intro — autonomous sessions, embedded shell/IDE/browser, Linear/Jira/Slack, CLI↔cloud handoff, credits. quality: vendor-doc.
- [17] **Devin Desktop docs (served at docs.windsurf.com).** Cognition, 2026. https://docs.windsurf.com/ — Windsurf rebranded into Devin Desktop (`devin-desktop` packages, cognitionai doc assets). quality: vendor-doc.
- [18] **Don't Build Multi-Agents.** Walden Yan, Cognition, 2025-06-12. https://cognition.ai/blog/dont-build-multi-agents — share-context principle, argument against parallel multi-agent coding (position, contested — cf. [36]). quality: company-blog.

**Google Jules**
- [19] **Jules docs — Getting started.** Google, 2026. https://jules.google/docs — autonomous tasks in a cloud VM, **plan review/approval before code changes**, AGENTS.md support. quality: vendor-doc.
- [20] **Jules docs — Limits and Plans.** Google, 2026. https://jules.google/docs/usage-limits — 15/100/300 tasks per day; 3/15/60 concurrent; Gemini tiering. quality: vendor-doc.
- [21] **Jules: Google's autonomous AI coding agent.** Google, 2025. https://blog.google/innovation-and-ai/models-and-research/google-labs/jules — asynchronous agent, secure cloud VM, GitHub integration. quality: vendor-doc.

**Other coding agents/tools**
- [22] **Codex CLI README.** OpenAI, 2026. https://github.com/openai/codex — local CLI + IDE + app + Codex Web; ChatGPT-plan or API-key auth. quality: vendor-doc.
- [23] **Gemini CLI README.** Google, 2026. https://github.com/google-gemini/gemini-cli — open source, Gemini-only, free-tier quotas, GitHub Action, sandboxing, headless. quality: vendor-doc.
- [24] **aider documentation.** Aider AI, 2026. https://aider.chat/docs/ — terminal pair programming, git integration, lint/test auto-fix, broad LLM provider list, leaderboards. quality: vendor-doc.
- [25] **OpenHands docs — Introduction.** All Hands AI, 2026. https://docs.openhands.dev/ — canvas/SDK/Agent Server, Automation Server (scheduled + event-driven), Cloud with budget management. quality: vendor-doc.
- [26] **SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering.** Yang et al., 2024. https://arxiv.org/abs/2405.15793 — ACI design; SWE-bench 12.5% / HumanEvalFix 87.7% at publication (venue not stated on the abstract page — **unverified**). quality: peer-review-pending/preprint (paper itself).
- [27] **Meet Junie, Your Coding Agent by JetBrains.** A. Zakonov, JetBrains, 2025-01. https://blog.jetbrains.com/junie/2025/01/meet-junie-your-coding-agent-by-jetbrains — IDE agent, runs inspections/tests and verifies; SWE-bench Verified 53.6% (vendor-reported); blog index shows "Junie CLI, the LLM-agnostic coding agent, is now in Beta" (2026-03) and "Junie … Out of Beta" (2026-06). quality: company-blog.

**Spec-driven / staged pipelines**
- [28] **Spec Kit README.** GitHub, 2026. https://github.com/github/spec-kit — constitution→specify→plan→tasks→implement→converge; bug assess→fix→test with `verified/partial/failed`; 140k stars. quality: vendor-doc.
- [29] **Understanding Spec-Driven-Development: Kiro, spec-kit, and Tessl.** Birgitta Böckeler, Thoughtworks (martinfowler.com), 2025-10-15. https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html — Kiro Requirements→Design→Tasks with requirement-number trace-back; spec-kit checklists as per-step "definition of done"; critical field observations. quality: reputable engineering blog (analysis).

**Frameworks**
- [30] **LangGraph overview.** LangChain, 2026. https://docs.langchain.com/oss/python/langgraph/overview — state-graph orchestration, durable execution, HITL, LangSmith tracing/evals. quality: vendor-doc.
- [31] **CrewAI docs — Introduction.** CrewAI, 2026. https://docs.crewai.com/en/introduction — Flows (state/event-driven) + Crews (role agents, delegation). quality: vendor-doc.
- [32] **OpenAI Agents SDK.** OpenAI, 2026. https://openai.github.io/openai-agents-python/ — agents/handoffs/guardrails, HITL, tracing, sandbox agents. quality: vendor-doc.
- [33] **Microsoft Agent Framework overview.** Microsoft, 2026 (page updated 2026-08-25). https://learn.microsoft.com/en-us/agent-framework/overview — successor to AutoGen + Semantic Kernel; agents + Harness Agent + graph/functional workflows; multi-language. quality: vendor-doc.
- [34] **AutoGen documentation.** Microsoft, 2026. https://microsoft.github.io/autogen/stable/ — event-driven Core, AgentChat, Studio, extensions (MCP, Docker executor, gRPC runtime). quality: vendor-doc.
- [35] **AG2 docs — Overview/Motivation.** AG2, 2026. https://docs.ag2.ai/docs/user-guide/motivation/ — divergence from AutoGen (2024-11), harness with middleware **token budgets**, HITL hooks, evaluation, multi-provider. quality: vendor-doc.

**Coordination-model evidence**
- [36] **How we built our multi-agent research system.** Hadfield, Zhang, Lien, Scholz, Fox, Ford, Anthropic, 2025-06-13. https://www.anthropic.com/engineering/built-multi-agent-research-system — orchestrator–workers, ~15× chat tokens, 90.2% internal-eval gain (vendor-reported), coding less parallelizable, artifact-to-filesystem pattern, eval practice. quality: company-engineering-blog.
- [37] **MetaGPT: Meta Programming for A Multi-Agent Collaborative Framework.** Hong et al., 2023/2024. https://arxiv.org/abs/2308.00352 (ICLR 2024 Oral, listing at iclr.cc/virtual/2024/oral/19756) — SOPs in prompt sequences, assembly-line roles, agents "verify intermediate results". quality: peer-reviewed.
- [38] **ChatDev: Communicative Agents for Software Development.** Qian et al., accepted to **ACL 2024** (per arXiv comments), 2024. https://arxiv.org/abs/2307.07924 — chat-chain phases (design/coding/testing), communicative de-hallucination. quality: peer-reviewed.

**PM / traceability / standards**
- [39] **ISO/IEC 15504-5:2012 (Withdrawn; revised by ISO/IEC TS 33061:2021).** ISO, 2012/2021. https://www.iso.org/standard/60555.html — process assessment model status: the 15504 series is superseded by the 330xx family. quality: standard.
- [40] **Automotive SPICE PAM 3.1 (2017) and PAM 4.0 (2023).** VDA QMC. https://vda-qmc.de/wp-content/uploads/2023/02/Automotive_SPICE_PAM_31_EN.pdf and https://vda-qmc.de/wp-content/uploads/2023/12/Automotive-SPICE-PAM-v40.pdf — PRM/PAM, V-model process pairing, ISO/IEC 33004 conformance. *Confirmed via search-index excerpts of the official PDFs, not a full PDF re-fetch; mirrored by [2].* quality: standard.
- [41] **Requirements Traceability guide (bidirectional traceability, RTM, change-impact analysis).** Jama Software, 2026. https://www.jamasoftware.com/requirements-management-guide/requirements-traceability/ — ALM-grade traceability concepts; also indexes an ASPICE 4.0 guide. quality: vendor-doc.
- [42] **SWE-bench: Can Language Models Resolve Real-World GitHub Issues?** Jimenez et al., **ICLR 2024** (per arXiv comments), 2024. https://arxiv.org/abs/2310.06770 — de-facto outcome-verification benchmark (2,294 real GitHub issues). quality: peer-reviewed.
- [43] **ReqToCode: Embedding Requirements Traceability as a Structural Property of the Codebase.** Schlathölter, 2026-03-14. https://arxiv.org/abs/2603.13999 — compile-time requirement traceability ("Traceables"); **preprint, explicitly not peer-reviewed**; its references cite Wang et al., FSE 2025 Companion on embedding traceability in LLM code generation (secondary — venue not re-verified). quality: preprint.
- [44] **GitHub repository metadata API (stars, created/pushed dates, licenses).** GitHub, queried 2026-10-07. https://api.github.com/repos/… — maturity figures used in the matrix (Hephaestus 3★, opencode 212k★, Claude Code 150k★, Spec Kit 140k★, Codex 128k★, Gemini CLI 107k★, OpenHands 90k★, MetaGPT 71k★, AutoGen 61k★, CrewAI 59k★, aider 49k★, LangGraph 43k★, OpenAI Agents SDK 30k★, SWE-agent 20k★, AG2 5k★). quality: primary data source.

## Open questions / follow-ups

- How does a store-mediated fleet actually behave at scale vs Claude Code agent teams'
  message-based self-coordination? (Needs a spike, not a document.)
- Is there any *peer-reviewed* 2024–2026 work measuring whether staged definition→verification
  pipelines improve agent output quality? None found in this pass — candidate `spike` ticket.
- Jules' "Planning Critic" (vendor changelog claims a 9.5% task-failure reduction) was only
  visible via search index — verify directly before citing.
- Re-run this landscape quarterly: this snapshot is dated 2026-10-07 and the segment moves fast.
