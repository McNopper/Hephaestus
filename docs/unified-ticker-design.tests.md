# Component criteria: the unified ticker-v design (test-design for T-117) — criteria & verdict (T-118)

- **About this document:** the paired test-design verification artifact of
  ticket T-118 (project `hephaestus`, stage `test-design`, epic U-112, basis
  T-103). It is the PAIRED VERIFICATION of ticket **T-117**'s design
  artifact: it derives component criteria **1:1 from T-117's acceptance
  criteria**, gives each an observable oracle — the design doc's own
  sections are the source of truth for *what the design decides*, the
  governing definitions are the source of truth for *whether it is correct
  and complete* — and fixes the verdict protocol that decides T-117's
  done/not-done contribution (mirrored as a comment on ticket T-118; this
  file carries the criteria and the evidence). **Read by:** the engine
  review pass (weighs the verdict; alone sets `done`), the ticker-v stage
  ticker, the maintainer. **Related:** the artifact under verification
  `docs/unified-ticker-design.md` (T-117 — in flight at minting time; the
  criteria are decidable against it the moment it lands); governing
  definitions `docs/unified-ticker-architecture.md` (T-103's product, done
  by engine verdict 2026-11-15, commit `6dda34e`; cited [ARCH]),
  `docs/unified-ticker-system.md` (T-055's product; cited [SYS]),
  `docs/design-stage-pipeline.md` (the U-110 design; cited [DSG]);
  sibling records `docs/unified-ticker-architecture.tests.md` (T-104 — the
  library-level verification this component level sits inside) and
  `docs/unified-ticker-system.tests.md`; tickets T-117 (under verification)
  and T-118 (this record's ticket).

## Scope: what "component level" verifies here

The unified ticker's modules are modules of a **process/agent
architecture** ([ARCH §Scope]) anchored in store, schedule, skill and
subagent surfaces — so its component level is the design INSIDE and
BETWEEN the components C1..C8, not code. Layer separation against the
already-verified levels:

- **Library level (sibling record):** T-104's
  `docs/unified-ticker-architecture.tests.md` verified the component set
  C1..C8 and the default-deny edge table E1..E11 (plus the denied-edge
  rules) against the basis docs and the live machinery. This record does
  not re-verify the edges; it reads them as fixed structure.
- **Component level (this record):** per T-118's own ticket description —
  "the design artifact's contracts are complete and consistent: each
  component contract names inputs/outputs/error behavior; each workflow
  respects the architecture's dependency edges; Q-002/Q-003 dispositions
  are decided or explicitly re-routed; no contract contradicts
  docs/unified-ticker-system.md; no invented parallel structure vs
  docs/design-stage-pipeline.md". T-117's scope (ticket description) names
  the substance: per-component contracts (inputs/outputs/error behavior)
  for the architecture's components; workflows consistent with the
  dependency rules; the design-routed open questions Q-002 and Q-003
  owned and answered; and the S-2 orphaned-claim detection mechanism
  designed (routed to the design leg by both [SYS S-2] and [ARCH
  §Open questions — routed, not decided]).

The pair runs **concurrently** (T-117 and T-118 share basis T-103 and epic
U-112; both planned into ticker-v; per the pair doctrine this record never
blocks on the sibling's completion): the criteria are written to be
**decidable against the artifact T-117 produces**, and the Verdict section
fixes the mechanical decision protocol applied at verdict time.

## Method and verdict semantics

Static documentation-level verification (the sibling method, T-096's
`docs/acceptance-run-design.tests.md`): every criterion is decided by
reading the design doc T-117 records — located by its own sections,
checked against the governing section named in the oracle. No store writes
are needed beyond this record's own bookkeeping. Verdict semantics:
**PASS/FAIL per criterion** with the evidence located or quoted; the
overall verdict (**APPROVE / APPROVE-WITH-NOTES / REJECT**) decides
T-117's done/not-done contribution and is **advisory to the engine review
pass**, which alone sets `done`. T-118 sets no status on T-117; a REJECT
is a failed validation that blocks T-117 in its own stage and escalates to
its basis T-103 per the routing rule.

## Criterion derivation (1:1 from T-117's ACs)

| T-117 AC (verbatim concern) | Criteria |
|---|---|
| AC1 "design artifact exists (docs/ convention About header) with component contracts for the architecture's components: inputs/outputs/error behavior named, workflows consistent with edges E1..E11 and the denied-edge rules" | CT-U-001 (exists at the chain-convention path + About header), CT-U-002 (per-component contracts complete: three faces, C1..C8 resolved), CT-U-003 (the key workflows designed, executing component named per step), CT-U-004 (E1..E11 respected; denied edges unused) |
| AC2 "every architecture element (C1..C8, I-1..I-5, S-1..S-6, INV-1..INV-7) resolves to a contract or an explicit pass-through; Q-002 and Q-003 answered or explicitly re-routed with rationale; no contract contradicts docs/unified-ticker-system.md or docs/design-stage-pipeline.md" | CT-U-005 (element coverage: contract or explicit pass-through), CT-U-006 (Q-002/Q-003 dispositions pinned; S-2 detection decided; Q-001/Q-004 stay routed), CT-U-007 (no contradiction of [SYS]/[DSG]; no invented parallel structure) |
| AC3 "the artifact is recorded on this ticket (doc) and the ticket is in-review; done is never self-assessed" | CT-U-008 (T-117 history ordering — the artifact-before-flip pattern) |

Every criterion below traces to exactly one AC concern; no criterion
verifies structure outside T-117's ACs and ticket description.

## Component criteria

### CT-U-001 · The design artifact exists at the chain-convention path with the repo doc header (T-117 AC1)

- **Statement:** T-117 records exactly one stage-product artifact — the
  unified-ticker design doc — and the doc carries the repo documentation
  header.
- **Oracle:** (a) the artifact recorded on T-117 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file in the repo; (b) its
  path follows the chain naming convention: the unified chain's stage
  products live at `docs/unified-ticker-<stage>.md` (requirements:
  `docs/unified-ticker-requirements.md`; system:
  `docs/unified-ticker-system.md`; architecture:
  `docs/unified-ticker-architecture.md`), so the design stage product is
  `docs/unified-ticker-design.md`; (c) the file opens with the **About
  this document** header per the `project-manager-doc-about` convention —
  kind, read-by, related, and the owning ticket id recorded.
- **PASS:** all three observable.
- **FAIL:** no artifact on T-117 at decision time; the ref does not
  resolve; the About header missing; a path that breaks the chain
  convention without a recorded reason on the ticket.

### CT-U-002 · Per-component contracts complete: inputs, outputs, error behavior — every component resolved (T-117 AC1)

- **Statement:** every component C1..C8 of [ARCH §Components and layers]
  carries at least one component contract in the design doc that names its
  **inputs, outputs and error behavior** — or resolves as an explicit
  pass-through naming the basis section that fixes it wholesale (the
  [ARCH §Pass-through] precedent; C2 `host` "provides surfaces and
  transport only — no agency of its own" and C7 `process` "loaded by
  actors; never an active process" are the expected pass-through shapes).
  A contract naming only the happy path is incomplete.
- **Oracle:** locate the contracts in the design doc's own components /
  contracts section(s); check each for the three faces and for
  consistency against the governing rows: [ARCH §Components and layers]
  responsibilities and "Anchored in" column, the interface column of
  E1..E11 ([ARCH §Dependency rules]), and [SYS §Major parts] /
  [SYS §Technology choices]. Face checks the basis pins: the steward
  contract (C4) carries reconciliation of interrupted work and the
  mid-pass store-loss reconnect ([ARCH S-2/S-4]; [SYS I-3]) and the
  admission faces count-before-spawn + defer-at-cap ([ARCH I-4]); the
  reviewer contract (C6) carries the delivered-not-applied verdict split
  with FAIL and UNCLEAR behaviors ([ARCH AD-005]; E6/E7); the arming
  contract (C8) carries the exactly-one re-arm guard and the no-store-
  footprint rule (E1; [ARCH denied C8 → C1]); the schedule contract (C3)
  carries the dormant-not-running orphan state ([ARCH I-1]); the store
  contract (C1) carries confirmed-write coherence ([SYS I-3]; [ARCH
  QA-5]).
- **PASS:** all eight components resolve (contract with all three faces,
  or explicit pass-through); every contract consistent with its [ARCH]
  row and E-interface.
- **FAIL:** a component with neither contract nor explicit pass-through;
  a contract missing any face; a contract contradicting its component's
  basis row or E-row interface.

### CT-U-003 · The key workflows are designed, each step's executing component named (T-117 AC1)

- **Statement:** the workflows the architecture's runtime actually runs
  appear as designed workflows: (1) the **ten-stage tick pass** (S-1
  steady state, the fixed write order); (2) the **claim-spawn-fulfill-
  verdict-divide cycle** (the E3/E4/E5/E6 interplay of one ticket through
  one tick); (3) the **resume/reconciliation flow** (S-2/S-4 — what an
  interrupted run leaves behind and what the first pass repairs). Each
  workflow states which component executes each step.
- **Oracle:** the design doc's workflows section(s), checked against:
  (1) [ARCH E3]'s fixed write order (escalate → verdict application →
  division → plan-then-claim → gated close) and [SYS §The tick as a
  system event] / FR-006; (2) [ARCH I-2] and [DSG §5] (background spawn,
  the narrow brief, idle completion, own-ticket footprint E5); (3) [ARCH
  §Scenario walks S-2/S-4] plus AD-006 (reconciliation lives in the
  steward, from C1 state alone) and I-5 (the record stays active until
  the repair drains). No orphan step: every executor resolves to C1..C8 —
  no unnamed component and no new actor (the PO stays outside the runtime
  loop, [ARCH §System part → component mapping]).
- **PASS:** all three workflows present and consistent with their
  sources; every step's executor a named component.
- **FAIL:** a named-scope workflow absent; the tick's fixed order
  altered; a workflow step whose executor is unnamed or outside C1..C8.

### CT-U-004 · Every workflow stays within E1..E11; denied edges stay unused (T-117 AC1)

- **Statement:** every interaction a workflow performs is performable
  through a listed edge E1..E11; no step requires an edge on the denied
  list.
- **Oracle:** enumerate each workflow's store/host/tree interactions in
  the design doc; map each to its E-row (E1 arming, E2 firing, E3 steward
  store verbs, E4 spawn/admission/reconnect, E5 worker own-ticket writes,
  E6 reviewer reads, E7 gate runs, E8/E9 skill loads, E10/E11 operator
  edges); then the denied sweep ([ARCH §Dependency rules] denied list,
  step by step): C1 → anything (no callbacks or notifications — discovery
  is by read at the tick boundary); C2 as agency (initiates no store
  write, makes no ticket decision); C5/C6 → C4 (no mid-flight orders —
  the idle completion over E4 is the only return channel); C5 ↔ C5 and
  C6 ↔ C6 lateral; C4 discipline work or bare `done` (done only as
  verdict application inside E3's fixed order); C5 → `done` (structurally
  excluded from E5's write set); C6 → file edits; C8 → C1 (arming never
  writes store business state); any actor → store files directly (hand
  edits are not an edge; the smallest-documented-correction convention
  [AGENTS.md §Write discipline] is an exception path, not a designed
  channel); any path → per-stage schedules (FR-004, INV-7 — the ten→one
  supersession [SYS §Supersession] must not regress).
- **PASS:** every step maps to a listed edge; no denied-edge step
  anywhere.
- **FAIL:** a step performable only through a denied edge; a step using a
  verb its actor's E-row does not list.

### CT-U-005 · Every architecture element resolves to a contract or an explicit pass-through (T-117 AC2)

- **Statement:** each element of the architecture's inventory — C1..C8,
  I-1..I-5, S-1..S-6, INV-1..INV-7 — resolves in the design doc to a
  contract or an explicit pass-through: no silent gaps, no element
  "handled" in prose that resolves to nothing checkable.
- **Oracle:** the design doc's coverage / mapping record (whatever
  sections carry the element-to-contract mapping), swept against the full
  element inventory in [ARCH]: §Components and layers (C1..C8), §The
  interaction model through the components (I-1..I-5), §Scenario walks
  (S-1..S-6), §Invariant anchors (INV-1..INV-7), plus §Pass-through (the
  basis-fixed rows the design inherits rather than re-derives). Two
  failure directions: an element with neither contract nor pass-through;
  an element implied but never resolved. A pass-through is legitimate
  only where the basis fixes the structure ([ARCH §Pass-through] rows;
  [SYS §Supersession] carve-outs) — the S-2 detection mechanism and
  Q-002/Q-003 are NOT pass-through-eligible (they are routed to this
  design leg; CT-U-006 decides them).
- **PASS:** every element resolves one-to-one; every pass-through names
  the basis section it inherits.
- **FAIL:** an element with no resolution; a pass-through used where the
  basis routes the decision to the design leg.

### CT-U-006 · Q-002 and Q-003 dispositions are decided or explicitly re-routed with rationale; the S-2 detection mechanism is decided; Q-001/Q-004 stay routed (T-117 AC2)

- **Statement:** T-117 OWNS Q-002 and Q-003 (its ticket description).
  Each must end the design leg in exactly one of two states: (a)
  **DECIDED** — a named disposition with the basis constraints honored;
  or (b) **EXPLICITLY RE-ROUTED** — a named destination plus rationale
  (the routing the basis already gives counts: Q-002 "rides Route A" per
  [SYS §Open questions carried forward]). A silent skip of either
  question FAILS. Additionally the **S-2 orphaned-claim detection
  mechanism** — routed to the design leg by [SYS S-2] and the dead-claim
  row of [ARCH §Open questions] — is **DECIDED** (bounded staleness vs
  session-existence probe), honoring the architecture-fixed contract:
  orphaned claims are released and re-queued, never silently left
  `in-progress` forever, never double-spawned while the worker might
  still be alive. Conversely **Q-001** (Route A) and **Q-004** (owner
  arm-time call, C-F) STAY routed — the design deciding them itself
  violates [ARCH §Open questions — routed, not decided].
- **Oracle:** the design doc's open-questions / disposition record,
  checked against [ARCH §Open questions — routed, not decided] and
  [SYS §Open questions carried forward]:
  - **Q-002 constraints:** INV-3 must survive steward replacement —
    reuse keeps the cap count (the counter is steward-instance-local,
    [ARCH I-4]/AD-004); fresh requires a drain rule so old children
    cannot blow the cap ([ARCH S-3]); the E1 exactly-one outcome
    (INV-1/INV-2) holds either way.
  - **Q-003 constraints:** the contract never depends on one pass fitting
    the window ([SYS Q-003]; [ARCH Q-003 row]; AD-002 — C1 carries all
    state); the fallback shape (a store-carried stage cursor + per-stage
    sub-passes) is the named alternative; if re-routed, the destination
    names where the fit question lands.
  - **S-2 detection:** the chosen mechanism is named, its parameters
    stated, and it respects the release-and-requeue contract ([ARCH S-2]
    steps 1–3: `task_release` → `sprint-backlog` → re-claim under the
    cap).
- **PASS:** Q-002 and Q-003 each decided (constraints honored) or
  explicitly re-routed (destination + rationale); the S-2 detection
  mechanism decided within the fixed contract; Q-001/Q-004 still routed.
- **FAIL:** either owned question silently absent; a disposition
  contradicting the S-3 cap-drain constraint or the
  window-independence contract; the S-2 detection mechanism passed
  through or left open; Q-001/Q-004 decided here.

### CT-U-007 · No contract contradicts [SYS] or [DSG]; no invented parallel structure (T-117 AC2)

- **Statement:** wherever the design relies on structure the basis
  already fixes, it records the mapping (cites the governing section)
  instead of re-deciding it; and no contract anywhere contradicts
  `docs/unified-ticker-system.md` or `docs/design-stage-pipeline.md`.
- **Oracle:** checked both ways. (Positive) the design cites the basis
  sections it relies on: the model and roles [DSG §1], ticket contract
  [DSG §2], link contract [DSG §3], tick algorithm [DSG §4], fulfillment
  [DSG §5], stage skills [DSG §6], artifact index [DSG §7], the verdict
  [DSG §8], native-first inventory [DSG §11], and the unified chain's
  supersession [SYS §Supersession]. (Negative) the re-decision-marker
  sweep over the whole doc, chain-specific: any reappearance of the
  superseded ten-schedule topology (per-stage schedules, `ticker-<stage>`
  records, per-stage intervals — FR-004/INV-7); a changed status walk or
  new ticket fields ([DSG §2]); a link relation beyond `epic`/`basis`
  ([DSG §3]); a second common skill or per-stage skill copies ([DSG §6];
  E8/E9); a second `done` authority ([DSG §8]; AD-005); the cap enforced
  by a surface rather than C4's procedure ([ARCH I-4]/AD-004); mid-flight
  orders into the steward (denied C5/C6 → C4); store hand edits as a
  designed write path (denied any actor → store files directly; C-E);
  invented recovery machinery — worktree isolation ([DSG §5] no
  isolation; recovery is git revert) or reconciliation in the arming path
  ([ARCH AD-006]).
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers; no contradiction of [SYS §Major parts], [SYS I-1..I-5],
  [SYS §Invariants] or the [DSG] sections above.
- **FAIL:** any re-decision marker; described-but-not-mapped reliance on
  basis-fixed structure; a contract contradicting [SYS] or [DSG] — or
  [ARCH §Pass-through]'s recorded mappings.

### CT-U-008 · T-117 recorded its artifact before the in-review flip; done never self-assessed (T-117 AC3)

- **Statement:** the fulfillment order holds on the ticket record — the
  store-side proof of the contract for this pair.
- **Oracle:** T-117's history: an `artifact:…` event carrying the design
  doc ref, timestamped **before** the `updated:status` → in-review flip;
  the worker's fulfillment comment adjacent to the flip naming the common
  `stage-pipeline` skill and its stage discipline skill `software-design`
  (E9, [DSG §6]); no `done` event inside the fulfillment window (the
  denied edge, [ARCH denied C5 → `done`] — done is the engine's verdict,
  never the fulfiller's self-assessment).
- **PASS:** artifact event precedes the flip; both skills named; the
  window ends at in-review.
- **FAIL:** flip before artifact; no fulfillment comment or no skill
  named; any `done` inside the window.

## Verdict

**Decision protocol (mechanical, applied when T-117's artifact lands):**
run CT-U-001 → CT-U-008 in order against the recorded design doc; decide
each by its oracle and record the located evidence; overall verdict =
**APPROVE** if all eight PASS, **APPROVE-WITH-NOTES** if all PASS with
non-blocking observations, **REJECT** if any FAIL (failed validation →
block T-117 in its own stage with the failing criterion, escalate to the
basis T-103 per the routing rule). The verdict decides T-117's
done/not-done contribution in the engine's hands; the engine review pass
alone sets `done`; T-118 never sets a status on T-117. This pair decides
the design child's done/not-done; it does not gate other waves (T-118
ticket description).

**State at minting (2026-11-16, during T-118's fulfillment):** criteria
minted and decidable; decision **pending** — at mint time T-117 was
in-progress with an empty artifact list, and
`docs/unified-ticker-design.md` was absent from the tree (checked:
`docs/unified-ticker*` contains requirements, system, architecture and
their `.tests` siblings only); T-117's recovery comment
(ticker-v:resume, 2026-11-16T08:23Z) confirms the earlier claim was
orphaned and its fulfillment restarted fresh. Nothing blocks — the
protocol above makes the decision mechanical the moment the design doc
lands.
