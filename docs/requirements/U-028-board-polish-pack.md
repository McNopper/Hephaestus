# Requirements: U-028 — Board polish pack: WIP indicators and V chevron connectors

## About this document
- **Kind:** `doc` / requirements definition (stage artifact of ticket U-028, stage `requirements`).
- **Read by:** the downstream V-chain stages of U-028 (`system` → `architecture` → `design` → `implementation`) and the verification leg (`test-*`, ending at `test-requirements`); the reviewer agent at acceptance; any agent asking "what must the WIP count and the V chevrons do?".
- **Written by:** the requirements-stage worker (role `pm`, skill `software-requirements`).
- **Related:** U-018 (origin — the rubberduck-review follow-ups whose slices 1+2 landed; "V chevron connectors, WIP limits" were the deferred remainder), U-016 (the V board layout these features polish), **U-026 (v-flow visibility — this ticket is explicitly secondary to it, see *Scope & Priority*)**, and the current-behavior baseline in `eclipse/bundles/com.opencode.ide.board` (`BoardView`, `BoardModel`/`BoardSnapshot`, `StageColumn`, `VStageLayout`, `TicketRow`). This file is the **what and why** — placement, styling and code are the later stages' work.

## Goal

Two deferred nice-to-haves from the U-018 rubberduck review, now entering the V properly
instead of floating in chat:

1. **WIP indicators** — the board answers "how much work is running right now?" with a
   plain count of in-progress tickets, visible per V stage column and board-wide. The
   flat Progress layout already carries this implicitly (its `in-progress (n)` column
   header), but the V-model stages layout — the primary layout for V-flow work — has no
   in-progress aggregate at all: in-progress tickets are scattered across ten stage
   columns and the headers show only total and blocked counts.
2. **V chevron connectors** — the board draws the reading order of the V as a chevron
   chain, stages 1→10: down the definition leg, across the vertex turn, up the
   verification leg. Today the order exists only as *numbers* in the column headers
   (`3 · architecture`, "Stage 3 of 10" tooltips); the two arms do not yet read as one
   continuous flow.

Both are polish for the single developer steering the agent fleet from the Board —
cheap answers to two standing reading questions, nothing more.

## Scope & Priority

- **In scope:** exactly the two features above, board-side only (view + its SWT-free
  model projections). No store schema, no `task_*` tool, no fleet/dispatch change.
- **Explicitly secondary to U-026 (v-flow visibility).** U-026 is the high-priority
  chain (per-card stage progress, movement traces, send-back visibility, digests);
  U-028 is the low-priority polish pack (`low`, 2 SP). No U-028 work may block, rework
  or gate a U-026 surface; if the two collide on a board surface, **U-026 wins** and
  this ticket adapts or descopes.

## Users
- **Human Product Owner (the single dev)** — primary reader; steers the fleet from the Board and wants load and flow at a glance.
- **PM agent / wave loop** — indirect: reads the task store, not the Board pixels; unaffected by this feature (compat constraint).
- **The V-chain's verification leg** — consumes this document's acceptance criteria at `test-*` stages and final acceptance.

## User Stories
- **US-001:** As the single developer steering the agent fleet, I want the number of in-progress tickets visible per V stage column and board-wide, so that I can judge the fleet's current load at a glance — without switching to the Fleet view or counting ▶ cards by hand.
- **US-002:** As a Board reader, I want the V's reading order drawn as a chevron chain from stage 1 to stage 10, so that the two arms read as one continuous flow — work enters at the top left, turns at the vertex, and climbs to acceptance.

## Functional Requirements

*System name used in EARS below:* **the board** = the PM Board view plus its SWT-free
model projections in the `com.opencode.ide.board` bundle. **WIP count** = the number of
tickets with status `in-progress` (exactly the tickets the board already marks with the
▶ row glyph). **Reading order** = the canonical stage ladder `VStages.STAGES`
(1 `requirements` … 5 `implementation`, 6 `test-implementation` … 10 `test-requirements`),
whose geometry `VStageLayout` renders as two arms with the vertex turn at the bottom.

### Feature A — WIP indicators (count only)

- **FR-001 (ubiquitous):** THE board SHALL display, in the V-model stages layout, a per-stage-column WIP count: for every rendered stage column, the count of that column's tickets whose status is `in-progress`, shown alongside the existing total/blocked header counts, including when it is 0.
- **FR-002 (ubiquitous):** THE board SHALL display one board-wide WIP count — the number of `in-progress` tickets across the displayed project — on a surface visible in **every** Group-by layout (the header's fleet row, beside the existing `n ready · m stale` readiness badge, is the natural home; design confirms), including when it is 0.
- **FR-003 (event-driven):** WHEN the board refreshes (watcher event, manual Refresh, layout switch), THEN every WIP count SHALL be recomputed from that refresh's snapshot with the same row-set semantics as the existing column counts — a WIP count never disagrees with the cards the board currently shows.
- **FR-004 (unwanted):** IF the WIP count takes any value (including values above the fleet's concurrency setting or any other number), THEN the board SHALL NOT warn, recolor, gate, block or otherwise change behavior because of it — no WIP-limit concept exists in this feature (no maximum, no threshold, no setting, no tool).
- **FR-005 (state-driven):** WHILE the flat layout's `in-progress` column is hidden (hidden-statuses filter) or the epic-swimlane layout is active, WHEN tickets are in progress, THEN the board-wide WIP count (FR-002) SHALL still show them — the figure survives layout and status-filter hiding.

### Feature B — V chevron connectors (reading order 1→10)

- **FR-006 (ubiquitous):** THE V-model stages layout SHALL render a directional chevron connector between each consecutive stage pair along the reading order — 1→2→3→4→5 down the definition leg, the 5→6 vertex turn, and 6→7→8→9→10 up the verification leg (nine connectors) — with placement derived from `VStageLayout`/`VStages`, never a second hard-coded sequence. The existing numbered headers (`1 · requirements` …) and their "Stage n of 10" tooltips remain unchanged; the chevrons supplement, not replace them.
- **FR-007 (ubiquitous):** THE chevron connectors SHALL point in the reading direction: downward along the definition leg, across the vertex turn toward the verification leg, and upward along the verification leg.
- **FR-008 (unwanted):** IF chevron connectors are rendered, THEN they SHALL NOT change column geometry (fixed-width, always-render V cells), drag-drop targets, tooltips, or any interactive behavior of the board — they are pure decoration.
- **FR-009 (unwanted):** IF a column carries a loud signal (red blocked count, blocked rows), THEN the chevrons SHALL NOT use that signal's styling — not red, not bold — ticket content and blocked state stay the loudest things on the board.
- **FR-010 (ubiquitous):** THE chevron chain SHALL render whenever the V-model stages layout renders, including on a fully empty board — the V gestalt holds on an empty board exactly as the always-render columns do (U-016 doctrine).

## Non-Functional Requirements
- **NFR-PERF-001:** WIP counts SHALL derive from the snapshot a refresh already computes (no additional store reads); chevrons are static decoration — neither may add measurable refresh or layout time.
- **NFR-COMPAT-001:** All existing board behavior SHALL remain unchanged: layouts, drag-drop (status drops, stage drops, the send-back reason contract), filters (blocked-only, bugs-only, text filter, stage visibility, hidden statuses), persisted settings, epic swimlanes, the untracked and archive rows. No new persisted setting is introduced — chevrons and WIP counts are always on.
- **NFR-A11Y-001:** The reading order stays carried by the numbered headers and their tooltips; chevrons are decorative redundancy, so non-visual access loses nothing.

## Constraints & Assumptions
- **C-001:** Board-side only: the `com.opencode.ide.board` bundle's SWT-free model projections plus the Board view rendering. No task-store schema, `task_*` tool, fleet or dispatch change (the count never gates anything — see FR-004).
- **C-002:** `VStageLayout`/`VStages` stays the single source of the V geometry and reading order; the view derives connector placement from it (U-016 doctrine: geometry cannot drift from the canonical ladder).
- **C-003:** Explicitly secondary to U-026 — on any board-surface collision, U-026 wins; this ticket adapts or descopes (see *Scope & Priority*).
- **C-004 (assumption):** "WIP" means status `in-progress`, nothing finer — the count deliberately does not distinguish fleet-claimed running agents from other in-progress claims (open question Q-003).

## Acceptance Criteria

- **AC-001 (FR-001 — per-column WIP):** Given the V-model stages layout and a project with two `in-progress` tickets in `architecture`, one in `test-design` and none in any other stage, when the board renders, then the `architecture` column header shows in-progress count 2, `test-design` shows 1, every other stage column shows 0 — each alongside its existing total/blocked counts.
- **AC-002 (FR-002 — board-wide WIP):** Given any Group-by layout and a project with exactly four tickets in status `in-progress`, when the board renders, then the board-wide WIP surface shows 4.
- **AC-003 (FR-003 — live refresh):** Given the board showing WIP 2, when a peer agent claims another ticket (a watcher event moves one ticket from `sprint-backlog` to `in-progress`), then after the automatic refresh every WIP surface shows 3 with no manual Refresh.
- **AC-004 (FR-004 — no limits concept):** Given any number of in-progress tickets (e.g. more than the fleet's concurrency setting), when the board renders, then the WIP counts display plainly — no threshold styling, warning, dialog, or gating of launches and drag-drops — and no WIP-limit setting or tool exists.
- **AC-005 (FR-005 — survives filters/layouts):** Given the flat layout with the `in-progress` column hidden and three tickets in progress, when the board renders, then the board-wide WIP surface still shows 3.
- **AC-006 (FR-006, FR-007 — the chain):** Given the V-model stages layout, when it renders, then all nine consecutive-pair connectors along the reading order are present — 1→2, 2→3, 3→4, 4→5 (definition leg), 5→6 (vertex turn), 6→7, 7→8, 8→9, 9→10 (verification leg) — each pointing in the reading direction (down, across, up).
- **AC-007 (FR-008 — geometry & behavior stable):** Given the V-model stages layout before and after this feature, when a user drags a ticket onto a stage column (forward drop or backward drop with send-back reason), then column geometry (fixed width, always-render), drop targets and the send-back contract behave exactly as before — the existing board test suite stays green.
- **AC-008 (FR-009 — visual hierarchy):** Given a stage column with blocked tickets (red blocked count), when the board renders with chevrons, then the blocked count remains red/bold and the chevrons use neither red nor bold styling.
- **AC-009 (FR-010 — empty board):** Given an empty board (a sprint with no tickets, or an empty store), when the V-model stages layout renders, then all ten stage columns and the full nine-connector chevron chain still render.
- **AC-010 (ticket AC 2 — scope, doc-level):** Given these requirements, then the scope is the two polish features only, U-026 is named primary (*Scope & Priority*, C-003, Non-Goals), and no store/fleet/tool surface is added (C-001) — small by construction.

## Open Questions
- **Q-001:** Exact chevron placement and visual form (gap between stacked column cells, header-edge markers, or an overlay) — a design-stage decision; requirements fix only the nine connectors, their directions, and the hierarchy rules.
- **Q-002:** Exact home and format of the board-wide WIP figure (extending the readiness badge to `n ready · m stale · w wip` vs a sibling label) — a design-stage decision.
- **Q-003:** Should a future WIP surface distinguish fleet-claimed running agents from other in-progress claims (`PeerJobReconstructor` data)? Deliberately out of scope here (C-004); revisit together with any future WIP-limits ticket.
- **Q-004:** Do chevrons need a visibility toggle? Assumed no — always-on, quiet decoration; revisit only if live use complains.

## Non-Goals
- **No WIP limits** — no maximum, threshold, warning or policy anywhere; the kanban WIP-limit concept is deliberately absent "yet" (a future ticket would own it).
- No fleet-liveness distinction in the WIP count — count is by status only (see Q-003).
- No per-lane WIP counts in the epic-swimlanes layout — the board-wide surface covers it.
- No V minimap (U-018's rejected alternative), no animation, no click/navigation on chevrons, no chevron toggle setting.
- No U-026 surfaces: per-card stage progress (e.g. 4/10), movement traces, send-back event visibility, per-wave movement digests — those are U-026's; no change to readiness badges, blocked styling, or the numbered headers' semantics.
- No change to the flat Progress layout's existing column counts (its `in-progress (n)` header already reports that column's WIP).

## Traceability
| Ticket AC | Covered by |
|---|---|
| 1 — WIP visibility on in-progress, count only, no limits concept yet | FR-001–FR-005; AC-001–AC-005 |
| 1 — chevron connectors along the V reading order (1→10) on the board | FR-006–FR-010; AC-006–AC-009 |
| 2 — small scope, explicitly secondary to U-026 | *Scope & Priority*; C-001, C-003; Non-Goals; AC-010 |
