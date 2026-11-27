---
name: stage-pipeline
description: Execute the linked stage pipeline as a stage agent - the abstract tick procedure and the per-ticket fulfillment loop. Load when running a stage ticker tick or fulfilling a stage ticket.
---

# Stage pipeline (U-110)

## About this document

- **Kind:** `skill` (the live ticker procedure - the abstract tick pass over the whole V and the per-ticket fulfillment loop)
- **Read by:** the steward session that loads it on every tick; humans operating the ticker; **written by:** maintainers
- **Related:** pairs with `ticker-setup.ps1` (the arm-time tick-prompt carriage) and `docs/design-stage-pipeline.md` / `docs/autonomy-spectrum-design.md`; linked from `AGENTS.md`.

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
test-requirements — run steps 1-4b, then step 5 once.

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
resolve when the surface recovers. Re-check the liveness sweep at steps 4b
and 5.
The preamble only releases claims; it never writes stage business state,
and the stage loop's order below is untouched.

**Kickoff horizon read (DS-2 / C-C3-1, `docs/autonomy-spectrum-design.md` §2.3 -
the pump's one dial touchpoint, run before the stage loop):** resolve the
run's position once per pass: from a staged ticket of the run, walk the
`basis` chain to its root, the GOAL root (a `stage: requirements` ticket,
as `GoalIntake.java:17-18`, `50-74` registers it - never stageless; a
staged ticket with no chain root has no position -
fall through); `task_get` (E2) the root and parse its dial record's
`goal horizon` line - and ONLY that line (DD-03): the review-cadence dial
has no pump edge, no step below takes a dial parameter, and the five steps
stay `docs/design-stage-pipeline.md` §4 verbatim under both horizon modes
(QA-3, pump invariance).
Record absent, unparseable, no chain root, or the read failing →
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
4. DONE → PROCESS FORWARD (division): requirements come in, get sorted and
   prepared, and every done stage ticket TRIGGERS the next stage's ticket(s) -
   for each done ticket without downstream children, create the next-stage
   ticket(s) per your stage's division map - `task_create` with `stage` = the
   child stage and `basis` = the done ticket's id (THE link: the basis chain up
   to the requirement is the scope - the requirement IS the epic, owner rule
   2026-11-19; there is no epic field to set), and carry the artifact references into the
   description. Deterministic check (owner rule 2026-11-19): `task_doctor`
   reports every walked definition ticket that has no link to its next stage,
   no right-leg join (a `test-*` ticket with `basis` = it), or a dangling
   basis - heal by dividing, by creating the join, or - shipped legacy work
   only - by recording a `division SATISFIED` comment (never mint duplicate
   children for shipped work). Quota (DS-003): the
   pass divides up to **10 basis tickets** - the division-burst quota, a
   procedure constant, deliberately NOT an arm-time parameter. It counts
   basis tickets divided, never child tickets created, and one basis
   ticket's division is atomic: all its children + the paired `test-*` in
   one step. Quota exhausted → the remaining done tickets stay
   done-without-children and are re-discovered next pass (a burst after work
   lands drains over several ticks - the pipeline's normal async
   behavior). Pairing: also create
   the paired `test-*` ticket linked to the same basis when the child is a
   definition stage. Live-in-Eclipse rule (owner requirement 2026-11-15,
   T-039): a definition ticket whose acceptance criteria include
   live-in-Eclipse checks (console checks, UI passes, panel round trips)
   divides with a paired test-requirements child carrying that live check —
   live-in-Eclipse verification is ACCEPTANCE verification and belongs to
   the last V stage, not in ad-hoc blocked/in-review states or manual
   follow-up notes. The child is the recurrence pattern, NON-BLOCKING:
   every done definition ticket with live-in-Eclipse checks produces a
   fresh test-requirements acceptance ticket (the T-002 pattern
   systematized) - it hardens acceptance and never gates; no verdict
   waits on it. The recurrence entry is division-map DATA (C-C5-3, E4 -
   the tick algorithm stays unchanged while it fires) and it lands at
   its single firing: this division-time child instance. The ticket
   (`docs/autonomy-spectrum-design.md` DS-3) is an ordinary staged
   `test-requirements` ticket created through the division/link verbs at
   the boundary — the write goes through the store, the E3-observable —
   and it is a derivation LEAF by construction: nothing's `basis` or
   readiness references it (no ticket ever takes it as its `basis`), so
   no component waits on it; it walks its own `in-review → done` on the
   ordinary verdict lane (step 3) and never gates the definition ticket that spawned
   it (docs/live-in-eclipse-recurrence-flow.md I4/I5). Idempotency heals
   like any division — the consumed [AR-D §2.2 C-M2-3] heal discipline:
   at the next boundary the firing actor re-reads existing derivations
   and creates only the missing ticket — one per matching definition ticket
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
   flow §3 (title with the token suffix, basis = D (the basis chain to the
    requirement is the scope - there is no epic field), role
   tester, stage test-requirements, type task / 0 points / medium
   priority, no model, description = flow §3.2 verbatim — the
   NON-BLOCKING paragraph never reworded, acceptance criteria = flow
   §3.3's pairing shape: the live check operationalized + the evidence
   line): `task_create` with the field set - the instance is BORN READY
   (its basis D is done, so the birth rule lands it `ready`) - then
   `task_update` assignee `owner-acceptance`: the assignee is the SHIELD
   (a claim only ever takes UNASSIGNED tickets, so no worker takes it).
   Confirmed is read-back truth (`task_get`), never the minter's intent
   (DS-002); an unconfirmed update is repaired by re-asserting the
   update - NEVER re-created (the title scan would see the created
   ticket and dedup would skip the mint, leaving the state wrong). Do
   NOT claim the instance, do NOT spawn a worker for it (DR-3).
4b. STALE → RE-RUN (the consistency consumer -
   `docs/design-stage-pipeline.md` §3 "consistency is always-on"): rows with
   readiness kind STALE are PRODUCED by the store (the readiness tick's
   `recordInvalidations` leaves the `inputs changed:` marker) but no step ever
   CONSUMED them - a re-run-needed ticket sat unclaimed forever, and its
   markers accumulated as silent noise. Act on EVERY kind-STALE row of this
   stage, by the row's status:
   - status `ready` + unassigned → RE-DISPATCH: claim it directly (the
     Field-observations targeted claim: status `in-progress` + assignee
     `ticker-v:<id>` in one `task_update`, pre-checked unassigned - a
     foreign claim is untouched), then spawn ONE background subagent with
     the stage's agent (STAGE-AGENT MAP) and the step-5 fulfillment brief
     PLUS the re-run note as the ticket's first instruction: the basis
     changed after this ticket's last run (the readiness reason names the
     basis id and both stamps) - re-read the CURRENT basis artifacts and
     revise every claim of your stage product that no longer holds. STALE
     outranks READY in the engine's own report (severity order), so these
     dispatch BEFORE step 5's fresh kickoff; the cap, ledger and
     liveness-sweep accounting are step 5's, unchanged.
   - status `done` → REPORT-ONLY: a terminal done-state ticket is never
     dispatchable (AGENTS.md H6) and re-opening completed work is the
     Product Owner's call - never claim, never block, never spawn. Name
     them in the pass digest (step 6) as ONE count line,
     `stale-done: <n> report-only rows`, so the accumulated markers surface
     every pass instead of sitting silent; the markers stay as the ASPICE
     consistency evidence.
   - `paused` → never claimed directly (AGENTS.md: only the resume path
     moves it); it flows through this step the pass after the pump resumes
     it.

   Policy: RE-DISPATCH, not escalate-to-basis. STALE means the upstream
   ALREADY changed after this ticket ran - the rework happened there, the
   inconsistent product is THIS ticket's, so this ticket re-runs; an
   escalation would push the wrong side (and a direct `task_set_blocked`
   entry on a `done` basis is refused by the store - the chain walk that
   re-opens done hops is escalation's motion from below, not dispatch's).
   Nothing is held to release either: a claimed row reads RUNNING, never
   STALE.
5. READY → KICKOFF: **pre-check, claim, then spawn** - before any
   `task_claim`, re-check the candidate against step 1's readiness row and
   claim only ids whose row read kind READY there (the verb HAS filtered
   readiness since T-226: TaskStore.claim() evaluates StageReadiness inside
   the claim transaction and skips a basis-linked candidate whose verdict
   reads WAIT_UPSTREAM or STALE, while a basis-less flow-WAIT_UPSTREAM
   stays claimable (T-220's loop pin) - keep your own pre-check anyway:
   skip any id the (4b) list named, never scoop it blind). READY tickets
   born from
   division are claimable directly (the birth rule lands them `ready`):
   claim under the spawn cap (`task_claim`), then spawn ONE
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
   the stage. A landed markdown doc must be lint-clean under the
   repository's own verification gate - the gate its AGENTS.md/README
   documents (docs/own-project.md step 4): run that gate before landing the
   touched files; no push-time CI is assumed.

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
6. CLOSE the pass - the sprint model is gone (owner 2026-11-20): no record
   to reopen or close, no wave to sweep (recurrence R2 retired with it -
   only the division-time pairing instance in step 4 remains). The close
   runs the RELEASE-CADENCE EVALUATION (owner requirement 2026-11-15,
   T-041; rekeyed from wave to pass, once per pass):

      1. Run the evidence query (docs/release-cadence-flow.md section
         3.4), rekeyed to the completed work - the live tickets in `done`
         and `not-implemented`: (1) `task_list` those rows; (2) `task_get`
         each plus the goal root when one exists; (3) scan `comments` +
         `artifacts` for `by` in the owner set (`owner`,
         `owner-directive`, `owner-acceptance`); (4) content floor: the
         matched text must STATE an outcome (accept, approve, review
         verdict) - a question, a direction or a drive-by note is not
         acceptance. Never history, never assignees, never git
         authorship, never chat text; ambiguity means PENDING (R4, the
         conservative asymmetry).
      2. Owner-set evidence passing the floor means outcome `UNLOCKED
         (evidence: <ticket id> + author + ts)`. Otherwise `PENDING
         REVIEW`.
      3. Record it (DS-002 write schedule - two writes, no state): ONE
         line in the tick's chat output (the pass digest, H7) on EVERY
         evaluation - `release cadence: <UNLOCKED (evidence <ref>) |
         pending review>` - plus, when a goal root exists, a
         `task_add_comment` on it with the same outcome at first
         determination and at every flip (the state lives on a ticket,
         where comments and history work).
      4. Do NOT run `release.ps1`. Do NOT block anything. Do NOT spawn.
         Do NOT escalate a PENDING REVIEW as NEEDS-HUMAN - a pending
         release is an absence, not a datum (DS-4), and the open work
         already surfaces on the Board.

      The verbs on this path are the reads above plus the recordings -
      no execution verb exists here (the cut is C3's motion on the
      owner's EXPLICIT GO, docs/release-cadence-flow.md section 4.2: the
      ticker never runs the script). No ticker armed: the kickoff
      assistant is the fallback evaluator with the same query, announced
      in chat (H7). The next tick repeats.

An empty ready queue costs one readiness query. That is a successful tick.

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
  ready ticket of the role. Pre-check the candidate's assignee/status, then
  claim with a targeted `task_update` (status + assignee in one move), the
  assignee `ticker-v:<ticketId>` (the machinery claim namespace the
  preamble's classification keys on).
- **Division-with-rationale for legacy done tickets:** a done ticket whose
  derivation already exists in substance (delivered under the old model,
  `basis: null`) gets division recorded SATISFIED with a comment on the
  ticket - do not mint duplicate children for shipped work. The PO can
  override with one comment and the next tick divides literally.
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
  `.harness/ticker-watchdog.ps1` (a scheduled task, every 5 min, survives
  reboots) reconciles the ticker-v schedule with the
  desired-state flag `.harness/ticker-state.json` (`{"enabled": true|false}`).
  **Nothing machine-specific is hardcoded (T-223)** - the watchdog derives what
  it drives, so the same file works in any checkout/machine: the repo root is
  the script's own git root (the `ticker-setup.ps1:70-80` pattern), the base
  URL is the running service's `url` from `~/.local/state/opencode/service.json`
  (port `28894` only as last-resort fallback), and the **scheduled-task name is
  derived per repo: `<repo folder>TickerWatchdog`** - this checkout resolves to
  `HephaestusTickerWatchdog` (the name its task is registered under), another
  repo to `<yourrepo>TickerWatchdog`; register it under the derived name.
  `-Repo` / `-BaseUrl` / `-TaskName` override any of the three, and `-DryRun`
  prints the resolved triple plus the ready-to-run `schtasks` registration
  command without reading state or touching the service. ENABLE: set the flag
  true + run `ticker-setup.ps1` (the watchdog re-arms
  within 5 min if the cycling eats the schedule). DISABLE: set the flag
  false + delete every ticker-v schedule + terminate the ticker-v sessions
  (the durable disable - the watchdog will not re-arm against a false flag).
  The watchdog logs to `.harness/ticker-watchdog.log`.
