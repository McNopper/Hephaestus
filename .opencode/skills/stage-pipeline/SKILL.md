---
name: stage-pipeline
description: Execute the linked stage pipeline as a stage agent - the abstract tick procedure and the per-ticket fulfillment loop. Load when running a stage ticker tick or fulfilling a stage ticket.
---

# Stage pipeline (U-110)

You are a stage agent in the linked stage pipeline. The procedure is ABSTRACT
and identical for every stage; only your stage's data differs (its division
map, its discipline skill, its artifacts). Tickets are STATIONARY: they are
born in a stage and never move - completion creates and links the downstream
tickets instead.

## The tick (ONE pass over the whole V - always the same)

Run this loop exactly. Do not improvise steps or order. ONE schedule
(`ticker-v`) walks the whole V - the whole-V pass is the standing shape
(DS-003), not a per-stage sub-pass: for each stage in canonical order —
requirements, system, architecture, design, implementation,
test-implementation, test-design, test-architecture, test-system,
test-requirements — run steps 1-4, then step 5 once.

**Pass preamble - reconcile (S-2, DS-004):** before the stage loop,
classify every claimed ticket over the spawn ledger (your own bookkeeping:
one entry per spawn of this steward lifetime, `completed` set on the
child's completion report) and the job list (W13) - lifetime-scoped
classification, no time-based staleness bound:

- **in flight** - assignee in the `ticker-v:<ticketId>` namespace AND the
  ledger entry's job is live in the job list: skip; it counts toward the
  cap.
- **dead child** (ledger entry, job absent from the job list, no completion
  report) or **orphaned** (assignee in the namespace, no ledger entry):
  `task_release`, re-plan, re-claim under the cap. After a restart the
  ledger is empty, so every machinery claim classifies as orphaned -
  correct, because nothing survives the restart.
- **foreign claim** (assignee outside the namespace): not the ticker's
  property - untouched, never released.

Two hard lines hold by construction: orphans are released on the first
pass that observes them (never silently `in-progress` forever), and
release happens only when no live child of any lifetime can still be
executing (never double-spawn). Conservative fallback: job list
unavailable → every ledger entry without a completion report counts as
live - reconciliation may under-release, never double-spawn; stragglers
resolve when the surface recovers. Re-check the liveness sweep at step 5.
The preamble only releases claims; it never writes stage business state,
and the stage loop's order below is untouched.

**Kickoff horizon read (DS-2 / C-C3-1, `docs/autonomy-spectrum-design.md` §2.3 —
the pump's one dial touchpoint, run before the stage loop):** resolve the
run's position once per pass: from a staged ticket of the run, walk the
`epic` link to the epic-less ancestor, the
stageless GOAL root (a staged ticket with no epic ancestor has no position -
fall through); `task_get` (E2) the root and parse its dial record's
`goal horizon` line - and ONLY that line (DD-03): the review-cadence dial
has no pump edge, no step below takes a dial parameter, and the five steps
stay `docs/design-stage-pipeline.md` §4 verbatim under both horizon modes
(QA-3, pump invariance).
Record absent, unparseable, no epic-root ancestor, or the read failing →
fall through silently to the doctrine defaults (C-C5-1:
`run-to-Product-Goal`, the T-041 floor unchanged - `AGENTS.md`): the tick
proceeds, and the pump NEVER asks the owner - it holds no edge back to
intake (DENY-1). Re-read at every pass; no session caches a run position
(DD-02), so a mid-run re-position by the owner is obeyed at the next pass
with no restart machinery (advisory data, QA-1). Edges used here: E2 (the
store read) and E4 (this skill), nothing else (autonomy-spectrum design
§2.3, §8).

**Horizon enforcement at the schedule lane (C-C3-3,
`docs/autonomy-spectrum-design.md` §2.3; E6 - arm at kickoff via
`ticker-setup.ps1`, disarm at a time-boxed horizon; not a store edge):**

- `run-to-Product-Goal` (or a record falling through to it) → the `ticker-v`
  schedule keeps recurring; the run stops only at NEEDS-HUMAN escalations
  (step 2) or the GOAL root reporting done by engine verdict.
- `time-boxed(end)` reached - at the kickoff read or observed later in the
  pass - → no NEW READY launches from that point on; the current boundary
  still finishes cleanly: claimed background workers already spawned run to
  their engine verdicts (in-flight tickets are never abandoned mid-flight
  because a clock ran out, AD-4), engine verdicts land, and step 6 closes
  the record with a digest line naming the horizon end. Then the steward
  disarms as the pass's last act - the durable disable this skill documents
  (`.harness/ticker-state.json` `enabled: false` + delete the `ticker-v`
  schedules) - so the watchdog cannot re-arm a horizon-ended run; the digest
  records the ACTUAL disarm time - a late disarm is visible, never silent.
  A missed disarm (steward session died first) → the next pass disarms;
  precision is schedule-tick-granular (AD-4's recorded trade-off).
- The disarm is a deliberate stop, NEVER an escalation: no
  `task_set_blocked`, no NEEDS-HUMAN for a clock
  (`docs/autonomy-spectrum-system.md` §Interfaces 5).

Per stage:

1. SCAN — `task_readiness` for the project; take only this stage's tickets.
2. BLOCKED → ESCALATE BACK: a blocked ticket goes to its basis (the upstream
   link) as a rework request via `task_set_blocked` on the basis with the
   reason; if the basis is itself blocked, or the round-trip repeats, escalate
   to NEEDS-HUMAN (`blocked` + reason naming the human decision needed).
   Agents resolve what agents can resolve; the rest surfaces to the Product
   Owner.
3. IN-REVIEW → VERDICT: for each of this stage's tickets sitting in
   `in-review` (and not owned by one of your running subagents) UNLESS the
   ticket's `review` field is `human` (T-155: first/last-stage tickets
   reserved for the OWNER's verdict - the engine never verdicts them;
   `review: ai` or absent keeps this engine path), spawn ONE
   reviewer subagent (the `reviewer` agent, read-only) to give the engine
   verdict: PASS → `done`; FAIL → `blocked` with the reviewer's reasons
   (which escalates to the basis); UNCLEAR → one retry round-trip to the
   fulfiller. Verdicts are the engine's, never the fulfiller's own.
4. DONE → PROCESS FORWARD (division): for each done ticket without downstream
   children, create the next-stage ticket(s) per your stage's division map —
   `task_create` with `stage` = the child stage, `epic` = the parent's epic
   (scope inherits), `basis` = the done ticket's id (the derivation edge), and
   carry the artifact references into the description. Quota (DS-003): the
   pass divides up to **10 basis tickets** - the division-burst quota, a
   procedure constant, deliberately NOT an arm-time parameter. It counts
   basis tickets divided, never child tickets created, and one basis
   ticket's division is atomic: all its children + the paired `test-*` in
   one step. Quota exhausted → the remaining done tickets stay
   done-without-children and are re-discovered next pass (a burst after a
   wave landing drains over several ticks - the pipeline's normal async
   behavior). Pairing: also create
   the paired `test-*` ticket linked to the same basis when the child is a
   definition stage. Live-in-Eclipse rule (owner requirement 2026-11-15,
   T-039): a definition ticket whose acceptance criteria include
   live-in-Eclipse checks (console checks, UI passes, panel round trips)
   divides with a paired test-requirements child carrying that live check —
   live-in-Eclipse verification is ACCEPTANCE verification and belongs to
   the last V stage, not in ad-hoc blocked/in-review states or manual
   follow-up notes. The child is the recurrence pattern, NON-BLOCKING:
   every successful wave/release produces a fresh test-requirements
   acceptance ticket (the T-002 pattern systematized) — it hardens
   acceptance and never gates; no verdict, wave or release waits on it.
   The recurrence entry is division-map DATA (C-C5-3, E4 — the tick
   algorithm stays unchanged while it fires) and it lands the same
   ticket shape at either firing: this child instance and the
   wave-close sweep in step 6(b). The ticket
   (`docs/autonomy-spectrum-design.md` DS-3) is an ordinary staged
   `test-requirements` ticket created through the division/link verbs at
   the boundary — the write goes through the store, the E3-observable —
   and it is a derivation LEAF by construction: nothing's `basis` or
   readiness references it (no ticket ever takes it as its `basis`), so
   no component waits on it; it walks its own `in-review → done` on the
   ordinary verdict lane (step 3) and never gates the wave that spawned
   it (docs/live-in-eclipse-recurrence-flow.md I4/I5). Idempotency heals
   like any division — the consumed [AR-D §2.2 C-M2-3] heal discipline:
   at the next boundary the firing actor re-reads existing derivations
   and creates only the missing ticket — one per successful wave/release
   (DS-3, autonomy-spectrum design §5 W-3 step 2).
   The LIVE-CHECK PAIRING extension (design C-1/W-1; wording:
   docs/live-in-eclipse-recurrence-flow.md §5.1) operationalizes the rule
   once per done definition ticket D, immediately after D's ordinary
   division children: scan D's acceptance criteria for live-in-Eclipse
   checks — none → stop (a doubtful line counts as a match, DS-001);
   source token `(<D id> AC<n>)` from the first matching criterion; if any
   test-requirements ticket's title already carries the token (open or
   done), stop — the instance exists; the scan surface unavailable →
   defer the mint to a later pass, never mint blind (DS-004). Create per
   flow §3 (title with the token suffix, basis = D, epic = D's epic, role
   tester, stage test-requirements, type task / 0 points / medium
   priority, no model, description = flow §3.2 verbatim — the
   NON-BLOCKING paragraph never reworded, acceptance criteria = flow
   §3.3's pairing shape: the live check operationalized + the evidence
   line): `task_create` with the field set, then `task_update` status
   `in-progress` + assignee `owner-acceptance` — confirmed is read-back
   truth (`task_get`), never the minter's intent (DS-002). The engine
   refuses the direct landing → retry in-pass, then the fallback
   `sprint-backlog` + `owner-acceptance` (the assignee pre-check keeps it
   out of targeted claims); an unconfirmed update is repaired by
   re-asserting the update — NEVER re-created (the title scan would see
   the created ticket and dedup would skip the mint, leaving the state
   wrong). Do NOT plan the instance into a sprint record, do NOT spawn a
   worker for it (DR-3).
5. READY → KICKOFF: **plan first, then claim** — READY tickets born from
   division sit in `product-backlog` without a sprint, and `task_claim`
   only takes `sprint-backlog` tickets: `task_plan_sprint` them into the
   rolling `ticker-v` sprint record in the same tick, then claim under the
   spawn cap (`task_claim`), then spawn ONE
   background subagent per claimed ticket **with the stage's agent** (U-104,
   STAGE-AGENT MAP below) and the fulfillment brief (below). A ticket that
   carries a `model` pins the head (the per-ticket override wins). Claims are
   machinery claims: the assignee is
   `ticker-v:<ticketId>` - the ticker's reserved claim namespace (DS-001);
   peer sessions claim under their own identities and never into it.
   Count before every spawn: ledger entries whose child job is live in the
   job list, counting BOTH workers and reviewers; a child stops counting
   on its completion report. Record every spawn in the ledger and mark the
   entry `completed` on the child's completion report; spawn failure after
   a successful claim → release the claim immediately. Never spawn two
   subagents whose file lanes overlap; when lanes overlap, serialize within
   the stage. **A landed markdown doc must be
   lint-clean** (`releng/lint.ps1` over the touched files — CI's markdown
   gate runs on every push and a lint-broken doc turns CI red).

   **STAGE-AGENT MAP** (U-104; every row overridable store-side via
  `task_set_stage_agent` / readable with `task_stage_agents` — settable from
  the chat/Board without file edits): requirements -> `hephaestus-requirements`,
  system -> `hephaestus-system`, architecture -> `hephaestus-architecture`,
  design -> `hephaestus-design`, implementation -> `hephaestus-implementation`,
  test-implementation -> `hephaestus-test-implementation`,
  test-design -> `hephaestus-test-design`,
  test-architecture -> `hephaestus-test-architecture`,
  test-system -> `hephaestus-test-system`,
  test-requirements -> `hephaestus-test-requirements`.
  Head precedence: the ticket's `model` (per-ticket pin) > the store's
  `task_stage_agents` row (read it fresh at spawn time; missing row -> the
  default below) > the agent definition's `model:` frontmatter (INJECTED by
  the local setup, never committed — D-004) > the opencode default.
  `DispatchPolicyStore`/stage-models is gone (U-110) — this map plus the
  ticket's `model` are the whole per-stage dispatch config. Stageless tickets
  spawn with the default worker agent (`executor`).
**STAGELESS verdict lane (once per pass, after the stage loop, before step 6):**
`in-review` tickets with NO stage are invisible to the loop above by
construction - step 1 takes "only this stage's tickets" and readiness reports
a no-stage ticket `NOT_APPLICABLE` - so step 3 never sees them (the T-174
blindness: stageless tickets waited in `in-review` with no verdict lane at
all). Treatment mirrors step 3 exactly: take `task_list(status=in-review)`
and keep the rows with no `stage`; skip a ticket owned by one of your running
subagents; skip a ticket whose `review` field is `human` (T-155 allows the
field on stageless tickets - the OWNER's verdict, never the engine's); for
the rest spawn ONE read-only reviewer subagent under the same cap + ledger
accounting as any stage spawn: PASS → `task_update` done; FAIL →
`task_set_blocked` with the reviewer's reasons (a stageless ticket has no
basis edge - the blocker surfaces to the Product Owner on the next pass's
step 2); UNCLEAR → the doubt stays as a comment on the `in-review` ticket
(the AGENTS unstaged rule - there is no stage visit to count a retry
round-trip against). Verdicts only: stageless tickets never divide (step 4
needs a stage's division map), are never kicked off (step 5 needs a stage's
agent), and this lane never claims one.
6. CLOSE the pass - the order inside the close is fixed (DR-6/DR-9, the
   W-2 composition seam): (a) reopen the `ticker-v` sprint record with
   the digest (U-108 reopen semantics keep the record rolling); (b) the
   WAVE-COMPLETION RECURRENCE sweep (T-039; design C-1/W-2; wording:
   docs/live-in-eclipse-recurrence-flow.md §5.1), once per pass: for
   each sprint record W of the project, W is a completed wave iff W's id
   does not start with `ticker-` (micro-records are never waves), W
   holds at least one ticket, and zero members are open (open = any
   status except done; a `paused` member keeps the wave open). Per
   completed wave: source token `(wave <W id>)`; if any
   test-requirements ticket's title already carries the token (open or
   done), skip W — the instance exists; the scan surface unavailable →
   defer the mint to a later pass, never mint blind (DS-004). Create per
   flow §3 (title with the token suffix, basis = null, epic = W's single
   common epic else null — scope container only, role tester, stage
   test-requirements, type task / 0 points / medium priority, no model,
   description = flow §3.2 verbatim, acceptance criteria = flow §3.3's
   wave shape: the four fixed lines naming the wave's agent-facing
   surfaces, evidence line last): `task_create` with the field set, then
   `task_update` status `in-progress` + assignee `owner-acceptance` —
   confirmed is read-back truth (`task_get`, DS-002); refused landing →
   retry in-pass, then the fallback `sprint-backlog` +
   `owner-acceptance`; an unconfirmed update is re-asserted, NEVER
   re-created. One digest note per mint (U-061). Do NOT plan the
   instance into a sprint record, do NOT spawn a worker for it (DR-3); a
   record failing the predicate is skipped, not a failure — the sweep
   re-derives every pass (QA-002); (c) the sibling release-cadence
   evaluation READS the instances the sweep created or kept
   (docs/release-cadence-flow.md §6 — read-only across the seam, DR-9);
   (d) RELEASE-CADENCE EVALUATION (owner requirement 2026-11-15, T-041;
   wording = flow §4.1's STEP 6 EXTENSION, run once per pass AFTER the
   recurrence mint in (b) so the fresh instance is visible, BEFORE the
   record closes in (e)):

      1. For each completed wave W (flow §1 carve-out applies): run the
         evidence query (flow §3.4), verbatim four steps — (1)
         `task_list(sprint=<W id>)` for the wave's members, completed iff
         every member is `done`; (2) `task_get` W's acceptance instance
         and every member; (3) scan `comments` + `artifacts` for `by` in
         the owner set (`owner`, `owner-directive`, `owner-acceptance`);
         (4) content floor: the matched text must STATE an outcome
         (accept, approve, review verdict) — a question, a direction or a
         drive-by note is not acceptance. Never history, never assignees,
         never git authorship, never chat text; ambiguity → PENDING (R4,
         the conservative asymmetry).
      2. Owner-set evidence passing the floor → outcome `UNLOCKED
         (evidence: <ticket id> + author + ts)`. Otherwise `PENDING
         REVIEW`.
      3. Record it (DS-002 write schedule — two writes, no state): ONE
         line in the pass digest on EVERY evaluation — `release cadence:
         wave <W id> <UNLOCKED (evidence <ref>) | pending review>` — plus
         a `task_add_comment` on W's acceptance instance with the same
         outcome at first determination and at every flip (the state lives
         on a ticket, where comments and history work; the record itself
         has no comment channel).
      4. Do NOT run `release.ps1`. Do NOT block anything. Do NOT spawn.
         Do NOT escalate a PENDING REVIEW as NEEDS-HUMAN — the open
         instance already surfaces it to the owner on the Board.

      The verbs on this path are the reads above plus the two recordings
      — no execution verb exists here (the cut is C3's motion on the
      owner's EXPLICIT GO, flow §4.2: the ticker never runs the script).
      No ticker armed: the kickoff assistant is the fallback evaluator
      with the same query, announced in chat (H7).
   (e) close the record. The next tick repeats.

An empty backlog costs one readiness query. That is a successful tick.

## Fulfillment (worker subagents - one ticket, nothing else)

Your world is the ticket: its content, the DIRECT basis artifacts (from the
artifacts store or repo paths), your stage's discipline skill, and your lane
constraints. You have no big picture and need none — the ticket + the basis
product define done.

1. Read the ticket and its basis artifacts.
2. Do the work in your declared file lane only.
3. Record the stage product: `task_add_artifact` (kind doc/file with the
   product path - or publish to the artifacts store when enabled).
4. Write the fulfillment comment: `task_add_comment` with `by` = your own
   identity - the completion report naming your stage skill. It is your
   identity evidence for the flip and lands BEFORE it: the canonical write
   order is artifact → fulfillment comment → `in-review`.
5. Set the ticket `in-review` - a bare flip and your LAST write. NEVER set
   `done`: the verdict is the engine's review pass + quality gate, never your
   self-assessment.
6. If blocked, `task_set_blocked` with the reason naming what you need. Do
   not improvise around a blocker.

## Boundaries (non-negotiable)

- The stage product is recorded BEFORE `done` — done-time enforcement refuses
  bare definition tickets; do not fight it.
- The spawn cap is a hard limit: at most N subagents running concurrently
  (N = the configured cap). Queue, do not exceed.
- Tickets stay in their stage. Fulfillment moves the LINKED downstream
  tickets, never the ticket itself.
- The user is the Product Owner; the main chat agent is the kickoff
  assistant; you are the worker. Escalate instead of guessing beyond the
  ticket.

## Field observations (live acceptance run, 2026-11-14)

- **Claims are targeted, not role-based.** A blind role-claim grabs the next
  sprint-backlog ticket of the role — which can cross into ANOTHER sprint's
  backlog. Pre-check the candidate's assignee/status, then claim with a
  targeted `task_update` (status + assignee in one move), the assignee
  `ticker-v:<ticketId>` (the machinery claim namespace the preamble's
  classification keys on).
- **Division-with-rationale for legacy done tickets:** a done ticket whose
  derivation already exists in substance (delivered under the old model,
  `basis: null`) gets division recorded SATISFIED with a comment on the
  ticket - do not mint duplicate children for shipped work. The PO can
  override with one comment and the next tick divides literally.
- **Rolling records stay open while work is in flight:** `task_close_sprint`
  ejects unfinished tickets to product-backlog, corrupting in-flight
  fulfillment. Leave the record active until the work lands; a later tick
  closes it.
- **Nested steward sessions need `experimental.subagent_depth >= 2`** in the
  user-global config (setup-local per D-004) - the ticker itself is one
  subagent level, its workers the second.
- **Store access lost mid-flight** (the namespace vanishes mid-pass - opencode
  cycles the tasks MCP server on store-write bursts / session spawns,
  Windows exit code 1 = host-side termination; see T-045): **reconnect and
  CONTINUE the pass** - `POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`
  (service URL + password from `~/.local/state/opencode/service.json`),
  wait for the namespace to re-register, then resume the scan where it
  stopped. Only abort the pass if the reconnect itself fails. The store
  never loses a confirmed write; an aborted pass is the last resort, not
  the protocol. Fresh sessions still re-arm against the stable dropins
  jars (B-027 fix).
- **Pause/resume semantics (owner directive, days-run):** shutting the PC
  down pauses the pipeline - schedules are bound to sessions on the running
  service and die with it. Resume after boot = re-run `ticker-setup.ps1`
  alone (its own parameter surface; the store is the memory,
  nothing else needs re-arming). A fresh `deploy-dev.ps1` also kills the
  live `tasks` server (jar replaced under it) - reconnect via
  `POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`.
- **Re-arm binding (DS-002):** on a LIVE re-arm (the `ticker-v` schedule
  still exists on the running service) `ticker-setup.ps1` REUSES the
  steward binding - same session, refreshed interval and prompt; a fresh
  steward is created only when no schedule exists (first arm or
  post-restart). Reuse preserves the cap counter and ledger, so no drain
  rule is needed; post-restart is cap-safe without one because nothing
  survives the restart (no old children exist to exceed the cap). The
  interlock: a live re-arm never replaces the steward, so there is NEVER a
  state where an old steward's children run while a new steward reconciles
  claims - which is what makes the preamble's lifetime-scoped orphan
  detection sound.
- **Reliable enable/disable (T-052 mitigation, the watchdog):** the
  T-052 cycling eats session-bound schedules stochastically, so a bare
  `ticker-setup.ps1` arming is NOT durable. The self-healing layer:
  `.harness/ticker-watchdog.ps1` (a scheduled task, `HephaestusTickerWatchdog`,
  every 5 min, survives reboots) reconciles the ticker-v schedule with the
  desired-state flag `.harness/ticker-state.json` (`{"enabled": true|false}`).
  ENABLE: set the flag true + run `ticker-setup.ps1` (the watchdog re-arms
  within 5 min if the cycling eats the schedule). DISABLE: set the flag
  false + delete every ticker-v schedule + terminate the ticker-v sessions
  (the durable disable - the watchdog will not re-arm against a false flag).
  The watchdog logs to `.harness/ticker-watchdog.log`.
