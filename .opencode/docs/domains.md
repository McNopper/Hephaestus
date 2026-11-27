# Domains

## About this document

- **Kind:** `doc` / reference (part of `.opencode/docs/`).
- **Read by:** agents and maintainers; **written by:** maintainers.
- **Related:** complements `contracts.md`; the dispatch map is mirrored in `project-manager-orchestrate-execution`.
- **Currency / availability (T-250, 2026-11-26):** the Examples column shows the *naming convention*, not an inventory. What actually ships in this repo: 19 skills under `.opencode/skills/` (`software-*`, `test-software-*`, `project-manager-*`, `stage-pipeline`) and 18 agents under `.opencode/agent/`. The `cpp-`/`graphics-` examples (`cpp-tools`, `graphics-expert`, `graphics-render-comparison`, `mcp.graphics`) were **curated out of this repo on 2026-10-15** and are setup-supplied (root `AGENTS.md` - *C++ and graphics tooling lives outside the repo*); `code-dependency`, `code-repo-map` and `research-artificial-analysis-models` are not under `.opencode/skills/` either - resolve a target before dispatching to it.

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
| `project-manager-` | Project management | tickets, waves, routing, costing (estimates + live actuals), traceability, doc standards | `project-manager-operating-model`, `project-manager-orchestrate-execution`, `project-manager-route-request`, `project-manager-audit-traceability`, `project-manager-estimate-costs`, `project-manager-gather-intelligence`, `project-manager-create-ticket`, `project-manager-doc-about` |
| `cpp-` | C++ execution utility | methodology for the `cpp-tools` agent | `cpp-tools` |
| `graphics-` | Graphics utility (thin) | methodology; heavy work is `mcp.graphics` | `graphics-render-comparison` |
| `code-` | Code analysis | package/namespace dependency map; emits Mermaid block diagram; third-party license audit → compatibility table + remediation; probe-don't-read repository orientation map | `code-dependency`, `code-licenses`, `code-repo-map` |
| `research-` | Live research utility | methodology for fetching/normalizing external data sources | `research-artificial-analysis-models` |

## Coordination agents (unprefixed) and domain agents (prefixed)

Coordination agents are unprefixed: `orchestrator`, `manifest-author`, `executor`, `reviewer`,
`rubberduck`, `research`, `project-manager`. Domain agents keep their prefix: `cpp-tools`, `graphics-expert`.
All are model-neutral: each references a **tier**, never a hard-coded model ID. The
concrete tier-to-model mapping is setup-local and never committed here (D-004) - the
`opencode.json` default `model` field plus, where a setup chooses one, an
agent-frontmatter `model:` override - and no agent under `.opencode/agent/` carries a
`model:` key today.

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
