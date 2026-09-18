# Requirements: U-027 — Readiness consults the archive; safe auto-archiving of done tickets

## About this document
- **Kind:** `doc` / requirements definition (stage artifact of ticket U-027, stage `requirements`).
- **Read by:** the downstream V-chain stages of U-027 (`system` → `architecture` → `design` → `implementation`) and the verification leg (`test-*`, ending at `test-requirements`); the reviewer agent at acceptance; any agent asking "what must archive-aware readiness do?".
- **Written by:** the requirements-stage worker (role `pm`, skill `software-requirements`).
- **Related:** the U-025 lesson (commit `deff2d1` — store-level `_archive/`, manual Archive action, wave-close auto-archiving deliberately withheld); `.opencode/docs/contracts.md` (ticket/stage contract); `StageReadiness`/`TaskStore` in `eclipse/bundles/com.opencode.ide.tasks` (current behavior baseline). This file is the **what and why** — solution shape, structure and code are the next stages' work.

## Goal

Done tickets should leave the active board (it shows work in flight, not history) **without
breaking the V-pipeline**. Today those two goals collide: readiness (the H6 machinery that
decides READY / WAIT_UPSTREAM / …) evaluates upstream satisfaction over the **live** ticket
list only, so archiving a done upstream orphans its downstream stages — they read
`WAIT_UPSTREAM` forever (the U-025 lesson, caught live by `RecurringWavesTest` on
2026-09-19). This feature makes readiness **consult the archive**, then — and only then —
returns **wave-close auto-archiving**, behind a flag, gated so automation never archives a
done ticket a live ticket still needs.

## Users
- **PM agent / wave loop (U-022 RecurringWaves)** — closes drained waves; wants done tickets to leave the board automatically and safely.
- **Dispatch machinery (auto-dispatch, WavePlanner, task_readiness consumers)** — admits tickets by readiness verdict; must keep epic chains flowing across archived upstreams.
- **Fleet stage workers** — consult upstream evidence (the epic chain) before claiming work; an archived done upstream is finished evidence, not missing evidence.
- **Human Product Owner** — keeps a lean active board and traceability into `_archive/`; must never be surprised by a broken chain or lost ticket data.

## User Stories
- **US-001:** As the PM agent running recurring waves, I want closed waves to archive their drained done tickets automatically, so that the active board stays lean without me (or a human) pruning it.
- **US-002:** As the dispatch machinery, I want an archived done upstream to satisfy its epic-chain downstream exactly as the live ticket did, so that archiving never orphans a stage and waves keep draining.
- **US-003:** As the human Product Owner, I want auto-archive off by default and conservative when on, so that flipping the automation on cannot hide evidence a live chain still uses — and never loses ticket history.

## Functional Requirements

*System names used in EARS below:* **readiness** = the H6 dispatch-readiness evaluation
(`StageReadiness` verdicts surfaced by `task_readiness`, the board badges, auto-dispatch
and `WavePlanner` admission, and the STALE invalidation recorder); **wave close** = the
sprint/wave closing path (`task_close_sprint`, driven manually or by the recurring-waves
loop); **the archive** = a ticket moved to `<project>/_archive/` (U-025).

### Archive-aware readiness (the correctness core)

- **FR-001 (ubiquitous):** THE readiness SHALL evaluate upstream satisfaction over the union of live tickets and archived tickets of the project.
- **FR-002 (state-driven):** WHILE an epic-chain ticket (epic parent, or epic sibling per the traceability pairing) is archived with status `done`, WHEN readiness evaluates a downstream ticket whose upstream stage matches that archived ticket's stage, THEN readiness SHALL treat the archived ticket as satisfying that upstream — with the same verdict the identical live ticket would produce — and the reason text SHALL name the archived ticket and state that it is archived.
- **FR-003 (unwanted):** IF a ticket is archived, THEN no live ticket's readiness kind SHALL change as a result of the archive event itself — in particular no downstream ticket becomes `WAIT_UPSTREAM` (orphaning) and none becomes `STALE` (the archive timestamp is bookkeeping, not an input change).
- **FR-004 (unwanted):** IF an archived ticket file is missing or unparsable, THEN readiness SHALL skip that file (with a warning) and evaluate over the remaining evidence — a corrupt archive never breaks readiness or wave close.
- **FR-005 (ubiquitous):** THE readiness SHALL apply archive-awareness uniformly across every surface that consumes verdicts (`task_readiness` tool, board readiness badges, auto-dispatch admission, wave planning, invalidation recording) — one consistent verdict everywhere.

### Wave-close auto-archiving (the automation, deliberately conservative)

- **FR-006 (optional feature):** WHERE the per-project **wave-close auto-archive flag** is enabled (OFF by default; toggled per project from the Board or the tool surface, mirroring the U-022 recurring-waves opt-in doctrine), WHEN a wave closes, THEN wave close SHALL archive that wave's `done` tickets — **but only those no live ticket still needs** (see *Needs* definition below) — and SHALL complete the close exactly as today for everything else (unfinished tickets return to the product backlog, sprint status becomes `closed`).
- **FR-007 (unwanted):** IF the flag is off (the default), THEN wave close SHALL leave done tickets live, pinning today's U-025-lesson behavior.
- **FR-008 (event-driven):** WHEN a done ticket is skipped by the guard at wave close, THEN the wave close SHALL still succeed and the ticket SHALL simply remain live for a later close or the manual Archive action.
- **FR-009 (ubiquitous):** THE wave-close auto-archive SHALL consider only tickets with status `done` — never `in-review`, `in-progress`, or backlog states.
- **FR-010 (ubiquitous):** THE archive operation SHALL preserve the ticket record verbatim — history, artifacts, actuals, comments; only the location changes and the archive event is appended — and SHALL NEVER lose the record if interrupted mid-archive (at worst the ticket exists in both places).

#### "A live ticket still needs the archived upstream" — the guard definition

A live ticket **L needs** a done ticket **D** (so D must not be auto-archived) when:

1. L is live (not archived, any status other than `done`), **and**
2. L is epic-chained to D (L's `epic` is D's id, or L's `epic` equals D's `epic`, or L's id equals D's `epic`), **and**
3. L consumes D's stage output — directly or transitively: L's stage sits strictly after D's stage on the V ladder, applying the verification pairing (a `test-X` stage consumes from definition stage `X`'s ladder position).

*Rejected alternative — immediate-upstream-only guard:* archiving D as soon as only
*transitive* consumers remain (e.g. the `requirements` head archives while `design` runs).
FR-002 makes that *safe*, but not *useful*: mid-chain evidence should stay visible to the
board, the flow-visibility work (U-026) and humans tracing a live chain. A chain archives
together, when it has fully drained — coarse, simple, and impossible to get wrong.

## Non-Functional Requirements
- **NFR-PERF-001:** With hundreds of archived tickets (envelope: 500 archived + 100 live), readiness evaluation SHALL stay in the same order of magnitude as today's live-only evaluation — the archive is consulted per evaluation, never re-scanned per live ticket.
- **NFR-REL-001:** Readiness and wave close SHALL tolerate an absent `_archive/` directory, an empty one, and unparsable entries (skip + warn) — no crash paths through the archive.
- **NFR-COMPAT-001:** Verdict kinds and their precedence, the manual Archive action (U-025), the `archived()` listing, and all `task_*` schema keys SHALL remain unchanged; a store with no archived tickets SHALL behave identically to today.
- **NFR-SAFE-001 (data safety):** Archiving SHALL be lossless and effectively atomic — the archived copy is written before the live file is removed; interruption leaves at worst a duplicate, never a gap.

## Constraints & Assumptions
- **C-001:** Storage stays plain Markdown, one file per ticket, `_archive/` as the sibling directory (U-025, live) — no database, no new format.
- **C-002:** `StageReadiness` remains a pure function over one snapshot (no I/O, no clock) — archived evidence enters as data, preserving the pure core (design stage picks the mechanism).
- **C-003:** The automation hooks wave close (`task_close_sprint`), covering both manual close and the U-022 recurring loop's drained-wave close — no second timer.
- **C-004:** "Wave" and "sprint" are synonyms in prose; the schema key stays `sprint` (stability doctrine).
- **C-005 (assumption):** Archived tickets are frozen evidence — nothing writes to an archived ticket except the archive event itself; restore remains a manual file move, out of scope.

## Acceptance Criteria

- **AC-001 (FR-001, FR-002 — the ticket's AC 3, first half):** Given an epic chain with a done `requirements` parent and a live `system` child reading `WAIT_UPSTREAM`, when the parent is archived (manual Archive action), then `task_readiness` reports the child `READY` with a reason naming the archived parent as archived.
- **AC-002 (FR-002, verification leg):** Given a live `test-design` ticket whose epic-chain `design` upstream is archived done, when readiness is evaluated, then that upstream counts as satisfied (the ticket is `READY`, or `NOT_APPLICABLE` if itself done) — not `WAIT_UPSTREAM`.
- **AC-003 (FR-003 — STALE protection):** Given a live ticket that already ran (e.g. `READY` after rework, or `done`) whose epic-chain upstream is archived, when readiness is evaluated after the archive, then no ticket reads `STALE` because of the archive event.
- **AC-004 (FR-006, FR-008 — the guard):** Given the auto-archive flag on and a closing wave whose done parent has a live, not-done, epic-chained downstream member (in the same or a later wave), when the wave closes, then the parent stays live, the close completes, and unfinished tickets are returned to the product backlog as today.
- **AC-005 (FR-006, FR-010 — the ticket's AC 3, second half):** Given the flag on and a fully drained wave (every ticket done, no live downstream needs anywhere), when the wave closes, then all its done tickets move to `_archive/` with records intact, the sprint reads `closed`, and readiness over the remaining live store shows no orphaned `WAIT_UPSTREAM` child of an archived upstream.
- **AC-006 (FR-007 — default off):** Given the flag off (default), when a wave closes, then done tickets stay live — byte-for-byte today's behavior.
- **AC-007 (FR-004, NFR-REL-001):** Given an unparsable file inside `_archive/`, when readiness runs and a wave closes, then both complete, skipping that file with a warning.
- **AC-008 (FR-009):** Given a ticket `in-review` at wave close with the flag on, when the wave closes, then it is returned to the product backlog — not archived.
- **AC-009 (FR-005):** Given an archived done upstream, when any readiness surface is consulted (`task_readiness`, board badge, wave planning admission), then all surfaces report the same satisfied/upstream verdict for the downstream ticket.

## Open Questions
- **Q-001:** Flag storage mechanics (per-project field in `_meta.json`, board preference, or fleet tuning) — a design-stage decision; requirements fix only: per project, OFF by default, togglable from Board or tool surface.
- **Q-002:** Should `task_readiness` rows mark *which* evidence tickets are archived beyond the reason text (a structured field)? Nice-to-have for U-026 flow visibility; decide at design.
- **Q-003:** A dwell time before auto-archive (archive N closes after done) was considered and left out — immediate-at-close matches the ticket; revisit only if live operation shows churn.

## Non-Goals
- No auto-unarchive/restore path or UI (manual file move remains possible by construction).
- No archive retention, compression, or garbage collection.
- No change to the manual Archive action or the board's Archive view (U-025 semantics stand).
- No new board rendering for archived rows (U-028 owns board polish).
- No change to wave-close semantics beyond the guarded archiving step.

## Traceability
| Ticket AC | Covered by |
|---|---|
| 1 — readiness evaluates upstream satisfaction including ARCHIVED tickets | FR-001, FR-002, FR-005; AC-001, AC-002, AC-009 |
| 2 — wave-close auto-archive behind a flag, guarded | FR-006–FR-010 + guard definition; AC-004, AC-005, AC-006, AC-008 |
| 3 — archived parent satisfies WAIT_UPSTREAM children; wave archives cleanly | AC-001, AC-003, AC-005 |
