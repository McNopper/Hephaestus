---
name: project-manager-doc-about
description: >
  Use this skill as the standard for making every Markdown document in this repo
  self-explaining: a brief "About this document" section near the top that states what
  the file IS (skill, agent, roadmap, ticket note, spec, decision, …), who reads it, and
  how it relates to neighbours. Invoked by any agent that creates or rewrites a .md file,
  so interpretation never depends on the filename alone. This skill is INDEPENDENT of the
  PM/ticket system — a human or agent can use it on its own to author a standalone doc.
---

# Document Self-Description (About this document)

## About this document
- **Kind:** `skill` (reusable capability, auto-loaded by opencode)
- **Read by:** any agent that creates or rewrites a Markdown file; also humans authoring docs; **written by:** maintainers
- **Related:** part of the `project-manager-*` domain set; standalone (no lifecycle pair) — works independently of the PM/ticket system.

You are the **documentation clarity** guide for Hephaestus. In an agentic workflow a repo
accumulates many Markdown files whose meaning is otherwise inferred from the *filename*.
That is fragile: a file gets renamed, moved, or read out of context and its purpose is lost.
This skill makes **every Markdown document** in the repo carry its meaning **inside itself** —
the rule applies to any `*.md` file, not just skills or agents.

## Why this exists

- Agents and humans open files by path, not by a table of contents.
- Filenames are overloaded (`foo.md` could be a skill, a spec, a note, a decision).
- Cross-references break when files move; an in-body description survives the move.

## Independence from the PM system

This skill does **not** require tickets, waves, or the task store (`task_*` tools). It is a plain
authoring standard you (or any agent) can apply to **any** Markdown file:

- A **human** can invoke `project-manager-doc-about` directly to write a standalone document (a spec,
  a design note, meeting minutes) and just fill in the `About` section — no PM workflow,
  no ticket.
- An **agent** can use it the same way, or as part of a PM-driven task. Both are fine.
- If you do use the PM system, mirror the document `kind` into the ticket's `task_add_artifact`
  (`kind=doc`) so the ticket and the file agree — but that step is optional.

The PM/ticket workflow is itself **optional** in Hephaestus: skills and agents can be used
directly by a human (or by another agent) without ever creating a ticket. Use the PM system
when you want tracked, multi-agent, wave-based execution; skip it for ad-hoc work.

## The rule

**Every standalone Markdown document starts with an `## About this document` section**
(immediately after the H1 title) that states, in 1–4 lines:

1. **What it is** — the document *kind* (see the vocabulary below).
2. **Who reads / writes it** — human, a specific agent/role, or both.
3. **How it relates** — the parent or sibling documents it belongs with (one line).

Keep it short and factual. No philosophy; just enough that a reader who landed here by
accident knows what they're looking at.

## Document-kind vocabulary

Use one of these (or a close, clearly-named variant) as the `kind`:

| kind | meaning | typical owner/reader |
|---|---|---|
| `skill` | a `SKILL.md` defining a reusable capability | agents (auto-loaded) |
| `agent` | a `.opencode/agent/*.md` coordination/domain agent | orchestrator / workers |
| `roadmap` | the time plan / Gantt of milestones | human + PM |
| `board` | live Kanban aggregating work | human + PM |
| `status` | human-readable progress narrative | human |
| `decisions` | escalations the human must decide | human → PM |
| `brief` | the human's mandate / goal (write-once) | human (PM reads) |
| `spec` / `requirements` | what the software must do / why | developer / tester |
| `design` | how a component is built | developer / architect |
| `architecture` | library boundaries & dependency rules | architect |
| `ticket-note` | a comment/artifact attached to a ticket | PM / worker |
| `run-log` | an autonomous session's execution record | PM / orchestrator |
| `doc` / `guide` | general explanatory or how-to content | anyone |

## Default shape

```md
# <Title>

## About this document
- **Kind:** `skill` (reusable capability)
- **Read by:** auto-loaded by opencode for any agent; **written by:** maintainers
- **Related:** pairs with `<neighbour-skill>`; part of the `software-*` definition set.

<rest of the document…>
```

For a human-facing planning doc:

```md
# Roadmap — <Project>

## About this document
- **Kind:** `roadmap` (time plan / milestones)
- **Read by:** the human (Product Owner); **written by:** the PM agent
- **Related:** rolls up from `BOARD.md`; driven by tickets in the task store (`task_*` tools).
```

## Where it is mandatory

This section is required in **every** `*.md` file in the repository, including but not
limited to:

- Every skill under `.opencode/skills/*/SKILL.md` — note the front-matter `description`
  is **not enough on its own**: a reader who opens the raw file must see the purpose in
  the body. Keep the `About` consistent with `description`.
- Every agent under `.opencode/agent/*.md` — same: front matter is machine metadata, the
  `About` is for humans/agents reading the file directly.
- Any planning/status/decision doc (roadmap, board, status, decisions, brief, run-logs).
- Any spec/design/architecture markdown produced by a `software-*` task — record the
  owning ticket id in the `About` so the artifact links back to its ticket.
- `README.md`, `AGENTS.md`, `cpp/README.md`, `cpp/AGENTS.md`, and every other repo doc.
- Notes, meeting logs, design drafts — if it is Markdown, it gets an `About`.

The only exceptions are trivially-generated or ephemeral files (e.g. build outputs,
changelog entries) where the filename + format is self-evident; when in doubt, add it.

## Anti-patterns

- Don't write a novel — one to four lines, no filler.
- Don't restate the filename as if it explains itself ("this is foo.md").
- Don't skip the `kind` — that is the whole point.
- Don't put the `About` far down the page; it must be the first section after the title.

## Hand off

- When you create a Markdown artifact for a ticket, record it with `task_add_artifact`
  (`kind=doc`) and mirror the `About` `kind` there so the ticket and the file agree.
