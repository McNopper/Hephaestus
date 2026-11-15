# Architecture tests: the acceptance-run architecture doc (U-111) — Part I amendment record (verifies T-098), Part II library criteria (A-004)

- **About this document:** the library-level (test-architecture) verification
  record for the T-097 coherence child **T-098** — criteria derived 1:1 from
  T-098's acceptance criteria, each with an observable oracle (grep hits, git
  diff, encoding/alignment checks), and the recorded verdict. This record is
  amendment-scoped: it verifies the T-098 attribution amendment (fulfilled
  by T-099). **Part II** is ticket **A-004**'s library-criteria record —
  it verifies the same document's module/edge structure (M1–M5, E1–E7)
  against the live store; both parts keep their own verdicts. **Read by:** the engine review
  pass (weighs the verdict), the ticker-v stage ticker, the maintainer.
  **Related:** `docs/acceptance-run-architecture.md` (under verification, as
  amended by T-098), `docs/acceptance-run-system.md` (governing basis, as
  amended by T-097 — cited as [SYS]), `docs/acceptance-run-system.tests.md`
  (IT-A-102 basis context, read-only), tickets T-097 (Part I basis), T-098
  (Part I under verification), T-099 (Part I fulfillment), A-003 (Part II:
  the architecture doc's fulfillment ticket), A-004 (Part II fulfillment —
  this part's recording ticket).
  **Provenance note:** the tasks MCP namespace dropped before T-099's
  completion comment could be written; this file is the durable record of the
  criteria and verdict, to be linked onto ticket T-099 by the reviewer/ticker.

## Part I — amendment verification of T-098 (ticket T-099)

*(Everything through the closing "Verdict" and snapshot line below is Part
I's, preserved verbatim.)*

## How these tests run

Static documentation-level verification: every criterion is decided by an
observable oracle on the file itself or its git diff — ripgrep over
`docs/acceptance-run-architecture.md`, `git diff` against HEAD
(index 6f7e7b3 → working tree), and byte-level encoding checks. No store
writes are performed or needed for the decisions below. Verdict semantics:
PASS/FAIL per criterion; the overall verdict (APPROVE / REJECT /
APPROVE-WITH-NOTES) is advisory to the engine review pass, which alone sets
`done` (D-3).

## Criteria and results

### AT-A-901 · Zero contradictions with the amended basis (T-098 AC1, part 1) — PASS

- Oracle: ripgrep over the amended architecture doc for the superseded
  wording patterns `with its actor`, `engine actor`, `History-with-actor`,
  `history (with actors)`, `stamp history with actors`, `transition by
  worker`, `append-only history with actors`, `done (engine`.
- Evidence: **0 matches.**
- Residual-`actor` audit: 24 substring matches, classified — 1 substring
  false positive (`re`**`actor`**``, M4 row's `eclipse/QUALITY.md` reactor
  reference); run-participant sense (lines 49, 56, 57, 70, 92, 97, 146, 160,
  202, 207, 275, 289 — including the verbatim [SYS §Overview] quote "task
  store plus four actors around it"); true claim/artifact stamping, which
  [SYS §Interfaces 1] confirms (lines 100, 174, 220, 226, 228, 256); explicit
  negation or amendment context (lines 25, 27, 104, 270 — "no stamped
  actor", "not on stamped transition actors", "attribution is partial");
  amended diagram box (line 81, `history (actors partial)`).

### AT-A-902 · Coherence with the amended system definition (T-098 AC1, part 2) — PASS

- Oracle: sentence-level comparison of the amended D-1/D-3 flows and the
  D-point evidence table against [SYS §Interfaces 1 / §data flows].
- Evidence: the cross-cutting bullet (`History-with-attribution`) and the M1
  invariant mirror the settled rule — partial attribution, claim/artifact
  writes actor-stamped, status transitions bare ("record only the change"),
  identities caller-asserted, engine attribution via the review pass's
  observable marks. D-1 mirrors [SYS §data flows D-1] sentence-for-sentence
  (bare `in-review` flip, worker identity in the adjacent fulfillment
  comment). D-3 mirrors [SYS §data flows D-3]: `done` with the engine's mark
  — annotated done event OR engine-verdict comment within the review batch,
  pre-2026-11-15 dones grandfathered. The D-1 evidence-table row is
  character-identical to the system doc's D-1 row; the D-3 row mirrors its
  counterpart and, correctly for pass-through, omits only the observed
  identity names (`review` / `ticker-v:review`) that live in the source of
  truth.
- Pass-through discipline: the full mark rule is NOT restated — no observed
  spans (51 ms / ~17 min), no identity enumeration, no re-specification; the
  amendment note (header) cites `[SYS §Interfaces 1]` as amended by T-097 as
  source of truth and states the doc "re-points its wording only".
- Amendment note: present, cites T-097. ✓

### AT-A-903 · Minimal diff, lane-clean (T-098 AC2) — PASS

- Oracle: `git diff` on `docs/acceptance-run-architecture.md`.
- Evidence: exactly one file, **37 insertions(+), 19 deletions(-)** —
  byte-identical to T-098's completion report. Git groups the edits as 7
  hunks (adjacent edits merge); the report's "9 hunks" enumerates the same 9
  logical changes: header amendment note, cross-cutting bullet rename,
  diagram box, M1 invariant, D-1 flow, D-3 flow, D-1 evidence row, D-3
  evidence row, QA-1 — every one attribution-related, zero unrelated content
  in the diff.
- UTF-8 clean: no BOM (first bytes `23 20 41` = `# A`), zero mojibake markers
  (Ã / â€ / ï¿½ / U+FFFD), uniform LF endings (CRLF=0, lone-LF=359; git's
  LF→CRLF warning is repo-wide autocrlf behavior, pre-existing).
- ASCII diagram alignment intact: the amended box line
  `│ claims · history (actors partial) │` measures 69 characters, identical
  to its three sibling box lines (69/69/69, bottom border 49); interior
  padding compensated (4 spaces → 1), and the right-edge context
  `│      └────────────┘` is byte-identical in the diff's −/+ lines.
- Lane: `docs/acceptance-run-system.tests.md` untouched (clean in git
  status), as T-098 required. The also-modified `docs/acceptance-run-system.md`
  is T-097's own uncommitted amendment (same wave), not T-098's write.

### AT-A-904 · E-table matches the amended interface lists (no drift) — PASS

- Oracle: edge rows E1/E2/E3 vs [SYS §Interfaces 1] verb lists.
- Evidence: E1 (M2→M1) `task_readiness`, `task_plan_sprint`, `task_claim`,
  division/link creation, `task_close_sprint` — exact match (its
  `task_set_blocked` escalation is additionally cited from [DSG §4],
  basis-cited, no contradiction); E2 (M3→M1) `task_get`,
  `task_add_artifact`, `task_update` (→ `in-review`), `task_add_comment`,
  `task_set_blocked` — exact match; E3 (M4→M1) `task_get` /
  `task_traceability` reads + the `done` transition — exact match. No E-row
  appears in any diff hunk.

## Observed, non-blocking (for the reviewer)

- T-098 AC3 (artifact before `in-review`) holds on the ticket record:
  artifact 09:24:47.355Z → status flip 09:25:06.917Z.
- `docs/acceptance-run-system.tests.md` still carries pre-amendment wording
  (history "with actors", transition actors in IT-A-101/102). This drift is
  real but explicitly out of T-098's and T-099's scope ("do not touch the
  tests doc"; IT-A-102's basis stays A-002's re-verification lineage) — owned
  by that doc's own flow, not by this pair.

## Verdict

**APPROVE-WITH-NOTES** — all four criteria PASS with observable evidence;
the notes above are observations for the engine review pass, none blocking.
This verdict decides T-098's done/not-done in the reviewer's hands; T-099
sets no status.

Evidence snapshot taken 2026-11-15 against working tree @ HEAD 6f7e7b3.

## Part II — library criteria for the architecture doc's module & edge structure (ticket A-004)

### Scope, amendment lineage, and what this part decides

- **Subject:** the library-level (test-architecture) verification of
  `docs/acceptance-run-architecture.md` — A-003's stage product, under
  verification by its paired test-architecture ticket **A-004** (epic
  U-111, basis A-001, same basis as A-003). Where Part I verifies the
  T-098 *amendment diff*, Part II verifies the doc's **library structure**:
  the module set M1–M5 and the default-deny edge table E1–E7 — against the
  store the run actually executes on.
- **Amendment lineage (honesty note, recorded per the ticket's
  requirement):** A-004's acceptance criteria were minted 2026-11-14,
  **pre-amendment**, and say every criterion names "history events **with
  actors**". The settled actor-attribution basis ([SYS §Interfaces 1] as
  amended by **T-097**, carried into the architecture doc by **T-098**)
  supersedes that wording: store attribution is **partial and
  caller-asserted** — claim and artifact events carry the acting identity,
  **status transitions are bare** (`updated:status`, no stamped actor), and
  engine attribution rests on the review pass's **observable marks**
  (engine-annotated done event **or** engine-verdict comment within the
  review batch around the flip; pre-2026-11-15 dones grandfathered).
  Part II's criteria therefore key on the **observable marks** —
  actor-stamped claim/artifact events, bare transitions read together with
  adjacent comments, verdict comments, `in-review` → `done` ordering, and
  the link graph — never on a stamped transition actor. All criteria
  verify against the **amended** wording.
- **What this part decides:** PASS/FAIL per criterion with evidence; the
  overall verdict decides **the architecture doc's library-level
  done/not-done contribution** and is **advisory to the engine review
  pass**, which alone sets any `done` (D-3). A-004 sets no ticket status
  and never sets done.
- **A-004 acceptance-criteria mapping:** AC-1 (library criteria per
  component + dependency rule, each traceable to a D-point) → §Component
  criteria + §Dependency-rule criteria + §D-point traceability; AC-2
  (every criterion names the observable store evidence that decides it) →
  the *Store evidence* bullet of every criterion, evidence keyed per the
  lineage note above; AC-3 (artifact recorded, ticket in-review, done
  never self-assessed) → the store writes attempted for this ticket —
  see §Sample capture state.

### How Part II decides (method)

Live-observation library tests over the running store
`.harness/tasks/hephaestus/` — the architecture's "modules" are modules of
the run (architecture doc §Scope), so its library criteria are decided on
the store, not on code:

- **Decidable-by-construction criteria:** run the named non-mutating
  `task_*` reads (`task_get`, `task_readiness`, `task_traceability`,
  `task_links`, `task_doctor`) and compare field states, history shapes and
  graph shape with the criterion's PASS/FAIL shapes.
- **Live-verified sample** (A-004's evidence duty, made concrete): five
  samples (LV-1..LV-5) were executed against the live store on 2026-11-15
  during this ticket's fulfillment, outputs recorded verbatim in
  §Live verification sample — proving the criteria decidable **today**,
  not in principle.
- Reads are non-mutating per the verb contract (sibling IT-A-105); the
  tasks MCP namespace dropped immediately after the sample reads (known
  failure mode, disclosed below), so a fresh `task_doctor` output was not
  captured in this session — its last recorded observation (sibling doc,
  post-remediation `{"inconsistencies":[],"ok":true}`) is cited instead and
  flagged for the reviewer's re-run at verdict time.

### Component criteria (M1–M5) — one per module

Each criterion states the module's contract (responsibilities + allowed
edges from the E-table) and the observable store evidence that decides it.

**AT-A-701 · M1 `store` — the passive substrate with the evidence
monopoly.** (D-1, D-2, D-3)

- Contract (architecture doc §Modules M1 row, §Dependency rules): single
  coordination blackboard — tickets with immutable `stage:`, `epic`/`basis`
  links, artifact indexes, claims, sprint records, readiness, append-only
  history with partial attribution; mutated **only** through the `task_*`
  verbs; **zero outgoing edges** (no callbacks/notifications — discovery is
  by read at the tick boundary).
- Store evidence: `task_doctor` reports zero unparsable files (⇒ every
  state change landed through the verbs); every history event on the chain
  tickets is a verb-produced shape (`created`, `planned into …`, claim,
  `updated:…`, `artifact…`, `artifact removed:…`, `blocked:…`, `done …`) —
  no hand-edit shapes; `stage:` on each chain ticket equals its creation
  stage with no stage-change event anywhere in history; claim/artifact
  events carry acting identities, status transitions are bare (amended
  attribution); repeated reads change nothing.
- FAIL: a history action outside the verb shapes; a `stage:` rewrite; a
  read that appends history or bumps `updated_at`; any notification or
  callback mechanism (M1 growing an outgoing edge).
- Observed: sibling doc's post-remediation doctor snapshot `ok:true`;
  chain histories verb-shaped throughout (§Live verification sample);
  fresh doctor re-run flagged for verdict time (connection drop, disclosed
  above).

**AT-A-702 · M2 `ticker` — fixed control cadence, no discipline work,
never accepts.** (D-1 claim+spawn side; D-2 division side)

- Contract (§Modules M2 row + E1/E4/E5): per stage the fixed tick
  SCAN → BLOCKED → DONE → READY → CLOSE as a micro-sprint over
  `ticker-<stage>`; writes only via **E1** (`task_readiness`,
  `task_plan_sprint`, `task_claim`, division/link creation,
  `task_close_sprint`, escalation `task_set_blocked`); spawns workers via
  **E4**; loads the common skill via **E5** — never produces a stage
  product itself, never accepts.
- Store evidence: per-stage micro-sprint records exist (`planned into
  ticker-requirements`, `planned into S-16`, `planned into ticker-v`
  events); claim events sit adjacent to ticker kickoff comments naming
  spawn + brief (A-001: kickoff comment by `system-ticker` at 17:57:31Z;
  identity stamping on the claim event itself is inconsistent
  pre-amendment — A-001 `claimed by system-ticker` vs T-031 bare
  `updated:status,assignee` — which is exactly why these criteria key on
  marks + adjacency, not on stamped transition actors); division children
  appear as `created` events with `basis`/`epic` set in the batch
  following the basis done; **no stage-product artifact event authored
  inside a ticket's fulfillment window by anything but that window's one
  worker identity**; **no `done` outside a review batch carrying the
  engine's mark**.
- FAIL: a kickoff/ticker identity recording a stage product (discipline
  work); a `done` by a kickoff identity without a review-batch verdict
  mark (self-acceptance); a tick that skips readiness (undisciplined
  dispatch).
- Observed: all chain claims/divisions match (LV-1, LV-2); the one
  review-identity FAIL-block (`blocked:` on A-002 by `ticker-v:review`) is
  the reviewer routing of AGENTS.md, not discipline work — Part II
  §Observed, N-2.

**AT-A-703 · M3 `worker` — one goal-driven fulfiller per ticket, stops at
`in-review`.** (D-1)

- Contract (§Modules M3 row + E2/E6, denied edges): exactly one worker per
  claimed ticket; objective *finish this ticket* — produce the stage
  product, record it (`task_add_artifact`), set `in-review`
  (`task_update`); narrow brief — inputs reachable from ticket records
  alone (AD-005); **never** `done`, never →M2, never ↔M3, never the gate.
- Store evidence: between claim and `in-review` exactly one claim
  transition and every stage-product artifact event carries **one** worker
  identity; the `in-review` flip is bare with the worker's **fulfillment
  comment adjacent** (identity evidenced by the comment — amended D-1);
  the window ends at `in-review` — no `done` in it; the fulfillment
  comment names lane + inputs reconstructible from ticket records alone.
- FAIL: two distinct worker actors' stage-product artifacts in one
  fulfillment window; a worker-set `done`; artifact recorded after the
  flip; inputs not reachable from ticket records alone.
- Observed: A-001 window 17:57:08→17:59:43Z, one worker (`system-worker`);
  A-003 window 08:28:24→08:36:15Z, one worker (`architecture-worker`) —
  the duplicate artifact events inside it are the disclosed remediation,
  all stamped `architecture-worker` (N-1); T-098 window single
  `ticker-v:T-098` (LV-1). Note: worker identity shapes vary
  (`<stage>-worker`, `<sprint>:<ticket>`) — caller-asserted peer
  convention; the discriminator is the single identity per window, not the
  prefix.

**AT-A-704 · M4 `verdict` — the only `done` authority, verdict on store
evidence.** (D-3)

- Contract (§Modules M4 row + E3/E7, AD-003): reviews each `in-review`
  ticket on store evidence (**E3** reads), runs the central quality gate
  (**E7**), alone performs the `done` transition; the verdict lands on the
  ticket as an observable mark — engine-annotated done event **or**
  engine-verdict comment within the review batch around the flip (amended
  mark rule; pre-2026-11-15 dones grandfathered). Neither worker nor
  ticker grades itself.
- Store evidence: every `done` follows its ticket's `in-review` flip and
  carries the engine's mark; a `done` **on/after 2026-11-15 with neither
  mark** fails D-3 outright; ordering `in-review` → `done` everywhere; no
  done inside any worker fulfillment window.
- FAIL: a post-2026-11-15 done with neither mark; a done with no preceding
  `in-review`; a done inside a fulfillment window.
- Observed: A-001's 2026-11-14 done carries the annotated event
  (grandfathered shape); A-003's 2026-11-15 done is a bare flip **with**
  the engine-verdict comment by `ticker-v:review` 5.6 min later (LV-3) —
  both mark shapes live on the chain.

**AT-A-705 · M5 `process` — static knowledge, loaded, never an actor.**
(D-1, D-2 — the QA-3 parameterization side)

- Contract (§Modules M5 row + E5/E6): the common `stage-pipeline` skill +
  the per-stage discipline skills; loaded by M2 (E5: common only) and M3
  (E6: common + stage); **zero outgoing edges** — no store writes of its
  own; per-stage parameterization (division map, discipline skill) is
  data, not divergent code paths.
- Store evidence (negative + parameterization): no history event exists
  that is not attributable to M1 verbs driven by M2/M3/M4 identities — M5
  leaves no author; every fulfillment record names its **stage discipline
  skill + the common skill**; the identical fulfillment shape recurs
  across stages with only per-stage data differing (requirements → system
  → architecture → test legs; division results differ per division map,
  procedure shape does not — QA-3).
- FAIL: an actor's fulfillment/tick record naming no skill
  (parameterization not loaded); stage procedures diverging in shape.
- Observed: A-001 "per the software-system skill"; A-003 "per the
  software-architecture skill"; T-097 "software-system"; tick/kickoff
  comments show the same scan→…→close procedure per stage.

### Dependency-rule criteria — one per allowed edge (E1–E7)

Each criterion names the edge, its verb-level interface, and a
store-observable demonstration.

**AT-A-801 · E1 (M2 → M1) — division, claiming, planning, escalation.**
(D-2; D-1 claim side)

- Interface (verb-level): `task_readiness`, `task_plan_sprint`,
  `task_claim`, division/link creation (`task_create` with `basis` +
  `epic` + the upstream's carried artifacts), `task_close_sprint`;
  `task_set_blocked` escalation of a blocked ticket onto its basis.
- Store demonstration: the ticker's division writes appear as **ticket
  creation events with `basis`/`epic` set** — verify live on the U-111
  chain: T-031 → A-001 (basis T-031, epic U-111); A-001 → A-002/A-003/A-004
  (basis A-001); A-003 → T-095/T-096 (basis A-003, created
  08:58:10.147Z, 83 ms after A-003's done flip — the DONE step of the same
  review batch); `task_links` shows the graph shape; readiness gates
  children on the basis done; a blocked escalation lands as a `blocked:`
  history event naming the basis (A-002).
- FAIL: a child with missing `basis`/`epic`; a READY child whose basis is
  not done; an escalation that does not name its basis.
- LIVE-VERIFIED: LV-2, LV-4.

**AT-A-802 · E2 (M3 → M1) — the worker write path.** (D-1)

- Interface (verb-level): `task_get`, `task_add_artifact`,
  `task_update` (→ `in-review`), `task_add_comment`, `task_set_blocked`.
- Store demonstration: on each fulfilled chain ticket the history reads
  claim → **actor-stamped artifact event** → bare `in-review` flip +
  adjacent fulfillment comment; a blocked worker lands a `blocked:` event
  with its reason.
- FAIL: artifact after the flip; two worker actors in one window; any
  worker `done` (denied edge).
- LIVE-VERIFIED: LV-1 (A-003, T-098 histories); A-001 recorded in the
  sibling doc and re-read this session.

**AT-A-803 · E3 (M4 → M1) — verdict reads, the `done` transition, and the
verdict-comment mark.** (D-3; D-2 pairing side)

- Interface (verb-level): `task_get` / `task_traceability` reads + the
  `done` transition; plus the mark and routing mechanisms the amended rule
  and AGENTS.md name — the **engine-verdict comment** in `comments[]`
  (this is how mark shape (2) lands) and the FAIL-block
  `task_set_blocked`. The E3 row of the architecture doc lists only the
  reads + the transition; see §Observed N-2 for the completeness note this
  live evidence forces.
- Store demonstration: every chain `done` follows its `in-review` and
  carries a mark (A-001: annotated done event by `review`, grandfathered
  shape; A-003: bare flip + verdict comment by `ticker-v:review` within
  the review batch); `task_traceability` pairs definitions with
  verifiers (U-111 verified_by A-002/A-004/T-096/T-099 via epic).
- FAIL: a post-amendment done with neither mark; a done with no preceding
  `in-review`; traceability rows contradicting the verifier tickets'
  statuses at read time.
- LIVE-VERIFIED: LV-3, LV-5.

**AT-A-804 · E4 (M2 → M3) — one spawn per claimed ticket, cap + lane
serialization.** (D-1)

- Mechanism (verb-level): native background subagent per claimed ticket,
  global cap (default 4), serialization on overlapping file lanes; the
  spawn prompt IS the brief; the worker reports on idle — no reverse
  channel.
- Store demonstration: the kickoff comment names the spawned session and
  the brief inputs (A-001: "spawned goal-driven executor subagent
  ses_e24cb0ddcffe4hECXFcTxyjI81 with the fulfillment brief … Spawn 1 of
  cap 4"); one fulfillment window per claim (AT-A-703's shapes); claimed
  tickets stay ≤ cap in samples — overlap analysis: A-002+A-003 both
  in-progress 08:32:33–08:36:15Z = 2 ≤ 4; the 09:11–09:30Z
  rework/verification batch (T-097, T-098, T-099, A-004 claims) never had
  two in-progress at once (each flip preceded the next claim).
- FAIL: two workers in one window; an over-cap claim sample (sampled
  criterion — sibling IT-A-203's caveat applies, corroborate with tick
  digests).
- Observed: kickoff comments + timestamp overlap analysis, §Live
  verification sample capture state.

**AT-A-805 · E5 (M2 → M5) — the ticker loads the common skill only.**
(D-2 procedure side; QA-3)

- Mechanism: the tick is thin glue over the common `stage-pipeline` skill
  — no discipline work in the tick.
- Store demonstration: tick/kickoff comments across stages carry the same
  procedure (scan readiness → blocked → divide → kickoff → close) and
  name no discipline product; per-stage differences in store outcomes are
  exactly the division-map parameters (requirements → system child;
  architecture → design child + test-design pair; system → architecture +
  test-system/test-architecture pairs).
- FAIL: a tick comment producing a stage product; stage tick procedures
  diverging in shape.
- Observed: ticker comments on A-001 (three tick passes recorded) and the
  ticker-v batch comments.

**AT-A-806 · E6 (M3 → M5) — the worker loads common + its stage's
discipline skill.** (D-1)

- Mechanism: the brief carries "its stage skill + the common
  `stage-pipeline` skill".
- Store demonstration: every fulfillment comment names both (A-001:
  software-system; A-003: software-architecture; T-097: software-system;
  A-002: per its record, test-system lane; A-004: this record per
  `test-software-architecture`).
- FAIL: a fulfillment naming no stage skill (parameterization not loaded),
  or a product outside the worker's lane.
- Observed: fulfillment comments across the chain.

**AT-A-807 · E7 (M4 → repo tree) — the central gate is M4's input only.**
(D-3 gate side)

- Mechanism: the quality gate (build/tests/lint) runs on the shared tree
  after each landing; gate configuration is project-owned, never re-decided
  here.
- Store demonstration: every mark-bearing verdict cites the product/gate
  outcome (A-001 done note "product PASS, lint clean"; A-003 verdict
  comment "hygiene clean"); **negative (denied edge)**: no worker/ticker
  record claims gate passage as completion — workers stop at `in-review`
  with artifacts only.
- FAIL: a post-amendment done whose verdict names no product/gate outcome;
  a worker/ticker gate claim.
- Observed: verdict texts on A-001, A-003.

### D-point traceability

Every criterion traces to at least one D-point of
`docs/acceptance-stage-pipeline.md`:

| Criterion | D-1 | D-2 | D-3 |
|---|---|---|---|
| AT-A-701 M1 store | ✓ evidence monopoly | ✓ links/readiness | ✓ history + guard |
| AT-A-702 M2 ticker | ✓ claim+spawn | ✓ division | — (never accepts) |
| AT-A-703 M3 worker | ✓ fulfillment | — | — (stops at `in-review`) |
| AT-A-704 M4 verdict | — | — | ✓ sole done + mark |
| AT-A-705 M5 process | ✓ skill loading | ✓ parameterization | — |
| AT-A-801 E1 | ✓ claim | ✓ division+links+readiness+escalation | — |
| AT-A-802 E2 | ✓ write path | — | — |
| AT-A-803 E3 | — | ✓ traceability rows | ✓ done + mark |
| AT-A-804 E4 | ✓ spawn/brief | — | — |
| AT-A-805 E5 | ✓ procedure shape | ✓ | — |
| AT-A-806 E6 | ✓ skill loading | — | — |
| AT-A-807 E7 | — | — | ✓ gate input |

D-1 primarily via AT-A-702/703/801/802/804/805/806; D-2 via
AT-A-701/702/705/801/803/805; D-3 via AT-A-701, AT-A-703 (negative:
worker never reaches it), AT-A-704, AT-A-803, AT-A-807.

### Live verification sample (2026-11-15) — recorded outputs

Executed during A-004's fulfillment against the live store; outputs
verbatim, trimmed to decisive fields. Five samples cover AT-A-801..803 and
the module criteria they instantiate — proving the criteria decidable
**today**.

**LV-1 · E2 worker write path + M3 single fulfiller (AT-A-802, AT-A-703) —
`task_get` A-003:**

> history: `created` 2026-11-14T18:11:10Z → `planned into ticker-v`
> 08:06:37Z → `updated:status,assignee` 08:28:24.806Z (claim, bare) →
> `artifact:file:docs/acceptance-run-architecture.md` **by
> `architecture-worker`** 08:36:14.954Z → `updated:status` 08:36:15.193Z
> (bare, → in-review) → disclosed duplicate artifact events 08:36:16–
> 08:37:41Z (same actor) → `artifact removed:file:…` ×3 by
> `test-system-worker:A-002` 08:41:27Z (remediation) → `updated:status`
> 08:58:10.064Z (bare, → done).

PASS — actor-stamped artifact, bare flips, one worker actor in the
fulfillment window, window ends at in-review. Same shapes on T-098:
artifact by `ticker-v:T-098` 09:24:47.355Z → bare flip 09:25:06.917Z.

**LV-2 · E1 division + link graph (AT-A-801) — `task_get` T-095 +
`task_links` A-003:**

> T-095: `basis: A-003`, `epic: U-111`, stage `design`, created
> 2026-11-15T08:58:10.147Z — 83 ms after A-003's done flip 08:58:10.064Z.
> task_links(A-003): up [A-001 (done), T-031 (done)], down [T-095, T-096],
> back [A-002, A-004, T-097], epic U-111.

PASS — division writes land as creation events with `basis`/`epic` set;
epic inherited all the way down; the earlier chain equally set (A-001
basis T-031; A-002/A-003/A-004 basis A-001).

**LV-3 · E3 done authority + amended mark rule (AT-A-803, AT-A-704) —
`task_get` A-003 (history tail + comments):**

> done flip 08:58:10.064Z is a **bare** `updated:status` — no actor, no
> annotation. The engine mark is shape (2): comment by **`ticker-v:review`**
> 09:03:47.336Z (5.6 min after the flip, within the review batch):
> "Engine verdict PASS (architecture, 2026-11-15): reviewer verified
> docs/acceptance-run-architecture.md (modules M1-M5, default-deny edge
> table E1-E7 …). Division executed: T-095/T-096."

PASS — both mark shapes of the amended rule observed live on one chain:
A-003 (verdict comment) and A-001 (annotated done event
`done (engine review pass: product PASS, lint clean)` by `review`,
2026-11-14, grandfathered). No worker/ticker done event anywhere on the
chain; every done follows its `in-review` flip. The amendment was
necessary, not cosmetic: the bare flip alone is undecidable — exactly
A-002's FAIL escalation (recorded on A-001 and A-002, 09:03:46Z), which
T-097 resolved.

**LV-4 · M1 readiness gates children on the basis (AT-A-801, AT-A-701) —
`task_readiness`:**

> T-095 **READY** "upstream satisfied by basis A-003 (done)"; T-096
> **READY** "upstream satisfied by basis A-003 (done)"; A-003
> **NOT_APPLICABLE** "stage 'architecture' finished (done) and inputs
> unchanged"; A-001 / T-031 / U-111 **NOT_APPLICABLE** (done, inputs
> unchanged); A-002 **BLOCKED** (escalation reason in the row); A-004
> **RUNNING** (this ticket, in-progress at read time).

PASS — readiness is a pure function of the link graph + statuses; the
children flipped READY from the basis done with no manual write (negative
control in the sibling doc: A-002 created 17:51:48Z → basis A-001 done
18:02:20Z → claimed 08:32:33Z at the next tick — the WAIT_UPSTREAM →
READY flip happened between those events with no intervening store write).

**LV-5 · E3 traceability reads pair definitions with verifiers
(AT-A-803) — `task_traceability`:**

> U-111 row: `verified_by: [A-002, A-004, T-096, T-099]`, `via: [epic:A-002,
> epic:A-004, epic:T-096, epic:T-099]`, links list the whole chain
> (T-031, A-001..A-004, T-095..T-099). A-004 row: `verifies U-111 via
> epic:U-111, self_verified: false`. A-001 row (definition):
> `verified_by: []` at read time — its verifiers are in-flight/blocked,
> consistent with the store at read time.

PASS — matrix rows exist for the chain and are self-consistent
(`via`/`self_verified` coherent, statuses current).

**Sample capture state:** reads ran 2026-11-15 during this ticket's
fulfillment (chain state: A-003 done; T-097/T-098/T-099 in-review;
T-095/T-096 READY). Immediately after the sample the tasks MCP namespace
dropped (the run's known failure mode) — the remaining store writes for
this ticket (artifact + verdict comment + in-review flip) were attempted
after the file work, per the ticket's lane instruction; if this file
carries no linked ticket confirmation, the ticket-side record is the
final message of A-004's worker session. Criteria outside the sample are
decided from the same session's reads (recorded per criterion as
*Observed*) plus the sibling doc's snapshot; the reviewer re-runs any
read at verdict time — the snapshot is corroboration, not the verdict.

### Observed, non-blocking (Part II, for the reviewer)

- **N-1 Duplicates on A-003:** three duplicate artifact+comment pairs
  (08:36:16–08:37:41Z) and their removal (08:41:27Z, after the in-review
  flip — outside the fulfillment window). Disclosed by the concurrent
  A-002 worker on A-003 itself; all events verb-shaped and stamped
  `architecture-worker` — AT-A-703's single-worker conclusion unaffected.
  Root cause (disclosed there): both workers chose the same temp-dir
  filename for a private tasks-server stdio bridge.
- **N-2 E-table completeness (wording drift, real):** the live store shows
  M4 (review-pass identities) writing beyond E3's literal verb list — the
  **engine-verdict comments** (the amended mark mechanism, named in the
  architecture doc's D-3 flow but not in the E3 row) and the FAIL-block
  `task_set_blocked` (A-002, by `ticker-v:review`; routing per AGENTS.md,
  listed under E1's M2 row). No lateral edge, no done-authority breach —
  the settled basis itself requires the comment mark — but the E3 row
  under-reports M4's write surface. Non-blocking; a one-row E3 amendment
  is the owner's call (this ticket's lane forbids editing the
  architecture doc).
- **N-3 Verdict application by ticker:** T-099's verdict comment was
  "applied by the ticker from the worker's report after a store outage" —
  permitted by the amended basis (identities caller-asserted; the mark,
  not the flip actor, decides). Worth the reviewer's notice for
  outage-path discipline: the ticket-level evidence (AT-A-901..904 plus
  this part) lived in the file while the store was unreachable.

### Verdict (Part II — the architecture doc's library-level contribution)

| Criterion | Verdict | Decisive evidence |
|---|---|---|
| AT-A-701 | PASS | verb-shaped histories throughout; doctor snapshot `ok:true`; `stage:` immutable on every chain ticket |
| AT-A-702 | PASS | micro-sprint records + division batches live; no ticker-authored stage products; no unmarked non-review dones |
| AT-A-703 | PASS | LV-1: one worker actor per window; bare flips + adjacent fulfillment comments |
| AT-A-704 | PASS | LV-3: both mark shapes live; `in-review` → `done` ordering everywhere |
| AT-A-705 | PASS | both skills named in every fulfillment record; identical procedure shapes across stages |
| AT-A-801 | PASS | LV-2/LV-4: `basis`/`epic` on every child; readiness pure on the graph |
| AT-A-802 | PASS | LV-1: claim → actor-stamped artifact → bare `in-review` |
| AT-A-803 | PASS | LV-3/LV-5: marks + ordering + self-consistent traceability rows |
| AT-A-804 | PASS | kickoff comments name session/brief/cap; ≤ cap in every overlap sample |
| AT-A-805 | PASS | identical tick-procedure comments across stages |
| AT-A-806 | PASS | common + discipline skill named in every fulfillment record |
| AT-A-807 | PASS | verdicts cite product/gate outcomes; no worker/ticker gate claims |

**APPROVE-WITH-NOTES** — all twelve library criteria PASS with observable
evidence (five live-verified on today's store); the architecture doc's
module/edge structure is real, default-deny, and decidable from the store
alone today. Notes N-1..N-3 are observations for the engine review pass,
none blocking. This verdict decides the architecture doc's library-level
done/not-done **contribution** in the reviewer's hands; A-004 sets no
status and never sets done.

Evidence snapshot taken 2026-11-15 against the live store (chain: A-003
done; T-097, T-098, T-099 in-review; T-095, T-096 READY; epic U-111).
