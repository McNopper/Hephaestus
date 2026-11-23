# Live-in-Eclipse acceptance recurrence — integration criteria & verdict (T-086)

## About this document

- **Kind:** `doc` / test-system verification artifact (V stage: test-system;
  ticket T-086, epic U-113, basis T-039). This is the PAIRED VERIFICATION of
  T-085's system artifact `docs/live-in-eclipse-recurrence-flow.md`: it
  derives integration criteria 1:1 from T-085's acceptance criteria, checks
  each against an observable oracle (governing docs, store contracts, live
  store state), and records the verdict that decides T-085's done/not-done.
- **Read by:** the engine review pass (tick step 3 — the verdict authority
  for T-085 and T-086), the stage ticker pass, the maintainer.
- **Related:** the artifact under verification
  `docs/live-in-eclipse-recurrence-flow.md` (T-085); governing definitions
  `.opencode/skills/stage-pipeline/SKILL.md` (tick step 4 division map),
  `AGENTS.md` (paired-verification paragraph, autonomy spectrum),
  `ROADMAP.md` (T-041 standing rule); tickets T-002 (origin instance),
  T-040 (pre-flow instance), T-039 (the rule).
- **Verdict recorded:** also on ticket T-086 as a comment (the hand-off
  contract); this file carries the full evidence.
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps, sprint records and backlog statuses quoted below are historical;
  their verdicts stand as recorded on their dates.

## System under test

The recurrence FLOW specified by T-085: which actor mints the recurring
live-in-Eclipse acceptance ticket (stage tickers at tick step 4 pairing /
step 6 wave sweep; kickoff assistant as fallback), from what template
(§3), on what triggers (R1/R2/R3), with which non-blocking guarantees
(G1..G6). Integration level: the flow's contracts are checked against the
REAL dispatch machinery it must compose with — `task_claim`,
`task_readiness`, `task_plan_sprint`/`task_close_sprint`, the tick loop's
step order, the store's blocked/done invariants — not against a model of
them.

## Criterion derivation (1:1 from T-085's ACs)

| T-085 AC | This ticket's criterion |
|---|---|
| AC1 "system artifact exists: the recurrence flow (actor, trigger, template, non-blocking guarantee) citing SKILL.md + AGENTS.md" | ITS-001..ITS-005 verify the artifact's content contracts against SKILL.md + AGENTS.md + ROADMAP.md + live store state (existence alone is not verification) |
| AC2 "the flow names the exact division-step wording that produces the ticket so the next ticker pass is deterministic" | ITS-001 (verbatim wording + exactly-one semantics + meaning-intact trace) |
| AC3 "artifact recorded on this ticket before in-review" | checked directly on T-085's history (see ITS-006) |

Plus the ticket's own additional integration concern: coherence as ONE
system (actors, dedup keys, paused-keeps-open, digest note) → ITS-005.

## Test cases and evidence

| ID | Rule / contract | Oracle | Result |
|---|---|---|---|
| ITS-001 | Division-step wording: verbatim quote, exactly-one mint, map meaning intact | Literal text comparison vs SKILL.md step 4; extension anchors vs tick order; dedup rule §3.4 | **PASS** |
| ITS-002 | No readiness/verdict edge references a recurring ticket as a dependency (G1/G2) | `task_claim` contract; live `task_readiness` rows; sprint-membership semantics; store link graph | **PASS** |
| ITS-003 | Template matches the T-002 pattern (and the T-040 pairing shape) | §3 templates vs T-002's executed pass + T-040's live shape | **PASS** |
| ITS-004 | Release cut composes with ROADMAP's cadence rule; backfill bounded | ROADMAP T-041 text vs doc §4 position + backfill conditions | **PASS** |
| ITS-005 | Coherence as ONE system: actors, dedup, paused-keeps-open, digest | Actor table vs H7/step 3; §9 failure behavior; U-038; U-061/U-108 | **PASS** (with notes) |
| ITS-006 | T-085 AC3: artifact before in-review | T-085 history event ordering | **PASS** |

### ITS-001 — the division-step wording (T-085 AC2)

**Verbatim check.** SKILL.md tick step 4 (lines 43–52) carries the
live-in-Eclipse rule; doc §5.1 quotes it. Compared word-for-word: the
quoted body is IDENTICAL to SKILL.md's sentences ("a definition ticket
whose acceptance criteria include live-in-Eclipse checks (console checks,
UI passes, panel round trips) divides with a paired test-requirements child
carrying that live check … The child is the recurrence pattern,
NON-BLOCKING: every successful wave/release produces a fresh
test-requirements acceptance ticket (the T-002 pattern systematized) — it
hardens acceptance and never gates; no verdict, wave or release waits on
it."). The quote drops only the "Live-in-Eclipse rule (owner requirement
2026-11-15, T-039):" lead-in, which the doc cites separately. AGENTS.md's
paired-verification paragraph carries the same classification (ACCEPTANCE =
`test-requirements` leg, "a fresh, NON-BLOCKING test-requirements
acceptance ticket per successful wave/release") — doc, SKILL.md and
AGENTS.md agree.

**Exactly-one trace.** Sentence-by-sentence against the extensions (§5.1):

1. "divides with a paired test-requirements child carrying that live check"
   → STEP 4 EXTENSION steps 1–4: scan D's ACs (step 1), token from the
   first matching AC (step 2), dedup (step 3), create per §3 with
   `basis=D`, `epic=D's epic` (step 4); the child carries the live check
   via §3.2's description slot and §3.3's pairing ACs. Meaning intact.
2. "belongs to the last V stage" → template field `stage`:
   `test-requirements` (immutable). Intact.
3. "not in ad-hoc blocked/in-review states or manual follow-up notes" → the
   extension creates a REAL store ticket. Intact.
4. "The child is the recurrence pattern" / "every successful wave/release
   produces a fresh … ticket" → STEP 6 EXTENSION is explicitly titled
   "WAVE-COMPLETION RECURRENCE (the recurrence sentence of step 4)".
   R2's trigger = a substantive record (`ticker-*` excluded, matching
   ROADMAP's own micro-record carve-out) holding ≥1 ticket and ZERO open
   tickets — i.e. a *successful* wave; dedup on `(wave <record id>)`
   (open OR done) makes it exactly once, ever; the sweep runs once per pass
   at the close step, so re-runs are harmless. Exactly one per successful
   wave holds.
5. "it hardens acceptance and never gates; no verdict, wave or release
   waits on it" → the minted state matches no dispatch branch (ITS-002);
   the sweep runs "after the digest is written, before the record closes" —
   completion is already true when the mint fires (G4: records evidence,
   does not define it). Intact.

**Anchor check.** The extensions attach at named points of the EXISTING
loop ("immediately after D's ordinary division children are created";
"after the digest … before the sprint record closes") and reorder nothing.
The §5.1 justification for placing the wave sweep at step 6 (a stage-local
check can miss a wave completed by a LATER stage's verdict in the same
pass) is sound against SKILL.md's structure (steps 1–4 run per stage in
canonical order). Determinism for the next ticker pass: every step of both
extensions is a concrete store verb (`task_create` with
stage/epic/basis → `task_update` status+assignee; fallback mint
sprint-backlog + owner-acceptance if the engine refuses the direct mint).

**Determinism caveat (note N2, non-blocking):** step 4 processes done
tickets WITHOUT downstream children, so the pairing extension fires only on
a post-flow division moment; a legacy done definition that already carries
children never re-enters step 4 and gets no pairing instance. The
machinery's answer is the field discipline "division SATISFIED with
rationale" — applied live to T-002 by ticker-v (comment 2026-11-15
08:55:17: "Its recurrence is SYSTEMATIZED, not duplicated"), with the PO
override as the escape hatch. Consistent, not contradictory.

### ITS-002 — no readiness/verdict edge depends on the instance (G1, G2)

**Claim branch (G1) — `task_claim` contract.** Tool contract (tasks MCP
catalog, verbatim): "Atomically claim the next matching task (->
in-progress) … **Only sprint-backlog tickets are claimable**." SKILL.md
step 5 agrees ("`task_claim` only takes `sprint-backlog` tickets"). The
minted state is `in-progress` + `owner-acceptance`, no sprint — it matches
no claim branch and no kickoff branch.

**Readiness (G1) — live rows, 2026-11-15.** `task_readiness(hephaestus)`:
every in-progress ticket reports RUNNING, never READY — T-086 ("stage
'test-system' work is in-progress"), T-056, T-051, T-057; in-review tickets
(T-055, T-085, T-084, T-038, T-042, T-054, T-083) also report RUNNING.
Step 5 kicks off READY tickets only → a minted instance is unreachable by
dispatch. (The doc's §6 exemplars T-055/T-085 were in-progress at writing
and have since moved to in-review — the kind "RUNNING, never READY" is
unchanged; note N3.)

**Sprint membership (G1).** Template field `sprint`: none ("NEVER planned
into any sprint record"); both extensions repeat "Do NOT plan it into a
sprint record". `task_close_sprint` ejects only MEMBERS ("unfinished tasks
return to product-backlog") → a non-member can never be ejected back into
a claimable state.

**Derivation edges (G2) — live link graph.** `task_links`: T-002 →
`up:[]`, `down:[]`, `back:[]`; T-040 → `up:[]`, `down:[]`, `back:[]`;
T-085 → `down:[]` (last stage; empty division map). Store-wide grep of
`.opencode/tasks/hephaestus/` for `basis: T-002` / `basis: T-040`: ZERO
matches — no ticket in the project derives from an acceptance instance, so
no readiness edge can traverse one. The instance's own basis (pairing: D,
done) points OUT of it; the store refuses blocking a done ticket
(documented invariant, AGENTS.md write discipline), so the dependency
direction can never back-gate (G6).

**Verdict edges (G3).** Step 3 verdicts only in-review tickets of the
scanned stage; an instance enters in-review only when the OWNER flips it
(§5.3), and the reviewer never consults it (established on U-009, quoted
in doc §6). No third ticket's verdict path reads an instance.

### ITS-003 — the template matches the T-002 pattern

- Wave instance ACs (§3.3): live console checks / UI passes (Board /
  Server / Chat / Providers, context menus with valid + empty selection) /
  panel round trips (one chat + one store round trip, `task_doctor` or
  `task_board`) / evidence artifact. These are exactly the check families
  of T-002's EXECUTED release smoke test (owner-acceptance comment,
  2026-11-14: "the owner conversed with the agent through the panel (round
  trip proven), the store answered from the panel (task_doctor +
  task_board live), all seven board columns rendered, and the 19-tool
  surface was deployed"). The template's evidence AC matches T-002's
  practice (artifact `git:29d9bc9` recorded by `owner-acceptance` before
  done); findings-as-bug-tickets matches T-002 AC4 and its actual history
  (finding → T-003). T-002's one-time bring-up ACs (store-root preference,
  first Board launch) are correctly NOT in the recurring template — they
  are setup, not the live-pass pattern T-039 names.
- Pairing instance (§3.3): the T-040 shape — live check operationalized +
  evidence AC. T-040's live title carries the pairing token convention
  "(U-009 AC4)" — §3.4's `(<D id> AC<n>)` matches the established
  instance.

### ITS-004 — release composition and the bounded backfill

ROADMAP.md T-041 standing rule (lines 177–190): a completed wave commits +
pushes; "the version is cut only when the wave's work carries a human
review"; "The wave, not the calendar, is the release cadence — and the
human review, not the wave alone, is the release trigger. The recurring
live-in-Eclipse acceptance hardens the work without gating it (T-039):
engine verdicts are necessary for a release, never sufficient. Ticker
micro-records (the per-stage rolling records) are not waves in this
sense."

The doc's R3 position ("no mint on release cut — once per wave, not once
per release") composes with every load-bearing claim:

1. The release carries exactly its waves' results; each wave minted its
   instance at completion → a cut-time mint would demand a second live
   pass over unchanged work (no new evidence; thin-harness rule D-005).
2. The gating temptation is refused exactly where strongest: the doc cites
   ROADMAP's sentence VERBATIM ("engine verdicts are necessary for a
   release, never sufficient") and routes the human review INTO the live
   pass — the owner's review session executes outstanding instances at the
   keyboard (the AGENTS.md autonomy-spectrum worked example: the
   unattended ticker-v wave + the owner's review cut v0.2.0; T-002's pass
   WAS that review's live evidence).
3. Open instances carry over and thicken evidence against the released
   build (nothing about the cut reads them) — consistent with G5.

**Backfill bounded.** Fires only when "waves predate the rule (no covering
instance exists, open or done)", mints ONE instance, by the kickoff
assistant, same template, announced in chat; token `(release vN.N.N)` is
deduped like any other. It cannot fire when a wave instance covers the
release, and cannot repeat. Retroactive check: v0.2.0's live pass EXISTS
(T-002, done — named in ROADMAP's release definition item 6, line 76), so
the backfill correctly would not fire for it. The exception exists so no
release ships without at least one minted instance — bounded and
well-motivated.

### ITS-005 — coherence as ONE system

- **Actors.** Mint: stage tickers (routine; steps 4/6) + kickoff assistant
  (fallback when no ticker is armed — chat-first control plane H7) + owner
  (manual override; the owner can override any machine rule). Execute: the
  owner at the keyboard (`owner-acceptance` pseudo-actor, established by
  T-002/U-111/U-112 assignees — live grep). Verdict: the engine review
  pass (step 3) — "Done is never the owner's or the ticker's
  self-assessment" (§5.3) matches SKILL.md step 3 ("Verdicts are the
  engine's, never the fulfiller's own"). Three disjoint roles; no actor
  both executes and verdicts. Coherent.
- **Dedup keys.** Title-token convention (`(<D id> AC<n>)` /
  `(wave <record id>)` / `(release vN.N.N)`), scanned across open AND done
  test-requirements tickets; idempotent and convergent (a mint lost
  mid-pass is re-minted by the next sweep — the store never loses a
  confirmed write). Failure behavior on a false trigger (§9): a
  PM-planned record misnamed as a wave mints ONE extra instance —
  "harmless (one extra non-gating instance)". The design fails OPEN in the
  harmless direction (a surplus non-gating ticket), never fail-closed (a
  missed gate) — the correct polarity for a non-blocking mechanism. A
  dedup miss (token typo) degrades the same way. Coherent.
- **Paused-keeps-open.** R2: open = any status except done → a `paused`
  ticket (U-038: parked for maintenance, visible, never blocked) keeps the
  wave open, so no acceptance instance is minted for a parked wave; when
  the pause clears and the wave completes, the sweep mints then. Matches
  close semantics: at mint time the record holds only done tickets, and
  the minted instance is not a member, so `task_close_sprint` has nothing
  to eject. Coherent.
- **Digest note.** STEP 6 item 4 notes the mint in the pass digest (U-061);
  SKILL.md step 6 reopens the `ticker-v` record with the digest (U-108
  reopen semantics). Combined with the store's history events ("the store's
  history events record who minted", §9), every mint leaves two audit
  traces. Coherent.

### ITS-006 — T-085 AC3 (artifact before in-review)

T-085 history: `artifact:file:docs/live-in-eclipse-recurrence-flow.md` at
2026-11-15T09:01:36.994Z, `updated:status` (→ in-review) at
09:01:37.057Z. Ordering holds. The artifact cites SKILL.md (verbatim
quote), AGENTS.md (paired verification, autonomy spectrum, H7) and
ROADMAP.md (T-041) — AC1's citation requirement met.

## Notes (for the reviewer to weigh)

- **N1 — failure-path resume state (robustness, not a contract breach).**
  §5.4 parks a failed instance `blocked` with resume to `sprint-backlog`.
  After the owner clears the blocker, the instance sits in a CLAIMABLE
  state until the owner re-applies `in-progress` + `owner-acceptance`
  while re-running the pass. G1 as worded covers the MINTED state
  (verified); the post-clear state relies on the owner acting immediately
  and on the claim walk's assignee pre-check (field discipline, SKILL.md
  field observations) — machinery does not enforce it. A one-line
  strengthening ("on resume, re-set in-progress + owner-acceptance before
  anything else") would close it. Does not affect the ACs under
  verification.
- **N2 — legacy done definitions** with existing children never re-enter
  step 4, so pairing instances fire only on post-flow divisions; covered
  by the division-SATISFIED-with-rationale discipline (applied live to
  T-002, 2026-11-15). Consistent; worth remembering when a legacy
  definition with live-check ACs shows up.
- **N3 — exemplar drift.** Doc §6/§7 cite T-055/T-085 as the live
  in-progress readiness evidence; they are now in-review (still RUNNING,
  never READY). The guarantee's substance is unaffected — live rows
  T-086/T-056/T-051/T-057 confirm RUNNING for in-progress.
- **N4 — T-040 transition pending.** The pre-flow instance is still
  `sprint-backlog` + unassigned (out of pattern). It is currently STALE
  (upstream T-036 moved 2026-11-15 09:03) — not READY, so no kickoff
  branch reaches it this pass — but §7's prescribed one-time
  `task_update` (in-progress + owner-acceptance) should land before the
  test-requirements leg settles, else it becomes a claimable READY
  ticket. Out of T-085's and this ticket's lane; surfaced for the owner /
  next ticker pass.
- **N5 — fallback mint is discipline-guarded.** §3.1's fallback
  (`sprint-backlog` + `owner-acceptance` if the engine refuses the direct
  mint) is kept out of claims only by the assignee pre-check, not by
  machinery (§9 acknowledges `in-progress` is "the only state today's
  machinery keeps out of every dispatch branch"). Accepted by design;
  noted so the reviewer knows the guarantee's strength differs between
  primary and fallback.

## Overall verdict

**APPROVE-WITH-NOTES.** All five integration criteria PASS against live
store evidence and the governing docs; the flow is coherent as ONE system
(actors, dedup, paused-keeps-open, digest) and its non-blocking guarantees
G1/G2/G4/G5/G6 are verified against the real machinery (G3 verified
against the documented U-009 precedent). The notes (N1–N5) are robustness
and follow-up items, none of which contradicts T-085's acceptance
criteria or the governing T-039 rule. This verdict decides T-085's
done/not-done in the reviewer's hands; it does not gate other waves.
