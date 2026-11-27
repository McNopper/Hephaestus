# System: the autonomy-spectrum flow — intake, record, pump, gates (U-113)

- **About this document:** the system-level definition of the autonomy
  spectrum as a FLOW — which actor records a run's position at goal
  intake, where that position lives so it is store- and git-verifiable,
  and how the pump obeys it (both goal-horizon modes; review-cadence
  gating of release and acceptance). **Read by:** the product-owner agent
  (the intake actor), the stage tickers (the pump), the engine review
  pass, the maintainer. **Related:** `AGENTS.md` "The autonomy spectrum
  (the operating model, owner requirement 2026-11-15)" (the doctrine this
  system implements), `ROADMAP.md` Vision / Product Goal paragraph (the
  run-to-goal reference), `.opencode/agent/product-owner.md` "The two
  dials (goal intake)" (the intake actor's procedure, recorded by T-042),
  `docs/design-stage-pipeline.md` (the pump this system parameterizes;
  §4 the tick algorithm), `docs/acceptance-run-system.md` (the
  acceptance-run system this composes with), the ROADMAP standing rules
  (T-041 release cadence, T-039 recurring acceptance).

## Overview

The autonomy spectrum is not a mechanism — it is a **run position recorded
on a GOAL-root ticket at goal intake and read back by the unchanged
pump**. Four parts: the product-owner agent writes the position (two
dials: goal horizon, review cadence) onto the goal ticket; the task store
keeps it as the single verifiable home (git-versioned Markdown, D-006);
the stage tickers run the identical tick algorithm under either horizon
mode, parameterized only by when they stop launching; and the
review/acceptance gates enforce the cadence (a human review that accepts
cuts a release — T-041; recurring acceptance hardens without gating —
T-039). The dials tune stop conditions and human-entry points; they add
no machinery, and they never stall a wave.

## The two dials (the run position)

- **Goal horizon** — where the run stops:
  - **time-boxed** (extreme A): the harness works a fixed time and its
    result at the horizon is the final product of that run — maximum
    autonomy, the human absent.
  - **run-to-Product-Goal** (the general rule): no time bound; the
    harness runs until the Product Goal (`ROADMAP.md` Vision) is reached.
- **Review cadence** — how often the human enters the loop:
  - **releases-only-on-human-review** (the standing rule, T-041 — the
    safe end): waves complete on engine verdicts and end at commit + push;
    the version is cut only when a human review accepts.
  - **recurring acceptance** (T-039): every successful wave/release grows
    a fresh non-blocking test-requirements acceptance ticket — hardening
    that never gates; tighter settings put the human in a tight loop
    (extreme B: constant feedback).
- The dial floor is fixed: no setting releases without a human review
  (T-041 is a standing rule, not a dial value), and NEEDS-HUMAN is the
  escalation threshold at every setting.

## Major Parts

- **Goal intake — the product-owner agent** (`.opencode/agent/product-owner.md`,
  U-105 lineage): when the owner's message is a whole objective (the
  GOAL root — `stage: requirements`, never stageless), it records the run
  position on the ticket as
  the two dials — asking when the owner leaves them unstated (bounded
  round-trips, then register with the open question recorded). The dials
  are run parameters recorded on the ticket, not acceptance criteria.
- **The store record — the GOAL-root ticket**: the position's only
  home is the ticket file `.harness/tasks/hephaestus/<GOAL-id>.md` (the
  description carries the labeled dial record; a comment may carry the
  intake Q&A). Read back with `task_get`; auditable in git (D-006: store
  files are git-versioned Markdown). Child tickets inherit by their `basis`
  chain — the position is read from the goal root, never restated per
  child, never held in chat memory or a session.
- **The pump — stage tickers on schedules** (`docs/design-stage-pipeline.md`
  §4): the tick algorithm is identical under both horizon modes; the
  horizon decides only whether kickoff keeps launching. The cadence dial
  changes nothing inside the pump.
- **The review/acceptance gates** (the engine review pass + the human
  review): engine verdicts gate every ticket's `done` (unchanged); the
  human review gates only the release (T-041); the recurring acceptance
  ticket (T-039) hardens without gating.

## Interfaces

- **`task_create` / `task_update` (intake → store)** — the dial record
  crosses here as labeled ticket text; verifiable by `task_get` on the
  GOAL ticket (the store, not memory, is the proof — the product-owner
  loop, step 4).
- **`task_get` / `task_board` / `task_readiness` (pump ← store)** — the
  ticker reads the goal root's dial record at kickoff to learn the run's
  horizon; children are found through the `basis` chain, so every stage
  ticker of the run obeys the same position without per-ticket
  restatement.
- **Git history (audit)** — changes to the dial record are commits on the
  ticket file (D-006); the position's provenance is `git log`-verifiable.
- **Wave close (pump → release gate)** — a completed wave (all tickets
  done with engine verdicts) always ends in commit + push;
  `eclipse/releng/release.ps1` (tag per D-002, `-SNAPSHOT` bump) runs
  only when the wave's work carries a human review that accepts.
- **NEEDS-HUMAN (pump → owner)** — `task_set_blocked` with no live retry
  is the only unplanned stop a run-to-goal run takes; a time-boxed run
  also stops at its horizon (deliberately, not as an escalation).

## End-to-end data flows

- **F-1 Intake writes the position.** The owner states an objective →
  the product-owner classifies it as a GOAL root → asks about the
  dials if unstated (at most a couple of round-trips) → records horizon +
  cadence on the ticket → cites the ticket id back. Evidence: `task_get`
  shows the dial record on the GOAL ticket; `git log` on the ticket file
  shows when it was written.
- **F-2 The pump obeys the horizon.** At each kickoff the ticker reads
  the goal root's horizon:
  - **run-to-Product-Goal:** the stage schedules recur without end; the
    run stops only at NEEDS-HUMAN escalations (the owner's queue) or when
    the GOAL root itself reports done by engine verdict.
  - **time-boxed:** the goal's steward session carries the horizon (it
    armed the stage schedules at kickoff) and disarms them at the horizon.
    The pump then finishes the current tick boundary cleanly — claimed
    workers run to completion, engine verdicts land, the sprint closes
    with a digest naming the horizon end — and launches no new READY
    tickets. The wave rules still apply to what the run produced:
    commit + push, release only on human review.
- **F-3 The gates obey the cadence.** A wave completes with engine
  verdicts → commit + push always → a human review that accepts cuts the
  release (T-041); without one the release stays pending. Independently,
  every successful wave/release grows a fresh non-blocking
  test-requirements acceptance ticket (T-039) — at every cadence setting.
- **F-4 Unresolved settings default to the safe end — never stall.** An
  unanswered dial question does not hold the goal: intake registers with
  the open question recorded, and the pump runs the defaults — the
  run-to-Product-Goal horizon (the general rule) with the T-041 cadence
  floor (a human review that accepts cuts a release; waves end at
  commit + push). No dial state blocks a claim, a verdict, a wave close,
  or a commit.

## Technology choices

| Concern | Choice | Why |
|---|---|---|
| Position home | the GOAL-root ticket file, git-versioned Markdown (D-006) | `task_get`-verifiable and `git log`-auditable; no memory |
| Intake actor | the product-owner agent (U-105 lineage; recorded by T-042) | the single point of entry; the owner's seat, build-denied |
| Pump | the unchanged tick algorithm (design §4) | the dials set stop conditions, not procedures |
| Release trigger | a human review that accepts (T-041; `release.ps1`) | the wave, not the calendar, is the release cadence; the human review is the trigger |
| Acceptance recurrence | a fresh non-blocking test-requirements ticket per wave/release (T-039) | hardens acceptance without gating it |

## Key trade-offs / open questions

- **Dials are a ticket-record convention, not store schema.** The store
  does not validate the record's presence or shape; the intake actor owns
  it. If intake misses recur, promoting the dials to first-class ticket
  fields is the follow-up — a store change, not a pump change.
- **Horizon enforcement lives at the steward/schedule level, not the
  engine.** The engine has no horizon concept; a time-boxed run is obeyed
  at kickoff (no new launches past the horizon) while in-flight work
  always runs to its engine verdict — a ticket is never abandoned
  mid-flight because a clock ran out.
- **The safe-end default is unbounded time, gated releases.** An owner
  silent on the horizon gets run-to-Product-Goal (the AGENTS.md general
  rule) with NEEDS-HUMAN as the only stop; an owner silent on cadence
  gets releases-only-on-human-review (T-041). Both defaults are the
  doctrine's own defaults, not new policy.
