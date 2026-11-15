# Product Goal — project `hephaestus`

- **Kind:** seed doc / the Scrum 2020 **Product Goal** for this task-store project (rides here until the store schema earns a first-class field; `_`-prefixed files are ignored by the task loader).
- **Read by:** the PM agent, workers, and humans reading the board.
- **Written by:** the Product Owner (human) via the PM agent.

## Goal

Hephaestus is the **agentic harness**: intelligence lives in `.opencode/` (agents, skills)
and MCP servers; the Eclipse plugin (`eclipse/`) hosts, services, and observes headless
agents working in isolated git worktrees. The human uses Eclipse as PM + reviewer; agents
self-organize via the task board. From this point on **the harness develops itself through
its own board** (ROADMAP.md `H1.7`).

Epics (`E-00x`) mirror the repository ROADMAP.md phases and are **interim value chunks**
toward this goal — they are not the final product:

| Epic | Mirrors | Theme |
|---|---|---|
| `E-001` | ROADMAP H1 | Task board on Maven storage — finish: FleetRunner v2, Maven mojos, dogfood cutover |
| `E-002` | ROADMAP H2 | Eclipse surfaces: PM Board view, Fleet view, provider logos |
| `E-003` | ROADMAP H3 | Scale & depth: plural connections, virtualized viewers, SSE-driven fleet completion |
| `E-004` | ROADMAP H4 | CDT integration & chat polish |
| `E-005` | Standing | Engineering health: refactor cadence, cost telemetry → estimation |

*(H5–H7, 2026-08-23 → 2026-08-25, ran as direct session waves **off the board** — see
ROADMAP.md history; V-001..V-003 + S-03 were reconciled as done on 2026-08-25. From
ROADMAP **Milestone V** on, new work goes through this board again: seed tickets per
milestone — V validation run, P permissions & safety, U UI pass, H hygiene.)*

Prime rule (from ROADMAP.md): never build in the plugin what Hephaestus already provides —
and what it provides moves into the Maven/Eclipse world when we touch it.
