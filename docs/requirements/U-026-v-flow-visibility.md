# Requirements: U-026 — V-flow visibility: watch tickets travel stages 1-10 and see their send-backs

## About this document
- **Kind:** `doc` / requirements definition (stage artifact of ticket U-026, stage `requirements`).
- **Read by:** the downstream V-chain stages of U-026 (`system` → `architecture` → `design` → `implementation`) and the verification leg; the reviewer agent at acceptance; anyone asking "what must V-flow visibility show?".
- **Written by:** the requirements-stage worker (role `pm`, skill `software-requirements`).
- **Related:** ticket U-026 (the chain head — the pump walks it 1 through 10); U-028 (board polish — explicitly secondary to this feature); B-007 (stage-aware acceptance — its evidence matrix is what this feature visualizes); `VStages` / `TaskStore.advance|sendBack|passStage|reportHorizontal` (the movement events this feature reads); `docs/requirements/U-028-board-polish-pack.md`. This file is the **what and why** — tool shape, storage and code are the next stages' work.

## Goal

Tickets already travel the V pipeline and the store records every movement
(advance, send-back, pass, horizontal report, settle) as an attributable
history event — but the user cannot SEE the journey. "We should SEE how
things flow from stage 1 to 10 and if things need to be resolved sometimes
back" (product owner, 2026-09-19). This feature turns the recorded history
into visible flow: how far a ticket has travelled, where it moved back and
why, and how a whole wave moved — without inventing new data.

## Users
- **Product owner / human reviewer** — watches a wave drain and wants to see movement and setbacks at a glance.
- **PM agent** — runs waves unattended; a per-wave movement digest is its review input.
- **Any agent or human inspecting a ticket** — wants the stage journey inline on the card or its details.

## User Stories
- **US-001:** As a product owner, I see each card's stage progress (e.g. `4/10`) so I know how far along it is without opening it.
- **US-002:** As a product owner, I see a ticket's movement trace — stage transitions with timestamps and authors — so I can reconstruct its journey.
- **US-003:** As a product owner, I see send-back events with their reasons so I understand why work came back.
- **US-004:** As the PM agent, I get a per-wave movement digest so I can summarize what a wave moved without re-reading every ticket.

## Functional Requirements

*System names:* **a stage journey** = the ordered sequence of movement events
recorded in one ticket's history between creation and now; **movement** = any
history event that changes or reports the ticket's position on the V
(advance, send-back, stage pass, horizontal report) or settles a stage's run
(merge/claim/settle markers as supporting context); **the digest** = a compact
per-wave summary of movements.

- **FR-001 (ubiquitous):** A stage journey SHALL be derived from the ticket's existing `history` events only — no new recording channel, no side state.
- **FR-002 (state-driven):** Every ticket card in the V-layout SHALL show its stage progress as `<visited>/10`, where visited counts the V stages the journey has entered (the current stage counts once entered).
- **FR-003 (event-driven):** WHEN a ticket's history contains stage movements, the ticket's details surface SHALL show the movement trace: each transition with its timestamp, author, direction (advance / send-back / pass / horizontal report) and the recorded reason where the event carries one.
- **FR-004 (event-driven):** WHEN a ticket was sent back, the send-back SHALL appear unmissably in the trace and on the card: the source stage, the destination stage and the reason text (`sent back from X: <reason>` / `reported to X: <reason>` / the `clarification to` markers).
- **FR-005 (event-driven):** WHEN a wave is planned or closed, the system SHALL produce a per-wave movement digest: counts of advances, passes, send-backs and reports, the tickets that moved back (with reasons), and the tickets that never moved.
- **FR-006 (ubiquitous):** The digest SHALL be renderable as plain text (a ticket comment or chat output) so the chat-first control plane can produce it without the Eclipse UI.
- **FR-007 (unwanted):** No feature of this specification SHALL write to the store to be visible — visibility is a projection of recorded state (U-027 uniformity doctrine).
- **FR-008 (ubiquitous):** U-029 pass-through events (`stage N passed: <reason>`) SHALL count as movement — the stage was visited, and the trace shows the rationale.

## Non-Functional Requirements
- **NFR-PERF-001:** Deriving a journey or digest SHALL be O(history size) with no I/O beyond the store read already happening for the snapshot.
- **NFR-COMPAT-001:** Additive only: no change to the status set, stage set, history schema or any transition's semantics.
- **NFR-REDUND-001:** The card's progress indicator is duplicated in the ticket details trace (same data, two depths) — never two sources.

## Constraints & Assumptions
- **C-001:** The `history` event vocabulary (`advanced to`, `sent back to`, `reported to`, `stage N passed`, `clarification to`, `review doubt`) is the movement vocabulary; parsing lives beside the store codec.
- **C-002:** Stage numbering is the canonical `VStages` order (1 = `requirements` … 10 = `test-requirements`); the V reading order of U-028's chevrons is the same order.
- **C-003 (assumption):** The digest attaches as a ticket comment on the wave's epic/head ticket or returns as tool output; placement is design-stage work.

## Acceptance Criteria

- **AC-001 (FR-001, FR-002 — the ticket's AC "per-card stage progress"):** Given tickets mid-journey, when the board renders, then each card shows `<visited>/10` derived from its history.
- **AC-002 (FR-003, FR-004, FR-008 — the ticket's AC "movement trace" + "visible send-backs"):** Given a ticket with advances, a send-back with a reason and a stage pass, when its details render, then the trace lists every movement with timestamp, author, direction and reason, and the send-back is highlighted.
- **AC-003 (FR-005, FR-006 — the ticket's AC "per-wave movement digest"):** Given a wave whose tickets moved, when the digest is produced, then it counts movements by kind and names the sent-back and unmoved tickets with reasons, as plain text.
- **AC-004 (FR-007):** Given the visible surfaces, when they render, then the store is unchanged (a projection test).

## Non-Goals
- **No animation work** and no timeline UI beyond the digest (explicitly out).
- No minimap, no wave replay, no forecasting.
- No new stored state: no journey caches, no movement tables.
- No WIP-limit concepts (U-028 FR-004 territory) and no readiness badges (already live).

## Open Questions
- **Q-001:** Digest placement — epic ticket comment, tool output only, or both? (C-003.)
- **Q-002:** Should the card's `<visited>/10` count stages entered after a rework twice? (Lean: count distinct stages visited; the trace shows repeats.)

## Traceability
| Ticket AC | Covered by |
|---|---|
| Requirements doc section defines the flow-visibility feature: journey, movement events, what the user must SEE | this document (Goal, FR-001..FR-008) |
| per-card stage progress (4/10) | FR-002; AC-001 |
| movement trace from ticket history (stage transitions with timestamps) | FR-001, FR-003; AC-002 |
| visible send-back events with their reasons | FR-004; AC-002 |
| per-wave movement digest | FR-005, FR-006; AC-003 |
| non-goals named explicitly (no animation, no timeline UI beyond the digest) | Non-Goals |
