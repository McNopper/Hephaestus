# own-project.md - bring your own project

## About this document

- **Kind:** `doc` / user guide - how to run the Hephaestus harness on YOUR
  project instead of on Hephaestus itself.
- **Read by:** anyone adopting the template repo for their own work.
- **Related:** `../README.md` ("Reuse as a template" for the file mechanics),
  `new-machine-setup.md` (tooling prerequisites), `fleet-quickstart.md` (the
  first fleet run), `../AGENTS.md` (workflow conventions),
  `.opencode/skills/project-manager-create-ticket` (ticket writing).

## The core idea

To the harness, **a project is a subdirectory of the task store**:
`.opencode/tasks/<project>/` holds one Markdown file per ticket plus the
`_meta.json` sidecar. Hephaestus keeps its own work in
`.opencode/tasks/hephaestus/` - that name is the only thing "wired" to this
repo. Everything else (skills, agents, board, fleet, the V-pipeline) is
project-agnostic: every `task_*` tool takes a `project` parameter, and
several projects live side by side in one store.

## Step-by-step

### 0. Get the files and the tooling

Copy the pieces per **"Reuse as a template"** in the README (skills, agents,
`opencode.json`, `AGENTS.md`, the `tasks` launcher, optionally
`mcp/graphics` and `cpp/`). Install the toolchain per `new-machine-setup.md`
(JDK 21, Node, PowerShell 7, git; Eclipse CDT only if you want the IDE
harness). Build the tool jars once so the `tasks`/`fleet` stdio launchers
resolve (README "Install & Use", step 4).

### 1. Name your project and create its store

```bash
mkdir -p .opencode/tasks/<your-project>
```

No registration anywhere: the store is just files. The Board, the readiness
machinery and the fleet all discover the project from the folder name. Keep
tickets out of `.opencode/tasks/` root - the project subdirectory is what
gives them a namespace.

### 2. Point the config at your world

In `opencode.json`:

- set the default `model` (and any per-agent overrides) to the **tiers** your
  providers can fill - agents reference tiers, never model IDs;
- keep only the MCP servers you copied (drop `graphics`/`fleet` if unused);
- the `tasks` entry points at `eclipse/tasks-tools.ps1` - keep it if you
  copied the launcher, or point it at your own build of the bundles.

### 3. Rewrite AGENTS.md (or trim it)

`AGENTS.md` is the convention contract your agents read first. The template
one describes THIS repo (its skills table, its layout). Replace the
project-specific parts with yours: what the repo is, where the code lives,
what "done" means. Keep the two invariants if you want the machinery:
the store is the single blackboard, and skills are flat by domain in
`.opencode/skills/`.

### 4. Define your verification gate

The review loop treats "the repository's verification gate (see its
AGENTS.md/README)" as the CI truth. Define yours (e.g. `cmake --build ...
&& ctest`, `npm test`, `.\build.ps1 verify`) and write it down in your
AGENTS.md - agents will run it, and the reviewer treats a red gate as FAIL.

### 5. Write the first tickets

Use the `project-manager-create-ticket` skill (or the Board's New Ticket):

- `id` minted by the store (`T-NNN` per prefix), `title`, `description`,
  `acceptance_criteria` (testable!), `role` (pm / architect / developer /
  tester), `type` (story / task / bug / spike), `priority`, `story_points`;
- optional `stage` for the V-pipeline (`requirements` -> `system` ->
  `architecture` -> `design` -> `implementation` -> `test-implementation` ->
  `test-design` -> `test-architecture` -> `test-system` ->
  `test-requirements`); stageless tickets work fine too - the pipeline is
  opt-in structure.

### 6. Work them - three ways

1. **Direct:** `task_claim(role=...)` in a chat session, do the work,
   `task_add_artifact` + move to `in-review`.
2. **Waves:** plan a sprint (wave) from the Board or `task_plan_sprint`, then
   let workers claim; the auto-dispatch loop (`fleet_fleet_auto_start`) or
   the recurring-waves mode (`fleet_fleet_waves_start`) drive it unattended.
3. **Fleet:** `fleet_fleet_dispatch` per ticket (git-worktree isolation,
   role-mapped agent, merge-back). The fleet is **disabled by default** (it
   eats tokens) - arm it with the Enable button in the Fleet/Background view
   or the `fleet_fleet_*` tools. Use the ticket's `model` field as the cost
   lever: small well-specified tickets deserve cheap models.

### 7. If you use the Eclipse harness

Open **your repo** as the workspace: the tasks root is adopted from the
workspace project (anything under a git repo carrying `.opencode/tasks`
qualifies; there is also a preference override). The PM Board, Fleet and
Background views then show your project, not Hephaestus'.

## Replacing the Hephaestus-specific bits (checklist)

| Template artifact | Replace with |
|---|---|
| `.opencode/tasks/hephaestus/` | your project directory (delete ours) |
| `ROADMAP.md` | your own roadmap (or delete) |
| `opencode.json` model ids | your providers' models per tier |
| `AGENTS.md` repo description | your repo's conventions + verification gate |
| `docs/` (this guide's siblings) | your project docs (delete ours) |
| `deploy-dev.ps1` `ECLIPSE_HOME` | your Eclipse install (only for the IDE harness) |

## Quick troubleshooting

- **No `task_*` tools:** the store server is not up - build the tool jars and
  check `opencode.json`'s `tasks` entry (or the `eclipse-build` endpoint when
  running in Eclipse).
- **Store looks empty:** projects are subdirectories; tickets at the store
  root are out of scope. Check the project name spelling in the tools' call.
- **Fleet does nothing:** it is disabled by design until you Enable it; and
  it needs git (worktrees) plus a claimable ticket in `sprint-backlog`.
- **Readiness shows WAIT_UPSTREAM forever:** upstream stages need `done`
  tickets with matching epics; unstaged tickets always read NOT_APPLICABLE
  and are never re-dispatched.
