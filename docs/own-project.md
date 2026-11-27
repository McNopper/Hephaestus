# own-project.md - bring your own project

## About this document

- **Kind:** `doc` / user guide - how to run the Hephaestus harness on YOUR
  project instead of on Hephaestus itself.
- **Read by:** anyone adopting the template repo for their own work.
- **Related:** `../README.md` ("Reuse as a template" for the file mechanics),
  `new-machine-setup.md` (tooling prerequisites and bring-up),
  `.opencode/skills/stage-pipeline` (the first run: arm `ticker-setup.ps1`
  and walk the V - step 6 below; `fleet-quickstart.md` was retired
  2026-11-14 with the fleet in U-110), `../AGENTS.md` (workflow conventions),
  `TESTING.md` (once it runs: exercise it — the tester's guide doubles as the
  smoke test for a fresh adoption),
  `.opencode/skills/project-manager-create-ticket` (ticket writing).

## The core idea

To the harness, **a project is a subdirectory of the task store**:
`.harness/tasks/<project>/` holds one Markdown file per ticket plus the
`_meta.json` sidecar. Hephaestus keeps its own work in
`.harness/tasks/hephaestus/` - that name is the only thing "wired" to this
repo. Everything else (skills, agents, board, the V-pipeline) is
project-agnostic: every `task_*` tool takes a `project` parameter, and
several projects live side by side in one store.

## Step-by-step

### 0. Get the files and the tooling

Copy the pieces per **"Reuse as a template"** in the README (skills, agents,
`opencode.json`, `AGENTS.md`, the `tasks` launcher, optionally `cpp/`
and — since the 2026-10-15 curation — a graphics MCP server of your own
(`mcp.graphics` is no longer shipped in this repo)). Install the toolchain per
`new-machine-setup.md` - its *Prerequisites* table is the canonical list
(Eclipse CDT only if you want the IDE harness). Build the tool jars once so
the `tasks` stdio launcher resolves (README "Install & Use", the *Build the
tool jars* step - run it in the Hephaestus checkout, which owns the jars).

Once it runs, do one pass of the tester's guide,
[`TESTING.md`](TESTING.md) — §1 is setup, §3 is the headless path; it doubles
as the smoke test for a fresh adoption.

### 1. Name your project and create its store

```bash
mkdir -p .harness/tasks/<your-project>
```

No registration anywhere: the store is just files. The Board, the readiness
machinery and the ticker all discover the project from the folder name. Keep
tickets out of `.harness/tasks/` root - the project subdirectory is what
gives them a namespace. That is the subtree layout; the store root can also
be a **dedicated store repo** whose root *is* the store - see
`.opencode/docs/contracts.md`, **Two store layouts**.

### 2. Point the config at your world

In `opencode.json`:

- set the default `model` (and any per-agent overrides) to the **tiers** your
  providers can fill - agents reference tiers, never model IDs;
- keep only the MCP servers you copied — the template's `opencode.json`
  declares exactly one (`tasks`); add your own (a graphics server, say) only
  if you actually use it;
- the `tasks` entry points at `eclipse/tasks-tools.ps1` — a **relative** path
  that resolves against YOUR repo, which normally has no `eclipse/`: the tasks
  server then never starts and **every `task_*` tool dies**. Run the README
  recipe's one-line `sed` rewrite (or set it by hand) so the command carries
  the **absolute** path of the Hephaestus checkout's launcher, e.g.
  `"C:/path/to/Hephaestus/eclipse/tasks-tools.ps1"` — and keep that checkout
  around: the launcher runs **built jars**, resolved from `$ECLIPSE_HOME\dropins`
  (default `C:\eclipse-cpp`) or from `eclipse/bundles/*/target` next to itself
  (`eclipse/tasks-tools.ps1:96-121`). Build them once in the Hephaestus
  checkout (README "Install & Use", the *Build the tool jars* step) — that is
  the prerequisite in step 0 above. Only copy the launcher into your repo if
  you also ship the jars it should run.

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
  `test-requirements`); a ticket with NO stage is outside the pipeline -
  readiness reports it `NOT_APPLICABLE` and only the verdict lane ever
  touches it (nothing is claimed or divided), so the V is opt-in structure:
  give a ticket a `stage` to put it in the V.

### 6. Work them - two ways

1. **Direct:** `task_claim(role=...)` in a chat session, do the work,
   `task_add_artifact` + move to `in-review`.
2. **The ticker (unattended):** arm the ONE schedule with
   `.opencode/skills/stage-pipeline/ticker-setup.ps1` (one steward session, one
   `ticker-v` schedule walking every stage in canonical order); each tick resolves
   blocked items first, divides done tickets into linked downstream tickets
   and kicks off READY tickets via goal-driven subagents under the spawn
   cap. The procedure lives in the `stage-pipeline` skill; live evidence in
   ticket U-111. Use the ticket's `model` field as the cost lever: small
   well-specified tickets deserve cheap models.

### 7. If you use the Eclipse harness

Open **your repo** as the workspace: the tasks root is adopted from the
workspace project (anything under a git repo carrying `.harness/tasks`
qualifies; there is also a preference override). The PM Board and the
Server view then show your project, not Hephaestus'.

### 8. Cut a release (define your own)

There is no turnkey release step for adopters in this template - be precise
about where the line is:

- **What this repo provides:** `eclipse/releng/release.ps1`, the release
  sequence for THIS repository's Eclipse reactor. It hard-requires this layout:
  it resolves the git root and then demands `<repo>/eclipse/build.ps1`
  (`eclipse/releng/release.ps1:32-36`), runs the Tycho quality gate
  (`clean verify -Pquality`), spans the version across the reactor (poms, bundle
  MANIFESTs, feature.xml via `tycho-versions:set-version`), tags `vX.Y.Z` and
  stops there — the default run ends after the tag + release commit; a bump to
  the next `-SNAPSHOT` is a separate on-demand act (`-BumpNext`, owner directive
  2026-11-27: the bump happens on demand). In a repo without `eclipse/` it dies
  at that first check **by design** - it is not a generic release tool.
- **What you do instead:** define your own cut - the honest minimum needs
  nothing but git:

  1. your verification gate (step 4) is green on a clean tree;
  2. pick the version and update whatever carries it in YOUR build
     (`package.json`, `Cargo.toml`, `CMakeLists.txt`, ...);
  3. `git tag -a vX.Y.Z -m "<summary>"` - push when you mean to publish
     (`git push origin <branch> --follow-tags`);
  4. write the release notes (`CHANGELOG.md` entry, the tag message, or a
     hosted release page).

  If you later want the automation, adapt `release.ps1` against YOUR gate: the
  reusable part is the pattern (clean-tree guard -> gate -> version -> tag ->
  next-SNAPSHOT on demand); the Tycho steps are not.

## First Eclipse start (how Eclipse finds your repo)

Eclipse cannot guess where the repo lives - four things can tell it, and
each is a one-timer:

0. **Pin it in `eclipse.ini`** (the simplest, machine-wide - and the
   strongest): one line in the vmargs -
   `-Dopencode.repo=C:/path/to/your/repo` - and the plugin takes it as THE
   repo (connection scope + task store + board project). **When set it wins
   over every preference and every `plugin_customization.ini` key** -
   Preferences cannot override it. `deploy-dev.ps1` pins it automatically
   to the repo it was run from.

1. **Open the relevant directory**: import/open your project's repo folder
   as a workspace project. The adoption rules then do the rest - the task
   store (`<repo>/.harness/tasks`) and the connection scope (the repo root,
   which is what MCP servers/agents/skills are scoped to) are both adopted
   from the open project. This is the recommended path without an ini pin.
2. **Adopt it from the Board**: the Board's *Adopt repo…* action opens a
   directory dialog, writes a root `.project` for the chosen repo, and
   imports + opens it as a workspace project - the repo-driven derivation
   then anchors to it exactly as in 1. Repos with nested Eclipse projects
   are refused (Eclipse forbids overlapping projects).
3. **Or ship the values in an ini file**: `eclipse/plugin_customization.ini`
   holds the three machine-local values (connection `workingDirectory`,
   `tasksRoot`, `tasksProject`) and is seeded into a fresh workspace when
   Eclipse runs with `-pluginCustomization plugin_customization.ini`
   (`deploy-dev.ps1` copies the file and adds the flag). Adapt the three
   paths for your machine; users can still override these in Preferences -
   they are defaults, not locks (unlike the `-Dopencode.repo` pin, which
   beats them all).

Either way, the Server view's description line shows the active scope
(`scoped to: ...`) so an empty MCP list is never silently misleading, and a
user-typed store root that is really a repo root descends into its
`.harness/tasks` automatically.

## Replacing the Hephaestus-specific bits (checklist)

| Template artifact | Replace with |
|---|---|
| `.harness/tasks/hephaestus/` | your project directory (delete ours) |
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
- **An older doc tells you to "Enable Fleet":** there is nothing to enable —
  the fleet was removed with U-110 (no `fleet` key in `opencode.json`, no
  Fleet view, no Enable Fleet button). Unattended work runs on the ticker
  (step 6); the `.git/opencode-fleet/` directory you may still see on disk is
  only the worktree isolation layer's home (`new-machine-setup.md` →
  *Machine-local state*).
- **Readiness shows WAIT_UPSTREAM forever:** upstream stages need `done`
  tickets linked by basis; unstaged tickets always read NOT_APPLICABLE
  and are never re-dispatched.
