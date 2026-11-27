# Autonomy-spectrum architecture — library tests (T-114)

## About this document

- **Kind:** `doc` / test-architecture-stage artifact of ticket T-114 (epic
  U-113, basis T-101, stage `test-architecture`): the paired verification of
  the architecture child T-113 — library criteria proving
  `docs/autonomy-spectrum-architecture.md` is complete, consistent and
  derivable 1:1 from its governing system definition, with every real-code
  claim checked read-only at HEAD.
- **Read by:** the reviewer and the engine's done/not-done decision for the
  T-113 pair (T-113 itself is already `done` — engine verdict PASS,
  2026-11-16 08:39Z; this doc is the pair's persistent verification
  evidence, never a self-assessment); the maintainer.
- **Related:** `docs/autonomy-spectrum-architecture.md` (T-113 — the subject
  under verification, commit `dcd5c9f`, sole-file lane);
  `docs/autonomy-spectrum-system.md` (T-101 — the governing system
  definition, source of truth for the 1:1 checks; cited as [SYS]);
  `docs/design-stage-pipeline.md` (§4 the tick algorithm — the
  no-invented-parallel-structure reference; cited as [DSG]); `AGENTS.md`
  ("The autonomy spectrum (the operating model, owner requirement
  2026-11-15)" — the doctrine; cited as [AGENTS]);
  `docs/acceptance-run-architecture.md` (the reuse mapping's source; cited
  as [AR]); `docs/intake-seat-architecture.tests.md` (T-106 — the format
  this doc follows); the store files `.harness/tasks/hephaestus/T-113.md`
  and `.harness/tasks/hephaestus/T-114.md` (the real store path every
  store-path oracle below cites).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps, sprint records and backlog statuses quoted below are historical;
  their verdicts stand as recorded on their dates.

## Library under test

- **Boundary:** the architecture artifact as a *document* contract — its
  component structure (C1..C5), its default-deny dependency rules (E1..E6
  plus the denied-edge set) and its guarantee flows (G-1..G-4) against the
  system doc it derives from.
- **Contract:** every [SYS] major part maps to exactly one primary
  component anchored in real code; every guarantee the ticket names traces
  through named components/edges to a [SYS] data flow; the store is the
  only shared substrate (no edge between any pair of C1/C3/C4); nothing is
  invented parallel to [DSG] or [AGENTS].
- **Architecture reference:** T-113 §Scope and §Chosen pattern
  (positioning), §Components and layers (structure), §Dependency rules
  (default-deny) (edges), §Guarantee flows through the architecture
  (traceability), §Interfaces to the real code (anchors), §Coverage.

## Derivation

The criteria below derive 1:1 from T-113's three acceptance criteria
(store `.harness/tasks/hephaestus/T-113.md`):

- **T-113 AC-1** (the artifact exists with component structure,
  per-component responsibilities and explicit dependency rules, convention
  header included) → LT-001..LT-003.
- **T-113 AC-2** (the four guarantees — store-verifiable run position,
  T-041 release floor, T-039 non-blocking recurrence, F-4 safe-end
  defaults — trace through the components to [SYS]'s parts) →
  LT-004..LT-006.
- **T-113 AC-3** (artifact recorded on the ticket before in-review; done
  never self-assessed) → LT-007.

## Test cases

| ID | Criterion (T-113 AC) | Oracle (source of truth) | Method | Expected |
|---|---|---|---|---|
| LT-001 | AC-1: the artifact exists as a `docs/` document with the repo About header, landed as the sole file of its lane | the file itself; git history of `docs/autonomy-spectrum-architecture.md` | read + `git log`/`git show` | present; commit `dcd5c9f` carries it sole-file; About header per convention |
| LT-002 | AC-1: component structure complete — C1..C5 each with layer, responsibility, real-code anchor, basis citation; every [SYS] major part mapped to exactly one primary component | `docs/autonomy-spectrum-architecture.md` §Components and layers + §System part → component mapping vs [SYS] §Major Parts, §The two dials, §Technology choices | read, side-by-side derivation check | five fully-specified rows; the four [SYS] parts map one-to-one to C1..C4; C5 derived from [SYS] doctrine sections, not invented |
| LT-003 | AC-1: dependency rules explicit and default-deny — every allowed edge E1..E6 sourced; every denied edge carries a structural reason; direction consistency holds; the named rule is structural (no edge between any pair of C1/C3/C4; the store the only shared substrate) | `docs/autonomy-spectrum-architecture.md` §Dependency rules (default-deny) vs [SYS] §Interfaces and [AR] §Dependency rules (default-deny), §Architecture decisions AD-002/AD-003 | trace check over the edge set | six sourced edges, seven reasoned denied edges, C2/C5 zero outgoing, the named rule holds in the edge set |
| LT-004 | AC-2: the four guarantee flows G-1..G-4 trace through named components/edges to [SYS]'s data flows, one-to-one with the flows the guarantees are drawn from | `docs/autonomy-spectrum-architecture.md` §Guarantee flows through the architecture vs [SYS] §End-to-end data flows | per-row flow lookup, chains resolved against the component/edge tables | 4/4 guarantees resolve; each chain uses only components/edges the doc defines |
| LT-005 | AC-2: real-code anchors observed read-only at HEAD; the [AR] reuse mapping resolves | the cited files at HEAD: `.opencode/agent/product-owner.md`, `.harness/tasks/hephaestus/` + the `task_*` verbs, `.opencode/skills/stage-pipeline/ticker-setup.ps1`, `eclipse/releng/release.ps1`, `ROADMAP.md`; [AR] §Modules and layers | grep/read, instances recorded | all five components' anchors present; C2≡[AR] M1, C3≡M2, C4=M4+release resolve |
| LT-006 | AC-2: no invented parallel structure vs `docs/design-stage-pipeline.md` and `AGENTS.md` — no new machine construct anywhere | [DSG] §4 (the tick algorithm), §6, §9, §11; [AGENTS] "The autonomy spectrum (the operating model, owner requirement 2026-11-15)" + "Write discipline" | quote-and-consistency check | C3's tick is [DSG] §4 verbatim; E4/E5/E6 point at existing surfaces; the basis's "no machinery" rule holds |
| LT-007 | AC-3: artifact-before-review store discipline on T-113; done never self-assessed | store `.harness/tasks/hephaestus/T-113.md` history via `task_get` | `task_get` | an artifact event precedes the in-review flip; the `done` transition carries the engine's verdict |

## Observed evidence

### LT-001 — artifact exists with About header, sole-file commit — **PASS**

- `docs/autonomy-spectrum-architecture.md` exists (349 lines).
  `git show --name-only dcd5c9f` lists exactly this one file — commit
  `dcd5c9f` ("T-113 executed (architecture stage of the autonomy-spectrum
  V-walk)…"), 1 file changed, 349 insertions: one new file, matching the
  declared lane. `git status --porcelain` on the file is empty (the
  committed content is the worktree content).
- The About header is present (lines 3–27): it states what the document
  IS (the architecture-level definition of the autonomy spectrum as a
  flow), who reads it (the product-owner agent, the stage tickers and the
  steward, the engine review pass, the paired test-architecture ticket
  T-114, the maintainer) and its Related set ([SYS], [AR], [DSG], [AGENTS],
  [PO], tickets T-113/T-114) — the repo docs convention T-113 AC-1
  requires.

### LT-002 — component structure complete, 1:1 with the system doc — **PASS**

§Components and layers defines exactly five components, every row carrying
all four fields (layer, responsibility, "Anchored in (real code)", Basis):
C1 `intake` (actor), C2 `store` (substrate), C3 `pump` (control), C4
`gates` (governance), C5 `doctrine` (knowledge, static).

Side-by-side derivation ([SYS] §Major Parts → the mapping table
§System part → component mapping):

| [SYS] major part | Architecture component | Derivation |
|---|---|---|
| 1. Goal intake — the product-owner agent | **C1** | 1:1 — the mapping table's own row; the responsibility (classify as GOAL/epic root, ask about the two dials with bounded round-trips, write the labeled record, cite the id back, never steers/verdicts/releases) restates [SYS] §Major Parts 1 and [SYS] §F-1 |
| 2. The store record — the GOAL/epic-root ticket | **C2** | 1:1 — [SYS] §Major Parts 2 carried through: single home, `epic` inheritance, "never restated per child, never held in chat memory or a session", git-auditable per D-006 |
| 3. The pump — stage tickers on schedules | **C3** | 1:1 — [SYS] §Major Parts 3: identical tick under both horizon modes, dial input at kickoff only, cadence no pump edge |
| 4. The review/acceptance gates | **C4** | 1:1 — [SYS] §Major Parts 4: engine verdicts gate `done` unchanged, human review gates only the release (T-041), T-039 hardens without gating |

All four [SYS] parts map to exactly one primary component each. The fifth
component (C5) is the static-knowledge carrier: its content (the two-dial
vocabulary and the safe-end defaults [SYS §The two dials], the T-041/T-039
rules [SYS §The two dials; §Technology choices], the tick algorithm
[DSG §4]) is entirely SYS/doctrine-sourced, and the doc's cross-cutting
list assigns it no new owner ("the dial record's shape — a C5 convention…
with C2 giving custody only"). No orphan component, no unspecified owner.

### LT-003 — dependency rules explicit, default-deny, named rule structural — **PASS**

- **Allowed edges:** E1..E6, each row with direction, interface verbs and
  basis citation — E1 C1→C2 [SYS §Interfaces 1; §F-1; §F-4], E2 C3→C2
  [SYS §Interfaces 2; DSG §4], E3 C4→C2 [SYS §Interfaces 1; §F-3],
  E4 C3→C5 [DSG §4; SYS §Major Parts 3; §F-3], E5 C1→C5
  [SYS §Major Parts 1; PO], E6 C3→stage schedules [SYS §F-2;
  §Key trade-offs]. All six cite real [SYS] interfaces; nothing unsourced.
- **Denied edges:** seven, each with a structural reason — C1→C3, C1→C4,
  C3↔C4 both directions, C2→anything, any machine component→the release
  trigger (named as "the T-041 floor as structure"), any component→C2
  files directly (the write discipline, [AGENTS]), any side channel
  carrying the run position ([SYS §Major Parts 2]).
- **Direction consistency holds in the edge set:** the six allowed edges
  are C1→C2, C3→C2, C4→C2, C3→C5, C1→C5, C3→schedules — C2 and C5 have
  zero outgoing edges, every store edge points toward C2, and **no edge
  exists between any pair of C1, C3, C4**: the named rule ("the store is
  the only shared substrate; intake, pump and gates never link directly")
  holds structurally, not by convention.
- **Inheritance verified:** the same default-deny form and the substrate
  rule are [AR]-sourced — [AR] §Dependency rules (default-deny) (line 116)
  and [AR] AD-002 "The store is the only shared substrate" (line 293) /
  AD-003 "The done authority is structural" (line 297) both exist; E3's
  "[AR] AD-003" cite and AD-2's "[AR] AD-002 inherited" cite resolve.
- The [SYS] §Interfaces coverage is complete: intake→store (E1),
  pump←store (E2), git audit (C2 custody, D-006), wave close (E3 +
  the release boundary), NEEDS-HUMAN (E2's `task_set_blocked`
  escalation onto the basis) — matching the doc's own §Coverage list.

### LT-004 — guarantee flows trace to the system doc's flows — **PASS, one wording note**

The §Guarantee flows through the architecture table maps, with named
chains:

| Guarantee | [SYS] flow | Chain | Resolves |
|---|---|---|---|
| G-1 store-verifiable run position | F-1 | C1 → E1 → C2 (record on the GOAL root) → `epic` inheritance → C3 kickoff read (E2) | yes — every element defined; evidence `task_get` + `git log` matches [SYS] §F-1's own evidence sentence |
| G-2 T-041 release floor | F-3 (release half) | C3 wave close → C4 engine verdicts → human review that accepts → release | yes — the no-machine-edge denial is LT-003's denied edge; matches [SYS] §F-3 |
| G-3 T-039 non-blocking recurrence | F-3 (recurrence half) | C5 division-map entry (data) → wave-close landing in C2 → fresh test-requirements acceptance ticket | yes — matches [SYS] §F-3's second sentence; "nothing's basis or readiness references it" matches [AGENTS] line 94 ("the stage-pipeline skill's division map carries the recurrence") |
| G-4 F-4 safe-end defaults | F-4 | C1 bounded round-trips → register with the open question recorded (E1) → C3 kickoff read → C5 defaults apply | yes — matches [SYS] §F-4 including "no dial state blocks a claim, a verdict, a wave close, or a commit" |

All four guarantees T-113 AC-2 names resolve through defined components
and edges to [SYS] flows. **Wording note (non-blocking):** the doc's
§Coverage bullet says "the four data flows F-1..F-4 → G-1..G-4,
one-to-one", but F-2 (the pump obeys the horizon) has no G-row of its own —
G-2 and G-3 are the two halves of F-3. F-2's substance is still traced
structurally: the C3 row cites [SYS §F-2], E6's basis is "[SYS §F-2;
§Key trade-offs]", and QA-3 carries the both-modes invariance. AC-2's
substance (the four named guarantees trace through the components) is
unaffected; the loose "one-to-one" phrasing belongs to a coherence pass on
T-113's doc, not to this lane.

### LT-005 — real-code anchors observed at HEAD; reuse mapping resolves — **PASS**

Every component anchor checked read-only at HEAD:

- **C1** `.opencode/agent/product-owner.md`: frontmatter lines 9–13 —
  `tools:` `write: false`, `edit: false`, `patch: false`, `bash: false`
  (the "write-denied for repo files" claim); "The two dials (goal intake –
  the autonomy spectrum)" carries the cited
  section; the bounded round-trips ("at most a couple of round-trips,
  then register with the open questions recorded") sit in its loop step; the store-
  proof sentence ("the store, not your memory, is the proof") sits at
  registration step 7 (all four re-verified 2026-11-26 – the section moved
  off its old line numbers).
- **C2** `.harness/tasks/hephaestus/` exists (239 ticket files today –
  71 live + 168 in `_archive/` –
  including
  `T-113.md` and `T-114.md`, both archived since; it read 333 files at
  this run's 2026-11-16 check); the `task_*` verb surface is real (this
  ticket's own bookkeeping runs on it); D-006 (store files git-versioned
  Markdown) resolves at `ROADMAP.md`'s decision-records paragraph
  ("D-006: the store substrate stays git-versioned Markdown").
- **C3** `.opencode/skills/stage-pipeline/ticker-setup.ps1` exists; its
  Usage line documents `[-IntervalMinutes 5] [-Cap 4]` (it read 15 at the
  2026-11-16 check — the owner set the cadence to 5 the same day), and its
  requirements comment requires
  `"experimental": { "automations": true }` (renamed from `schedules` in
  2.3.1, flagless since 2.4.0) — the schedule lane E6 names;
  the `stage-pipeline` skill file exists beside it.
- **C4** `eclipse/releng/release.ps1` exists (the release machinery
  reachable "only from the human-review context"); the engine-verdict side
  resolves in [AR] §Modules and layers M4 ("the ONLY `done` authority",
  the M4 `verdict` row).
- **C5** the doctrine surfaces all exist: [AGENTS] "The autonomy spectrum
  (the operating model, owner requirement 2026-11-15)" — the paragraph of
  that name; `ROADMAP.md` Vision/**Product Goal** (lines 18/29); [PO]
  "The two dials (goal intake – the autonomy spectrum)"; [DSG] §4 (the
  ticker-cadence section).

Reuse mapping ([AR] §Modules and layers): **C2 ≡ M1** (the M1 `store` row — same
substrate description: epic/basis links, readiness, append-only history,
`.harness/tasks/hephaestus/` + `task_*` verbs), **C3 ≡ M2** (the M2
`ticker` row — same
fixed tick and the same steward/schedule/`ticker-setup.ps1` anchor), **C4 =
M4 + the release boundary** (the M4 `verdict` row). The mapping is reuse, not
re-decision: the spectrum doc adds C1, the dial record as C2 data and the
C5 parameter layer, exactly as AD-1 states.

### LT-006 — no invented parallel structure vs [DSG] and [AGENTS] — **PASS**

Quote-and-consistency check of every machine-side claim:

- **C3's tick is [DSG] §4 verbatim.** The doc's §Interfaces: "the tick is
  [DSG §4] verbatim — SCAN → BLOCKED → DONE → READY → CLOSE".
  [DSG] §4's tick-algorithm subsection (always the same, every launch)
  reads exactly: 1. SCAN, 2. BLOCKED
  → ESCALATE BACK, 3. DONE → PROCESS FORWARD, 4. READY → KICKOFF, 5.
  CLOSE; and [DSG]'s own framing — "The ticker has no decisions to make —
  the procedure is fixed, parameterized only by its stage" — is the same
  pump invariance the architecture claims. E2's verb set
  (`task_plan_sprint`, claim under the spawn cap, division/link creation,
  `task_close_sprint`, `task_set_blocked` onto the basis) is [DSG] §4's
  own vocabulary.
- **E4 points at an existing surface.** [DSG] §6: "One common skill —
  `stage-pipeline`" — the skill file exists; the T-039 recurrence entry is
  [AGENTS]-sourced data (line 94: "the stage-pipeline skill's division map
  carries the recurrence"), carried as C5 data the pump already loads —
  not a new pump behavior.
- **E6 points at existing primitives.** [DSG] §4 fixes the schedule lane
  (per-stage interval via `ticker-setup.ps1`), §9 keeps the Board toggle
  as the tickers' schedules switch, §11 names `experimental.schedules`
  with "list/pause/delete = the disarm path" — the steward arm/disarm at a
  time-boxed horizon is [SYS] §F-2 behavior on [DSG]-existing primitives.
- **No new machine construct anywhere.** No spectrum engine (§Chosen
  pattern evaluates and rejects it, citing [SYS §Overview] "they add no
  machinery"); no new store mechanic (C2 = [AR] M1 with "Text custody
  only: no dial schema, no dial validation, no dial-driven store
  behavior"); no new stage; no second coordination channel — the
  store-as-only-substrate rule restates [DSG]'s "Stages are decoupled
  through the store — never a direct handoff". The denied edges mirror
  standing doctrine: the write-discipline denial quotes [AGENTS] "Write
  discipline" (the **Write discipline** paragraph); the NEEDS-HUMAN
  escalation threshold is
  the autonomy-spectrum paragraph's review-cadence line; the dial vocabulary and safe-end defaults are
  that paragraph's pass-through (all three name-cited post-T-231 style,
  re-verified 2026-11-26).

### LT-007 — store discipline on T-113 — **PASS**

Store history of `.harness/tasks/hephaestus/T-113.md` (via `task_get`,
2026-11-16):

- Artifact event `docs/autonomy-spectrum-architecture.md` at
  `08:28:55.570Z` (by `ticker-v:T-113`) precedes the status update at
  `08:29:02.795Z` (in-review) — **the artifact was recorded before the
  review move**, as AC-3 requires.
- The `done` transition at `08:39:17.532Z` carries `by: null` (the
  engine), immediately after the review comment `08:39:17.448Z`
  (by `ticker-v:review` — engine verdict PASS). **Done was never
  self-assessed**; the store file at HEAD reads `status: done`,
  `epic: U-113`.
- Recorded anomaly (documented by T-113's own completion report and by the
  engine review, both non-blocking): two artifact records exist — the
  first (08:28:55) carries a note describing a draft shape ("C-1..C-7 /
  DR-1..DR-7") the committed doc does not use; the second (08:31:20)
  describes the committed content. AC-3's substance is judged on the
  first record, which precedes the flip; the stale note is T-113's
  recorded history and is not repaired by this lane.

## Verdict

| ID | Criterion | Result |
|---|---|---|
| LT-001 | artifact + About header, sole-file commit | **PASS** |
| LT-002 | component structure complete, 1:1 | **PASS** |
| LT-003 | dependency rules explicit, default-deny, named rule structural | **PASS** |
| LT-004 | guarantee flows trace to [SYS] flows | **PASS** (one wording note — F-2, non-blocking) |
| LT-005 | anchors observed at HEAD; reuse mapping resolves | **PASS** |
| LT-006 | no invented parallel structure | **PASS** |
| LT-007 | artifact-before-review; done never self-assessed | **PASS** |

**Overall judgment: PASS — the paired verification supports the
architecture child T-113.** The artifact's structure is complete (five
components with real anchors, six sourced default-deny edges, seven
reasoned denials, the named rule structural), derivable 1:1 from
`docs/autonomy-spectrum-system.md`, all four guarantees trace through
named components to the system doc's flows, every code-cited anchor was
observed at HEAD, and nothing is invented parallel to [DSG] or [AGENTS].
No criterion failed; the single finding is the §Coverage wording note on
F-2 (LT-004), recorded with its disposition. T-113's `done` stands as the
engine's verdict; this doc is the pair's verification evidence, not the
decision.

## Lane

One new file (`docs/autonomy-spectrum-architecture.tests.md`). Read-only
on everything else: no source, doc, skill or store write besides this
ticket's own bookkeeping (artifact + comment + status). T-113 and its
artifact untouched; `done` not self-assessed anywhere — the verdict above
is evidence for the engine's decision, not the decision.
