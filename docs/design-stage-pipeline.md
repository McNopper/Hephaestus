# Design: the async stage pipeline (U-110)

- **About this document:** the design for U-110 — stationary tickets, a linked
  stage pipeline, per-stage schedule tickers spawning goal-driven subagents,
  and the retirement of the fleet and the wandering-V machinery. It is the
  governing definition for every implementation chunk of U-110; chunks cite
  sections of this file. **Status: draft for implementation** — the model
  decisions are owner-directed (2026-11-13/14 conversation, recorded on the
  ticket); the sections marked OPEN get their final values during the chunks.
- **Read by:** every agent implementing a U-110 chunk; the maintainer.
- **Related:** `docs/opencode-v2-adoption.md` (the experimental-surface policy
  this design applies — W1 and W12 are adopted by it), `docs/fleet-redundancy-review-2026-11.md`
  (the research that showed the execution primitives are native), ticket
  `.opencode/tasks/hephaestus/U-110.md` (the full decision trail).

## Scope decision (owner, 2026-11-14)

**The harness keeps: the task store and the pipeline designed here.** Tasks
(tickets, epic links, readiness, claims, sprints) plus the stage tickers,
goal-driven subagents, the artifacts store, and the engine-owned verdict.
Everything else enters adopt-and-remove: the FleetRunner and its dispatch /
merge-back / watchdog / admission machinery, the `fleet_*` MCP tools, the
wandering-V verbs, and RecurringWaves. The D-005 minimal core shrinks to:
**store + engine verdict + subagent spawn cap + Eclipse host.**

## 1. The model

```text
epic (basis links)          schedules (W1)           subagents (2.3.0)
     │                            │                        │
[stationary tickets] ──► per-stage tickers ──► spawn cap ──► goal-driven workers
     │                    (= sprint kickoffs)                ("finish this ticket")
     │                            │                        │
     └── artifacts store (W12) ◄──┴── readiness/claims ────┘
                    │
        engine verdict + gate + commit   ← never the agent's self-assessment
```

### Roles and reporting lines

| Role | Who | Does | Never does |
|---|---|---|---|
| **Product Owner** | the user | states intent and goals, prioritizes, decides scope, resolves NEEDS-HUMAN escalations | works tickets |
| **Assistant (main chat agent)** | the chat session | the PO's proxy: turns statements into tickets/goals (intake), plans and stages them, **kicks off and steers the machinery** (ticker lifecycle, spawn cap, waves), surfaces state, files findings | works tickets; self-accepts; runs unattended (it exists only while the PO talks to it) |
| **Stage tickers** | one steward session per stage (schedules) | run micro-sprints over their stage's backlog: kickoff, spawn workers, divide + link downstream, close | self-accept; exceed the spawn cap; touch other stages' tickets |
| **Workers (subagents)** | spawned per ticket | finish exactly their ticket goal-driven, produce artifacts, set `in-review` | accept; wander to other stages |
| **Review pass + quality gate** | the engine | the only `done` authority | — |

The assistant is a **kickoff assistant, not a controller**: the machinery runs
autonomously (tickers on schedules, subagents on goals) and keeps running when
the chat is closed; the assistant's job is to translate PO intent into the
store, start the loop, and be the PO's window into it. This is the chat-first
control plane (H7) with the orchestrator role absorbed into the assistant.

1. **Tickets are stationary.** A ticket is born in one stage and never moves:
   the `stage:` field is *ownership*, not a cursor. Its lifecycle is the
   ordinary status walk within its stage (`product-backlog → sprint-backlog →
   in-progress → in-review → done`).
2. **The pipeline is the link graph.** Completing an upstream ticket *divides*:
   the stage agent creates the derived ticket(s) for the next stage and links
   them (`epic` = the basis ticket). One basis fans out to several children
   (ASPICE work-product derivation).
3. **Per-stage tickers** (upstream `experimental.schedules`, W1 — adopted): one
   steward session per stage. Each schedule trigger is a **sprint kickoff**: a
   micro-sprint over the stage's READY backlog.
4. **Goal-driven subagents** (native default since 2.3.0): the ticker spawns a
   background subagent per claimed ticket with exactly one objective — *finish
   this ticket*. Admission is one knob: the **subagent spawn cap**.
5. **The verdict is engine-owned.** Completion moves a ticket to `in-review`;
   only the engine's review pass and the quality gate make it `done`. Neither
   the worker nor the ticker grades itself.

## 2. Ticket contract

- **Immutable ownership:** `stage:` is set at creation and never rewritten.
  The ten canonical stages stay as-is (`VStages.STAGES`); `null` stays legal
  for non-pipeline tickets.
- **Status walk within the stage** — unchanged store semantics (the status
  machine, claims, blocked states, plan/claim/review all keep working as-is).
- **Done-time enforcement (migrated from advance-time):** a definition-stage
  ticket (`requirements`..`implementation`) cannot reach `done` without ≥1
  recorded artifact (the stage's product). Enforcement point: the store's
  `done` transition (today the guard lives in `advance` — it moves). A
  rationale recorded as a doc artifact satisfies it, unchanged.
- **Division:** `done` of an upstream ticket is the *trigger* for creating the
  downstream ticket(s). Creation is performed by the stage agent as part of
  finishing (the "division"), or by the downstream stage's ticker at kickoff
  when the division map is mechanical (design → implementation). Both paths
  produce tickets with `epic = <basis id>`; both record the same artifact
  references so the chain carries content, not just IDs.

## 3. The link contract (research-informed: scope and derivation are SEPARATE relations)

The decomposition research (2026-11-14, `docs/research-epic-decomposition-2026-11.md` —
Scrum Guide, SAFe, Jira, GitLab, GitHub) is decisive: every framework keeps
**two orthogonal axes apart** — *scope containers* (epic → children, child
links to parent) and *time containers* (sprints/milestones batch items for
delivery; milestones are zero-duration checkpoints or dated batches, never
scope containers, never a decomposition layer). No framework uses the epic
field as a derivation basis — hierarchy and derivation are separate relations
everywhere (Jira: `parent` ≠ issue links; GitLab: parent epic ≠ linked epics;
GitHub: sub-issue parent ≠ cross-references).

Therefore:

- **`epic` = the scope container.** A division creates the downstream tickets
  with the SAME epic as the completing ticket — one epic groups its whole
  derivation tree (the SAFe view: epic contains its features). Epic done =
  rollup of epic-children done (GitHub/GitLab pattern); no separate epic DoD.
- **NEW `basis` field = the derivation edge** (directional, child → the
  upstream ticket whose product this ticket derives from). This is what
  readiness walks for upstream/downstream and STALE — the star-shaped epic
  graph cannot carry direction, the basis chain can. ASPICE traceability:
  basis = trace-to-basis; derivations = every ticket whose basis points here.
- **Paired verification at division:** division creates BOTH the next
  definition ticket AND its paired `test-*` ticket — same epic (scope),
  basis = the ticket they verify (order). Readiness gates the test ticket on
  its basis reaching `done`.
- **Consistency is always-on:** readiness STALE (basis-upstream's
  `inputs_changed_at` stamp changed after the downstream ran — U-052's field)
  is the ASPICE consistency check. **Dependency: U-052 lands before the
  tickers go live.**
- **Checkpoints are not entities:** a "milestone" is either a wave with an
  optional target date (owner decision, later if needed) or the completion
  gate of a key ticket (the stage-gate verdict) — per Atlassian: "your
  project milestone could be the completion of a certain task, but it's not
  the task itself." No new construct.
- **Anti-pattern guardrails encoded:** no evergreen wandering epics (an epic
  with open children across many waves is a smell — Atlassian puts
  long-lived programs in *initiatives above* epics); waves without goals are
  a smell (the sprint goal is the commitment); milestones never become scope
  containers.
- Grouping umbrellas coexist unchanged: `E-*` program umbrellas keep working
  as plain epic grouping; `task_traceability` reports both relations (epic =
  scope rollup, basis = order and traceability).

## 4. Micro-sprints and the ticker cadence

- **The schedule trigger is a sprint kickoff.** Each tick runs the existing
  wave lifecycle per stage: kickoff → work → close.
  1. *Kickoff:* readiness scan of the stage's backlog; `task_plan_sprint` pulls
     the READY tickets under the admission cap; the sprint `goal:` is set
     (e.g. "process READY design tickets T-103, T-104").
  2. *Work:* spawn one goal-driven subagent per claimed ticket (Section 5).
  3. *Close:* `task_close_sprint` — unfinished tickets return to the stage
     backlog (existing semantics); the wave digest is recorded (U-061);
     completed work's downstream links grow the next stage's READY set.
- **Rolling sprint record per stage:** each ticker reuses one named record
  (`ticker-<stage>`, e.g. `ticker-design`), reopened at kickoff (U-108's
  reopen semantics) and closed at the tick boundary. This keeps the sprint
  namespace compact (no per-tick id explosion) while history, goal and digest
  stay per-tick. The auto-minted `S-xx` records remain for human-run waves.

### The tick algorithm (always the same, every launch)

The ticker has no decisions to make — the procedure is fixed, parameterized
only by its stage. Escalation-before-launch is deliberate (resolution-first,
U-050):

```text
TICK(stage):
  1. SCAN     my stage's tickets via readiness (this is the whole observation step)
  2. BLOCKED  → ESCALATE BACK: a blocked ticket goes back to its basis (the
               upstream link) as a rework request; if the basis is itself
               blocked, or the round-trip repeats, escalate to the PO
               (NEEDS-HUMAN). Agents resolve what agents can resolve;
               the rest surfaces to the Product Owner.
  3. DONE     → PROCESS FORWARD: division — create + link the downstream
               ticket(s) per the division map, carrying the artifact
               references; the next stage's READY set grows.
  4. READY    → KICKOFF: claim under the spawn cap, spawn one goal-driven
               subagent per ticket ("finish this ticket").
  5. CLOSE    the micro-sprint record; record the digest; next tick repeats.
```

The same loop runs whether the backlog is empty (steps 1–2 find nothing,
step 5 closes — one thin readiness query) or full. Determinism is the point:
every stage agent executes the identical process; only its stage's data
differs.

### Abstract procedure, concrete stage

The tick algorithm and the fulfillment procedure are **abstract and
identical across all ten stages**. What is concrete per stage is only data:

| Concrete per stage | Example (design) |
|---|---|
| the **division map** — which children it spawns, for which stage | a done design ticket spawns implementation tickets (`basis` = the design ticket, `epic` inherited, stage = implementation) |
| the **discipline skill** | `software-design` / for workers `software-implementation` |
| the **artifacts** | the design document(s) published to the artifacts store |

**Stages are decoupled through the store — never a direct handoff.** Example,
end to end: the design ticker's worker finishes design ticket X → division
creates the implementation tickets (`basis` = X) → the design ticker's work
is done. The implementation ticker knows nothing of this until its **next
schedule tick**, when readiness marks the new tickets READY (basis done) and
its kickoff claims them and spawns the implementation workers. No stage
calls another; the store is the only channel, and the tick boundary is the
only synchronization point. This is what makes the procedure abstract:
adding a stage = adding its division map + skill, nothing else.

## 5. Fulfillment: goal-driven subagents

- **One subagent per ticket**, spawned by the ticker, background (native 2.3.0
  semantics: returns immediately, reports on idle; #63675 prevents partial
  acceptance). The subagent's goal is exactly: *finish ticket X — produce the
  stage product, record the artifact, set the ticket `in-review`*. U-105's
  governed-goal semantics: the session never self-accepts.
- **Workers are narrow: the brief, not the big picture** (owner, 2026-11-14).
  A subagent's world is exactly its ticket: the ticket content, the **direct
  basis artifacts** (the upstream stage product it derives from — e.g. the
  design document for an implementation worker), its stage skill + the common
  process skill, and its lane constraints. No epic tree, no initiative
  context, no other tickets, no store-wide visibility. Transitively needed
  content lives IN the basis artifact (ASPICE consistency: the design doc
  incorporates the requirements it implements). The ticker composes this brief
  into the spawn prompt — the worker needs write access only for its own
  ticket (status flips, artifact recording). Consequences: small prompts and
  cheap workers; a worker cannot wander (it never learns other tickets exist);
  the ticker holds the big picture, the worker holds the ticket.
- **Admission = the spawn cap.** Global cap (⧗ OPEN — default proposal **4**)
  plus per-stage serialization: the ticker never spawns two subagents whose
  file lanes overlap; when lanes overlap it serializes within the stage.
  File-lane discipline is a store convention (labels/notes on the ticket),
  not git plumbing — the E-006 rule ("one ticket = one exclusive lane") made
  operational.
- **No isolation — accepted risk (owner, experimental harness):** subagents
  edit the shared tree; recovery from a broken run is git revert, not branch
  discard. The central gate + commit close the loop exactly as the chat
  session does today.
- **Watchdog shrinks:** background idle reporting (#63382/#63675) plus
  `experimental.job.list` probing (W13 — first consumer) for subagents that
  never go idle. Stalled spawns are aborted and their tickets returned to the
  stage backlog with a blocker note.
- **Cost observability:** `usage.limits` + `session.generate` tokens/cost feed
  the cap decisions; no parallel bookkeeping.

## 6. Stage skills: one common process skill, parameterized by stage

- **One common skill — `stage-pipeline` (new)** — encodes the shared procedure
  every stage agent executes: readiness query → claim under the cap →
  goal-driven fulfillment loop → artifact recording → division + downstream
  linking → `in-review` handoff → micro-sprint close. The tickers' tick prompt
  is thin glue over this skill; the workers' goal prompt references it. One
  process, ten parameterizations — not ten divergent copies.
- **The per-stage discipline knowledge already exists** and stays: the stage's
  skill family (`software-requirements`, `software-system`,
  `software-architecture`, `software-design`, `software-implementation`,
  `test-software-*` — the `VStages.SKILL` mapping). A worker loads
  **common + stage** (process + discipline); a ticker loads **common** only
  (it executes the process, it does not do the discipline work).
- Skills are the carriage of process change: when the pipeline procedure
  evolves, `stage-pipeline` changes once and every stage inherits it
  (AGENTS.md: "the repo works as one").

## 7. Work products: the artifacts store

- Stage products (requirements doc, design doc, …) are **published to the
  upstream artifacts store** (W12; opt-in flag `"experimental": { "artifacts":
  true }` — a local setting per D-004).
- The ticket's artifact list stays the **store-side index**:
  `task_add_artifact(kind=url/doc, ref=<artifact view URL>)`. Board, doctor and
  traceability keep working unchanged.
- Downstream dispatch injects the linked upstream artifacts as **governing
  definitions** — the link graph carries content, not just IDs.
- Fallback: if the flag is off, stage products fall back to repo-path refs as
  today (no code path change — only the ref's shape differs).

## 8. The verdict

- Worker sets `in-review`; the ticker never accepts. **The right leg is the
  review:** the paired `test-*` tickets (Section 3) are the verification
  work; the engine's review pass + the quality gate remain the only `done`
  authority. The central gate (build/tests/lint) runs on the shared tree
  after each landing, unchanged.

## 9. Retirements and removal plan

| Retired | Replacement | Removal timing |
|---|---|---|
| `task_advance` / `task_pass_stage` / `task_report_horizontal` / wandering `send_back` | division + link creation; send-back-as-movement becomes rework (a failed downstream blocks/stales its upstream link) | Chunk 5, after tickers hold parity live |
| RecurringWaves (batch waves) | per-stage ticker kickoffs | Chunk 5 |
| FleetRunner, `fleet_*` MCP tools, fleet bundle + board fleet views | ticker-spawned subagents + the spawn cap | **Last** — only after live parity evidence (the acceptance run, ex-U-037, validates the whole loop) |
| **Board's Fleet on/off toggle** | kept — becomes the **enable/disable switch for the tickers' schedules**: ON = schedules armed (micro-sprint kickoffs run), OFF = dormant. Re-labeled (e.g. Auto/Tickers) in Chunk 5; the same panel gains the owner-adjustable spawn cap and interval fields | Chunk 5 |
| `send_back` as clarification routing (`clarification:` → originator, U-023) | kept — routing, not movement | stays |

## 10. Migration of live state

- Staged tickets keep their `stage:` as **ownership**; no data migration of
  the field itself. Tickets mid-walk (a stage cursor between stages) resolve
  to their current stage as their permanent home.
- Open sprint records (`S-08`.., `ticker-*` for new stages) continue under the
  existing reopen semantics (U-108).
- The Board's V-pipeline projection becomes a **stage-column × link-graph**
  view (columns = stages, tickets stationary, edges = epic links). UI chunk.
- The traceability report keeps its three signals; the "wandered ticket pairs
  with itself" signal disappears with the wandering — pairing is now explicit
  (division-created test tickets).

## 11. Native-first inventory (design rule #1)

Adopted native: `experimental.schedules` (W1) · `experimental.artifacts.*`
(W12) · background subagents (#63382) · `experimental.job.list` (W13) ·
worktree APIs (unchanged adoption target, now optional — only if a future
design needs isolated worktrees) · permission request/reply · `usage.limits` /
`session.stats` / `session.generate` tokens-cost.
Ours (nothing native exists — D-005 core): the store (tickets, epic basis
links, readiness, claims), the spawn-cap admission, the engine verdict, the
Eclipse host surfaces.

## 12. Implementation chunks

| Chunk | Content | Gate |
|---|---|---|
| 1 | This design doc (integration of the decomposition research into §3) | lint |
| 2 | Store: done-time artifact guard (moved from `advance`), division/link helpers, `ticker-*` sprint records | full verify |
| 3 | Ticker substrate: schedules config (flag on), per-stage sessions, **the `stage-pipeline` common skill (§6)**, tick prompt, kickoff/work/close loop | full verify + live ticker evidence |
| 4 | Fulfillment: subagent spawn + cap + lane discipline + goal prompts; U-052 stamp lands first (readiness dependency) | full verify + live evidence |
| 5 | Board stage-columns projection; docs/skills rewrite (AGENTS.md V-section, fleet-quickstart, operating model) | full verify |
| 6 | Retirements: wandering verbs, RecurringWaves, FleetRunner + fleet tools (after live parity: the acceptance run) | full verify -Pquality |
