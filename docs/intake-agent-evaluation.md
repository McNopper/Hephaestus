# Intake agent evaluation — enforcing the intake boundary by role (T-036)

## About this document

- **Kind:** `doc` / evaluation — the requirements-stage artifact of ticket T-036
  (basis U-112): it evaluates the dedicated intake agent in design (definition,
  prompt, boundary enforcement) and states a build/defer recommendation.
- **Read by:** the maintainer (owns the build/defer call), the PM and the tickers
  (intake dispatch), any agent touching the chat-first control plane.
- **Related:** `AGENTS.md` §Chat-first control plane (the intake-discipline
  paragraph this evaluation backs); `.opencode/agent/product-owner.md` (the
  evaluated agent definition); ticket T-035 (the retro-fit provenance pattern —
  referenced, not re-documented); `docs/HISTORY.md` (the one-line doctrine record).

## Goal

Enforce the intake boundary **by role, not only by discipline**: a dedicated
intake-role agent owns registration of owner input so the chat-side agent
structurally cannot implement directly — closing the gap that produced the two
violations of 2026-11-14 (the unified-ticker prototype; the AGENTS.md intake
edit) before this requirement landed.

## Requirement (owner, 2026-11-14, T-036)

Things the owner types are requirements or bugs; they go through the harness
(store -> tickers -> workers -> engine verdicts). Functional requirements, EARS:

- FR-1 (ubiquitous): THE chat control plane SHALL treat owner chat input as
  requirements or bugs only.
- FR-2 (event-driven): WHEN the owner posts a requirement or bug, the intake
  role SHALL register it as a ticket with distilled acceptance criteria and
  cite the ticket id back to the owner.
- FR-3 (unwanted): IF a chat-side agent implements an owner request directly,
  THEN the process SHALL treat the act as a process violation (the work
  re-enters through the store; it is not shipped as chat edits).
- NFR-1 (robustness): enforcement SHOULD not depend on the acting model's
  self-discipline — a role-level tool profile beats a prompt-level admonition.

## Provenance (retro-fit pattern, per T-035)

Same pattern as T-035: the mechanism preceded its ticket and the provenance is
recorded honestly on the ticket — this ticket specifies, evaluates and verifies
that mechanism, it does not rebuild it. Concretely: the two violations above
happened before the requirement landed; the owner override then created the
seat directly (commit `7aab59b`, `.opencode/agent/intake.md`) and the owner
directed its rename to `product-owner` (commit `55f2823`). See T-035 for the
pattern's canonical statement; this document applies it, not re-documents it.

## The evaluated agent (definition as found in the tree)

`.opencode/agent/product-owner.md` — the Product Owner's seat, `mode: primary`,
tools denied at role level: `write`, `edit`, `patch`, `bash`. Per-message loop:
classify (requirement / bug / process rule / question) -> distill acceptance
criteria -> link (`basis`) -> register and cite the ticket id -> verify
machine output from store evidence (`task_board`, `task_readiness`, `task_get`,
`task_doctor`). Governance duties: chain coherence, honest NEEDS-HUMAN
surfacing, machine-keeping ops (re-arm the tickers, reconnect the tasks
server, deploy gate jars). Boundaries: never implement, never self-declare
done, never spawn fulfillment workers, record provenance honestly.

## Prompt sketch

The live file IS the prompt; the sketch below is the evaluated shape
(distilled, not a duplicate):

- Role statement: "You are the single point of entry. Everything typed here is
  a requirement or a bug. You are not in Build — your tool profile makes
  implementation impossible."
- Per-message contract: classify, ask at most a couple of clarifying
  questions, register with testable acceptance criteria, link, cite the id,
  verify with `task_*` evidence — status claims cite tool output, never
  intent.
- Refusal pattern: any request to "just fix/edit/build it" answers with the
  registration offer ("I file this as a ticket; ticker-v's workers implement")
  — never with an edit.
- Escalation pattern: NEEDS-HUMAN as a blocked ticket stating the decision
  needed, never build instructions.

## Boundary enforcement: by role vs by discipline

- **By discipline** (prompts/skills only): the chat agent is told not to
  implement. Zero setup, but one convincing request away from violation —
  exactly what failed twice on 2026-11-14.
- **By role** (agent definition): `write`/`edit`/`patch`/`bash` denied in the
  agent frontmatter; the agent cannot edit files even if asked. Violation
  requires deliberately switching agents — and the override rule (the owner
  CAN enter Build by choice) stays intact, because it is the owner's switch,
  not the agent's.
- **Residual gap (honest):** role-level denial binds only sessions running
  the `product-owner` agent; a plain chat session stays unrestricted. The
  doctrine paragraph in AGENTS.md (the discipline layer) therefore remains
  necessary as the second layer. Role + doctrine together are the design;
  either alone is weaker.

## Recommendation: BUILD (validated retro-fit) — keep the seat

The seat exists by owner override; evaluated on the merits, keeping it is
right:

- Role-level denial is the only enforcement that does not depend on model
  discipline — and discipline-only demonstrably failed twice.
- Cost is one agent definition (already paid) and one `/agents` switch per
  owner session; deferral has no surviving case, it would leave exactly the
  failed layer in place.
- Follow-through in this ticket's lane: AGENTS.md states the doctrine for
  every chat-side session; the seat enforces it structurally for intake
  sessions.

## Spot-check rule (post-landing compliance check)

After this doctrine lands, a review pass can verify from store and git
evidence alone that no direct chat-agent implementations occurred. For a
review window:

1. Every requirement/bug the owner posted traces to a registered ticket whose
   derivation chain flows through the V (registration -> stages -> workers ->
   verdicts); implementation work appearing outside ticket merge-backs is a
   violation signal.
2. Ticket artifacts and history `by` fields on implementation lanes cite
   worker/ticker sessions, never a chat-side session (`task_get`,
   `task_board` provide the evidence).
3. Git history on implementation lanes shows changes arriving as
   fleet/worktree merge-back commits, not as direct chat-session commits.

Run it as part of an acceptance review or retro; a hit means rework through
the store plus a doctrine reminder — not a silent pass.

## Acceptance criteria mapping (T-036)

- AC-1 (AGENTS.md boundary statement): the chat-first section's
  intake-discipline paragraph (this change set).
- AC-2 (evaluation with build/defer recommendation): this document,
  Recommendation section.
- AC-3 (spot-check rule stated): this document, Spot-check rule section.
- AC-4 (retro-fit pattern stays documented): referenced via T-035 in the
  Provenance section — referenced, not duplicated.

## Open questions

- Q-1: none blocking. Naming note (recorded, owner's call, not this ticket's
  lane): the owner comment of 2026-11-14 named the seat `intake.md`; it lives
  as `product-owner.md` after the owner-directed rename (commit `55f2823`).
