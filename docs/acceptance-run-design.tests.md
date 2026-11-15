# Component criteria: the acceptance-run design doc (U-111) — criteria & verdict (T-096)

- **Kind:** `doc` / test-design verification artifact (V stage: test-design;
  ticket T-096, epic U-111, basis A-003). The PAIRED VERIFICATION of ticket
  **T-095**'s design artifact: it derives component criteria **1:1 from
  T-095's acceptance criteria**, gives each an observable oracle — the design
  doc's own sections are the source of truth for *what the design decides*,
  the governing definitions are the source of truth for *whether it is
  correct and complete* — and records the verdict that decides T-095's
  done/not-done (mirrored as a comment on ticket T-096; this file carries the
  criteria and the evidence). **Read by:** the engine review pass (weighs the
  verdict; alone sets `done`, D-3), the ticker-v stage ticker, the
  maintainer. **Related:** the artifact under verification
  `docs/acceptance-run-design.md` (T-095 — in flight at minting time; the
  criteria are decidable against it the moment it lands); governing
  definitions `docs/acceptance-run-architecture.md` (A-003's product, as
  amended 2026-11-15 by T-097/T-098 — cited [ARCH]),
  `docs/acceptance-run-system.md` (A-001, as amended by T-097 — cited [SYS]),
  `docs/design-stage-pipeline.md` (the U-110 design — cited [DSG]); sibling
  records `docs/acceptance-run-architecture.tests.md` (Part II — the
  library-level verification this component level sits inside) and
  `docs/live-in-eclipse-recurrence-flow.tests.md` (shape reference); tickets
  T-095 (under verification) and T-096 (this record's ticket).

## Scope: what "component level" verifies here

The acceptance run's modules are modules of a **process/agent architecture**
([ARCH §Scope]) anchored in store, skill, schedule and subagent surfaces —
so its component level is the design INSIDE each module, not code. Layer
separation against the already-verified levels:

- **Library level (done):** A-004's Part II
  (`docs/acceptance-run-architecture.tests.md`) verified the module set
  M1–M5 and the default-deny edge table E1–E7 **against the live store**.
  This record does not re-verify the edges; it reads them as fixed
  structure.
- **Component level (this record):** per T-096's own ticket description —
  "the design artifact's contracts are complete and consistent: each
  component contract names inputs/outputs/error behavior; each workflow
  respects E1–E7; no invented parallel structure vs
  docs/design-stage-pipeline.md". T-095's scope (ticket description) names
  the substance: per-component contracts for M1 store / M2 ticker / M3
  worker / M4 verdict / M5 process, the key workflows (tick procedure,
  claim-spawn-fulfill-verdict-divide cycle, shutdown-mid-pass
  reconciliation), and the data structures (ticket schema usage, sprint
  record lifecycle, history event semantics).

The paired pair runs **concurrently** (T-095 and T-096 are both children of
A-003's division, created 2026-11-15T08:58:10Z, both claimed by ticker-v in
one wave): the criteria below are written to be **decidable against the
artifact T-095 produces**, and the Verdict section fixes the mechanical
decision protocol applied at verdict time.

## Method and verdict semantics

Static documentation-level verification (the sibling method): every
criterion is decided by reading the design doc T-095 records — located by
its own sections, checked against the governing section named in the
oracle. No store writes are needed beyond this record's own bookkeeping.
Verdict semantics: **PASS/FAIL per criterion** with the evidence located or
quoted; the overall verdict (**APPROVE / APPROVE-WITH-NOTES / REJECT**)
decides T-095's done/not-done contribution and is **advisory to the engine
review pass**, which alone sets `done` (D-3). T-096 sets no status on
T-095; a REJECT is a failed validation that blocks T-095 in its own stage
and escalates to its basis A-003 per the routing rule.

## Criterion derivation (1:1 from T-095's ACs)

| T-095 AC (verbatim concern) | Criteria |
|---|---|
| AC1 "design artifact exists: components/contracts/workflows derived from docs/acceptance-run-architecture.md, repo doc convention header included" | CT-D-001 (exists at the chain-convention path + About header), CT-D-002 (per-component contracts complete, incl. the named data structures), CT-D-003 (the three key workflows designed) |
| AC2 "every workflow names its module boundary and stays within the allowed edges E1-E7" | CT-D-004 (module boundary named per workflow), CT-D-005 (E1–E7 respected; denied edges unused) |
| AC3 "pass-through mapping recorded where docs/design-stage-pipeline.md already fixes the structure" | CT-D-006 (mapping present; no invented parallel structure) |
| AC4 "artifact recorded on this ticket before in-review" | CT-D-007 (T-095 history ordering — the ITS-006 pattern) |

Every criterion below traces to exactly one AC concern; no criterion
verifies structure outside T-095's ACs and ticket description.

## Component criteria

### CT-D-001 · The design artifact exists at the chain-convention path with the repo doc header (T-095 AC1)

- **Statement:** T-095 records exactly one stage-product artifact — the
  acceptance-run design doc — and the doc carries the repo documentation
  header.
- **Oracle:** (a) the artifact recorded on T-095 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file in the repo; (b) its path
  follows the chain naming convention: the acceptance chain's stage
  products live at `docs/acceptance-run-<stage>.md` (system:
  `docs/acceptance-run-system.md`, architecture:
  `docs/acceptance-run-architecture.md`), so the design stage product is
  `docs/acceptance-run-design.md`; (c) the file opens with the **About this
  document** header per the `project-manager-doc-about` convention — kind,
  read-by, related, and the owning ticket id recorded (the skill's
  spec/design rule).
- **PASS:** all three observable.
- **FAIL:** no artifact on T-095 at decision time; the ref does not
  resolve; the About header missing; a path that breaks the chain
  convention without a recorded reason on the ticket.

### CT-D-002 · Per-component contracts complete: inputs, outputs, error behavior — every module, data structures included (T-095 AC1)

- **Statement:** every module M1 store / M2 ticker / M3 worker / M4 verdict
  / M5 process ([ARCH §Modules]) carries at least one component contract in
  the design doc that names its **inputs, outputs and error behavior**; the
  store-side data structures the scope names — **ticket schema usage,
  sprint record lifecycle, history event semantics** — are designed (as the
  substance of M1's contract and the contracts that consume them).
- **Oracle:** locate the contracts in the design doc's own components /
  contracts section(s); check each for the three faces (all present — a
  contract naming only the happy path is incomplete) and for consistency
  against the governing rows: [ARCH §Modules] responsibilities,
  [ARCH §Dependency rules] interface column, [ARCH §Interfaces to the real
  code], and [SYS §Interfaces 1] as amended by T-097 (partial,
  caller-asserted attribution; claim/artifact events actor-stamped, status
  transitions bare; engine attribution via the review pass's observable
  marks). A data-structure design that contradicts the store's observable
  semantics (e.g. a stamped actor on status transitions) FAILS.
- **PASS:** all five modules contracted with all three faces; the three
  named data structures designed consistently with [SYS §Interfaces 1] and
  [DSG §2]/[DSG §3].
- **FAIL:** a module with no contract; a contract missing any face; a
  contract contradicting its module's basis row or the amended attribution
  semantics.

### CT-D-003 · The key workflows are designed: tick, fulfill-cycle, shutdown-mid-pass reconciliation (T-095 AC1)

- **Statement:** the three workflows T-095's scope names appear as designed
  workflows: (1) the **tick procedure**; (2) the
  **claim-spawn-fulfill-verdict-divide cycle**; (3) **shutdown-mid-pass
  reconciliation** — what an interrupted run leaves behind and how the run
  recovers.
- **Oracle:** the design doc's workflows section(s), checked against:
  (1) [DSG §4 tick algorithm] — five steps, SCAN → BLOCKED → DONE → READY →
  CLOSE, fixed order, escalation-before-launch; the design adds no step and
  reorders none ("determinism is the point"); (2) [ARCH §D-point evidence
  flows] D-1/D-2/D-3 chains and [SYS §End-to-end data flows] — the cycle's
  steps produce exactly the evidence those flows name; (3) [ARCH §Key
  quality attributes QA-4 + AD-007] and [DSG §5] — recovery is git revert
  with the gate + commit closing the loop, stalled spawns aborted and their
  tickets returned with a blocker note; no invented recovery machinery the
  basis denies (e.g. worktree isolation, explicitly rejected [DSG §5]).
- **PASS:** all three workflows present and consistent with their sources.
- **FAIL:** a named-scope workflow absent; the tick procedure altered; a
  reconciliation story that contradicts QA-4/AD-007/[DSG §5].

### CT-D-004 · Every workflow names its module boundary (T-095 AC2)

- **Statement:** each workflow states which module executes each step and
  which boundaries it crosses.
- **Oracle:** the design doc's own workflow statements; every named
  executing module resolves to M1–M5 as defined in [ARCH §Modules] (the
  system-part mapping is one-to-one, [ARCH §System part → module mapping] —
  the design introduces no sixth actor and no unnamed module).
- **PASS:** no orphan step — every step's executor is a named module of the
  architecture.
- **FAIL:** a workflow step whose executing module is unnamed or names
  something outside M1–M5.

### CT-D-005 · Every workflow stays within E1–E7; denied edges stay unused (T-095 AC2)

- **Statement:** every interaction a workflow performs is performable
  through an allowed edge E1–E7; no step requires an edge on [ARCH
  §Dependency rules]' denied list.
- **Oracle:** enumerate each workflow's store/machine interactions in the
  design doc; map each to its E-row interface (E1 ticker verbs, E2 worker
  verbs, E3 verdict reads + `done`, E4 spawn, E5/E6 skill loading, E7
  gate); then check the denied list step by step: M1 → anything; M3 → M2;
  M3 ↔ M3 and M2 ↔ M2 lateral; any actor → store files directly (hand
  edits); M2/M3 → `done`; M2/M3 → the gate. Boundary note (from A-004
  N-2): the engine-verdict comment and the FAIL-block `task_set_blocked`
  are the amended basis's own mark/routing mechanisms ([SYS §Interfaces 1]
  as amended; [ARCH §D-point evidence flows D-3] names the comment) — a
  design that includes them in M4's surface is **consistent**, not a
  violation.
- **PASS:** every step maps to a listed edge; no denied-edge step anywhere.
- **FAIL:** a step performable only through a denied edge; a step using a
  verb its actor's E-row does not list (with the N-2 exception above).

### CT-D-006 · Pass-through recorded where [DSG] fixes the structure — no invented parallel structure (T-095 AC3)

- **Statement:** wherever the design relies on structure [DSG] already
  fixes, it records the mapping (the pass-through rationale, as [ARCH
  §Pass-through] does at library level for [DSG] §1–§8) instead of
  re-deciding it.
- **Oracle:** the design doc's pass-through / mapping record, checked both
  ways: (positive) it cites [DSG] sections where it relies on DSG-fixed
  structure — the model/roles [DSG §1], ticket contract [DSG §2], link
  contract [DSG §3], tick algorithm [DSG §4], fulfillment/brief/cap [DSG
  §5], skill parameterization [DSG §6], artifact index [DSG §7], verdict
  model [DSG §8]; (negative) a re-decision-marker sweep over the whole doc:
  new ticket fields or a changed status walk; a modified or extended tick
  step order; a link relation beyond `epic`/`basis`; a replacement
  brief/cap/admission mechanism; a second common skill or per-stage copies;
  an artifact mechanism beyond the store index with store/fallback ref
  shapes; a second `done` authority.
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers.
- **FAIL:** any re-decision marker; described-but-not-mapped reliance on
  DSG-fixed structure; a contradiction of [ARCH §Pass-through]'s recorded
  mappings.

### CT-D-007 · T-095 recorded its artifact before the in-review flip (T-095 AC4)

- **Statement:** the fulfillment order holds on the ticket record — the
  store-side proof of D-1 for this pair.
- **Oracle:** T-095's history: an `artifact:…` event carrying the design
  doc ref, timestamped **before** the bare `updated:status` → in-review
  flip; the worker's fulfillment comment adjacent to the flip (identity
  evidenced by the comment per the amended rule, [SYS §Interfaces 1])
  naming its stage discipline skill (`software-design`) **and** the common
  `stage-pipeline` skill (E6, [DSG §6]); no `done` event inside the
  fulfillment window (denied edge, [ARCH AD-003]).
- **PASS:** artifact event precedes the flip; both skills named; window
  ends at in-review.
- **FAIL:** flip before artifact; no fulfillment comment or no skill named;
  any `done` inside the window.

## Verdict

**Decision protocol (mechanical, applied when T-095's artifact lands):**
run CT-D-001 → CT-D-007 in order against the recorded design doc; decide
each by its oracle and record the located evidence; overall verdict =
**APPROVE** if all seven PASS, **APPROVE-WITH-NOTES** if all PASS with
non-blocking observations, **REJECT** if any FAIL (failed validation →
block T-095 in its own stage with the failing criterion, escalate to the
basis A-003). The verdict decides T-095's done/not-done contribution in the
reviewer's hands; the engine review pass alone sets `done` (D-3); T-096
never sets a status on T-095.

**State at minting (2026-11-15, during T-096's fulfillment):** criteria
minted and decidable; decision **pending** — at mint time T-095 was
in-progress with an empty artifact list, and
`docs/acceptance-run-design.md` was absent from the tree (checked:
`docs/acceptance-run*` contains system, architecture and their `.tests`
siblings only). The pair is concurrent by division design (both children of
A-003 created 2026-11-15T08:58:10Z); nothing blocks — the protocol above
makes the decision mechanical the moment the design doc lands.
