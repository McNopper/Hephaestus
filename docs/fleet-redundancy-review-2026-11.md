# Fleet redundancy review - 2026-11-09 (is the fleet still required?)

- **About this document:** a dated research report (research agent, authoritative sources,
  fetched 2026-11-09) answering the owner's question: "is the fleet feature still required -
  it sounds like Claude Code / opencode do this automatically now?". It compares each fleet
  capability against native opencode v2.2.x and Claude Code 2.1.31x features and issues
  per-ticket verdicts for the fleet roadmap. Reader: the owner and every agent touching the
  fleet, the adoption matrix (`opencode-v2-adoption.md`) or the fleet tickets.
- **Related:** `docs/opencode-v2-adoption.md` (the upstream surface policy this report
  applies), `docs/competitive-landscape.md` (the standing market analysis this extends),
  `AGENTS.md` (the strict-reuse policy the verdicts implement).

## TL;DR

**The fleet's execution primitives are no longer differentiating - both harnesses now ship
them natively; the process layer (ticket store, V-model pairing, budget admission over a
backlog) is still genuinely Hephaestus's own.** Worktree isolation and per-ticket model
selection: REDUNDANT (native in both). Ticket claims, dispatch/role mapping, budget
admission, watchdogs, permission surfacing: PARTIAL primitives - strongest in Claude Code's
agent teams + dynamic workflows, fragmented in opencode. Nothing native replaces the durable
cross-agent coordination blackboard. Confidence: high for opencode and Claude Code (official
changelogs, docs, source); medium-high for the adjacent hosted consoles.

## Capability-by-capability verdicts

| # | Fleet capability | opencode (v2.2.x, Nov 2026) | Claude Code (v2.1.31x, Nov 2026) | Verdict |
|---|---|---|---|---|
| 1 | Git-worktree worker isolation + merge-back/settle | **PARTIAL**: `run -w/--worktree[=<name>]` spawns the session in a fresh worktree (#60712, v2.2.6); server API `GET/POST/DELETE /api/worktree` + `worktree.refresh` and a `worktreeReset` (reset branch to primary default); SDK `worktree.create/refresh/remove` ("the recorded strategy"). **No merge-back verb** - reset discards, remove deletes. | **REDUNDANT**: `claude --worktree <name>` since Feb 2026 with four enforced isolation checks, `EnterWorktree` tool, subagent `isolation: worktree` with auto-remove when a subagent finishes without changes, retention sweep + lock management; `/batch` splits one change into 5-30 worktree-isolated subagents that open PRs. | opencode PARTIAL (lifecycle gap: merge-back); Claude Code REDUNDANT |
| 2 | Ticket store with atomic claims | **STILL REQUIRED**: sessions, per-session todos, inbox, `session/stats` cost aggregates - no cross-agent store, no claim semantics. | **PARTIAL**: agent teams (experimental, opt-in) ship a shared task list with pending/in-progress/completed, dependency unblocking, **teammate self-claim with file locking**, TaskCreate/Get/List/Update tools + veto hooks - but one team per session, config deleted at session end, local directory, no backlog/sprint/artifact contract, no multi-project coordination. | The atomic-claim *mechanism* is native (Claude Code, experimental); the durable cross-session, cross-host **blackboard with artifact hand-off** remains Hephaestus's |
| 3 | Role/stage dispatch + upstream-definition injection | **PARTIAL**: custom agents (permissions, model, mode), `run --agent`; schedules fire prompts on time/condition triggers under serve with fire-once coordination; session instruction entries (#56550) inject durable keyed context. No stage pipeline, no epic-chain injection. | **PARTIAL**: subagent definitions double as role definitions; dynamic workflows codify multi-stage orchestration as rerunnable JS (`agent()`, `pipeline()`, `parallel()`, `phase()`). No V-model, no definition-to-verification pairing, no governing-upstream enforcement. | Mechanisms native; the **pipeline semantics** (stage-to-role, upstream artifact injection, send-back routing) remain Hephaestus's |
| 4 | Concurrency + cost-budget admission (auto-dispatch, waves) | **PARTIAL**: `/goal` runs a session autonomously until a reviewer confirms; `experimental.usage.limits` + Console budgets refused server-side with typed errors (#58694). No admission controller. | **PARTIAL (strongest native governance)**: dynamic workflows cap concurrency (16 default, env-tunable to 256), 1000 agents/run, size guidelines, large-workflow warning at >25 agents or >1.5M projected tokens, usage-limit pause-and-resume, per-stage model selection; `--max-budget-usd` per run; org/workspace spend limits. Still no prioritized-backlog planner with budget admission. | Per-run and org caps native; **budget-gated admission over a persisted backlog** remains Hephaestus's pump |
| 5 | No-progress watchdog | **STILL REQUIRED** (dispatched sessions): `opencode run` background-job waits are explicitly unbounded; SIGTERM cancels, exit 143. No stall detection for sessions. | **PARTIAL/REDUNDANT inside workflows**: stall detection + auto-restart (max 5 attempts, per-agent `stallMs`), streaming-idle watchdog, pause on usage limits. Outside workflows: notifications only. | opencode STILL REQUIRED; Claude Code PARTIAL (workflows only) |
| 6 | Permission-ask surfacing | **PARTIAL**: the reply protocol is native (ask rules; once/always/reject; saved approvals become durable allow rules). No cross-session aggregation surface. | **PARTIAL**: teammate prompts bubble to the lead; agent view "Needs input" grouping; Desktop approval cards; headless `canUseTool`/`--permission-prompt-tool`; inter-agent messages are untrusted (an agent cannot approve on your behalf). | Answer protocol native; **fleet-wide aggregation** remains Hephaestus's thin layer |
| 7 | Per-ticket / per-stage model override | **REDUNDANT**: `run --model/--variant`, per-agent `model`, `session.switchModel`. | **REDUNDANT**: subagent frontmatter `model:`, precedence chain, per-stage model naming, `--model`, org model allowlists. | REDUNDANT both |
| 8 | Fleet control verbs over MCP/chat | **PARTIAL**: the v2 HTTP/SDK is the intended embedder surface (sessions, background job list #60698, `sessionBackground` promote, worktree CRUD, schedules, stats). No dispatch-to-ticket, no activity probes, no shutdown orchestration. | **PARTIAL**: Agent SDK is Anthropic's explicit "build your own harness" surface; headless `-p`; agent view monitors. No local fleet verbs. | opencode explicitly invites embedders to compose on the API - which is what the fleet is |

**Adjacent (commoditization check):** hosted parallel-agent consoles are commodity. Cursor
Cloud Agents: unlimited parallel VM-isolated agents, REST API (launch/steer/lifecycle),
Automations (cron/PR/Slack/Linear triggers), self-hosted pools. Codex Cloud: per-task VMs,
best-of-N, and the Agents API (Sept 2026) productizing the managed harness. Anthropic goes
furthest: **Claude Managed Agents** (April 2026, public beta) productizes the fleet's
infrastructure concerns - managed harness, durable sessions, vaulted credentials, scoped
permissions, scheduled deployments, multi-agent coordination (research preview) at
$0.08/session-hour plus tokens. Direction across all vendors: isolation, parallelism,
orchestration and monitoring converge on platform features; governance/policy and process
definition stay with the buyer.

## What remains genuinely Hephaestus's own (minimal core)

1. **The task store as the single coordination blackboard** - durable tickets, atomic
   claims, artifacts-on-ticket, history invariants, cross-agent and cross-host.
2. **The V-model pipeline semantics** - stage-to-role mapping, paired
   definition-to-verification criteria, send-back/horizontal routing.
3. **The admission pump** - readiness, concurrency + hard-cost-budget caps over a
   prioritized backlog, NEEDS-HUMAN escalation.
4. **The Eclipse host integration** - a second, independent host the embedder API invites.

Everything below that layer should now be adopted, not owned.

## Ticket verdicts (2026-11-09)

| Ticket | Verdict | How |
|---|---|---|
| U-090 greenfield fleet project | KEEP, RESCOPE the mechanics | Keep the flow (store -> admission -> dispatch). Execution on native primitives: worktree spawn/reap via the server worktree APIs or `run -w`; publish via PR. Add an acceptance criterion: zero Hephaestus code creates or deletes a git worktree directly. |
| U-099 retry re-enters the session | CONFIRMS EXISTING SCOPE | The ticket already uses the native steering route (probe-verified U-092); keep the resume-vs-fresh *policy* in the store, no custom session plumbing. |
| U-100 wave lessons | CONFIRMS EXISTING SCOPE | Already re-substrated (owner 2026-10-29) to upstream instruction entries (#56550) with the prompt path as legacy fallback - exactly what the research recommends; no custom mechanism to retire (it never shipped). |
| U-104 stage launch config | CONFIRMS EXISTING SCOPE | Post-re-scope it is the stage-to-agent mapping + governing-definitions prompt injection - the part upstream does not provide. Agent definitions carry the model (D-004: injected locally, never committed). |
| U-037 live auto-dispatch acceptance | KEEP | Validates the layer research shows is genuinely ours (budget-gated admission + NEEDS-HUMAN). Rescope the measurement inputs: pull cost actuals from `usage.limits` + `session/stats` instead of parallel bookkeeping. |
| NEW U-106 | FILED | opencode's server worktree routes (create/remove/refresh/reset) overlap FleetRunner's direct git worktree handling -> adopt-and-remove class per the strict-reuse policy. Merge-back stays ours (no native verb). |

## Standing watch (re-run this research on these events)

- **Claude Code agent teams** graduate from experimental and gain durable cross-session task
  lists - that single change would erode the ticket-store moat for small projects; the moat
  narrows to the V-model pairing alone.
- opencode worktree API grows a merge/PR strategy in `worktree.remove` - then the fleet's
  merge-back also becomes adoptable.
- opencode schedules + `/goal` + `sessionBackground` compose into a supported unattended
  multi-ticket pattern (currently each is opt-in/experimental and none admits on budget).

## References

Primary sources fetched 2026-11-09: opencode changelogs v2.2.1/v2.2.4/v2.2.6
(opencode.ai/changelog), upstream source
(github.com/anomalyco/opencode: `packages/opencode/src/server/routes/instance/httpapi/groups/experimental.ts`,
PRs #60712, #61270, #60698, #60699, #61911, #58694, #56550), opencode docs (permissions,
SDK worktrees, instructions); Claude Code docs (code.claude.com: worktrees, agents,
agent-teams, workflows, costs, github-actions, claude-code-on-the-web, changelog v2.1.319),
Anthropic articles (Managed Agents 2026-04-08, Agent SDK); Cursor docs (cloud-agent, API
endpoints, subagents); OpenAI (Codex Cloud help article, Agents API 2026-09-10).
