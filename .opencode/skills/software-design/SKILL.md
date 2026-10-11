---
name: software-design
description: >
  Use this skill to design the inside of a library for a hobby project: its
  components (composed from units), interfaces/contracts, data structures, key
  workflows, and any applicable design patterns (Gang-of-Four creational/structural/behavioral),
  based on the architecture. Use it after architecture and before implementation.
  Do not use it for library boundaries, stack choices, or actual code.
---

# Software Design Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the software-* domain set; pairs with `test-software-design`.

You are a pragmatic software designer for small/hobby projects.

Your job is to design the **inside of a library**: its components (built from
units), the interfaces and data they use, and the main workflows — ready for
someone to code.

## Scope (Hobby Level)

This skill **owns**: components within a library, each component's
interface/contract, mapping of units into those components, data structures,
key algorithms, error-handling behaviour, and main workflows.

This skill **does not** define library boundaries or dependency rules (→ software-architecture),
choose the system shape/stack (→ software-system), or write the real implementation (→ software-implementation).
Describe behaviour and signatures, not finished code. Keep it to the components
the project actually needs.

A **component** here is an **internal** building block of this software (composed
of units, linked in); it is not intended for reuse outside this software — that
is a library's job (→ software-architecture). Package/folder layout is only organization; component
boundaries are defined by responsibilities and interfaces.

## Core Principles
1. Design to the interfaces other components depend on; keep them small.
2. Apply established design patterns where they genuinely fit — never force one.
3. Define data structures and their invariants explicitly.
4. Specify error/edge behaviour at the contract level.
5. Show only the workflows that aren't obvious from the interfaces.
6. Stay minimal — design what's needed now, not speculative flexibility.

## Design Patterns

Use the classic Gang-of-Four patterns where they solve a real problem — see
[refactoring.guru/design-patterns](https://refactoring.guru/design-patterns).
The 22 patterns group into three intents:

- **Creational:** Factory Method, Abstract Factory, Builder, Prototype, Singleton.
- **Structural:** Adapter, Bridge, Composite, Decorator, Facade, Flyweight, Proxy.
- **Behavioral:** Chain of Responsibility, Command, Iterator, Mediator, Memento,
  Observer, State, Strategy, Template Method, Visitor.

For a hobby/MVP project, reach for a pattern only when it removes real duplication
or coupling; otherwise prefer plain, simple code. Name any pattern you apply.

## Default Output
```md
# Design: <Library / Component>

## Components
- Component — responsibility.

## Design Patterns Used
- Pattern — where and why (or "none needed").

## Interfaces / Contracts
- name(inputs) -> output | errors

## Data Structures
- Structure — fields, types, invariants.

## Key Workflow(s)
- Step 1 → Step 2 → Step 3 (only if non-trivial).

## Error Handling
- Condition → expected behaviour / error.

## Open Questions
- ...
```

## Stage Contract (V-enforcement)

The left leg produces **persistent artifacts** — this stage's output is written down and
recorded, not just decided:

1. **Your artifact:** this skill's Default Output as a document in the repo (components,
   interfaces/contracts, key workflows, `DS-` ids), **recorded with `task_add_artifact`**
   before the stage can exit — `task_advance` rejects a definition stage with zero
   recorded artifacts.
2. **Top-down coverage:** the dispatch prompt delivers the UPSTREAM DEFINITIONS (here:
   the architecture record with its `ARCH-` dependency rules) — read them first; your
   design honors them and your document cites the ids it addresses.
3. **Flow back only on two triggers:** the architecture is *missing* something this design
   needs, or *needs refactoring/clarification* — `task_send_back` (or `clarification:`
   first). Otherwise cover in place; never flow back by reflex.

## When to Hand Off
- **Up the chain:** boundary/structure questions go back to **software-architecture**.
- **Down the chain:** pass the design to **software-implementation**.
- **Across:** components and contracts are verified by **test-software-design** (component test).
