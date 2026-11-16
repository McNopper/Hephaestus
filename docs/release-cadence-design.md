# Release cadence — component design (T-121)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket T-121 (epic U-113, basis
  T-109, stage `design`): the inside of the release-cadence components —
  contracts (`name(inputs) -> output | errors`), data structures, key
  workflows and error behavior for the architecture's components C1..C6.
- **Read by:** the implementation stage (its basis — what each component's
  run surface must honor), the `test-design` stage (component tests against
  these contracts), the reviewer (traceability check against the
  architecture and the system flow), and the maintainer; **written by:**
  T-121 (design stage, discipline `software-design`).
- **Related:** `docs/release-cadence-architecture.md` (T-109 — the governing
  architecture: components C1..C6, edges E1..E9, the denied-edge list,
  AD-001..AD-006, QA-1..QA-4; cited [ARCH]); `docs/release-cadence-flow.md`
  (T-093 — the system flow whose oracle, actors and outcome paths every
  contract here preserves; cited [SYS]);
  `docs/live-in-eclipse-recurrence-flow.md` (the sibling system flow —
  composed with, referenced, never modified; cited [REC]);
  `docs/design-stage-pipeline.md` (the tick machinery — pass-through basis;
  cited [DSG]); `eclipse/releng/release.ps1` (C4's reality anchor — read,
  never run here); `.opencode/skills/stage-pipeline/SKILL.md` (the tick C2
  rides in); `ROADMAP.md` (the T-041 standing rule); tickets T-041 (the
  rule), T-039 (non-blocking acceptance), U-112 / U-111 (the worked example
  that cut v0.2.0).

## 1. Design scope

The architecture (T-109) fixed the **boundaries**: six components anchored
to real surfaces of this repo, default-deny edges E1..E9, and the denied-edge
list whose sharpest entries are C2→C4 (the evaluator never cuts) and C4→C1
(the script has no store access). This document designs the **inside** of
each component — what it takes in, what it returns, how it fails — per the
`software-design` skill. The cadence is a **process/agent architecture**
([ARCH] §Scope): its components are components of the run, so these
contracts are behavioral specifications of run surfaces — store verbs, the
close sweep, the chat session, the release script, the documents — not code
signatures (DS-006). Where the inside is already designed elsewhere, this
document records an **explicit pass-through** and adds nothing:

- **C1 (store):** the contracts are the engine's own (write discipline,
  append-only history, the state machine) — the architecture already treats
  the store as substrate (AD-001); designing store behavior here would
  contradict the zero-new-surfaces promise ([SYS §1]).
- **C4 (cut):** the script's guards and the D-002 sequence **are** the
  design — verbatim pass-through of [SYS §4.2] ("the flow adds no
  validation of its own", AD-004). Only the invocation/result contract is
  added here.
- **C5 (knowledge):** the documents are the carriage of rule change
  ([DSG §6]) — no machinery to design; the wording is consumed verbatim,
  never restated.
- **The tick algorithm:** [DSG §4] is the machine's design; C2's evaluation
  attaches to it as the named extension point, and nothing here redesigns
  the tick.

Contract notation throughout: `name(inputs) -> output | errors`. Design
decisions made at this stage are recorded as **DS-001..DS-006** (listed in
§3 and at their point of use).

## 2. Component contracts

### The shared evaluation contract (C5 spec — applied by C2 and C3)

One query exists; both evaluators apply it verbatim — no fallback variant
(DS-001; [SYS §6]: one wave detection, one floor, shared; AD-003: the
oracle is knowledge, executed per evaluation — no component implements it).

- `runEvidenceQuery(wave: WaveRef) -> EvidenceResult` — the 4-step query of
  [SYS §3.4], verbatim: (1) `task_list(sprint=<record id>)` — the wave's
  members; completed when every member is `done` (the [REC §4 R2] shared
  detection); (2) `task_get` the wave's acceptance instance and every
  member; (3) scan `comments` + `artifacts` for `by` in the owner set
  (`owner`, `owner-directive`, `owner-acceptance`); (4) content floor: the
  matched text must STATE an outcome (accept, approve, review verdict) —
  a question, a direction or a drive-by note is not acceptance.
- `determineOutcome(matches: EvidenceMatch[]) -> Outcome` — owner-set
  evidence passing the floor → `UNLOCKED(evidence ref)`; otherwise
  `PENDING_REVIEW`. errors: none thrown — the conservative asymmetry (R4)
  resolves every doubt to PENDING: a false negative parks a release, a
  false positive would cut an unauthorized version ([SYS §3.4]).
- `recordOutcome(wave, outcome) -> void` — the recording duty (R7),
  semantics per DS-002: the digest line is written on **every** evaluation
  (it is "the audit trail of when the evaluation ran and what it saw",
  [SYS §4.3]); the instance comment carries the outcome at **first
  determination** and at **every flip** (the first PENDING, each UNLOCKED).
  errors: an instance-comment write failure leaves the digest line standing;
  the next sweep re-derives (N5) — no retry loop inside the pass (the tick
  boundary is the only synchronization point, [DSG §4]).

Scan-surface invariants (what the query deliberately never reads —
[SYS §3.1]): no status-transition history events (actorless — the A-002
finding), no assignee fields (`owner-acceptance` as assignee is a routing
marker), no git authorship, no chat text. Silence is pending, never
implicit approval ([SYS §3.5]).

### C1 — store (substrate) — explicit pass-through

The architecture's only evidence and state substrate; its contracts are
engine-owned and out of this design's scope ([ARCH] C1; §1 above). The
cadence consumes exactly this surface:

- **Read:** `task_list(sprint=<record id>)` (wave members) and
  `task_get(<id>)` (status, comments, artifacts with self-asserted `by`).
- **Write — the entire cadence write set:** `task_add_comment` (outcomes,
  recordings, failure comments) + `task_add_artifact` (the executor's git
  artifact) + the digest-line convention carried by the record close
  (U-061; no dedicated store API — accepted, [SYS §8]).
- **Invariants relied on:** append-only history with caller-asserted
  authorship (the audit trail behind AD-006); the sprint record has no
  comment channel — which is WHY every outcome lands on the acceptance
  instance ([SYS §4.1 step 3]).
- **Deliberately absent:** any new store concept — no pending-release
  marker, no evaluated flag, no cadence ticket type ([SYS §1]; AD-001);
  and C1 has no outgoing contract at all — zero outgoing edges ([ARCH]
  direction consistency).

### C2 — evaluator (the ticker pass)

The ONLY routine evaluator: the `ticker-v` steward at tick step 6 (close),
after the digest and the sibling's R2 mint, before the record closes
([SYS §4.1]; order verified live, T-094 ITS-001).

- `evaluateCompletedWaves() -> void` — for each wave in the sweep's
  evaluation set (the wave(s) whose record closes in this pass, [SYS §4.1];
  plus any completed wave still parked on an open acceptance instance —
  how [SYS §4.4]'s later flip stays reachable without a stored marker),
  run the shared query (above), determine the outcome, record it (DS-002).
  errors: none — every condition resolves to a valid Outcome; a sweep lost
  mid-run is re-derived by the next (N5, QA-3); store API failure ends the
  sweep honestly, the next sweep re-derives.
- Write surface (exactly E1's — [ARCH] edge table): the query's reads +
  two write kinds (digest line + instance comment) under the ticker's
  machine identity. No claim, no status change, no `task_set_blocked`,
  no spawn.
- Knowledge surface (E7): loads SKILL.md (the tick it rides in), the STEP 6
  EXTENSION wording, and the oracle spec — applied, never restated (E7;
  [SYS §4.1]).

**Deliberately absent from C2's contract surface** (their absence IS the
enforcement — the denials are architecture rules, DS-003):

- **No `invokeCut` / `runRelease` / any execution verb — the human-gate
  denial (C2→C4)**, the central denial [ARCH] names: a mid-pass tick cannot
  promise the quiet tree the script demands, and the push policy demands
  the owner's explicit go ([SYS §2 row 1 never-does; §4.1 step 4; N3; R5]).
  UNLOCKED determines and records — it never executes; the cut is a
  separate motion with a human in the loop ([SYS §4.2]).
- No `block` / `fileNeedsHuman` / escalation verb — a PENDING REVIEW parks,
  never blocks (N2; R6; the open instance is the surface).
- No owner-identity write — C2 writes only under its machine identity; its
  own digest claims are never evidence (R2; [SYS §3.5]).
- No spawn, no status transition, no readiness edge — read-only until the
  record (N1).

### C3 — assistant (fallback evaluator · executor · re-recorder)

The main chat session (H7): fallback evaluator when no ticker is armed;
the cut's executor between passes; the re-recorder of owner speech; the
recorder of cut results and failures ([SYS §2 row 2]).

- `evaluateWaveFallback(wave: WaveRef) -> Outcome` — the same shared query
  (DS-001), announced in chat as it runs (H7; the announcement is the
  interface duty, the store writes are the record). errors: identical
  conservative asymmetry (R4). Recording per DS-002 (R7).
- `reRecordOwnerSpeech(words, context) -> owner-directive comment` — quotes
  the owner's words **verbatim** with the context (the established
  re-recording convention; live precedents: T-036, T-039, U-113). errors:
  paraphrase is forbidden — a re-recording that is not the owner's words is
  a fabricated owner identity; if the words are ambiguous as evidence, ask
  the owner, never author owner text.
- `executeCut(go: OwnerGo, push: bool) -> CutResult | CutFailure` — the E3
  invocation of C4. Preconditions, all three at contract level: the owner's
  explicit go (R5 — the release decision; **no default exists**); BETWEEN
  passes on a quiet tree (N3 — the script's dirty-tree guard is only
  satisfiable there); `push=true` only on the owner's explicit `-Push` go.
  The release version is **derived, never passed**: the current reactor
  `-SNAPSHOT` stripped (live anchors: `0.2.1-SNAPSHOT` → `v0.2.1`, then
  `0.2.2-SNAPSHOT`) — no version invention in chat ([SYS §4.2]). errors:
  every C4 guard/Die path lands as `recordCutFailure` (below); tickets,
  records and verdicts are untouched (N4); retry at the next evaluation or
  the next go.
- `recordCut(result: CutResult) -> void` — results recorded back over E2
  ([ARCH] Path A step 6): a `git` artifact (tag / release-commit refs), an
  acceptance comment on the wave's acceptance instance, a `docs/HISTORY.md`
  entry (R7). errors: a recording failure after a successful cut leaves the
  git anchors standing — re-record on the next motion.
- `recordCutFailure(failure: CutFailure) -> void` — a failure comment on
  the instance naming the failed step (N4's executor half — the script
  cannot record it itself, T-094 TN-3).
- Knowledge surface (E8): loads the cadence doc (fallback evaluation +
  executor rules), the standing rule (ROADMAP, T-041), and the sibling mint
  wording it composes with ([SYS §2 row 2; §6; H7]).

**Deliberately absent:** no `executeCut` path without a recorded go — the
contract has no default and no implied consent; a go-less cut is a process
violation visible in the history audit trail (AD-006). And C3's own
`chat-session` writes are machine class — never evidence by itself,
however owner-shaped the text ([SYS §3.2]).

### C4 — cut (the script) — pass-through + the invocation contract

`eclipse/releng/release.ps1` IS the design — verbatim pass-through of
[SYS §4.2] and AD-004: the flow adds no validation of its own; the script's
guards are the single validation layer. This design adds only the
invocation/result contract:

- `release.ps1 [-Push]` — invoked from inside the repository, on the
  release branch, clean tree; runs the D-002 sequence: set-version to the
  release version across the reactor → the quality gate
  (`clean verify -Pquality`) → the "Release vX.Y.Z" commit + annotated tag
  → set-version to the next `-SNAPSHOT` → optional `-Push` (branch + tag).
- errors — every one a `Die` that aborts before harm: repository check;
  build-wrapper check; X.Y.Z format (rejects ANY suffixed version — this is
  where an invented version dies, the second wall behind DS-003);
  tag-exists (live: `v0.1.10`, `v0.1.11`, `v0.2.0` exist); branch check;
  dirty-tree check; fetch-freshness check; push failure (leaves honest
  local-only state). The quality-gate failure reverts the version bump and
  aborts before any commit — nothing is ever half-released ([SYS §4.2]; N4).
- **No store contract exists on C4's surface** (DS-004 — the C4→C1 denial
  visible as absence): the script RETURNS `CutResult`/`CutFailure` to its
  caller (C3 over E3, or the owner personally over E4) and git anchors
  RESULTS — the tag, the release commits — never the trigger ([SYS §3.1];
  T-094 TN-3; the structural form of [REC G5] "release unaffected"). All
  recording is the caller's duty (C3 over E2; the owner over E9).

### C5 — knowledge (static) — explicit pass-through

The documents themselves ([ARCH] C5; [DSG §6]: skills and docs are the
carriage of process change — change once, every actor inherits). No runtime
contract exists; runtime state never lives here. Consumed verbatim, never
restated: the wave detection ([REC §4 R2] — it exists once); the 4-step
query + content floor + conservative asymmetry ([SYS §3.4]); the identity
conventions table ([SYS §3.2]); the STEP 6 EXTENSION wording ([SYS §4.1]);
the standing rule (ROADMAP, T-041). The oracle is knowledge, executed per
evaluation by C2/C3 — no component implements it (AD-003).

### C6 — verdict (governance) — explicit pass-through

The engine review pass: SKILL.md tick step 3 (the reviewer subagent) + the
per-project quality gate; the only `done` authority ([DSG §8] engine-only
done; [ARCH] C6). Contract-relevant surface: verdict events and verdict
comments are machine class, never counted ([SYS §3.5]) — the NECESSARY half
of every release condition, never the sufficient one. After a cut, C6
provides the instance's own normal engine-verdict close (E6; the [REC]
lifecycle untouched). **Deliberately absent:** any verdict→cut path — the
denied C6→C4 edge (N6; T-039; demonstrated live: a fully green wave still
produced PENDING, T-094 ITS-005).

## 3. Design patterns used

- **Blackboard over the store** — chosen upstream ([ARCH] §Chosen pattern);
  pass-through, not re-chosen here. Listed because every contract above
  reads or writes the shared substrate.
- **Facade** — the shared evaluation contract aggregates C1's read verbs
  (`task_list`, `task_get`) behind one outcome contract for two consumers
  (C2, C3): one query, one floor, one asymmetry (DS-001).
- Nothing else forced — the denials are structural *absences* of contracts,
  not pattern machinery; C4 is a plain script; C3's duties are plain
  functions.

## 4. Data structures

- `WaveRef {recordId}` — a completed wave. Invariants: record id not
  starting with `ticker-`, at least one member ticket, zero open (the
  [REC §4 R2] shared detection; the micro-record carve-out, [SYS §1]).
- `EvidenceMatch {ticketId, by, ts, text}` — one owner-authored store write.
  Invariants: `by` ∈ the owner set (`owner`, `owner-directive`,
  `owner-acceptance`); `text` states an outcome (the floor); for the
  member-ticket shape, `ts` at or after the wave's completion (the last
  engine verdict among the wave's tickets — [SYS §3.4]).
- `EvidenceResult {outcome, matches[]}` — `outcome ∈ {UNLOCKED(matches),
  PENDING_REVIEW}`. Invariants: UNLOCKED carries at least one match;
  ambiguity resolves to PENDING (R4); an empty result is pending, never
  implicit approval ([SYS §3.5]).
- `OwnerGo` — the owner's explicit go, given in chat or person; no default
  value exists in any contract (DS-003).
- `CutResult {tag, releaseCommit, snapshotCommit, pushed}` and
  `CutFailure {step, reason}` — the script's two outcome shapes;
  caller-consumed return values, never self-recorded (DS-004).
- No cadence-specific structure is ever STORED — the outcomes above are
  derived per evaluation from ordinary tickets, comments and artifacts;
  the store holds no pending-release marker and no evaluated flag
  (AD-001; N5).

## 5. Key workflows

Only the non-obvious flows; each cites the edges and denials it upholds.

### W-1 — Close-sweep evaluation (C2; E1, E7; N1, N2, N5; the [REC] seams)

1. The sweep reaches tick step 6 after the digest is written and after the
   sibling's R2 mint, before the record closes ([SYS §4.1] fixed order —
   the freshly minted instance is visible to the query; T-094 ITS-001).
2. Evaluation set: the wave(s) closing in this pass; parked waves (open
   acceptance instances, findable by scanning open `(wave ...)` titles)
   re-derived ([SYS §4.4]).
3. Run the shared query per wave — C5's spec over C1's data (E1 reads).
4. Outcome per wave: `UNLOCKED(evidence ref)` or `PENDING REVIEW`;
   ambiguity → PENDING (R4).
5. Record (E1 writes; R7; DS-002) — the digest line on every evaluation:

```text
release cadence: wave <record id> UNLOCKED (evidence <ticket id> + author + ts)
release cadence: wave <record id> pending review
```

   and the instance comment with the same outcome at first determination
   and at every flip.
6. The four do-NOTs, verbatim duties of [SYS §4.1 step 4]: do NOT run
   `release.ps1`, do NOT block anything, do NOT spawn, do NOT escalate a
   PENDING REVIEW as NEEDS-HUMAN — the open instance already surfaces it
   to the owner on the Board.

Upheld: the denied C2→C4 / blocking / owner-identity / spawn edges (all
absent from C2's surface, §2); N1 (two non-state writes only); N5
(idempotent, marker-free); one-sweep-two-extensions and one-instance-two-
readers ([REC] seams — the cadence mints no tickets of its own).

### W-2 — Fallback evaluation (C3; E2, E8; H7; R1)

1. No ticker is armed; a wave closes in the main chat session.
2. C3 announces the evaluation in chat (H7), runs the SAME shared query
   (DS-001), records the same two writes under its identities.
3. Identical conservative asymmetry; identical recording semantics.

Upheld: R1 (the ONLY two evaluators that exist — ticker pass, announced
fallback; every other actor's evaluation is denied); the PM/housekeeping
denial (no evaluation contract exists outside C2/C3).

### W-3 — Path A: review present, the cut ([SYS §4.2]; E9/E2, E1, E3, E5, E6; R5, N3, N4, N6)

1. Owner evidence enters C1: E9 direct (the worked example recorded
   `e2c2b74`/`29d9bc9` under `owner-acceptance`) or via E2's re-recording
   under `owner-directive` (C3, verbatim words).
2. A sweep's query finds the evidence passing the floor → outcome
   UNLOCKED, recorded twice (W-1 steps 4–5).
3. The owner gives the explicit go in chat or person — the release
   decision (R5; one of the owner's roles, T-036).
4. C3 invokes C4 (E3) between passes: quiet tree, release branch — C4's
   own guards enforce everything else ([SYS §4.2]: the flow adds no
   validation of its own).
5. C4 runs the D-002 sequence on the repo tree (E5): set-version → quality
   gate (a failure reverts the bump, aborts pre-commit) → "Release vX.Y.Z"
   commit + annotated tag → next `-SNAPSHOT` bump → optional `-Push` on
   the explicit go. The version is the current `-SNAPSHOT` stripped —
   never invented in chat (DS-003).
6. Results recorded back over E2 (C3) — or over E9 when the owner ran the
   cut personally (E4; the worked example, artifacts under
   `owner-acceptance`): the git artifact, the acceptance comment on the
   instance, the HISTORY.md entry (R7; DS-005 — the recording carries the
   executor's identity).
7. The instance closes through its own normal engine-verdict path (E6) —
   the [REC] lifecycle untouched.

Evidence chain, store/git-verifiable end to end ([ARCH] Path A): owner-set
evidence in C1 → UNLOCKED outcome comments → git tag + release commits →
git artifact + acceptance comment + HISTORY entry.

Upheld: R5 (go-only execution; denied C2→C4 and C6→C4 — no machine path to
the cut exists); N3 (the tree serializes — the dirty-tree guard cannot
pass while workers write); N4 (every Die lands as a recorded failure,
nothing else touched).

### W-4 — Path B: no review, commit + push, park ([SYS §4.3]; N2, N6)

1. The query finds no owner evidence (or the floor fails) → outcome
   PENDING REVIEW, recorded twice (W-1 steps 4–5).
2. The ordinary wave close proceeds — commit + push ALWAYS happens (the
   standing rule's first half, distinct from the release's `-Push`).
   Path B adds nothing: it is the absence of W-3's steps 3–5.
3. The release parks in two pre-existing, Board-visible states: the OPEN
   acceptance instance (`in-progress`, assignee `owner-acceptance`) and
   the digest line. Nothing new is created — parking is a read plus two
   non-state writes.
4. No escalation, no block, no nag — the review cadence is an owner dial
   (the autonomy spectrum; no nagging exists, accepted [SYS §8]).

Upheld: N2 (pending is not blocked); N6 (a perfectly green wave with
machine evidence everywhere still parks); AD-005 (pending rides existing
states).

### W-5 — The unlock later ([SYS §4.4]; N5, QA-3)

1. Evidence may arrive long after the wave closed — the owner reviews at
   their own cadence.
2. The next sweep re-derives the parked wave (W-1 step 2): the flip to
   UNLOCKED needs no stored marker to invalidate (AD-001); the flip is
   recorded (DS-002 — first determination and every flip are the recorded
   moments).
3. One review session typically unlocks ONE cumulative cut: per-wave
   granularity lives in the evidence, the cut is per occasion (the worked
   example — the arc's waves, one acceptance session, v0.2.0).
4. The go → W-3 steps 4–7.

Upheld: N5/QA-3 (convergence — idempotent, marker-free, re-derived every
sweep; a lost sweep costs nothing); QA-2 (ambiguity still resolves to
PENDING, both polarities proven live, T-094 ITS-003/ITS-005).

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| Ambiguous evidence at the content floor | outcome PENDING (R4) — a false negative parks; a false positive would cut |
| No owner evidence found | PENDING REVIEW recorded (digest + instance comment); never blocked, never NEEDS-HUMAN (N2, R6) |
| Instance-comment write fails mid-recording | the digest line stands; the next sweep re-derives and re-records (N5; no retry loop inside the pass) |
| A sweep lost mid-run (the tasks namespace can drop) | re-derived by the next sweep — the tick boundary is the only synchronization point ([DSG §4]; QA-3) |
| C4 guard `Die` (repository, wrapper, format, tag-exists, branch, dirty tree, freshness) | abort before harm; C3 records the failure on the instance (N4); retry at the next evaluation or go |
| Quality-gate failure during D-002 | version bump reverted, abort before any commit; failure recorded; nothing half-released (N4) |
| Push failure | honest local-only state (tag + commits local); recorded; `-Push` retries at the next go (N4) |
| A version invented outside the stripped `-SNAPSHOT` | unrepresentable in `executeCut` (DS-003); C4's format guard is the second wall — any suffixed version Dies |
| A cut attempted without the go | no contract path exists (DS-003); a violation would be visible in the history audit trail (AD-006) |
| Workers writing during a cut | structurally serialized — C4's dirty-tree guard cannot pass while the tree is dirty (N3) |
| A machine identity writing the owner set | process violation visible in history (AD-006); the oracle's machine rule keeps such writes out of the evidence |
| Green verdicts everywhere, machine-authored evidence only | PENDING REVIEW — N6, demonstrated live (T-094 ITS-005) |
| Paraphrased "owner" re-recording | forbidden — `reRecordOwnerSpeech` quotes verbatim or asks; fabricated owner text is a process violation (DS-005) |

## 7. Edge-compliance map (E1..E9 and the denied edges)

Every edge of the governing architecture and the contract or workflow that
realizes it (T-121 AC-1's "workflows consistent with edges E1..E9"):

| Edge | Realized by |
|---|---|
| E1 C2 → C1 | C2 `evaluateCompletedWaves` (§2 C2): the query reads (`task_list`, `task_get`) + the two write kinds (digest line, instance comment) — nothing else |
| E2 C3 → C1 | C3 fallback evaluation, `reRecordOwnerSpeech`, `recordCut`, `recordCutFailure` (§2 C3) |
| E3 C3 → C4 | C3 `executeCut` — preconditions go / between-passes / derived version; the exit result flows back over E2 |
| E4 owner → C4 | outside the machine contracts — the principal's own act (the worked example cut v0.2.0 from the acceptance session) |
| E5 C4 → repo tree | the script's own D-002 sequence — full pass-through (§2 C4) |
| E6 C6 → C1 | engine verdicts (done events, verdict comments); the instance's normal close after a cut (W-3 step 7) |
| E7 C2 → C5 | C2 loads SKILL.md + the STEP 6 EXTENSION wording + the query spec (§2 C2) |
| E8 C3 → C5 | C3 loads the cadence doc, the standing rule, the sibling mint wording (§2 C3) |
| E9 owner → C1 | owner-set comments and artifacts — the only writes that classify as HUMAN evidence (W-3 step 1) |

Every denied edge and the contract absence that enforces it (the denials
are architecture rules — visible here as missing contracts, not as
discipline):

| Denied edge | Enforced by (contract-visible) |
|---|---|
| C1 → anything | C1 has no contract at all (§2 C1 pass-through); zero outgoing edges ([ARCH] direction consistency) |
| C2 → C4 — THE human-gate denial | NO execution verb on C2's surface (§2 C2, Deliberately absent; DS-003) — the evaluator can never become the cutter |
| C2 → blocking / escalation surfaces | no block / NEEDS-HUMAN verb on C2 (§2 C2); N2 |
| C2 → owner identities | C2 writes machine-only (§2 C2); R2 |
| C4 → C1 | no store verb on C4's surface — results are returned to the caller (§2 C4; DS-004) |
| C6 → C4 | no verdict→cut path anywhere (§2 C6); N6 |
| C3 → C4 without the go | `executeCut`'s go precondition has no default (§2 C3; DS-003; R5) |
| PM / housekeeping → evaluation | no evaluation contract exists outside C2/C3 (§2, W-2; R1) |
| Any machine actor → owner-set writes | only E9 and C3's verbatim `owner-directive` re-recording carry owner identities (DS-005); authorship is self-asserted, so violations are visible in the history audit, not technically impossible (AD-006) |

## 8. Traceability matrix (T-121 AC-2)

Every architecture element ([ARCH], as named in AC-2) → its design
resolution here. Consistency rule: no contract contradicts [SYS] — each
resolution cites the system section it preserves.

| Architecture element | Design resolution |
|---|---|
| C1 store | §2 C1 — explicit pass-through + the consumed read/write surface ([SYS §1], §4.1 step 3) |
| C2 evaluator | §2 C2 contracts + the deliberately-absent block ([SYS §2 row 1; §4.1]) |
| C3 assistant | §2 C3 contracts ([SYS §2 row 2; §3.2; §4.2]) |
| C4 cut | §2 C4 — pass-through + the invocation contract ([SYS §4.2]; AD-004) |
| C5 knowledge | §2 C5 — explicit pass-through; the shared evaluation contract ([SYS §3; §6]) |
| C6 verdict | §2 C6 — explicit pass-through ([SYS §2 row 4; §3.5]) |
| Actor row: stage ticker pass | C2 (§2 C2); its never-does column → the deliberately-absent block |
| Actor row: kickoff assistant | C3 (§2 C3); never-does → the absences (no go-less cut, chat-session never evidence, no version invention) |
| Actor row: Product Owner / maintainer | the human principal — outside the machine contracts; its acts are E4/E9 and the go that gates E3 (W-3 steps 1, 3; the worked example) |
| Actor row: engine review pass | C6 (§2 C6); never-does (the sufficient half) → the denied C6→C4 + the machine rule |
| Actor row: PM / housekeeping passes | no component — the denied evaluation edge (§7; [SYS §2] the PM note) |
| Oracle clause: store-only (§3.1) | the shared query reads C1 exclusively (E1/E2); no contract reads anywhere else for review evidence |
| Oracle clause: not status-transition history | the scan-surface invariants (§2, shared contract) — the query never reads history (the A-002 finding) |
| Oracle clause: not assignee fields | same invariants — assignee is a routing marker; the query scans comments + artifacts only |
| Oracle clause: not git authorship | the denied C4→C1 (§7; DS-004); git is read only as results (W-3 step 6) |
| Oracle clause: not chat text | only a store write classifies; chat is the interface (H7) — §2 C3's absence note |
| Oracle clause: identity conventions (§3.2) | C5's table, pass-through (§2 C5); the scan matches `by` against the owner set |
| Oracle clause: the `chat-session` call-out | C3's own writes are machine; the verbatim `owner-directive` re-recording is the human evidence (§2 C3; DS-005) |
| Oracle clause: canonical instance evidence (§3.3) | the instance read (status `done` + owner-set artifact and/or comment) in the shared query steps 2–3 |
| Oracle clause: secondary shapes (§3.4) | the same scan: owner acceptance comments on member tickets (ts at/after completion) + NEEDS-HUMAN-resolution comments that accept |
| Oracle clause: the 4-step query (§3.4) | the shared evaluation contract, verbatim (§2 opening; DS-001) |
| Oracle clause: the content floor (§3.4) | step 4 of the shared contract — states-an-outcome, judged per evaluation; asymmetry R4 |
| Oracle clause: never-counts (§3.5) | the machine rule + the structural denials (§7); silence is pending (§4 `EvidenceResult`) |
| Path A — the cut | W-3 (all seven steps, evidence chain verbatim from [ARCH]) |
| Path B — commit + push, park | W-4 (the two passive pending markers; nothing new created) |
| The unlock later | W-5 (marker-free flip; the cumulative cut) |
| N1 read-only until the record | C2's write surface is exactly two non-state writes (§2 C2; W-1 step 5) |
| N2 pending is not blocked | the denied blocking edge (§2 C2, §7); the open instance is the surface (W-4 step 3) |
| N3 cut never runs mid-pass | the denied C2→C4 + `executeCut`'s between-passes precondition + the dirty-tree guard (§2 C2/C3/C4; W-3 step 4) |
| N4 failure isolation | C4's Die contract + `recordCutFailure` (§2 C3/C4; §6 rows 5–7) |
| N5 convergence | the marker-free idempotent query + DS-002 recording semantics (§2 shared contract; W-5) |
| N6 verdicts alone never cut | the denied C6→C4 + the machine rule (§2 C6; §7; §6 last row) |
| Composition seams with [REC] | W-1 steps 1–2 (one sweep, fixed order; one detection shared; one instance, two readers — the cadence mints no tickets) |
| QA-1..QA-4 | preserved: QA-1 by the absences (§7), QA-2 by R4 + W-5, QA-3 by N5/W-5, QA-4 by DS-003 + the oracle ([ARCH] §Key quality attributes) |
| AD-001..AD-006 | carried: AD-001 (§2 C1/§4), AD-002 (§7), AD-003 (§2 C5), AD-004 (§2 C4), AD-005 (W-4), AD-006 (§6, DS-005) |

## 9. Open questions

None new at the design level. The inherited items are carried unchanged
(pass-through of [ARCH] §Open questions — they change wording or data,
never the contract set): T-094 TN-1 (no SKILL.md anchor sentence for the
cadence extension yet — doc-supplies-the-wording; a one-sentence anchor at
the next SKILL.md revision closes it), the digest's missing dedicated API
surface (accepted, [SYS §8] — DS-002 keeps the instance comment as the
machine-readable half), and no nagging (accepted, [SYS §8]). Nothing here
resolves or re-opens them.

## 10. Acceptance criteria mapping (T-121)

- **AC-1** (design artifact with the docs/ convention About header;
  component contracts with inputs/outputs/error behavior; workflows
  consistent with E1..E9 and the denied-edge rules): this document — the
  About header above; §2 contracts in `name(inputs) -> output | errors`
  form with per-contract errors; §4 data structures; §5 workflows with
  their edge citations; §6 consolidated error behavior; §7 the explicit
  edge + denial map.
- **AC-2** (every architecture element resolves to a contract or an
  explicit pass-through; no contradiction with [SYS]; the C2→C4 human-gate
  denial contract-visible): §8's matrix — C1..C6, all five actor rows, all
  twelve oracle clauses, both outcome paths plus the unlock, N1..N6, the
  seams, QA and AD rows, each resolved or passed through explicitly; the
  per-resolution [SYS] citations are the consistency check; the C2→C4
  denial is contract-visible three times — the deliberately-absent block
  (§2 C2), the denial map (§7), and the matrix row (§8).
- **AC-3** (artifact recorded on the ticket before in-review; done never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`)
  written before the `in-review` status update; `done` is the engine's
  verdict, never this stage's claim.

## 11. Lane and non-goals

This ticket's lane is one new doc file (`docs/release-cadence-design.md`).
Not touched: `docs/release-cadence-architecture.md`,
`docs/release-cadence-flow.md`,
`docs/live-in-eclipse-recurrence-flow.md`,
`docs/design-stage-pipeline.md`, `.opencode/skills/stage-pipeline/SKILL.md`,
`eclipse/releng/release.ps1`, `ROADMAP.md`, any code, any skill. No
concrete provider or model ids anywhere (D-004). The contracts are
specified, not scripted (DS-006): the cadence's "implementation" is the
wording riding SKILL.md step 6, the chat session's behavior, and the
existing script — no new code lane exists to build. Downstream hand-off:
implementation derives from §2/§4; `test-design` tests against the §2
contracts and the §6 error table (re-running the shared query on real
store data, as T-094 did, keeps the judgments evidence-backed).
