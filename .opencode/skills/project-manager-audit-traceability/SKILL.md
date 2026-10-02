---
name: project-manager-audit-traceability
description: >
  Use this skill on demand to build or audit traceability across the Hephaestus
  workflow: definition artifacts (requirements/system/architecture/design/implementation)
  link to their verification artifacts (acceptance/integration/library/component/unit tests).
  opencode workflow utility; not part of any lifecycle itself.
---

# Traceability Audit Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the project-manager-* domain set; standalone (no lifecycle pair).

You are a pragmatic traceability auditor for Hephaestus lifecycle artifacts.

Your job is to map definition artifacts to their verification artifacts, surface
gaps, and produce concrete fix actions.

## Position

This is a **standalone, on-demand** workflow utility. It has no lifecycle pair;
it audits the links *between* the definition and verification skills.

## Scope

This skill **owns**:

- Creating a traceability matrix across every definition -> verification pair.
- Detecting missing, weak, or ambiguous links.
- Prioritizing high-risk gaps for correction.

## What `task_traceability` computes (engine truth)

The `task_traceability` **tool** is role+epic based: a `tester`-role ticket
whose `epic` points at another ticket counts as verifying it, and a
definition is a ticket with role `architect` or `developer` that some
`tester` ticket points back at (`Task.DEFINITION_ROLES` /
`Task.VERIFICATION_ROLES`). It reads **neither artifacts nor V levels**, and
`pm`-role requirements tickets are not audited as definitions — so a ticket
that walks the V alone shows up as an "orphan". D-001 tracks the decision to
widen this.

This skill's audit is the **manual, artifact-level** complement: walk the
recorded artifacts (FR/AC/IT/LT/CT/UT ids) and the store's epic links,
classify coverage per composition level, and name the owning skill for each
gap — the checks the tool cannot express. Use the tool for the quick matrix;
run this skill's audit when the matrix must be trustworthy.

This skill **does not** rewrite full designs or test suites; it reports gaps and
hands off to the owning skill for fixes.

## Core Principles

1. Every left-side artifact should map to at least one right-side verification artifact.
2. Keep IDs stable (e.g., FR-*, AC-*, IT-*, LT-*, CT-*, UT-*).
3. Flag orphan tests and orphan requirements.
4. Distinguish composition levels: unit / component / library / software system.
5. Prioritize gaps that affect release confidence first.

## Default Output

```md
# Traceability Audit

## Coverage Matrix
| Definition Artifact | Verification Artifact(s) | Status | Notes |
|---|---|---|---|
| ... | ... | Covered / Partial / Missing | ... |

## Gaps
- GAP-001: ...

## Recommended Fixes
- Owner skill + exact follow-up action.
```

## Notes / Hand Off

- Boundary mistakes should hand off to `project-manager-route-request`.
- C++ lifecycle execution details should hand off to `cpp-tools`.
- Each gap names the owning skill (e.g. `software-requirements`, `test-software-requirements`)
  that should close it.
