# Component criteria: the amended attribution contracts (U-111) — criteria & verdict (T-112)

## About this document

- **Kind:** `doc` / test-design-stage verification artifact (V stage:
  `test-design`; ticket T-112, epic U-111, basis T-098): the PAIRED
  VERIFICATION of ticket **T-111**'s design artifact — the design child of
  T-098, the amendment's component-contract stage. It derives component
  criteria **1:1 from T-111's acceptance criteria**, gives each an
  observable oracle — the design doc's own sections are the source of truth
  for *what the design decides*; the governing definitions are the source
  of truth for *whether it is correct and complete* — and records the
  verdict with evidence that decides T-111's done/not-done contribution
  (mirrored as a comment on ticket T-112; this file carries the criteria
  and the evidence).
- **Read by:** the engine review pass (weighs the verdict; alone sets
  `done` on T-111, D-3), the ticker-v stage ticker, the maintainer.
- **Related:** the artifact under verification
  `docs/acceptance-run-design-amended.md` (T-111 — in-review; its section
  numbers are cited below as [DSN]); governing definitions
  `docs/acceptance-run-system.md` (T-097 — the settled attribution
  re-specification, the source of truth for the no-contradiction check;
  cited [SYS]); `docs/acceptance-run-architecture.md` (T-098's product —
  the architecture as amended 2026-11-15; cited [ARCH]);
  `docs/design-stage-pipeline.md` (the U-110 design — the
  no-invented-parallel-structure reference; cited [DSG]); sibling records
  `docs/acceptance-run-design.tests.md` (T-096 — the same method over the
  un-amended sibling design) and `docs/intake-seat-architecture.tests.md`
  (shape reference); tickets T-111 (under verification) and T-112 (this
  record's ticket).

## Scope: what "component level" verifies here

The acceptance run's modules are modules of a **process/agent
architecture** ([ARCH §Scope]) anchored in store, skill, schedule and
subagent surfaces — so its component level is the design INSIDE each
module, not code. Layer separation against the neighbouring levels:

- **Library level:** A-004's Part II
  (`docs/acceptance-run-architecture.tests.md`) verified the module set
  M1–M5 and the default-deny edge table E1–E7 against the live store;
  T-099 (test-architecture, in-review) re-verifies the amended wording.
  This record does not re-verify the edges; it reads them as fixed
  structure.
- **Component level (this record):** per T-112's own ticket description —
  "the design artifact's contracts are complete and consistent: each
  amendment-touched contract names inputs/outputs/error behavior; each
  workflow respects the architecture's dependency rules; no contract
  contradicts T-097's settled attribution re-specification; no invented
  parallel structure vs docs/design-stage-pipeline.md". T-111's scope
  (ticket description) names the substance: component contracts for
  exactly the amendment-touched surfaces — C-A1 history & attribution
  (M1), C-A2 review-pass evidence marks (M4), C-A3 done-flip evidence
  flow (M3 → M4 across M1) — plus the workflows and data structures those
  contracts consume ([DSN §1–§4]).
- **Un-amended machinery:** its component level is T-096's record
  (`docs/acceptance-run-design.tests.md`); this record covers only the
  amendment's delta — the two design docs compose without overlap by
  [DSN §1]'s own pass-through mapping.

**Pair state:** unlike T-096 (criteria minted while its design was still
in flight, verdict pending), T-111 completed fulfillment before this
record was written — the artifact landed 2026-11-16T08:28:57Z and T-111
sits in-review. The criteria below are therefore decided **in the same
motion they are stated**: each criterion's evidence is located in the
landed doc, and the Verdict section fixes the overall result. The decision
protocol is unchanged — only its application was immediate.

## Method and verdict semantics

Static documentation-level verification (the sibling method): every
criterion is decided by reading [DSN] — located by its own sections — and
checked against the governing section named in the oracle. No store writes
are needed beyond this record's own bookkeeping. Verdict semantics:
**PASS/FAIL per criterion** with the evidence located or quoted; the
overall verdict (**APPROVE / APPROVE-WITH-NOTES / REJECT**) decides
T-111's done/not-done contribution and is **advisory to the engine review
pass**, which alone sets `done` (D-3). T-112 sets no status on T-111; a
REJECT is a failed validation that blocks T-111 in its own stage and
escalates to its basis T-098 per the routing rule.

## Criterion derivation (1:1 from T-111's ACs)

| T-111 AC (verbatim concern) | Criteria |
|---|---|
| AC-1 "design artifact exists (docs/ convention About header) with component contracts for every amendment-touched component: inputs/outputs/error behavior named, workflows consistent with the architecture's dependency edges" | CT-A-001 (exists at the recorded path + About header), CT-A-002 (contracts complete: all three faces on every amendment-touched component), CT-A-003 (workflows respect the dependency edges) |
| AC-2 "every amended architecture element (partial attribution, both engine-mark shapes, in-review→done ordering, grandfathering) resolves to a component contract; no contract contradicts T-097's settled re-specification" | CT-A-004 (partial attribution), CT-A-005 (both engine-mark shapes), CT-A-006 (in-review→done ordering), CT-A-007 (grandfathering), CT-A-008 (no T-097 contradiction — the consistency sweep), CT-A-009 (no invented parallel structure vs [DSG]; also T-111's ticket-description scope clause) |
| AC-3 "the artifact is recorded on this ticket (doc) and the ticket is in-review; done is never self-assessed" | CT-A-010 (artifact-before-review store ordering; engine-only done) |

Every criterion traces to exactly one AC concern (CT-A-009 additionally to
T-111's ticket-description scope clause — the same source [DSN §10]'s own
AC mapping claims via its §8 last row); no criterion verifies structure
outside T-111's ACs and ticket description.

## Component criteria

### CT-A-001 · The design artifact exists at the recorded path with the repo doc header (T-111 AC-1)

- **Statement:** T-111 records exactly one stage-product artifact — the
  amended-attribution design doc — and the doc carries the repo
  documentation header.
- **Oracle:** (a) the artifact recorded on T-111 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file; (b) its path extends
  the chain convention: the acceptance chain's stage products live at
  `docs/acceptance-run-<stage>.md`, and this design is the amendment's
  delta on the design stage, named `docs/acceptance-run-design-amended.md`
  — the suffix distinguishing it from T-095's `docs/acceptance-run-design.md`
  without breaking the chain naming ([DSN §1, §11]); (c) the file opens
  with an **About this document** section per the
  `project-manager-doc-about` convention — kind, read-by, related, and the
  owning ticket id recorded.
- **PASS:** all three observable.
- **FAIL:** no artifact on T-111 at decision time; the ref does not
  resolve; the About section missing; a chain-breaking path without a
  recorded reason on the ticket.

### CT-A-002 · Contracts complete: inputs, outputs, error behavior — every amendment-touched component (T-111 AC-1)

- **Statement:** every amendment-touched component ([DSN §1]: C-A1 store
  history & attribution M1; C-A2 review-pass evidence marks M4; C-A3
  done-flip evidence flow M3 → M4 across M1) carries at least one component
  contract in [DSN §2] naming its **inputs, outputs and error behavior**;
  the data structures the contracts consume are designed in [DSN §4].
- **Oracle:** locate the contracts in [DSN §2]; check each for the three
  faces (a contract naming only the happy path is incomplete) and for
  consistency with the governing rows: [ARCH §Modules] M1/M3/M4
  responsibilities, [ARCH §Dependency rules] interface column (E2's verb
  list; E3 reads + `done`), and [SYS §Interfaces 1] as amended by T-097.
  Contracts expected from the amendment's touch list: the
  partial-attribution surface (`append`, `readHistory`, `readComments` +
  the ordering guarantee), the evidence-mark writes (`recordVerdictComment`,
  `flipDone`), the read-time decision (`decideEngineAttribution`), and the
  end-to-end flow contract (the worker-side write sequence, the
  verdict-side marked flip). A contract contradicting the store's
  observable semantics (e.g. a stamped actor on a status transition) FAILS.
- **PASS:** all three components contracted with all three faces; [DSN §4]
  carries the structures they consume (`HistoryEvent`, `Comment`,
  `ReviewPassIdentity`, `ReviewBatch`, `CutoffDate`, `Decision`,
  `FulfillmentChain`); [DSN §6] consolidates the error behavior.
- **FAIL:** a touched component with no contract; a contract missing any
  face; a contract contradicting its basis row or the amended attribution
  semantics.

### CT-A-003 · Every workflow names its boundary and stays within E1–E7; denied edges stay unused (T-111 AC-1)

- **Statement:** every workflow the design states names the module boundary
  it rides and is performable through allowed edges E1–E7; no step requires
  a denied edge.
- **Oracle:** [DSN §5] W-1..W-4 — every step must cite its edge (W-1: E1/E4
  unchanged, the worker sequence on E2 only; W-2: E3 reads, the E7 gate,
  then mark + flip; W-3: E3 reads only; W-4: no write), and [DSN §7] must
  map every edge and denial of [ARCH §Dependency rules] to the contract
  that upholds it. Cross-check step by step: the worker's write sequence
  uses exactly E2's verbs (`task_add_artifact` → `task_add_comment` →
  `task_update` → `in-review`); the mark-2 write rides E3's comment verb —
  the carrier [SYS §Interfaces 1] itself names (the A-004 N-2 boundary note
  applies: consistent, not a violation); the denials stay unused — M2/M3 →
  `done` (W-1 ends at `in-review` with no `done` anywhere; W-2's flip is
  M4's alone), any actor → store files directly (all writes through the
  verbs), M1 → anything (the decision is pulled by consumers, never
  pushed); every step's executor resolves to M1–M5 of [ARCH §Modules].
- **PASS:** every step maps to a listed edge; no denied-edge step anywhere.
- **FAIL:** a step performable only through a denied edge; an unnamed or
  out-of-set executing module; a step using a verb its actor's E-row does
  not list (with the named-carrier exception above).

### CT-A-004 · Partial attribution resolves to a component contract, consistent with T-097 (T-111 AC-2)

- **Statement:** the partial-attribution element resolves to a component
  contract: attribution partial by event kind, identities caller-asserted,
  no stamped transition actor, no authentication mechanism.
- **Oracle:** [DSN §2 C-A1] — `append(event)` invariants: history
  append-only; "attribution is partial by event kind: claim and artifact
  writes carry the acting identity (`by` present), status transitions
  record only the change (`updated:status`, no `by` — no stamped actor)
  [SYS §Interfaces 1, amended by T-097]"; caller-asserted identities
  recorded exactly as given, misspellings caught at read time, never a
  store rejection (DS-005); the deliberately-absent paragraph denying both
  the actor-stamped status transition and any authentication of a `by`
  value. Structures: [DSN §4] `HistoryEvent` attribution-by-kind; [DSN §6]
  misspelled-identity row; [DSN §8] row 1. Against [SYS §Interfaces 1]:
  "claim and artifact writes carry the acting identity; status transitions
  record only the change (`updated:status`, no stamped actor) — identities
  are caller-asserted peer conventions, not authenticated" — the contract
  must consume exactly this, adding no stamp and no verification mechanism.
- **PASS:** the element resolves to C-A1's contract and the §4 structure;
  wording matches [SYS] with T-097 cited at the point of use.
- **FAIL:** a stamped transition actor anywhere; an identity-verification
  mechanism; a claim/artifact event left bare; a contradiction of the
  caller-asserted rule.

### CT-A-005 · Both engine-mark shapes resolve to component contracts, consistent with T-097 (T-111 AC-2)

- **Statement:** both mark shapes resolve: mark-1 the engine-annotated done
  event; mark-2 the engine-verdict comment by a review-pass identity within
  the review batch around the flip.
- **Oracle:** mark-1 — [DSN §2 C-A2] `flipDone(ticketId, annotation?)`:
  "mark-1 when called with the engine annotation (observed 2026-11-14
  shape: `done (engine review pass: …)` by `review`)"; [DSN §4]
  `HistoryEvent` annotated-`done` row (mark-1); [DSN §8] row 2 (DS-004).
  Mark-2 — [DSN §2 C-A2] `recordVerdictComment`: an engine-verdict comment
  in the ticket's `comments[]` by a `ReviewPassIdentity` (observed set
  {`review`, `ticker-v:review`}, caller-asserted — [DSN §4]) landing within
  the `ReviewBatch` around the flip (observed calibration `before = 51 ms`,
  `after ≈ 17 min` — [SYS]'s observed span carried as data, not a
  design-fixed constant, per [DSN §9]); [DSN §6] rows (a non-review-pass
  identity → plain comment, not a mark; a comment outside the batch,
  including retrofits → not a mark); [DSN §8] row 3 (DS-003). Against
  [SYS §Interfaces 1]: marks "(1) an engine-annotated done event … or (2)
  an engine-verdict comment in the ticket's own `comments[]` by a
  review-pass identity … within the review batch around the flip" — both
  shapes, the identity set and the window must match; the design adds no
  third mark and no wider carrier ([DSN §2 C-A2] preamble: "no new verb,
  no new edge").
- **PASS:** both shapes resolve, each to its contract; identities, window
  and observed shapes match [SYS] exactly.
- **FAIL:** a missing shape; a third mark; a wider carrier; a design-fixed
  identity registry or window constant presented as structure.

### CT-A-006 · The required in-review→done ordering resolves to a component contract, consistent with T-097 (T-111 AC-2)

- **Statement:** the ordering element resolves to a contract: the history
  carries `in-review` strictly before `done`, and the marks are read
  together with that ordering.
- **Oracle:** [DSN §2 C-A3] verdict side — "the required ordering: the
  history carries `in-review` strictly before `done`; the marks are read
  together with that ordering [SYS §Interfaces 1]"; [DSN §5] W-2 (the
  ordering "holds by construction of the sequence") and W-3 step 2 (the
  ordering precondition: without a preceding `in-review` the marks cannot
  be read together with the ordering → `undecidable`, flagged as a
  store-integrity anomaly); [DSN §4] `FulfillmentChain` (the D-1 prefix
  ending at the bare `in-review`); [DSN §6] row (a `done` with no preceding
  `in-review` → `undecidable`); [DSN §8] row 4. Against [SYS §Interfaces
  1] ("read together with the `in-review` → `done` ordering") and the [SYS
  §D-point map] D-3 row: exact consumption; ordering required for every
  post-cutoff done.
- **PASS:** the ordering resolves to C-A3's invariant and W-3's
  precondition; the undecidable-on-missing-ordering behavior matches [SYS].
- **FAIL:** marks decoupled from the ordering; an ordering violation that
  still yields `engine-set`; a missing precondition.

### CT-A-007 · Grandfathering resolves to a component contract, consistent with T-097 (T-111 AC-2)

- **Statement:** the grandfathering element resolves to a contract:
  pre-2026-11-15 dones without marks classify `grandfathered-legacy` at
  read time and do not fail D-3; no write path exists.
- **Oracle:** [DSN §2 C-A3] grandfathering (DS-001) — a `done` before
  2026-11-15 without either mark is classified `grandfathered-legacy` at
  read time (W-4), never repaired by back-filled marks; [DSN §4]
  `CutoffDate = 2026-11-15` applied to the `done` event's timestamp, and
  `Decision.grandfathered-legacy`; [DSN §5] W-4 — no write follows
  (append-only history forbids rewriting the event, and the review batch
  window makes a retroactively added verdict comment fall outside it, so a
  late comment is never counted as a mark — DS-003); [DSN §6] rows (a
  pre-cutoff `done` without marks → `grandfathered-legacy`; a comment
  outside the batch including retrofits → not a mark); [DSN §8] row 5.
  Against [SYS §Interfaces 1]: "dones before that date without a mark are
  grandfathered (pre-mitigation legacy)" — grandfathering must be
  read-time classification only, with no write path and no back-fill
  mechanism.
- **PASS:** the element resolves to W-4 and the §4 fields; read-time-only
  semantics match [SYS].
- **FAIL:** a write-based grandfathering or back-fill path; a pre-cutoff
  unmarked done classified `undecidable`; a cutoff applied to anything but
  the `done` event's timestamp.

### CT-A-008 · No contract contradicts T-097's settled re-specification (T-111 AC-2)

- **Statement:** the five settled facts (partial attribution, exactly two
  mark shapes, the required ordering, the cutoff, grandfathering) are
  consumed exactly as [SYS §Interfaces 1] states them, and the design
  introduces none of the structures the settlement removed.
- **Oracle:** positive — each of the five facts carries [SYS §Interfaces
  1, amended by T-097] cited at its point of use ([DSN §2] all three
  components; [DSN §8]'s closing consistency paragraph: "no contract above
  introduces a stamped transition actor, an authenticated identity, a
  third mark shape, a wider mark carrier, a write-based grandfathering
  path, or any new edge or verb"). Negative sweep over the whole doc: a
  stamped transition actor; an authenticated or verified identity (DS-005
  must add no mechanism); a third mark shape; a wider mark carrier than
  the comment verb [SYS] names; a write-based grandfathering path; any new
  edge or verb ([DSN §2 C-A2] preamble; [DSN §7] E3 row). Mechanical
  decision-time check: a text sweep over the file — the only occurrences of
  "authenticated", "third mark", "registry", "stamped transition",
  "engine actor" must be the doc's own negations, and every "stamp"
  occurrence must be the true claim/artifact stamping or its explicit
  negation; a settled fact silently re-decided (e.g. the observed window or
  identity set hardened into design-fixed structure, contradicting [SYS]'s
  observed-data framing and [DSN §9]'s calibration stance) FAILS.
- **PASS:** five facts consumed verbatim-consistent; zero reintroduction
  markers.
- **FAIL:** any reintroduction marker; a settled fact silently re-decided.

### CT-A-009 · No invented parallel structure vs docs/design-stage-pipeline.md (T-111 AC-2; ticket-description scope clause)

- **Statement:** wherever [DSN] relies on structure [DSG] already fixes,
  it records the pass-through instead of re-deciding it; the amendment adds
  no parallel machine construct.
- **Oracle:** positive — [DSN §1] records the explicit pass-throughs (M2
  ticker, M5 process, the spawn cap/lane serialization, the quality gate
  E7, artifact carrying, claims, readiness, division/linking, D-2's flow —
  designed in [DSG §1–§8] and the sibling T-095 doc; "this doc adds
  nothing there"), plus the store's verb mechanics as engine-owned
  substrate (AD-001 pass-through); [DSN §3] records "None introduced" for
  patterns; [DSN §7] carries the untouched pass-through rows (E4/E5/E6/E7);
  [DSN §9] carries the calibration inputs as data, "no contract shape
  changes". Negative sweep: a new ticket field or a changed status walk; a
  modified tick step order; a link relation beyond `epic`/`basis`; a second
  `done` authority (the review pass must stay the ONLY one — [DSN §2 C-A2],
  AD-003 pass-through); a second common skill or per-stage copies; a
  replacement brief/cap/admission mechanism; any new machine construct the
  settlement or [DSG] does not name.
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers.
- **FAIL:** any re-decision marker; described-but-not-mapped reliance on
  DSG-fixed structure; a contradiction of [ARCH §Pass-through]'s recorded
  mappings.

### CT-A-010 · Artifact recorded before the in-review flip; done never self-assessed (T-111 AC-3)

- **Statement:** the fulfillment order holds on the ticket record — the
  store-side proof of D-1 for this pair (the ITS-006 pattern, as applied by
  the sibling CT-D-007).
- **Oracle:** T-111's history: an `artifact:doc:docs/acceptance-run-design-amended.md`
  event timestamped **before** the bare `updated:status` → in-review flip;
  the worker's fulfillment comment adjacent to the flip (identity evidenced
  by the comment per the amended rule, [SYS §Interfaces 1]) naming the
  stage product; no `done` event inside the fulfillment window (denied
  edge, [ARCH AD-003]); T-112 sets no status on T-111.
- **PASS:** artifact event precedes the flip; fulfillment comment adjacent;
  window ends at in-review.
- **FAIL:** flip before artifact; no fulfillment comment; any self-set
  `done`.

## Observed evidence

Decided 2026-11-16 against the landed artifact
(`docs/acceptance-run-design-amended.md`, 354 lines, HEAD) and the store
records of T-111 (via `task_get`):

### CT-A-001 — artifact + About header — **PASS**

- T-111's artifact list carries exactly one entry: `kind: doc`, `ref:
  docs/acceptance-run-design-amended.md`, recorded 2026-11-16T08:28:57.295Z
  (by `ticker-v:T-111`); the file exists at that path.
- The **About this document** section is present ([DSN] lines 3–25): Kind
  (design-stage artifact of ticket T-111, epic U-111, basis T-098, stage
  `design`), Read by (implementation, test-design, engine review pass,
  reviewer), Related ([ARCH], [SYS], [DSG], T-095's read-only neighbour,
  [REQ]) — the owning ticket id recorded. The path extends the chain
  convention with the `-amended` suffix for the amendment's delta design,
  distinct from T-095's `docs/acceptance-run-design.md`.

### CT-A-002 — contracts complete on every touched component — **PASS**

- C-A1 (M1): `append(event) -> HistoryEvent` with invariants and the
  no-rejection error note; `readHistory(ticketId) -> HistoryEvent[]`
  (errors: unknown id → not-found; empty history legal); `readComments`
  (errors: not-found; empty legal); plus the ordering guarantee the
  decision consumes ([DSN §2 C-A1]).
- C-A2 (M4): `recordVerdictComment(ticketId, verdict, by) -> void`
  (errors: `by` outside the review-pass identity set → recorded but not a
  mark; write failure → the mitigation rule); `flipDone(ticketId,
  annotation?) -> done` (errors: structurally unreachable for M2/M3);
  `decideEngineAttribution(ticketId) -> Decision` (errors: neither mark
  on/after the cutoff → `undecidable` → fails D-3) ([DSN §2 C-A2]).
- C-A3 (M3 → M4 across M1): the worker-side `finish(ticketId) ->
  in-review` sequence — four ordered steps, each with its error branch
  (fulfillment comment omitted → D-1 incomplete → doubt path; attempted
  `done` → structurally unavailable) — and the verdict-side chain under
  the required ordering ([DSN §2 C-A3]).
- [DSN §4] designs every structure the contracts consume; [DSN §6]
  consolidates eight error rows. Consistent with [ARCH §Modules] M1/M3/M4,
  E2's verb list, E3, and [SYS §Interfaces 1].

### CT-A-003 — workflows within E1–E7 — **PASS**

- Every step of W-1..W-4 names its edge ([DSN §5]): W-1 rides E2 only
  ("no `done` anywhere"); W-2 rides E3 reads + the E7 gate, then mark +
  flip; W-3 is E3-reads-only; W-4 writes nothing. [DSN §7] maps all seven
  edges and every denial row to its upholding contract.
- Cross-check vs [ARCH §Dependency rules]: the worker sequence uses
  exactly E2's verbs (`task_add_artifact`, `task_add_comment`,
  `task_update` → `in-review`); the mark-2 write rides E3's comment verb —
  the carrier [SYS §Interfaces 1] names (A-004 N-2 exception: consistent,
  not a violation); no step requires M2/M3 → `done`, a direct store-file
  write, or any M1-initiated edge. Every step's executor resolves to
  M1–M5.

### CT-A-004 — partial attribution — **PASS**

- [DSN §2 C-A1]: "attribution is partial by event kind: claim and artifact
  writes carry the acting identity (`by` present), status transitions
  record only the change (`updated:status`, no `by` — no stamped actor)
  [SYS §Interfaces 1, amended by T-097]"; identities recorded exactly as
  given, misspellings caught at read time (DS-005, never a store
  rejection); the deliberately-absent paragraph denies both the
  actor-stamped status transition and any authentication of a `by` value.
- [DSN §4] `HistoryEvent` attribution-by-kind; [DSN §6] misspelled-identity
  row; [DSN §8] row 1. Word-for-word consumption of [SYS §Interfaces 1]'s
  partial-attribution sentence, T-097 cited at the point of use; no stamp
  and no verification mechanism added.

### CT-A-005 — both mark shapes — **PASS**

- Mark-1: [DSN §2 C-A2] `flipDone(ticketId, annotation?)` — "mark-1 when
  called with the engine annotation (observed 2026-11-14 shape: `done
  (engine review pass: …)` by `review`)"; [DSN §4] `HistoryEvent`
  annotated-`done` row; [DSN §8] row 2.
- Mark-2: [DSN §2 C-A2] `recordVerdictComment` — mark-2 via
  `task_add_comment`, "observed practice on every PASS flip since
  2026-11-15 (observed identities: `review`, `ticker-v:review`)"; [DSN §4]
  `ReviewPassIdentity` (observed set, "not a design-fixed registry") and
  `ReviewBatch` (`before = 51 ms`, `after ≈ 17 min` — [SYS]'s observed span
  carried as data per [DSN §9]); [DSN §6] rows (non-review-pass identity →
  plain comment, not a mark; outside the batch including retrofits → not a
  mark); [DSN §8] row 3.
- Both shapes, the identity set and the window match [SYS §Interfaces 1]
  exactly; no third mark, no new carrier ([DSN §2 C-A2] preamble: "no new
  verb, no new edge").

### CT-A-006 — in-review→done ordering — **PASS**

- [DSN §2 C-A3] verdict side: "the required ordering: the history carries
  `in-review` strictly before `done`; the marks are read together with
  that ordering [SYS §Interfaces 1]".
- [DSN §5] W-2 (the ordering "holds by construction of the sequence") and
  W-3 step 2 (ordering precondition; a missing preceding `in-review` →
  `undecidable` + store-integrity anomaly flag); [DSN §4]
  `FulfillmentChain` ends at the bare `in-review`; [DSN §6] row (a `done`
  with no preceding `in-review` → `undecidable`); [DSN §8] row 4. Matches
  [SYS]'s "read together with the ordering" and its D-3 evidence row.

### CT-A-007 — grandfathering — **PASS**

- [DSN §2 C-A3] grandfathering (DS-001): a pre-cutoff `done` without
  either mark is classified `grandfathered-legacy` at read time (W-4) and
  is never repaired by back-filled marks.
- [DSN §4] `CutoffDate = 2026-11-15` applied to the `done` event's
  timestamp; `Decision.grandfathered-legacy`; [DSN §5] W-4 — no write
  follows (append-only history plus the batch window make retrofit marking
  structurally ineffective — DS-003); [DSN §6] rows; [DSN §8] row 5.
  Read-time-only classification, exactly [SYS]'s grandfather rule, with no
  write path.

### CT-A-008 — no T-097 contradiction — **PASS**

- The five settled facts each consume [SYS §Interfaces 1, amended by
  T-097] cited at the point of use (CT-A-004..007 evidence above), and
  [DSN §8]'s closing consistency paragraph states the negative explicitly:
  "no contract above introduces a stamped transition actor, an
  authenticated identity, a third mark shape, a wider mark carrier, a
  write-based grandfathering path, or any new edge or verb".
- Independent sweep at decision time (grep over the file): "authenticated",
  "third mark", "registry", "stamped transition", "engine actor" occur
  only as the doc's own negations ([DSN §2 C-A1] deliberately-absent
  paragraph; [DSN §4] "not a design-fixed registry"; [DSN §7] E3 row; [DSN
  §8] consistency paragraph); every "stamp" occurrence is the true
  claim/artifact stamping or its negation; zero reintroduction markers
  found; the window and identity set stay calibration data ([DSN §9]), not
  re-decided structure.

### CT-A-009 — no invented parallel structure vs [DSG] — **PASS**

- Positive: [DSN §1] records the explicit pass-throughs (M2, M5, the spawn
  cap/lane serialization, the gate E7, artifact carrying, claims,
  readiness, division/linking, D-2's flow — designed in [DSG §1–§8] and
  the sibling T-095 doc; "this doc adds nothing there"), plus the verb
  mechanics as engine-owned substrate (AD-001 pass-through); [DSN §3]
  "None introduced"; [DSN §7] rows E4/E5/E6/E7 "untouched pass-through";
  [DSN §9] carries the window and identity set as calibration data ("no
  contract shape changes").
- Negative sweep: no new ticket field, no changed status walk, no
  tick-order edit, no link relation beyond `epic`/`basis`, no second
  `done` authority ([DSN §2 C-A2]: "The review pass is the ONLY `done`
  authority (AD-003)"), no second common skill, no new admission
  mechanism, no new machine construct anywhere in the doc.

### CT-A-010 — artifact-before-review store discipline — **PASS**

- T-111 history (via `task_get`): the artifact event
  `artifact:doc:docs/acceptance-run-design-amended.md` at
  2026-11-16T08:28:57.295Z (by `ticker-v:T-111`) precedes the bare
  `updated:status` in-review flip at 08:28:57.364Z by 69 ms; the
  fulfillment comment by `ticker-v:T-111` lands at 08:29:19.576Z (+22 s —
  inside the review batch) and names the stage product and its lane; no
  `done` event exists in the window — T-111 sits in-review.
- T-112 sets no status on T-111; `done` is left to the engine's verdict
  (D-3).

## Verdict

| ID | Criterion | Result |
|---|---|---|
| CT-A-001 | artifact exists + About header | **PASS** |
| CT-A-002 | contracts complete (three faces, all touched components) | **PASS** |
| CT-A-003 | workflows within E1–E7 | **PASS** |
| CT-A-004 | partial attribution resolves, T-097-consistent | **PASS** |
| CT-A-005 | both mark shapes resolve, T-097-consistent | **PASS** |
| CT-A-006 | ordering resolves, T-097-consistent | **PASS** |
| CT-A-007 | grandfathering resolves, T-097-consistent | **PASS** |
| CT-A-008 | no contradiction of T-097 | **PASS** |
| CT-A-009 | no invented parallel structure vs [DSG] | **PASS** |
| CT-A-010 | artifact before in-review; done engine-only | **PASS** |

**Overall verdict: APPROVE.** All ten criteria PASS against the landed
artifact; nothing argued around. The design doc's contracts are complete
(all three amendment-touched components with inputs/outputs/error
behavior), every amended element — partial attribution, both engine-mark
shapes, the required in-review→done ordering, grandfathering — resolves to
a component contract consuming [SYS §Interfaces 1] exactly as T-097
settled it, no contradiction and no invented parallel structure was found,
and the store discipline held. This verdict is **advisory evidence for the
engine review pass**, which alone sets `done` on T-111 (D-3); T-112 sets
no status on T-111. (A REJECT would have blocked T-111 in its own stage
and escalated to its basis T-098 — not applicable here.)

Non-blocking note: the observed calibration inputs (the `ReviewBatch`
bounds and the `ReviewPassIdentity` set) remain data pending owner
canonicalization — [DSN §9]'s own open-items stance, mirroring [ARCH §Open
questions]; no action for this pair.

## Lane

One new file (`docs/acceptance-run-design-amended.tests.md`). Read-only on
everything else: the sibling design doc and its `.tests` neighbours, the
architecture/system/pipeline docs, any code, skill or store file —
untouched; no store write besides this ticket's own bookkeeping (artifact +
status + comment). T-111's status untouched; `done` not self-assessed
here — the verdict above is evidence for the engine's decision, not the
decision.
