# Architecture: the release cadence (components, dependency rules)

## About this document

- **Kind:** `doc` / architecture-stage specification (V stage:
  architecture; ticket T-109, basis T-093). It derives the
  INTERNAL STRUCTURE of the release cadence from its system definition:
  the components, each one's responsibility, and the explicit dependency
  rules between them as **default-deny edges** — an edge exists only if
  listed here. Where the system definition or the pipeline design already
  fixes the structure, this doc records the mapping and the pass-through
  rationale instead of new structure (§Pass-through). It also traces
  every actor row, the store-only evidence oracle, both outcome paths and
  the non-blocking guarantees N1–N6 through the components to their
  evidence (§Traceability).
- **Read by:** the stage ticker pass (whose close sweep hosts the
  evaluator component), the kickoff assistant (fallback evaluator; cut
  executor), the engine review pass, the paired `test-architecture`
  ticket (created by this ticket's division — its criteria verify this
  doc), the maintainer; **written by:** T-109 (architecture stage,
  discipline `software-architecture`).
- **Related:** `docs/release-cadence-flow.md` (the upstream system
  definition this architecture structures; cited as [SYS]),
  `docs/design-stage-pipeline.md` (the pipeline design — where it fixes
  structure, recorded as pass-through; cited as [DSG]),
  `docs/live-in-eclipse-recurrence-flow.md` (the sibling system flow —
  composed with, referenced, never modified; cited as [REC]),
  `ROADMAP.md` (the T-041 standing rule — the rule layer above [SYS]),
  `.opencode/skills/stage-pipeline/SKILL.md` (the tick machinery the
  evaluation rides in), `eclipse/releng/release.ps1` (the cut machinery —
  reality anchor, read, never run here), `AGENTS.md` (write discipline,
  chat-first control plane H7, autonomy spectrum); tickets T-041 (the
  rule), T-039 (non-blocking acceptance), U-112 / U-111 (the worked
  example: the 2026-11-14 owner review that cut v0.2.0), T-094 (the
  paired system test whose engine verdict — APPROVE-WITH-NOTES — grounds
  this doc's claims in live evidence).
- **Currency (T-202, owner model 2026-11-20):** the sprint model is gone -
  no sprint records, no waves, no wave instances; the cadence unit is the
  pass's **completed work** (`done` / `not-implemented` tickets), and the
  sibling's wave instance (R2) retired with the model. The sprint-record,
  wave and R2-mint mechanics narrated below are historical - the verdicts
  stand as recorded on their dates (re-verified 2026-11-26).

## Scope

The release cadence is not a program — like the acceptance run's
architecture before it, it is a **process/agent architecture**: its
components are components of the run, each anchored to a real surface of
this repo (the task store, the ticker's close sweep, the chat session,
the release script, the flow documents), not to a package or library lane
[SYS §Overview]. The question this doc answers is: *which component owns
which cadence responsibility, who may act on whom through which
interface, and through which component chain every release decision's
evidence flows* — so that a reviewer reading only the store (plus git
refs) can decide the release state at any moment [SYS §3.4: the query is
re-runnable by anyone, including the paired test ticket].

Two scope exclusions, both inherited from the basis:

- The tick procedure itself (SCAN/BLOCKED/VERDICT/DONE/READY/CLOSE) and
  the sibling flow's mint belong to the stage pipeline's structure —
  this doc consumes the close sweep as a named extension point
  ([DSG §4]; [REC §5.1]) and structures only what the cadence adds to it.
- The spawn machinery is out of scope: the cadence never spawns (N1's
  "no spawn" is enforced here by the absence of any spawn edge).

## Chosen pattern

Evaluated against [SYS]: a **layered** pattern would imply call tiers the
cadence forbids (the evaluator writes two things and stops; nobody calls
the cut); **client-server** captures only the script invocation and
misses the evidence substrate; **event-driven** would wrongly license
reactive links — the cadence has no bus; evaluation is a poll at the tick
boundary, the only synchronization point [DSG §4].

Chosen: **blackboard over the task store, evaluated read-only at a fixed
cadence, executed only through a human-gated tool.** The store is the
shared substrate (all evidence and all cadence state live there as
ordinary tickets, comments and artifacts); two control components read it
at the close sweep and write exactly two non-state things; one tool
component executes the cut, reachable only from the chat control on the
owner's explicit go. This is the same pattern family as the
acceptance-run architecture (same substrate — consistency across the
requirement chains). The cadence's distinctive structural property is what it
DENIES: no edge from evaluation to execution, no edge from the tool to
the store.

## Components and layers

| Component | Layer | Responsibility (from the basis) | Anchored in (real code) | Basis |
|---|---|---|---|---|
| **C1 `store`** | substrate | The only evidence and state substrate: wave member tickets, sprint records, the sibling-owned acceptance instance (status, comments, artifacts with `by` authorship), the append-only history. Holds NO cadence-specific concept — no pending-release marker, no evaluated-flag [SYS §1]. Mutated only through the `task_*` verbs. | `.harness/tasks/hephaestus/` + the `task_*` verb surface (`task_list`, `task_get`, `task_add_comment`, `task_add_artifact`) | [SYS §1 "no new store concept"; §3.1; §4.3] |
| **C2 `evaluator`** | control | The ONLY routine evaluator: at the close sweep (tick step 6 — after the digest, after the sibling's R2 mint, before the record closes) runs the evidence query for each completed wave (the shared [REC R2] detection: record id not starting with `ticker-`, at least one ticket, zero open); records the outcome exactly twice — a digest line + an instance comment; outcomes: UNLOCKED (evidence ref) / PENDING REVIEW. | the `ticker-v` steward session running SKILL.md step 6, applying the STEP 6 EXTENSION wording from [SYS §4.1] | [SYS §2 row 1; §4.1; R1, R7] |
| **C3 `assistant`** | control | Fallback evaluator when no ticker is armed (same query, announced in chat — H7); the EXECUTOR of the cut on the owner's explicit go; re-recorder of owner speech under owner identities; recorder of cut results and script failures on the instance. | the main chat session (the kickoff assistant) | [SYS §2 row 2; §3.2; §4.2; R5] |
| **C4 `cut`** | tool (passive) | The D-002 release sequence under its own guards (in-repo, release branch, clean tree, tag-exists, X.Y.Z format, freshness); self-validating — the flow adds no validation of its own; every failure aborts before harm (a gate failure reverts the bump before any commit). | `eclipse/releng/release.ps1` | [SYS §4.2 "D-002, owned by the script"; N3, N4] |
| **C5 `knowledge`** | knowledge (static) | The oracle spec and the wording: identity conventions, the query, the content floor, the extension wording, the standing rule, the shared wave detection. Read by control; never an active process; runtime state never lives here (ROADMAP holds the rule, the store holds the state). | `docs/release-cadence-flow.md`, `docs/live-in-eclipse-recurrence-flow.md`, `ROADMAP.md` (rule), `.opencode/skills/stage-pipeline/SKILL.md` | [SYS §3; §6; R2–R4] |
| **C6 `verdict`** | governance | The engine review pass: the only `done` authority for the wave's tickets (the NECESSARY half of every release condition) and, after a cut, for the acceptance instance itself. Never the sufficient half. | SKILL.md tick step 3 (the reviewer subagent) + the per-project quality gate | [SYS §2 row 4; §3.5; §4.2; N6] |

Actor → component mapping (every [SYS §2] actor row lands in exactly one
home; the tickets are data owned by C1, not an actor):

| Actor [SYS §2] | Architectural home |
|---|---|
| Stage ticker pass | **C2** |
| Kickoff assistant | **C3** |
| Product Owner / maintainer | the human principal — outside the machinery; its edges are E4/E9 below plus the explicit go that gates E3 |
| Engine review pass | **C6** |
| PM agent / housekeeping passes | **no component** — [SYS §2]'s PM note is a denied edge: housekeeping never re-evaluates or re-records cadence outcomes |

Cross-cutting (each anchored in an existing component, no new owners):

- **Identity conventions** — a C1 data property classified by C5's
  table: comment/artifact `by` fields are self-asserted strings; the
  owner set (`owner`, `owner-directive`, `owner-acceptance`) is the only
  HUMAN class; every machine spelling — `ticker-v`, `ticker-v:<ticket>`,
  `review`, `chat-session`/`main-session` (T-094 TN-7: both spellings
  observed live, one machine rule covers both) — is never evidence.
- **Write discipline** — every store write goes through the `task_*`
  verbs (AGENTS.md); hand edits are not an architecture edge.
- **The digest** — a C2 convention recorded with the record close
  (U-061); it has no dedicated store API (accepted, [SYS §8]); the
  instance comment is the machine-readable half.

```text
              [C5 knowledge]  static: oracle spec · extension wording · rules
                ▲ E7               ▲ E8
                │ loads            │ loads
          [C2 evaluator]      [C3 assistant]══ the owner's explicit go ══► E3
            │ E1 query +         │ E2 query + record · owner-speech        │
            │ outcome record     │ re-record · cut results + failures      ▼
            ▼                    ▼                                   [C4 cut]
   ┌──────────────────────────────────────────┐                    │ E5 D-002
   │ [C1 store] wave members · acceptance     │                    ▼
   │ instance · comments/artifacts (`by`) ·   │               repo tree
   │ history · sprint records                 │                    ▲
   └──────────────────────────────────────────┘                    │ E4
            ▲ E6 read + done                                 [owner]
         [C6 verdict]          (owner edges: E9 evidence writes → C1;
                                E4 may run the cut; the go unlocks E3)
```

## Dependency rules (default-deny)

Layer rule: dependencies point toward the substrate or static knowledge
only — control depends on substrate + knowledge; the tool depends on the
repo tree only; governance depends on the substrate; substrate and
knowledge depend on nothing. The human principal sits outside the
layering (the owner can do anything). **Default deny: an edge exists only
if listed here.**

| Edge | Direction | Interface (verbs / mechanism) | Basis |
|---|---|---|---|
| E1 | C2 → C1 | Read: `task_list(sprint=<record id>)`, `task_get` (the instance + every member), scan `comments` + `artifacts` `by` fields. Write: `task_add_comment` (the outcome on the instance) + the digest line carried by the record close (U-061). Nothing else — no claim, no status change, no `task_set_blocked`, no spawn. | [SYS §3.4; §4.1; N1, N2] |
| E2 | C3 → C1 | The same query as fallback evaluation + outcome comment + chat announcement (H7); owner-speech re-recording (`owner-directive` comments quoting the owner's words); cut recording (git artifact + acceptance comment); script-failure comments (N4's executor half). | [SYS §3.2; §4.1; §4.2; N4] |
| E3 | C3 → C4 | `release.ps1` invocation — preconditions: the owner's explicit go (the release decision, R5), between passes (quiet tree), version = the current `-SNAPSHOT` stripped (the script's format guard rejects everything else); `-Push` only on the explicit go. The exit result flows back to C3, which records it over E2. | [SYS §4.2; N3] |
| E4 | owner → C4 | The owner may run the script personally (the owner can do anything; the worked example cut v0.2.0 from the acceptance session). | [SYS §4.2] |
| E5 | C4 → repo tree | The D-002 mutation: set-version → quality gate → "Release vX.Y.Z" commit + annotated tag → next `-SNAPSHOT` bump → optional `-Push`, each step under the script's own guards; every `Die` aborts, the gate failure reverting pre-commit. | [SYS §4.2; N4] |
| E6 | C6 → C1 | Engine verdict: reads + `done` for the wave's tickets; after a cut, the instance's own normal engine-verdict close (the [REC] lifecycle untouched). | [SYS §2 row 4; §4.2; N6; REC G3] |
| E7 | C2 → C5 | Loads SKILL.md (the tick procedure it rides in) + the STEP 6 EXTENSION wording + the oracle spec it applies. | [DSG §4, §6; SYS §4.1] |
| E8 | C3 → C5 | Loads the cadence doc (fallback evaluation + executor rules), the standing rule, and the sibling mint wording it composes with. | [SYS §2 row 2; §6; H7] |
| E9 | owner → C1 | Direct evidence writes: owner-set comments and artifacts (the worked example recorded the release artifacts under `owner-acceptance`). The only writes that can ever classify as HUMAN evidence. | [SYS §3.2; R2] |

Denied edges (each with its structural reason — these are architecture
rules, not conventions):

- **C1 → anything**: the store is a passive substrate — no callbacks, no
  notifications; the cadence is discovered by read at the tick boundary
  [DSG §4].
- **C2 → C4**: THE central denial — the ticker never runs
  `release.ps1` (a mid-pass tick cannot promise the quiet tree the
  script demands; the push policy demands the owner's explicit go) [SYS
  §2 row 1 never-does; §4.1 step 4; N3; R5].
- **C2 → blocking / escalation surfaces**: a PENDING REVIEW never sets
  `task_set_blocked`, never blocks the record, never files NEEDS-HUMAN;
  the open instance is the surface [N2; R6; SYS §4.1 step 4].
- **C2 → owner identities**: the ticker never writes under the owner
  set; its own digest claims are never evidence [R2; SYS §3.5].
- **C4 → C1**: the script has no store access; git anchors RESULTS (the
  tag, the release commits), never the trigger; all recording is
  executor work over E2 [SYS §3.1; T-094 TN-3]. This is also the
  structural form of [REC G5] ("release unaffected"): the module that
  cuts reads no instances — see §Traceability, seams.
- **C6 → C4**: no verdict pattern, however green, triggers or unlocks a
  cut [N6; T-039; SYS §2 row 4].
- **C3 → C4 without the go**: E3's precondition is the owner's explicit
  go; without it there is no invocation and no version invention (the
  current `-SNAPSHOT` stripped is the only legal argument) [R5; SYS
  §4.2].
- **PM / housekeeping → evaluation**: no routine PM role exists in this
  flow; a housekeeping pass must not re-evaluate or re-record cadence
  outcomes [SYS §2, the PM note].
- **Any machine actor → owner-set writes**: only the owner's own writes
  (E9) and C3's re-recording of owner speech under `owner-directive` may
  carry owner identities. Authorship is self-asserted, so this denial is
  a process rule whose violations are visible in the history audit
  trail, not a technical impossibility ([SYS §8, accepted]; AD-006).

Direction consistency: C1 and C5 have **zero outgoing edges**. C2 points
only at C1 + C5. C3 points at C1 + C5 plus the single invocation edge E3.
C4 points only at the repo tree. C6 points only at C1. The graph is
acyclic; every machine actor's only outbound channel is toward the
substrate or knowledge, and the one execution edge is human-gated.

## Interfaces to the real code

- **C1 `store`** — `.harness/tasks/hephaestus/` and the `task_*` verbs
  (the `tasks` stdio launcher or the `eclipse-build` endpoint; wire names
  stay `task_*`). Invariants relied on: append-only history with
  caller-asserted authorship, and the comment channel on tickets — which
  is WHY the outcome lives on the acceptance instance: the sprint record
  itself has no comment channel [SYS §4.1 step 3].
- **C2 `evaluator`** — the `ticker-v` schedule (armed by
  `ticker-setup.ps1`) running SKILL.md's tick; the evaluation attaches at
  step 6 (close), ordered after the digest and the sibling's R2 mint,
  before the record closes. Honest note (T-094 TN-1): SKILL.md carries no
  cadence anchor sentence yet — the ticker's discovery path is [SYS]
  itself ("Read by: the stage ticker pass"), the same
  doc-supplies-the-wording pattern the sibling uses; the structure here
  is unaffected.
- **C3 `assistant`** — the main chat session (H7): fallback evaluator at
  wave close, announced in chat; executor between passes, on the go;
  re-recorder (live precedents: the `owner-directive` comments on
  T-036, T-039, U-113).
- **C4 `cut`** — `eclipse/releng/release.ps1`: guards (repository,
  build wrapper, X.Y.Z format — rejects any suffixed version, tag-exists,
  branch, dirty tree, fetch freshness) and the five-step D-002 sequence;
  every failure is a `Die` that aborts, the gate failure reverting the
  version bump before any commit. Live anchors (T-094 ITS-004): the
  reactor version is `0.2.1-SNAPSHOT`, so the next legal cut is `v0.2.1`
  (then `0.2.2-SNAPSHOT`); tags `v0.1.10`, `v0.1.11`, `v0.2.0` exist.
- **C5 `knowledge`** — the documents themselves; they are the carriage of
  rule change ([DSG §6]: change once, every actor inherits).
- **C6 `verdict`** — SKILL.md tick step 3 (the reviewer subagent, the
  engine's verdicts) + the project's quality gate.

## Pass-through: where the upstream already fixes the structure

Per this stage's contract, where [SYS] or [DSG] already fixes structure,
this doc records the mapping and the pass-through rationale — not new
structure:

| Upstream fixes | Recorded here as | Pass-through rationale |
|---|---|---|
| [SYS §2] the four actors + the never-does column | C2/C3/C6 + the owner principal; never-does → the denied-edge list | The actor structure is decided; the architecture only names components and encodes never-does as absent edges |
| [SYS §1] "No new store concept, no new ticket type, no new tool" | C1 holds zero cadence-specific data; the cadence mints no tickets (AD-001) | The zero-new-surfaces promise IS the substrate contract; nothing architectural to add |
| [SYS §3] the oracle (identities, query, floor) | C5 spec applied by C2/C3 over C1 data (AD-003) | The oracle is deliberately knowledge, not machinery — no component implements it; actors apply it per evaluation |
| [SYS §4.1] the evaluation point + wording | C2's close-sweep order (digest → sibling mint → cadence evaluation → record close) | The tick machinery is [DSG §4]-fixed and determinism is the point; the extension rides in it verbatim |
| [SYS §4.2] "the flow adds no validation of its own" | C4 as a self-guarding tool (AD-004) | Verbatim pass-through: the script's guards are the only validation layer |
| [SYS §4.3] parking via existing states | C1's open instance + the digest line as THE pending markers (AD-005) | "ROADMAP holds the rule, this flow holds the state"; no pending-release concept exists |
| [SYS §4.4] re-run convergence | E1/E2's marker-free, idempotent query (N5, QA-3) | No stored "already evaluated" marker to invalidate — decided upstream |
| [REC §3–§5] the instance lifecycle (mint, execute, verdict) | "One instance, two readers": [REC] owns the lifecycle; the cadence adds only E1 reads + outcome comments | The sibling owns its flow's tickets; this architecture adds no edge into it |
| [DSG §4] the tick algorithm; the unified `ticker-v` topology | C2's fixed procedure | [DSG]'s per-stage illustration is realized live as ONE `ticker-v` steward walking all stages ([SYS §2]; SKILL.md); determinism unchanged |
| [DSG §6] skills/docs as static knowledge | C5 | "Skills are the carriage of process change" — knowledge, not runtime actors |
| [DSG §8] engine-only `done` | C6 + the denied C6→C4 edge | The verdict model is decided; the architecture states it as an unreachable-transition rule |
| [DSG §2/§3] store contract (`stage:` immutability, basis links, history, write discipline) | C1 invariants | Store semantics decided; C1 owns them wholesale |

## Traceability: the cadence through the components

The traceability duty of this stage, section by section of [SYS]: every
element named in the ticket's AC-2 traced through the components above to
its evidence.

### Actor rows ([SYS §2])

| Actor row | Component | Responsibility mapped | Never-does → enforced by | Evidence it leaves in C1 |
|---|---|---|---|---|
| Stage ticker pass | C2 | the only routine evaluator: E1 query per completed wave at the close sweep; outcome recorded twice | runs `release.ps1` → denied C2→C4; blocks or escalates → denied blocking edge; counts its digest claims as evidence → denied owner-identity writes + the oracle's machine rule | the digest line (U-061) + the instance comment under a machine identity — never review evidence |
| Kickoff assistant | C3 | fallback evaluator (announced in chat); executor of the cut on the go; owner-speech re-recorder; failure/result recorder | cuts without the go → E3's precondition; treats a `chat-session` comment as evidence → the oracle's machine rule; improvises a version → C4's format guard | `owner-directive` comments (the re-recording — the HUMAN evidence); `chat-session`/`main-session` comments (machine); git artifacts + acceptance comments after a cut |
| Product Owner / maintainer | the human principal | the REVIEWER — the only identity whose evidence unlocks a cut; gives the go (incl. `-Push`); may run C4 personally | is nagged → no nagging component exists anywhere in the graph (accepted, [SYS §8]) | owner-set comments/artifacts (E9) — the only HUMAN class [R2] |
| Engine review pass | C6 | the NECESSARY half; the only `done` authority (wave tickets; the instance after a cut) | the SUFFICIENT half → denied C6→C4 + the oracle's never-counts | engine verdicts (done events, verdict comments) — machine class, never evidence |

### The evidence oracle ([SYS §3]) — where each clause lives

| Oracle clause | Architectural home | How the evidence flows |
|---|---|---|
| store-only (§3.1) | C1 is the only evidence substrate | the query reads C1 exclusively (E1/E2); no component reads anywhere else for review evidence |
| not status-transition history (§3.1) | C1 data property: history events carry no actor stamp (the A-002 finding; live corroboration: 165 of 199 events actorless — T-094 ITS-003) | the query never reads history |
| not assignee fields (§3.1) | assignee = routing marker (`owner-acceptance` means "awaits the owner") | the query scans comments + artifacts only |
| not git authorship (§3.1) | denied C4→C1: git anchors results, never the trigger | git refs are read only after a cut, as results (Path A step 6) |
| not chat text (§3.1) | chat is the interface (H7), not the record | only a store write classifies |
| identity conventions (§3.2) | C5's table over C1's `by` fields; owner set = the only HUMAN class | the scan matches `by` against the owner set |
| the `chat-session` call-out (§3.2) | C3's own writes are machine evidence; the re-recording under `owner-directive` quoting the owner's words is the human evidence (T-036, T-039, U-113 precedents) | E2 writes both classes; the oracle classifies them apart |
| canonical instance evidence (§3.3) | the sibling-owned acceptance instance (C1 data; lifecycle per [REC]); `done` + an owner-set artifact and/or comment | E1/E2 read it; C6 provides its `done` |
| secondary shapes (§3.4) | owner-identity acceptance comments on member tickets (timestamped at/after wave completion); NEEDS-HUMAN-resolution comments that accept | same scan, same floor |
| the 4-step query (§3.4) | C2's evaluation procedure (E1); C3's fallback (E2) | re-runnable by anyone — T-094 ran it live on real tickets, both polarities |
| the content floor (§3.4) | C5 spec; judged per evaluation; the conservative asymmetry R4 | ambiguous → PENDING (a false negative parks; a false positive cuts) |
| never-counts (§3.5) | negative clauses, structurally reinforced: C2 cannot write owner identities, C4 cannot write the store, verdicts are machine | green verdicts still produce PENDING (N6; demonstrated live — T-094 ITS-005) |

### Path A — review present: the cut ([SYS §4.2])

1. Owner evidence enters C1: E9 direct (the worked example recorded
   `e2c2b74`/`29d9bc9` under `owner-acceptance`) or via E2's
   re-recording under `owner-directive`.
2. A close sweep's query finds owner-set evidence passing the floor →
   outcome UNLOCKED, recorded twice (E1: digest + instance comment).
3. The owner gives the explicit go in chat or person — the release
   decision, one of the owner's roles (T-036).
4. C3 invokes C4 (E3) between passes: quiet tree, release branch —
   C4's own guards enforce everything else.
5. C4 runs the D-002 sequence on the repo tree (E5): set-version →
   quality gate (a failure reverts, aborts pre-commit) → "Release
   vX.Y.Z" commit + annotated tag → next `-SNAPSHOT` bump → optional
   `-Push` on the explicit go. The version is the current `-SNAPSHOT`
   stripped — never invented in chat.
6. Results recorded back over E2: a git artifact (tag / release-commit
   refs), an acceptance comment on the instance, a HISTORY.md entry
   [R7]. Worked example, all store/git-verifiable: tag `v0.2.0` →
   commit `e2c2b74` → `29d9bc9` (U-112/U-111 comments and artifacts
   under `owner-acceptance`).
7. The instance closes through its own normal engine-verdict path (E6) —
   the [REC] lifecycle untouched.

Evidence chain, store/git-verifiable end to end: **owner-set evidence in
C1 → UNLOCKED outcome comments → git tag + release commits → git
artifact + acceptance comment + HISTORY entry.**

### Path B — no review: commit + push, park ([SYS §4.3–4.4])

1. The query finds no owner evidence (or the floor fails) → outcome
   PENDING REVIEW, recorded twice (E1; E2 as the announced fallback).
2. The ordinary wave close proceeds — commit + push ALWAYS happens (the
   standing rule's first half, distinct from the release's `-Push`).
   Path B adds nothing: it is the absence of Path A's steps 3–5.
3. The release parks in two pre-existing, Board-visible states: the OPEN
   acceptance instance (`in-progress`, assignee `owner-acceptance`,
   findable by scanning open `(wave ...)` titles) and the digest line.
4. Later evidence ([SYS §4.4]): the next sweep's re-query flips the
   outcome to UNLOCKED — there is no stored marker to invalidate (N5,
   QA-3); one review session typically unlocks ONE cumulative cut
   (per-wave granularity in the evidence, per-occasion cut).

Evidence chain: **the open instance + the digest line; nothing new is
created on the park path** — parking is a read plus two non-state
writes.

### The non-blocking guarantees N1–N6 ([SYS §5])

| Guarantee | Enforcing structure (edge or denial) |
|---|---|
| N1 read-only until the record | E1's write surface is exactly two non-state writes (digest line + instance comment); no claim, status, spawn or readiness edges exist for C2; the sweep fires after completion is already true |
| N2 pending is not blocked | the denied blocking/escalation edge; the open instance (a state the owner already watches) is the surface — the T-039 shape |
| N3 cut never runs mid-pass | the denied C2→C4 edge; E3's go + between-passes preconditions; the tree serializes structurally — C4's dirty-tree guard cannot pass while workers write |
| N4 failure isolation | C4 self-guarding: every `Die` aborts (gate failure reverts pre-commit; push failure leaves honest local-only state); the executor records the failure on the instance over E2 (the script has no store access — T-094 TN-3); tickets, records and verdicts untouched; retry at the next sweep or the next go |
| N5 convergence | the query is idempotent and re-run every sweep; C1 holds no "already evaluated" marker (AD-001); a sweep lost mid-run is re-derived by the next — the tick boundary is the only synchronization point [DSG §4] |
| N6 verdicts alone never cut | the denied C6→C4 edge; the oracle's machine-evidence rule; the release condition is verdicts AND owner evidence, in that combination, forever [T-039] |

### The invariants R1–R7 ([SYS §7]) as structure

| Invariant | Structural form |
|---|---|
| R1 only the close sweep (or announced fallback) evaluates | E1/E2 are the only evaluation edges; every other actor's evaluation is denied (the PM/housekeeping denial) |
| R2 owner set = only review evidence | E9 + E2's re-recording clause are the only owner-identity writes; C2 denied owner identities; the oracle's machine rule |
| R3 comments/artifacts authorship only | the query's scan surface; denied C4→C1 (git); history and assignees excluded by the oracle spec |
| R4 ambiguous → PENDING | the content floor's asymmetry, applied per evaluation (QA-2) |
| R5 only the owner's go authorizes the cut | E3's precondition; E4 owner-only; denied C2→C4 |
| R6 pending blocks nothing | the denied blocking/escalation edge; parking rides passive states (AD-005) |
| R7 every outcome recorded twice; the release as git artifact + HISTORY | E1's double write; Path A step 6's recording chain; runtime state only in C1, rule only in C5 |

### Composition seams with the sibling flow — preserved by structure

([SYS §6]; [REC] is referenced, never modified by this architecture.)

| Seam | How the structure preserves it |
|---|---|
| One sweep, two extensions, fixed order | C2's close-sweep order: digest → sibling R2 mint → cadence evaluation → record close; the evaluation's E1 read sees the freshly minted instance (order verified live: T-094 ITS-001) |
| One wave detection, shared verbatim | the detection rule exists once — in C5 ([REC §4 R2] wording); C2's procedure references it, never restates it |
| One instance, two readers | [REC] owns mint/execute/verdict; the cadence adds only E1 reads + outcome comments; the cadence mints no tickets of its own |
| The outcome lives on the instance | the record has no comment channel; the instance does — that asymmetry of C1 is why the outcome comment lands there [SYS §4.1 step 3] |
| Mirrored guarantees | [REC G4] = N1; [REC G5] = the denied C4→C1 edge seen from the other side — per T-094 TN-2's resolution, the close-sweep evaluation is a cadence step, NOT a release step: the component that reads the instance (C2) is not the component that cuts (C3 → C4), and the cut machinery itself has no store edge at all |
| What this flow adds that [REC] lacks | the oracle (C5 spec over C1 data) and the cut path (E3/E4/E5) — exactly [SYS §6]'s last bullet |

## Key quality attributes

- **QA-1 Non-blocking by construction** — every N-guarantee is enforced
  by an ABSENT edge, not by discipline: the cadence's only writes are two
  non-state writes; the only execution path is human-gated [T-039; SYS
  §5]. The structure cannot stall a wave even if every component
  misjudges.
- **QA-2 Evidence decidability + conservative asymmetry** — every
  release state decidable from store reads + git refs alone; ambiguity
  resolves to PENDING. Proven live, both polarities: PENDING on the
  `ticker-v` record, UNLOCKED on U-111/U-112 (T-094 ITS-003/ITS-005).
- **QA-3 Convergence** — idempotent, marker-free, re-derived every
  sweep; a lost sweep costs nothing (the tasks namespace can drop
  mid-run — it did during T-094's own run) [DSG §4; SYS §4.4].
- **QA-4 Human-authority preservation** — the release decision is
  structurally human twice: the evidence must be owner-AUTHORED (the
  oracle) and the execution owner-AUTHORIZED (E3's go / E4). No machine
  path to the cut exists: C2→C4 and C6→C4 are absent, and C4's guards
  refuse everything else.

## Architecture decisions

- **AD-001 Zero new surfaces** — every component is anchored to an
  existing surface (store verbs, the existing close sweep, the existing
  script, existing docs); C1 gains no cadence data; the cadence mints no
  tickets [SYS §1]. Trade-off: the architecture is a set of conventions
  over shared surfaces — its edges are denials, invisible in code;
  enforcement is doc + review, and the paired test keeps it honest by
  re-running the query.
- **AD-002 Default-deny, with C2→C4 as the sharpest denial** — the
  evaluator can never become the cutter. Trade-off: a cut needs a second
  actor and a human go, so UNLOCKED and the cut are separated in time —
  deliberate (the go IS the release decision).
- **AD-003 The oracle is knowledge, executed** — spec in C5, applied by
  control per evaluation; no oracle implementation exists. Trade-off:
  the content floor is judged, not parsed — mitigated by the
  conservative asymmetry (R4) and by live calibration (T-094 ran the
  query on real tickets).
- **AD-004 The cut is a self-guarding tool; the flow adds no validation**
  — full pass-through of D-002 [SYS §4.2]. Trade-off: none taken —
  deliberate non-duplication; the script's guards are the single
  validation layer.
- **AD-005 Pending state rides existing states** — the open instance +
  the digest line; no pending-release concept; Board visibility for free
  [SYS §4.3]. Trade-off: no dedicated pending query — discovery is by
  scanning open `(wave ...)` titles; accepted (harmless; the Board shows
  it anyway).
- **AD-006 Authorship conventions replace authentication** — `by`
  strings are self-asserted; the store cannot authenticate them ([SYS
  §8], accepted). Mitigations: the conservative asymmetry (R4) + the
  append-only history audit trail; a machine writing `owner-directive`
  fraudulently is a visible process violation, not a technical
  impossibility.

## Open questions

None new at the architecture level. The inherited items change wording or
data, never the edge set: T-094 TN-1 (no SKILL.md anchor sentence for the
cadence extension yet — doc-supplies-the-wording; a one-sentence anchor
at the next SKILL.md revision closes it), TN-2 (the G5 wording
tightening — its resolution is already structural here: denied C4→C1),
TN-4 (T-040's one-time transition; [REC §7] prescribes it), the digest's
missing dedicated API surface (accepted, [SYS §8]), and no nagging
(accepted, [SYS §8] — the review cadence is an owner dial).

## Coverage

- **[SYS §1]** overview + micro-record carve-out → §Scope (the sweep
  target: substantive waves, detection shared with [REC]) + AD-001.
- **[SYS §2]** actors (+ the PM no-role note) → §Components mapping +
  §Traceability actor rows + the PM denial.
- **[SYS §3]** the oracle → C1/C5 + §Traceability oracle table.
- **[SYS §4]** the flow (evaluation point, Path A, Path B, the later
  unlock) → C2's close-sweep order + §Traceability both paths + QA-3.
- **[SYS §5]** N1–N6 → the denied-edge list + §Traceability N-table +
  QA-1.
- **[SYS §6]** composition → §Traceability seams ([REC] referenced,
  never modified).
- **[SYS §7]** R1–R7 → §Traceability invariant table.
- **[SYS §8]** trade-offs → AD-005, AD-006, §Open questions.
- **[DSG]** §1/§4 (the tick, the unified ticker topology), §2/§3 (the
  store contract), §6 (the knowledge layer), §8 (engine-only done) →
  §Pass-through.
- **Rule layer** (T-041 in ROADMAP; T-039; the autonomy spectrum /
  intake discipline in AGENTS.md) → cited through [SYS]; QA-4 is its
  structural restatement.
- **Not covered (out of scope):** the tick procedure's other steps, the
  sibling flow's internals, the spawn machinery — other walks and later
  stage products; nothing here constrains them beyond the named
  extension point.
