# Requirements: the unified V-ticker (`ticker-v`)

- **About this document:** the requirements definition for T-035 — WHAT the
  scheduler must do now that the stage pipeline is driven by ONE schedule
  walking the whole V step by step, and WHY (owner directive 2026-11-14).
  It formalizes the PROTOTYPE (`.opencode/skills/stage-pipeline/ticker-setup.ps1`
  and the rewritten `SKILL.md` tick procedure, implemented pre-ticket and
  recorded as provenance on T-035) so the downstream stages specify, verify
  and harden it rather than rebuild it from zero. **Status: requirements —
  the derivation basis for the system → design → implementation → test
  stages of the ticker-v arc.**
- **Read by:** the downstream stages deriving from T-035; the testers writing
  the paired acceptance tests; the maintainer.
- **Related:** ticket `.harness/tasks/hephaestus/T-035.md` (owner requirement
  - provenance), related `.harness/tasks/hephaestus/U-112.md` (the walked
  release path that exposed the ten-schedule cost), `docs/design-stage-pipeline.md`
  (the U-110 design — its scheduling-topology sections are superseded on the
  ten→one point by this document; store contract, readiness, verdicts and
  division carry over unchanged), `docs/requirements-stage-pipeline.md`
  (the per-stage-era requirements — this document amends exactly its
  ticker-topology aspect), `.opencode/skills/stage-pipeline/SKILL.md`
  (the one-pass tick procedure the prototype arms).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## Coverage (upstream definitions addressed)

- **U-112** (owner objective 2026-11-14): the pipeline walked the whole
  release path with TEN per-stage tickers. The walk exposed the cost of that
  topology — ten schedules to arm, ten sessions to watch, a spawn cap that is
  global only by luck (the sum of ten independent tickers), and ten things to
  re-arm after every reboot. Addressed by FR-001..FR-004 (one schedule, one
  session), FR-010/FR-011 (global cap), FR-015 (resume), FR-004 (retirement).
- **Owner requirement 2026-11-14 (recorded on T-035):** "the scheduler should
  be ONE schedule that goes through the whole V model step by step — not one
  schedule per stage" — canonical order, one steward session, one rolling
  sprint record (`ticker-v`), spawn cap global by construction. Addressed by
  the whole document; T-035's five acceptance criteria map one-to-one to
  AC-001..AC-005 (legend: **C1..C5** = T-035 acceptance criteria 1..5).
- **Prototype provenance (T-035 artifacts, read-only):** `ticker-setup.ps1`
  (single `ticker-v` schedule arming) and `SKILL.md` (one-pass tick
  procedure). The FRs below describe what the prototype does and must keep
  doing; the deltas found while verifying it against the actual script
  content are stated honestly in [Prototype deltas](#prototype-deltas-found-while-verifying-2026-11-15)
  and Open Questions.

## Goal

Drive the entire linked stage pipeline with ONE scheduled ticker (`ticker-v`):
each tick is ONE pass over all ten V stages in canonical order — escalating
blockages, giving engine verdicts, dividing done tickets, kicking off ready
tickets — under a single global spawn cap, so the pipeline is one thing to
arm, observe, pause and resume, and the maintainer's only regular duty stays
resolving NEEDS-HUMAN.

## Users

- **Product Owner** (the maintainer): arms, pauses, resumes the pipeline with
  one command; resolves NEEDS-HUMAN escalations.
- **The ticker** (one steward session on the `ticker-v` schedule): walks the
  V; escalates, verdicts, divides, kicks off. Never works a ticket itself.
- **Workers** (background subagents): finish exactly one ticket each, under
  the global spawn cap.
- **Assistant** (main chat session): the PO's proxy — intake and kickoff;
  surfaces state; never works tickets, never self-accepts.

## User Stories

- **US-001:** As the PO, I want ONE schedule that walks the whole V step by
  step, so that the pipeline is a single thing to arm, watch and trust — not
  ten schedules to keep in step.
- **US-002:** As the PO, I want the spawn cap global by construction, so that
  concurrent subagents are bounded by one number — never the sum of ten
  independent tickers each enforcing its own cap.
- **US-003:** As the PO, I want resume after shutdown to be re-running one
  script, so that the task store is the only memory and nothing else needs
  re-arming.

## Functional Requirements

### Topology — the ONE model (C1)

- **FR-001 (C1)** ubiquitous: THE V-pipeline scheduler SHALL consist of
  exactly ONE steward session and exactly ONE experimental automation, and the
  automation SHALL be named `ticker-v`.
- **FR-002 (C1, C3)** ubiquitous: THE `ticker-v` schedule SHALL drive its
  steward session with the stage-pipeline skill's one-pass tick prompt (the
  prompt the prototype arms: per stage — readiness scan, blocked-escalate,
  in-review-verdict, done-divide; then plan-claim-spawn under the cap; then
  a pass digest).
- **FR-003 (C1, C4)** unwanted: IF a `ticker-v` schedule already exists on
  the running service WHEN `ticker-setup.ps1` is invoked, THEN the setup
  SHALL leave exactly ONE `ticker-v` schedule in place afterwards (reuse or
  replace — never a duplicate), with exactly one steward session driving it.
- **FR-004 (C1, C5)** ubiquitous: THE pipeline SHALL NOT arm per-stage
  schedules; the ten former per-stage schedules SHALL stay retired, and no
  setup or teardown path SHALL recreate them.

### The tick — one pass over the whole V (C1, C3)

- **FR-005 (C1, C3)** ubiquitous: ONE `ticker-v` tick SHALL run ONE pass over
  ALL ten stages in this canonical order: requirements, system, architecture,
  design, implementation, test-implementation, test-design,
  test-architecture, test-system, test-requirements.
- **FR-006 (C1)** event-driven: WHEN the pass visits a stage, the ticker
  SHALL process only that stage's tickets, in this fixed order:
  (1) BLOCKED → escalate to the basis via `task_set_blocked` on the basis
  ticket; NEEDS-HUMAN when the basis is itself blocked or the round-trip
  repeats;
  (2) IN-REVIEW → engine verdict via ONE reviewer subagent per ticket
  (PASS → `done`; FAIL → `task_set_blocked` with the reviewer's reasons;
  UNCLEAR → one retry round-trip to the fulfiller);
  (3) DONE without downstream children → divide: create the linked
  next-stage ticket(s) plus the paired `test-*` ticket for definition stages
  (`basis` = the done ticket, artifact references carried).
- **FR-007 (C1)** event-driven: WHEN READY tickets are kicked off, the ticker
  SHALL plan them into the rolling `ticker-v` sprint record first
  (`task_plan_sprint`) and only then claim them (`task_claim`) — claims take
  `sprint-backlog` tickets only, and division-born tickets start in
  `product-backlog`.
- **FR-008 (C1)** event-driven: WHEN a ticket is claimed, the ticker SHALL
  spawn ONE background subagent for it with the fulfillment brief (the
  ticket, its direct basis artifacts, its stage's discipline skill; record
  the artifact; set `in-review` — never `done`).
- **FR-009 (C1)** unwanted: IF the file lanes of two claimable tickets
  overlap, THEN the ticker SHALL serialize their spawns within the pass
  (never two concurrent subagents on one lane).

### The global spawn cap (C1, C2)

- **FR-010 (C1, C2)** ubiquitous: THE spawn cap SHALL be global by
  construction — there is exactly one ticker, and it SHALL keep its own
  running subagents at most Cap (setup default 4) across the ENTIRE pass,
  counting them before every spawn and queueing claims while at cap.
- **FR-011 (C2)** unwanted: IF a spawn would exceed Cap, THEN the ticker
  SHALL defer that kickoff to a later tick (never exceed).

### The rolling sprint record (C1, C2)

- **FR-012 (C1, C2)** ubiquitous: THE pipeline SHALL use ONE rolling sprint
  record, `ticker-v`, reused across ticks (U-108 reopen semantics) — not a
  sprint per tick and not a sprint per stage.
- **FR-013 (C2)** unwanted: IF spawned work is still in flight WHEN a pass
  would close the `ticker-v` record, THEN the record SHALL stay active until
  that work lands (closing ejects unfinished tickets to `product-backlog`
  and corrupts in-flight fulfillment); a later tick closes it with the
  digest.

### Empty pass

- **FR-014 (C3)** ubiquitous: THE ticker SHALL treat an empty V (nothing
  actionable in any stage) as a successful pass; its cost SHALL be one
  `task_readiness` query per stage.

### Resume semantics (C1, C4)

- **FR-015 (C1, C4)** event-driven: WHEN the service restarts or the machine
  reboots (schedules are bound to sessions on the running service and die
  with it), THEN resuming the pipeline SHALL consist of re-running
  `ticker-setup.ps1` alone — the task store is the only memory; no ticket,
  sprint, link or artifact state needs re-arming. The exactly-one outcome is
  FR-003.

## Non-Functional Requirements

- **NFR-OPER-001 (C3):** Observability — pipeline health SHALL be readable
  from ONE schedule record on the service (`runCount`, `lastOutcome`), not
  assembled from ten.
- **NFR-REL-001 (C4):** Resilience — a shutdown or reboot loses nothing; the
  store carries all state, and resume is FR-015's one command.
- **NFR-COST-001 (C2):** Cost bound — concurrent subagents (and therefore
  token burn) are bounded by one number, the global Cap.

## Constraints & Assumptions

- **C-A:** The experimental automations surface is enabled in the service
  config (`"experimental": { "automations": true }` — renamed from schedules in 2.3.1 #64160; W1 adopted, see
  `docs/opencode-v2-adoption.md`).
- **C-B (F1, confirmed in SKILL.md field observations):** The steward session
  itself spawns workers, so the ticker is a NESTED steward:
  `experimental.subagent_depth >= 2` must hold in the user-global config
  (setup-local per D-004) — the ticker is one subagent level, its workers
  the second. Without it the ticker cannot spawn its fulfillment workers.
- **C-C (observed live 2026-11-14, script header):** Ticker sessions must
  live in the repo and inherit the harness config — the setup creates the
  steward as a CHILD session of a repo-rooted parent (a plain home-rooted
  session gets the user-global config only: no task tools).
- **C-D (D-004):** Provider/model choices are setup-local; this document
  names behaviors and tiers only, never concrete provider or model ids.
- **C-E:** Store writes go through the `task_*` tools only; text files are
  UTF-8 (AGENTS.md write discipline).
- **C-F:** Cadence and cap are arm-time parameters (`-IntervalMinutes`
  default 5, `-Cap` default 4) - owner-tunable at each arming (the default
  was 15 at the 2026-11-15 requirements run; the owner set the cadence to
  1 fire / 5 minutes on 2026-11-16 - `ticker-setup.ps1` today).

## Prototype deltas (found while verifying, 2026-11-15)

Verified against the actual script and skill content; recorded here so the
implementation leg hardens exactly these, honestly:

1. **Resume is required-but-not-yet-idempotent.** The claim "re-running
   `ticker-setup.ps1` yields exactly one schedule" is the REQUIREMENT
   (FR-003/AC-004). As written today, the script unconditionally POSTs a new
   steward session and a new schedule on every run (script lines 76–107: no
   lookup by name, no retire) — a re-run today would DUPLICATE. The
   implementation leg adds the guard: dedupe/replace by the `ticker-v` name,
   and decide steward-session reuse vs. recreation (Open Questions Q-002).
   This is exactly the ticket's "validates/hardens rather than builds from
   zero".
2. **Stale `-Stages` reference.** SKILL.md's pause/resume field observation
   still says "re-run `ticker-setup.ps1` with the same `-Stages`" — a
   per-stage-era leftover; the unified script has no `-Stages` parameter.
   Reconciliation belongs to the skill-lane ticket, not this one.
3. **Latent auth-check defect.** `ticker-setup.ps1` line 25 tests
   `$Password`, which is never defined (the parameter is `$Token`), so the
   fallback condition is always true and `-BaseUrl`/`-Token` overrides are
   clobbered by `service.json`. Hardened in the implementation leg.
4. **Doc coherence.** AGENTS.md, `docs/design-stage-pipeline.md` and
   `docs/requirements-stage-pipeline.md` still describe the per-stage
   ticker strategy; this document is the requirements for the amendment.
   The repo-level coherence pass (AGENTS.md: engine behavior and skill/doc
   text must not diverge) belongs to the queued lanes that own those files.

**Landed since (re-verified 2026-11-26):** all four deltas are fixed - the
re-arm lookup/retire guard and the `$Password` → `$Token` auth check are in
`ticker-setup.ps1` (2026-11-16), the stale `-Stages` line is gone from
`SKILL.md` (T-130, 2026-11-16), and the per-stage coherence text was
reconciled by the ticker-v arc. The findings above stand as the
2026-11-15 record of what the prototype then read.

## Acceptance Criteria

One-to-one with T-035's five criteria. **Scope note: this ticket delivers
AC-001 only** — the requirements artifact; AC-002..AC-005 gate the later
pipeline stages (system/design verification, the verification leg, the
resume proof, the retirement check).

- **AC-001 (FR-001..FR-015; C1):** Given this document, When the
  unified-ticker model is reviewed, Then each of the canonical order
  (FR-005), the single schedule and session (FR-001), the single rolling
  record (FR-012), the global cap (FR-010), plan-then-claim (FR-007) and
  the resume semantics (FR-003/FR-015) is stated as a testable requirement
  with an id — the requirements artifact exists and is complete.
- **AC-002 (FR-005..FR-013; C2):** Given the model, When the system/design
  stages verify the ticker's interaction with sessions, schedules, the cap
  and the rolling record, Then the interaction is found coherent (the paired
  test verifies).
- **AC-003 (FR-002, FR-005; C3):**
  Given `ticker-v` armed on the running service,
  When the schedule's interval elapses,
  Then the schedule record reports `runCount >= 1` and
  `lastOutcome = fired`, and the steward session's pass covers all ten
  stages requirements → … → test-requirements in canonical order.
- **AC-004 (FR-003, FR-015; C4):**
  Given `ticker-v` already armed,
  When `ticker-setup.ps1` runs again,
  Then exactly ONE `ticker-v` schedule exists on the service (no
  duplicates) and exactly one steward session drives it.
- **AC-005 (FR-004; C5):**
  Given the unified model adopted,
  When any setup or teardown path runs,
  Then the ten per-stage schedules stay retired (none recreated).

## Open Questions

- **Q-001:** Idempotency mechanism for FR-003 — client-side lookup by
  schedule name `ticker-v` with reuse (update the interval), or
  retire-and-recreate? Decided in design against the actual API surface
  (does schedule creation/listing support name-based lookup and replace?).
- **Q-002:** Steward-session accumulation — should re-arm reuse the existing
  `ticker-v` session (history persists) or create a fresh one each time
  (deterministic context)? Lean fresh; design decides.
- **Q-003:** Does ONE tick's ten-stage walk fit one subagent's context window
  at deep-V scale (many tickets per stage)? If not, what carries the
  stage-cursor state between ticks — flagged for AC-002's coherence check.
- **Q-004:** Are the defaults (`-IntervalMinutes 5` at HEAD - the owner's
  2026-11-16 cadence call; it read 15 when this question was posed -
  `-Cap 4`) the right
  standing values for the unified ticker, or does one ticker walking all ten
  stages want a longer interval? Owner call at arm time.
