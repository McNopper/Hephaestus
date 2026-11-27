# Fleet quickstart - RETIRED 2026-11-14

## About this document

- **What it was:** RETIRED - the newcomer how-to for the
  task fleet's headless flow (seed ticket → dispatch → poll → merge →
  verify — FleetRunner dispatch, waves, watchdog, worktree merge-back),
  written **2026-08-28** (ticket V-005; the record's landing is in
  `docs/HISTORY.md`, "Milestone V — First real fleet run ✅ PASSED
  2026-08-28": "The quickstart landed as `docs/fleet-quickstart.md`").
- **Retired because:** the fleet was removed in the U-110 arc — live
  acceptance evidence in ticket U-111 (AE-6), design in
  `docs/design-stage-pipeline.md` section 9; the fleet-redundancy review
  (`docs/fleet-redundancy-review-2026-11.md`, 2026-11-09) had already shown
  opencode's native primitives covering the execution layer. The dead
  procedure body was deleted 2026-11-26 (T-233, docs consolidation lane
  T-d) and survives only in git history
  (`git log -- docs/fleet-quickstart.md`).
- **The replacement:** the linked stage pipeline - ONE steward session + ONE
  `experimental.automations` automation (`ticker-v`) armed with
  `.opencode/skills/stage-pipeline/ticker-setup.ps1`; each tick walks all V
  stages in canonical order, procedure in the stage-pipeline skill, store
  evidence in ticket U-111 (AE-6).
- **Per-stage agents (U-104):** each V stage's workers spawn with their own
  agent definition (`.opencode/agent/hephaestus-*.md`, the STAGE-AGENT MAP in
  the stage-pipeline skill), overridable store-side with
  `task_set_stage_agent` / `task_stage_agents` - no file edits; a ticket's
  `model` field pins the head.
- **Goal intake (U-105):** `/goal <objective>` in the chat is captured
  CLIENT-SIDE as a requirements ticket (objective verbatim as the definition
  artifact; the raw server self-review loop is never driven); `/goal
  pause|resume|clear` map to store semantics (paused ticket / back to the
  pump / close-or-split with every basis chain kept valid); the goal is done
  ONLY on the engine's verdict.
- **Original record:** 2026-08-28 (bullet 1); retired 2026-11-14; body
  removed 2026-11-26 (T-233).

See also: `docs/design-stage-pipeline.md` (the pipeline that replaced the
fleet), the `stage-pipeline` skill, and `AGENTS.md` (states, the V-pipeline
stages, and the chat-first control plane).
