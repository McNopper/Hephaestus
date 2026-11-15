# AGENTS.md — Hephaestus opencode workflow

## About this document

- **Kind:** `doc` / repo-level workflow convention (auto-loaded by opencode from the git root).
- **Read by:** any agent operating in this repo; **written by:** maintainers.
- **Related:** pairs with `README.md`; tiers and selection rules live in the `project-manager-orchestrate-execution` skill; concrete models are setup-local (`opencode.json` default + agent frontmatter — none committed).

Repository-level conventions for agentic work in this repository. Hephaestus is an
**opencode-native**, **domain-organized** system: skills and agents are flat under
`.opencode/` and named by `<domain>-<descriptor>`; project management is a concrete,
Scrum-like ticket/sprint workflow over the **task store** (`.harness/tasks/`, one
Markdown file per ticket) served as `task_*` MCP tools; C++ and graphics tooling are
first-class agents / MCP tools.

## Two scopes

*(Positioning: this harness is deliberate weight for
COMPLEX projects — board, tickets, docs, V-pipeline are opt-in structure. For a
simple project the plain opencode TUI suffices; anyone loading the Eclipse
plugin has chosen the machinery on purpose. Keep that bar: don't grow structure
the TUI already covers.)*

- **A whole initiative / project** → the **PM agent** (`project-manager`) runs the Scrum workflow
  over tickets in the task store (one subdirectory per project; multiple projects
  coexist). The maintainer acts as the product owner: writes the brief/goal, prioritizes the backlog,
  and resolves the NEEDS-HUMAN escalations - tickets that are blocked
  (status=blocked) with no
  live fleet job retrying them. Acceptance itself is the engine's review pass: a PASS
  verdict moves the ticket to done. Issues bubble up to the PM and only human-worthy
  ones are escalated.
- **A software change inside a project** → a ticket of the right `role` is claimed by a
  worker, which uses the matching `software-*` / `test-software-*` skill, and records
  its artifact back on the ticket.

> **The PM/ticket system is optional.** Every skill and agent can be used **directly** by a
> human (or by another agent) with no ticket, sprint, or task store involved — for
> ad-hoc work, just invoke the skill or `/agents` you need. Use the PM system only when you
> want tracked, multi-agent, sprint-based execution. Likewise, `project-manager-doc-about` (and any
> skill) works standalone, independent of PM.

## The flow and the final check

Hephaestus owns **the V-model flow** — how work passes: definitions move down
(requirements → system → architecture → design → implementation), verification
comes back up, with artifacts, coverage, evidence and DONE as a gate verdict.
The **final check** is a different thing: the per-project quality/architecture
gate (global skills + the project's own gate configuration) invoked at each
stage's acceptance. It is project-specific and never owned by Hephaestus —
so no specialized skills or metrics get committed to this repo; they come
from the user or live in the project itself. Where a new check is wanted, it
belongs in the project's own files, not as a Hephaestus feature.

## Skills are discovered flat by domain

opencode loads every `SKILL.md` under `.opencode/skills/*/`. There are no subfolders by
domain — the **domain is in the name**. Naming convention: `<domain>-<descriptor>`.

| Domain prefix | Meaning | Example skills |
|---|---|---|
| `software-` | Definition (what/how) | `software-requirements`, `software-system`, `software-architecture`, `software-design`, `software-implementation` |
| `test-software-` | Verification of a definition level | `test-software-implementation`, `-design`, `-architecture`, `-system`, `-requirements` |
| `project-manager-` | Project management | `project-manager-operating-model`, `project-manager-orchestrate-execution`, `project-manager-route-request`, `project-manager-audit-traceability`, `project-manager-estimate-costs`, `project-manager-gather-intelligence`, `project-manager-create-ticket`, `project-manager-doc-about` |

Cross-cutting coordination agents are **unprefixed** (`orchestrator`, `manifest-author`,
`executor`, `reviewer`, `rubberduck`, `research`, `project-manager`). Domain agents were
curated out of the repo (2026-10-15): specialized skills and agents come from the user,
not from Hephaestus (quarantine: `C:\Temp\Hephaestus`).

## The ticket / sprint workflow (the PM)

**Paired verification:** when a ticket ENTERS a
definition stage, its paired verification criteria are created and LINKED in
the same motion - requirements -> acceptance criteria, system -> integration
criteria, architecture -> library tests, design -> component tests,
implementation -> unit tests - so that by the time the V reaches the right
leg, the tests already exist and decide done / not-done. The pair is written
at stage ENTRY, not at review time: link the criteria as artifacts on the
ticket (`task_add_artifact`). `task_traceability` pairs with THREE signals
(D-001, implemented as U-070): the **role + epic link** (`tester` verifying a
definition), its **own journey when it walked both legs** (a definition entry
then a `test-*` stage - `advance()` flips the role on entry to the test leg,
so the wandered ticket pairs with itself), and a
**test-shaped artifact** (`*.tests/` path) - `pm` requirements count as
definitions; the row reports `via`/`self_verified` so a genuine pair is
distinguishable from inflation, and a definition with no signal still reports
as an orphan (the `project-manager-audit-traceability` skill drives the
manual artifact-level audit). A definition
stage without its linked verification counterpart is incomplete work.
Live-in-Eclipse verification (console checks, UI passes, panel round trips)
is ACCEPTANCE verification (owner requirement 2026-11-15, ticket T-039): it
pairs to requirements via the `test-requirements` leg - the last stage of
the V - and belongs there, not in ad-hoc blocked/in-review states or manual
follow-up notes; the stage-pipeline skill's division map carries the
recurrence (a fresh, NON-BLOCKING test-requirements acceptance ticket per
successful wave/release).

**How work moves (the reporting lines):** the flow is either **along the
V** or **horizontal for validation** - nothing moves any other way. When a
stage succeeds, it **divides its result and passes it further**: outputs
feed the next stage down/up the V (and the epic chain when a ticket
decomposes). When something **blocks, it goes back to the origin** - the
stage it came from, where a **requirement can clarify it** (a new or
updated requirement upstream answers the implementation question, then the
work flows down again; horizontally, a failed validation returns to the
definition that produced it). The loop is automatable to a degree:
clarification round-trips run between agents up to their limit, and every
outcome is **tracked as effort** - `fleet actuals:` carry cost/tokens and
the ticket's artifacts carry the results - which is exactly why links to
results and artifacts live ON the tickets: the maintainer tracks effort and
evidence without reading code, and escalates (NEEDS-HUMAN) only when the
round-trips are exhausted.

**V-model execution (U-110, linked stage pipeline):** tickets are
**stationary** — born in one stage, never moved. The pipeline is the LINK
GRAPH: completing an upstream ticket divides it into linked downstream
tickets (`epic` = scope container inherited by the whole derivation tree;
`basis` = the derivation edge). `stage:` is immutable ownership set at
creation. Division is performed by the stage's ticker/agent on completion;
the downstream stage's ticker discovers the new tickets via readiness at its
next tick (the tick boundary is the only synchronization point). Design:
`docs/design-stage-pipeline.md`; live acceptance evidence: ticket `U-111`
(AE-6 comment — requirements T-031 → linked system child A-001, engine
verdicts throughout).

**Ticker strategy (resolution-first, U-110):** every stage ticker's tick
resolves BLOCKED ITEMS FIRST — a blocked ticket escalates to its **basis**
(the upstream link: `task_set_blocked` on the basis with the reason; NEEDS-HUMAN
if the basis is blocked or the round-trip repeats) — then processes done
tickets (division: create + link the downstream tickets), and only then kicks
off READY tickets, NEEDS-HUMAN as the last resort after agents had their
attempt. One attempt per ticket per tick (no hot loops), and attempts count
against the spawn cap and the cost budget like any launch. A blocked ticket
stays put once no agent route remains. Design: `docs/design-stage-pipeline.md`
§4; the live procedure: the `stage-pipeline` skill.

**The repo works as one:** skills, agents, docs,
store and engine form a single system - after behavior changes, the
corresponding skills/agents/docs are revisited in the same flow (coherence
passes run as tickets, e.g. U-030). Divergence between what the engine does and
what the skills prescribe is a defect, not cosmetics. Known divergences that are
not fixed yet are listed in `ROADMAP.md` (*Coherence debt*) - where an entry there
contradicts this file, the entry is right.

**Autonomy target:** waves run themselves on a
recurring basis — agents execute, review, accept and advance; the maintainer's
ONLY regular duty is resolving **NEEDS-HUMAN items** — tickets whose blocked
flag is set with no live fleet job retrying them; clearing the blocker
returns them to the pump. Everything else should need zero end-user action.
U-021 (reviewer auto-accept + auto-advance), U-022 (recurring waves +
escalation surface) and U-023 (clarification loop: agents pass back to the
originator agent; round-trip limits escalate to the maintainer) implement this —
the Hephaestus principle: fully autonomous fleets and waves, the maintainer as
last resort.

**The autonomy spectrum (the operating model, owner requirement 2026-11-15):**
in general the harness runs until the **Product Goal** (`ROADMAP.md` Vision)
is reached; a **human review that accepts** cuts a release (the T-041 standing
rule). Every run sits on a spectrum between two extremes: (A) the harness
works for a set time and its result is the final product — maximum autonomy,
the human absent, time-boxed; (B) the human checks very often and gives
constant feedback — maximum human involvement, a tight loop. The owner sets
each run's position at goal intake (U-105) with two dials: the **goal
horizon** (time-boxed vs run-to-Product-Goal) and the **review cadence**
(releases only on human review — T-041; recurring acceptance hardens without
gating — T-039; NEEDS-HUMAN as the escalation threshold). Worked example
(2026-11-14): an unattended ticker-v run drove the wave end-to-end, and the
owner's review and acceptance cut release `v0.2.0`.

**Terminology:** the UI says **wave**, not sprint —
a wave is a named batch of agent work, planned on demand and drained in minutes
by the fleet (no weekly cadence, no time-box). The store field, tool names
(`task_plan_sprint`) and ticket frontmatter keep `sprint` as the schema key for
stability; treat "wave" and "sprint" as synonyms everywhere else.

The **task store** stores **tickets** and **sprints**, scoped per **project** (one
subdirectory of `.harness/tasks/` each, so several independent projects run at once).
**The store is the ground truth — the single coordination blackboard.** The Eclipse Board
is a projection of it, and every agent (a user's chat session, a fleet worker, the
auto-dispatcher) is a *peer* that reads and writes the store; no agent owns or messages
another. Coordination is store-mediated — claim a ticket, record the artifact, move the
state — so several agents and several users can work the same project **in parallel
without blocking each other**: the atomic `task_claim` is the only serialization point,
and it serializes tickets, not people. The store is served as `task_*` MCP tools — by
the Eclipse harness's `eclipse-build` endpoint when Eclipse runs, and by the `tasks`
stdio launcher (`eclipse/tasks-tools.ps1`, configured in `opencode.json`) for TUI-only
sessions (both launchers resolve the built jars from the **main checkout** when invoked
inside a fleet worktree — worktrees carry no `target/` build output); opencode prefixes them with the server name (`tasks_task_*` /
`eclipse-build_task_*`) — the tool/wire names stay `task_*`. States:

```text
product-backlog --plan--> sprint-backlog --claim--> in-progress --verify--> in-review --accept--> done
   (incomplete on sprint close ──────────────────────────────────────────────────────────────┘)
paused = parked for maintenance (U-038): visible, never blocked; resume is a status update
blocked = a STATE (status:blocked + resume_to:str + blocker:str); clearing
  returns to resume_to (default sprint-backlog); done and paused can never be blocked
stage = immutable V-pipeline ownership (U-110): set at creation, never rewritten - the ticket's permanent stage. The pipeline FLOWS by links: completing an upstream ticket divides it into linked downstream tickets (epic inherited, basis = the completing ticket); task_readiness gates the children on their basis (WAIT_UPSTREAM until done, then READY)
```

**Write discipline.** Store writes go through the `task_*` tools (or the engine API),
never through scripts or bulk hand edits: the engine keeps the invariants (a done ticket
is never blocked, every change leaves a history event, every ticket file stays
parseable) that direct writes silently break - `task_doctor` reports unparsable ticket
files. Where the API has no verb (correcting a wrong artifact ref), make the smallest
hand edit, verify it with `task_get` + `task_doctor`, and record a comment on the
ticket. Text files are UTF-8: never round-trip them through Windows PowerShell 5.1
`Get-Content`/`Set-Content` (it re-encodes through the ANSI code page); use the editor
tools or byte-level UTF-8 I/O - the quality gate rejects the resulting mojibake.

**Chunk small.** One ticket = one independently verifiable change (one file lane,
one mechanism, one doc pass). If a ticket would grow past ~3 story points or mixes
concerns, split it — smaller chunks are easier to track, verify, review and re-run.
Todos list the steps *within* one change; they are not a substitute for decomposition.

Readiness machinery (H6 dataflow): `task_readiness` reports, per ticket, whether its
stage is READY / WAIT_UPSTREAM / STALE / BLOCKED / RUNNING / NOT_APPLICABLE (no stage,
or done - with fresh inputs, or having entered the pipeline with no upstream ticket at
all; never re-dispatched). READY means upstream done AND inputs unchanged since the last
run; STALE means an epic-chain upstream was updated after the downstream ran (any write
to the upstream counts). The PM agent can use it to ask "what's
runnable right now"; the Eclipse Board mirrors it as an opt-in auto-dispatcher
(*Auto ▶*: launches every runnable sprint ticket under a concurrency + cost-budget cap).

- A worker **self-claims** by role: `task_claim(role=…)` atomically finds the next
  matching ticket, moves it to `in-progress`, sets `assignee`. Two agents never get the
  same ticket. A returned ticket (`task_release`) can be picked up by a *different*
  agent.
- When a worker produces an artifact (file, git commit/branch, doc), it records it with
  `task_add_artifact(kind=file|git|path|url|doc, ref=…)` **before** moving to `in-review` —
  the ticket is the hand-off contract.
- A review FAIL blocks the ticket **in its own stage** (`task_set_blocked`
  with the reviewer's reasons — the maintainer-escalation signal) and, under
  U-110, the blockage escalates to the ticket's **basis** (the upstream link):
  the upstream stage's ticker re-opens or reworks the basis. An UNCLEAR
  verdict round-trips the doubt to the originator (one retry per
  stage visit); an unstaged ticket stays in `in-review` with the doubt as a comment.
- See `project-manager-operating-model` for Scrum events, the Definition of Done, and the
  bubble-up → escalation rule; `project-manager-create-ticket` for how to fill a ticket; `project-manager-route-request`
  when the next step is ambiguous; `project-manager-audit-traceability` for the definition→verification matrix.

## Canonical composition hierarchy & the V pipeline

The dividing line is **reuse scope** (static-vs-shared linkage is a build decision):

- **Unit** → smallest implementation element with a clear interface.
- **Component** → composed of units; **internal** to this software (linked in).
- **Library** → composed of components; **independently deployable and reusable outside
  this software**; exposes a clear interface and dependency rules.
- **Software system** → composed of libraries plus external/system interfaces.
- **Package/folder** (and language *modules*) → organization only; not a lifecycle level.

Verification maps by level: `test-software-implementation` (unit) ↔ `software-implementation`,
`test-software-design` (component) ↔ `software-design`, `test-software-architecture` (library)
↔ `software-architecture`, `test-software-system` (integration) ↔ `software-system`,
`test-software-requirements` (acceptance) ↔ `software-requirements`.

The V-model is used as an **async pipeline**, not a phase gate: stages run **concurrently**,
each finished stage's ticket feeds the next stage's backlog, and a stage that cannot proceed
sends the ticket back with a reason. There are **no phase gates and no ordering enforcement**
— the ticket's `stage` field (nullable; `VStages` in the tasks bundle is canonical) only
steers dispatch (stage → role → skill) and the prompt the fleet gives the worker:

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

**V-enforcement — the process the harness forces (U-078..U-082, done-time under U-110):**

- **Every definition stage CREATES a persistent artifact** (the stage's document, recorded
  with `task_add_artifact`); the store's **done-time guard** refuses a definition-stage
  `done` without one (a rationale recorded as a doc artifact counts) and `task_doctor`
  lints legacy done/in-review definition tickets that carry none ("the architecture was
  not done") — the API cannot reach bare `done` for a staged definition ticket.
- **Top-down coverage is automatic:** the ticker's dispatch prompt injects the governing
  UPSTREAM DEFINITIONS — the ticket's basis chain + artifact refs (U-110: the chain
  carries content, not just IDs) — a `done` upstream with no artifact yields a GOVERNING
  DEFINITION MISSING block, and non-bug work with nothing above it at all is forced to
  write the missing abstraction first. The right leg's artifacts are persistent truth;
  they only validate they are *there* and *fulfilled*.
- **DONE is a gate verdict, not an agent's claim:** artifact · coverage (upstream ids
  cited) · every acceptance criterion verified · quality gate ran — "it works" is proven
  on the paired `test-*` stage, and readiness refuses a basis that claims done with
  no artifact (backfill unblocks).
- **Routing — two triggers only:** a FAILED VALIDATION blocks the ticket **in its own
  stage** (`task_set_blocked` with the reason) and escalates to its **basis** — the
  upstream link reworks; a MISSING or WRONG upstream definition escalates the same way
  (`clarification:` first for questions, max 3 round-trips). Cover in place otherwise —
  never flow back by reflex.

## C++ and graphics tooling lives outside the repo

The 2026-10-15 curation moved the specialized lanes out (quarantine at
`C:\Temp\Hephaestus`): the `cpp-tools` methodology skill and agent, the
`graphics-render-comparison` skill, the `graphics-expert` agent, and the
`mcp.graphics` server. The `cpp/` C++ project and its own `verify` target
remain in the repo (its lanes are listed in `eclipse/QUALITY.md`); any C++
or graphics methodology for that project comes from the user, not from
Hephaestus.

## Chat-first control plane (H7)

**Chat is the primary interface — everything is triggerable from an opencode chat session;
the Eclipse views (Board buttons, dialogs) are optional conveniences on top.**

**Chat-first ≠ chat-rooted:** once work is dispatched, the chat session is a *kickoff
assistant and peer*, not the root of a command tree. Workers report to their **tickets**
(status, artifacts, history events) — never back into the kickoff session. To follow
work: read the store (`tasks_task_get`, `task_readiness`); do not address worker
sessions directly. This store-mediated coordination is exactly what lets one user run
several agents in parallel on a project without anyone blocking anyone.

**Intake discipline (owner requirement 2026-11-14, ticket T-036):** what the owner
types is a **requirement or a bug** — it enters the harness as a registered ticket
(store -> tickers -> workers -> engine verdicts), never as direct implementation in
chat. The chat agent **registers** (tickets with distilled acceptance criteria),
**governs** (records, epics, escalation) and **verifies** (evidence from the store);
**workers implement** — direct implementation by the chat agent is a **process
violation**. Build belongs to the machine; the rule binds the machine and its agents,
not the PO (the owner can override). Enforcement is **by role** where possible: the
`product-owner` agent (`.opencode/agent/product-owner.md`) denies
write/edit/patch/bash and owns registration (evaluation:
`docs/intake-agent-evaluation.md`).

Concretely, the chat agent — the PO's **kickoff assistant** — can:

- run the whole ticket/sprint workflow via the `tasks` server (`tasks_task_*`);
- **arm and disarm the stage tickers** via `.opencode/skills/stage-pipeline/ticker-setup.ps1`
  (one steward session + one `experimental.schedules` schedule per stage — W1,
  adopted; the tick prompt is thin glue over the `stage-pipeline` skill), or flip
  the Board's schedules switch (chunk 5: re-labeled Auto/Tickers);
- **file intake tickets** (goal intake, U-105 flow): the PO posts
  **requirements and bugs** in chat - requirements become GOAL/requirements
  tickets (process rules land in the governing docs), bugs become
  `type: bug` tickets; on completion the stage tickers divide it into the
  linked derivation chain;
- **model selection is a cost lever on the ticket**: the optional `model` field
  (`provider/model[#variant]`, via `task_create`/`task_update`) decides what a run
  costs — small well-specified tickets deserve cheap models;
- **escalation surface**: tickers and workers surface NEEDS-HUMAN via
  `task_set_blocked`; clearing it resumes the flow. For unattended runs, prefer
  making risky actions `deny` in the opencode permission config instead of `ask` —
  an unanswered ask holds the run until answered.

The tickers themselves run **unattended**: schedules fire the tick prompt, the
`stage-pipeline` skill drives the fixed procedure, and the machinery keeps running
when the chat closes — the maintainer's only regular duty is resolving
NEEDS-HUMAN escalations. Live acceptance evidence: ticket `U-111` (AE-6 —
requirements T-031 → division → linked system child A-001, engine verdicts
throughout).

## eclipse/ — the IDE harness

The Eclipse plugin (`eclipse/`) is the **agentic harness**: chat plus Server (agents/sessions/MCP
servers/skills)/Providers/Board
views, the MCP build-tools endpoint `eclipse-build`, and the tasks Board (stage
columns over the linked ticket graph). It is a Maven/Tycho reactor — **Maven plans, CMake builds**.

- **v2 in this client (last live cross-check: v2.3.0, 2026-11-13; see `docs/opencode-v2-adoption.md`).** The client speaks the v2 API: `/api` paths with
  Basic auth, `{data:[…]}` envelopes, async `POST /session/:id/prompt` + reply polling, the
  single `/api/event` stream (v2 event names; `location.directory` scopes client-side). The
  interactive connection **attaches to the shared background service** by default
  (`OpencodeServiceDiscovery`; preference-controlled, spawn as fallback). Two v2 realities to
  keep in mind when touching this code: **session state is global per user** (the Server view
  scopes via `?directory=`), **event streams are per-process** — which is why the fleet
  deliberately keeps spawning its own `opencode serve`. TUI steering is gone in v2 and text/symbol search has no v2
  equivalent; session share is NOT implemented for v2 sessions (the v2.0.19 TUI's
  own /share toasts "not implemented"; the share config field is v1 legacy) - the
  chat shows the same honest notice.
- **Build:** `cd eclipse; .\build.ps1 clean verify` (Java 21 + Tycho; Node for the chat-web checks).
  Fast inner loop: `.\build.ps1 verify` (incremental — seconds, vs minutes for `clean`);
  reserve `clean verify` for the release gate / pre-deploy. `-DskipNodeChecks=true`
  skips the chat-web checks, opt-in `-T 2` trades a little CPU for wall clock (off by
  default — keep the system responsive), and each test bundle boots its own OSGi test
  runtime (Tycho) — that startup dominates short builds.
  **Quality gate** (`eclipse/QUALITY.md`): every `verify` runs Checkstyle
  (clean-code lint, enforced) and, once per reactor, the clean-architecture exec check
  (dependency direction + no fixed paths + no mojibake, enforced); `verify -Pquality` adds
  SpotBugs (report-only) and PMD-CPD duplication checks — the release gate is
  `.\build.ps1 clean verify -Pquality`.
- **Deploy:** `.\deploy-dev.ps1` (`ECLIPSE_HOME` / `-EclipseRoot`, default `C:\eclipse-cpp`; close Eclipse first — the bundle jars are locked while it runs).
- **Docs:** `eclipse/README.md`, `eclipse/ARCHITECTURE.md`, and the root `ROADMAP.md`.

## Model tiers (model-neutral agents)

Agents and docs reference **tiers**, never hard-coded model IDs. No default
model and no per-agent model override are committed (decision D-004:
contributors use different providers) - each setup
configures its own `opencode.json` default and, where needed, an agent
frontmatter `model:`; resolve the concrete model through `/models`. The Eclipse chat's selector combos
commit deliberately: un-armed drift (wheel/pointer traffic over the selector
row) reverts; only an opened-dropdown pick or Enter changes the model. Tiers and their selection rules are
defined in `project-manager-orchestrate-execution`.

| Tier | Selection rule |
|---|---|
| `very-low` | cheapest/fastest for trivial, mechanical edits |
| `low` | best available open-weight model — **default executor** |
| `mid` | balanced general model for standard impl/tests |
| `high` | top-capability reasoning + large context — **planning + review** |
| `very-high` | frontier/highest-risk — **run twice & reconcile** |

Tier-selection rule: pick the **lowest tier whose criteria still satisfy the task**;
escalate (never de-escalate) when uncertain.

**Provider/model choices are LOCAL settings, never committed (D-004, owner
directive 2026-10-31):** which provider or model a user selects lives only in
local configuration — the Eclipse preference store and the user-global
`opencode.json`, both outside the repository. No committed content — docs,
code, examples, test fixtures, the ticket store — names a concrete provider or
model id; use `<provider>/<model>` placeholders. The one exception is the
generic provider-catalog support itself (ProviderLogos / fetch-logos and their
public slug mapping, THIRD-PARTY.md attribution), which any user of any
provider needs and carries no information about this setup. Enforcement:
CI runs `releng\check-no-provider-ids.ps1` on every push, and
`releng\init-local-settings.ps1` generates the local settings skeleton
on a fresh machine (the file lives in the user profile, outside the repo).

## Custom agents (`.opencode/agent/`)

Lean, flat, model-neutral:

- **Coordination (unprefixed):** `orchestrator` (kicks off the sprint; workers self-claim),
  `manifest-author` (high-tier plan + execution manifest), `executor` (open-tier task execution;
  records artifacts), `reviewer` (high-tier final review; edit-denied), `rubberduck`
  (cross-vendor critic; edit-denied), `research` (authoritative-source investigation;
  validated synthesis), `project-manager` (Scrum Master + PO proxy; always present).

## Debugging discipline

- **Evidence before theory:** one decisive probe (grep the logs, dump the
  state) before the first hypothesis.
- **Fix the failure class, not the instance.**
- **No hacks - always the proper solution.** Fix at the layer that owns the
  problem; never paper over a wrong model with a special case, a derived
  mirror or a transitional band-aid. If the domain says X (e.g. "blocked is
  a state"), implement X — everywhere, in one motion
  ("No hacks! Put this somewhere! Always proper solutions.").
- **No source edits while a gate runs**; validate an edited script by running it.
- **Every engine outcome that is not success is logged where it happens.**
- **Tests assert contracts, not implementation details** (ordering or
  "last-ness" is an implementation detail unless the contract says so).
- **Status claims cite tool output, never intent.**

## opencode feature usage (recommended)

- Plan mode (`Tab`) for multi-file / multi-phase changes before implementation.
- `/agents` to select a coordination or domain agent.
- `/models` to pick a model for a task (tiers resolve here). Connect whichever
  providers you use via `/connect`.
- The `orchestrator` dispatches concurrent subagents (Task tool) for parallel tickets;
  workers self-claim the rest via `task_claim`.
- Skills auto-load from `.opencode/skills/`; reference files with `@`.
- Run the project's verification gate (e.g. `cpp/` `verify` target) via bash before merge.
