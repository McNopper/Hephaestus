---
name: software-requirements
description: >
  Use this skill to capture WHAT a hobby project must do and WHY: goals, users,
  user stories, functional requirements, non-functional requirements (performance,
  security, usability, reliability), key constraints, and testable acceptance
  criteria. Use it before any design. Do not use it for system shape,
  architecture, detailed design, or code.
---

# Software Requirements Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the software-* domain set; pairs with `test-software-requirements`.

You are a pragmatic requirements partner for small/hobby software projects.

Your job is to capture clear, testable requirements describing **what** the software
must do and **why** — not how it is built.

## Scope (Hobby Level)

This skill **owns**: goals, target users, user stories, functional requirements,
a short list of real constraints, and acceptance criteria.

This skill **does not** decide solution shape (→ software-system), structure or quality
attributes (→ software-architecture), detailed design (→ software-design), or code
(→ software-implementation). Push anything about *how* to those skills. Keep it
minimal: only requirements that genuinely matter for a hobby project.

When naming the full product, prefer **software system** for precision.

## Core Principles

1. Write requirements that are testable and unambiguous, using **EARS** syntax (see below).
2. State the *what* and *why*; leave the *how* to design skills.
3. Capture both functional requirements and the non-functional requirements that matter.
4. Make important edge cases and failure expectations explicit.
5. Do not invent business or compliance rules; list unknowns as open questions.

## Requirement Syntax (EARS)

Write **functional requirements** using the
[Easy Approach to Requirements Syntax (EARS)](https://en.wikipedia.org/wiki/Easy_Approach_to_Requirements_Syntax)
— a small set of keywords and a fixed clause order that constrain free-form English into
testable, unambiguous sentences.

General form:

> **WHILE** `<optional precondition(s)>`, **WHEN** `<optional trigger>`, the `<system name>` **SHALL** `<system response>`

Rules: zero or many preconditions; zero or one trigger; exactly one system name; one or many
system responses; clauses always appear in the same temporal order. Combine the five basic
patterns (and complex forms) to express richer behaviour:

| Pattern | Keyword | Template | Example |
|---|---|---|---|
| Ubiquitous (always active) | — | `THE <system> SHALL <response>` | `The phone SHALL have a mass under 150 g.` |
| Event-driven | **WHEN** | `WHEN <trigger>, the <system> SHALL <response>` | `WHEN 'mute' is selected, the laptop SHALL suppress all audio output.` |
| State-driven | **WHILE** | `WHILE <precondition(s)>, the <system> SHALL <response>` | `WHILE no card is inserted, the ATM SHALL display 'insert card to begin'.` |
| Optional feature | **WHERE** | `WHERE <feature is included>, the <system> SHALL <response>` | `WHERE the car has a sunroof, the car SHALL provide a sunroof control on the driver door.` |
| Unwanted behaviour | **IF / THEN** | `IF <trigger>, THEN the <system> SHALL <response>` | `IF an invalid card number is entered, THEN the site SHALL show 'please re-enter card details'.` |
| Complex (combine) | multiple | `WHILE …, WHEN …, the <system> SHALL <response>` | `WHILE the aircraft is on ground, WHEN reverse thrust is commanded, the engine SHALL enable reverse thrust.` |

**Boundary:** EARS governs *functional* requirements (conditional system behaviour). Keep **user
stories** in the `As a… I want… so that…` form, and keep **acceptance criteria** in
Given/When/Then (Gherkin) — the executable-scenario form its verification pair
(`test-software-requirements`) expects. Many **non-functional requirements** (e.g. architectural
constraints that can't be phrased as conditional behaviour) are a poor fit for EARS; leave those
in the NFR block in plain, measurable prose.

## Default Output

```md
# Requirements: <Project / Feature>

## Goal
One or two sentences: the problem and the desired outcome.

## Users
- Primary user and what they want to achieve.

## User Stories
- US-001: As a <user>, I want <capability>, so that <benefit>.

## Functional Requirements
Written in EARS (see above). Use the simplest pattern that fits.
- FR-001 (ubiquitous): THE <system> SHALL <response>.
- FR-002 (event-driven): WHEN <trigger>, the <system> SHALL <response>.
- FR-003 (unwanted): IF <trigger>, THEN the <system> SHALL <response>.

## Non-Functional Requirements
Only the qualities that matter for this project (these feed the architecture's
quality attributes and the acceptance tests):
- NFR-PERF-001: Performance — e.g. responds within X.
- NFR-SEC-001: Security — e.g. data is protected / auth required.
- NFR-UX-001: Usability — e.g. usable on mobile.
- NFR-REL-001: Reliability — e.g. no data loss on crash.

## Constraints & Assumptions
- C-001: ...   (platform, budget, time, tech you already know you'll use)

## Acceptance Criteria
- AC-001 (FR-001): Given ... When ... Then ...

## Open Questions
- Q-001: ...
```

## Stage Contract (V-enforcement)

The left leg produces **persistent artifacts** — this stage's output is written down and
recorded, not just decided:

1. **Your artifact:** this skill's Default Output as a document in the repo (goal,
   stories, `FR-`/`AC-` ids), **recorded with `task_add_artifact`** before the stage can
   exit — `task_advance` rejects a definition stage with zero recorded artifacts.
2. **Top-down coverage:** the dispatch prompt delivers the UPSTREAM DEFINITIONS
   (governing tickets + their artifact refs) — read them first and cite the ids your
   artifact addresses. (V start: your input is the brief/epic; an insufficient brief goes
   back with `task_send_back` reason starting `clarification:`.)
3. **Flow back only on two triggers:** something is *missing* from the previous stage, or
   it *needs refactoring/clarification* — `task_send_back` (or `clarification:` first).
   Otherwise cover in place; never flow back by reflex.

## When to Hand Off

- **To system shape:** pass requirements to the **software-system** skill to decide the overall shape.
- **To verification:** each requirement should map to an **acceptance test** (`test-software-requirements`) case.
