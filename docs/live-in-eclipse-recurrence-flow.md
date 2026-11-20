# Live-in-Eclipse acceptance recurrence — the system flow

## About this document

- **Kind:** `doc` / system-stage flow specification (V stage: system; ticket
  T-085, basis T-039). It specifies the recurrence FLOW: which
  actor mints the recurring live-in-Eclipse acceptance ticket, when, from
  what template, and how non-blocking is guaranteed in the dispatch
  machinery.
- **Read by:** the stage ticker pass (executes the mint at its division
  step), the kickoff assistant (fallback minter; the PO's window),
  and the maintainer (product owner — the executor of every live pass);
  **written by:** T-085 (system stage, discipline `software-system`).
- **Related:** `.opencode/skills/stage-pipeline/SKILL.md` (tick step 4
  division map — the governing rule this flow operationalizes), `AGENTS.md`
  (paired-verification paragraph; the autonomy spectrum), `ROADMAP.md`
  (release-cadence standing rule, T-041), `docs/design-stage-pipeline.md`
  (the tick machinery this flow runs inside), `docs/TESTING.md` (the
  checklist a live pass runs), tickets T-039 (the rule), T-002 (the origin
  instance), T-040 (the first instance).
- **Currency (T-202, owner model 2026-11-20):** trigger **R2 (the wave
  instance) is RETIRED** with the sprint model — there are no sprint records
  and no waves to complete, so only the division-time pairing instance (R1)
  mints, plus the release backfill (R3). The mint shape is **born `ready` +
  the `owner-acceptance` assignee shield** (a claim only ever takes
  unassigned tickets), replacing the old `in-progress` / `sprint-backlog`
  landing fight.

## 1. Overview

Live-in-Eclipse verification — console checks, UI passes, panel round trips,
the owner at the keyboard — is ACCEPTANCE verification (owner requirement
2026-11-15, T-039): it pairs to requirements via the `test-requirements` leg,
the last stage of the V. The recurrence turns that standing routine into
machinery: every done definition ticket whose acceptance criteria include
live-in-Eclipse checks (and, in one backfill case, a release)
yields a FRESH `test-requirements` acceptance ticket minted by the stage
ticker's own division step, filled from one fixed template, executed
by the owner, verdicted by the engine — and never gating anything. The
system has four moving parts and no new ones: the tick steps that already
exist (SKILL.md), the store states that already exist, the owner's live pass
(T-002 pattern), and the engine verdict.

## 2. Actors

| Actor | Role in the recurrence | Never does |
|---|---|---|
| **Stage ticker pass** (`ticker-v` steward running the stage-pipeline skill) | the ONLY routine minter: pairing instances at tick step 4 (section 5.1) | spawns a worker for an acceptance ticket; claims one (the assignee shield holds); accepts one |
| **Kickoff assistant** (main chat agent) | FALLBACK minter when no ticker is armed — same template, announced in chat (chat-first control plane, H7) | mints ad-hoc acceptance tickets outside the template |
| **Product Owner / maintainer** | executes the live pass at the Eclipse keyboard; files findings as tickets; may mint or override manually (the owner can override any machine rule) | — |
| **Engine review pass** (tick step 3) | the only `done` authority for an acceptance ticket, same as any ticket | accepts the ticker's or the owner's self-assessment |

`owner-acceptance` is the established pseudo-actor identity for the owner as
pass executor (T-002's assignee); the mint assigns it so no worker can claim
the ticket (section 6, G1).

## 3. The acceptance ticket (the template)

### 3.1 Fields

| Field | Value |
|---|---|
| `title` | `Acceptance (live in Eclipse): <one-line live check> (<D id> AC<n>)` |
| `type` / `story_points` / `priority` | `task` / 0 / medium |
| `role` / `stage` | `tester` / `test-requirements` (immutable) |
| `basis` | D (the machine-readable derivation edge to the verified definition) |
| `status` + `assignee` after mint | `ready` + `owner-acceptance` — born `ready` (its basis D is done, so the birth rule lands it ready); the assignee is the SHIELD |
| `model` | `null` |

The shield: a claim only ever takes UNASSIGNED tickets (SKILL.md field
observation: claims pre-check the candidate's assignee), so the
`owner-acceptance` assignee keeps the instance out of every targeted claim
and out of `task_claim`'s queue walk. Confirmed is read-back truth
(`task_get`), never the minter's intent (DS-002); an unconfirmed assignee
write is repaired by re-asserting the update — NEVER re-created (the title
scan would see the created ticket and dedup would skip the mint, leaving the
state wrong).

### 3.2 Description template

```text
The recurring live-in-Eclipse acceptance (owner requirement 2026-11-15,
T-039; flow: docs/live-in-eclipse-recurrence-flow.md). This ticket is the
pairing instance for <source token>.

Verify live in Eclipse (the owner at the keyboard; checklist:
docs/TESTING.md):
- <the live check(s), one line each — console checks, UI passes, panel
  round trips>

NON-BLOCKING: this ticket hardens acceptance and never gates — no verdict
or release waits on it (T-039). Record a screenshot or panel transcript
reference as an artifact before done. Findings from the pass are filed as
tickets (type: bug) as usual.
```

### 3.3 Acceptance criteria template

- Pairing instance (the T-040 shape): the live check operationalized, plus
  `Evidence recorded as artifact on this ticket (screenshot or panel transcript reference)`.
- Release backfill instance (the T-002 live-pass pattern):
  1. `Live console checks: <the release's agent-facing surfaces> exercised live in Eclipse — tool calls streaming, output visible, session binding correct`
  2. `UI passes: Board / Server / Chat / Providers views exercised against the deployed jars (context menus with a valid and an empty selection)`
  3. `Panel round trips: one chat round-trip and one store round-trip (task_doctor or task_board) from the Eclipse panel itself`
  4. `Evidence recorded as artifact on this ticket (screenshot or panel transcript reference)`

### 3.4 Source token and dedup

The token is the parenthesized suffix of the title: `(<D id> AC<n>)` or
`(release vN.N.N)`. Before minting, scan the
project's `test-requirements` tickets: if ANY ticket's title already carries
the token — open OR done — skip the mint. One instance per source event,
ever; a done instance means the pass already ran for that event. Dedup makes
the mint idempotent and convergent: a mint that failed mid-pass is retried
by the next pass's division heal, a successful one never duplicated.

## 4. Triggers

| # | Trigger event | Observed at | Mints | Source token |
|---|---|---|---|---|
| R1 | A done definition ticket D whose acceptance criteria include live-in-Eclipse checks divides | tick step 4 (division), inside the per-stage loop | 1 pairing instance | `(<D id> AC<n>)` |
| R2 | ~~A substantive wave completes~~ **RETIRED (T-202):** no sprint records, no waves — the wave instance died with the sprint model | — | — | — |
| R3 | A release is cut | — | **no mint** — see the position below; backfill exception only | `(release vN.N.N)` |

**Position — no mint at release cut:** the division-time pairing instances
already cover every definition ticket with live-in-Eclipse checks, and the
ROADMAP standing rule (T-041) keeps the human review as the release trigger:
a release is completed work PLUS a human review. Three reasons the cut mints
nothing:

1. **No new evidence.** The release carries exactly the paired definitions'
   results, and each minted its instance at division. A cut-time mint
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

**Backfill exception:** a release cut whose paired work predates the rule (no
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
> child is the recurrence pattern, NON-BLOCKING: every done definition
> ticket with live-in-Eclipse checks produces a fresh test-requirements
> acceptance ticket (the T-002 pattern systematized) — it hardens acceptance
> and never gates; no verdict waits on it.

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
       section 3 (basis = D) — born `ready` — then `task_update`
       assignee `owner-acceptance` (the shield); confirm by `task_get`
       (read-back truth, DS-002).
    5. Do NOT claim it. Do NOT spawn a worker for it (DR-3).
```

There is no step-6 sweep anymore: the wave-completion recurrence (old R2)
retired with the sprint model (T-202) — nothing in the close step mints.

### 5.2 Execute — the owner's live pass

The instance sits `ready` / `owner-acceptance` — assigned, therefore never
claimable; a visible "the live pass awaits the owner" state on the Board.
The owner works through the
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
(they ride the next pass), the failed evidence is recorded on the instance,
and the instance goes `blocked` (resume to `ready`) with the blocker
naming the finding tickets — a NEEDS-HUMAN-shaped signal aimed at the very
actor who executes the pass. When the findings land, the owner clears the
blocker and re-runs the pass.

## 6. The non-blocking guarantee

Six guarantees, each against a real dependency direction in the dispatch
machinery:

- **G1 — never claimed.** The minted state (`ready` + `owner-acceptance`)
  matches no claim: `task_claim` only ever takes UNASSIGNED ready tickets,
  and the assignee is the shield (SKILL.md field observation: claims
  pre-check the candidate's assignee). Readiness may report the instance
  READY (its basis D is done), but claimability requires
  `assignee == null`, so no claim ever returns it; and no machinery clears
  the assignee — only the owner does.
- **G2 — nothing derives from it.** `test-requirements` is the LAST V stage;
  its division map is empty, so a done instance creates no children. No step
  ever sets an acceptance instance as a basis. No readiness edge depends on
  it.
- **G3 — parent verdicts unaffected.** The live check moves OUT of the
  parent's review scope into the instance; established live on U-009: "AC4
  moved to its own acceptance ticket at test-requirements... This ticket
  returns to in-review for the remaining criteria." The reviewer never
  consults the instance.
- **G4 — completion unaffected.** The instance is not part of any completion
  predicate: the release-cadence evaluation (SKILL.md step 6) reads
  completed work (`done` / `not-implemented`); an open instance is simply
  absent from that set — it records evidence, it never defines completion.
- **G5 — release unaffected.** The release = engine verdicts + human review
  (T-041/ROADMAP). No release step reads acceptance instances; open ones
  carry over and thicken the evidence base against the released build.
- **G6 — failure isolation.** A failed pass cannot gate backwards: the basis
  (when set) is `done` and the store refuses blocking done tickets; the
  failure surfaces as finding tickets plus a blocked instance aimed at the
  owner (5.4). Findings ride the next pass.

Plus convergence: dedup on the source token (3.4) makes the mint idempotent;
a mint lost mid-pass (the tasks namespace can drop mid-run) is healed by the
next pass's division heal — the store never loses a confirmed write, and the
firing actor re-reads existing derivations before creating.

## 7. T-002 is the origin instance, not the mechanism

- **T-002** is a concrete, ONE-TIME ticket: created 2026-09-22 as the
  new-machine bring-up gate, re-scoped by the owner (2026-11-09) into the
  standing end-of-arc routine, executed 2026-11-14 evening as the release
  smoke test (chat panel round trip, store answering from the panel, all
  seven Board columns), `done`. It is an INSTANCE. ROADMAP's release
  definition item 6 still names it as the initial release's live pass.
- **The recurrence** (T-039, operationalized by this document) is the
  MECHANISM that mints fresh instances per done definition ticket with
  live-in-Eclipse checks (and the backfill release case) — the T-002
  pattern systematized. Future instances are
  minted by the ticker per section 5.1, not by hand.
- **T-040** is the first instance minted under the rule but BEFORE this flow
  existed (intake-minted under the pre-T-202 model, unassigned,
  `basis: null`). Under the owner model (T-202) it stays `blocked` — its
  resume target follows its assignee (unassigned → `ready`) — and the
  owner-executed state applies through the same shield: assignee
  `owner-acceptance`, so G1 holds for it too.

## 8. Invariants (summary)

- **I1** — instances are minted ONLY by the section 5.1 wording (ticker
  pass), or by the kickoff assistant with the same template when no ticker
  is armed (fallback, announced in chat).
- **I2** — owner-executed: never worker-spawned, never claimed by an agent.
- **I3** — born `ready`, shielded by assignee `owner-acceptance`; no
  machinery ever clears the assignee (only the owner does).
- **I4** — never a basis; never divided from; no children.
- **I5** — never gates: no verdict, release, or ticket depends on an
  instance.
- **I6** — one instance per source token, ever (open or done).
- **I7** — evidence recorded as an artifact before `done` (the same
  done-time discipline as definition stages).

## 9. Trade-offs and open questions

- **The assignee shield bypasses the claim walk** (accepted): readiness may
  report the instance READY, but claim requires `assignee == null`; the
  store's history events record who minted, so the audit trail stays honest.
- **Dedup keys on a title convention** (accepted): the `task_*` tools expose
  no label query today; if one lands upstream, move the source token to a
  label and keep the scan machine-readable.
- **The pairing fires on ticks only:** when no ticker is armed, the kickoff
  assistant is the fallback minter at division time (chat-first control
  plane, H7).
- **Cost:** each instance costs the owner one live pass; the shared
  checklist and multi-instance evidence (5.2) keep that at one pass per
  release occasion in practice.
