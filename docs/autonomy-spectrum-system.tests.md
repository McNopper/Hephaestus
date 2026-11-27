# Integration tests: the autonomy-spectrum system flow — verifies T-101

- **About this document:** the system-level integration test product of
  ticket T-102 (project `hephaestus`, stage `test-system`, epic U-113, basis
  T-042) — the PAIRED VERIFICATION of T-101's system artifact
  `docs/autonomy-spectrum-system.md`. Integration criteria IT-1..IT-7 derive
  1:1 from T-101's three acceptance criteria (AC1's four named flow elements
  expand into IT-1..IT-5; AC2 → IT-6; AC3 → IT-7); every criterion names its
  observable store/git-verifiable oracle and records the observed instance.
  **Read by:** the engine review pass deciding T-101's done/not-done (this
  pair is that decision's evidence); the product-owner agent (the intake
  actor the flow specifies); the downstream legs of the U-113 arc; the
  maintainer. **Related:** `docs/autonomy-spectrum-system.md` (the system
  under test); `AGENTS.md` "The autonomy spectrum (the operating model,
  owner requirement 2026-11-15)" (the governing doctrine);
  `ROADMAP.md` Vision / Product Goal paragraph;
  `.opencode/agent/product-owner.md` "The two dials (goal intake)";
  `docs/design-stage-pipeline.md` §4 (the pump's tick algorithm);
  ticket `T-102` at `.harness/tasks/hephaestus/T-102.md` (this doc's
  recording ticket; the verdict is also recorded there). **Store path
  discipline:** the task store lives at `.harness/tasks/hephaestus/` — the
  post-T-052 location (commit `ebac994`); every store path in this doc cites
  that real path (the sibling `docs/unified-ticker-system.tests.md` still
  carries pre-relocation `.opencode/tasks/` paths — see note N-2).

## How these tests ran

The system under test is not a program — it is a **flow over existing
machinery**: an intake agent, a ticket-file convention, the unchanged stage
tickers, and the review/acceptance gates. These are therefore
**live-observation integration tests**: each criterion is decided by (a)
reading the system artifact against its governing definitions, and (b)
running read-only observations against live reality — store reads via the
tasks MCP on project `hephaestus` (`task_get`, `task_links`,
`task_readiness`), git reads (`ls-files`, `log`, `log --follow`, `status`,
file-existence probes), and plain file reads of the governing docs. Nothing
was compiled, no script was executed, no store write was performed by the
probes (all reads). Run 2026-11-15 ~10:40–10:50Z by the T-102 worker
(`test-system-worker:T-102`).

Verdict summary: **APPROVE-WITH-NOTES** — all seven criteria PASS; three
notes (N-1..N-3) recorded for the reviewer and downstream legs, none
blocking T-101's done.

## IT-1 — The intake actor is the product-owner agent, per the doctrine (T-101 AC1: intake actor)

- **Criterion:** the system artifact names ONE intake actor for the run
  position — the product-owner agent at goal intake — and its procedure
  (classify a whole objective as a GOAL/epic root that is itself `stage:
  requirements`, never stageless, ask when the
  owner leaves the dials unstated with bounded round-trips, record the dials
  on the ticket as run parameters, cite the ticket id back) is consistent
  with `product-owner.md`'s own procedure and `AGENTS.md`'s doctrine; it
  invents no second intake path.
- **Oracle:** textual cross-check of `docs/autonomy-spectrum-system.md`
  (Major Parts "Goal intake", F-1, Technology choices "Intake actor") against
  `.opencode/agent/product-owner.md` "The two dials (goal intake)" and its
  loop, and `AGENTS.md` "The autonomy spectrum" paragraph; file-existence probe on the agent file.
- **PASS.** The doc's intake actor is exactly the agent the doctrine names:
  `AGENTS.md` "The autonomy spectrum" paragraph — "The owner sets each run's position at goal
  intake (U-105) with two dials"; `product-owner.md` L87–92 (quote re-synced to its
  post-T-221 wording) — "When the requirement is a whole objective (the GOAL
  root - a `stage: requirements` ticket, exactly as loop step 1 registers it;
  never a stageless root, which no lane ever claims), record its run position
  … as two dials, asking when the owner leaves them unstated". The doc's
  "bounded round-trips, then register with the open question recorded" is
  the agent file's own loop rule "at most a couple of round-trips,
  then register with the open questions recorded on the ticket", and F-1's
  "cites the ticket id back" is loop step 4 with its store-not-memory proof.
  The doc's "The dials are run parameters recorded on the ticket, not
  acceptance criteria" matches the agent file's closing sentence verbatim in
  substance ("they are recorded on the ticket, not as acceptance
  criteria"). Technology choice "the owner's seat, build-denied"
  matches the agent's frontmatter (`write`/`edit`/`patch`/`bash` all false,
  lines 9–13). The agent file exists and carries the cited section. No second
  intake actor appears anywhere in the doc.

## IT-2 — The run position's only home is the GOAL/epic-root ticket file (T-101 AC1 + AC2: run-position home)

- **Criterion:** the position's home is named as a real, store- and
  git-verifiable surface — the GOAL/epic-root ticket file under the REAL
  store path `.harness/tasks/hephaestus/`, read back with `task_get`,
  auditable in git (D-006), inherited by children through the `epic` link,
  never restated per child, never held in chat memory or a session.
- **Oracle:** file/git probes on the store directory and a live GOAL root;
  live `task_get` + `task_links` reads on project `hephaestus`.
- **PASS.** Every claim checks against live reality:
  - The store directory exists, is git-tracked, and is the REAL path:
    `git ls-files .harness/tasks/hephaestus/` → **315 tracked ticket files**
    at this run's 2026-11-15 read (211 tracked today — the done-sweep moved
    the rest into `_archive/`; re-verified 2026-11-26)
    (D-006 confirmed: `ROADMAP.md`'s **Decisions** section names D-006 as the store being
    git-versioned Markdown). `git log --follow` on the GOAL root's file
    shows real commit history (`ebac994`, `7741667`, `93b412e`) — the
    provenance oracle works.
  - The home shape exists live: `.harness/tasks/hephaestus/U-113.md` is a
    genuine stageless GOAL/epic root (`stage: null`, `epic: null`,
    `basis: null`, title "GOAL: the harness is stable …"), returned by live
    `task_get` with the spectrum position present on the ticket (see IT-6).
  - Inheritance via the `epic` link is live: `task_links` on T-101 returns
    `epic: U-113` (up edge: T-042, done; back edge: T-102, this ticket) —
    children find the root through the link, exactly as the doc specifies.
  - "Never restated per child": T-101's and T-102's frontmatter carry no
    dial record (verified by file read) — the position is read from the
    epic root, not duplicated.

## IT-3 — The pump obeys the horizon without changing the tick (T-101 AC1: both horizon modes' pump behavior)

- **Criterion:** the tick algorithm is specified as identical under both
  horizon modes, parameterized only by stop conditions — run-to-Product-Goal
  recurs until NEEDS-HUMAN or the GOAL root's engine-verdict done; a
  time-boxed run stops launching at its horizon while in-flight tickets run
  to their engine verdicts and the wave still ends in commit + push; the
  review-cadence dial changes nothing inside the pump; a ticket is never
  abandoned mid-flight.
- **Oracle:** textual cross-check of the doc's Major Parts "The pump" and
  F-2 against `docs/design-stage-pipeline.md` §4 (the TICK algorithm) and
  `AGENTS.md`'s autonomy target; consistency with the deployed pump shape.
- **PASS.** Design §4 fixes the procedure: "The ticker has no decisions to
  make — the procedure is fixed, parameterized only by its stage" with
  TICK(stage) = SCAN → BLOCKED → DONE → READY/KICKOFF → CLOSE
  (design-stage-pipeline.md §4 — the ticker-cadence section's tick
  algorithm). The doc's claim — "the horizon
  decides only whether kickoff keeps launching" — is exactly a stop
  condition on step 4 (READY → KICKOFF), not a new procedure; "The cadence
  dial changes nothing inside the pump" holds because T-039's recurrence is
  a division-map pairing (step 3), not pump-internal logic. The time-boxed
  path (steward disarms the schedules at the horizon; pump finishes the
  current tick boundary cleanly; claimed workers run to their engine
  verdicts; no new READY launches; sprint closes with a horizon digest)
  composes from existing surfaces only — schedule pause/disarm and the
  tick boundary as the sole synchronization point (design §4: "the store is
  the only channel, and the tick boundary is the only synchronization
  point") — no invented engine concept; the doc's own trade-off says so
  ("Horizon enforcement lives at the steward/schedule level, not the
  engine"). The run-to-goal stop set (NEEDS-HUMAN escalations, or the GOAL
  root done by engine verdict) matches `AGENTS.md`'s autonomy target and
  engine-verdict doctrine verbatim. Deployment-shape note: §4's per-stage
  tickers and the deployed unified `ticker-v` schedule (one steward walking
  all stages) both satisfy the doc's pump contract, because the contract
  parameterizes launching, not the pump's shape — the doc cites §4 for the
  algorithm, which is deployment-neutral. No parallel pump is invented.

## IT-4 — The gates obey the cadence: T-041 release floor, T-039 non-blocking recurrence (T-101 AC1: review-cadence gating)

- **Criterion:** release and acceptance gating match the standing rules the
  doc cites — a completed wave always ends in commit + push; the version is
  cut only by a human review that accepts (T-041, via
  `eclipse/releng/release.ps1`, tag per D-002); every successful
  wave/release grows a fresh NON-BLOCKING test-requirements acceptance
  ticket (T-039) at every cadence setting; the dial floor (no setting
  releases without human review; NEEDS-HUMAN as the escalation threshold)
  is fixed.
- **Oracle:** cross-check of the doc's dial section and F-3 against
  `AGENTS.md` "The autonomy spectrum" paragraph and `ROADMAP.md` L18–27; the standing rules' carrier
  tickets in the store; file-existence probe on the release script.
- **PASS.** All carriers are real and done: `.harness/tasks/hephaestus/T-041.md`
  (status `done`, epic U-113, engine verdict PASS at 09:03:47.272Z — the
  refined cadence "human review → release; otherwise commit+push", artifact
  `ROADMAP.md`) and `.harness/tasks/hephaestus/T-039.md` (status `done`,
  epic U-113, engine verdict PASS — "RECURRENCE, NON-BLOCKING … every
  successful wave/release produces a fresh test-requirements acceptance
  ticket … never gates"). The doc's wave-close interface names
  `eclipse/releng/release.ps1` (tag per D-002, `-SNAPSHOT` bump) — the file
  exists (`Test-Path` → True) and `ROADMAP.md`'s release-path bullet (L58)
  and its T-041 standing-rule paragraph confirm the D-002
  tagging rule. The doc's fixed floor ("no setting releases without a human
  review (T-041 is a standing rule, not a dial value), and NEEDS-HUMAN is
  the escalation threshold at every setting") is `AGENTS.md` "The autonomy spectrum"
  paragraph (its T-041 review-cuts-release line + its NEEDS-HUMAN threshold line)
  restated, not new policy. F-3's "at every cadence setting" for the T-039
  recurrence matches T-039's own non-blocking rule. `ROADMAP.md` L23–27
  (Product Goal) is fully covered by the doc's two dials: horizon
  (time-boxed vs run-to-goal) and review cadence.

## IT-5 — Every named surface is real; no invented parallel structure (T-101 AC1: citations + coherence with AGENTS.md and the pipeline design)

- **Criterion:** the artifact cites the three governing sections by name and
  every store/git surface it names is real; the flow adds no machinery
  parallel to `AGENTS.md` or `docs/design-stage-pipeline.md` — no new store
  schema, no new engine concept, no new stage, no second store path.
- **Oracle:** file-existence + section-content probes on every cited path;
  tool-surface probes on the named verbs; the doc's own trade-offs checked
  against the doctrine's defaults.
- **PASS.** Citations verified in place: `AGENTS.md` "The autonomy spectrum
  (the operating model, owner requirement 2026-11-15)" exists (named-paragraph cite - the old L156-168 span went off-by-one after T-231's block rewrite, so no line number is kept; re-verified 2026-11-26);
  `ROADMAP.md` Vision / Product Goal paragraph exists (section `## Vision`, the **Product Goal.** paragraph - re-verified 2026-11-26: the span still reads L18-27, but the cite is now section-named so a future ROADMAP edit cannot strand it);
  `product-owner.md` "The two dials (goal intake)" exists (L87–97);
  `docs/design-stage-pipeline.md` §4 (the ticker-cadence section) exists. Named surfaces
  verified real: `.opencode/agent/product-owner.md` (exists, build-denied
  frontmatter); `.harness/tasks/hephaestus/<GOAL-id>.md` (exists, 315
  tracked files at this run's 2026-11-15 read — 211 tracked today, re-verified
  2026-11-26); `task_get` / `task_board` / `task_readiness` /
  `task_links` (the read surfaces — exercised LIVE by this verification,
  see Provenance); `task_create` / `task_update` / `task_set_blocked` (the
  write surfaces — the store's own verbs, the same MCP namespace this
  worker operates through, `task_set_blocked` being the blocked-state verb
  of `AGENTS.md`'s state machine); `eclipse/releng/release.ps1` (exists);
  git audit via `git log` on ticket files (works, IT-2). No invented
  structure: the doc's trade-offs explicitly refuse the two temptations —
  "Dials are a ticket-record convention, not store schema" (the store gains
  no field) and "Both defaults are the doctrine's own defaults, not new
  policy" (run-to-Product-Goal is `AGENTS.md`'s general rule; releases-only
  on human review is T-041). No new stage, no parallel store, no second
  position home appears anywhere in the doc. The store path it cites is the
  real one (`.harness/tasks/hephaestus/`).

## IT-6 — The run-position oracle is store/git-verifiable, not memory (T-101 AC2)

- **Criterion:** the doc's read-back oracle actually works: the run position
  can be observed on the GOAL/epic-root ticket through `task_get` and
  audited in git, from a session that holds no dial state of its own —
  the store, not memory, is the proof.
- **Oracle:** LIVE `task_get` on U-113 (project `hephaestus`) + `git log`
  on its store file — run by this verification session, which was spawned
  with no dial knowledge and derived everything it states from these reads.
- **PASS.** Live `task_get` (project `hephaestus`, ticket `U-113`) returns
  the GOAL root "GOAL: the harness is stable - all relevant tickets done,
  store doctor-clean" whose ticket file carries the spectrum position:
  owner-directive comment 2026-11-15T07:40:18.594Z — "Autonomy spectrum
  added to the goal's scope (T-042): the harness runs until the Product
  Goal; releases happen when a human reviews and accepts; the two extremes
  … are the dial ends, set per run by goal horizon + review cadence" — and
  07:38:13.144Z for the T-041 cadence ruleset. Git audit works:
  `git log --follow -- .harness/tasks/hephaestus/U-113.md` → commits
  `ebac994` (store relocation T-052), `7741667`, `93b412e` — real,
  queryable provenance. The doc's F-1 oracle ("`task_get` shows the dial
  record on the GOAL ticket; `git log` on the ticket file shows when it was
  written") therefore holds as stated: both halves were executed live
  during this test run and decided the criterion. Note N-1 records the one
  shape difference on this particular goal (comments vs a labeled dial
  record in the description).

## IT-7 — Artifact recorded before in-review (T-101 AC3)

- **Criterion:** the store shows the system child's artifact event
  preceding its in-review transition, with the worker as actor — the pair's
  own discipline holding on the ticket under test.
- **Oracle:** T-101's history events via LIVE `task_get` on project
  `hephaestus`.
- **PASS.** Live `task_get` T-101: `artifact:doc:docs/autonomy-spectrum-system.md`
  at **10:37:04.348Z** (by `system-worker:T-101`) precedes `updated:status`
  (in-review) at **10:37:27.803Z**. The sibling chain shows the same
  discipline (T-041: artifact `ROADMAP.md` 08:31:29.618Z → status
  08:31:29.662Z). This ticket's own record follows it too: this doc is
  being recorded as T-102's artifact before T-102 moves to `in-review`.

## Coherence judgment (the ONE-flow question)

Does the flow hold as one system — intake → store → pump → gates?

- **Intake → store (I: `task_create`/`task_update`):** coherent. One actor,
  one home, one record shape (two labeled dials as ticket text); the
  bounded-round-trip rule is the agent's own loop rule, not a new protocol.
- **Store → pump (`task_get`/`task_board`/`task_readiness` + the `epic`
  link):** coherent — and live-proven: `task_links` resolves T-101 → epic
  U-113, `task_get` returns the root's position, `task_readiness` scans the
  stage rows (T-101 `system`, T-102 `test-system`, U-113 `stage: null`) —
  the pump's observation step reads exactly what intake wrote, with no
  per-child restatement anywhere in the chain.
- **Pump → gates:** coherent. The horizon touches only kickoff's launch
  decision; the cadence touches only the release trigger and the T-039
  recurrence; engine verdicts gate every ticket's `done` unchanged; the
  wave always ends in commit + push (T-041's floor).
- **The non-blocking guarantee (F-4):** it follows structurally, not by
  assertion — the dials are advisory defaults read at kickoff, so no dial
  state can precondition a claim, a verdict, a wave close, or a commit; the
  safe-end defaults are the doctrine's own defaults.
- **Doctrine scan:** no statement of the doc contradicts `AGENTS.md`'s
  "The autonomy spectrum" paragraph (name-cited post-T-231: the old L156-168
  span went off-by-one, so no line number is kept), `ROADMAP.md`'s Vision /
  Product Goal paragraph (was L18-27, now section-named - it still reads L18-27
  as of 2026-11-26), `product-owner.md` L87–97, or design §4;
  every doc element anchors to one of them.

**Verdict: APPROVE-WITH-NOTES.** T-101's artifact satisfies all three of its
acceptance criteria (IT-1/IT-3/IT-4/IT-5 → AC1; IT-2/IT-6 → AC2; IT-7 →
AC3) and is coherent as one flow. The notes below do not block T-101's
done.

## Notes for the reviewer and the downstream legs

- **N-1 (U-113's position predates the dial-record convention):** the GOAL
  root carries its run position as two owner-directive COMMENTS
  (07:38:13Z T-041 cadence, 07:40:18Z T-042 spectrum), not as the labeled
  dial record in the description the doc specifies — U-113 was registered
  at 07:30:51Z, before T-042 produced the intake procedure. The position is
  store-verifiable all the same (the comments live in the same ticket file,
  git-versioned), so AC2 holds; the labeled-record convention applies
  prospectively to goals registered after T-042. Normalizing U-113 itself
  is a one-comment intake act, out of this pair's lane.
- **N-2 (store-path hygiene):** the sibling pattern doc
  `docs/unified-ticker-system.tests.md` cites the pre-relocation store path
  (`.opencode/tasks/hephaestus/_meta.json`). The store was relocated to
  `.harness/tasks/` by T-052 (commit `ebac994` — "the store is data, not
  config"). This doc cites the real path everywhere. Downstream legs that
  copy store paths from the sibling should use
  `.harness/tasks/hephaestus/`.
- **N-3 (provenance of the system doc itself):** at verification time
  `docs/autonomy-spectrum-system.md` is untracked (`git status` → `??`) —
  fleet worktree changes commit at the engine's merge-back. No criterion
  depends on the system doc's own git history (all git oracles run against
  the store, which is committed), so nothing here is undecidable; after
  merge-back the doc's own provenance becomes `git log`-verifiable too.

## Provenance of the live evidence (2026-11-15, ~10:40–10:50Z)

- Store (tasks MCP, project `hephaestus`, all read-only): `task_get` T-101
  (status `in-review`; artifact event 10:37:04.348Z by
  `system-worker:T-101`; status event 10:37:27.803Z); `task_get` U-113
  (GOAL root, `stage: null`, spectrum + cadence owner-directives of
  07:40:18.594Z / 07:38:13.144Z); `task_links` T-101 (up: T-042 done;
  back: T-102; epic: U-113); `task_readiness` (rows for T-101/T-102/
  T-039/T-041/U-113 with their stages).
- Git (repo root): `git ls-files .harness/tasks/hephaestus/` → 315 files;
  `git log --follow --oneline` on `.harness/tasks/hephaestus/U-113.md` →
  `ebac994`, `7741667`, `93b412e`; `git status --porcelain` →
  `?? docs/autonomy-spectrum-system.md`; `Test-Path
  eclipse/releng/release.ps1` → True.
- Text (read, not executed): `docs/autonomy-spectrum-system.md`;
  `AGENTS.md` "The autonomy spectrum" paragraph; `ROADMAP.md`'s **Vision**
  section, its **Decisions** paragraph (D-006) and its release-path bullet;
  `.opencode/agent/product-owner.md` L1–97; `docs/design-stage-pipeline.md`
  §1–§6 (the **The tick algorithm** subsection of §4); store files
  `.harness/tasks/hephaestus/U-113.md`, `T-101.md`, `T-102.md`, `T-041.md`,
  `T-039.md`; pattern reference `docs/unified-ticker-system.tests.md`.
