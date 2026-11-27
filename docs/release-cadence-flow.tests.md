# Release cadence — integration criteria & verdict (T-094)

## About this document

- **Kind:** `doc` / test-system verification artifact (V stage: test-system;
  ticket T-094, epic U-113, basis T-041). This is the PAIRED VERIFICATION of
  T-093's system artifact `docs/release-cadence-flow.md`: it derives
  integration criteria 1:1 from T-093's acceptance criteria, checks each
  against an observable oracle (governing docs, the release script text,
  git refs, live store state), and records the verdict that decides
  T-093's done/not-done.
- **Read by:** the engine review pass (tick step 3 — the verdict authority
  for T-093 and T-094), the stage ticker pass (which must re-record the
  artifact + verdict on T-094 if this run's store writes were lost), the
  maintainer.
- **Related:** the artifact under verification
  `docs/release-cadence-flow.md` (T-093); governing definitions
  `ROADMAP.md` (the T-041 standing rule, "A human review unlocks a
  release; otherwise commit and push"), `AGENTS.md` (autonomy spectrum,
  intake discipline), `docs/live-in-eclipse-recurrence-flow.md` (the
  sibling conventions doc — compared, not edited),
  `eclipse/releng/release.ps1` (read, never run); tickets T-041 (the rule),
  T-039 (non-blocking acceptance), T-040 (live pre-flow instance),
  U-112 / U-111 (the worked example: the 2026-11-14 owner review that cut
  v0.2.0). Sibling verification artifact:
  `docs/live-in-eclipse-recurrence-flow.tests.md` (T-086 — same shape).
- **Verdict recording:** this file carries the full evidence; the ticket
  comment (or the ticker's re-recording of it) is the hand-off contract.
- **Currency (T-202, owner model 2026-11-20):** the sprint model is gone -
  no sprint records, no waves, no wave instances; the cadence unit is the
  pass's **completed work** (`done` / `not-implemented` tickets), and the
  sibling's wave instance (R2) retired with the model. The sprint-record,
  wave and R2-mint mechanics observed below are the 2026-11-15 run-date
  record - the verdicts stand as recorded on their dates (re-verified
  2026-11-26).

## System under test

The cadence FLOW specified by T-093: which actor evaluates the
human-review trigger at wave completion (the ticker pass at tick step 6,
after the sibling flow's R2 mint; the kickoff assistant as fallback), what
store-verifiable evidence counts as "a human review" (the identity-table
oracle over comment/artifact `by` fields), both outcome paths (cut via
`release.ps1` on the owner's explicit go vs commit + push with the release
parked on the open acceptance instance), and the non-blocking guarantees
N1–N6. Integration level: the flow's contracts are checked against the
REAL machinery it must compose with — SKILL.md's tick procedure, the
sibling flow's mint, the release script's own guards, git refs, and the
live task store — not against a model of them.

## Criterion derivation (1:1 from T-093's ACs)

| T-093 AC | This ticket's criterion |
|---|---|
| AC1 "system artifact exists: the cadence flow (actor, evidence oracle, both outcome paths, non-blocking guarantee) citing the ROADMAP standing rule" | ITS-001 (actor + evaluation point), ITS-002 (rule citation), ITS-004 (both paths end in the rule's stated results), ITS-005 (N1–N6), ITS-006 (coherence with the sibling doc as ONE ruleset) |
| AC2 "the human-review evidence oracle is store/git-verifiable, not memory" | ITS-003 (the query RUN live on the ticker-v record and its members, identity table applied, result recorded) |
| AC3 "artifact recorded on this ticket before in-review" | checked directly on T-093's history (see ITS-007) |

## Test cases and evidence

| ID | Rule / contract | Oracle | Result |
|---|---|---|---|
| ITS-001 | The evaluation point (tick step 6, after the sibling's R2 mint) is real in SKILL.md's procedure; the ordering composes — one sweep, two extensions | SKILL.md step 6 text; sibling §5.1 STEP 6 EXTENSION; cadence §4.1 wording; order trace | **PASS** (note TN-1) |
| ITS-002 | The artifact cites the ROADMAP standing rule faithfully; runtime state stays out of ROADMAP | ROADMAP.md's T-041 standing-rule bullet (section-named now; then lines 177–190 at this run) vs cadence §1/§4.3; AGENTS.md §autonomy-spectrum, §intake | **PASS** |
| ITS-003 | The human-review oracle is decidable, conservative, store/git-verifiable — and was RUN on a real record | `task_list(sprint=ticker-v)` + `task_get` × 51 + `git tag`/`git log`; identity table + content floor | **PASS** — live result: PENDING REVIEW (release parked, not cut) |
| ITS-004 | Both outcome paths end in the standing rule's stated results and name real machinery | release.ps1 guard/sequence text vs cadence §4.2; live pom version + git tags; sibling mint + T-040 for the parked surface | **PASS** (notes TN-4, TN-5) |
| ITS-005 | Non-blocking guarantees N1–N6 hold against the machinery | Extension wording vs store verbs; script Die paths; live verdict-vs-evidence demonstration | **PASS** (note TN-3) |
| ITS-006 | The two flow docs read as ONE ruleset (detection shared verbatim, seams listed, no contradiction) | Sibling R2/§5.1/G5/§2 vs cadence §1/§4.1/§6/§3.1 | **PASS** (note TN-2) |
| ITS-007 | T-093 AC3: artifact before in-review | T-093 history event ordering; artifact ref resolves | **PASS** |

### ITS-001 — the evaluation point and the ordering (T-093 AC1, actor)

**The sweep is real.** SKILL.md's tick procedure, step 6 (as read on the
2026-11-15 run, then lines 61-63):
"CLOSE the pass: reopen the `ticker-v` sprint record with the digest
(U-108 reopen semantics keep the record rolling) and close it." The close
sweep the cadence doc claims as its evaluation point EXISTS, and SKILL.md
binds the pass to it ("Run this loop exactly. Do not improvise steps or
order"). (Rekeyed since: SKILL.md step 6 today runs the RELEASE-CADENCE
EVALUATION on pass close; the sprint-record reopen went with the sprint
model, T-202 - re-verified 2026-11-26.)

**The ordering composes — one sweep, two extensions.** Trace of the
stated order at tick step 6:

1. The digest is written (sibling §5.1 STEP 6 EXTENSION preamble and
   cadence §4.1 preamble agree: "after the digest is written").
2. The sibling's R2 mint runs ("after the digest … before the sprint
   record closes"): for each completed wave it creates the
   `Acceptance (live in Eclipse): wave <record id> … (wave <record id>)`
   instance, `in-progress` + `owner-acceptance`.
3. The cadence evaluation runs ("after the wave-completion recurrence
   mint, before the sprint record closes" — the cadence wording
   EXPLICITLY orders itself after the mint, "so the freshly minted
   instance is visible to the query").
4. The record closes.

So YES: the cadence evaluation sees the acceptance instance minted in the
same sweep. For a wave completing in that pass, the fresh instance
carries no comments and no artifacts yet, so the query's instance leg
returns no owner evidence — PENDING REVIEW, which is the correct
conservative outcome for a just-completed wave; §4.4's re-run-every-sweep
convergence flips it later if evidence arrives. No circularity, no
read-before-write.

**Note TN-1 (for the reviewer):** SKILL.md itself carries the sibling's
governing sentence in step 4 (the T-039 recurrence rule) but at this run
had NO anchor sentence for the cadence evaluation anywhere - the ticker's
discovery path for this extension is the cadence doc's own "Read by: the
stage ticker pass" pointer and its §4.1 wording ("The wording the next
ticker pass applies"). That is the same doc-supplies-the-wording pattern
the sibling uses for its own STEP 6 EXTENSION text (only its step-4
sentence lives in SKILL.md), so it is consistent, not contradictory — but
the cadence extension was then one doc-read away from the literal
SKILL.md loop. A one-sentence anchor in SKILL.md step 6, when the
extensions are next revisited, would close it. Out of this ticket's lane.
**Resolved since:** SKILL.md step 6 now carries the anchor - "The close
runs the RELEASE-CADENCE EVALUATION (owner requirement 2026-11-15, T-041;
rekeyed from wave to pass, once per pass)" (re-verified 2026-11-26).

### ITS-002 — the ROADMAP standing rule citation (T-093 AC1)

ROADMAP.md's T-041 standing-rule paragraph (then lines 177-190 at this
run; section-named now so a ROADMAP edit cannot strand it - re-verified
2026-11-26): completed wave →
commit + push always; "The version is cut only when the wave's work
carries a human review: cut it via `eclipse/releng/release.ps1` (tag per
D-002, bump to the next `-SNAPSHOT`)"; "the release stays pending until a
review unlocks it"; "The wave, not the calendar, is the release cadence —
and the human review, not the wave alone, is the release trigger"; "The
recurring live-in-Eclipse acceptance hardens the work without gating it
(T-039): engine verdicts are necessary for a release, never sufficient";
"Ticker micro-record(s) … are not waves in this sense."

The cadence doc's §1 restates every element 1:1 and adds nothing that
changes the rule; §4.3 explicitly keeps the runtime state OUT of ROADMAP
("ROADMAP holds the rule, this flow holds the state" — matching ROADMAP's
own History discipline bullet, section-named now). AGENTS.md carries the autonomy
spectrum (review cadence set at goal intake - "The autonomy spectrum"
paragraph) and the intake
discipline (owner input = requirement or bug - "Intake discipline"
paragraph) exactly as the
cadence doc cites them (the fallback evaluator's authority; the
review-cadence dial in §4.4). PASS.

### ITS-003 — the live query run (T-093 AC2) — THE ORACLE RUN

Ran 2026-11-15, this session, on the sprint record `ticker-v` and its
members, per the paired-verification brief. Every step used store verbs
and git only.

**Step 1 — completion precondition.** `task_list(hephaestus,
sprint=ticker-v)`: **51 members, 7 done / 44 open** → "every member done"
is FALSE; the record is not a completed wave. Independently decisive: the
record's id starts with `ticker-`, so the micro-record carve-out (shared
by sibling R2 and cadence §1) excludes it from wave detection entirely.
The query returns a definitive NOT-A-COMPLETED-WAVE → no cadence
evaluation is due → no release. Decidable. (Note TN-6: the record is
itself a micro-record, so the sweep would formally never evaluate it; the
run below exercises the oracle MECHANICS over its members as the brief
instructs.)

**Steps 2–3 — the scan (mechanics exercise over all 51 members).**
Enumerated every comment, artifact and history event:

- 37 comments across 19 distinct `by` values; 29 artifacts across 19
  distinct `by` values (including 3 with NO author at all); 199 history
  events, of which **165 carry no actor**.
- Owner-set hits (`owner` / `owner-directive` / `owner-acceptance`):
  **exactly 4**, all `owner-directive` COMMENTS: T-036 ×2 ("so that I am
  not in Build" role rule; intake-agent creation record) and T-039 ×2
  (recurrence rule; non-blocking refinement).
- Everything else is machine class under the identity table:
  `ticker-v`, `ticker-v:review` (×7), `ticker-v:T-0xx` workers,
  `executor:T-041`, `architecture-worker`, `system-worker:T-097`,
  `test-system-worker:A-002`, `pm-resume`, `main-session`, `(none)`.

**Step 4 — the content floor.** All four owner-set comments record
owner DIRECTIVES (requirements intake: a role rule, an agent creation, a
process rule, a refinement). NONE states an outcome (accept / approve /
review verdict). The floor FAILS on all four → not reviews. This is the
conservative asymmetry working exactly as specified: owner-set authorship
alone does not unlock; a directive is "a direction", which §3.4 itself
names as not-acceptance.

**Result: the query returns NO HUMAN REVIEW → outcome PENDING REVIEW →
release parked, NOT cut.** Matches the expected result. Decidable (a
definitive classification at every step) and conservative (the only
human-class evidence fails the floor; silence and machine evidence never
unlock).

**Corroboration of §3.1's exclusions, live:** 165 of 199 history events
have no actor — keying evidence on status-transition history would be
UNDECIDABLE, validating the doc's exclusion (the A-002 finding) and the
choice to key on comment/artifact authorship instead.

**Positive control (the oracle is not vacuously negative).** The
worked-example tickets U-111/U-112 carry genuine owner-acceptance
evidence: U-111 comment `owner-acceptance` 2026-11-14T20:56:39Z ("Arc
closed by owner acceptance in Build mode …") + git artifact
`owner-acceptance: e2c2b74`; U-112 comment `owner-acceptance`
("GOAL met: … v0.2.0 tagged (e2c2b74) and pushed …") + git artifact
`owner-acceptance: 29d9bc9`. Run against THAT wave, the query returns
owner-set evidence that STATES an outcome → UNLOCKED. Both polarities of
the oracle are decidable on live data. The git anchors were verified
independently: tags `v0.1.10`, `v0.1.11`, `v0.2.0` exist; `e2c2b74`
("Release v0.2.0") and `29d9bc9` ("Prepare 0.2.1-SNAPSHOT") are real,
sequential commits.

**Verdict: PASS.** The oracle is store/git-verifiable end to end (no
memory, no chat text), decidable, and conservative as specified.

### ITS-004 — both outcome paths (T-093 AC1)

**Release path — the script's own guards.** Cadence §4.2 claims branch
check, dirty-tree check, tag-exists check, X.Y.Z format check, freshness
check, and "the flow adds no validation of its own". Verified against
`eclipse/releng/release.ps1` text: repo check (l. 32–33), build-wrapper
check (35–36), X.Y.Z format (38–40 — rejects any `-SNAPSHOT` suffix),
tag-exists (41), branch (43–46), dirty-tree (47–48), fetch freshness
(50–56). All five claimed guards exist; the doc indeed adds none.

**The D-002 sequence.** Script: set-version across the reactor
(117–118) → quality gate `clean verify -Pquality`, on failure REVERTS
(`git checkout -- .`) and aborts BEFORE any commit (124–131) → commit
"Release vX.Y.Z" + annotated tag (134–139) → set-version to the next
`-SNAPSHOT` + "Prepare" commit (141–146) → `-Push` optional, branch +
tag (148–155), with the policy header "push only on the owner's explicit
go" (14–15). Matches cadence §4.2's sequence claim element for element,
including the executor rule (kickoff assistant on the owner's explicit
go; the ticker never runs it).

**Version = current `-SNAPSHOT` stripped.** The script's format guard
rejects suffixed versions, so the caller MUST pass the stripped number —
the flow's "never invented in chat" is backed by the script refusing
anything else. Live at the 2026-11-15 run: `eclipse/pom.xml` reactor version was
`0.2.1-SNAPSHOT` (line 9) → stripped = 0.2.1; existing tags were
v0.1.10 / v0.1.11 / v0.2.0, so v0.2.1 did not exist and the next legal
cut was `v0.2.1` → `0.2.2-SNAPSHOT` (script lines 58-59 increment the
patch). Consistent end to end. (Today, re-verified 2026-11-26: the reactor
reads `0.4.7-SNAPSHOT` at line 9, tags run v0.1.10…v0.4.6, and the same
script lines still increment the patch - the rule, not the numbers, is
what the criterion pins.)

**Parked path — the Board-visible surface.** The open acceptance instance
mechanism exists: the sibling flow mints it (§5.1 STEP 6 EXTENSION step 3:
`in-progress` + `owner-acceptance`, never planned into a record; §5.2
calls it "a visible 'the live pass awaits the owner' state on the Board"),
and the cadence §4.3 correctly reads that state as the pending-release
marker, findable by scanning open `(wave …)` titles. Live at the
2026-11-15 run: T-040 existed
as the pre-flow instance (title token `(U-009 AC4)`, pairing shape;
status `sprint-backlog`, unassigned — the sibling's §7 documented exactly
this state and its one-time transition). (Today T-040 is `blocked` under
the owner's eclipse-build deprecation note with `resume_to: ready`, and
`sprint-backlog` went with the sprint model, T-202 — re-verified
2026-11-26.) No `(wave …)`-token instance has
been minted yet anywhere (store scan: T-040, B-007, U-026…U-029 are the
only `test-requirements` tickets; none carries a wave token) — consistent
with the mint being next-pass wording and no substantive wave having
completed under the scheme yet. PASS (notes TN-4, TN-5).

### ITS-005 — the non-blocking guarantees N1–N6 (T-093 AC1)

- **N1 — read-only until the record: PASS.** The evaluation writes only
  a digest line and an instance comment (`task_add_comment` — a
  non-state write; readiness, claims and statuses are untouched), and the
  extension's step 4 forbids running the script, blocking, and spawning.
  A wave cannot wait on its own evaluation: the sweep fires at step 6,
  after completion is already true.
- **N2 — pending is not blocked: PASS.** No `task_set_blocked` and no
  NEEDS-HUMAN for an absent review anywhere in the flow ("Do NOT escalate
  a PENDING REVIEW as NEEDS-HUMAN"); SKILL.md step 2's blocked-escalation
  is for FAILED VALIDATION, not absent reviews. The open instance is the
  surface — the T-039 shape.
- **N3 — the cut never runs mid-pass: PASS.** The actor table forbids the
  ticker from executing `release.ps1` and the extension repeats it. The
  script's own guards make mid-pass execution structurally impossible
  anyway: the dirty-tree check (47–48) cannot pass while workers write
  the tree, and the freshness check (50–56) refuses a behind branch.
  Ticket flow and the cut are serialized by the tree, as claimed.
- **N4 — failure isolation: PASS** (note TN-3). Every `Die` path in the
  script was enumerated: not-a-repo (33), wrapper (36), format (39),
  tag-exists (41), branch (45), dirty (48), behind (53), set-version
  failure (68), gate failure (130), push failure (151). The
  abort-before-commit claim is verified (127–131: gate failed → revert →
  Die before any commit); push failure honestly leaves local-only state
  (151). The "Die lands as a comment on the acceptance instance" half is
  EXECUTOR discipline — the script has no store access — so that
  guarantee is flow-backed, not script-backed; the script's own
  contribution (aborts, reverts, touches no tickets/records/verdicts) is
  verified.
- **N5 — convergence: PASS.** The extension stores no "already evaluated"
  marker; the query is read-only and idempotent; re-running it every
  sweep re-derives the outcome, so a sweep lost mid-run (the tasks
  namespace CAN drop — it dropped during THIS very ticket's run) is
  recovered by the next sweep, mirroring the sibling's mint convergence.
- **N6 — verdicts alone never cut: PASS, demonstrated live.** Engine
  verdicts are machine class (`review`, `ticker-v:review` — this record
  holds seven `ticker-v:review` comments and green verdicts on its done
  tickets, e.g. T-041's engine PASS), and the query still returns PENDING
  — the owner-set requirement + content floor + the script's
  owner-explicit-go push policy (14–15) make a green wave unable to cut,
  on live data.

### ITS-006 — coherence with the sibling doc as ONE ruleset

- **Wave detection shared VERBATIM:** sibling R2 ("a sprint record whose
  id does NOT start with `ticker-` holds at least one ticket and zero
  open tickets", paused keeps the wave open) — the wording as read on the
  2026-11-15 run (R2 is RETIRED since T-202: [REC §4] now strikes the row
  — "no sprint records, no waves" — re-verified 2026-11-26) = cadence §1's carve-out;
  both derived the `ticker-*` exclusion from ROADMAP's own sentence. Same
  detection, same micro-record exclusion, no drift.
- **One sweep, two extensions, fixed order:** verified in ITS-001 — both
  extensions attach at the same named points (after digest, before
  record close) and the cadence wording orders itself after the mint.
  Neither doc reorders the tick loop.
- **One instance, two readers:** the sibling owns the instance lifecycle
  (mint, execute, verdict); the cadence only READS it and comments the
  cadence outcome onto it. The cadence extension creates NO tickets of
  its own (verified — its step list contains no create), and the
  cadence-outcome comment is machine-authored (`ticker-v`), so it can
  never pollute the evidence oracle it feeds.
- **Identity conventions compose:** the sibling uses `owner-acceptance`
  as the instance ASSIGNEE (routing marker); the cadence classifies
  `owner-acceptance` AUTHORSHIP as HUMAN evidence while §3.1 explicitly
  rules the assignee field out as evidence. Assignee-vs-authorship is
  kept distinct across both docs — no contradiction.
- **Mirrored guarantees:** sibling G5 ("release unaffected") is cadence
  N1/N2 seen from the other side, as §6 claims; sibling G4 (wave
  completion unaffected) matches N1. Fallbacks mirror too (kickoff
  assistant, announced in chat, H7). The sibling's R3 (no mint at release
  cut) and the cadence's no-mint stance agree.
- **One wording tension (note TN-2):** sibling G5 says "No release step
  reads acceptance instances", while the cadence evaluation reads
  instances for evidence. Resolution by the seam's own definitions: the
  evaluation is a close-sweep cadence step, NOT a release step; the CUT
  itself (the script run, the release decision's execution) reads no
  instances; open instances carry over exactly as G5's own text says.
  Behavioral contradiction: none found. A one-line tightening in either
  doc ("no release step reads acceptance instances; the cadence
  evaluation at the close sweep is not a release step") would remove the
  ambiguity for future readers.

**Verdict: PASS** — the two docs compose as one wave-close ruleset with
the seams explicitly listed and no behavioral contradiction.

### ITS-007 — T-093 AC3 (artifact before in-review)

T-093 history: `artifact:file:docs/release-cadence-flow.md` at
2026-11-15T09:22:44.026Z, then `updated:status` (→ in-review) at
09:22:47.067Z. Ordering holds; the artifact ref resolves (the file was
read in full this session) and carries the About section, the actor
table, the evidence oracle, both paths, N1–N6, the composition section
and invariants — AC1's content requirements met.

## Notes (for the reviewer to weigh)

- **TN-1 — no SKILL.md anchor for the cadence extension.** SKILL.md
  carries the sibling's governing sentence (step 4) but nothing for the
  cadence evaluation; the ticker's discovery path is the cadence doc
  itself. Same doc-supplies-the-wording pattern as the sibling's own
  STEP 6 extension; consistent, not contradictory. Suggested follow-up
  (out of lane): one anchor sentence in SKILL.md step 6 at the next
  revision. **Resolved since (re-verified 2026-11-26):** SKILL.md step 6
  now opens "The close runs the RELEASE-CADENCE EVALUATION (owner
  requirement 2026-11-15, T-041; rekeyed from wave to pass, once per
  pass)" - the anchor landed; the note stands as recorded.
- **TN-2 — G5 wording tension** ("No release step reads acceptance
  instances" vs the close-sweep evaluation reading them). Resolvable by
  the seam definitions; no behavioral contradiction; one-line tightening
  suggested.
- **TN-3 — N4's instance-comment half is executor discipline.** The
  script cannot write to the store, so "every Die path lands as an
  instance comment" holds only if the executor (kickoff assistant or
  owner) records it; the script's own abort/revert/no-store-touch
  semantics are verified. The doc should be read as specifying executor
  behavior there, which it does (§4.2 "Recording the release", N4's
  framing).
- **TN-4 — T-040's transition was pending at this run** (then
  `sprint-backlog`,
  unassigned, planned into the ticker-v record — pre-flow shape; today
  T-040 is `blocked` under the owner's eclipse-build deprecation note,
  `resume_to: ready`, and `sprint-backlog` is retired — T-202; re-verified
  2026-11-26). The
  sibling's §7 prescribes the one-time `task_update`; out of T-085's,
  T-086's and this ticket's lane. The cadence query handles ANY open
  instance correctly (non-done → no owner evidence → PENDING), so the
  cadence flow is unaffected either way.
- **TN-5 — the mint+evaluation composition is verified against the
  stated wording, not against a live sweep run:** no substantive wave has
  completed under the scheme yet, so no `(wave …)` instance exists. The
  ORACLE itself was run live (ITS-003), both polarities (PENDING here,
  UNLOCKED on U-111/U-112). First live exercise of the full sweep remains
  future evidence.
- **TN-6 — the ticker-v record is itself a micro-record** (id starts with
  `ticker-`), so the cadence evaluation would formally never fire for it;
  ITS-003's run exercised the query mechanics over its members per the
  paired-verification brief, validating decidability and conservatism on
  real data. No cadence outcome was (or should be) recorded for it.
- **TN-7 — live identity spellings.** The record's live `by` values
  include `main-session` (and U-112 carries `ci-evidence`) where the
  cadence doc's example table cites `chat-session`; the table is
  explicitly "Examples observed" and both spellings fall under the
  machine rule ("everything else incl. chat-session" — the main chat
  session IS the kickoff assistant). No contradiction; worth adding the
  observed spellings at the next doc revision so the table stays
  exhaustive in practice.

## Overall verdict

**APPROVE-WITH-NOTES.** All seven integration criteria PASS: the
evaluation point and its ordering compose with the sibling's mint in the
real tick-6 sweep (ITS-001); the artifact cites the ROADMAP standing rule
faithfully (ITS-002); the human-review oracle was RUN live on the
ticker-v record — 51 members enumerated, identity table applied, the only
owner-set evidence (4 `owner-directive` comments) fails the content
floor, result **PENDING REVIEW → release parked, not cut**, with the
positive control (U-111/U-112 `owner-acceptance` evidence → UNLOCKED)
proving the oracle's other polarity (ITS-003); both outcome paths end in
the standing rule's stated results against the real script guards, the
real D-002 sequence and the real version rule as observed at this run
(0.2.1-SNAPSHOT → v0.2.1; the reactor reads 0.4.7-SNAPSHOT today)
(ITS-004); N1–N6 hold against the machinery, with N6 demonstrated live
(green verdicts, still pending) (ITS-005); the two flow docs read as ONE
ruleset with shared detection, explicit seams and no behavioral
contradiction (ITS-006); and T-093's artifact landed before in-review
(ITS-007). The notes TN-1–TN-7 are robustness, wording and follow-up
items; none contradicts T-093's acceptance criteria, the T-041 standing
rule, or T-039's non-blocking acceptance. This verdict decides T-093's
done/not-done in the reviewer's hands; it does not gate other waves.
