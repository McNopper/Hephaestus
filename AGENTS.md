# AGENTS.md ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Hephaestus opencode workflow

## About this document
- **Kind:** `doc` / repo-level workflow convention (auto-loaded by opencode from the git root).
- **Read by:** any agent operating in this repo; **written by:** maintainers.
- **Related:** pairs with `README.md`; tiers and selection rules live in the `project-manager-orchestrate-execution` skill, concrete models in `opencode.json` and per-agent overrides.

Repository-level conventions for agentic work in this repository. Hephaestus is an
**opencode-native**, **domain-organized** system: skills and agents are flat under
`.opencode/` and named by `<domain>-<descriptor>`; project management is a concrete,
Scrum-like ticket/sprint workflow over the **task store** (`.opencode/tasks/`, one
Markdown file per ticket) served as `task_*` MCP tools; C++ and graphics tooling are
first-class agents / MCP tools.

## Two scopes

*(Positioning: this harness is deliberate weight for
COMPLEX projects ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â board, tickets, docs, V-pipeline are opt-in structure. For a
simple project the plain opencode TUI suffices; anyone loading the Eclipse
plugin has chosen the machinery on purpose. Keep that bar: don't grow structure
the TUI already covers.)*

- **A whole initiative / project** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ the **PM agent** (`project-manager`) runs the Scrum workflow
  over tickets in the task store (one subdirectory per project; multiple projects
  coexist). The human is Product Owner: writes the brief/goal, prioritizes the backlog,
  accepts at Sprint Review. Issues bubble up to the PM and only human-worthy ones are
  escalated.
- **A software change inside a project** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ a ticket of the right `role` is claimed by a
  worker, which uses the matching `software-*` / `test-software-*` skill, and records
  its artifact back on the ticket.

> **The PM/ticket system is optional.** Every skill and agent can be used **directly** by a
> human (or by another agent) with no ticket, sprint, or task store involved ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â for
> ad-hoc work, just invoke the skill or `/agents` you need. Use the PM system only when you
> want tracked, multi-agent, sprint-based execution. Likewise, `project-manager-doc-about` (and any
> skill) works standalone, independent of PM.

## Skills are discovered flat by domain

opencode loads every `SKILL.md` under `.opencode/skills/*/`. There are no subfolders by
domain ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the **domain is in the name**. Naming convention: `<domain>-<descriptor>`.

| Domain prefix | Meaning | Example skills |
|---|---|---|
| `software-` | Definition (what/how) | `software-requirements`, `software-system`, `software-architecture`, `software-design`, `software-implementation` |
| `test-software-` | Verification of a definition level | `test-software-implementation`, `-design`, `-architecture`, `-system`, `-requirements` |
| `project-manager-` | Project management | `project-manager-operating-model`, `project-manager-orchestrate-execution`, `project-manager-route-request`, `project-manager-audit-traceability`, `project-manager-estimate-costs`, `project-manager-gather-intelligence`, `project-manager-create-ticket`, `project-manager-doc-about` |
| `cpp-` | C++ execution utility | `cpp-tools` (methodology; the `cpp-tools` agent runs the commands) |
| `graphics-` | Graphics utility (thin) | `graphics-render-comparison` (the heavy lifting is the `mcp.graphics` tools) |
| `code-` | Code analysis | `code-dependency` (package/namespace dependency map ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ Mermaid block diagram), `code-licenses` (third-party license audit ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ compatibility table + remediation), `code-repo-map` (probe-don't-read orientation map: layout, build/test entry points, module one-liners) |
| `research-` | Live research utility | `research-artificial-analysis-models` (Artificial Analysis leaderboard ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ filtered, cost-sorted Markdown table) |

Cross-cutting coordination agents are **unprefixed** (`orchestrator`, `manifest-author`,
`executor`, `reviewer`, `rubberduck`, `research`, `project-manager`); domain agents keep their prefix
(`cpp-tools`, `graphics-expert`).

## The ticket / sprint workflow (the PM)

**Paired verification:** when a ticket ENTERS a
definition stage, its paired verification criteria are created and LINKED in
the same motion - requirements -> acceptance criteria, system -> integration
criteria, architecture -> library tests, design -> component tests,
implementation -> unit tests - so that by the time the V reaches the right
leg, the tests already exist and decide done / not-done. The pair is written
at stage ENTRY, not at review time: link the criteria as artifacts on the
ticket (`task_add_artifact`), and `task_traceability` audits exactly these
pairs (the `project-manager-audit-traceability` skill). A definition stage
without its linked verification counterpart is incomplete work.

**V-model execution:** a staged ticket wanders
through ALL ten stages, 1 to 10 ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â but a stage where nothing applies to the
feature (e.g. no architecture-level change needed) is a **pass-through**:
the ticket advances with a recorded rationale ("no architecture impact:
local change") instead of a full dispatch. Every stage is visited; not
every stage does work. (U-029 implements the pass-through semantics.)

**Pump strategy:** every wave tick tries to
RESOLVE BLOCKED ITEMS FIRST ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â route a blocked ticket to the previous stage
(vertical send-back on the definition chain) or report it horizontally
(the verification-definition pair at the same V level) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â and only then
plans new launches. NEEDS-HUMAN is the last resort after agents had their
attempt. (U-031 implements the resolution-first tick order.)

**The repo works as one:** skills, agents, docs,
store and engine form a single system - after behavior changes, the
corresponding skills/agents/docs are revisited in the same flow (the pump
runs coherence passes; U-030). Divergence between what the engine does and
what the skills prescribe is a defect, not cosmetics.

**Autonomy target:** waves run themselves on a
recurring basis ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â agents execute, review, accept and advance; the human's
ONLY regular duty is resolving **blocked items agents could not resolve**
(NEEDS-HUMAN escalation). Everything else should need zero end-user action.
U-021 (reviewer auto-accept + auto-advance), U-022 (recurring waves +
escalation surface) and U-023 (clarification loop: agents pass back to the
originator agent; round-trip limits escalate to the human) implement this ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â
the Hephaestus principle: fully autonomous fleets and waves, the human as
last resort.

**Terminology:** the UI says **wave**, not sprint ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â
a wave is a named batch of agent work, planned on demand and drained in minutes
by the fleet (no weekly cadence, no time-box). The store field, tool names
(`task_plan_sprint`) and ticket frontmatter keep `sprint` as the schema key for
stability; treat "wave" and "sprint" as synonyms everywhere else.

The **task store** stores **tickets** and **sprints**, scoped per **project** (one
subdirectory of `.opencode/tasks/` each, so several independent projects run at once).
**The store is the ground truth ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the single coordination blackboard.** The Eclipse Board
is a projection of it, and every agent (a user's chat session, a fleet worker, the
auto-dispatcher) is a *peer* that reads and writes the store; no agent owns or messages
another. Coordination is store-mediated ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â claim a ticket, record the artifact, move the
state ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â so several agents and several users can work the same project **in parallel
without blocking each other**: the atomic `task_claim` is the only serialization point,
and it serializes tickets, not people. The store is served as `task_*` MCP tools ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â by
the Eclipse harness's `eclipse-build` endpoint when Eclipse runs, and by the `tasks`
stdio launcher (`eclipse/tasks-tools.ps1`, configured in `opencode.json`) for TUI-only
sessions (both launchers resolve the built jars from the **main checkout** when invoked
inside a fleet worktree ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â worktrees carry no `target/` build output); opencode prefixes them with the server name (`tasks_task_*` /
`eclipse-build_task_*`) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the tool/wire names stay `task_*`. States:

```
product-backlog --plan--> sprint-backlog --claim--> in-progress --verify--> in-review --accept--> done
   (incomplete on sprint close ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ¢â€šÂ¬ÃƒÂ¢Ã¢â‚¬ÂÃ‹Å“)
paused = parked for maintenance (U-038): visible, never blocked; resume is a status update
blocked = orthogonal flag (blocked:bool + blocker:str) at any active state
stage = optional V-pipeline field: task_advance -> next stage's backlog; task_send_back -> previous stage (blocked + reason)
```

Readiness machinery (H6 dataflow): `task_readiness` reports, per ticket, whether its
stage is READY / WAIT_UPSTREAM / STALE / BLOCKED / RUNNING / NOT_APPLICABLE (no stage,
or already done with fresh inputs ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â never re-dispatched) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â upstream done AND inputs
unchanged since the last run ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â and the store records `inputs changed:` history markers
when an upstream moves after a downstream ran. The PM agent can use it to ask "what's
runnable right now"; the Eclipse Board mirrors it as an opt-in auto-dispatcher
(*Auto ÃƒÂ¢Ã¢â‚¬â€œÃ‚Â¶*: launches every runnable sprint ticket under a concurrency + cost-budget cap).

- A worker **self-claims** by role: `task_claim(role=ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦)` atomically finds the next
  matching ticket, moves it to `in-progress`, sets `assignee`. Two agents never get the
  same ticket. A returned ticket (`task_release`) can be picked up by a *different*
  agent.
- When a worker produces an artifact (file, git commit/branch, doc), it records it with
  `task_add_artifact(kind=file|git|path|url|doc, ref=ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦)` **before** moving to `in-review` ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â
  the ticket is the hand-off contract.
- Review/verification failure sends the ticket back to `in-progress` (rework loop).
- See `project-manager-operating-model` for Scrum events, the Definition of Done, and the
  bubble-up ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ escalation rule; `project-manager-create-ticket` for how to fill a ticket; `project-manager-route-request`
  when the next step is ambiguous; `project-manager-audit-traceability` for the definitionÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢verification matrix.

## Canonical composition hierarchy & the V pipeline

The dividing line is **reuse scope** (static-vs-shared linkage is a build decision):

- **Unit** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ smallest implementation element with a clear interface.
- **Component** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ composed of units; **internal** to this software (linked in).
- **Library** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ composed of components; **independently deployable and reusable outside
  this software**; exposes a clear interface and dependency rules.
- **Software system** ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ composed of libraries plus external/system interfaces.
- **Package/folder** (and language *modules*) ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ organization only; not a lifecycle level.

Verification maps by level: `test-software-implementation` (unit) ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬Â `software-implementation`,
`test-software-design` (component) ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬Â `software-design`, `test-software-architecture` (library)
ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬Â `software-architecture`, `test-software-system` (integration) ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬Â `software-system`,
`test-software-requirements` (acceptance) ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬Â `software-requirements`.

The V-model is used as an **async pipeline**, not a phase gate: stages run **concurrently**,
each finished stage's ticket feeds the next stage's backlog, and a stage that cannot proceed
sends the ticket back with a reason. There are **no phase gates and no ordering enforcement**
ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the ticket's `stage` field (nullable; `VStages` in the tasks bundle is canonical) only
steers dispatch (stage ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ role ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ skill) and the prompt the fleet gives the worker:

| Stage | Role | Skill |
|---|---|---|
| `requirements` | pm | `software-requirements` |
| `system` | architect | `software-system` |
| `architecture` | architect | `software-architecture` |
| `design` | developer | `software-design` |
| `implementation` | developer | `software-implementation` |
| `test-implementation` | tester | `test-software-implementation` |
| `test-design` | tester | `test-software-design` |
| `test-architecture` | tester | `test-software-architecture` |
| `test-system` | tester | `test-software-system` |
| `test-requirements` | tester | `test-software-requirements` |

## C++ and graphics are tools, not a separate lifecycle

- **C++**: the `cpp-tools` **agent** runs CMake configure/build, clang-format, cppcheck,
  clang-tidy, clang-scan-deps via bash and reads their reports (methodology in the
  `cpp-tools` skill). The old `cpp/mcp` server is gone.
- **Graphics**: window capture, RenderDoc capture, and render comparison are **MCP tools**
  in `mcp.graphics` (`graphics_screenshot`, `graphics_renderdoc_capture`,
  `graphics_renderdoc_frame`, `graphics_compare_renders`). The `graphics-expert` agent
  (pinned to `very-high`) drives them for frontier-level graphics work; `graphics-render-comparison`
  is the thin methodology skill.

## Chat-first control plane (H7)

**Chat is the primary interface ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â everything is triggerable from an opencode chat session;
the Eclipse views (Board buttons, dialogs, launchers) are optional conveniences on top.**

**Chat-first ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â  chat-rooted:** once work is dispatched, the chat session is a *launcher
and peer*, not the root of a command tree. A running worker reports to its **ticket**
(status, artifacts, actuals) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â never back into the dispatching session, which sees only
`fleet_fleet_jobs` state until the work lands in the store. To follow dispatched work:
poll the store (`tasks_task_get`, `fleet_fleet_jobs`), do not wait on or address the
worker session. This store-mediated coordination is exactly what lets one user run
several agents in parallel on a project without anyone blocking anyone.

Concretely, a chat agent can already:

- run the whole ticket/sprint workflow via the `tasks` server (`tasks_task_*`);
- **dispatch the fleet** via the `fleet` server: `fleet_fleet_dispatch` (async launch for
  one ticket ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â worktree isolation, role-mapped agent, merge-back, artifacts/actuals on the
  ticket; settle reaps the merged worktree+branch and a dispatch reclaims stale merged
  residue itself (B-006) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â "branch already exists" now means real unmerged work, never
  engine bookkeeping; the watchdog aborts hung sessions ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the ticket budget is
  progress-aware (B-008): it is a NO-PROGRESS window that progressing sessions
  never hit (busy alone is not progress), an absolute run cap is the backstop, and
  every abort records a
  diagnostic snapshot (last assistant text, last tool call, pending request) on the
  ticket). Model
  selection is a **cost lever on the ticket**: the optional `model` field
  (`provider/model[#variant]`, via `task_create`/`task_update`) decides what a run costs ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â
  small well-specified tickets deserve cheap models; `fleet_dispatch(model=ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦)` overrides
  per run. The reviewer pass stays on the server default.
  `fleet_fleet_jobs` (poll the live job snapshot), `fleet_fleet_job_details` (live
  progress probe: busy/messages/complete ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â "are we moving?"),
  `fleet_fleet_permissions` / `fleet_fleet_permissions_answer` (list and answer
  permission asks of unattended runs ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â once/always/reject),
  `fleet_fleet_sync_store` / `fleet_fleet_status_store` / `fleet_fleet_recover_store`
  (distributed-fleet store discipline, **scoped to `.opencode/tasks`** ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â never touches
  the rest of the repo). The fleet spawns its own authenticated `opencode serve` on
  first dispatch (a fresh password is generated when `OPENCODE_SERVER_PASSWORD` is
  unset) and kills it on shutdown.
- **Host discipline** : background work runs in
  **opencode** (its service already executes requests and shells in the
  background) or in **Eclipse** (the JobManager runs every task as a Job in
  one fixed `JobGroup`) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â anything else is reinventing the wheel. The
  automatic fleet pump (auto-dispatch / recurring waves) lives in Eclipse;
  when Eclipse is closed the fleet does not pump, by design. The former V-006
  detached daemon (TCP core + proxy + pidfile + launcher) is retired.
- **Strict reuse policy** (full matrix in
  `docs/opencode-v2-adoption.md`): a capability comes from the FIRST tier
  that has it ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â **opencode v2** (sessions, shells, PTYs, worktrees, VCS,
  permissions, forms, MCP management: check `GET /openapi.json` first), then
  **Eclipse** (Jobs, Progress, preferences, scheduling rules), and our own
  code only where neither host has the thing (task store, V-pipeline
  steering). Building a parallel mechanism is a defect.
- capture/compare renders via `mcp.graphics`.

The opt-in auto-dispatch loop is chat-triggerable via `fleet_fleet_auto_start`
(explicit project/sprint, concurrency and cost-admission budget),
`fleet_fleet_auto_status`, and `fleet_fleet_auto_stop`. Board and chat share the
headless scheduling policy and repository reservations.

The U-022 **recurring-waves mode** is chat-triggerable via `fleet_fleet_waves_start`
(per project, optional first wave, concurrency + hard-stop cost budget),
`fleet_fleet_waves_status` (active wave, waves planned, stop reason, and the
**NEEDS-HUMAN rows** ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â every ticket blocked with no in-flight retry), and
`fleet_fleet_waves_stop`. When the active wave drains, the next wave is planned
automatically from the prioritized backlog (top-priority READY tickets) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â no human
click between waves. The loop parks while NEEDS-HUMAN tickets wait (clearing a
blocker resumes it automatically; the Board's readiness badge carries the
needs-me count) and stops cleanly on budget exhaustion or when nothing is
plannable. OFF by default; under the fleet daemon it survives client disconnects.

Not yet chat-triggerable: *proactive* ask-surfacing inside
the dispatching chat (answering works via `fleet_fleet_permissions*`) and
board rendering. For unattended runs, prefer making risky
actions `deny` in the opencode permission config instead of `ask` ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â an unanswered ask
holds the run until answered (the watchdog pauses, it does not decide).

## eclipse/ ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the IDE harness

The Eclipse plugin (`eclipse/`) is the **agentic harness**: chat plus Server (agents/sessions/MCP
servers/skills)/Providers/Board
views, the MCP build-tools endpoint `eclipse-build`, a git-worktree agent fleet, and the
headless FleetRunner. It is a Maven/Tycho reactor ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â **Maven plans, CMake builds**.

- **opencode v2 (pin 2.0.10).** The client speaks the v2 API: `/api` paths with
  Basic auth, `{data:[ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦]}` envelopes, async `POST /session/:id/prompt` + reply polling, the
  single `/api/event` stream (v2 event names; `location.directory` scopes client-side). The
  interactive connection **attaches to the shared background service** by default
  (`OpencodeServiceDiscovery`; preference-controlled, spawn as fallback). Two v2 realities to
  keep in mind when touching this code: **session state is global per user** (the Server view
  scopes via `?directory=`), **event streams are per-process** ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â which is why the fleet
  deliberately keeps spawning its own `opencode serve`. Session share and TUI steering are gone
  in v2; text/symbol search has no v2 equivalent.
- **Build:** `cd eclipse; .\build.ps1 clean verify` (Java 21 + Tycho; Node for the chat-web checks).
  Fast inner loop: `.\build.ps1 verify` (incremental ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â seconds, vs minutes for `clean`);
  reserve `clean verify` for the release gate / pre-deploy. `-DskipNodeChecks=true`
  skips the chat-web checks, opt-in `-T 2` trades a little CPU for wall clock (off by
  default ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â keep the system responsive), and each test bundle boots its own OSGi test
  runtime (Tycho) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â that startup dominates short builds.
  **Quality gate** (`eclipse/QUALITY.md`): every `verify` runs Checkstyle
  (clean-code lint, enforced) and the clean-architecture exec check
  (dependency direction + no fixed paths, enforced); `verify -Pquality` adds
  SpotBugs (report-only) and PMD-CPD duplication checks ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the release gate is
  `.\build.ps1 clean verify -Pquality`.
- **Deploy:** `.\deploy-dev.ps1` (`ECLIPSE_HOME` / `-EclipseRoot`, default `C:\eclipse-cpp`; close Eclipse first ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the bundle jars are locked while it runs).
- **Docs:** `eclipse/README.md`, `eclipse/ARCHITECTURE.md`, and the root `ROADMAP.md`.

## Model tiers (model-neutral agents)

Agents and docs reference **tiers**, never hard-coded model IDs. The concrete
model for each tier is configured in `opencode.json` (the default `model`) and
in any per-agent override (only `graphics-expert` overrides, pinning to
`very-high`); resolve through `/models`. The Eclipse chat's selector combos
commit deliberately: un-armed drift (wheel/pointer traffic over the selector
row) reverts; only an opened-dropdown pick or Enter changes the model. Tiers and their selection rules are
defined in `project-manager-orchestrate-execution`.

| Tier | Selection rule |
|---|---|
| `very-low` | cheapest/fastest for trivial, mechanical edits |
| `low` | best available open-weight model ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â **default executor** |
| `mid` | balanced general model for standard impl/tests |
| `high` | top-capability reasoning + large context ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â **planning + review** |
| `very-high` | frontier/highest-risk ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â **run twice & reconcile** |

Tier-selection rule: pick the **lowest tier whose criteria still satisfy the task**;
escalate (never de-escalate) when uncertain.

## Custom agents (`.opencode/agent/`)

Lean, flat, model-neutral (except `graphics-expert`):

- **Coordination (unprefixed):** `orchestrator` (kicks off the sprint; workers self-claim),
  `manifest-author` (high-tier plan + execution manifest), `executor` (open-tier task execution;
  records artifacts), `reviewer` (high-tier final review; edit-denied), `rubberduck`
  (cross-vendor critic; edit-denied), `research` (authoritative-source investigation;
  validated synthesis), `project-manager` (Scrum Master + PO proxy; always present).
- **Domain agents:** `cpp-tools` (C++ execution), `graphics-expert` (very-high; graphics).

## opencode feature usage (recommended)

- Plan mode (`Tab`) for multi-file / multi-phase changes before implementation.
- `/agents` to select a coordination or domain agent.
- `/models` to pick a model for a task (tiers resolve here). Providers commonly
  used: e.g. Z.AI, GitHub Copilot, OpenAI ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â connect whichever you use via `/connect`.
- The `orchestrator` dispatches concurrent subagents (Task tool) for parallel tickets;
  workers self-claim the rest via `task_claim`.
- Skills auto-load from `.opencode/skills/`; reference files with `@`.
- Run the project's verification gate (e.g. `cpp/` `verify` target) via bash before merge.
