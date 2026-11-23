# Library Tests: the unified ticker-v architecture (test-architecture for T-103)

- **About this document:** the paired test-architecture product of ticket
  T-104 (project `hephaestus`, stage `test-architecture`, epic U-112, basis
  T-055) — the verification leg paired to the architecture child T-103. It
  derives the library criteria 1:1 from T-103's three acceptance criteria,
  names an observable oracle for each criterion, and records the per-criterion
  verdict with observed evidence plus an overall judgment for T-103's
  done/not-done decision. The library under test is a **doc architecture**:
  its contract is the architecture artifact's structure (components C1..C8,
  default-deny edges E1..E11 plus denied-edge rules) and its traceability
  claims (I-1..I-5, S-1..S-6, INV-1..INV-7, the pass-through table, the routed
  questions and Routes A/B/C). **Read by:** the engine verdict deciding
  T-103's done (via this ticket's in-review), the PM, the maintainer.
  **Related:** `docs/unified-ticker-architecture.md` (the subject under
  verification, commit `6dda34e`; cited as [ARCH]),
  `docs/unified-ticker-system.md` (the upstream basis; [SYS]),
  `docs/unified-ticker-requirements.md` (FR/NFR/constraint ids; [REQ]),
  `docs/design-stage-pipeline.md` ([DSG]),
  `.opencode/skills/stage-pipeline/SKILL.md` ([SKILL]),
  `.opencode/skills/stage-pipeline/ticker-setup.ps1` ([SETUP]),
  `.opencode/agent/reviewer.md` ([REV]); tickets T-103 (architecture, done by
  engine verdict), T-104 (this verification ticket).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps, sprint records and backlog statuses quoted below are historical;
  their verdicts stand as recorded on their dates.

## Library under test

- **Artifact:** `docs/unified-ticker-architecture.md` @ `6dda34e`
  (`6dda34ead236531d045ab45d338c8971ccf77123`, "docs(ticker-v): architecture
  of the unified V-ticker (T-103)", 1 file changed, 498 insertions — observed
  via `git show --stat`).
- **Boundary:** stage `architecture` (ticket T-103); the doc is the stage
  product, not code — library tests here are structure-derivation and
  traceability-resolution checks against the stage's sources of truth, plus
  read-only reality anchors for script/config claims ([SETUP], [SKILL],
  [REV], the task store).
- **Contract:** T-103's three acceptance criteria (read from the store):
  AC-1 the doc exists (docs/ About-header convention) with component
  structure, per-component responsibilities and explicit dependency rules
  derived from [SYS]; AC-2 every interaction-model element I-1..I-5 and every
  scenario S-1..S-6 traces through the architecture's components to its
  evidence, with Q-001..Q-004 routed and the prototype deltas kept on their
  Route A/B/C destinations; AC-3 the artifact is recorded on the ticket and
  the ticket reached in-review with done never self-assessed.
- **Derivation rule:** every criterion below maps to exactly one T-103 AC
  (see the derivation table); no criterion tests content the ACs do not cover.

## Derivation: T-103 acceptance criteria → library criteria

| T-103 AC | Library criteria | What the criteria prove |
|---|---|---|
| AC-1 (structure derived from [SYS]) | LT-001..LT-006 | the doc exists per convention; every [SYS] major part owns exactly one component; responsibilities and edges E1..E11 are basis-derived, default-deny, and direction-consistent; denied edges match the basis's prohibitions; no invented parallel structure vs [DSG] |
| AC-2 (traceability through the components) | LT-007..LT-013 | I-1..I-5, S-1..S-6, INV-1..INV-7 resolve one-to-one to real [SYS] sections; Q-001..Q-004 and dead-claim detection stay routed; Routes A/B/C keep their destinations with observed delta instances; the pass-through table cites real sections and supersedes only what [SYS §Supersession] names; FR/NFR/constraint ids resolve to [REQ] |
| AC-3 (artifact + in-review, done never self-assessed) | LT-014, LT-015 | the artifact was recorded on T-103 before in-review and done came from the engine; the doc's machinery anchors exist and its store claims match observed store state |

## Test cases

| ID | Rule / behaviour (criterion) | Oracle | Expected | Verdict |
|---|---|---|---|---|
| LT-001 | [ARCH] exists at the AC-named path with the repo-doc About header (docs/ convention) | the file itself; AGENTS.md doc-about convention | header present, file committed | PASS |
| LT-002 | every [SYS §Major parts] element maps to exactly one primary component; the PO lands outside the runtime loop | [SYS] Major parts list vs [ARCH] §System part → component mapping | 1:1 mapping, no part unmapped or double-owned | PASS |
| LT-003 | per-component responsibilities are basis-derived: each Basis citation resolves and the responsibility text matches the basis | [ARCH] §Components and layers vs [SYS]/[DSG] sections cited | every citation resolves; no invented responsibility | PASS |
| LT-004 | dependency rules are explicit and default-deny: E1..E11 carry exactly the interactions of [SYS I-1..I-5]; the layer rule is stated; every edge's Basis resolves | [ARCH] §Dependency rules vs [SYS §The interaction model], [DSG §5/§6] | allowed edges fully enumerated, each traceable | PASS |
| LT-005 | denied edges match the basis's prohibitions, each with its structural reason | [SYS I-2/I-3/§Invariants], AGENTS.md §Write discipline, [REV], [DSG §5], [SKILL] | all ten denied rules anchored in the basis | PASS |
| LT-006 | direction-consistency claim holds on the edge set: C1/C7 zero outgoing, C2 hosts only, graph acyclic | [ARCH] §Dependency rules direction paragraph vs the E1..E11 table itself | claim derivable from the table | PASS |
| LT-007 | I-1..I-5 each traced through components to evidence; no element missing or re-specified | [ARCH] §The interaction model through the components vs [SYS §The interaction model] | all five traced, content matching the basis | PASS |
| LT-008 | S-1..S-6 each re-traced through the component chain; no new mechanism invented (dead-claim detection stays routed to design) | [ARCH] §Scenario walks vs [SYS §Restart and re-arm scenarios] | all six walks present and basis-faithful | PASS |
| LT-009 | INV-1..INV-7 anchored one-to-one; preserved-by mappings consistent with [SYS §Invariants] | [ARCH] §Invariant anchors vs [SYS §Invariants] | 7 anchors, mappings consistent | PASS |
| LT-010 | Q-001..Q-004 and dead-claim detection stay routed to their owning legs; the architecture adds constraints only | [ARCH] §Open questions vs [SYS §Open questions carried forward], [SYS S-2] | owning legs unchanged, no answers invented | PASS |
| LT-011 | prototype deltas keep their Route A/B/C destinations (stage + file lane identical to [SYS]); the routes' content claims resolve in the real files | [ARCH] route table vs [SYS §Downstream routes]; [SETUP], [SKILL] read-only | destinations unchanged; delta instances observed | PASS |
| LT-012 | pass-through rows cite real [SYS]/[DSG] sections; superseded content limited to [SYS §Supersession]'s exact scope | [ARCH] §Pass-through vs [DSG] section list, [SYS §Supersession] | every row resolves; supersession scope exact | PASS |
| LT-013 | FR-001..FR-015, NFR-OPER/REL/COST-001 and C-A..C-F exist in [REQ] and map to real [ARCH] sections | [REQ] definitions; [ARCH] §Traceability "stated here" cells | all ids resolve; mappings point at real sections | PASS |
| LT-014 | the artifact was recorded on T-103 BEFORE in-review; done was applied by the engine, never self-assessed | the task store (T-103 history + comments), read-only | artifact < in-review < engine done | PASS |
| LT-015 | the doc's machinery anchors exist and its store claims match observed store state | `.harness/tasks/hephaestus/`, `_meta.json` sprint records, [REV], [SETUP] | anchors present; ONE rolling record `ticker-v` observed | PASS |
| LT-016 | no invented parallel structure vs [DSG]: only the ten→one point is superseded; carried-over sections appear as mapping, not re-decision; no new store verbs, roles or scheduling topology | [SYS §Supersession]; the E-table verb set vs the real `task_*` surface; [DSG §1] roles | nothing invented where [DSG]/[SYS] fix structure | PASS |

## Evidence and verdicts (per criterion)

- **LT-001 PASS.** `docs/unified-ticker-architecture.md` exists (498 lines);
  its About header (lines 3–31) states what the document IS (the
  architecture-level definition of the unified V-ticker), who reads it and
  its relations — the docs/ convention AC-1 names. Commit `6dda34e` touches
  exactly this one file (observed: `git show --stat` → 1 file changed, 498
  insertions).
- **LT-002 PASS.** [SYS §Major parts] names eight parts: the opencode
  service, the `ticker-v` schedule, the steward session, worker subagents,
  reviewer subagents, the task store, the rolling sprint record `ticker-v`,
  and the PO/kickoff assistant. [ARCH]'s mapping table assigns each exactly
  one primary component (service→C2, schedule→C3, steward→C4, workers→C5,
  reviewers→C6, store→C1, rolling record→C1 as DATA) and maps the PO outside
  the runtime loop (operator edges only) — the [SYS] "three runtime actors
  around the store" structure preserved, no part unmapped, none double-owned
  (the record sharing C1 with the store is [SYS I-5]'s own assignment: store
  DATA driven through C4's writes, recorded as such).
- **LT-003 PASS.** All eight component rows carry Basis citations that
  resolve (re-verified 2026-11-16 against the source docs after T-103's
  citation correction): C1→[SYS §Major parts + I-3; I-5], C2→[SYS §Major
  parts + remember-vs-forget], C3→[SYS §Major parts + I-1], C4→[SYS §Major
  parts + I-2..I-4], C5→[SYS §Major parts + I-2; DSG §5], C6→[SYS §Major
  parts + I-2; I-3], C7→[DSG §6] (corrected from the dangling "[SYS
  §Related]" — [SYS] defines no Related section, only its About-header
  bullet; [DSG §6] "Stage skills: one common process skill, parameterized
  by stage" carries C7's content), C8→[SYS I-1; S-3/S-4; §Route A].
  Responsibility spot-checks match the basis text: the steward "never works
  a ticket itself and never accepts one" ([SYS] verbatim); the worker's
  store footprint is the four own-ticket verbs ([SYS I-2]); the reviewer is
  "one per in-review ticket per stage visit" ([SYS §Major parts verbatim,
  FR-006 step 2); C8's defaults `-IntervalMinutes 15` / `-Cap 4` observed in
  [SETUP] lines 15–16 and its `name = "ticker-v"` at line 103.
- **LT-004 PASS.** The layer rule ("dependencies point toward the substrate,
  static knowledge or platform only... Default deny: an edge exists only if
  listed here") is stated. E1..E11 enumerate exactly the basis's
  interactions: E1 arming ([SYS I-1]), E2 firing ([SYS I-1]), E3 the store
  interface in the tick's fixed order incl. gated close and `task_release`
  reconciliation ([SYS I-3; S-2]), E4 spawn/admission/reconnect ([SYS I-2;
  I-4; I-3 store-loss]), E5 own-ticket writes ([SYS I-2]), E6/E7 reviewer
  reads and gate runs ([SYS I-2; reviewer.md]), E8/E9 skill loads ([DSG
  §6]), E10/E11 operator edges ([SYS §Major parts PO; FR-015; AGENTS.md
  escalation surface]). Every Basis cell resolves to a real section; no
  allowed interaction of [SYS I-1..I-5] lacks an edge; no edge lacks a basis.
- **LT-005 PASS.** All ten denied rules anchored: C1→anything (store passive,
  [SYS §The tick as a system event]); C2 no agency (host owns surfaces,
  [SYS §Major parts]); C5/C6→C4 (no mid-flight orders — [SYS I-2] reports on
  idle, [DSG §5]); C5↔C5 / C6↔C6 lateral (a worker never touches another
  ticket, [SYS I-2]); C4 discipline work / bare `done` ([SYS §Major parts
  steward; FR-006]); C5→`done` ([SYS I-2] "never sets `done`"; [SKILL]
  Fulfillment step 4); C6 file edits (observed: [REV] frontmatter
  `permission: edit: deny`, lines 7–8); C8→C1 (reconciliation is C4's
  first-pass duty, [SYS S-2/S-4]); direct store-file writes (AGENTS.md
  §Write discipline); per-stage schedules ([SYS INV-7, FR-004]). Each rule's
  reason is the basis's own, not a new one.
- **LT-006 PASS.** Checked against the E-table itself: C1 appears only as a
  target (E3, E5, E6 — plus E11); C7 appears only as a target (E8, E9); C2
  appears as target of E1/E4 and the mechanical carrier of E2's delivery
  ("E3/E5/E6 ride the store transport it hosts"), originating nothing. Every
  actor edge (C4, C5, C6, C8, PO) terminates at C1/C7/C2/C8 — no edge from
  substrate, knowledge or platform outward as agency; the acyclicity claim is
  derivable from the table.
- **LT-007 PASS.** All five interaction-model elements have dedicated
  paragraphs with component/edge assignments and evidence: I-1→C3+E1+E2
  (arm-time prompt, `runCount`/`lastOutcome` health, name-as-identity,
  orphaned-steward dormancy — all [SYS I-1] content); I-2→E4+C5/C6 lifetimes
  (three-level tree, worker death → orphaned claim, reviewer skip rule — the
  skip resolves in [SKILL] step 3's "not owned by one of your running
  subagents"); I-3→E3 (read/write paths, one attempt per ticket, reconnect —
  [SYS I-3] verbatim); I-4→C4's procedure + AD-004 (count-before-spawn
  counting BOTH worker and reviewer children, defer-at-cap planned-not-
  claimed, steward-instance-local counter — [SYS I-4] verbatim); I-5→C1
  data + E3's gated lifecycle (U-108 reopen, in-flight protection — [SYS
  I-5] verbatim). Nothing re-specified beyond the mapping.
- **LT-008 PASS.** All six scenario walks present and basis-faithful: S-2's
  three reconciliation steps match [SYS S-2] steps 1–3 exactly, including the
  routed detection mechanism ("bounded staleness vs session-existence probe —
  a design-stage decision per [SYS S-2]") and the system contract (released
  and re-queued, never silently left `in-progress` forever, never
  double-spawned). S-3 carries the cap-drain-or-reuse constraint [SYS S-3]
  states; S-5 includes the carry-over subtlety (live workers from earlier
  ticks reduce free slots); S-6 is the gated close ([SYS S-6]). No walk
  invents a mechanism the basis does not fix.
- **LT-009 PASS.** The anchor table has exactly seven rows matching [SYS
  §Invariants]' seven; each preserved-by mapping is consistent with [SYS]'s
  own column (INV-1/INV-2 → E1's guard; INV-3 → count-before-spawn +
  defer-at-cap + S-3 drain-or-reuse; INV-4 → E3's fixed order; INV-5 → U-108
  reopen + gated close; INV-6 → the total remember-vs-forget split + first-
  pass reconciliation; INV-7 → single arming path with no per-stage mode).
  None re-derived; each names the architecture element that carries it.
- **LT-010 PASS.** The routed-questions table keeps every owning leg
  unchanged from [SYS §Open questions carried forward]: Q-001 → Route A
  (design/implementation vs the actual schedule API), Q-002 → design
  constrained by S-3, Q-003 → design decision flagged for the paired test
  ([SYS Q-003]'s own flag, T-035 AC-2), Q-004 → owner call at arm time; the
  dead-claim detection row routes to the design leg per [SYS S-2]. The
  architecture's additions are constraints only (E1's observable exactly-one
  contract; INV-3-survives-replacement for Q-002; "changing cadence/cap is a
  re-arm, never a new edge") — no mechanism answers are invented; the closing
  statement "there are no NEW open questions" holds against the table.
- **LT-011 PASS.** Destinations unchanged vs [SYS §Downstream routes]:
  Route A → the ticker-v arc's implementation stage, file lane the script
  only, content deltas #1 + #3; Route B → a skill-lane ticket, SKILL.md only,
  delta #2; Route C → the coherence-documentation lanes, delta #4's
  constraint half. Observed instances (all read-only):
  - delta #1 — [SETUP] line 107 POSTs `/api/experimental/schedule`
    unconditionally (no list/lookup-by-name guard anywhere in the script):
    the duplicate-on-re-arm defect Route A's guard removes, exactly as [ARCH]
    C8 and E1 describe.
  - delta #3 — [SETUP] line 25 `if (-not $BaseUrl -or -not $Password)` tests
    `$Password`, which the param block (lines 14–21) never defines (the
    parameter is `$Token`): the fallback branch always wins and explicit
    `-BaseUrl`/`-Token` overrides are clobbered — the `$Password`→`$Token`
    fix is real and correctly located.
  - delta #2 — [SKILL] lines 126–128 still say "re-run `ticker-setup.ps1`
    with the same `-Stages`" while the script has no `-Stages` parameter:
    the per-stage-era leftover Route B reconciles, exactly as [ARCH]'s C8/INV-7
    rows and Route B anchor state.
  - delta #4 (constraint half) — [SKILL] lines 112–114 document
    `experimental.subagent_depth >= 2`; [ARCH] carries it as the depth
    constraint on E4 and routes the documentation duty to Route C.
- **LT-012 PASS.** Every pass-through row cites a real section: [SYS] rows
  (§Overview, I-1..I-5, §The tick as a system event, §Invariants, §Restart
  and re-arm scenarios, §Technology choices) and [DSG] rows (§1, §4, §2, §3,
  §5, §6, §7, §8, §11) — all exist in their files. The two SUPERSEDED rows
  cover exactly [SYS §Supersession]'s scope ([DSG §1] model + [DSG §4]
  cadence/rolling-record topology, on the ten→one point only); the
  carried-over content [SYS §Supersession] actually lists (§2 store
  contract, §3 link contract, §5 fulfillment narrow-brief rules, §8 division
  and the engine verdict, §11 native-first inventory — plus the readiness/
  STALE semantics) all appear as mapping rows with pass-through rationale;
  [DSG §6] and [DSG §7] are [ARCH]'s own additional pass-through rows, not
  [SYS §Supersession] carry-overs (sentence corrected 2026-11-16 — the
  earlier version misquoted the carry-over list as including §6/§7) —
  nothing outside the supersession scope is dropped, nothing inside it is
  carried.
- **LT-013 PASS.** [REQ] definitions observed (read-only `Select-String`):
  FR-001..FR-015 each defined exactly once ("**FR-0xx (C...)**" definition
  lines); NFR-OPER-001, NFR-REL-001, NFR-COST-001 present; C-A..C-F present.
  [ARCH] §Traceability maps every id to a real [ARCH] section — spot-checked
  FR-001→C3+E1+INV-1/INV-2 anchors, FR-002→E2, FR-005→C4 via E8, FR-006→E3,
  FR-014→E3 read path, FR-015→E10+QA-1+INV-6+S-2/S-4, NFR-OPER-001→QA-3+I-1,
  NFR-REL-001→QA-1+the split, NFR-COST-001→QA-2+I-4, C-A→C2, C-B→the depth
  constraint on E4, C-C→E1's parent anchoring, C-D→no provider/model ids
  ([ARCH] carries none), C-E→the task_*-only write rule, C-F→arm-time
  parameters in E1/E2. All mappings resolve.
- **LT-014 PASS.** Observed from the store (read-only): T-103 history —
  artifact `doc:docs/unified-ticker-architecture.md` recorded 10:56:21.222 →
  status update (in-review) 10:56:21.291 → status update (done, engine
  verdict application) 11:02:26.704, with the division comment by `ticker-v`
  ("Division of done T-103 completed... design child T-117 + paired
  test-design T-118") recording the engine outcome. The fulfiller's
  completion report (10:56:42) explicitly never set done. Artifact before
  in-review before engine done: AC-3 holds.
- **LT-015 PASS.** Machinery anchors observed: `.harness/tasks/hephaestus/`
  exists (`Test-Path` → True; one Markdown file per ticket, e.g. T-104.md) —
  C1's anchor; the `service.json` url+token discovery is the pattern [SETUP]
  lines 26–33 use — C2's anchor; [REV] `edit: deny` plus the exact quoted
  sentence ("the engine applies it, you stay read-only", [REV] line 61) —
  C6's anchor; `.opencode/skills/stage-pipeline/` with SKILL.md and
  ticker-setup.ps1 — C7/C8's anchors. Store claim observed: `_meta.json`
  sprints contains exactly ONE `ticker-v` record
  (`status: active, created 2026-11-15T07:53:53.455Z`) — the ONE rolling
  record of C1/I-5, matching [ARCH]'s store claims.
- **LT-016 PASS.** Nothing invented in parallel to [DSG]: the only
  superseded [DSG] content is the ten→one scheduling point ([SYS
  §Supersession]'s exact scope — LT-012); the carried-over sections appear as
  pass-through mappings; the edge table's verb set (`task_set_blocked`,
  `task_update`, `task_create`, `task_plan_sprint`, `task_claim`,
  `task_close_sprint`, `task_release`, `task_readiness`, `task_get`,
  `task_list`, `task_board`, `task_add_artifact`, `task_add_comment`) is the
  real store surface — no new verb is invented; the role set (steward,
  worker, reviewer, PO) matches [DSG §1]'s roles table; no per-stage
  scheduling topology is reintroduced anywhere.

## Overall verdict

**PASS — all 16 criteria hold.** [ARCH]'s structure is complete and
consistent with its basis: the C1..C8 component set and the E1..E11 +
denied-edge rules are derivable 1:1 from [SYS §Major parts] and [SYS §The
interaction model]; every traceability claim (I-1..I-5, S-1..S-6,
INV-1..INV-7, pass-through rows, routed questions, Routes A/B/C, the
FR/NFR/constraint table) resolves to a real section of [SYS], [DSG], [REQ],
[SKILL] or [REV]; the script/config claims are confirmed by read-only
observation of the actual files and the actual store; and the AC-3 provenance
chain (artifact → in-review → engine verdict) is evidenced by the store
history. **The architecture child T-103's done verdict is UPHELD** — its
acceptance criteria AC-1 and AC-2 are fulfilled by the document as verified
above, and AC-3's process facts are verified from the store.

## Non-blocking observations

- [ARCH]'s component diagram places the `[PO] operator` block under C4 with
  a lone `▲`; the normative edges are unambiguous in the table (E10 PO→C8,
  E11 PO→C1). ASCII layout imprecision only — no rule contradicts the edge
  table.
- The store retains the retired topology's closed per-stage sprint record
  (`ticker-requirements`, `status: closed`) alongside the historical
  S-records. INV-7 bans per-stage *schedules* (arming paths), not historical
  sprint records; the ONE `ticker-v` rolling record exists as specified. No
  violation — noted only so Route B's coherence pass does not misread it.
