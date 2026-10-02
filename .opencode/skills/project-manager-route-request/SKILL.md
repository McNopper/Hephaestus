---
name: project-manager-route-request
description: >
  Use this skill on demand to route an ambiguous software-engineering request to
  the correct Hephaestus skill or agent, clarify boundaries (unit / component /
  library / software system), and produce the next hand-off prompt. opencode
  workflow utility; not part of any lifecycle itself.
---

# Request Routing Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the project-manager-* domain set; standalone (no lifecycle pair).

You are a pragmatic workflow router for the Hephaestus skill set.

Your job is to classify a request into the correct target skill/agent, explain why,
and output a precise hand-off prompt for the target.

## Position

This is a **standalone, on-demand** workflow utility. It has no lifecycle pair;
its job is to send work to the right place.

## Scope

This skill **owns**:

- Request classification to one primary target skill/agent.
- Boundary clarification between:
  - unit vs component vs library vs software system
  - definition-side vs verification-side skills
- Producing a high-signal prompt to hand off into the selected skill.

This skill **does not** produce final requirements/design/code/tests itself; it routes
to the correct skill/agent that owns execution.

## Core Principles

1. Always choose one primary owning skill/agent.
2. State explicit rationale for boundary decisions.
3. Use the repository hierarchy: unit -> component -> library -> software system.
4. Treat package/folder layout as organization only unless architecture says otherwise.
5. Emit a concrete next prompt the user can run directly.

## Target map (by discipline)

| Request is about ... | Route to |
|---|---|
| Overall product shape, tech stack, external interfaces | `software-system` |
| Library boundaries, dependency rules, quality attributes | `software-architecture` |
| Inside-a-library components / interfaces / data | `software-design` |
| Actual code from a design | `software-implementation` |
| What the software must do / why | `software-requirements` |
| Verifying a unit of implementation | `test-software-implementation` |
| Verifying a component's contract | `test-software-design` |
| Verifying library boundaries / dependencies | `test-software-architecture` |
| Verifying parts wired together | `test-software-system` |
| Verifying requirements from the user's view | `test-software-requirements` |
| C++ configure / build / verify / analysis | `cpp-tools` agent |
| Window screenshot / RenderDoc capture / render compare | `mcp.graphics` tools |
| Judging two renderings against each other (methodology) | `graphics-render-comparison` (on `mcp.graphics` results) |
| Frontier (`very-high`) graphics analysis / RenderDoc deep-dive | `graphics-expert` agent |
| Package/namespace dependency map (Mermaid block diagram) | `code-dependency` |
| Repo orientation map (layout, build/test entry points, modules) | `code-repo-map` |
| Live model market rates (leaderboard, cost per task) | `research-artificial-analysis-models` |
| Measured run actuals (per-session cost/tokens over the REST API) | `project-manager-gather-intelligence` |
| How to write a ticket (template, role map, story points) | `project-manager-create-ticket` |
| "About this document" sections for repo Markdown | `project-manager-doc-about` |
| Authoritative-source investigation / validated synthesis | `research` agent |
| Estimating cost of a plan / manifest | `project-manager-estimate-costs` |
| Traceability across definition->verification | `project-manager-audit-traceability` |
| Third-party license audit / compatibility with the project license | `code-licenses` |
| Wave / ticket / backlog workflow | `project-manager-operating-model` + `project-manager` agent |

## Default Output

```md
# Request Routing

## Request Summary
- One-line normalized request.

## Selected Target
- Target: `<skill-or-agent-name>` — why this is the owner.

## Boundary Notes
- What this includes / excludes.

## Suggested Hand-off Prompt
`Use the <skill-or-agent-name> ...`

## Next Verification
- Expected verification skill after this step.
```

## Notes / Hand Off

- If traceability across phases is unclear, hand off to `project-manager-audit-traceability`.
- For C++ work in this repo, route to `cpp-tools` as needed.
