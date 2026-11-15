# Integration tests: the unified V-ticker system model — verifies T-055

- **About this document:** the system-level integration test product of
  ticket T-056 (project `hephaestus`, stage `test-system`, epic U-112, basis
  T-035) — the PAIRED VERIFICATION of T-055's system artifact
  `docs/unified-ticker-system.md`. Integration criteria (IT-1..IT-10) derive
  1:1 from T-055's four acceptance criteria and the binding FRs of
  `docs/unified-ticker-requirements.md` (FR-001/002/003/005/007/010/011/012/
  015, with FR-004/006/008/009/013/014 as cited support); each criterion
  names its observable oracle. **Read by:** the engine review pass deciding
  T-055's done/not-done; the design/implementation legs of the ticker-v arc
  (Routes A/B/C consumers); the maintainer. **Related:**
  `docs/unified-ticker-system.md` (the system under test),
  `docs/unified-ticker-requirements.md` (the governing requirements),
  `.opencode/skills/stage-pipeline/ticker-setup.ps1` + `SKILL.md` (the
  prototype the model must match), `docs/design-stage-pipeline.md`
  (superseded-on-ten→one context), ticket `T-056` (this doc's recording
  ticket; the verdict is also recorded there).

## How these tests ran

The system under test is not a program — it is the task store plus one
schedule, one steward session and the spawned subagents around it. These are
therefore **live-observation integration tests**: each criterion is decided
by (a) reading the system artifact against its governing FRs, and (b) where
cheap and safe, running read-only observations against live reality —
`task_get` / `task_readiness` store reads, the store's sprint record
(`.opencode/tasks/hephaestus/_meta.json`), and the opencode service's
schedule/session listing (`GET /api/experimental/schedule?sessionID=…`,
`GET /api/session?directory=…`, read-only GETs). Nothing was compiled; no
script was executed (the prototype was read). Run 2026-11-15 ~09:00–09:15Z
by the T-056 worker (`ticker-v:T-056`).

Verdict summary: **APPROVE-WITH-NOTES** — all ten criteria PASS; six notes
(N-1..N-6) recorded for the reviewer and the downstream legs.

## IT-1 — One schedule × one steward, by name (T-055 AC1; FR-001, FR-002; INV-1/INV-2)

- **Criterion:** the model specifies exactly ONE `ticker-v` schedule and ONE
  steward session as the system's identity, the schedule driving the steward
  with the one-pass tick prompt; arming, firing, health, identity, lifetime
  and pause/disarm are all specified as one interaction (I-1).
- **Oracle:** the system doc's I-1/INV-1/INV-2 text against requirements
  FR-001/FR-002; live service probes (schedule record shape; steward
  sessions' parentage).
- **PASS.** I-1 covers arming (POST shape matches the script's), firing
  (one complete pass per turn), health (NFR-OPER-001: ONE record,
  `runCount`/`lastOutcome`), identity (the name as the exactly-one key),
  lifetime (dies with the service; inert session records persist), and
  pause/disarm (W1 pause/resume/delete converging on FR-015's one-command
  resume). Live: three sessions titled `ticker-v` exist, all children of the
  repo-rooted root session `ses_f37a21973ffeZeCwmRTC7KSOvY` ("Hephaestus")
  — C-C (config inheritance) confirmed live; the script's tick prompt
  (`ticker-setup.ps1` L83–99) matches FR-002's one-pass description and the
  SKILL.md procedure step for step.

## IT-2 — Canonical ten-stage order, consistent everywhere (T-055 AC1; FR-005)

- **Criterion:** ONE pass over ALL ten stages in canonical order, stated
  identically in requirements, system doc, script prompt and skill.
- **Oracle:** four-source textual cross-check; live division evidence for
  order (stages never call each other; division writes the links).
- **PASS.** requirements → system → architecture → design → implementation
  → test-implementation → test-design → test-architecture → test-system →
  test-requirements is identical in FR-005, the system doc's "The tick"
  section, the script's `$stages` (L82) and SKILL.md (L17–20). Live: T-035
  (requirements) reached done 08:44:48.768Z and its division children T-055
  (system) and T-056 (paired test-system) were created at 08:44:48.835/.901Z
  — same epic U-112, basis = the done ticket; the implementation stage's
  tickets (T-054/T-057) run concurrently with test-system work (readiness
  rows), exactly the async-pipeline shape the tick section specifies.

## IT-3 — Plan-then-claim (T-055 AC1; FR-007; INV-4)

- **Criterion:** no claim without a prior `task_plan_sprint` into
  `ticker-v`; claims take `sprint-backlog` only; division-born tickets start
  in `product-backlog` (hence the ordering's necessity is stated, not just
  asserted).
- **Oracle:** system doc I-3 (write path) / I-4 (defer-at-cap) / INV-4
  against FR-007; live store history on this wave's own tickets.
- **PASS.** The doc states the ordering as an invariant and gives the
  mechanism reason (planning without planning first makes division-born
  tickets unclaimable). Live: T-055 planned 08:50:53.669Z → claimed
  08:50:53.721Z; T-056 planned 08:55:17.074Z → claimed 09:02:28.520Z
  (plan event precedes claim event on both); the board shows unclaimed
  `sprint-backlog` rows (the deferred state is a store-observable shape).

## IT-4 — Global cap by construction; count-before-spawn; defer-at-cap (T-055 AC1; FR-010, FR-011; INV-3)

- **Criterion:** the cap is global because exactly one steward exists (one
  counter owner — US-002); every spawn is counted beforehand, workers AND
  reviewers both count; at cap the kickoff is deferred as planned-but-not-
  claimed; the value is arm-time data delivered in the prompt; the
  steward-instance-locality hazard on re-arm is carried, not ignored.
- **Oracle:** system doc I-4/INV-3/S-5 against FR-010/FR-011 and
  NFR-COST-001; the script's prompt interpolation; store state for the
  deferred shape.
- **PASS.** I-4 explicitly counts reviewers (the cost bound is about
  concurrent subagents), stops counting a child at its delivery, and S-5
  makes the cap global across ticks (carry-over workers reduce free slots).
  The script interpolates `$Cap` and "check your running subagents first —
  never exceed it" into the prompt (L94–96), i.e. arm-time delivery (C-F).
  S-3 carries the re-arm hazard (fresh steward counts at zero) into Route A
  as a constraint on Q-002 — the one place the invariant could silently
  break is owned. The live counter itself is steward-context state (by
  design, remember-vs-forget); its store-observable shadow is the deferred
  row (IT-3's unclaimed sprint-backlog shape).

## IT-5 — ONE rolling sprint record, U-108 reopen, in-flight protection (T-055 AC1; FR-012, FR-013; INV-5)

- **Criterion:** one rolling record `ticker-v` reused across ticks (never a
  record per tick or per stage); reopen semantics keep it rolling; it never
  closes while work is claimed/in flight.
- **Oracle:** the store's sprint records (`.opencode/tasks/hephaestus/
  _meta.json`) and the record's members' statuses — live.
- **PASS.** Exactly one `ticker-v` record exists: `status: active`,
  `created_at: 2026-11-15T07:53:53.455Z`, no closed timestamp — while its
  members are demonstrably in flight (T-055 `in-review` with artifact,
  T-056/T-054/T-057 `in-progress`). The record has been rolling since 07:53
  across multiple passes without closing: S-6 holds live, not just on paper.
  I-5's reopen lifecycle (plan reopens a closed record; plan into an active
  one is a goal update — the record's goal text changed with this wave's
  planning) matches the store's observed behavior. Note N-6 below covers the
  one historical per-stage-era record.

## IT-6 — Exactly-one survives re-arm (T-055 AC1+AC3; FR-003; INV-1/INV-2 + Route A)

- **Criterion:** re-arming while armed MUST leave exactly one schedule and
  one driving steward; the prototype's unconditional POST is identified as
  the delta, and both the mechanism decision (Q-001) and the steward
  reuse-vs-fresh decision with its cap-drain constraint (Q-002, S-3) are
  routed to an owning ticket.
- **Oracle:** system doc I-1 (identity), S-3, Route A against requirements
  delta #1; the script text (live hazard confirmed); live steward-session
  accumulation.
- **PASS.** The doc states the required outcome, marks the prototype's
  present violation honestly (it POSTs unconditionally — script L79/L106
  have no lookup and no retire), and routes the fix with its constraint.
  Live confirms the delta is real and the hazard analysis correct: three
  `ticker-v` steward session records have accumulated from today's
  re-arms/steward recreations (758 min / 19 min / 3 min old) — Q-002's
  accumulation concern made visible; and the duplicate hazard is
  service-lifetime-scoped (after a restart zero schedules exist, so a
  post-restart re-run arms exactly one — the mid-life re-run is the
  duplicating case). Both cases are covered by the doc's lifetime +
  re-arm model.

## IT-7 — Resume is one command; the store is the only memory (T-055 AC3; FR-015; INV-6)

- **Criterion:** the remember-vs-forget split is total; a shutdown loses
  nothing that cannot be re-derived; resume = re-running
  `ticker-setup.ps1` alone; the first pass reconciles from store state
  alone (S-2/S-4).
- **Oracle:** the doc's split table and S-2 reconciliation contract against
  a LIVE service-vs-store differential — the strongest live evidence this
  pass could obtain.
- **PASS.** At verification time the running service held **zero
  schedules** (enumerated: all 50 repo-directory sessions, each
  `GET /api/experimental/schedule?sessionID=…` returned `{data: []}`) while
  the store held everything: the active rolling record (IT-5), in-flight
  claims (T-056 `in-progress`, assignee `ticker-v:T-056`), in-review
  hand-offs with recorded artifacts (T-055), all epic/basis links, artifact
  indexes and history. This pass is itself the S-2 survived-work case
  running to completion — a worker finishing its claim after the schedule
  that fired its steward died. Nothing in the runtime's loss needs store
  repair; the model's reconciliation duties (in-review → verdict step;
  claimed → orphan-claim check; record stays active until drained) land on
  the first post-re-arm pass, exactly as S-2 step 1–3 prescribes. The
  orphaned-claim *detection* mechanism is honestly deferred to design
  ("bounded staleness vs session-existence probe") with the system contract
  fixed (release-and-requeue, never silently stuck, never double-spawned) —
  a deferral, not a gap.

## IT-8 — The per-stage schedules stay retired (T-055 AC3; FR-004; INV-7)

- **Criterion:** no setup or teardown path recreates the ten per-stage
  schedules; observability comes from ONE record, not ten.
- **Oracle:** live schedule enumeration across all sessions (any
  `ticker-*` schedule anywhere would be a FAIL); the script as the only
  arming path.
- **PASS.** Zero schedules of any name exist on the running service; the
  script arms only the unified `ticker-v`; Route A's guard removes the only
  duplicate-create hazard. NFR-OPER-001 is satisfied in shape (one record to
  read when armed). Note N-5 records the armed-state precondition of that
  oracle.

## IT-9 — Every prototype delta has a named owning route (T-055 AC2)

- **Criterion:** all four deltas recorded by the requirements stage ride a
  named route with an owning leg and file lane; open questions ride with
  them; no delta unowned.
- **Oracle:** requirements §Prototype deltas (deltas 1–4) vs the doc's
  Routes A/B/C; the prototype files' actual text (deltas verified real).
- **PASS.** Delta #1 (re-arm guard) and delta #3 (`$Password` vs `$Token` —
  script L25 tests the never-defined `$Password`, param is `$Token` at L18,
  so explicit `-BaseUrl`/`-Token` overrides are always clobbered) share
  Route A: one implementation-leg ticket, one file lane
  (`ticker-setup.ps1`), carrying Q-001 and Q-002 with S-3's constraint —
  correct chunking (same file, same behavior). Delta #2 (SKILL.md L126–127
  still says "re-run `ticker-setup.ps1` with the same `-Stages`"; the
  unified script has no `-Stages` param) rides Route B, the skill lane.
  Delta #4 rides Route C (the `subagent_depth >= 2` constraint stays
  documented, setup-local per D-004) with the prose-coherence half assigned
  to the lanes that own AGENTS.md / design-stage-pipeline /
  requirements-stage-pipeline. Q-003 (one-tick context fit) is explicitly
  flagged with the system's position (the contract never depends on one
  pass fitting the window; fallback is a store-carried stage cursor, a
  design decision) — coherent with the store-as-only-memory model, and the
  doc routes the flag to this paired test, which hereby records it: no
  mechanism needed at system level; the design leg owns the cursor
  fallback only if a pass ever stops fitting.

## IT-10 — Artifact recorded before in-review (T-055 AC4)

- **Criterion:** the store shows the artifact event preceding the
  in-review transition, with the worker as actor.
- **Oracle:** T-055's history events (`task_get`) — live.
- **PASS.** T-055: `artifact:file:docs/unified-ticker-system.md` at
  08:56:56.608Z (by `ticker-v:T-055`) precedes `updated:status`
  (in-review) at 08:57:00.389Z. The sibling chain shows the same order
  (T-035: artifact 08:25:32.655Z → status 08:25:37.970Z). This ticket's own
  record follows the same discipline: this doc is the recorded product and
  the ticket moves to `in-review` with it.

## Coherence judgment (the ONE-system question)

Does the model hold as one system — schedule × session × cap × record?

- **Schedule × session (I-1/I-2):** coherent. One name as identity key;
  firing = one complete pass; session tree three levels deep with C-B/C-C
  as the documented config preconditions; worker/reviewer lifetimes carry
  their store footprints only. The current live state (zero schedules, one
  steward pass driving, two inert steward records) sits inside the model's
  envelope — I-1's lifetime section predicts exactly this shape.
- **Cap (I-4):** coherent with the one-steward topology — global by
  construction, and the one scenario that could break it (re-arm replaces
  the steward mid-flight) is explicitly carried into Route A as a hard
  constraint (S-3), not waved away.
- **Record (I-5):** coherent with plan-then-claim — the record exists
  BECAUSE claims need sprint membership, the reopen lifecycle makes one id
  roll across ticks, and in-flight protection (FR-013) plus the deferred
  claim shape (planned-not-claimed) close the loop with the cap (IT-3/IT-4).
- **Remember-vs-forget:** the split is total and decision-complete — every
  next-tick decision is made from store state alone. Live-proven today in
  the S-4 direction (service forgot everything; the store lost nothing).
- **Scenarios S-1..S-6:** each walks to the invariants; none contradicts a
  binding FR. S-2's detection deferral and S-3's Q-002 constraint are
  honest open points with owners; S-5/S-6 were additionally observed live
  (deferred rows; record active under in-flight work).
- **Invariant ↔ FR scan:** no invariant contradicts a binding FR; every
  invariant table row anchors to at least one binding FR and names its
  preservation mechanism; every FR in the binding set is stated somewhere
  in the doc (the doc's own traceability table checks out against the
  requirements text).

**Verdict: APPROVE-WITH-NOTES.** T-055's artifact satisfies all four of its
acceptance criteria (IT-1/IT-2/IT-3/IT-4/IT-5 → AC1; IT-9 → AC2; IT-6/IT-7/
IT-8 → AC3; IT-10 → AC4) and is coherent as one system. The notes below do
not block T-055's done.

## Notes for the reviewer and the downstream legs

- **N-1 (live S-4 state):** at verification time no schedule exists on the
  service (post-restart, pre-resume). INV-1/NFR-OPER-001 are armed-state
  invariants and are vacuous until re-arm; the first post-re-arm pass must
  run S-2's reconciliation over the live in-flight set (in-review tickets
  → verdict step — including T-055 itself once this verification lands;
  claimed tickets → orphan-claim check; record stays active until drained).
- **N-2 (schedule listing is per-session):** the W1 list endpoint requires
  `sessionID` (observed live: omitting it → 400 `Missing key
  ["sessionID"]`), so Route A's "look up the existing `ticker-v` schedule
  by name" is realizable only as (a) a lookup scoped to the steward session
  (if Q-002 chooses reuse) or (b) cross-session enumeration
  (`GET /api/session` + per-session schedule GETs — what this verification
  did). The design leg should pin Q-001's mechanism against this shape; a
  name lookup against only the NEW session's list would miss an OLD
  session's schedule.
- **N-3 (SKILL.md step 6 vs FR-013):** the tick procedure's close step
  ("reopen … and close it") reads as unconditional, while FR-013/I-5 gate
  closing on nothing-in-flight; SKILL.md's own field-observation bullet
  (rolling records stay open while work is in flight) states the correct
  rule. Suggest folding the step-6 wording fix into Route B's skill-lane
  ticket scope (it already owns SKILL.md for delta #2).
- **N-4 (INV-1 phrasing):** "exactly ONE `ticker-v` schedule exists on the
  running service" is the armed-state invariant; between a restart and the
  re-arm the service legitimately holds zero (FR-015's premise). One
  clarifying sentence in the doc would prevent a false audit failure.
- **N-5 (health oracle precondition):** `runCount`/`lastOutcome` are
  observable only while a schedule exists. AC-003's proof (fires, walks all
  ten stages, `runCount >= 1`, `lastOutcome = fired`) belongs to the
  verification leg AFTER the next re-arm — not gated by T-055's done.
- **N-6 (inert history):** the per-stage-era sprint record
  `ticker-requirements` remains in the store's sprint map. It is a record,
  not a schedule; no arming path recreates it (FR-004 untouched); candidate
  for archive in a future cleanup close.

## Provenance of the live evidence (2026-11-15, ~09:00–09:15Z)

- Store: `task_get` T-035/T-055/T-056; `task_readiness` (T-055 RUNNING
  in-review, T-056 RUNNING in-progress, deferred sprint-backlog rows,
  T-002 STALE on T-055's inputs-changed — the STALE oracle observed
  working); sprint records from `_meta.json` (one `ticker-v`, active;
  per-stage-era `ticker-requirements` inert).
- Service (read-only GETs, url+token from `service.json`):
  `GET /api/session?directory=<repo>` → 50 sessions; three titled
  `ticker-v`, all children of repo-rooted root `ses_f37a21973ffeZeCwmRTC7K
  SOvY`; `GET /api/experimental/schedule?sessionID=…` → `{data: []}` for
  every session (zero schedules anywhere).
- Text: `docs/unified-ticker-system.md`, `docs/unified-ticker-requirements.md`,
  `.opencode/skills/stage-pipeline/ticker-setup.ps1`,
  `.opencode/skills/stage-pipeline/SKILL.md`,
  `docs/design-stage-pipeline.md` — read, not executed.
