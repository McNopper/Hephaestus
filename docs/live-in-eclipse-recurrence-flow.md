# Live-in-Eclipse acceptance recurrence — the system flow

## About this document

- **Kind:** `doc` / system-stage flow specification (V stage: system; ticket
  T-085, basis T-039). It specifies the recurrence FLOW: which
  actor mints the recurring live-in-Eclipse acceptance ticket, when, from
  what template, and how non-blocking is guaranteed in the dispatch
  machinery.
- **Read by:** the stage ticker pass (executes the mint at its division and
  close steps), the kickoff assistant (fallback minter; the PO's window),
  and the maintainer (product owner — the executor of every live pass);
  **written by:** T-085 (system stage, discipline `software-system`).
- **Related:** `.opencode/skills/stage-pipeline/SKILL.md` (tick step 4
  division map — the governing rule this flow operationalizes), `AGENTS.md`
  (paired-verification paragraph; the autonomy spectrum), `ROADMAP.md`
  (release-cadence standing rule, T-041), `docs/design-stage-pipeline.md`
  (the tick machinery this flow runs inside), `docs/TESTING.md` (the
  checklist a live pass runs), tickets T-039 (the rule), T-002 (the origin
  instance), T-040 (the first instance).

## 1. Overview

Live-in-Eclipse verification — console checks, UI passes, panel round trips,
the owner at the keyboard — is ACCEPTANCE verification (owner requirement
2026-11-15, T-039): it pairs to requirements via the `test-requirements` leg,
the last stage of the V. The recurrence turns that standing routine into
machinery: every successful wave (and, in one backfill case, a release)
yields a FRESH `test-requirements` acceptance ticket minted by the stage
ticker's own division/close steps, filled from one fixed template, executed
by the owner, verdicted by the engine — and never gating anything. The
system has four moving parts and no new ones: the tick steps that already
exist (SKILL.md), the store states that already exist, the owner's live pass
(T-002 pattern), and the engine verdict.

## 2. Actors

| Actor | Role in the recurrence | Never does |
|---|---|---|
| **Stage ticker pass** (`ticker-v` steward running the stage-pipeline skill) | the ONLY routine minter: pairing instances at tick step 4, wave instances at tick step 6 (section 5.1) | spawns a worker for an acceptance ticket; plans one into a sprint record; accepts one |
| **Kickoff assistant** (main chat agent) | FALLBACK minter when no ticker is armed — same template, announced in chat (chat-first control plane, H7) | mints ad-hoc acceptance tickets outside the template |
| **Product Owner / maintainer** | executes the live pass at the Eclipse keyboard; files findings as tickets; may mint or override manually (the owner can override any machine rule) | — |
| **Engine review pass** (tick step 3) | the only `done` authority for an acceptance ticket, same as any ticket | accepts the ticker's or the owner's self-assessment |

`owner-acceptance` is the established pseudo-actor identity for the owner as
pass executor (T-002's assignee); the mint assigns it so no worker can claim
the ticket (section 6, G1).

## 3. The acceptance ticket (the template)

### 3.1 Fields

| Field | Pairing instance (R1) | Wave instance (R2) |
|---|---|---|
| `title` | `Acceptance (live in Eclipse): <one-line live check> (<D id> AC<n>)` | `Acceptance (live in Eclipse): wave <record id> live pass (wave <record id>)` |
| `type` / `story_points` / `priority` | `task` / 0 / medium | same |
| `role` / `stage` | `tester` / `test-requirements` (immutable) | same |
| `basis` | D (the machine-readable derivation edge to the verified definition) | `null` (no single upstream ticket) |
| `sprint` | none — NEVER planned into any sprint record | same |
| `status` + `assignee` after mint | `in-progress` + `owner-acceptance` | same |
| `model` | `null` | `null` |

Fallback: if the engine refuses a direct mint into `in-progress` (no claim
walk), mint `sprint-backlog` + `owner-acceptance` — the assignee keeps it out
of targeted claims (SKILL.md field observation: claims pre-check the
candidate's assignee). The `in-progress` mint is primary because it matches
no dispatch branch at all (G1).

### 3.2 Description template (both instances)

```text
The recurring live-in-Eclipse acceptance (owner requirement 2026-11-15,
T-039; flow: docs/live-in-eclipse-recurrence-flow.md). This ticket is the
<pairing|standing> instance for <source token>.

Verify live in Eclipse (the owner at the keyboard; checklist:
docs/TESTING.md):
- <the live check(s), one line each — console checks, UI passes, panel
  round trips>

NON-BLOCKING: this ticket hardens acceptance and never gates — no verdict,
wave or release waits on it (T-039). Record a screenshot or panel transcript
reference as an artifact before done. Findings from the pass are filed as
tickets (type: bug) as usual.
```

### 3.3 Acceptance criteria template

- Pairing instance (the T-040 shape): the live check operationalized, plus
  `Evidence recorded as artifact on this ticket (screenshot or panel transcript reference)`.
- Wave instance (the T-002 live-pass pattern):
  1. `Live console checks: <the wave's agent-facing surfaces> exercised live in Eclipse — tool calls streaming, output visible, session binding correct`
  2. `UI passes: Board / Server / Chat / Providers views exercised against the deployed jars (context menus with a valid and an empty selection)`
  3. `Panel round trips: one chat round-trip and one store round-trip (task_doctor or task_board) from the Eclipse panel itself`
  4. `Evidence recorded as artifact on this ticket (screenshot or panel transcript reference)`

### 3.4 Source token and dedup

The token is the parenthesized suffix of the title: `(<D id> AC<n>)`,
`(wave <record id>)`, or `(release vN.N.N)`. Before minting, scan the
project's `test-requirements` tickets: if ANY ticket's title already carries
the token — open OR done — skip the mint. One instance per source event,
ever; a done instance means the pass already ran for that event. Dedup makes
the mint idempotent and convergent: a mint that failed mid-pass is retried
by the next sweep, a successful one never duplicated.

## 4. Triggers

| # | Trigger event | Observed at | Mints | Source token |
|---|---|---|---|---|
| R1 | A done definition ticket D whose acceptance criteria include live-in-Eclipse checks divides | tick step 4 (division), inside the per-stage loop | 1 pairing instance | `(<D id> AC<n>)` |
| R2 | A substantive wave completes: a sprint record whose id does NOT start with `ticker-` holds at least one ticket and zero open tickets (open = any status except `done`; `paused` keeps the wave open) | tick step 6 (close), as a sweep after the digest is written, before the record closes | 1 wave instance | `(wave <record id>)` |
| R3 | A release is cut | — | **no mint** — see the position below; backfill exception only | `(release vN.N.N)` |

Ticker micro-records (`ticker-*`, the rolling per-stage records) NEVER
trigger R2 — ROADMAP is explicit that they are not waves in this sense;
substantive planned waves are.

**Position — no mint at release cut (once per wave, not once per release):**
the wave-mint already covers every release. The ROADMAP standing rule (T-041)
reads: the wave, not the calendar, is the release cadence — and the human
review, not the wave alone, is the release trigger; a release is a completed
wave PLUS a human review. Three reasons the cut mints nothing:

1. **No new evidence.** The release carries exactly its waves' results, and
   each of those waves minted its instance at completion. A cut-time mint
   would demand a second live pass over unchanged work — a parallel
   mechanism for what exists (thin-harness standing rule, D-005).
2. **The gating temptation is forbidden exactly there.** The cut is the one
   moment a gate would be most tempting, and the rule forbids it in the same
   breath: "engine verdicts are necessary for a release, never sufficient"
   — the acceptance "hardens the work without gating it" (T-039). The
   release's human review IS a live-pass occasion: the owner's review session
   executes the outstanding instances at the keyboard (the worked example in
   AGENTS.md's autonomy spectrum: the owner's review and acceptance cut
   `v0.2.0`).
3. **Open instances carry over.** An instance still open at cut time keeps
   thickening evidence against the released build; nothing about the cut
   reads it.

**Backfill exception:** a release cut whose waves predate the rule (no
covering instance exists, open or done) mints ONE release instance at the
cut, by the kickoff assistant, same template, announced in chat. This is the
only case where "once per release" is literally true, and it exists so no
release ships without at least one minted acceptance instance.

## 5. The flow, end to end

### 5.1 Mint — the exact division-step wording

The governing rule is SKILL.md tick step 4 (quoted verbatim):

> a definition ticket whose acceptance criteria include live-in-Eclipse
> checks (console checks, UI passes, panel round trips) divides with a
> paired test-requirements child carrying that live check — live-in-Eclipse
> verification is ACCEPTANCE verification and belongs to the last V stage,
> not in ad-hoc blocked/in-review states or manual follow-up notes. The
> child is the recurrence pattern, NON-BLOCKING: every successful
> wave/release produces a fresh test-requirements acceptance ticket (the
> T-002 pattern systematized) — it hardens acceptance and never gates; no
> verdict, wave or release waits on it.

The wording the next ticker pass applies (AGENTS.md carries the same
classification: live-in-Eclipse = ACCEPTANCE = the `test-requirements` leg):

```text
STEP 4 EXTENSION — run once per done definition ticket D, immediately after
D's ordinary division children are created:

  LIVE-CHECK PAIRING (owner requirement 2026-11-15, T-039)
    1. Scan D.acceptance_criteria for live-in-Eclipse checks (console
       checks, UI passes, panel round trips). If none, stop.
    2. source token := "(<D id> AC<n>)" for the first matching AC.
    3. If any test-requirements ticket's title already carries the token
       (open or done), stop — the instance exists.
    4. Create the ticket per docs/live-in-eclipse-recurrence-flow.md
       section 3 (basis = D), then set status
       in-progress, assignee owner-acceptance.
    5. Do NOT plan it into a sprint record. Do NOT spawn a worker for it.

STEP 6 EXTENSION — run once per pass, after the digest is written, before
the sprint record closes:

  WAVE-COMPLETION RECURRENCE (the recurrence sentence of step 4)
    1. For each sprint record W of the project: if W's id does not start
       with "ticker-", W holds at least one ticket, and every ticket of W
       is done, then W is a completed wave.
    2. source token := "(wave <W id>)". If any test-requirements ticket's
       title already carries the token (open or done), skip W.
    3. Create the ticket per section 3 (basis = null), then set status
       in-progress, assignee
       owner-acceptance. Do NOT plan it into a sprint record.
    4. Note the mint in the pass digest (U-061).
```

Why the wave sweep lives at step 6 and not inside a stage's step 4: steps
1-4 run per stage in canonical order, so a stage-local check can miss a wave
completed by a LATER stage's verdict in the same pass; the close sweep sees
the whole pass, runs once, and dedup makes re-runs harmless.

### 5.2 Execute — the owner's live pass

The instance sits `in-progress` / `owner-acceptance` — a visible "the live
pass awaits the owner" state on the Board. The owner works through the
ticket's checks in Eclipse (checklist: `docs/TESTING.md`) and records the
evidence — a screenshot or panel transcript reference — with
`task_add_artifact` on the instance. Evidence economics: ONE live pass may
evidence SEVERAL open instances (the checklist is shared); record the
evidence artifact on each, keep each instance's verdict independent.

### 5.3 Verdict

The owner flips the instance to `in-review`; the next pass's step 3 spawns
the reviewer and the engine verdicts it like any ticket — PASS `done`, FAIL
`blocked`. Done is never the owner's or the ticker's self-assessment.

### 5.4 A failed pass

A failed live pass never back-gates onto finished work: the store refuses
blocking a `done` ticket, so escalating to the basis (step 2's rule) cannot
re-open the verified definition. Instead: findings are filed as bug tickets
(they ride the NEXT wave), the failed evidence is recorded on the instance,
and the instance goes `blocked` (resume to sprint-backlog) with the blocker
naming the finding tickets — a NEEDS-HUMAN-shaped signal aimed at the very
actor who executes the pass. When the findings land, the owner clears the
blocker and re-runs the pass.

## 6. The non-blocking guarantee

Six guarantees, each against a real dependency direction in the dispatch
machinery:

- **G1 — never dispatched.** The minted state (`in-progress` +
  `owner-acceptance`, no sprint) matches no tick branch: step 5 claims only
  unclaimed `sprint-backlog` tickets; readiness reports `RUNNING` for
  in-progress work (observed live 2026-11-15: T-055, T-085), never `READY`;
  and the instance is never planned into any record, so no
  `task_close_sprint` can eject it back into a claimable state.
- **G2 — nothing derives from it.** `test-requirements` is the LAST V stage;
  its division map is empty, so a done instance creates no children. No step
  ever sets an acceptance instance as a basis. No readiness edge depends on
  it.
- **G3 — parent verdicts unaffected.** The live check moves OUT of the
  parent's review scope into the instance; established live on U-009: "AC4
  moved to its own acceptance ticket at test-requirements... This ticket
  returns to in-review for the remaining criteria." The reviewer never
  consults the instance.
- **G4 — wave completion unaffected.** The instance is not a member of the
  wave record; a wave completes on its real tickets' engine verdicts alone.
  The mint happens at close, after completion is already true — it records
  evidence, it does not define completion.
- **G5 — release unaffected.** The release = engine verdicts + human review
  (T-041/ROADMAP). No release step reads acceptance instances; open ones
  carry over and thicken the evidence base against the released build.
- **G6 — failure isolation.** A failed pass cannot gate backwards: the basis
  (when set) is `done` and the store refuses blocking done tickets; the
  failure surfaces as finding tickets plus a blocked instance aimed at the
  owner (5.4). Findings ride the next wave.

Plus convergence: dedup on the source token (3.4) makes the mint idempotent;
a mint lost mid-pass (the tasks namespace can drop mid-run) is re-minted by
the next sweep — the store never loses a confirmed write, and the sweep
re-derives what was never confirmed.

## 7. T-002 is the origin instance, not the mechanism

- **T-002** is a concrete, ONE-TIME ticket: created 2026-09-22 as the
  new-machine bring-up gate, re-scoped by the owner (2026-11-09) into the
  standing end-of-arc routine, executed 2026-11-14 evening as the release
  smoke test (chat panel round trip, store answering from the panel, all
  seven Board columns), `done`. It is an INSTANCE. ROADMAP's release
  definition item 6 still names it as the initial release's live pass.
- **The recurrence** (T-039, operationalized by this document) is the
  MECHANISM that mints fresh instances per successful wave (and the backfill
  release case) — the T-002 pattern systematized. Future instances are
  minted by the ticker per section 5.1, not by hand.
- **T-040** is the first instance minted under the rule but BEFORE this flow
  existed (intake-minted; `sprint-backlog`, unassigned, `basis: null`). Live
  readiness evidence (2026-11-15) showed it READY via the then-legacy
  epic-chain fallback (removed with the epic field, 2026-11-19 — a basis-less
  ticket resolves through its own flow history only). Transition note
  (out of T-085's lane): apply the owner-executed state
  once — `task_update` status `in-progress`, assignee `owner-acceptance` —
  so G1 holds for it too.

## 8. Invariants (summary)

- **I1** — instances are minted ONLY by the section 5.1 wording (ticker
  pass), or by the kickoff assistant with the same template when no ticker
  is armed (fallback, announced in chat).
- **I2** — owner-executed: never worker-spawned, never claimed by an agent.
- **I3** — never planned into any sprint record.
- **I4** — never a basis; never divided from; no children.
- **I5** — never gates: no verdict, wave, release, or ticket depends on an
  instance.
- **I6** — one instance per source token, ever (open or done).
- **I7** — evidence recorded as an artifact before `done` (the same
  done-time discipline as definition stages).

## 9. Trade-offs and open questions

- **Minting into `in-progress` bypasses the claim walk** (accepted): it is
  the only state today's machinery keeps out of every dispatch branch; the
  store's history events record who minted, so the audit trail stays honest.
- **Dedup keys on a title convention** (accepted): the `task_*` tools expose
  no label query today; if one lands upstream, move the source token to a
  label and keep the scan machine-readable.
- **Wave detection keys on the `ticker-` record naming** (accepted): that
  distinction is ROADMAP's own; a PM-planned custom record name that is
  actually a micro-record would false-trigger one mint — naming discipline
  is the guard, and the false trigger is harmless (one extra non-gating
  instance).
- **The sweep runs on ticks only:** when no ticker is armed, the kickoff
  assistant is the fallback minter at wave close (chat-first control plane,
  H7).
- **Cost:** each instance costs the owner one live pass; the shared
  checklist and multi-instance evidence (5.2) keep that at one pass per
  release occasion in practice.
