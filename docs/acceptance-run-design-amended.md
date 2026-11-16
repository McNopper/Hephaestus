# Design: acceptance-run component contracts under the amended attribution model (T-111)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket T-111 (epic U-111, basis
  T-098, stage `design`): the inside of the acceptance run's
  amendment-touched components — component contracts (inputs → outputs |
  errors), data structures, key workflows and error behavior for exactly the
  surfaces the 2026-11-15 attribution amendment touches: the store's
  history/attribution surface, the review pass's evidence marks, and the
  done-flip evidence flow.
- **Read by:** the downstream implementation stage (its basis — what gets
  built or adjusted per component), the `test-design` stage (component tests
  against these contracts), the engine review pass, and the reviewer
  (traceability check against the amended architecture and the settled
  system re-specification).
- **Related:** `docs/acceptance-run-architecture.md` (T-098 — the governing
  architecture AS AMENDED 2026-11-15; cited [ARCH]);
  `docs/acceptance-run-system.md` (T-097 — the settled attribution
  re-specification, the source of truth for the attribution rule; cited
  [SYS]); `docs/design-stage-pipeline.md` (the U-110 design — pass-through
  basis; cited [DSG]); `docs/acceptance-run-design.md` (T-095 — the sibling
  design of the un-amended machinery; a read-only neighbour this ticket
  never edits — see §1); `docs/acceptance-stage-pipeline.md` (the run's
  requirements — the demonstration contract; cited [REQ]).

## 1. Design scope

The amendment (T-097 → T-098) did not add modules or edges — it re-pointed
the attribution wording of [ARCH] onto the store's observable reality, per
the settled rule of [SYS §Interfaces 1]. This document designs the inside of
exactly the components whose contracts that re-pointing touches:

- **C-A1 — store history & attribution surface (M1)** — the append-only
  history with *partial* actor attribution; the substrate property every
  D-point's decidability rests on.
- **C-A2 — review-pass evidence marks (M4)** — the observable marks the
  review pass leaves on a ticket (both mark shapes), the review batch, and
  the read-time attribution decision.
- **C-A3 — done-flip evidence flow (M3 → M4 across M1)** — the end-to-end
  evidence chain: the worker's bare `in-review` stop with its
  identity-adjacent comment, and the engine's marked `done` under the
  required `in-review` → `done` ordering, with grandfathering for
  pre-2026-11-15 dones.

Everything the amendment does not touch is recorded as an **explicit
pass-through** and designed nowhere (no invented parallel structure):

- **M2 ticker, M5 process, the spawn cap/lane serialization, the quality
  gate (E7), artifact carrying, claims, readiness, division/linking, and
  D-2's flow** — unchanged by the amendment; their design lives in [DSG]
  (§1–§8) and in the sibling `docs/acceptance-run-design.md` (T-095), which
  covers the un-amended machinery. This doc adds nothing there; the two
  design docs compose without overlap: T-095 designs the untouched whole,
  this doc designs the amendment's delta.
- **The store's verb mechanics themselves** — engine-owned substrate
  (AD-001 pass-through): contracts below *record* the observable behavior
  the amendment re-pointed; none re-decides it.

Contract notation throughout: `name(inputs) -> output | errors`. Design
decisions made at this stage are recorded as **DS-001..DS-006** (§3, §4,
and at their point of use).

## 2. Component contracts

### C-A1 — store history & attribution surface (M1)

Reality anchor: `.harness/tasks/hephaestus/` ticket files plus the `task_*`
verb surface; the append-only history is the substrate property [ARCH]
assigns to M1, amended 2026-11-15. The amendment changed no store
mechanics — it settled which evidence consumers may rely on. Contracts
(recorded behavior, engine-owned substrate):

- `append(event) -> HistoryEvent` — internal to every `task_*` verb write.
  Invariants: history is append-only (no rewrite, no delete, ordered by
  append time); attribution is **partial by event kind** (§4): claim and
  artifact writes carry the acting identity (`by` present), status
  transitions record only the change (`updated:status`, no `by` — no
  stamped actor) [SYS §Interfaces 1, amended by T-097].
  errors: none at the attribution level — the bare transition shape does
  not accept an actor, so no caller can stamp one; a caller-asserted
  identity is recorded exactly as given (misspellings included) — identity
  quality is a peer-convention matter caught at read time (C-A3's
  decision), never a store rejection (DS-005).
- `readHistory(ticketId) -> HistoryEvent[]` via `task_get` | errors:
  unknown id → not-found; empty history → legal for a freshly created
  ticket.
- `readComments(ticketId) -> Comment[]` via `task_get` | errors: unknown
  id → not-found; empty `comments[]` → legal.
- Ordering guarantee: the ordered event list makes the `in-review` →
  `done` sequence readable as an order relation — the input C-A2's
  decision requires.

**Deliberately absent** (their absence is the amendment's point): no
actor-stamped status transition anywhere in the surface, and no
authentication of any `by` value — identities are caller-asserted peer
conventions [SYS §Interfaces 1]; the design adds no identity-verification
mechanism (DS-005).

### C-A2 — review-pass evidence marks (M4)

The review pass is the ONLY `done` authority (AD-003); since the
amendment, its attribution evidence is exactly what it *leaves on the
ticket*. Contracts (the calling conventions of the existing verb surface —
no new verb, no new edge; the mark-2 write rides the store's comment verb
on the single M4 → M1 edge, which [SYS §Interfaces 1] names as the mark's
carrier):

- `recordVerdictComment(ticketId, verdict, by: ReviewPassIdentity) -> void`
  via `task_add_comment` — writes mark-2: an engine-verdict comment in the
  ticket's `comments[]`, observed practice on every PASS flip since
  2026-11-15 (observed identities: `review`, `ticker-v:review`).
  errors: `by` outside the observed review-pass identity set → the comment
  is recorded as a plain comment and is **not a mark** (the decision will
  not count it); write failure → the `done` flip must not proceed
  unmarked on or after the cutoff (the mitigation rule below).
- `flipDone(ticketId, annotation?) -> done` via the store's `done`
  transition — mark-1 when called with the engine annotation (observed
  2026-11-14 shape: `done (engine review pass: …)` by `review`); a bare
  `updated:status` when called without, in which case mark-2 must already
  exist within the review batch around the flip.
  errors: none of M2/M3 can reach this contract at all — the transition is
  structurally unreachable for ticker and worker (denied-edge rule
  [ARCH §Dependency rules; AD-003]).
- **The mitigation rule** ([SYS §Interfaces 1] as amended; this design
  operationalizes it, it does not restate it as new policy): every PASS
  `done` flip on or after 2026-11-15 carries at least one of the two
  marks. Standing practice since the cutoff: mark-2 on every PASS flip.
- `decideEngineAttribution(ticketId) -> Decision` — the read-time decision
  procedure (W-3): pure read over M1 alone (E3 reads; no side channel —
  QA-1 [ARCH §Key quality attributes]).
  errors: a PASS `done` on or after the cutoff with neither mark →
  `undecidable` → fails D-3 [SYS §Interfaces 1; REQ §D-3] — surfaced as
  the review pass's own contract violation by paired verification and
  audit, never silently passed.

### C-A3 — done-flip evidence flow (M3 → M4 across M1)

The end-to-end flow contract — the amended D-1 stop and D-3 flip as one
evidence chain ([ARCH §D-point evidence flows], amended; [SYS §End-to-end
data flows]):

- **Worker side (M3, edge E2 only)** — `finish(ticketId) -> in-review`,
  the write sequence in order:
  1. produce the stage product;
  2. `task_add_artifact` — actor-stamped event (the product's provenance);
  3. `task_add_comment` — the **fulfillment comment**, the worker's
     identity evidence for its flip (DS-006);
  4. `task_update` → `in-review` — a **bare** transition; the worker's
     LAST write.
  errors: the fulfillment comment omitted → the flip's acting identity is
  unevidenced (D-1 evidence incomplete [SYS §data flows D-1]) → the
  reviewer's doubt path (UNCLEAR round-trip; rework through the store);
  an attempted `done` → structurally unavailable — the worker's write
  surface is limited to its own ticket's flips to `in-review`
  [FR-017/FR-021; AD-003].
- **Verdict side (M4, edges E3 + E7)** — review the ticket + artifacts +
  history on store evidence, run the central quality gate on the shared
  tree, then mark and flip per C-A2, under the **required ordering**: the
  history carries `in-review` strictly before `done`; the marks are read
  *together with* that ordering [SYS §Interfaces 1].
- **Decidability invariant** (the flow's whole point): reading M1 alone,
  the chain `claim(by worker) → artifact(by worker) → fulfillment
  comment(by worker) → in-review(bare) → [verdict comment(by
  review-pass identity) within the batch] → done(marked, or
  grandfathered)` decides D-1 and D-3 end-to-end with no stamped
  transition actor anywhere and no worker/ticker `done` event in the
  chain.
- **Grandfathering** (DS-001): a `done` before 2026-11-15 without either
  mark is classified `grandfathered-legacy` at read time (W-4) — it does
  not fail D-3; it is never "repaired" by back-filled marks.

## 3. Design patterns used

- **Shared Repository / blackboard** — chosen upstream (AD-001);
  pass-through, not re-chosen here. Every contract above consumes it.
- **None introduced.** The attribution decision (W-3) is a plain pure
  function over store evidence; the flow contracts are calling
  conventions over existing verbs. A pattern here would be weight, not
  design — the amendment's whole point was to remove an unfounded
  promise, not to add machinery.

## 4. Data structures

- `HistoryEvent {ts, action, by?, detail?}` — one append-only history
  entry. Attribution by kind (the partial-attribution contract):
  `claim` / artifact-add → `by` present (actor-stamped, caller-asserted);
  status transition → `by` absent (`updated:status` only); annotated
  `done` → mark-1 (the annotation plus a review-pass `by` — the observed
  exception shape). Invariants: append-only; ordered by `ts`.
- `Comment {ts, by, text}` — one `comments[]` entry; `by` caller-asserted.
- `ReviewPassIdentity` — a caller-asserted label observed to belong to
  the engine review pass; observed set: {`review`, `ticker-v:review`}
  [SYS §Interfaces 1]. The set is observed data, not a design-fixed
  registry (§9).
- `ReviewBatch {flipTs, before, after}` — the window around the
  `in-review` → `done` flip in which a mark-2 comment lands; observed
  calibration: `before = 51 ms`, `after ≈ 17 min` [SYS §Interfaces 1].
  Property: a comment outside the window is not a mark — which makes
  retrofitting a verdict comment onto an old done structurally
  ineffective (DS-003).
- `CutoffDate = 2026-11-15` — the mitigation date; the grandfathering
  boundary, applied to the `done` event's timestamp.
- `Decision ∈ {engine-set, grandfathered-legacy, undecidable, no-done}` —
  the only output of `decideEngineAttribution`; `no-done` = no `done`
  event (ticket still in flight; no attribution question).
- `FulfillmentChain` — the D-1 evidence shape: `[claim(stamped),
  artifact(stamped), fulfillment-comment, in-review(bare)]` on a ticket
  whose `stage:` never moved; the prefix of C-A3's full chain.

## 5. Key workflows

Only the flows the amendment touches; every step cites the edge it rides.

**W-1 — Amended fulfillment (D-1)** — ticker claims + spawns (E1/E4,
unchanged), then the worker's C-A3 write sequence (E2 only):
artifact (stamped) → fulfillment comment → bare `in-review` flip.
Evidence: stamped claim and artifact events, the worker's identity in the
adjacent comment, a bare flip, `stage:` immutable, **no `done` anywhere**
[SYS §data flows D-1; REQ §D-1]. Upheld: E2, the M2/M3 → `done` denial,
AD-003.

**W-2 — Amended verdict (D-3)** — M4 reads ticket + artifacts + history
(E3), runs the gate on the shared tree (E7), then: `recordVerdictComment`
(mark-2, within the batch around the flip) and/or the annotated `done`
(mark-1), then `flipDone` — the ordering `in-review` strictly before
`done` holds by construction of the sequence. Evidence: the marked done
and/or the in-batch verdict comment; no worker/ticker `done` event
[SYS §data flows D-3; REQ §D-3]. Upheld: E3, E7, AD-003, QA-1.

**W-3 — The attribution decision** (read-time; any reviewer reading M1
alone — DS-002):

1. `readHistory` — locate the `done` event; none → `no-done` (stop).
2. Ordering precondition: a preceding `in-review` event exists in the
   ordered history; without it the marks cannot be read "together with
   the ordering" → `undecidable` (flagged as a store-integrity anomaly
   for reviewer attention).
3. Mark check: mark-1 (the done event itself annotated, `by` a
   review-pass identity) **or** mark-2 (a comment whose `by` is a
   review-pass identity and whose `ts` falls inside the `ReviewBatch`
   around the flip).
4. Either mark present → `engine-set`.
5. Neither mark, and the `done` `ts` < `CutoffDate` →
   `grandfathered-legacy` (W-4).
6. Otherwise (done on or after the cutoff, no mark) → `undecidable` →
   **fails D-3** [SYS §Interfaces 1].

Upheld: E3 reads only; QA-1 (decidable from store evidence alone, no side
channel).

**W-4 — Legacy audit (grandfathering)** — a pre-cutoff `done` without
marks classifies `grandfathered-legacy`; **no write follows** (DS-001):
append-only history forbids rewriting the event, and the review batch
window makes a retroactively added verdict comment fall outside it — so a
late comment is never counted as a mark. Grandfathering is a read-time
classification only; the store's write surface is untouched. Upheld:
C-A1 append-only invariant; DS-001; DS-003.

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| Worker or ticker attempts `done` | structurally unavailable — denied edge; the transition is reachable only from the engine review context (AD-003) |
| Worker flips `in-review` without the fulfillment comment | flip's acting identity unevidenced (D-1 incomplete) → reviewer doubt (UNCLEAR) → rework through the store |
| PASS `done` on/after 2026-11-15 with neither mark | `undecidable` → fails D-3 — the review pass's own contract violation, caught by paired verification / audit, never silently passed |
| Verdict comment by a non-review-pass identity | recorded as a plain comment; **not a mark**; decision falls through to mark-1 / cutoff rule |
| Verdict comment outside the review batch (incl. retrofits) | not a mark (DS-003) — grandfathering cannot be "upgraded" after the fact |
| `done` with no preceding `in-review` | ordering precondition unmet → `undecidable`; flagged as a store-integrity anomaly |
| Misspelled caller-asserted identity | recorded as given; fails the mark check at read time; corrected by a peer comment — never a store edit (DS-005) |
| Pre-cutoff `done` without marks | `grandfathered-legacy` — does not fail D-3; no action, no back-filled marks (DS-001) |

## 7. Dependency-rule compliance map

Every edge and denial of [ARCH §Dependency rules] against the contract
that rides it:

| Edge / denial | Upheld by |
|---|---|
| E1 (M2 → M1) | untouched pass-through — the ticker's verbs produce bare transitions under C-A1's general contract; the ticker never accepts, so it has no mark surface |
| E2 (M3 → M1) | C-A3 worker side — the fulfillment write sequence (artifact, comment, bare flip) rides E2's exact verb list; nothing outside it |
| E3 (M4 → M1) | C-A2 + C-A3 verdict side — reads, the `done` transition, and the mark-2 comment write, all on the single M4 → M1 edge; no new edge, no new verb (the comment verb is the mark's named carrier per [SYS §Interfaces 1]) |
| E4 (M2 → M3 spawn) | untouched pass-through — the brief is unchanged by the amendment |
| E5 / E6 (skills) | untouched pass-through — the tick and fulfillment procedures are unchanged; only the evidence contracts move |
| E7 (M4 → repo tree) | untouched pass-through — the gate remains M4's input only |
| M2/M3 → `done` denied | C-A2 `flipDone` unreachable for M2/M3; C-A3's error branch |
| Any actor → store files directly denied | no contract reads or writes store files except through the verbs; W-3/W-4 are read-time only |
| M1 → anything denied | C-A1 has no callbacks — discovery is by read at the tick boundary; the decision is pulled by consumers, never pushed |

## 8. Traceability matrix (T-111 AC-2)

Every amended architecture element ([ARCH] as amended 2026-11-15; source
of truth [SYS §Interfaces 1]) → its design resolution here:

| Amended element | Design resolution |
|---|---|
| Partial actor attribution (claim/artifact stamped; status transitions bare; identities caller-asserted) | C-A1 contracts; `HistoryEvent` attribution-by-kind (§4); DS-005 |
| Engine mark shape 1 — annotated done event | C-A2 `flipDone(annotation)`; mark-1 (§2); DS-004 |
| Engine mark shape 2 — engine-verdict comment by a review-pass identity within the review batch | C-A2 `recordVerdictComment`; `ReviewBatch` (§4); DS-003 |
| Required `in-review` → `done` ordering | C-A3 ordering invariant; W-3 step 2 (the precondition the marks are read together with) |
| Grandfathering (pre-2026-11-15 dones) | W-4; `Decision.grandfathered-legacy` + `CutoffDate` (§4); DS-001 |
| D-1 evidence chain (worker identity via the adjacent comment) | C-A3 worker side; `FulfillmentChain` (§4); DS-006; W-1 |
| D-3 evidence chain (bare flip → marked done, no worker/ticker done) | C-A3 verdict side; W-2; W-3 |
| Amendment touches nothing else (no invented parallel structure) | §1 explicit pass-throughs: M2, M5, E4–E7, D-2, the gate, claims/readiness/division (§7) |

**Consistency with T-097's settled re-specification** ([SYS §Interfaces
1]): no contract above introduces a stamped transition actor, an
authenticated identity, a third mark shape, a wider mark carrier, a
write-based grandfathering path, or any new edge or verb — the five
settled facts (partial attribution, two mark shapes, ordering, cutoff,
grandfathering) are each consumed exactly as [SYS] states them, cited
inline at their point of use.

## 9. Open items (calibration, not structure)

Two inputs of the decision are **observed calibrations**, carried as data
([SYS]-observed values), not design-fixed constants: the `ReviewBatch`
window bounds (51 ms / ~17 min) and the `ReviewPassIdentity` set
(`review`, `ticker-v:review`). If the owner later fixes canonical values
or a new review-pass label appears, that changes `Decision` inputs only —
no contract shape changes (mirroring how [ARCH §Open questions] treats
Q-001/Q-002 as cost knobs, never structure).

## 10. Acceptance criteria mapping (T-111)

- **AC-1** (design artifact with the docs/ convention About header;
  component contracts for every amendment-touched component with
  inputs/outputs/error behavior named; workflows consistent with the
  architecture's dependency edges): this document — the header above;
  §2 contracts in `name(inputs) -> output | errors` form; §4 data
  structures; §5 workflows, each step citing its edge; §6 error behavior;
  §7 the edge-compliance map.
- **AC-2** (every amended element — partial attribution, both mark
  shapes, ordering, grandfathering — resolves to a component contract; no
  contradiction with T-097): §8 traceability matrix with the consistency
  paragraph.
- **AC-3** (artifact recorded on the ticket before in-review; done never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`)
  written before the `in-review` status update; `done` is the engine's
  verdict.

## 11. Lane and non-goals

This ticket's lane is one new doc file
(`docs/acceptance-run-design-amended.md`). Not touched:
`docs/acceptance-run-design.md` (T-095's lane — consulted read-only,
never edited), `docs/acceptance-run-architecture.md`,
`docs/acceptance-run-system.md`, `docs/design-stage-pipeline.md`, any
code, any skill, any store file. No concrete provider or model ids
anywhere (D-004). The contracts are specified, not scripted; component
*realization* belongs to the implementation stage; the contracts'
verification belongs to the paired `test-design` stage. Downstream
hand-off: implementation derives from §2/§4; `test-design` tests against
the §2 contracts, the §6 error table, and the W-3 decision procedure.
