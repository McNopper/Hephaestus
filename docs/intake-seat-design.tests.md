# Component criteria: the intake-seat design doc (T-115) — criteria & verdict (T-116)

## About this document

- **Kind:** `doc` / test-design verification artifact (V stage `test-design`;
  ticket T-116, epic U-112, basis T-105; the PAIRED VERIFICATION of ticket
  **T-115**'s design artifact — the sibling definition ticket whose stage is
  `design`). It derives component criteria **1:1 from T-115's acceptance
  criteria**, gives each an observable oracle — the design doc's own
  sections are the source of truth for *what the design decides*, the
  governing definitions are the source of truth for *whether it is correct
  and consistent* — and records the verdict with evidence on ticket T-116
  (this file carries the criteria and the located evidence; a comment on
  T-116 mirrors the verdict). **Read by:** the engine review pass (weighs
  the verdict; alone sets `done`), the ticker-v stage ticker, the
  maintainer, and the implementation stage (it builds on a basis whose
  consistency is proven here). **Related:** the artifact under verification
  `docs/intake-seat-design.md` (T-115 — done by engine verdict 2026-11-16,
  commit `fd6db8b`, cited **[DES]**); governing definitions
  `docs/intake-seat-architecture.md` (T-105 — components C-1..C-6, rules
  DR-1..DR-7, decisions AD-001..AD-007, cited **[ARCH]**),
  `docs/intake-seat-system.md` (T-083 — the system definition whose
  invariants 1–6 are the consistency bar, cited **[SYS]**),
  `docs/design-stage-pipeline.md` (the machine's own design — the
  no-invented-parallel-structure reference, cited **[DSG]**); sibling
  records `docs/intake-seat-architecture.tests.md` (the library-level
  verification this component level sits inside — read as fixed structure)
  and `docs/acceptance-run-design.tests.md` (shape reference); tickets
  T-115 (under verification) and T-116 (this record's ticket).

## Scope: what "component level" verifies here

The intake seat's components are components of a **process/agent
architecture** ([ARCH §2]) anchored in store, skill, host-surface and
subagent reality — so its component level is the design INSIDE each
component, not code. Layer separation against the already-verified levels:

- **Library level (done):** T-106's `docs/intake-seat-architecture.tests.md`
  verified the architecture artifact's structure (C-1..C-6, DR-1..DR-7,
  traceability §10) against the live system doc and the live code at HEAD.
  This record does not re-verify the boundaries; it reads them as fixed
  structure and checks the design upholds them on the inside.
- **Component level (this record):** per the `test-software-design` skill —
  each component's designed contract (inputs → outputs | errors), its
  workflows and its error behavior, complete and consistent. The concrete
  concerns are T-116's own ticket description: each component contract
  names inputs/outputs/error behavior; each workflow respects the
  architecture's dependency rules; no contract contradicts
  `docs/intake-seat-system.md`; no invented parallel structure vs
  `docs/design-stage-pipeline.md`. Per that description, this pair decides
  the design child's done/not-done; **it does not gate other waves.**

**Pair state:** the acceptance-run pair (T-095/T-096) was minted concurrent
and decided later; this pair's definition leg landed first — T-115 is done
(engine PASS verdict 2026-11-16, artifact at commit `fd6db8b`). The
criteria below are therefore minted and **decided in one pass**: each
criterion carries its located evidence. The engine's PASS on T-115
preceded this record — the Verdict section is the component-level evidence
trail for that decision, not the decision itself; T-116 sets no status on
T-115.

## Method and verdict semantics

Static documentation-level verification (the sibling method): every
criterion is decided by reading the design doc T-115 records — located by
its own sections — and checked against the governing section named in the
oracle; store and git checks where the criterion is about store discipline
or commit shape. No writes anywhere except this record's own file and
ticket bookkeeping. Verdict semantics: **PASS/FAIL per criterion** with the
evidence located or quoted; the overall verdict (**APPROVE /
APPROVE-WITH-NOTES / REJECT**) is recorded on ticket T-116 (AC-3) and is
evidence for the engine, which alone sets `done`. A FAIL would have been a
failed validation: block the design child in its own stage and escalate to
the basis T-105 per the routing rule — stated for protocol completeness;
nothing failed.

## Criterion derivation (1:1 from T-115's ACs)

| T-115 AC (verbatim concern) | Criteria |
|---|---|
| AC-1 "design artifact exists (docs/ convention About header) with component contracts for the architecture's components: inputs/outputs/error behavior named, workflows consistent with the architecture's dependency rules DR-1..DR-7" | CT-001 (exists at the chain-convention path + About header), CT-002 (per-component contracts complete: the three faces, all of C-1..C-6, data structures included), CT-003 (D-3's degrade-not-fail error face pinned as an explicit contract), CT-004 (workflows consistent with DR-1..DR-7; the §7 compliance map complete) |
| AC-2 "every architecture element (C-1..C-6, dispatch wiring, guarded switching, model resolution, spot-check mechanization S-1/S-2/S-3) resolves to a component contract; no contract contradicts docs/intake-seat-system.md; Q-1 stays OPEN" | CT-005 (the §8 traceability matrix resolves every element, pass-throughs explicit), CT-006 (S-1/S-2/S-3 resolve as specified query contracts — the script stays future work per [SYS §7]/[ARCH §12]), CT-007 (consistency sweep vs the system doc: invariants 1–6 + the fixed surfaces), CT-008 (no invented parallel structure vs [DSG] — the C-3/C-4 pass-throughs), CT-009 (Q-1 stays OPEN) |
| AC-3 "the artifact is recorded on this ticket (doc) and the ticket is in-review; done is never self-assessed" | CT-010 (T-115 history ordering — the artifact-before-review discipline; done by the engine) |

Every criterion traces to exactly one AC concern. T-116's ticket
description names four component-level concerns; each is carried inside the
AC-derived criteria: the three contract faces → CT-002, workflows vs
dependency rules → CT-004, no contradiction with the system doc → CT-007,
no invented parallel structure vs [DSG] → CT-008. No criterion verifies
structure outside T-115's ACs and this ticket's description.

## Component criteria

### CT-001 · The design artifact exists at the chain-convention path with the repo doc header (T-115 AC-1)

- **Statement:** T-115 records exactly one stage-product artifact — the
  intake-seat design doc — at the chain-convention path, carrying the repo
  documentation header.
- **Oracle:** (a) the artifact recorded on T-115 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file in the repo; (b) its path
  follows the chain naming convention — this V-walk's stage products live
  at `docs/intake-seat-<stage>.md` (system: `docs/intake-seat-system.md`,
  architecture: `docs/intake-seat-architecture.md`), so the design stage
  product is `docs/intake-seat-design.md`; (c) the file opens with the
  **About this document** header per the `project-manager-doc-about`
  convention — kind, read-by, related, and the owning ticket id recorded.
- **PASS:** all three observable.
- **FAIL:** no artifact on T-115 at decision time; the ref does not
  resolve; the About header missing; a path that breaks the chain
  convention without a recorded reason on the ticket.
- **Evidence (decided 2026-11-16):** T-115's artifact event
  (2026-11-15T11:09:47.904Z, kind `doc`) carries exactly
  `docs/intake-seat-design.md`; the file exists (395 lines). Commit
  `fd6db8b` ("T-115 design: product-owner seat component contracts
  (C-1..C-6) …") carries it as the **sole file** (395 insertions,
  0 deletions; verified via `git show --stat`) — one new file, matching the
  declared lane ([DES §11]). The About header (lines 3–19) names kind
  (design-stage artifact of T-115, epic U-112, basis T-105, stage
  `design`), read-by and related — including the governing architecture,
  the system doc, [DSG] as C-4's pass-through basis, the seat definition
  and `AGENTS.md`. **PASS.**

### CT-002 · Per-component contracts complete: inputs, outputs, error behavior — every component, data structures included (T-115 AC-1)

- **Statement:** every component C-1..C-6 carries contract(s) in the
  design's `name(inputs) -> output | errors` notation that name their
  **inputs, outputs and error behavior**; C-3/C-4 may resolve as explicit
  pass-throughs with recorded rationale (their contracts are engine-owned
  substrate / [DSG]-fixed — inventing contracts there would itself be
  invented parallel structure, CT-008's sweep); the design's data
  structures are the contracts' vocabulary and consistent with them.
- **Oracle:** [DES §2], checked per component for the three faces (a
  contract is incomplete only when no section of the document names its
  failure behavior — the consolidated [DES §6] table or a shared
  substrate-level error row may carry a face); [DES §4] data structures,
  checked against the contracts that consume them; consistency against the
  governing rows [ARCH §2] responsibilities and [ARCH §3] rules.
- **PASS:** all six components contracted (four designed + two explicit
  pass-throughs with rationale); every failure mode named somewhere in the
  document; data structures consistent.
- **FAIL:** a component with no contract and no recorded pass-through; a
  failure mode of a contract named nowhere; a data structure contradicting
  its contract.
- **Evidence (decided 2026-11-16):** C-1 — nine loop-step contracts:
  `classify` (ambiguity → bounded clarification ≤2 round-trips, past the
  bound register with open questions **on the ticket**), `distill` (bug
  wording quoted, distillation labeled), `link` (unknown epic/basis →
  halt, no orphan ticket), `register` (store API failure → owner surfaced
  with the draft preserved, retry from the draft, no local queue — DS-006),
  `price`, `cite`, `verifyRegistration` (missing/mis-linked → correct via
  `task_update`, re-verified), `verifyProgress` (store unreachable →
  honest "cannot verify"), `escalate` (NEEDS-HUMAN with what is needed) —
  plus DS-001's deliberately-absent verbs as the enforcement surface. C-2 —
  three route contracts, each with errors: D-1 name miss → simply not in
  the list, no partial state; D-2 `commitAgentPick` commit-or-revert over
  `GuardState` with the stated invariant (the agent changes only through an
  armed commit); D-3 `openChat` with the unknown-`agentId` behavior
  (pinned separately, CT-003); plus the shared error note (no route
  reachable from the machine — DR-2/invariant 4). C-3/C-4 — explicit
  pass-throughs with recorded rationale (substrate per [ARCH AD-001];
  [DSG §1–§8] is the machine's design). C-5 — eight base read contracts +
  three spot-check query contracts with the collective errors clause
  ("none of the three throws on a hit — hits **are** the output") and the
  read-only statement (DR-4). C-6 — three outcome contracts with read-back
  verification (DS-007) and the error behavior (route unavailable → duty
  unmet → honest NEEDS-HUMAN; a delegate's unverified "done" is never
  quietly accepted). [DES §4] — six data structures (`OwnerInput`,
  `Classification`, `TicketDraft` with per-field invariants,
  `SpotCheckWindow`, `Candidate` with no verdict field per DS-004,
  `GuardState` with the arm/commit/revert invariant), each consumed by
  named contracts in §2/§5. Observation (non-blocking, see Verdict note 1):
  `price`/`cite` carry no inline error clause; their failure class is the
  [DES §6] store-failure row — no failure mode is left unnamed, so the
  criterion holds. **PASS.**

### CT-003 · D-3's unknown-agentId behavior is pinned: degrade-not-fail as an explicit contract (T-115 AC-1 — the error face at the string seam)

- **Statement:** the D-3 `openChat` contract states its error behavior for
  an unknown `agentId` — **degrade** (the chat opens on the default agent
  selection), **not fail** — as an explicit, testable contract clause. This
  is a design decision (DS-003): consistent with, but not stated by, the
  upstream definitions ([ARCH AD-005] fixes the untyped string seam and its
  pinning test, not a failure mode; [SYS §2 D-3] fixes the preselect path,
  not the miss behavior) — so the design doc is the only home of this
  decision and must state it, not imply it.
- **Oracle:** [DES §2 C-2 D-3] ("unknown `agentId` → the chat opens on the
  **default** agent selection — degrade, do not fail", with the rationale:
  a stale name from an old Server-view listing must never block opening a
  chat) and the [DES §6] row ("Unknown `agentId` on D-3 | chat opens on
  default agent selection — degrade, don't fail (DS-003)"); consistency
  check against [ARCH §3 DR-3] (D-2/D-3 share the host but no contract —
  the string seam), [ARCH AD-005] and [SYS §2 D-3]. Observable when
  implemented: `openChat` with an unknown `agentId` yields an open chat
  view on the default agent selection — no exception, no blocked dialog.
- **PASS:** the clause present in both the contract and the consolidated
  error table; consistent with upstream (which demands neither
  fail-closed nor degrade); exactly one behavior stated.
- **FAIL:** the behavior only implied; a contradiction (fail-closed on
  unknown id without a recorded decision); two conflicting error
  behaviors in different sections.
- **Evidence (decided 2026-11-16):** both cites present ([DES] lines
  112–120 and the §6 row at line 307); DS-003 named at point of use and in
  the [DES §7] DR-3 row ("the string seam, DS-003"); sweep: `agentId`'s
  error behavior appears at exactly these two locations — no conflicting
  statement anywhere. This criterion is the criteria-level home of the
  T-115 engine review's non-blocking observation ("DS-003 degrade-not-fail
  is a design choice for test-design to pin") — pinned here. **PASS.**

### CT-004 · Every workflow respects DR-1..DR-7; the compliance map is complete and truthful (T-115 AC-1)

- **Statement:** each workflow (W-1..W-4) states the rules it upholds and
  actually upholds them step by step; [DES §7]'s map covers all seven rules
  with upholders that exist and genuinely uphold.
- **Oracle:** [DES §5] workflows and their "Upheld:" lines, checked
  step-by-step against [ARCH §3] DR-1..DR-7; [DES §7], checked for
  (a) coverage — all seven rules have a row — and (b) truthfulness — each
  "Upheld by" cell resolves to contracts/workflows present in [DES §2]/§5
  that actually uphold the rule. Denial sweep: no workflow step requires a
  denied direction (machine → seat; seat → C-4 internals; seat shell; hand
  writes to the store; committed model ids; seat-owned verdicts).
- **PASS:** every step within the rules; all seven map rows truthful.
- **FAIL:** a workflow step violating a rule; a map row whose upholder
  does not exist or does not uphold.
- **Evidence (decided 2026-11-16):** W-1 — steps are classify → bounded
  clarification → distill → link → `register` (`task_create`) + `price`
  (`model` field, runtime value only) → `cite` + `verifyRegistration`
  (`task_get`): machine contact exclusively `task_*` (DR-1), no committed
  id (DR-6), the ticket enters the backlog and verdicts come later from
  the engine (DR-7) — its Upheld line is correct. W-2 — non-shell route
  first, shell-bound → steward delegate with the outcome specified,
  read-back verification, unmet duty → NEEDS-HUMAN: no C-4 internals
  touched, outcomes only (DR-1, DR-5, AD-002) — correct. W-3 — reviewer
  fixes the window at T-041 reviews and retros, three `Candidate[]` lists,
  per-candidate verdict is the reviewer's, a hit → rework through the
  store, never a silent pass: reads only (DR-4), candidates not verdicts
  (DR-7/AD-007) — correct. W-4 — owner picks by name (D-1/D-2/D-3), no
  machine-initiated route (DR-2/invariant 4), default-agent miss → the
  doctrine layer (invariant 6): name-only dependence (DR-3) — correct.
  [DES §7] — all seven rows present; spot-checked cells resolve: DR-1
  (C-1's verbs are exclusively `task_*`; no contract names a C-4 internal),
  DR-4 (write verbs only in C-1's registration/escalation set — true:
  `register`/`price`/`escalate` plus `verifyRegistration`'s `task_update`
  correction, all C-1 `task_*` verbs), DR-6 (`price`/`TicketDraft.model`
  runtime placeholders only; §4 adds no ids), DR-7 (DS-001; DS-004; W-3
  step 3). No step requires a denied direction anywhere. **PASS.**

### CT-005 · The traceability matrix resolves every architecture element (T-115 AC-2)

- **Statement:** every architecture element T-115's AC-2 names — C-1..C-6,
  dispatch wiring, guarded switching, model resolution, spot-check
  mechanization S-1/S-2/S-3 — resolves in the design doc's traceability
  matrix to a component contract or an explicit pass-through.
- **Oracle:** [DES §8] matrix, per row: the "Design resolution" cell
  resolves to a [DES] section that exists and covers the element;
  pass-through rows carry their rationale; the element list cross-checked
  verbatim against T-115's AC-2 wording.
- **PASS:** every named element has a resolving row; every resolution
  target exists.
- **FAIL:** an element without a row; a row pointing at a nonexistent or
  empty section.
- **Evidence (decided 2026-11-16):** all ten rows resolve — C-1 (§2
  contract set, DS-001/DS-006); C-2 dispatch wiring D-1/D-2/D-3 (§2 route
  contracts); guarded switching AD-004 (§2 D-2 machine + `GuardState`
  §4); model resolution (explicit pass-through — the runtime chain — plus
  the one contract touchpoint, `price` in C-1); C-3 (explicit pass-through,
  substrate, §1/§2); C-4 (explicit pass-through, [DSG], DR-1/DR-2 keeping
  both directions out); C-5 (§2 base contracts, Facade §3); S-1/S-2/S-3
  (§2 query contracts + `Candidate`/whitelist, DS-004/DS-005); C-6 (§2
  outcome contracts, DS-007); Q-1 (§9, OPEN). Every target section exists
  and covers its row. The §8 consistency paragraph additionally folds the
  four system-doc elements outside the architecture (evidence verb list,
  whitelist, dials, cost lever) — checked under CT-007. **PASS.**

### CT-006 · S-1/S-2/S-3 resolve as specified query contracts — the script stays future work (T-115 AC-2)

- **Statement:** the spot-check mechanization resolves to query contracts
  (named signatures over the window type, outputs `Candidate[]` only),
  specified in the design — and the design neither includes nor demands a
  checking script; the script is recorded as its own future ticket per
  [SYS §7] / [ARCH §12].
- **Oracle:** [DES §2 C-5] query contracts (`s1`/`s2`/`s3` over
  `SpotCheckWindow {t0, t1}` → `Candidate[]`; `Candidate {signal, subject,
  reason}` with no verdict field per DS-004, backing DR-7/AD-007), checked
  element-by-element against [SYS §4] and [ARCH §5] (S-1 store+git queries,
  S-2 by-fields, S-3 merge-back topology, the identity whitelist); the
  deferral statements ([DES §2 C-5] "specified, not scripted — a checking
  script is its own ticket with its own tests"; [DES §11] "stay specified,
  not scripted") against [SYS §7] and [ARCH §12].
- **PASS:** query shapes match upstream; candidate-only output; the
  deferral recorded in substance; no script demanded or presented as
  existing.
- **FAIL:** a query shape contradicting [SYS §4]/[ARCH §5]; a verdict
  field anywhere in C-5's data; a script demanded now.
- **Evidence (decided 2026-11-16):** shapes match: S-1 store query
  (implementation-lane = `stage: implementation` or `stage: test-*`; empty
  `basis:`; no owner-directive/fleet provenance) + git query (pattern
  `\b[TU]-\d+\b`; cited ids resolved via `ticketRecord`; an unresolvable
  reference **is a Candidate**, not an exception — a refinement of the
  error face, consistent with upstream's "must resolve via `task_get`");
  S-2 (`history[].by` / `artifacts[].by` vs `isWhitelisted`, bare
  chat-session id named) — match; S-3 (`git worktree list` as the
  merge-source whitelist, `branchesContaining`, main-line-only + no ticket
  reference → candidate) — match. Whitelist DS-005 recorded unchanged
  (fleet identities `ticker-v:*`, `owner-directive` comments, engine/null
  created-events, recorded coordination sessions). Deferral cited with
  both upstream sections; no script demanded anywhere in the document —
  consistent with this record's own dispatch hint. **PASS.**

### CT-007 · No contract contradicts the system doc (T-115 AC-2)

- **Statement:** no contract in [DES §2] — nor data structure or workflow —
  contradicts an invariant or fixed statement of [SYS].
- **Oracle:** sweep by [SYS §1] invariants 1–6 and the fixed surfaces
  (§1 evidence verb list; §2 routes including D-2's guarded semantics and
  D-3's seam; §3 no-committed-model + the cost lever; §4 whitelist, query
  shapes, cadence; §5 Q-1), locating the preserving clause per item (as
  [DES §8]'s consistency paragraph claims); a contradiction is a contract
  whose behavior differs from the system doc's fixed statement.
- **PASS:** every invariant and fixed surface preserved with a locatable
  clause; zero contradictions.
- **FAIL:** any contract clause contradicting [SYS].
- **Evidence (decided 2026-11-16):** invariant 1 → C-1's absent verbs
  (DS-001) + the role-denial reality anchor + C-6's steward route for
  shell-bound duties; invariant 2 → DS-001 (no `done` transition, DR-7);
  invariant 3 → DS-001 (no worker spawn — spawn capacity is C-4's
  tickers'); invariant 4 → W-4 step 2 + C-2's shared note (every input an
  owner gesture); invariant 5 → C-1's override note (no contract attempts
  to prevent the owner's override); invariant 6 → W-4 step 3 + W-3 as the
  detective control. Fixed surfaces: D-2's `GuardState` invariant matches
  [ARCH AD-004]/[SYS §2 D-2] (armed commit / un-armed drift reverts /
  Enter arms and commits / focus-loss reverts); D-3's seam matches
  [ARCH AD-005]; model resolution defers to runtime surfaces exactly as
  [ARCH §4]/[SYS §3] fix (`price`/`TicketDraft.model` runtime
  placeholders only); the whitelist, S-1/S-2/S-3 shapes and the T-041 +
  retros cadence match [SYS §4] (checked under CT-006); C-5's base
  contracts are exactly the system doc's evidence verbs
  (`task_board`, `task_readiness`, `task_get`, `task_doctor`) + git
  reads; the dials ride `TicketDraft.dials` (GOAL roots only, recorded on
  the ticket, never as acceptance criteria) — consistent with the seat
  definition/U-105 doctrine, adding nothing contradictory. **Zero
  contradictions found. PASS.**

### CT-008 · No invented parallel structure vs the machine's design (T-115 AC-2; T-116 description concern 4)

- **Statement:** wherever the design relies on structure [DSG] already
  fixes, it records the pass-through and adds no parallel machinery.
- **Oracle:** [DES §2 C-3]/[DES §2 C-4] + §1's pass-through list, checked
  both ways. Positive: C-4's design IS [DSG] (stationary tickets, the link
  graph, per-stage tickers, goal-driven workers, the engine-owned
  verdict); C-3's contracts are the engine's own substrate. Negative
  re-decision sweep over the whole document: a new store field or a
  changed status walk; a modified tick step order; a link relation beyond
  `epic`/`basis`; a second `done` authority; a second coordination
  channel; per-stage skill copies.
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers.
- **FAIL:** any re-decision marker; described-but-unmapped reliance on
  DSG-fixed structure.
- **Evidence (decided 2026-11-16):** positive — C-3's pass-through
  enumerates the engine-owned contracts (tickets, claims, readiness,
  history events with `by` provenance, the done-time artifact guard, the
  blocked state machine, the write discipline): real store semantics,
  none re-specified; C-4's pass-through states it outright ("adds **no**
  contract for C-4 … Recorded here so AC-2's 'every element resolves' is
  explicit rather than silent"). Negative sweep clean — `TicketDraft`'s
  fields map onto existing `task_create`/`task_update` parameters
  (title, description, acceptance_criteria, kind, type, epic, basis,
  model); `dials` rides **on** the ticket, not as a new store field;
  `link(draft, {epic, basis?})` uses only the two canonical relations; no
  status-walk change, no tick-order mention, no second `done` authority
  (DS-001 denies it), no second channel (DR-1/DR-2 hold throughout; C-6's
  host-endpoint edge is the architecture's own [ARCH §2 C-6], not
  invented here). **PASS.**

### CT-009 · Q-1 stays OPEN — nothing resolved (T-115 AC-2)

- **Statement:** the design doc keeps Q-1 (intake vs product-owner
  naming) OPEN as the owner decision, consistent with [SYS §5]/[ARCH §8];
  its recorded contribution (the name as a value) changes no contract
  signature and resolves nothing.
- **Oracle:** [DES §9] ("Status: OPEN — owner decision … Nothing here
  resolves it") and the [DES §8] Q-1 row ("§9 — OPEN, unchanged");
  comparison with [SYS §5] and [ARCH §8] (status, both-options framing,
  blast radius); a check that no other section resolves or presupposes a
  decision.
- **PASS:** OPEN recorded with the decision request standing; the
  contribution consistent with [ARCH AD-003]'s blast-radius statement; no
  resolution anywhere.
- **FAIL:** a resolved or presupposed naming decision anywhere in the
  document.
- **Evidence (decided 2026-11-16):** §9 verbatim OPEN with the decision
  request restated; the contribution (every contract treats the seat name
  as a **value** — DR-3's dispatch key — so either owner decision is data
  + docs, no contract signature changes) matches [ARCH AD-003]; the §8
  row reads "OPEN, unchanged"; no section names a chosen name as decided.
  **PASS.**

### CT-010 · Artifact-before-review store discipline on T-115; done never self-assessed (T-115 AC-3)

- **Statement:** the fulfillment order holds on the paired definition
  ticket's record: the artifact event precedes the in-review flip, and
  `done` is never the worker's own act.
- **Oracle:** T-115's store history: an `artifact:doc` event carrying the
  design doc ref, timestamped **before** the bare `updated:status` →
  in-review flip; the `done` transition following the engine's verdict
  comment, not authored by the worker.
- **PASS:** artifact event precedes the flip; done is the engine's.
- **FAIL:** flip before artifact; a self-assessed done.
- **Evidence (decided 2026-11-16):** T-115 history (via `task_get`):
  artifact event `docs/intake-seat-design.md` at 2026-11-15T11:09:47.904Z
  (by `ticker-v:T-115`) → in-review status at 11:09:51.347Z → engine
  verdict comment (PASS) at 2026-11-16T08:28:26.602Z → done status at
  08:28:26.692Z. The artifact precedes the review flip by ~4 seconds; the
  worker never set done — the done transition follows the engine's verdict
  comment in the same second. T-116 touches no status on T-115. **PASS.**

## Verdict

**Decision protocol (mechanical):** run CT-001 → CT-010 in order against
the recorded design doc; decide each by its oracle with located evidence;
overall verdict = **APPROVE** if all ten PASS, **APPROVE-WITH-NOTES** if
all PASS with non-blocking observations, **REJECT** if any FAIL (a FAIL
blocks the design child in its own stage and escalates to the basis
T-105). The verdict is evidence for the engine's review pass, which alone
sets `done`; T-116 never sets a status on T-115.

**State at decision time (2026-11-16):** the artifact landed at `fd6db8b`
(2026-11-15); T-115 was set done by the engine's PASS verdict
(2026-11-16) **before** this record's verdict was written — the table
below is therefore the component-level evidence trail for that decision,
recorded per T-116's AC-3, not the decision itself.

| ID | Criterion | Result |
|---|---|---|
| CT-001 | artifact exists at the chain path + About header | **PASS** |
| CT-002 | per-component contracts complete (three faces, C-1..C-6, data structures) | **PASS** (note 1) |
| CT-003 | D-3 degrade-not-fail pinned as an explicit contract | **PASS** |
| CT-004 | workflows consistent with DR-1..DR-7; compliance map complete | **PASS** |
| CT-005 | traceability matrix resolves every element | **PASS** |
| CT-006 | S-1/S-2/S-3 as specified query contracts; script stays future work | **PASS** |
| CT-007 | no contract contradicts the system doc | **PASS** |
| CT-008 | no invented parallel structure vs [DSG] | **PASS** |
| CT-009 | Q-1 stays OPEN | **PASS** |
| CT-010 | artifact-before-review; done never self-assessed | **PASS** |

**Overall verdict: APPROVE-WITH-NOTES — all ten criteria PASS; the design
child T-115's done (set by the engine on 2026-11-16) is confirmed by this
evidence trail.** Nothing was argued around. Non-blocking notes:

1. **Error-face placement (CT-002 observation):** `price`/`cite` name no
   inline error clause; their failure behavior lives in [DES §6]'s
   consolidated store-failure row (DS-006). No failure mode is left
   unnamed in the document — a coherence-pass wording nit, not a contract
   gap.
2. **[DSG] citation-range variance (cosmetic; the T-115 engine review's
   observation, confirmed here):** the [DES] About header and the §8
   matrix row cite [DSG] "§4–§6" for C-4's pass-through while §2 C-4
   cites "§1–§8". Both ranges exist in [DSG] and both citations resolve;
   the variance is phrasing, not substance.
3. **DS-003's status (CT-003):** degrade-not-fail on an unknown
   `agentId` remains a design choice consistent with — but not stated by —
   the architecture; it is now pinned as an explicit component criterion
   (CT-003), which any future verification of that behavior checks
   against. The implementation stage inherits it as a contract.

Follow-ups (not this lane): the coherence pass may fold notes 1–2's
wording into [DES]; the implementation stage derives from [DES §2]/§4 and
is verified against [DES §2]/§6 with CT-003's oracle; the spot-check
script remains its own future ticket with its own tests ([SYS §7],
[ARCH §12]) — this record demands no script.

## Lane

One new file (`docs/intake-seat-design.tests.md`). Read-only on everything
else — including `docs/intake-seat-design.md` itself (the sibling's lane):
no source, doc, skill or store write besides this ticket's own bookkeeping
(artifact + verdict comment + the in-review move). T-115's status
untouched; `done` not self-assessed here — the verdict above is evidence
for the engine's decision, never the decision.
