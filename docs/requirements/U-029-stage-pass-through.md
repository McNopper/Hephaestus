# Requirements: U-029 — Stage pass-through: every staged ticket visits all ten stages; non-applicable stages pass with a rationale

## About this document
- **Kind:** `doc` / requirements definition (stage artifact of ticket U-029, stage `requirements`).
- **Read by:** the downstream V-chain stages of U-029 (`system` → `architecture` → `design` → `implementation`) and the verification leg (`test-*`, ending at `test-requirements`); the reviewer agent at acceptance; any agent asking "what must stage pass-through do?".
- **Written by:** the requirements-stage worker (role `pm`, skill `software-requirements`).
- **Related:** user direction 2026-09-19 ("they should wander from 1 to 10; sometimes no need to change something on architecture level; however, V-model execution"); `.opencode/docs/contracts.md` (ticket/stage contract); `VStages`/`TaskStore.advance`/`StageReadiness` in `eclipse/bundles/com.opencode.ide.tasks` and `WavePlanner`/`FleetRunner`/`ReviewVerdict` (U-021 autonomous acceptance) in `eclipse/bundles/com.opencode.ide.fleet` (current behavior baseline); U-026 (flow trace — consumes the data this feature produces); U-027 (the "verdict-neutral bookkeeping" and "uniform across every consumer" doctrines reused below). This file is the **what and why** — tool shape, storage and code are the next stages' work.

**Terminology guard (read this first).** A **stage pass** (this feature) is a recorded pass-through of one V stage. It is *not* the U-021 review verdict `VERDICT: PASS` (acceptance of a finished stage) and *not* the readiness kind `NOT_APPLICABLE` (unstaged/done tickets). Where the review machinery is meant, this doc says **review acceptance**.

## Goal

The V-pipeline must stay **visible and honest** — every staged ticket wanders through all ten stages, 1 to 10 — without **burning agent runs on stages where nothing applies** to the change (the user's example: a local change with no architecture-level impact). Today the only way past such a stage is a full dispatch that produces a hollow artifact, or an off-protocol shortcut that breaks the 1–10 trace. This feature makes the shortcut a first-class citizen: a stage with no applicable change **passes** — advancing exactly like a completed stage, with a recorded rationale, no dispatch, no charge — so the flow trace keeps showing ten visited stages while the fleet spends nothing on the empty ones.

## Users
- **PM agent / wave loop (U-022 RecurringWaves, WavePlanner)** — drains waves; wants non-applicable stages to advance themselves instead of launching pointless sessions or parking tickets.
- **Stage workers (requirements→implementation)** — want to name, in their artifact, the downstream stages their change does not touch, and trust the machinery to honor it.
- **Reviewer agent (U-021 autonomous acceptance)** — judges in-review tickets; needs a legitimate way to say "this stage has no applicable work" instead of accepting a hollow artifact or bouncing the ticket.
- **Dispatch machinery (auto-dispatch, wave planning, cost overview)** — must never launch or charge a worker for a passed stage.
- **Human Product Owner** — audits the 1–10 journey and every "why was there no work here?" through the ticket history and the U-026 flow trace.

## User Stories
- **US-001:** As the PM agent running waves, I want non-applicable stages to advance with a recorded rationale instead of a dispatched worker, so that the V stays visible without spending budget on empty agent runs.
- **US-002:** As a stage worker, I want to name the downstream stages my change does not touch, so that no agent is dispatched to fabricate artifacts for them.
- **US-003:** As the reviewer, I want to pass a stage at acceptance when its work does not apply, so that I neither accept hollow artifacts nor create pointless rework.
- **US-004:** As the human Product Owner, I want every pass to carry a reason, an author and a timestamp in the ticket history and the flow trace, so that I can audit why any stage saw no work.

## Functional Requirements

*System names used in EARS below:* **the pipeline** = the V-stage machinery (`VStages` ladder, the `task_advance`/`task_send_back` transitions, and the pass transition this feature adds); **the dispatcher** = whatever launches stage workers (fleet dispatch, auto-dispatch, wave planning admission); **the readiness** = the H6 verdict evaluation and every consumer of it (as in U-027: `task_readiness`, board badges, dispatch admission, invalidation recording); **the flow trace** = the U-026 movement surface built from ticket history.

### Full-travel semantics (the ticket's AC 1)

- **FR-001 (ubiquitous, invariant):** THE pipeline SHALL move every staged ticket through the ten canonical stages strictly one stage per transition, so that a ticket's stage sequence and history show an unbroken 1→10 journey (`requirements` → … → `test-requirements`) regardless of passes — a pass visits a stage, it never skips over one.
- **FR-002 (event-driven):** WHEN a stage is passed, THEN the pipeline SHALL transition the ticket exactly like a completed-stage advance — stage and role become the next stage's, status becomes the next stage's backlog (`product-backlog`), the assignee is cleared, the blocked flag stays as-is — **and** SHALL append a ticket-history event `stage '<stage>' passed: <reason>` carrying author and timestamp. No stage artifact is required or expected for a passed stage.
- **FR-003 (unwanted):** IF a pass is attempted for the V tip stage `test-requirements`, or with a blank reason, THEN the pipeline SHALL reject it — the journey always ends with a real acceptance run, and a pass without a stateable claim of non-applicability is not a pass.

### Who decides (the ticket's AC 2)

Exactly three declaring authorities, in order of preference (earlier = cheaper):

- **FR-004 (forward declaration — the preferred path, complex):** WHILE a stage worker finishes stage N's artifact, WHEN it names a downstream stage M as non-applicable with a rationale, THEN the pipeline SHALL honor that declaration when the ticket reaches M: record the pass attributed to the declaring worker and launch no stage-M worker. The declaration is **advisory until honored** — it is data the dispatcher (or the PM) consults at M, not a side effect at N.
- **FR-005 (conflicting declarations, state-driven):** WHILE declarations for a stage M conflict (an earlier artifact declared it non-applicable, a later artifact requires work there — or withdraws the declaration), WHEN the ticket reaches M, THEN the **newest** declaration SHALL govern; with no declaration at all, the default is a dispatched worker — the machinery never invents a pass (no heuristics, no auto-passing).
- **FR-006 (reviewer pass at acceptance, event-driven):** WHEN a ticket is `in-review` at a stage and the reviewer judges that no applicable work exists for that stage, THEN the reviewer SHALL be able to record the pass (with rationale) through the same machinery instead of accepting a hollow artifact or sending the ticket back — following the U-021 doctrine that the reviewer judges and the **engine** applies the store transition.
- **FR-007 (discovered at stage — the costly fallback, event-driven):** WHEN a dispatched stage worker discovers mid-run that nothing applies to its stage, THEN it SHALL finish by recording a pass with its rationale instead of fabricating an artifact (the session was already launched and charged; the journey stays honest and the next stages inherit the declaration).

### Rationale recording and visibility (the ticket's AC 3)

- **FR-008 (ubiquitous):** THE pass SHALL always be recorded in the ticket history as an auditable event — action text `stage '<stage>' passed: <reason>`, author, timestamp — never as a silent mutation; passes leave the same evidentiary trail as advances and send-backs.
- **FR-009 (ubiquitous):** THE pass event SHALL be carried entirely in ticket-history data, such that the U-026 flow trace renders it without a parallel channel: per-card stage progress counts a passed stage as **visited** (a ticket at stage 6 with two passes still shows 6/10), the movement trace lists the pass with its timestamp, reason and author, and the per-wave movement digest counts passes as movement.

### No dispatch, no charge, machinery equivalence (the ticket's AC 4)

- **FR-010 (state-driven):** WHERE a stage is passed without a worker session (honored forward declaration, or a pass recorded while the ticket sits undispatched in the stage's backlog), WHEN the dispatcher considers the ticket, THEN it SHALL launch no session for the passed stage and the cost overview SHALL record **no** charge attributable to the pass.
- **FR-011 (ubiquitous, equivalence):** THE readiness and advance machinery SHALL treat a passed stage exactly like a completed stage: a passed upstream satisfies downstream evaluation with the same verdict shape (the reason names the pass as satisfaction evidence), the pass event's own recording is verdict-neutral bookkeeping (no downstream ticket becomes `STALE` because a pass was recorded — the U-027 FR-003 doctrine), and epic-chain / own-flow-history satisfaction sees a pass as an advance.
- **FR-012 (unwanted):** IF an upstream input changes after a stage was passed and the ticket is later back at or past that point in a state where readiness would mark a completed stage `STALE`, THEN the passed stage SHALL be treated the same — a pass never outranks changed inputs; resolving requires real work or a fresh pass rationale that reflects the new inputs.
- **FR-013 (send-back into a passed stage, complex):** WHILE a ticket's stage N was passed and a later stage sends it back to N with a reason, WHEN the send-back lands, THEN the ticket SHALL arrive in N's backlog blocked with the send-back reason (existing semantics), the earlier pass SHALL remain in history as evidence, and clearing the blocker SHALL require either real stage-N work or a corrected pass whose rationale addresses the send-back reason — the send-back is precisely the feedback loop that catches a wrong pass.
- **FR-014 (ubiquitous, uniformity):** THE pass SHALL be treated uniformly across every consumer — `task_readiness`, board badges and stage progress, auto-dispatch admission, wave planning, cost overview, flow trace — one consistent story everywhere (the U-027 FR-005 doctrine).

## Non-Functional Requirements
- **NFR-COST-001:** A pass costs zero fleet budget — no session, no tokens, no cost-overview line; only the store transaction happens.
- **NFR-COMPAT-001:** Additive only: existing history actions (`advanced to`, `sent back to`), the stage field, the status set, and all `task_*` schema keys remain unchanged; a store that never sees a pass behaves byte-for-byte like today.
- **NFR-CONSIST-001:** One pass semantic everywhere (enforces FR-014): no surface may invent a second, divergent notion of "passed".
- **NFR-AUDIT-001:** Every pass is attributable (author + timestamp + reason) and mechanically rejectable when unattributable or blank — silence is not a pass.
- **NFR-TERM-001 (usability):** Tool and UI wording distinguishes the stage **pass** from review acceptance (`VERDICT: PASS`) and from the readiness kind `NOT_APPLICABLE`, so operators never conflate the three.

## Constraints & Assumptions
- **C-001:** The task store stays plain Markdown, one file per ticket; a pass is recorded in ticket history (whether the tool is a dedicated `task_pass_stage` or a flag on `task_advance` — and whether forward declarations live in a ticket field or artifact markers — is design-stage work).
- **C-002:** The one-stage-per-step transition is structural and preserved (FR-001) — "visit all ten" is enforced by the ladder, not by convention.
- **C-003:** "Wave" and "sprint" are synonyms in prose; the schema key stays `sprint` (stability doctrine).
- **C-004:** The U-026 flow trace consumes ticket history; passes ride that data — no parallel event channel.
- **C-005:** The reviewer pass builds on the U-021 engine-applies-verdict doctrine (the engine performs the store transition; the reviewer only judges).
- **C-006 (assumption):** Passes are declared by the three authorities of FR-004/006/007 only; extending the authority set (e.g. an at-rest PM pass tool) is a deliberate policy decision left to design, not something dispatch code does implicitly.

## Acceptance Criteria

- **AC-001 (FR-001, FR-002 — the ticket's AC 1):** Given a staged ticket at `architecture` whose change has no architectural impact, when the stage is passed with reason "no architecture impact: local change", then stage becomes `design`, role `developer`, status `product-backlog`, assignee cleared, and history gains `stage 'architecture' passed: no architecture impact: local change` with author and timestamp — and the ticket's stage sequence so far reads `requirements → system → architecture → design` with every adjacent pair exactly one ladder step apart.
- **AC-002 (FR-003 — the ticket's AC 1 guard):** Given a ticket at `test-requirements` (the V tip), when a pass is attempted, then the pipeline rejects it naming the tip; and given any stage, when a pass is attempted with a blank reason, then it is rejected.
- **AC-003 (FR-004, FR-005 — the ticket's AC 2):** Given stage N's artifact declares "`architecture`: pass — no architectural impact: local change" and no later artifact contradicts it, when the ticket reaches `architecture`, then the pass is recorded attributed to stage N's worker and no architect session is launched; but when a later artifact requires architecture work, then a worker is dispatched as usual.
- **AC-004 (FR-006 — the ticket's AC 2):** Given a ticket `in-review` at `design` where the reviewer finds no design-level work applies, when the reviewer passes the stage, then the ticket advances to `implementation` with the pass reason and reviewer attribution in history — no hollow artifact is demanded and no rework loop starts.
- **AC-005 (FR-008, FR-009 — the ticket's AC 3):** Given a ticket at stage 6 whose history contains two pass events, when the flow trace (U-026) renders it, then the card shows 6/10 stages visited and the movement trace lists both passes with timestamps, reasons and authors, and the wave's movement digest counts them.
- **AC-006 (FR-010, NFR-COST-001 — the ticket's AC 4):** Given a forward-declared pass honored while the ticket sat undispatched, when the wave drains, then the fleet launched no job for the passed stage and the cost overview attributes zero charge to it.
- **AC-007 (FR-011 — the ticket's AC 4):** Given a ticket at `design` whose epic-chain upstream `architecture` was passed, when readiness evaluates it, then it reads `READY` with a reason naming the passed upstream — the same verdict the identical completed stage would produce; and recording the pass made no ticket `STALE`.
- **AC-008 (FR-012, FR-013 — wrong-pass recovery):** Given `architecture` was passed and `implementation` sends the ticket back ("needs an architecture decision: concurrency model"), when the send-back lands, then the ticket sits in `architecture` blocked with that reason, history keeps the earlier pass, and after the blocker clears the stage shows either real work or a corrected pass rationale addressing the concurrency question.
- **AC-009 (FR-014, NFR-CONSIST-001):** Given a passed upstream, when any surface is consulted (`task_readiness`, board progress, wave-planning admission, cost overview), then all surfaces treat the pass as a completed stage, uniformly.

## Open Questions
- **Q-001:** Tool surface — dedicated `task_pass_stage(project, ticket, reason)` vs `task_advance(passed=true, reason=…)`; and its status gate (backlog states + `in-review`?) — design decides; requirements fix only the transition semantics (FR-002) and rejections (FR-003).
- **Q-002:** Forward-declaration storage — ticket frontmatter list vs artifact marker convention (and how the dispatcher finds the *newest* declaration cheaply) — design.
- **Q-003:** May the PM (or any store peer) record an at-rest pass without a prior declaration (FR-010 mentions it; C-006 defers the authority question)? Policy call at design.
- **Q-004:** Is any substance check beyond non-blank enforceable (e.g. minimum length, must name the stage)? Left to reviewer judgment for now (FR-008 + NFR-AUDIT-001 pin attribution, not eloquence).
- **Q-005:** Should `task_readiness` reasons flag pass-satisfaction with a structured marker beyond text (for U-026 styling, e.g. a "⟂" glyph)? Mirrors U-027 Q-002; decide at design.

## Non-Goals
- No stage skipping, ever — a pass visits the stage in sequence; the ladder, the ten-stage set, and `VStages` stay untouched.
- No new readiness kind — `NOT_APPLICABLE` keeps its existing meaning; a passed stage is simply completed.
- No heuristic or automatic passes — an authorized declaration always precedes a pass (autonomy doctrine: no silent skipping by machinery).
- No phase gates or ordering enforcement — the async-pipeline doctrine stands; passes change the cost of a stage, not the ordering rules.
- No U-026 trace UI work here — this feature delivers the data contract (FR-009); rendering is U-026's.
- No pass statistics, retention or reporting beyond the movement-digest contract (U-026 owns digests).
- No change to review acceptance semantics (`VERDICT: PASS/FAIL/UNCLEAR`) beyond the reviewer's new option to pass a stage.

## Traceability
| Ticket AC | Covered by |
|---|---|
| 1 — every staged ticket VISITS all ten stages; non-applicable stage advances with a recorded rationale instead of a full dispatch | FR-001, FR-002, FR-003; AC-001, AC-002 |
| 2 — who decides: previous stage's worker names non-applicable stages in its artifact, and/or the reviewer may pass a stage during acceptance | FR-004, FR-005, FR-006, FR-007 (fallback); AC-003, AC-004 |
| 3 — rationale recorded in ticket history (`stage N passed: reason`) and visible in the flow trace (U-026) | FR-008, FR-009; AC-005 |
| 4 — no dispatch for a passed stage, budget not charged; advance/readiness machinery treats the pass exactly like a completed stage | FR-010, FR-011, FR-012, FR-013, FR-014, NFR-COST-001; AC-006, AC-007, AC-008, AC-009 |
