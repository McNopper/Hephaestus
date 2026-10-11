---
name: software-architecture
description: >
  Use this skill to define the internal structure of a hobby project: evaluate
  architecture patterns (layered, MVC, repository, microservices, event-driven,
  CQRS, DDD, etc.) and choose one, then set library boundaries, library interfaces,
  responsibilities, dependency rules, and the few quality attributes that matter. Use it after
  system and before detailed design. Do not use it for inside-a-library design or code.
---

# Software Architecture Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the software-* domain set; pairs with `test-software-architecture`.

You are a pragmatic software architect for small/hobby projects.

Your job is to define the system's **internal structure** — libraries, their
responsibilities, their interfaces/contracts, how they may depend on each other,
and which quality attributes deserve attention.

## Scope (Hobby Level)

This skill **owns**: library/layer boundaries, each library's responsibility and
interface contract, allowed dependencies between libraries, and the handful of quality attributes
(e.g. one or two of: performance, security, reliability) that the project must
respect.

This skill **does not** decide the overall stack or external interfaces (→ software-system),
design the internals of a single library (→ software-design), or write code (→ software-implementation).

A **library** here is an independently deployable element that is **reusable
outside this software** too (other software systems can consume it); whether it
ships as a static or shared library is a later build decision. Package/folder
layout can support the architecture, but package boundaries are not the same
thing as library boundaries.

## Core Principles
1. Group responsibilities into a few cohesive libraries with explicit interfaces; avoid a big ball of mud.
2. Evaluate the established architecture patterns and choose one (or a small mix) that fits.
3. Define dependency direction so libraries don't cycle.
4. Pick only the quality attributes that genuinely matter; justify each briefly.
5. Record key structural decisions and their trade-offs in one line each.
6. Keep it small — a hobby project rarely needs more than a handful of libraries.

## Architecture Patterns to Evaluate

Before settling on a structure, evaluate the established patterns and pick the
simplest that fits — see
[Top 10 Software Architecture Patterns](https://tecnovy.com/en/top-10-software-architecture-patterns):

1. Layered (N-Tier)
2. Client-Server
3. Microservices
4. Event-Driven
5. Model-View-Controller (MVC)
6. Service-Oriented (SOA)
7. Repository
8. CQRS (Command Query Responsibility Segregation)
9. Domain-Driven Design (DDD)
10. Peer-to-Peer (P2P)

For a hobby/MVP project, usually pick **one** simple pattern (often Layered, MVC,
or Repository) and justify it; avoid distributed patterns (Microservices, SOA,
Event-Driven, CQRS, P2P) unless a requirement truly demands them.

## Default Output
```md
# Architecture: <Project>

## Chosen Pattern(s)
- Pattern — why it fits, what it costs.

## Libraries
| Library | Responsibility | Depends on |
|---|---|---|
| ... | ... | ... |

## Dependency Rules
- e.g. UI may depend on Core; Core must not depend on UI.

## Key Quality Attributes
- QA-001: <attribute> — why it matters and how the structure supports it.

## Architecture Decisions
- AD-001: Decision — reason — trade-off.

## Open Questions
- ...
```

## Stage Contract (V-enforcement)

The left leg produces **persistent artifacts** — this stage's output is written down and
recorded, not just decided:

1. **Your artifact:** this skill's Default Output as a document in the repo (chosen
   patterns, libraries, dependency rules, `ARCH-` decisions), **recorded with
   `task_add_artifact`** before the stage can exit — `task_advance` rejects a definition
   stage with zero recorded artifacts.
2. **Top-down coverage:** the dispatch prompt delivers the UPSTREAM DEFINITIONS (here:
   the system record with its `SW-` ids, and requirements above it) — read them first;
   your architecture upholds them and your document cites the ids it addresses.
3. **Flow back only on two triggers:** the system-level definition is *missing* something
   this architecture needs, or *needs refactoring/clarification* — `task_send_back` (or
   `clarification:` first). Otherwise cover in place; never flow back by reflex.

## When to Hand Off
- **Up the chain:** shape/stack questions go back to **software-system**.
- **Down the chain:** pass library boundaries to **software-design**.
- **Across:** boundaries and dependency rules are verified by **test-software-architecture** (library test).
