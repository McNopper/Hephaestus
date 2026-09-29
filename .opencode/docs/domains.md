# Domains

## About this document
- **Kind:** `doc` / reference (part of `.opencode/docs/`).
- **Read by:** agents and maintainers; **written by:** maintainers.
- **Related:** complements `contracts.md`; the dispatch map is mirrored in `project-manager-orchestrate-execution`.

Hephaestus organizes everything by **domain**, encoded in skill/agent **names**, not in
folder trees. opencode discovers every `SKILL.md` under `.opencode/skills/*/` and every
agent under `.opencode/agent/*.md` flat. The domain is the `<domain>-` prefix of the name.

## Naming convention

`<domain>-<descriptor>`, lowercase, dash-separated. A skill's front-matter `name:` MUST
match its folder name (opencode requirement). Name regex: `^[a-z0-9]+(-[a-z0-9]+)*$`.

## Domain prefixes

| Prefix | Domain | Carries | Examples |
|---|---|---|---|
| `software-` | Definition (what / how) | the left side of the work | `software-requirements`, `software-system`, `software-architecture`, `software-design`, `software-implementation` |
| `test-software-` | Verification | the right side, per level | `test-software-implementation`, `-design`, `-architecture`, `-system`, `-requirements` |
| `project-manager-` | Project management | tickets, waves (schema key `sprint`), routing, costing (estimates + live actuals), traceability, doc standards | `project-manager-operating-model`, `project-manager-orchestrate-execution`, `project-manager-route-request`, `project-manager-audit-traceability`, `project-manager-estimate-costs`, `project-manager-gather-intelligence`, `project-manager-create-ticket`, `project-manager-doc-about` |
| `cpp-` | C++ execution utility | methodology for the `cpp-tools` agent | `cpp-tools` |
| `graphics-` | Graphics utility (thin) | methodology; heavy work is `mcp.graphics` | `graphics-render-comparison` |
| `code-` | Code analysis | package/namespace dependency map; emits Mermaid block diagram; third-party license audit → compatibility table + remediation; probe-don't-read repository orientation map | `code-dependency`, `code-licenses`, `code-repo-map` |
| `research-` | Live research utility | methodology for fetching/normalizing external data sources | `research-artificial-analysis-models` |

## Coordination agents (unprefixed) and domain agents (prefixed)

Coordination agents are unprefixed: `orchestrator`, `manifest-author`, `executor`, `reviewer`,
`rubberduck`, `research`, `project-manager`. Domain agents keep their prefix: `cpp-tools`, `graphics-expert`.
All are model-neutral (reference a tier) **except** `graphics-expert`, which is pinned to
the `very-high` model.

## Why names, not folders

- Skills auto-load by directory; adding a subfolder doesn't change discovery but does
  make cross-references brittle. Flat + domain-in-name keeps everything one glob away.
- The domain prefix doubles as a dispatch hint: a ticket `role` maps to a prefix
  (`developer` → `software-*`, `tester` → `test-software-*`, `cpp-engineer` → `cpp-tools`,
  `graphics-engineer` → `mcp.graphics` + `graphics-expert`, `architect` → `software-system`
  / `software-architecture`, `pm` → `project-manager-*`). A staged ticket resolves through the chain
  `stage` → `role` → skill (the `VStages` mapping in the tasks bundle; the fleet dispatches
  by stage role):

| stage | role | skill |
|---|---|---|
| `requirements` | `pm` | `software-requirements` |
| `system`, `architecture` | `architect` | `software-system`, `software-architecture` |
| `design`, `implementation` | `developer` | `software-design`, `software-implementation` |
| `test-implementation` … `test-requirements` | `tester` | matching `test-software-*` |
