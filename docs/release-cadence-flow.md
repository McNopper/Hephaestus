# Release cadence — the system flow (who evaluates the human-review trigger and cuts)

## About this document

- **Kind:** `doc` / system-stage flow specification (V stage: system; ticket
  T-093, basis T-041). It specifies the release cadence as a
  SYSTEM flow: which actor evaluates the human-review condition at each
  pass close, what store-verifiable evidence counts as "a human review",
  both outcome paths (cut via `release.ps1` vs commit + push with the
  release parked), and how non-blocking is guaranteed.
- **Read by:** the stage ticker pass (executes the evaluation at its close
  step), the kickoff assistant (fallback evaluator; the executor of the
  cut on the owner's go), and the maintainer (product owner — the only
  reviewer whose evidence unlocks a cut); **written by:** T-093 (system
  stage, discipline `software-system`).
- **Related:** `ROADMAP.md` Standing rules (the release-cadence rule,
  T-041: "A human review unlocks a release; otherwise commit and push";
  its About rule: this file, not ROADMAP, carries the runtime mechanics),
  `AGENTS.md` (autonomy target; the autonomy spectrum — review cadence is
  set at goal intake), `docs/live-in-eclipse-recurrence-flow.md` (the
  sibling system flow — its pairing instances and the NON-BLOCKING
  acceptance mechanics this flow reads; the two docs compose as one
  ruleset, see section 6),
  `eclipse/releng/release.ps1` (the D-002 cut sequence this flow invokes,
  never reimplements), `.opencode/skills/stage-pipeline/SKILL.md` (tick
  step 6 — the close this flow runs inside), tickets T-041 (the rule),
  T-039 (non-blocking acceptance), U-112 / U-111 / T-002 (the worked
  example: the 2026-11-14 owner review that cut v0.2.0).
- **Currency (T-202, owner model 2026-11-20):** the sprint model is gone —
  no sprint records, no waves to detect, no wave instances. The cadence
  unit is the pass's **completed work** (`done` / `not-implemented`
  tickets), re-derived from status on every evaluation; the sibling flow's
  wave instance (R2) retired with the model, so the canonical evidence is
  owner-set evidence on the completed work itself.

## 1. Overview

The standing rule (T-041, owner requirement 2026-11-15) fixes the cadence:
completed work — every ticket `done` or `not-implemented` with engine
verdicts — is always committed and pushed; the VERSION is cut
(`eclipse/releng/release.ps1`, tag per D-002, bump to the next `-SNAPSHOT`)
only when that work carries a HUMAN review. The pass, not the calendar, is
the release cadence; the human review, not the pass alone, is the release
trigger. Engine verdicts are necessary for a release, never sufficient
(T-039).

What the rule leaves open is mechanization: at each pass close, WHO checks
for the review, against WHAT evidence, and what happens on each outcome —
without ever stalling the flow. This document answers those three
questions with store-native machinery only: the tick close step that
already exists (SKILL.md step 6), the completed tickets that already
exist, and the store identities that already exist. No new store
concept, no new ticket type, no new tool: the release is parked, evaluated
and cut entirely through surfaces that are already on the Board.

## 2. Actors

| Actor | Role in the cadence | Never does |
|---|---|---|
| **Stage ticker pass** (`ticker-v` steward running the stage-pipeline skill) | the ONLY routine evaluator: runs the evidence query over the completed work at tick step 6 (section 4.1); records the outcome in the pass digest and on the goal root | executes `release.ps1`; blocks any ticket over a missing review; files NEEDS-HUMAN for an absent review; counts its own digest claims as review evidence |
| **Kickoff assistant** (main chat agent) | FALLBACK evaluator when no ticker is armed (chat-first control plane, H7); the EXECUTOR of the cut: runs `release.ps1` on the owner's explicit go, records owner speech under owner identities | cuts without the owner's go; treats a `chat-session` comment as review evidence by itself; improvises a version number outside the script's rules |
| **Product Owner / maintainer** | the REVIEWER — the only identity whose evidence unlocks a cut; gives the explicit go (including the `-Push` go); may run `release.ps1` personally; reviews at their own cadence (the autonomy spectrum, set per run at goal intake) | is nagged: an overdue review never escalates, it only parks |
| **Engine review pass** (tick step 3, the `reviewer` agent) | the only `done` authority for the completed work's tickets — the NECESSARY half of every release condition | the SUFFICIENT half: no verdict pattern, however green, unlocks a cut |

The PM agent has no routine role in this flow: in the unified ticker-v
topology the ticker IS the routine PM-side actor; `pm`-family identities
appear only in housekeeping, and a housekeeping pass must not re-evaluate
or re-record cadence outcomes (I6).

## 3. The evidence oracle (what counts as "a human review")

### 3.1 Where evidence lives — and where it does not

The oracle is STORE-only, and inside the store it keys on **comment and
artifact authorship** (the `by` fields). It deliberately does NOT key on:

- **Status-transition history events.** The store's `updated:status`
  events carry no actor — established live by the A-002 engine verdict
  FAIL (2026-11-15): a worker-flipped status is indistinguishable from an
  engine-flipped one. Anything that only shows as a status change is not
  evidence of anything in this flow.
- **Assignee fields.** The sibling mint sets `owner-acceptance` as the
  acceptance instance's ASSIGNEE — that is a routing marker ("awaits the
  owner"), never a record of the owner having acted.
- **Git authorship.** Agents commit; commit author names do not map to
  store identities. Git anchors RESULTS in this flow (the tag, the release
  commit — section 4.2), never the trigger.
- **Chat text.** Conversation is the interface, not the record; only a
  store write is evidence.

### 3.2 The identity conventions (the names actually in use)

The `by` fields are self-asserted strings; their meaning is convention.
The conventions in live use in this store:

| Identity family | Examples observed | Class |
|---|---|---|
| Owner set | `owner`, `owner-directive`, `owner-acceptance` | HUMAN — the only review evidence |
| Ticker steward | `ticker-v` (division notes, planning), `ticker-v:review` (engine verdict comments, escalations), `ticker-v:pm-resume` | machine |
| Worker form `<steward>:<ticket>` | `ticker-v:T-042`, `ticker-v:T-093`, `test-system-worker:A-002` | machine |
| Stage-local legacy | `system-ticker`, `system-worker`, `architecture-worker` (pre-unified runs) | machine |
| Reviewer on done events | `review` ("done (engine review pass: product PASS)") | machine |
| Main chat session | `chat-session` (review legs, completion reports, artifact corrections) | machine — see below |
| PM family | `pm`, `pm-resume`, `pm-housekeeping` | machine |
| Maintenance / retired | `store-hygiene`, `executor`, `fleet`, `agent`, `lane-fleet` | machine |

`chat-session` deserves the explicit call-out: it is the kickoff
assistant — an AGENT — even though the owner speaks through it. A
`chat-session` comment therefore NEVER satisfies the oracle by itself,
however owner-shaped its text. The established re-recording convention
covers the real case: when the owner directs something through chat, the
kickoff assistant records it under `owner-directive` with the owner's
words quoted (live precedents: T-036, T-039, U-113 comments). That
re-recording is the human evidence; the chat line is not.

### 3.3 The canonical evidence: owner-set evidence on the completed work

With the wave instance (sibling R2) retired with the sprint model
(T-202), the canonical evidence shape is owner-set evidence landing ON
the completed work itself:

> The completed work carries a human review when at least one of its
> tickets is `done` AND carries owner-set evidence — an owner-identity
> artifact (the I7 evidence shape: screenshot or panel transcript
> reference) and/or an owner-identity acceptance comment.

The sibling flow's pairing instances (R1) remain the per-definition
live-pass surface: an owner artifact or acceptance comment recorded on a
pairing instance counts as soon as that instance reaches `done` (it is
then a completed ticket in the scan set); an open pairing instance is the
visible "the live pass awaits the owner" state, not yet evidence.

This is store-verifiable end to end: `task_list` the completed rows,
`task_get` each, read `comments`, `artifacts`. It composes with the
sibling ruleset instead of paralleling it: the instance's own
NON-BLOCKING guarantee is untouched — the review still never gates the
work; it gates only the RELEASE, which the standing rule already gates on
exactly this.

### 3.4 Secondary shapes and the query

Not every review will have flown through an acceptance instance (an owner
commenting directly on the work). Equally valid evidence, discovered by
the same scan:

- an owner-identity acceptance comment on ANY completed ticket,
  timestamped at or after its completion (the last engine verdict on that
  ticket — a review of an earlier state is superseded when later verdicts
  change the reviewed content);
- an owner-identity comment that explicitly accepts the outcome,
  recorded when the owner resolves one of the NEEDS-HUMAN
  escalations (a bare unblock is problem-fixing, not acceptance; the text
  must accept).

The query, mechanically (re-runnable by anyone, including the paired
test ticket):

1. `task_list(status=done)` + `task_list(status=not-implemented)` — the
   completed work (SKILL.md step 6 rekeys this section to exactly that
   set; there is no wave membership to list anymore).
2. `task_get` each row, plus the goal root when one exists.
3. Scan `comments` + `artifacts` for `by` in the owner set
   (`owner`, `owner-directive`, `owner-acceptance`).
4. Content floor: the matched text must STATE an outcome (accept,
   approve, review verdict) — a question, a direction or a drive-by note
   is not acceptance.

Step 4 is semantic, and the flow is honest about that: authorship is
mechanical, meaning is judged. The conservative asymmetry resolves every
doubt — **ambiguous evidence is NOT a review.** A false negative costs a
parked release (the owner reviews when they review anyway); a false
positive costs an unauthorized version cut. The default errs toward
"pending".

### 3.5 What never counts

Engine verdicts in any pattern (`review`, `ticker-v:review` — green
verdicts on every ticket are the necessary half, never the
sufficient one); assignee markers; commit authorship; `chat-session`
comments alone; the ticker's own digest claims; silence (an empty result
is "pending", not implicit approval).

## 4. The flow, end to end

### 4.1 Evaluation point — the step-6 close

The evaluation lives in the ticker's close step: tick step 6, once per
pass, over the completed work (there is no record to close and no wave
sweep — T-202). The wording the next ticker pass applies:

```text
STEP 6 EXTENSION — run once per pass, at the close:

  RELEASE-CADENCE EVALUATION (owner requirement 2026-11-15, T-041)
    1. Run the evidence query (section 3.4) over the completed work:
       task_list(status=done) + task_list(status=not-implemented), plus
       the goal root when one exists.
    2. If the query finds owner-set evidence: outcome := UNLOCKED
       (evidence: <ticket id> + author + ts). Else: PENDING REVIEW.
    3. Record the outcome:
       - one line in the tick's chat output (the pass digest, H7):
         "release cadence: <UNLOCKED (evidence <ref>) | pending review>";
       - when a goal root exists, a task_add_comment on it with the same
         outcome at first determination and at every flip (the state
         lives on a ticket, where comments and history work).
    4. Do NOT run release.ps1. Do NOT block anything. Do NOT spawn.
       Do NOT escalate a PENDING REVIEW as NEEDS-HUMAN — the open work
       already surfaces it to the owner on the Board.
```

When no ticker is armed, the kickoff assistant is the fallback evaluator
at close, same query, announced in chat (H7) — mirroring the
sibling's fallback mint.

### 4.2 Path A — review present: the cut

The evaluation only DETERMINES and RECORDS; execution is a separate
motion with a human in the loop:

- **Who runs it:** the kickoff assistant, in the chat session where the
  owner gives the explicit go — or the owner personally (the owner can
  do anything; the worked example ran the cut from the acceptance
  session). The ticker NEVER runs it: the script needs a quiet tree,
  which mid-pass ticks cannot promise, and the script's own push policy
  demands the owner's explicit go. The go IS the release decision — one
  of the owner's roles (requirements, decisions, acceptance — T-036);
  execution is machine-keeping.
- **From where:** inside the repository, on the release branch, clean
  tree — the script's own guards refuse everything else (branch check,
  dirty-tree check, tag-exists check, X.Y.Z format check, freshness
  check), so the flow adds no validation of its own.
- **The sequence (D-002, owned by the script):** set-version to the
  release version across the reactor -> the quality gate
  (`clean verify -Pquality`; a failure reverts and aborts before any
  commit) -> commit "Release vX.Y.Z" + annotated tag `vX.Y.Z` ->
  set-version to the next `-SNAPSHOT` -> optional `-Push`
  (branch + tag; only on the owner's explicit go). The version is the
  current `-SNAPSHOT` with the suffix stripped — never invented in chat.
- **Recording the release:** a `git` artifact (the tag / release-commit
  ref) and an acceptance comment on the goal root (when one exists);
  the event goes to `docs/HISTORY.md` (history discipline — past events
  live only there). Worked example, all store-verifiable: the 2026-11-14
  owner acceptance session cut v0.2.0 — tag `v0.2.0` -> commit e2c2b74
  "Release v0.2.0" -> 29d9bc9 "Prepare 0.2.1-SNAPSHOT" (U-112/U-111
  comments, artifacts `e2c2b74`/`29d9bc9` under `owner-acceptance`).
  The acceptance instances then close through their own normal engine
  verdict path.

### 4.3 Path B — no review: commit + push, park the release

The work still completes, still ships: the closing
pass commits the delivered work and pushes (the standing rule's
"otherwise commit and push" — the ordinary pass close, distinct from the
release's `-Push`). No version, no tag. The release parks as
**pending review**, recorded in two discoverable places, both store-native:

1. **The goal-root comment** (`release cadence: pending review`) — when a
   goal root exists, the state lives on a ticket, where comments and
   history work; an open acceptance instance (the sibling flow's
   "the live pass awaits the owner" state) surfaces the same pending pass
   on the Board.
2. **The pass digest line** (`release cadence: pending review`) — the
   audit trail of when the evaluation ran and what it saw.

`ROADMAP.md` carries none of this — runtime state lives in the store (its
own About rule says so); ROADMAP holds the rule, this flow holds the
state.

### 4.4 The unlock later — pending releases collapse into the next cut

Evidence may arrive long after the pass closed: the owner reviews at
their own cadence (the review-cadence dial is set per run at goal
intake). The machinery needs no memory of the wait: every close
re-runs the query, so the next evaluation after the
evidence lands flips the outcome to UNLOCKED; the owner gives the go in
chat; the cut executes. One honest wrinkle, stated rather than hidden:
the tag cuts the REPOSITORY state at cut time, and by then later passes
may have pushed on top of the reviewed work — so ONE review session
typically unlocks ONE cumulative cut over several pending passes, exactly
as the worked example did (the arc's work, one acceptance session,
v0.2.0). The per-ticket granularity lives in the evidence; the cut is
per occasion.

## 5. The non-blocking guarantee

Six guarantees, each against a real dependency direction:

- **N1 — read-only until the record.** The evaluation runs at the close,
  after the work's completion is already true; it writes only a digest
  line and a goal-root comment. No status, no claim, no spawn, no
  readiness edge is touched — the work cannot wait on its own evaluation.
- **N2 — pending is not blocked.** A PENDING REVIEW never sets
  `task_set_blocked` on any ticket, never blocks anything, never
  files NEEDS-HUMAN. The absence of a review is surfaced by the RECORDED
  PENDING OUTCOME (the goal-root comment and digest line), not by an
  escalation — that is the T-039 shape: acceptance hardens, never gates,
  and its absence gates even less.
- **N3 — the cut never runs mid-pass.** `release.ps1` mutates the shared
  tree (version bumps, commits, tag) and takes minutes at the gate; the
  ticker never invokes it, and the kickoff assistant executes it between
  passes, on the owner's go, where the script's clean-tree guard is
  actually satisfiable. Ticket flow and the cut are serialized by the
  tree, structurally.
- **N4 — failure isolation.** Every `Die` path in the script (dirty tree,
  tag exists, gate failed, push failed) lands as a comment on the
  goal root naming the failure; tickets and verdicts
  are untouched; the cut retries at the next evaluation or the next go.
- **N5 — convergence.** The query is idempotent and re-run every close;
  evidence discovered late flips the outcome (4.4) without any stored
  "already evaluated" marker to invalidate. A close lost mid-run (the
  tasks namespace can drop) is re-derived by the next close, like the
  sibling's division heal.
- **N6 — verdicts alone never cut.** Even perfectly green completed work
  with machine-authored evidence everywhere produces PENDING REVIEW. The
  release condition is verdicts AND owner evidence, in that combination,
  forever (T-039).

## 6. Composition — reading as one ruleset with the sibling flow

This flow and `docs/live-in-eclipse-recurrence-flow.md` are the two
halves of one close ruleset; the seams are explicit:

- **Division mints, close evaluates:** the sibling's pairing mint (R1)
  runs at tick step 4 (division); this flow's evaluation runs at tick
  step 6 (close), reading whatever the pass completed — including any
  pairing instance the sibling minted earlier and that has since been
  verdicted done.
- **No wave detection (T-202):** the sprint model is gone; there is no
  record naming convention to share. The cadence unit is the completed
  work, re-derived from status on every evaluation.
- **One instance owner:** the sibling owns the pairing instances'
  lifecycle (mint, execute, verdict); this flow never mints cadence
  tickets of its own — it only reads completed work and comments the
  cadence outcome on the goal root.
- **Mirrored guarantees:** the sibling's G5 ("release unaffected") is
  this flow's N1/N2 seen from the other side; both docs refuse to let
  acceptance or cadence states block anything.
- **What this doc adds that the sibling deliberately does not:** the
  evidence oracle (identity conventions, the query) and the cut path —
  the sibling's position section cites the rule; this doc mechanizes it.

## 7. Invariants (summary)

- **R1** — the evaluation runs ONLY at the close (ticker pass), or
  by the kickoff assistant as announced fallback; no other actor or
  moment ever evaluates cadence.
- **R2** — the owner set (`owner`, `owner-directive`, `owner-acceptance`)
  is the only review evidence; every other identity is machine,
  including `chat-session`.
- **R3** — evidence is comments and artifacts with authorship; status
  events, assignees, git authorship and silence are never evidence.
- **R4** — ambiguous evidence resolves to PENDING, never to a cut.
- **R5** — the ticker evaluates and records; only the owner's explicit
  go (in chat or person) authorizes the cut, and only the kickoff
  assistant (or the owner) executes `release.ps1`.
- **R6** — a pending review blocks nothing: no ticket, no
  escalation; it parks, visible, until the owner reviews.
- **R7** — every evaluation outcome is recorded twice (digest line +
  goal-root comment) and the release itself as a git artifact + HISTORY
  entry; nothing cadence-related lives in memory or in ROADMAP.

## 8. Trade-offs and open questions

- **The oracle trusts self-asserted `by` strings** (accepted): the store
  has no authentication of authorship; the mitigation is the conservative
  asymmetry (R4) plus the history-event audit trail every write leaves.
  A machine writing `owner-directive` fraudulently is a process violation
  visible in history, not a technical impossibility.
- **The content floor is semantic** (accepted): "states an outcome" is
  judged, not parsed; the paired test ticket (T-094) re-runs the query
  and judges the floor on live tickets, which keeps the judgment
  evidence-backed rather than mood-based.
- **Pending releases collapse into one cut** (accepted): per-pass cuts
  would tag unreviewed intermediate states; cumulative cuts match the only
  worked example and keep the tag authoritative for the whole pushed
  state.
- **The digest has no dedicated API surface in the tool catalog**
  (accepted): the digest line is a convention recorded in the tick's chat
  output (H7), and the goal-root comment is the machine-readable half —
  a dedicated digest API could take the line over without changing the
  query.
- **No nagging exists** (accepted, deliberate): unreviewed work waits
  indefinitely and silently; the autonomy spectrum makes the review
  cadence an owner dial, so the machinery reminding the owner would
  invert the operating model. The Board's open work and the recorded
  pending outcome are the whole reminder surface.
