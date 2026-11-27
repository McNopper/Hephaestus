# Design: the unified V-ticker (`ticker-v`) component contracts (T-117)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket T-117 (project
  `hephaestus`, basis T-103, stage `design`): the inside of the
  unified V-ticker's components — component contracts (inputs → outputs |
  errors), data structures, key workflows and error behavior, derived from the
  architecture's component set C1..C8 and its default-deny edge list E1..E11.
  The design leg also owns the three items the architecture routed here: the
  Q-002 and Q-003 answers (DS-002, DS-003) and the S-2 orphaned-claim
  detection mechanism (DS-004). Q-001 and Q-004 stay routed (§6).
- **Read by:** the downstream implementation stage (its basis — what gets
  built or adjusted per component), the `test-design` stage (component tests
  against these contracts), the engine review pass, the stage tickers and
  workers this design describes, and the maintainer.
- **Related:** `docs/unified-ticker-architecture.md` (T-103 — the governing
  architecture: components, default-deny edges, denied-edge rules,
  pass-through table; cited [ARCH]); `docs/unified-ticker-system.md` (T-055 —
  the system model: I-1..I-5, INV-1..INV-7, S-1..S-6; cited [SYS]);
  `docs/design-stage-pipeline.md` (the U-110 design — the store, link,
  fulfillment and verdict contracts carry over from it unchanged; cited
  [DSG]); `docs/unified-ticker-requirements.md` (T-035 — FR-001..FR-015,
  constraints C-A..C-F; cited [REQ]); `.opencode/skills/stage-pipeline/SKILL.md`
  (the one-pass tick procedure; cited [SKILL]);
  `.opencode/skills/stage-pipeline/ticker-setup.ps1` (the prototype arming
  script — read-only provenance; cited [SETUP]).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## 1. Design scope

[ARCH] fixes structure: components, layers, default-deny edges, the
interaction model and the invariant anchors. This document designs the
**inside** of those components — no new component, no new edge, no re-decision
of anything the pass-through table (§Pass-through in [ARCH]) already fixes.
Per the stage contract, where the basis fixes structure this document records
the mapping and moves on (AD-003's rule, applied one level down).

Contract notation throughout: `name(inputs) -> output | errors`. Design
decisions made at this stage are recorded as **DS-001..DS-006** at their point
of use and summarized in §5. Everything the basis already decided appears
here as a pass-through row in §6, not as new design.

The design leg's owned items, and where they land:

- **Q-002 (steward-session accumulation)** — answered as DS-002 (§2 C8, §3
  W-2): reuse of the steward binding on a live re-arm, fresh steward per
  service lifetime.
- **Q-003 (one-tick context fit at deep-V scale)** — answered as DS-003 (§2
  C4, §3 W-1/W-5): the whole-V pass stays the standing shape, the pass's
  division step gets an explicit quota, and the store-carried stage cursor is
  rejected with rationale.
- **S-2 orphaned-claim detection** — designed as DS-004 (§2 C4, §3 W-3):
  ledger-plus-namespace classification, no time-based staleness bound.

## 2. Component contracts

### C1 `store` — the substrate

Engine-owned semantics; [DSG §2/§3] pass-through wholesale ([ARCH
§Pass-through]). This design records only the **calling conventions** the
other components rely on — the verbs are the existing `task_*` surface, none
added, none re-specified:

- `get(ticketId) -> Ticket` via `task_get` | errors: unknown id → not-found.
- `readiness(stage) -> Report` via `task_readiness` | errors: unknown stage →
  error. Empty-V cost: exactly one query per stage (FR-014).
- `board() -> Board` via `task_board` — the reconciliation and verdict steps'
  observation input.
- `plan(record, tickets, goal)` via `task_plan_sprint` — U-108 reopen
  semantics: into a CLOSED record reopens it (`createdAt` preserved); into an
  ACTIVE record is a goal update only ([SYS I-5], pass-through).
- `claim(role) -> Ticket | null` via `task_claim` — atomic; the only
  serialization point. The claim identity convention is DS-001 (below).
- `release(ticketId)` via `task_release` — returns the ticket to
  `sprint-backlog`; the reconciliation verb (S-2).
- `update(ticketId, fields)` via `task_update` — the `done` write is reached
  only by C4's verdict application (AD-005). The exclusion is contractual:
  C5's brief and C6's edit-denied agent config exclude the write; the store
  records history but does not police callers ([SYS I-2], E5/E6 write sets).
- `setBlocked(ticketId, reason)` via `task_set_blocked` — escalation (step 1
  of a stage visit, FR-006) and the worker's stuck exit (E5).
- `addArtifact(ticketId, kind, ref, note, by)` via `task_add_artifact` — the
  worker's product record; the done-time guard reads it ([DSG §2]).
- `addComment(ticketId, text, by)` via `task_add_comment`.
- `create(ticket)` via `task_create` — division; carries `stage` /
  `basis` and the artifact references ([DSG §3]).
- `close(record)` via `task_close_sprint` — gated: only at a pass end where
  nothing is claimed or in flight (FR-013, pass-through).
- `doctor()` via `task_doctor` — the write-discipline verifier (C-E).

**Deliberately absent** (each absence is a decision, see §5): no stage-cursor
field (DS-003 rejects the cursor), no spawn-session pointer on claims (DS-004
keys on the claim identity namespace and the steward's own ledger instead),
no `divided` flag on tickets (DS-005: children's `basis` links pointing at a
ticket are the divided evidence; atomic per-ticket division makes that
sufficient).

### C2 `host` — the platform surfaces

Native surfaces only ([DSG §11] native-first pass-through); the host holds
surfaces and transport and initiates nothing (the denied C2-as-agency edge).
The contracts below are the consumed faces of W1 schedules, background
subagents, the W13 job list and the `tasks` MCP transport:

- `scheduleList() / scheduleGet(name) / scheduleCreate(rec) /
  schedulePause(name) / scheduleResume(name) / scheduleDelete(name)` — the
  W1 experimental-schedules surface. `scheduleCreate` on an existing name is
  the duplicate hazard Route A's guard removes; the design's arming contract
  (C8) never calls it before a name lookup returns empty.
- `sessionCreate(parentSessionId, title) -> sessionId` — the steward is
  created as a CHILD of a repo-rooted parent (C-C: config inheritance — task
  tools and skills); plain home-rooted sessions get user-global config only.
- `spawnBackground(sessionId, prompt) -> job` — native 2.3.0 semantics:
  returns immediately, reports on idle; no reverse channel ([SYS I-2]).
- `jobList(scope) -> Job[]` — W13, adopted ([DSG §11]); DSG §5's watchdog was
  its first consumer, the steward's liveness sweep (DS-004) is the second.
  Errors: surface unavailable → callers fall back conservatively (§4).
- `tasksConnect(directory) -> transport` — the mid-pass store-loss reconnect
  ([SYS I-3]; url + token from `service.json`, Basic auth). Errors: reconnect
  failure → abort the pass (last resort; confirmed writes alone suffice to
  restore coherence, QA-5).

### C3 `schedule` — the one record

Runtime control-data on C2; dies with the service ([SYS I-1] pass-through for
lifetime and health). The record shape is fixed by [SYS §Major parts]:
`{sessionID, name: "ticker-v", when: {every N minutes}, prompt, runCount,
lastOutcome}`. The design adds one contract detail — the **prompt text
contract** (arm-time data, authored by C8):

- contents: the canonical ten-stage list (FR-005), the Cap value (C-F), the
  pointer to [SKILL] as the procedure, and the **supersession clause**
  (DS-006): "this prompt is the current parameterization; it supersedes any
  earlier tick prompt still visible in this session's history".
- the clause is static prompt text, not carried state — E2's rule stands
  (each firing is a complete, independent pass; no state between ticks in
  prompt or schedule). It exists precisely because DS-002 reuses the steward
  session on a live re-arm: an updated prompt must not be second-guessed by
  an older prompt's parameters in the session history.
- errors: none (data, not process). A malformed prompt is an arming-time
  defect caught by C8's contract, never a tick-time event.

### C4 `steward` — the tick procedure

The control actor: one pass over all ten stages in canonical order (FR-005),
the fixed per-stage processing order (FR-006), the cap discipline (I-4), the
reconciliation duty (S-2), the verdict application (AD-005), the gated close
(I-5). Its edges: E3 (store, the fixed write order), E4 (spawn/admission),
E8 (loads [SKILL] only). Contracts:

- `tick(prompt: TickPrompt) -> PassDigest | aborted` — the pass entry.
  Inputs: the arm-time prompt (stage list, Cap). Steps, in order: the
  reconciliation preamble (DS-004) → per stage in canonical order: BLOCKED
  escalate → IN-REVIEW verdict → DONE divide (DS-005, quota of DS-003) →
  READY kickoff (plan-then-claim under the cap, INV-4) → gated close (I-5).
  Errors: store transport drop → reconnect over C2 and continue the pass
  ([SYS I-3]); repeated failure → abort (last resort); the next tick resumes
  from C1 state alone (QA-5, INV-6). The stage loop's own ordering is
  untouched by the preamble: FR-006 governs per-stage step order; the
  preamble is the pass-level reconciliation duty [SYS S-2] assigns to the
  first pass, run before the loop (it only releases claims; it never writes
  stage business state).
- `reconcile(claims, ledger) -> ReleasePlan` — internal, run at pass start
  (the preamble) and re-checked at the READY step's liveness sweep. The
  classification mechanism is DS-004 (§3 W-3).
- `countLiveChildren(ledger, jobs) -> int` — count-before-spawn (FR-010):
  ledger entries whose child job is live in the C2 job list, counting BOTH
  workers and reviewers ([SYS I-4]). A child stops counting on its completion
  report. Errors: job list unavailable → every ledger entry without a
  completion report counts as live (conservative: the cap is possibly
  under-used, never exceeded — §4).
- `applyVerdict(ticket, verdict) -> status` — PASS → `done` (the only
  reachable `done`, AD-005); FAIL → `setBlocked` with the reviewer's reasons;
  UNCLEAR → one retry round-trip riding E4 (re-spawn the fulfiller with the
  UNCLEAR question appended to its brief), then wait for the next tick —
  one attempt per ticket per tick ([SYS I-3], FR-006).
- `divide(ticket, quota) -> children[] | deferred` — the division step:
  read the done ticket (`get`), create the next-stage children plus the
  paired `test-*` tickets for definition stages (`create` with
  `basis` = the done ticket, artifact references carried —
  [DSG §3]). **Atomic per basis ticket** (DS-005): all children of one
  ticket are created in one step or none are; the quota counts basis tickets
  divided, never child tickets created. Quota exhausted → remaining done
  tickets stay done-without-children and are re-discovered next pass —
  the same sanctioned deferral pattern as FR-011's defer-at-cap; FR-006's
  fixed order is untouched.
- `kickoff(stage, freeSlots) -> claims[]` — the READY step: `plan` the READY
  set into the rolling record (reopens if closed, INV-4's precondition),
  then claim in plan order and spawn one worker per claimed ticket
  (FR-007/FR-008); at cap → defer: planned but NOT claimed, first claimed
  next tick (FR-011). Claim returns null (a peer won the race) → continue
  the plan order, no error. Spawn failure after a successful claim →
  release the claim immediately (self-heal; a ledger-less claim would be
  reconciled anyway, but the same-pass release avoids parking it).
- `close(record) -> digest | skipped` — the gated close: skipped whenever
  anything is claimed or in flight (FR-013, [SYS I-5] pass-through); on
  close, the digest records the pass.

Data structures (all lifetime-local; they die with C2 and their loss is
structurally safe — see DS-002/DS-004):

- `TickPrompt` — C3's prompt contract (stage list, Cap, supersession clause).
- `SpawnLedger` — `ticketId -> {jobId, kind: worker | reviewer, spawnedAt,
  completed: bool}`; one entry per E4 spawn of the current steward lifetime;
  written at spawn, `completed` set on the child's completion report. The
  ledger is the steward's own bookkeeping, not store state — exactly the
  "steward-instance-local" memory the remember-vs-forget table assigns to the
  service side ([SYS §Remember vs forget]).
- `PassDigest` — the closed record's digest text.

### C5 `worker` — the fulfillment loop

One background subagent per claimed ticket (FR-008); store footprint exactly
E5's write set; `done` unreachable; never spawns, never touches another
ticket (denied edges). Contracts:

- `fulfill(brief: Brief) -> CompletionReport` — steps: load common + stage
  skill (E9; [DSG §6]) → read the ticket and its direct basis artifacts →
  work the declared lane → run the lane's gates (lint/tests per the stage's
  discipline) and make them pass → `addArtifact` → `update` to `in-review` →
  `addComment` (completion note) → idle with the completion report.
  Errors: stuck or unfixable gate failure → `setBlocked` on its own ticket
  with the evidence, then idle — never land broken work into `in-review`
  (a wasted reviewer cycle is the alternative); store transport drop →
  reconnect over C2 like any actor (I-3), retry the own-ticket write.
- `Brief` — composed by C4 at spawn; exactly the narrow brief of [DSG §5]:
  ticket content, direct basis artifacts, stage skill, lane constraints —
  no scope umbrella, no other tickets, no store-wide visibility. The UNCLEAR
  retry re-spawn appends the reviewer's question to the same brief shape.
- `CompletionReport` — the only return channel (E4's idle report; no
  mid-flight orders, denied edge). Bounded by contract: ticket id, artifact
  ref, an AC-by-AC verdict line per acceptance criterion, open risks —
  a handful of lines, not the ticket's history (the report is prompt
  discipline, part of the brief text).

### C6 `reviewer` — the read-only verdict

One read-only subagent per `in-review` ticket per stage visit (FR-006 step
2); judges the ticket plus its recorded artifacts (E6 reads), runs the
acceptance/lint gates via bash on the repo tree (E7), file edits denied.
It delivers the verdict; C4 applies it (AD-005). Contracts:

- `review(ticketId) -> Verdict` — `Verdict = PASS | FAIL(reasons) |
  UNCLEAR(question)`.
  - PASS requires: every acceptance criterion verified with cited evidence
    (tool output, paths), no unresolved TODO, gates clean.
  - FAIL names the unmet criteria with the failing evidence.
  - UNCLEAR poses the question back to the fulfiller — also the verdict for
    a **gate tool failure** (lint binary missing, harness broken): the
    environment is broken, the work is not condemned; one round-trip, then
    the repeat escalates via C4's blocked path.
  - errors: ticket not-found → abort with the error in the report; C4 leaves
    the ticket for the next stage visit (no verdict was applied).

### C7 `process` — static knowledge

The procedure and discipline texts ([DSG §6] pass-through; no runtime
actor). Nothing to contract — but this design's procedure deltas have
landing surfaces, recorded for division:

- the reconciliation classification (DS-004) and the division quota (DS-003)
  belong in [SKILL]'s tick procedure text — a **skill-lane ticket** (the
  same lane shape as Route B, but distinct content; Route B's stale
  `-Stages` leftover is not this delta);
- the supersession clause (DS-006) belongs in the tick prompt template C8
  authors — inside Route A's file lane (the script).

### C8 `arming` — the one topology-mutating path

The only path that mutates runtime topology (E1); never touches store
business state (denied C8 → C1 edge; AD-006). Route A owns the script file
and the Q-001 mechanics; this contract fixes the behavior Route A must
implement:

- `arm(-BaseUrl?, -Token?, -IntervalMinutes = 5, -Cap = 4)
  -> ArmResult {scheduleId, stewardSessionId, reused}` — steps:
  (the interval default read 15 at design time; the owner set the cadence
  to 1 fire / 5 minutes on 2026-11-16 - `ticker-setup.ps1` today);
  1. resolve service url + token: explicit overrides win over
     `service.json` (the precedence the delta #3 fix restores - Route A
     content, landed 2026-11-16);
  2. `scheduleGet("ticker-v")`:
     - **found (live re-arm, S-3):** REUSE the binding — the same
       `sessionID`, refreshed interval and prompt (DS-002). The
       schedule-record mechanics (update-in-place vs delete-and-recreate)
       are Q-001's, routed to Route A — BOTH options must preserve the
       sessionID binding and leave exactly one schedule (INV-1);
     - **not found (first arm or post-restart, S-4):** `sessionCreate`
       (child of a repo-rooted parent, title `ticker-v`, C-C), then
       `scheduleCreate` bound to it with the interpolated prompt
       (DS-006's clause included).
  3. assert exactly one schedule and one steward afterwards (INV-1/INV-2);
  errors: service unreachable → fail fast naming the url tried;
  `service.json` missing AND no overrides → fail with the discovery hint;
  a duplicate-name race on create → re-lookup and reuse, fail only if the
  second lookup is also inconsistent. The parameter surface stays exactly
  C-F's two parameters (Q-004 stays owner-call; the division quota of
  DS-003 is deliberately a procedure constant, not a new arm-time
  parameter).

## 3. Key workflows

### W-1 the steady-state pass

S-1 pass-through ([SYS S-1], [ARCH S-1]) with the contracted mechanics:
C3 fires (E2) → `tick` (C4): preamble → per stage — readiness scan (one
query per stage, FR-014), escalation, verdict spawns and applications,
division under the quota, kickoff under the cap → gated close with the
digest. All spawns ride E4 admission (`countLiveChildren` before every
spawn, defer at cap — S-5). Evidence: C3's `runCount`/`lastOutcome`, the
tickets' histories, the record's digest.

### W-2 re-arm — the Q-002 rule (DS-002)

**Decision (Q-002 answered):** the steward-session binding is REUSED on a
live re-arm and freshly created per service lifetime.

- Live re-arm (S-3, schedule exists on the running service): E1 reuses the
  bound `sessionID` (C8 step 2, found branch). The cap counter lives in the
  steward's ledger and session — reuse preserves both, so INV-3 survives by
  construction and no drain rule is needed. This is the REUSE half of the
  drain-or-reuse constraint [ARCH S-3] fixes for E1.
- Post-restart re-arm (S-4, no schedule on the service): a fresh steward is
  created (fresh context, per the requirement's lean). This is cap-safe
  **without** a drain rule because nothing survives the restart: no old
  children exist to exceed the cap, so a zero-initialized counter is CORRECT
  ([SYS I-4]; QA-5 — cost boundedness never depends on unrecorded state
  surviving).
- Rationale against unbounded accumulation (the lean-fresh concern): context
  now resets on every service lifetime — the common case, since machines
  reboot and schedules die with the service ([SYS I-1]) — and within a
  lifetime the owner's disarm path (W1 delete of schedule and session,
  E10) is the supported fresh-within-lifetime escape. The arming path itself
  never duplicates (FR-003, INV-2).
- Interlock with DS-004: because a live re-arm never replaces the steward,
  there is NEVER a state where an old steward's children run while a new
  steward reconciles claims — which is what makes lifetime-scoped orphan
  detection sound.

Q-001 (the schedule-record mechanics) stays routed to Route A; the constraint
Route A must satisfy is stated in C8's contract. Q-004 stays owner-call at
arm time (C-F).

### W-3 reconciliation — orphaned-claim detection (DS-004)

**Decision (S-2 mechanism):** lifetime-scoped classification — the steward's
spawn ledger plus the claim identity namespace. Neither a time-based
staleness bound nor a bare session-existence probe.

The claim identity convention (DS-001, grounded in observed store practice:
`ticker-v:A-002`, `ticker-v:A-003`, and every in-flight machinery claim
T-094..T-121): every machinery claim carries the assignee
`ticker-v:<ticketId>`. The namespace is reserved for the ticker machinery;
peer sessions (chat, human-run waves) claim under their own identities and
must not claim into it (the store's peer convention, [AGENTS.md]).

At the pass preamble (and re-checked at the READY step's liveness sweep), for
each claimed ticket:

| Claim class | Test | Disposition |
|---|---|---|
| in flight | assignee in namespace AND ledger entry with a live job in the C2 job list | skip; counts toward Cap |
| dead child | ledger entry, job absent from the job list, no completion report | `release` → re-plan → re-claim under the cap (S-5 rules) |
| orphaned | assignee in namespace, no ledger entry | `release` → re-plan → re-claim under the cap |
| foreign claim | assignee outside the namespace | not the ticker's property — untouched, never released |

- The ledger-miss rule covers BOTH reconciliation triggers with one
  mechanism: after a restart the ledger is empty, so every machinery claim
  classifies as orphaned — correct, because nothing survives the restart —
  and mid-lifetime, a claim whose spawn never got ledgered (crash between
  claim and spawn) classifies the same way.
- Why not a staleness bound: a time window would kill slow-but-alive workers
  — the double-spawn hazard the fixed contract forbids ([SYS S-2]) — and
  would needlessly park post-restart orphans for the window's duration.
- Why not a bare existence probe: after a restart there is nothing to probe
  (trivially all-dead), and within a lifetime the probe needs the child's
  job identity — which only the ledger has. Ledger + job list is the exact
  combination; the job list is the adopted W13 surface (no new machinery —
  the [DSG §5] watchdog precedent, second consumer).
- Conservative fallback: job list unavailable → ledger entries without a
  completion report count as live (§4) — reconciliation may under-release,
  never double-spawn. Under-released stragglers resolve when the surface
  recovers.
- INV-4 on re-queue: `release` returns the ticket to `sprint-backlog`;
  re-claim is preceded by `plan` (idempotent goal update on the ACTIVE
  record, U-108) so plan-then-claim holds whichever way release interacted
  with record membership.
- The contract's two hard lines hold by construction: orphans are released
  on the first pass that observes them (never silently `in-progress`
  forever), and release happens only when no live child of any lifetime can
  still be executing (never double-spawned).

### W-4 fulfillment, verdict and application

Worker lifecycle ([DSG §5] pass-through, contracted in C5): claim implies
spawn (FR-008) → the brief → lane work → gates green → artifact + `in-review`
→ idle report → the ledger entry completes → the slot frees. The ticket's
next stage visit spawns one reviewer (C6, skipped when the steward's own
running children own the ticket) → verdict → C4 applies it in the fixed
write order (E3, AD-005). UNCLEAR rides E4 once as a brief-retry, then waits
for the next tick (one attempt per ticket per tick).

### W-5 division under the quota (DS-003/DS-005)

Done-without-children tickets are found by readiness each visit; the pass
divides up to the quota (default 10, a [SKILL] procedure constant), each
basis ticket atomically (all children + paired `test-*` in one step, artifact
references carried). Quota remainder → next pass. The atomicity rule is what
makes the done-without-children detection safe: children's `basis` links are
the divided evidence, so a half-divided ticket would be re-divided next pass
and duplicate children — the quota counts whole basis tickets precisely to
keep that impossible. Bursts after a wave landing therefore drain over
several ticks (~quota per pass), which is the pipeline's normal async
behavior: the next stage starts on children as they appear.

### W-6 defer-at-cap and the claim-first carry

S-5 pass-through: a spawn that would exceed Cap is deferred — planned, never
claimed (FR-011); the next tick claims deferred tickets first. Workers from
earlier ticks count while alive, so a pass starting with live carry-over has
correspondingly fewer free slots — the cap is global across ticks (I-4).

## 4. Error handling

| Condition | Expected behavior |
|---|---|
| Store transport drop mid-pass (any actor) | reconnect via `tasksConnect` and continue where it stopped ([SYS I-3]); repeated failure → abort the pass (last resort); next tick resumes from C1 state alone (QA-5) |
| Job list unavailable | conservative liveness: ledger entries without completion reports count as live — the cap is possibly under-used, never exceeded (INV-3); reconciliation under-releases, never double-spawns |
| Spawn rejected by the host after a successful claim | release the claim immediately, continue the plan order; the ticket re-enters the next pass's READY set |
| Claim returns null (peer won the race) | continue the plan order — not an error (the store's atomic claim is the only serialization point) |
| Worker stuck / unfixable gate failure | `setBlocked` on its own ticket with evidence, idle — never land broken work in `in-review`; C4's escalation step handles the blocked ticket next pass (FR-006 step 1) |
| Reviewer gate tool failure (environment) | UNCLEAR carrying the environment error — one round-trip to the fulfiller; a repeat escalates through C4's blocked path (NEEDS-HUMAN when the round-trip repeats) |
| Reviewer hits a not-found ticket | abort with the error reported; no verdict applied; the ticket waits for the next stage visit |
| Arming against an unreachable service | fail fast naming the url tried — no partial topology mutation |
| Arming without `service.json` and without overrides | fail with the discovery hint (never fall back to an implicit location) |
| Duplicate-name race on schedule create | re-lookup and reuse; fail only if the second lookup is still inconsistent (exactly-one is asserted either way, INV-1) |
| Close attempted while anything is claimed or in flight | close is skipped — the record stays ACTIVE (FR-013, INV-5) |

## 5. Design decisions index

| DS | Decision | Basis item it answers | Where |
|---|---|---|---|
| DS-001 | Machinery claims carry the assignee `ticker-v:<ticketId>`; the namespace is reserved for the ticker machinery | the S-2 contract's need for a store-visible discriminator | §3 W-3 |
| DS-002 | Q-002 answered: reuse the steward binding on a live re-arm; fresh steward per service lifetime; no drain rule needed in either case | Q-002, constrained by [SYS S-3]/[ARCH S-3] | §2 C8, §3 W-2 |
| DS-003 | Q-003 answered: whole-V pass stays the standing shape; the division step is quota-bounded (default 10, a procedure constant); the store-carried stage cursor is rejected | Q-003, [ARCH AD-002] | §2 C4, §3 W-1/W-5, §6 |
| DS-004 | S-2 mechanism: lifetime-scoped classification over the spawn ledger + claim namespace + W13 job list; no staleness bound | the routed dead-claim detection item, [SYS S-2] | §2 C4, §3 W-3 |
| DS-005 | Division is atomic per basis ticket; children's `basis` links are the divided evidence; no `divided` flag exists | FR-006 step 3, [DSG §3] | §2 C1/C4, §3 W-5 |
| DS-006 | The tick prompt carries a supersession clause; the arm-time parameter surface stays C-F's two parameters | I-1's arm-time data, C-F, Q-004 (stays routed) | §2 C3/C8 |

## 6. Resolution of the architecture's elements

Every [ARCH] element resolves to a contract above or an explicit
pass-through — nothing invented, nothing dropped:

| Element | Resolved as | Where |
|---|---|---|
| C1 `store` | contract: verb calling conventions + deliberate absences | §2 C1 |
| C2 `host` | contract: consumed native surfaces | §2 C2 |
| C3 `schedule` | contract: record + prompt text contract (DS-006) | §2 C3 |
| C4 `steward` | contract: tick, reconcile, admission, verdict, division, kickoff, close | §2 C4 |
| C5 `worker` | contract: fulfill loop, brief, completion report | §2 C5 |
| C6 `reviewer` | contract: review + verdict rules | §2 C6 |
| C7 `process` | pass-through ([DSG §6]) + landing surfaces for DS-003/004/006 | §2 C7 |
| C8 `arming` | behavior contract for Route A (mechanics stay Q-001's) | §2 C8 |
| E1 | C8's arming contract + DS-002's binding rule | §2 C8, §3 W-2 |
| E2 | C3's prompt contract; firing pass-through ([SYS I-1]) | §2 C3 |
| E3 | C4's `tick`/`applyVerdict`/`divide`/`kickoff`/`close` (fixed order, FR-006) | §2 C4 |
| E4 | C4's admission (`countLiveChildren`, defer-at-cap) + C5/C6 spawn briefs | §2 C4/C5/C6 |
| E5 | C5's write set (own ticket only; `done` unreachable) | §2 C5 |
| E6/E7 | C6's read-only review + gate runs | §2 C6 |
| E8/E9 | C7 pass-through ([DSG §6]) + C4/C5 loading rules | §2 C7 |
| E10/E11 | operator edges — outside the runtime loop, pass-through ([SYS §Major parts]) | §6 (no contract; the design adds none) |
| I-1 | pass-through: lifetime/health fixed by [SYS I-1]; prompt text contracted (DS-006) | §2 C3 |
| I-2 | pass-through: lifetimes/footprints fixed; brief + ledger contracted | §2 C4/C5 |
| I-3 | pass-through: the fixed write order is E3's contract verbatim | §2 C4 |
| I-4 | pass-through: count-before-spawn + defer-at-cap as C4 contracts | §2 C4, §3 W-6 |
| I-5 | pass-through: C1 data + gated close | §2 C1/C4 |
| S-1 | pass-through walk, mechanics contracted | §3 W-1 |
| S-2 | **designed here** — the detection mechanism (DS-004); the release-and-requeue contract was already fixed | §3 W-3 |
| S-3 | **decided here** — the reuse half of drain-or-reuse (DS-002) | §3 W-2 |
| S-4 | pass-through: fresh steward + full reconciliation, covered by DS-002/DS-004 | §3 W-2/W-3 |
| S-5 | pass-through walk | §3 W-6 |
| S-6 | pass-through walk (gated close) | §4 (close row) |
| INV-1 | preserved by C3's name identity + C8's lookup-before-create + Q-001's Route A mechanics | §2 C8 |
| INV-2 | preserved by DS-002: reuse keeps one session on live re-arm; fresh only where none is live | §3 W-2 |
| INV-3 | preserved by C4's count-before-spawn + defer-at-cap + DS-002 (no counter reset while children live) | §2 C4, §3 W-2/W-6 |
| INV-4 | preserved by C4's kickoff order + the re-plan-before-re-claim rule | §2 C4, §3 W-3 |
| INV-5 | preserved by C1's single record + C4's gated close (FR-013) | §2 C1/C4, §4 |
| INV-6 | preserved by the total remember-vs-forget split + C4's reconciliation from C1 + the empty ledger | §3 W-3 |
| INV-7 | preserved by C8's single arming path with no per-stage mode (FR-004) | §2 C8 |

### Open questions — dispositions

| Question | Disposition | Rationale |
|---|---|---|
| Q-001 (idempotency mechanism: update vs retire-and-recreate by name) | **stays routed to Route A** (implementation, against the actual W1 API) | per [SYS Q-001]/[ARCH]; the design constrains but does not decide: any mechanism must preserve the sessionID binding (DS-002) and leave exactly one schedule |
| Q-002 (steward-session accumulation) | **answered — DS-002**: reuse on live re-arm, fresh per service lifetime | the reuse half of [ARCH S-3]'s drain-or-reuse constraint preserves INV-3 with no drain rule; freshness per lifetime answers the lean-fresh concern; the owner's disarm path is the manual fresh escape |
| Q-003 (one-tick context fit) | **answered — DS-003**: whole-V standing shape + division quota; cursor rejected | per-pass load is already bounded on the expensive axis (spawns ≤ Cap incl. reviewers, briefs and reports bounded by contract); the residual unbounded term (division bursts) gets the quota; the cursor needs new store semantics (contradicts C1's pass-through-fixed contract, [DSG §2]) and would serialize the V (ten ticks minimum per walk) for a throughput gain the contract never needed — correctness is window-independent by design (QA-5, INV-6). If the paired test's live evidence shows real context pressure, the cursor returns as a new basis-driven delta — not silent growth of this one |
| Q-004 (cadence/cap defaults) | **stays routed** — owner call at arm time (C-F) | per [SYS Q-004]/[ARCH]; the design adds no default and deliberately no new arm-time parameter (the quota is a procedure constant) |

### Routes A/B/C — untouched

The prototype deltas keep their destinations ([SYS §Downstream routes]):
Route A (script hardening, deltas #1 and #3) gains this design's behavior
contract (§2 C8) and the DS-006 clause for its prompt template; Route B
(the stale `-Stages` leftover) is unchanged; Route C (the `subagent_depth`
note) is unchanged. The DS-003/DS-004 procedure deltas land as a separate
skill-lane ticket (§2 C7) — division at this ticket's done-time mints it.

## 7. What the paired test leg verifies

For the `test-design` ticket minted at division:

1. Coverage: every [ARCH] element (C1..C8, E1..E11, I-1..I-5, S-1..S-6,
   INV-1..INV-7) resolves in §6's matrix — none dangling, none invented.
2. DS-002 against [SYS S-3]: reuse preserves the cap count; fresh-after-
   restart is safe because children die with the service; no state where old
   children run while a new steward reconciles.
3. DS-003 against FR-005/FR-006/FR-011: the quota is a deferral on the
   division step, not an order change; the whole-V pass is untouched.
4. DS-004 against the [SYS S-2] fixed contract: orphans released and
   re-queued on the first observing pass; never double-spawned while a
   worker might be alive; foreign claims untouched.
5. No contract contradicts [SYS] or [DSG]: spot-check the quota vs FR-006's
   fixed order, the preamble vs FR-006's per-stage order, DS-002 vs
   FR-001/FR-003/INV-2, and the error table's fail-safe directions (§4).

## 8. Traceability — T-117's acceptance criteria

- **AC-1 (the artifact exists with contracts):** this document — About
  header, per-component contracts with inputs/outputs/errors (§2), workflows
  consistent with E1..E11 and the denied-edge rules (§3; every contracted
  write cites its edge; the denied edges appear as the write-set exclusions
  in §2 C1/C5 and the untouched rows of §6).
- **AC-2 (every element resolves; questions disposed; no contradiction):**
  §6's resolution matrix (C1..C8, E1..E11, I-1..I-5, S-1..S-6, INV-1..INV-7),
  the Q-002/Q-003 answers with the Q-001/Q-004 routed dispositions, DS-004's
  S-2 mechanism, and the pass-through citations to [SYS]/[DSG] throughout —
  each contract names the basis text it carries over rather than re-deciding
  it.
- **AC-3 (recorded, then in-review; done never self-assessed):** the artifact
  is recorded on T-117 with `task_add_artifact` before the status move;
  `in-review` is set and the engine's review pass owns `done`.
