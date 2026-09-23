# 🔱 Hephaestus

[![verify](https://github.com/McNopper/Hephaestus/actions/workflows/verify.yml/badge.svg)](https://github.com/McNopper/Hephaestus/actions/workflows/verify.yml)

## About this document
- **Kind:** `doc` / repo README (top-level entry point)
- **Read by:** humans evaluating/adopting the template; **written by:** maintainers
- **Related:** pairs with `AGENTS.md` (workflow conventions) and the skill/agent set under `.opencode/`

> *[Hephaestus](https://en.wikipedia.org/wiki/Hephaestus) — Greek god of the forge, and the one who built automatons: Talos, the golden mechanical attendants.*

## Overview

Hephaestus is an **opencode-native** template for **agentic project management and
software development**. It is organized by **domain** (not by a lifecycle or folder
tree): skills and agents are flat under `.opencode/` and named `<domain>-<descriptor>`.
Project management is a concrete, Scrum-like **ticket/sprint** workflow over the
**task store** (`.opencode/tasks/`, one Markdown file per ticket) served as `task_*`
MCP tools by the Eclipse harness's `eclipse-build` endpoint and the stdio
`tasks-tools` launcher (opencode prefixes them with the server name — `tasks_task_*`
in TUI sessions, `eclipse-build_task_*` in Eclipse; the tool/wire names stay `task_*`);
the **fleet** is dispatchable from chat itself via the `fleet` stdio server
(wire names `fleet_dispatch`/`fleet_jobs`/…, surfaced as `fleet_fleet_*` — chat is the
primary interface, Board buttons are conveniences); C++ and graphics are first-class **tools** (an agent and an
MCP server), not a separate lifecycle.

Three ideas hold it together:

- **Domains in names, not folders.** opencode discovers every `SKILL.md` under
  `.opencode/skills/*/`. Naming convention `<domain>-<descriptor>`
  (`software-`, `test-software-`, `project-manager-`, `cpp-`, `graphics-`, `code-`,
  `research-`); coordination agents are
  unprefixed.
- **A concrete PM, not a metaphor.** The `project-manager` agent runs Scrum over tickets in the
  task store. Tickets carry a `role` (discipline), and workers **self-claim** by role
  (`task_claim`). Multiple independent **projects** coexist as subdirectories of the
  store. The store is version-controlled Markdown — the seam the Maven mojos
  (`opencode-tasks:sync`/`plan`) and the Eclipse Board view build on.
- **Model-neutral by default.** Agents reference a *tier*; the concrete model
  resolves from `opencode.json` (default) and any per-agent overrides. Only `graphics-expert`
  is pinned (to `very-high`).

> **The PM/ticket system is optional.** Any skill or agent can be used **directly** by a
> human (or another agent) with no ticket or sprint — just invoke the skill or pick an
> agent with `/agents`. The PM system is there when you want tracked, multi-agent, sprint
> execution; skip it for ad-hoc work. Skills like `project-manager-doc-about` also work standalone,
> independent of PM.

## What works with and without Eclipse

Everything here is **opencode-native first**: the *entire* agentic stack — board,
fleet, skills, agents — runs from a plain `opencode` TUI in this repository.
Eclipse is the optional human surface: overview, inspection, takeover. One
repository, two ways to use it:

| Capability | Plain opencode TUI (no Eclipse) | Eclipse harness (on top) |
|---|---|---|
| Skills, agents, model tiers (`/agents`, `/models`, Plan mode) | ✅ | ✅ — same engine, surfaced in views |
| **Task board** — `task_*` tools incl. `task_doctor` lint, V-pipeline, sprints | ✅ `tasks` stdio server (`eclipse/tasks-tools.ps1`) | ✅ **Board view** (kanban + pipeline, type badges, peer-write refresh) *and* the same tools via `eclipse-build` |
| **Fleet** — dispatch, jobs, live progress, permissions, store sync, auto-dispatch (`fleet_*`) | ✅ `fleet` stdio server (`eclipse/fleet-tools.ps1`) | ✅ **Fleet view** (own *and peer-engine* jobs, diffs, permissions) |
| **Fleet daemon** — engine outliving the client (`eclipse/fleet-daemon.ps1`, `FLEET_DAEMON=auto\|always`) | ✅ detach/reconnect without killing runs | ✅ (views keep their own engine until the default flips) |
| Maven mojos `opencode-tasks:sync` / `:plan` over the store | ✅ | ✅ |
| Graphics MCP (screenshot, RenderDoc, render comparison) | ✅ | ✅ |
| `cpp-tools` agent driving CMake/clang tooling | ✅ (bash-driven) | ✅ |
| Structured C++ tool pack as MCP tools (`cmake_*`, `ctest_run`, `debug_batch`, …) | ❌ lives in Eclipse's `eclipse-build` endpoint | ✅ (per-start token auth) |
| Chat UI (markdown/KaTeX/mermaid, Stop, pending queue, late-reply recovery) | — the TUI *is* your chat | ✅ chat view |
| Server/Providers/Repo/Session views, live busy-session icons, CDT markers | ❌ | ✅ |
| Building this harness itself | `eclipse/build.ps1` (JDK 21, Maven/Tycho reactor) | same |

The split is deliberate architecture, not happenstance: the `client`, `tools`,
`tasks`, `git` and `fleet` bundles are **Eclipse-free (build-enforced)** — the IDE
consumes them, never owns them (see `eclipse/ARCHITECTURE.md`).

## Layout

| Path | What it is |
|---|---|
| `opencode.json` (repo root) | project config — default `model`, `AGENTS.md`, and the `tasks` + `fleet` (stdio launchers) + `graphics` MCP servers. |
| `AGENTS.md` (repo root) | opencode-first workflow conventions and routing. |
| `.opencode/skills/*/SKILL.md` | the skill library, flat by domain. |
| `.opencode/agent/*.md` | lean custom agents (coordination + domain). |
| `.opencode/docs/` | `domains.md`, `contracts.md`. |
| `.opencode/tasks/` | the **task store** — one Markdown file per ticket per project (`<project>/T-NNN.md` + `_meta.json` sidecar), version-controlled. |
| `mcp/graphics/` | the graphics MCP server (captures, comparisons). |
| `cpp/` | standalone AI-first C++23 build skeleton (its own `AGENTS.md`). |
| `eclipse/` | the Eclipse plugin — the agentic IDE harness (chat, Server view incl. **MCP servers + Skills**, Providers view with logos, the **PM Board + Fleet views**, the token-authed `eclipse-build` MCP endpoint serving the C++ **and** `task_*` tool packs, git-worktree fleet incl. the task-driven `TaskFleet` and the **detached daemon** (`fleet-daemon.ps1`), the `opencode-tasks` Maven plugin (`:sync`/`:plan` over the task store), the `tasks-tools.ps1`/`fleet-tools.ps1` stdio launchers; Maven/Tycho reactor). |

## Skills (flat, by domain)

| Domain | Skills |
|---|---|
| `software-` (definition) | `software-requirements`, `software-system`, `software-architecture`, `software-design`, `software-implementation` |
| `test-software-` (verification) | `test-software-implementation`, `-design`, `-architecture`, `-system`, `-requirements` |
| `project-manager-` (project management) | `project-manager-operating-model`, `project-manager-orchestrate-execution`, `project-manager-route-request`, `project-manager-audit-traceability`, `project-manager-estimate-costs`, `project-manager-gather-intelligence`, `project-manager-create-ticket`, `project-manager-doc-about` |
| `cpp-` (C++ utility) | `cpp-tools` (methodology; the `cpp-tools` agent runs the commands) |
| `graphics-` (graphics utility) | `graphics-render-comparison` (the heavy lifting is the `mcp.graphics` tools) |
| `code-` (code analysis) | `code-dependency` (package/namespace dependency map → Mermaid block diagram), `code-licenses` (third-party license audit → compatibility table + remediation), `code-repo-map` (probe-don't-read orientation map: layout, build/test entry points, module one-liners) |
| `research-` (live research utility) | `research-artificial-analysis-models` (Artificial Analysis model leaderboard → filtered, cost-sorted Markdown table) |

Verification maps by composition level: `test-software-implementation` ↔ `software-implementation`
(unit), `test-software-design` ↔ `software-design` (component), `test-software-architecture`
↔ `software-architecture` (library), `test-software-system` ↔ `software-system` (integration),
`test-software-requirements` ↔ `software-requirements` (acceptance).

### Terminology (canonical in this repo)

The dividing line between **component** and **library** is **reuse scope**, not size and
not static-vs-shared linkage (that is a build decision):

| Term | Meaning | Reuse scope | Composes into |
|---|---|---|---|
| **Unit** | Smallest element with a clear interface; implementation fills its content. | within one component | Component |
| **Component** | Units behind a clear interface; **internal** to this software. | within this software | Library |
| **Library** | Components behind a clear interface; **reusable outside this software**. | reusable across systems | Software System |
| **Software System** | Integrated product of libraries + external interfaces. | the deliverable | — |
| **Package/Folder** | Organization only; a language *module* is also just organization. | — | — |

## The ticket / sprint workflow

The **task store** (`.opencode/tasks/<project>/`, one Markdown file per ticket) holds
**tickets** and **sprints**, scoped per **project** so several independent projects run at
once. Agents read/write it through the `task_*` MCP tools; humans can read the files
directly (and hand edits are tolerated between tool writes). Ticket states:

```
product-backlog --plan--> sprint-backlog --claim--> in-progress --verify--> in-review --accept--> done
   (incomplete on sprint close ───────────────────────────────────────────────────────────────┘)
blocked = orthogonal flag (blocked:bool + blocker:str) at any active state
```

Key rules:

- **Self-claim by role.** A worker loops `task_claim(role=…)`; the call is atomic (file
  lock + temp-rename writes) so two agents never get the same ticket. A returned ticket
  (`task_release`) can be picked up by a *different* agent. A claim with nothing to do
  returns `null` — worker loops stop on it.
- **Record artifacts.** When a worker produces a file, git commit/branch, or doc, it
  records it with `task_add_artifact(kind=file|git|path|url|doc, ref=…)` *before* moving to
  `in-review` — the ticket is the hand-off contract.
- **Rework loop.** Review/verification failure returns the ticket to `in-progress`.
- **Bubble-up → escalation.** A blocked worker sets `blocked` + a `blocker`; the PM resolves
  internally or escalates only human-worthy decisions.

**V pipeline (optional, per ticket).** A ticket may carry a `stage` — the 10 canonical stages
`requirements` … `test-requirements` (definition down the left leg, verification up the right).
Stages run **concurrently** (no phase gates): each finished stage feeds the next stage's backlog
via `task_advance` (which re-derives role/skill from the new stage); a stage that cannot proceed
calls `task_send_back` with a reason. The Board view has a Pipeline mode for stage-ordered columns.

See `project-manager-operating-model` (Scrum events, DoD, escalation), `project-manager-create-ticket` (how to fill
a ticket), `project-manager-route-request` (ambiguous next step), `project-manager-audit-traceability` (matrix).

## Agents (lean, flat, model-neutral except one)

| Agent | Role | Model |
|---|---|---|
| `orchestrator` | kicks off the sprint; workers self-claim | tier (`high`) |
| `manifest-author` | high-tier plan + execution manifest | tier (`high`) |
| `executor` | open-tier task execution; records artifacts | tier (`low`) |
| `reviewer` | high-tier final review (edit-denied) | tier (`high`) |
| `rubberduck` | cross-vendor critic (edit-denied) | tier (different vendor) |
| `research` | authoritative-source investigation; validated synthesis | tier (`high`) |
| `project-manager` | Scrum Master + PO proxy; always present | tier (`high`) |
| `cpp-tools` | C++ build/format/static-analysis via bash | tier (`low`) |
| `graphics-expert` | frontier graphics work; drives `mcp.graphics` | **pinned `very-high`** |

## C++ and graphics

- **C++**: the `cpp-tools` *agent* runs CMake configure/build, clang-format, cppcheck,
  clang-tidy and reads their reports (methodology in the `cpp-tools` skill). The old
  `cpp/mcp` server is gone — C++ is an agent now.
- **Graphics**: `mcp.graphics` exposes `graphics_screenshot`, `graphics_renderdoc_capture`,
  `graphics_renderdoc_frame`, `graphics_compare_renders`. `graphics-expert` (very-high tier) drives
  them; `graphics-render-comparison` is the thin methodology skill.

## Model tiers

Agents/docs reference **tiers**, never hard-coded model IDs. The concrete model
behind each tier is configured in `opencode.json` (the default `model` field)
and in any per-agent override (only `graphics-expert` overrides, pinning to
`very-high`); resolve through `/models`. In the Eclipse chat, selector changes
are deliberate by design: un-armed drift (mouse-wheel/pointer traffic over the
selector row) reverts — only an opened-dropdown pick or Enter commits.

| Tier | Selection rule |
|---|---|
| `very-low` | cheapest/fastest for trivial, mechanical edits |
| `low` | best open-weight model — **default executor** |
| `mid` | balanced general model for standard impl/tests |
| `high` | top-capability reasoning + large context — planning + review |
| `very-high` | frontier/highest-risk — run twice & reconcile |

## Recommended opencode workflow

1. **Frame the project:** the human writes the brief/goal; the `project-manager` agent creates tickets
   (`task_create`) in `product-backlog`.
2. **Sprint planning:** `task_plan_sprint` commits tickets to a sprint (`sprint-backlog`).
3. **Execute:** workers `task_claim(role=…)`, use the matching `software-*` /
   `test-software-*` skill, record artifacts, and move tickets to `in-review`.
4. **Review & accept:** `reviewer` / test skills verify; the `project-manager` agent accepts → `done`.
5. **Iterate:** defects rework; `task_close_sprint` returns unfinished tickets to the backlog.

Use **Plan mode** (`Tab`) for multi-file changes; `/agents` to pick an agent; `/models` to
resolve a tier; the `orchestrator` dispatches parallel subagents. Skills auto-load from
`.opencode/skills/`; reference files with `@`.

## Install & Use (opencode)

1. [Install opencode](https://opencode.ai/v2/docs/) (e.g. `npm install -g opencode-ai`).
2. Connect providers via `/connect` (e.g. Z.AI, GitHub Copilot, OpenAI —
   whichever you use).
3. Install the graphics MCP deps: `pip install -r mcp/graphics/requirements.txt`.
4. Build the tool jars once (JDK 21) — this covers both stdio servers:
   `cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.tasks
   -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.fleet
   -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.git clean package`
   (the launchers also resolve gson from the local Tycho cache).
5. Run `opencode` from this repo. Skills, agents, and `AGENTS.md` auto-load; the `tasks`
   and `fleet` stdio launchers and the `graphics` MCP server start from `opencode.json`.
6. Your first headless fleet dispatch: see **`docs/fleet-quickstart.md`** (seed
   ticket → `fleet_dispatch` → poll → merge → actuals — the whole engine works
   without Eclipse). Optionally start the engine **detached** with
   `eclipse/fleet-daemon.ps1` and set `FLEET_DAEMON=auto` so runs survive
   disconnecting (V-006 daemon).

> **MCP scope:** the bundled servers implement a deliberately minimal JSON-RPC surface
> (`initialize`, `tools/list`, `tools/call`, plus `ping` on the Java `tasks`/`eclipse-build`
> servers). The `tasks` launcher (Java, stdio) and the
> Eclipse-hosted `eclipse-build` endpoint (Streamable HTTP) expose the same `task_*` tool
> set — one surface, two transports; `graphics` is stdio. None implement `resources`,
> `prompts`, cancellation, or progress. That is sufficient for opencode tool calls.

> **Local plugin deps:** `.opencode/` carries a local `.opencode/package.json` that is
> **git-ignored** along with its `node_modules` — it is a per-clone convenience, not part
> of the template. A fresh clone starts without it. The old V1 `@opencode-ai/plugin`
> dependency has been **removed** (this repo ships no plugin sources; opencode v2's plugin
> package is `@opencode/plugin` and V1 plugins do not run in V2).

### Reuse as a template

Hephaestus is a **template repo**. Copy the pieces you need (below), then
follow **[`docs/own-project.md`](docs/own-project.md)** - the step-by-step
guide for wiring the harness to YOUR project (store layout, config,
tickets, waves, verification gate, and what to replace in the template):

```bash
# from your project root
mkdir -p .opencode/skills .opencode/agent mcp
cp -R /path/to/Hephaestus/.opencode/skills/* .opencode/skills/
cp -R /path/to/Hephaestus/.opencode/agent/*  .opencode/agent/
cp -R /path/to/Hephaestus/mcp/graphics       mcp/
cp    /path/to/Hephaestus/opencode.json .
cp    /path/to/Hephaestus/AGENTS.md .
# task board: copy the launcher + build the bundles (or point opencode.json's
# "tasks" entry at your own build of eclipse/tasks-tools.ps1)
mkdir -p eclipse
cp    /path/to/Hephaestus/eclipse/tasks-tools.ps1 eclipse/
```

Trim to what you need (e.g. drop `graphics-*` / `mcp.graphics` if unused). Set the
default `model` in `opencode.json` (and any per-agent overrides) to match your
providers.

### What *not* to do

- ❌ Don't hard-code a model in an agent beyond the default — reference a **tier**; only
  `graphics-expert` pins a model.
- ❌ Don't rename `SKILL.md` or rely on the folder name — identity is the front-matter
  `name:` (must match its folder).
- ❌ Don't put two `AGENTS.md` in the same folder — opencode loads one per git-root/cwd.
- ⚠️ Skills in `.opencode/skills/` load for everyone who runs `opencode` here — commit only
  what the project needs.

## C++ build template

[`cpp/`](cpp/) is a standalone **AI-first C++23 build template** (its own `CMakeLists.txt`,
`CMakePresets.json`, `AGENTS.md`, `.clang-tidy`, `.clang-format`, `src/`, `include/`,
`tests/`). It emits machine-readable reports (compile DB, Doxygen XML, clang-tidy /
cppcheck exports) and runs `verify` (fast) and `verify-full` (strict). The `cpp-tools`
agent drives it. See [`cpp/README.md`](cpp/README.md) and [`cpp/AGENTS.md`](cpp/AGENTS.md).

Presets ship for the Ninja-first toolchains — `default`/`release`/`analysis`
(Ninja + Clang on PATH, full AI analysis stack), `clang64`/`mingw64` (MSYS2
environments), `linux` (Ninja + gcc, incl. WSL) — and **`windows`** (Visual Studio +
MSVC, which builds and tests but skips the Clang-based analysis by design). On a
Windows host without Clang/Ninja, use `cmake --preset windows`.

## License

[MIT](LICENSE) © 2026 Norbert Nopper.

### Third-party licenses

The bundled graphics MCP server depends on **Pillow** (HPND) and **numpy**
(BSD-3-Clause) — all permissive and compatible with MIT. Their
full license texts and copyright notices are in
[`THIRD-PARTY.md`](THIRD-PARTY.md). The local opencode Node plugin
(`.opencode/`, git-ignored) and the `cpp/` template's test-only GoogleTest
(BSD-3-Clause, fetched on demand) are not redistributed and are documented there
as well. The Eclipse chat view additionally vendors **markdown-it**, **mermaid**,
**KaTeX**, and **highlight.js** into its plugin jar; their notices are also in
[`THIRD-PARTY.md`](THIRD-PARTY.md).
