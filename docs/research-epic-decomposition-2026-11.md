# Research: epic decomposition into milestones and sprints (Scrum, SAFe, Jira, GitLab, GitHub)

- **About this document:** a dated research report (research agent, 2026-11-14;
  authoritative sources — Scrum Guide, SAFe framework docs, Atlassian, GitLab,
  GitHub) answering the owner's question "an epic ticket is just split into
  several milestones and sprints — how is this normally done in Scrum or
  similar patterns?". It is the citation basis for §3 of
  `design-stage-pipeline.md` (U-110): the scope/derivation relation split, the
  no-milestone-construct decision, and the anti-pattern guardrails.
- **Read by:** agents working U-110's store chunks; the maintainer.
- **Related:** `design-stage-pipeline.md` (the design this feeds),
  `docs/opencode-v2-adoption.md` (the policy under which upstream features are
  adopted).

## TL;DR

The industry-standard model is **two orthogonal axes, not one chain**:
*scope containers* (epic → feature → story; child links to parent) and *time
containers* (sprint/iteration/PI batch items for delivery; milestones are
**zero-duration checkpoints or release-dated groupings, never scope
containers**). No framework decomposes "an epic into milestones and sprints" —
an epic is split into smaller **scope items**, which are then **assigned** to
sprints/PIs; GitLab makes the orthogonality explicit by letting one issue sit
in an epic *and* a milestone simultaneously. Atlassian's Plans guide even says
"in a plan, epics represent a milestone or deliverable" — i.e. "milestone" in
the owner's working statement is best read as *deliverable checkpoint*, not a
container type Hephaestus is missing.

## Summary

- **"Epic" is not Scrum canon.** The 2020 Scrum Guide contains no "epic" and
  no "milestone" — only the ordered Product Backlog, refinement ("breaking
  down and further defining Product Backlog items into smaller more precise
  items"), and Sprint Planning's decomposition topic. "Epic" is tooling
  convention layered on top.
- **Decomposition is scope-splitting, then time-batching.** SAFe: four tiers
  (Epic, Capability, Feature, Story), features sized to one PI, stories to one
  iteration. Jira: an epic is "a large user story broken down into smaller
  stories… delivered over a set of sprints". The Sprint Goal is the *single
  objective* the selected items serve.
- **Scope and time are separate containers that co-exist on one item.** GitLab:
  an issue sits in an epic *and* a milestone; Jira has three independent
  fields (`parent`, `Sprint`, fix `Version`); GitHub has sub-issue parent +
  milestone + iteration field on one issue.
- **Milestones are checkpoints, not hierarchy.** Atlassian: "zero-duration
  checkpoints… your project milestone could be the completion of a certain
  task, but it's not the task itself." SAFe: "a specific goal, event, or point
  in time used to evaluate progress." GitLab/GitHub milestones are flat,
  dated, progress groupings with no children semantics.
- **Link direction is uniformly child→parent.** Jira `parent` field on the
  child; GitLab child issue/epic; GitHub sub-issue `--parent` on the child.
- **Parent completion is a rollup of children, not a separate DoD.** GitHub
  progress rollup; GitLab epic date inheritance from children; Scrum mandates
  **one** DoD per product; no source defines a separate epic DoD.

## Concept table

| Construct | Scrum | SAFe | Jira | GitLab | GitHub | Container | Link direction |
|---|---|---|---|---|---|---|---|
| Epic | ✗ not canon | portfolio initiative, lean business case, MVP | parent of stories; level 1 | parent of issues (+ nested epics) | ✗ (top-level issue + sub-issues) | **SCOPE** | child → parent |
| Feature | ✗ | sized to one ART within a PI | ✗ (epic plays it) | ✗ | ✗ | SCOPE | child of epic |
| Story | PBI fitting one Sprint | few days, fits one iteration | smallest unit | issue | issue | SCOPE (leaf) | child → parent |
| Initiative | ✗ | above feature (Capability tier) | Premium: container for epics | nested epic | ✗ | SCOPE | child → parent |
| Sprint/Iteration | fixed ≤ 1 month; commitment = Sprint Goal | fixed-duration timebox (SAFe's iteration) | short period, `Sprint` field | iteration construct | iteration field | **TIME (cadence)** | item → sprint |
| PI | ✗ | 8–12 wk cadence timebox, PI Objectives | Plans/program board | ✗ | ✗ | TIME (program) | feature → PI via planning |
| Milestone | ✗ | point in time to evaluate progress; phase gates | ✗ no object — concept only | dated grouping, optional dates, co-exists with epic | dated grouping, % complete | TIME (checkpoint/batch) | item → milestone |
| Version/Release | ✗ | roadmap schedule | set released together, may span sprints | milestone-as-release | ✗ | TIME (delivery) | item → version |

## Canonical decomposition flow

1. **Frame the epic as an investment** (SAFe lean business case + MVP + Epic
   Hypothesis; Scrum: a large PBI serving the Product Goal).
2. **Split by SCOPE into child items** (refinement; epic → features sized to
   one ART-PI, features → stories sized to one iteration; Jira heuristics:
   anything estimated in weeks is an epic).
3. **Batch children into TIME containers** (Sprint Planning selects items
   forming one Sprint Goal; PI Planning assigns features; sprints and/or
   fix-versions; milestones/iterations).
4. **Mark checkpoints, don't create containers** — milestones attach to
   existing items or are events.
5. **Track parent completion by rollup** — children done ⇒ parent done; one
   DoD per product; Hephaestus's paired-verification-at-entry is the per-level
   DoD inheritance.

**Verdict on "an epic is split into several milestones and sprints":** not
canonical as stated. Canonical: *an epic is split into child scope items,
which are batched into time containers and optionally gated by checkpoints.*
Nearest literal implementation: GitLab's milestones-as-releases — and even
there the milestone groups the already-split issues.

## Recommendation (as adopted in design-stage-pipeline.md §3)

- **(a) Epic = scope-container-parent; derivation is its own relation
  (`basis`).** No framework overloads the epic field with derivation; Jira,
  GitLab and GitHub all keep hierarchy and derivation/link relations separate.
  Overloading would merge two orthogonal axes and break the natural reading
  "all tickets under this epic = one initiative's scope". Convention: children
  spawned by a division *inherit the parent's epic* (one epic = one
  initiative's whole derivation tree), while `basis` links preserve order and
  traceability. One field, one meaning; the chain stays the chain.
- **(b) No milestone construct.** Milestone semantics decompose into two
  things already present: *dated batch* (GitLab milestones have **optional**
  dates — a time-less wave covers this; an optional wave `target_date` may be
  added later if delivery dates matter) and *checkpoint* (the V-pipeline's
  stage-gate verdict). A third container would be a parallel mechanism for
  what existing tiers already do.
- **(c) Least-machinery translation of the owner's statement:** split by scope
  (children, epic = parent — supported), refine until each child fits one wave
  (the Scrum readiness rule), batch into waves (the `sprint` field),
  checkpoints = wave target dates or key-ticket completion gates, epic done by
  rollup. DoD is defined once per level-pair (paired verification at stage
  entry), never per-parent.

## Anti-patterns to encode as guardrails

1. **Epics as evergreen wandering containers** — Atlassian puts long-lived
   programs in *initiatives above* epics and expects "two or three epics per
   quarter" to complete; an epic with open children across many waves is a
   smell.
2. **Milestones as scope containers** — flat in GitLab and GitHub; hierarchy
   belongs to epic/sub-issues.
3. **Waves without goals** — the Sprint Goal is the commitment that makes a
   sprint more than a timebox; a wave states what completing it achieves.

## References (validated 2026-11-14)

- [1] The 2020 Scrum Guide — scrumguides.org/scrum-guide.html (primary standard)
- [2] SAFe Glossary — framework.scaledagile.com/glossary (vendor standard; full
  Epic/Feature/Milestone articles login-gated, public definitions used)
- [3] SAFe Epic / Story — framework.scaledagile.com/epic/, /story/
- [4] Atlassian, "What is an epic?" — support.atlassian.com/jira-software-cloud/docs/what-is-an-epic/
- [5] GitLab Docs, "Epics" — docs.gitlab.com/user/group/epics/
- [6] GitLab Docs, "Milestones" — docs.gitlab.com/user/project/milestones/
- [7] Atlassian, "What is a sprint?" / "What is a version?" — support.atlassian.com
- [8] Atlassian, "Advanced planning in Jira: Overview of Jira Plans" — atlassian.com/software/jira/guides/advanced-roadmaps/overview
- [9] Atlassian, "What are project milestones" — atlassian.com/work-management/project-management/project-milestones
- [10] Atlassian Agile Coach, "Epics" / "Epics, Stories, and Initiatives" — atlassian.com/agile (verbatim excerpts; walking-skeleton/story-mapping names are orientation, not cited claims)
- [11] Atlassian Agile Coach, "User Stories" — atlassian.com/agile/project-management/user-stories
- [12] Atlassian Support, "What are work types?" — support.atlassian.com/jira-cloud-administration/docs/what-are-issue-types/
- [13] GitHub Docs, sub-issues (adding/parent/progress/API) — docs.github.com
- [14] GitHub Docs, "About milestones" — docs.github.com
- [15] GitHub Docs, "About projects (v2)" — docs.github.com

**Open follow-ups:** SAFe's full Feature/Milestone articles are login-gated;
Cockburn/Patton primary sources for walking skeleton / story mapping not
fetched; whether Hephaestus waves gain an optional `target_date` is an owner
product decision, not settled by the sources.
