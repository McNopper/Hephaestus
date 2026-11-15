# Integration tests: the acceptance run of the async stage pipeline (U-111) — verifies A-001

- **About this document:** the system-level integration test design for the
  U-111 acceptance run — integration criteria for every interface named in
  `docs/acceptance-run-system.md` (IF-1 `task_*` store verbs, IF-2 subagent
  spawn mechanism, IF-3 store-as-blackboard data flows), each traceable to a
  demonstration point (D-1/D-2/D-3) of `docs/acceptance-stage-pipeline.md` and
  decidable from observable store evidence alone. **Read by:** the engine
  review pass (runs the reads, decides pass/fail per criterion), the stage
  tickers, the maintainer. **Related:** `docs/acceptance-run-system.md` (the
  basis: the system under test), `docs/acceptance-stage-pipeline.md` (the
  governing requirements: demonstration contract, evidence list, pass/fail
  bar), `docs/design-stage-pipeline.md` (§4 tick, §5 fulfillment, §8 verdict),
  ticket `A-002` (this doc's recording ticket). Scope: the run's live chain
  T-031 → A-001 → {A-002, A-003, A-004}, epic U-111.

## How these tests run

The system under test is not a program — it is the **running task store plus
its actors** (system doc §Overview). These are therefore **live-observation
integration tests**: a criterion is decided by running the named `task_*`
read(s) against `.opencode/tasks/hephaestus/` and comparing field states and
history events with actors. Every read named here is non-mutating
(`task_get`, `task_readiness`, `task_traceability`, `task_doctor`). PASS bar
per the requirements: **PASS iff D-1, D-2 and D-3 each hold on store evidence
alone**; the full FAIL-trigger list is mapped to criteria below.

Two conventions keep the criteria decidable while the engine's wording may
evolve:

- The **primary check** is a store *state or event meaning* (a field value, a
  history event that performs an action, an actor on that event).
- The **observed literal** is this run's concrete shape (e.g. the done event
  `done (engine review pass: product PASS, lint clean)` by actor `review`) —
  an instance of the meaning, not the only accepted form.

## Parts under test

From the system doc §Major Parts, in the interactions these tests exercise:

| Part | Role in the integration |
|---|---|
| Task store (`.opencode/tasks/hephaestus/`) | coordination blackboard; history with actors is the evidence medium |
| Per-stage ticker (steward session + `experimental.schedules`) | claims READY tickets, spawns workers, divides on done |
| Goal-driven worker subagent | one per claimed ticket; produces the stage product, records it, sets `in-review` |
| Engine review pass + quality gate | the only `done` authority |

## IF-1 — `task_*` store verbs (the only write path)

- **IT-A-101 · Worker write path is claim → artifact → `in-review`, with
  per-verb history events and actors.** (D-1)
  - Run: `task_get` on each chain ticket (T-031, A-001, A-002, A-003).
  - PASS: history contains, in order, (1) the claim/assign transition (actor =
    the ticker; observed shapes: `claimed by system-ticker` by
    `system-ticker`, or `updated:status,assignee`), (2) an artifact-add event
    (action `artifact:<kind>:<ref>`, matching an `artifacts[]` entry, actor =
    the worker identity), (3) the transition to `in-review` (actor = the
    worker; observed shape `updated:status`).
  - FAIL: no recorded artifact event; artifact recorded after `in-review`;
    `done` set by anyone other than the engine review (crosses IT-A-102).
  - Observed: A-001 — claim 17:57:08Z → `artifact:doc:docs/acceptance-run-system.md`
    17:59:43.409Z (by `system-worker`) → `updated:status` 17:59:43.465Z.

- **IT-A-102 · `done` is the engine review's alone.** (D-3)
  - Run: `task_get` on each chain ticket.
  - PASS: on each ticket that reached `done`, the done history event follows
    that ticket's `in-review` event and its actor is the engine review
    (observed: A-001 `done (engine review pass: product PASS, lint clean)`
    18:02:20.790Z by `review`); no `done` event anywhere on any chain ticket
    carries a worker or ticker actor.
  - FAIL: any worker-set or ticker-set `done` event in the chain.

- **IT-A-103 · `stage:` is immutable through every verb.** (D-1, D-2)
  - Run: `task_get` on each chain ticket.
  - PASS: each ticket's `stage` equals the stage it was divided/created into
    (T-031 `requirements`, A-001 `system`, A-002 `test-system`, A-003
    `architecture`) and no history event on the ticket records a stage change.
  - FAIL: any history event rewriting `stage`, or a `stage` value that
    disagrees with the ticket's stage ownership at creation.

- **IT-A-104 · Every mutation went through the verbs (no hand edits).** (D-1,
  D-2, D-3 — requirements PASS: "no manual store hand-edits in any history")
  - Run: `task_doctor` on the project; `task_get` on each chain ticket.
  - PASS: `task_doctor` reports zero unparsable/inconsistent ticket files
    (observed after the 2026-11-15 remediation: `{"inconsistencies":[],"ok":true}`),
    and every chain ticket's history consists of engine-generated action
    shapes (`created`, `planned into …`, claim, `updated:…`, `artifact:…`,
    `artifact removed:…`, `done …`).
  - FAIL: doctor reports an unparsable ticket file, or a history action
    outside the verb-produced shapes.

- **IT-A-105 · Store reads are side-effect-free.** (underpins "decidable from
  the store alone", D-1/D-2/D-3)
  - Run: `task_get` a chain ticket (note `updated_at` + last history event) →
    `task_readiness` + `task_traceability` → `task_get` again.
  - PASS: `updated_at` and the history tail are unchanged by the reads.
  - FAIL: a read appends history or bumps `updated_at` (evidence would then
    be created by its own observation — undecidable).
  - Observed: performed while writing this doc — A-002's history held at its
    claim event across repeated readiness/traceability/get reads.

## IF-2 — subagent spawn mechanism (ticker → worker)

- **IT-A-201 · Exactly one worker fulfills a claimed ticket.** (D-1)
  - Run: `task_get` on each chain ticket.
  - PASS: between the claim event and the `in-review` event there is exactly
    one claim/assign transition, and all stage-product artifact events in that
    window carry ONE worker actor (observed: A-001 claim by `system-ticker`
    → artifact by `system-worker`; A-003 claim 08:28:24Z → artifact by
    `architecture-worker`). A release (`task_release`) between claim and
    `in-review` legitimately resets the window — then the same check applies
    per fulfillment attempt, and only the final attempt's worker may have
    recorded the artifact.
  - FAIL: two distinct worker actors writing stage-product artifacts inside
    one fulfillment window, or a second claim without an intervening release.

- **IT-A-202 · The spawn brief is carried by the store, not by side channels.**
  (D-1, with IF-3)
  - Run: `task_get` the child; `task_get` its `basis`.
  - PASS: the child's description alone states the goal, the file lane, the
    acceptance criteria and the basis artifact refs, and the basis ticket's
    `artifacts[]` actually contains the referenced products — a reviewer
    reading only the two ticket records can reconstruct the brief the worker
    was spawned with.
  - FAIL: the child's product exists but its inputs are not reachable from
    the ticket records alone (brief lived in a prompt/session outside the
    store).
  - Observed: A-002's description names `docs/acceptance-run-system.md` +
    `docs/acceptance-stage-pipeline.md`; both are recorded artifacts of
    A-001 and T-031 respectively.

- **IT-A-203 · The spawn cap is the only admission knob (sampled).** (D-1)
  - Run: during an active tick window, `task_get` over the sprint's tickets.
  - PASS: the count of tickets with `status: in-progress` and an assignee
    stays ≤ the configured spawn cap (default 4) in every sample; corroborate
    with the ticker tick digest (requirements evidence list).
  - FAIL: more tickets claimed within one tick window than the cap.
  - Observed: 2026-11-15 ~08:32–08:41Z — A-002 and A-003 concurrently
    in-progress (2 ≤ 4).
  - Sampling caveat: a ≤-cap result is decidable per sample; a violation is
    only observable if a sample lands inside the over-cap window. Treated as
    a sampled criterion, corroborated by tick digests.

## IF-3 — the store files as the blackboard (division, linking, hand-off)

- **IT-A-301 · Division creates the child with `basis`/`epic` and the
  upstream's artifacts carried.** (D-2)
  - Run: `task_get` each child in the chain.
  - PASS: `basis` = the upstream ticket id, `epic` = the upstream epic, and
    the upstream's governing artifact refs are reachable from the child's own
    record (carried in `artifacts[]` or named in its description).
  - FAIL: missing `basis` or `epic`, or a child whose record does not point at
    its basis's artifacts (the requirements' first FAIL trigger).
  - Observed: A-001 `basis` T-031, `epic` U-111, description carries
    `docs/acceptance-stage-pipeline.md`; A-002 `basis` A-001, `epic` U-111,
    description carries `docs/acceptance-run-system.md`; A-003/A-004 same
    epic, `basis` A-001.

- **IT-A-302 · Readiness gates the child on its basis — no manual
  intervention.** (D-2)
  - Run: `task_readiness` on the project; `task_get` the child and its basis.
  - PASS: a child reports READY only when its basis is `done` (observed:
    A-004 READY "upstream satisfied by basis A-001 (done)"); a ticket in an
    upstream-starved stage reports WAIT_UPSTREAM (observed live: T-007,
    T-008, T-037, T-044, U-059, U-109 — "no done/in-review ticket in upstream
    stage 'design'"); a done ticket reports NOT_APPLICABLE with inputs
    unchanged (observed: A-001). Negative control from this run's history: a
    child divided while its basis was still in-review holds no claim event
    until after the basis's done event (observed: A-002 created 2026-11-14
    17:51:48Z, A-001 done 18:02:20Z, A-002 claimed 2026-11-15 08:32:33Z at
    ticker-v's next tick — the WAIT_UPSTREAM → READY flip happened between
    those events with no manual store write between them, IT-A-104).
  - FAIL: a READY child whose basis is not done/in-review, or a basis-done
    child that stays WAIT_UPSTREAM with no blocker recorded and no live
    fleet job (the requirements' "child never becomes READY without manual
    intervention").

- **IT-A-303 · The ticket file is the hand-off contract (chain closure).**
  (D-2 + D-1)
  - Run: `task_get` each chain ticket, then check each recorded artifact ref.
  - PASS: every chain ticket's stage product exists at the path its
    description/ACs name (T-031 → `docs/acceptance-stage-pipeline.md`; A-001
    → `docs/acceptance-run-system.md`; A-002 →
    `docs/acceptance-run-system.tests.md`; A-003 →
    `docs/acceptance-run-architecture.md`), produced from inputs reachable
    only through ticket records (basis ref + carried/named artifacts).
  - FAIL: a product missing at its recorded ref, or the chain demonstrably
    needing coordination outside ticket records.

- **IT-A-304 · Traceability pairs each definition with its verification.**
  (D-3 input side; requirements evidence list)
  - Run: `task_traceability` on the project.
  - PASS: matrix rows exist for the chain's definition (A-001, architect) and
    its verifiers (A-002/A-004, tester, same epic); every verifier row's
    pairing is self-consistent — `via` explains itself (a distinct verifier
    via `epic:<id>` with `self_verified: false`; a wandered self-pair via
    `journey`/`artifacts` with `self_verified: true`) — and `verified_by` is
    consistent with the verifier tickets' statuses at read time.
  - FAIL: a definition with no tester row in its epic, or a verifier row
    whose `via`/`self_verified` pair is self-contradictory.
  - Observed: A-002 row `verifies U-111 via epic:U-111, self_verified false`;
    live negative-control shapes elsewhere in the project: B-007
    (`self_verified true, via journey`), B-006 (`via artifacts`).

## Requirements FAIL-trigger coverage

Every FAIL trigger of `docs/acceptance-stage-pipeline.md` is decided by a
criterion of this doc:

| FAIL trigger (requirements) | Decided by |
|---|---|
| missing `basis`/`epic` on the child | IT-A-301 |
| a worker-set `done` | IT-A-102 |
| a rewritten `stage:` | IT-A-103 |
| no recorded artifact | IT-A-101 |
| child never READY without manual intervention | IT-A-302 |
| manual store hand-edits in any history | IT-A-104 |

## Deliberate exclusions

- **Spawn serialization on file-lane overlap** (IF-2, system doc): not
  exercised — the run's single-chain happy path never has two tickets sharing
  a file lane (A-002's lane and A-003's lane are disjoint). Deciding it needs
  a seeded overlap fixture; excluded with the requirements' Out of scope
  (single-chain happy path only).
- **Performance, cost, multi-fan-out** (one basis → several children beyond
  the A-001 → {A-002, A-003, A-004} shape): excluded per the requirements.
- **Worker completion-report text and gate build output**: produced outside
  the ticket record (session output, CI log); their store-evidence proxy —
  the `in-review` (worker) → `done` (engine) event pair — is what IT-A-101 /
  IT-A-102 gate on. This doc deliberately does not gate on out-of-store
  content.

## Environment and data

- **Fixture:** the live store `.opencode/tasks/hephaestus/` (no seeding, no
  teardown — the run is the fixture). Chain under test: T-031 (done) → A-001
  (done 2026-11-14T18:02:20Z by engine review) → A-002 (this ticket),
  A-003 (in-review), A-004 (READY, unclaimed), epic U-111, sprint ticker-v.
- **Setup:** any session with the `tasks` MCP server (`eclipse/tasks-tools.ps1`
  or the Eclipse `eclipse-build` endpoint). All reads are non-mutating; no
  writes are performed by these tests — the actors under test produce the
  evidence.
- **History event shapes observed in this run** (instances of the primary
  checks above): `claimed by system-ticker` / `updated:status,assignee`
  (claim), `artifact:doc:<ref>` / `artifact:file:<ref>` (artifact add),
  `artifact removed:file:<ref>` (artifact removal, used by the 2026-11-15
  remediation disclosed on A-003), `updated:status` (transition),
  `done (engine review pass: product PASS, lint clean)` by actor `review`.

## Evidence snapshot (2026-11-15, taken while writing this doc)

Executed during this ticket's fulfillment, as working proof that the named
reads are runnable and decisive: A-001's history shows the full IT-A-101
chain and the IT-A-102 engine-only done; readiness shows the IT-A-302
READY/WAIT_UPSTREAM/NOT_APPLICABLE trio live; traceability shows IT-A-304
rows for A-002; `task_doctor` returned `{"inconsistencies":[],"ok":true}`.
The reviewer re-runs the same reads at verdict time — the snapshot is
corroboration, not the verdict. Done on this chain is the engine review's
alone (IT-A-102); this ticket's own verdict is likewise the engine's.
