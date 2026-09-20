# Requirements: B-007 — Stage-aware acceptance: the auto-accept reviewer must judge definition-leg artifacts, not just code diffs

## About this document
- **Kind:** `doc` / requirements definition (stage artifact of ticket B-007, stage `requirements`).
- **Read by:** the downstream V-chain stages of B-007 (`system` → `architecture` → `design` → `implementation`) and the verification leg (`test-*`, ending at `test-requirements`); the reviewer agent at acceptance; any agent asking "what must stage-aware acceptance do?".
- **Written by:** the requirements-stage worker (role `developer`, skill `software-requirements`).
- **Related:** ticket B-007 and its live-extension comment of 2026-09-19 (the settle check has the same bias); U-021 (reviewer auto-accept + auto-advance — the machinery this feature corrects); U-029 (stage pass-through — the paired feature; its definition-leg runs are exactly the ones falsely rejected); U-026 (flow trace; its requirements-stage run was the live rejection that opened this bug); U-023 (clarification loop doctrine: doubt round-trips to the originator, round-trip limits escalate to the human); U-027 ("uniform across every consumer" doctrine). Current-behavior baseline: `TaskFleet.enforceAcPaths` / `applyVerdict`, `ReviewPrompt`, `ReviewVerdict` in `eclipse/bundles/com.opencode.ide.fleet`, and the zero-changes merge refusal in `GitWorktreeManager.mergeBackGuarded` (`eclipse/bundles/com.opencode.ide.git`). This file is the **what and why** — tool shape, storage and code are the next stages' work.

**Terminology guard (read this first).** The **definition leg** of the V is the stages `requirements`, `system`, `architecture`, `design`; the **implementation stage** is `implementation`; the **verification leg** is `test-implementation`, `test-design`, `test-architecture`, `test-system`, `test-requirements`. **Acceptance evidence** is whatever proves a stage's acceptance criteria are met — it is stage-shaped: a requirements stage's correct output is a requirements doc plus ticket/store updates, not a code diff. Three engine checkpoints consume evidence today and must all become stage-aware: the **settle check** (zero-changes merge refusal: "worker produced no changes"), the **merge gate** (`enforceAcPaths`: "analysis-only run: no acceptance-criterion path in the diff"), and the **reviewer gate** (U-021 `ReviewPrompt`/`ReviewVerdict`/`applyVerdict`).

## Goal

The autonomous acceptance machinery (U-021) currently judges every run with a code-diff bias: it expects acceptance-criterion file paths in the branch diff and treats anything else as an analysis-only failure. That is correct for the implementation stage and **false for the entire definition leg** — live on 2026-09-19, U-026's requirements-stage run was rejected although its diff (ticket + requirements doc) was exactly the correct stage-1 output, and B-005/U-026 also hit the settle check because their store-side work landed through the `task_*` tools in the main store, not as worktree edits. This feature makes acceptance **stage-aware**: each V stage declares what counts as evidence, all three checkpoints apply that matrix, and reviewer doubt round-trips to the originator once before anything is blocked — so `blocked` on a ticket always means *needs-a-human*, never *an engine heuristic misfired*.

## Users
- **Definition-leg stage workers (requirements/system/architecture/design)** — produce docs, ticket-body/AC updates and store records as their correct output; must not be rejected for not producing code.
- **Reviewer agent (U-021 autonomous acceptance)** — needs a per-stage evidence matrix to judge against, instead of one code-diff-shaped rule.
- **Engine / merge machinery (settle check, merge gate, auto-dispatch, waves)** — must stop manufacturing false `blocked` tickets that park the autonomous loop.
- **PM agent / wave loop (U-022)** — drains waves unattended; every false rejection is a NEEDS-HUMAN row that stalls the pump for nothing.
- **Human Product Owner** — wants `blocked` to be a reliable escalation signal: when a ticket is blocked, a human is genuinely required.

## User Stories
- **US-001:** As a definition-leg stage worker, I want my ticket/doc/store artifacts accepted as valid evidence, so that a correct requirements or architecture run is not rejected for lacking a code diff.
- **US-002:** As the reviewer agent, I want the ticket's stage to tell me which evidence classes to expect, so that I judge each stage by its own contract.
- **US-003:** As the PM running waves, I want reviewer doubt to round-trip to the originator for one retry before blocking, so that `blocked` always means needs-a-human and the loop keeps draining.
- **US-004:** As the human Product Owner, I want the per-stage evidence matrix pinned by tests — including the U-026/B-005 regression cases — so that this class of false rejection cannot silently return.

## Functional Requirements

*System names used in EARS below:* **the acceptance machinery** = the three checkpoints together (settle check, merge gate, reviewer gate) plus the store transitions they trigger; **the evidence matrix** = the per-stage mapping from V stage to the evidence classes that satisfy acceptance; **the originator** = the stage worker (or its stage) whose run is being judged; **the store** = the Markdown task store, including ticket body, frontmatter, comments and recorded artifacts.

### The per-stage evidence matrix (the ticket's AC 1)

- **FR-001 (ubiquitous, invariant):** THE acceptance machinery SHALL evaluate a staged run against the evidence matrix keyed by the ticket's `stage` field — never against a single code-diff-only rule. An unstaged ticket keeps today's behavior (code-diff expectation) as the default row.
- **FR-002 (ubiquitous, definition leg):** WHILE a ticket sits at `requirements`, `system`, `architecture` or `design`, THE evidence matrix SHALL accept as valid acceptance evidence: (a) ticket-body or acceptance-criteria updates in the store, (b) artifacts recorded on the ticket of kind `doc`, `path` or `url`, and (c) doc/store paths in the merged diff (the stage's named artifact paths, `docs/**`, `.opencode/tasks/**`) — code changes are permitted but never *required* on the definition leg.
- **FR-003 (ubiquitous, implementation):** WHILE a ticket sits at `implementation`, THE evidence matrix SHALL expect code plus tests: at least one acceptance-criterion-named path in the diff (the existing AC-path rule) and test changes accompanying the implementation — today's merge-gate behavior, unchanged in substance.
- **FR-004 (ubiquitous, verification leg):** WHILE a ticket sits at a `test-*` stage, THE evidence matrix SHALL expect verification artifacts — tests and, where the stage uses them, golden/reference outputs — as the acceptance evidence; a verification-stage run that changes only implementation code without tests is suspect, not accepted.
- **FR-005 (ubiquitous, uniformity):** THE evidence matrix SHALL be the single source consulted by all three checkpoints (the U-027 uniformity doctrine): the settle check, the merge gate and the reviewer prompt/verdict all derive their expectations from the same per-stage mapping — no checkpoint may invent its own notion of "produced work".

### No false rejections on the definition leg (the ticket's AC 2 + the live extension)

- **FR-006 (unwanted):** IF a definition-leg run's evidence satisfies FR-002 entirely through store-side changes (ticket/doc updates routed through the `task_*` tools) with an empty branch diff, THEN the settle check SHALL count those store-side changes as produced work and SHALL NOT refuse the merge with "worker produced no changes".
- **FR-007 (unwanted):** IF a definition-leg run's merged diff touches only the ticket file, the store, and/or the stage's doc paths, THEN the merge gate SHALL NOT fire "analysis-only run: no acceptance-criterion path in the diff" — those paths are the stage's expected output, and the gate's expectation list comes from the matrix row, not from a code-path heuristic alone.
- **FR-008 (event-driven, regression pinning):** WHEN a run materially identical to the 2026-09-19 U-026/B-005 cases is judged (requirements stage; diff touches the ticket and a `docs/requirements/` doc; store-side updates via the task tools), THEN the acceptance machinery SHALL pass it through settle check, merge gate and reviewer gate without a false rejection.

### Doubt round-trips before blocking (the ticket's AC 3)

- **FR-009 (event-driven):** WHEN the reviewer cannot decide (verdict `UNCLEAR`, or a reply with no parseable verdict) on a staged ticket, THEN the acceptance machinery SHALL route the doubt back to the originator — send-back to the stage's own backlog with the reviewer's doubt as the reason — for exactly ONE originator retry, before the ticket may ever be blocked.
- **FR-010 (state-driven):** WHILE a ticket has already consumed its one doubt-retry for the current stage, WHEN reviewer doubt recurs on the retry's review, THEN the acceptance machinery SHALL block the ticket with the unresolved doubt as the blocker — `blocked` is the needs-a-human signal and is reached only after the originator had its attempt (the U-023 round-trip-limit doctrine).
- **FR-011 (unwanted):** IF any engine checkpoint (settle check, merge gate) refuses a run on heuristic grounds, THEN it SHALL NOT leave the ticket blocked as its first outcome on the definition leg; heuristic refusals route into the same originator-retry path as reviewer doubt. Blocking is reserved for exhausted retries and genuinely human decisions.
- **FR-012 (ubiquitous):** THE acceptance machinery SHALL record every doubt, retry and refusal as an attributable ticket comment (author, reason) so the escalation trail is auditable — a retry is never silent.

### Tests pin the matrix (the ticket's AC 4)

- **FR-013 (ubiquitous):** THE per-stage evidence matrix SHALL be pinned by automated tests: one test row per V stage asserting which evidence classes satisfy and which refuse, plus the FR-008 regression cases (definition-leg store-side-only run accepted; analysis-only implementation run still refused).

## Non-Functional Requirements
- **NFR-COMPAT-001:** Additive only: verdict shapes (`PASS`/`FAIL`/`UNCLEAR`), stage set, status set, store schema keys and the engine-applies-the-verdict doctrine (U-021) are unchanged; a run at `implementation` behaves byte-for-byte like today except where doubt-routing (FR-009/010/011) deliberately changes the first outcome.
- **NFR-AUDIT-001:** Every acceptance decision — accept, retry-routed doubt, refusal, block — is attributable (author + reason + stage) in ticket comments/history; no silent state changes.
- **NFR-AUTONOMY-001:** The autonomy doctrine holds: the machinery resolves what it can (originator retry) and escalates only exhausted cases; after this feature, a definition-leg false rejection must never again be the reason a wave parks.
- **NFR-COST-001:** The doubt retry reuses the normal stage dispatch; exactly one retry per stage visit — bounded cost, no retry storms (round-trip limit per U-023).
- **NFR-CONSIST-001:** One evidence matrix, three consumers (FR-005): no checkpoint-specific divergence, and the reviewer's *prompt* states the matrix row so the judging model sees the same contract the gates enforce.

## Constraints & Assumptions
- **C-001:** The ticket's `stage` field (nullable; `VStages` canonical) is the matrix key — no new stage taxonomy.
- **C-002:** The task store stays plain Markdown; "store-side changes count as produced work" (FR-006) means the machinery inspects the store/ticket state attributable to the run, not a new side channel.
- **C-003:** U-029 passes pair with this feature: a passed stage produces no artifact by design and must never trip the evidence matrix — the matrix only judges stages that actually ran.
- **C-004 (assumption):** "One retry" is per stage visit: a send-back/rework cycle that later returns to review gets a fresh retry budget; exact bookkeeping (comment marker vs counter) is design-stage work.
- **C-005 (assumption):** The originator-retry routing reuses `task_send_back`/re-dispatch mechanics rather than introducing a new transition; the tool surface is design-stage work.

## Acceptance Criteria

- **AC-001 (FR-001, FR-002, FR-005 — the ticket's AC 1):** Given tickets at `requirements`, `system`, `architecture` and `design` whose runs updated the ticket body/ACs, recorded `doc`/`path` artifacts, and touched only doc/store paths in the diff, when the acceptance machinery judges them, then each is accepted on that evidence — and given an `implementation` ticket, code+tests are still expected, and given a `test-*` ticket, tests/goldens are still expected.
- **AC-002 (FR-006, FR-007, FR-008 — the ticket's AC 2):** Given a replay of the U-026 case (requirements stage; diff = ticket + `docs/requirements/<id>.md`; store updates via the task tools), when the run settles, then neither "worker produced no changes" nor "analysis-only run: no acceptance-criterion path in the diff" fires and the ticket proceeds to review.
- **AC-003 (FR-009, FR-010 — the ticket's AC 3):** Given an in-review staged ticket where the reviewer returns `UNCLEAR`, when the verdict is applied, then the ticket goes back to the originator's stage backlog with the doubt as the reason (not blocked); and given the retry's review is `UNCLEAR` again, then — and only then — the ticket is blocked with the unresolved doubt.
- **AC-004 (FR-011 — the ticket's AC 3, engine side):** Given a definition-leg run a checkpoint would previously have refused on heuristic grounds, when the refusal path runs, then the outcome is the originator-retry route with an attributable comment — not a blocked ticket.
- **AC-005 (FR-013 — the ticket's AC 4):** Given the shipped test suite, when it runs, then one pinned test row per V stage asserts that stage's accepted/refused evidence classes, and the U-026/B-005 regression cases pass while an analysis-only implementation run is still refused.

## Open Questions
- **Q-001:** Where does the evidence matrix live — a code-level mapping beside `VStages`, or store/config data the PM can tune? Requirements fix only that it is single-sourced and stage-keyed (FR-001, FR-005); placement is design.
- **Q-002:** What exactly counts as "goldens" on the verification leg (render references, recorded fixtures, expected-output files)? The verification stages' own requirements refine this; the matrix needs the class, not the format.
- **Q-003:** Retry bookkeeping — comment-marker convention vs a ticket field counting doubt round-trips for the current stage; and whether a human clearing the blocker resets the budget. Design decides; FR-010 fixes only the semantics.
- **Q-004:** Should the reviewer prompt quote the matrix row verbatim per stage (NFR-CONSIST-001 leans yes), and does the reviewer's verdict line need a stage-aware evidence note beyond today's free-text reason? Design/review-prompt work.
- **Q-005:** Interaction with U-029 reviewer passes (FR-006 there): when the reviewer passes a stage, the evidence matrix is not consulted at all — confirm at design that the pass path bypasses, not weakens, these gates.

## Non-Goals
- No change to the verdict vocabulary or the engine-applies-the-verdict doctrine (U-021 stands; the reviewer judges, the engine transitions).
- No relaxation of the implementation-stage gate — analysis-only implementation runs remain refused; this feature removes false rejections, not real ones.
- No new readiness kinds, no stage-set changes, no phase gates — the async-pipeline doctrine stands.
- No heuristic auto-acceptance: the matrix widens *what counts as evidence*, it never skips judgment.
- No U-029 pass implementation here (paired feature, separate ticket); only the non-interference constraint C-003/Q-005.
- No changes to model tiers, reviewer agent selection, or cost accounting beyond the bounded retry (NFR-COST-001).

## Traceability
| Ticket AC | Covered by |
|---|---|
| 1 — stage-appropriate evidence: definition leg accepts ticket-body/AC + doc/store paths; implementation expects code+tests; verification expects tests/goldens | FR-001, FR-002, FR-003, FR-004, FR-005, NFR-CONSIST-001; AC-001 |
| 2 — no more 'analysis-only run' false rejections for definition-leg runs (incl. the settle-check extension: store-side changes count) | FR-006, FR-007, FR-008; AC-002 |
| 3 — reviewer doubt routes to the originator / one retry before ever blocking (blocked = needs-a-human) | FR-009, FR-010, FR-011, FR-012, NFR-AUTONOMY-001, NFR-COST-001; AC-003, AC-004 |
| 4 — tests pin the per-stage evidence matrix | FR-013; AC-005 |
