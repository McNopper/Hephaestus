# Contracts

## About this document
- **Kind:** `doc` / reference (part of `.opencode/docs/`).
- **Read by:** agents and maintainers; **written by:** maintainers.
- **Related:** complements `domains.md`; the ticket/artifact rules are enforced by the `task_*` MCP tools (the `tasks` pack of the Eclipse harness's `eclipse-build` endpoint, also served over stdio via `eclipse/tasks-tools.ps1`) and the `project-manager-create-ticket` skill.

The shared, durable agreements every skill and agent honors. Change these centrally and
update all references.

## Ticket contract (`task_*` tools, `.opencode/tasks/` store)

A ticket is the **hand-off unit**. Its authoritative shape (per project):

| Field | Type | Notes |
|---|---|---|
| `id` | `T-NNN` (or prefix, e.g. `FR-001`) | minted by `task_create(id_prefix=)` |
| `title` | string | active verb, states outcome |
| `description` | string | what + why |
| `type` | `story`/`task`/`bug`/`spike` | |
| `status` | enum | `product-backlog` → `sprint-backlog` → `in-progress` → `in-review` → `done` |
| `blocked` + `blocker` | bool + string | **orthogonal** flag, any active state |
| `sprint` | `S-NN` / null | set by `task_plan_sprint`; null in backlog |
| `story_points` | int | relative size; `project-manager-estimate-costs` can later feed a `cost` field |
| `priority` | `low`/`medium`/`high`/`critical` | drives self-claim order |
| `role` | enum (extensible) | `architect`/`developer`/`tester`/`pm`/`cpp-engineer`/`graphics-engineer` → who claims it |
| `stage` | enum/null | V pipeline stage (canonical `VStages` order): `requirements`, `system`, `architecture`, `design`, `implementation`, `test-implementation`, `test-design`, `test-architecture`, `test-system`, `test-requirements`; `null` = legacy/untracked. Set at creation for definition work; verification tickets carry their test stage. `task_advance` moves a finished ticket to the next stage's backlog (role follows the new stage); `task_send_back` returns it to the previous stage, blocked with the reason. |
| `assignee` | string | set by `task_claim` |
| `acceptance_criteria` | string[] | GIVEN/WHEN/THEN; verification must satisfy all |
| `labels` | string[] | free tags |
| `epic` | string | parent ticket id for traceability |
| `artifacts` | `{kind, ref, note, by, ts}[]` | **where the produced output lives** |
| `todos` | `{text, done}[]` | checklist items (`task_add_todo`/`task_toggle_todo`/`task_remove_todo`) |
| `history` | append-only | state transitions |
| `comments` | append-only | human/agent notes |
| `created_at` / `updated_at` | ISO-8601 instant (UTC, e.g. `2026-08-28T03:25:20.519Z`) | drives claim/backlog ordering |

### The store (Maven-ready Markdown storage)

One **file per ticket** — `<repo>/.opencode/tasks/<project>/T-NNN.md` — Markdown with a
frontmatter block (scalars + one-line JSON lists) and tool-owned body sections
(`## Todos`, `## Artifacts`, `## Comments`, `## History`); the free-form description is the
body. A `_meta.json` sidecar holds the per-prefix id counters and sprint metadata
(goal/status/timestamps); the board is derived from the tickets. The files are
**version-controlled** (`.gitattributes` pins LF; the codec tolerates CRLF/BOM on read) so
task changes ride the normal git/Maven workflow — the seam the future
`opencode-tasks:sync`/`plan` mojos build on.

### Tool surface (`pm_*` → `task_*`)

`task_create`, `task_get`, `task_list`, `task_update`, `task_set_blocked`,
`task_clear_blocked`, `task_claim`, `task_release`, `task_add_comment`,
`task_add_artifact`, `task_add_todo`, `task_toggle_todo`, `task_remove_todo`,
`task_backlog`, `task_board`, `task_plan_sprint`, `task_close_sprint`,
`task_traceability`. Served by the **`eclipse-build` MCP endpoint** when the Eclipse
harness runs (plus the C++ tool pack) and by the **`tasks` stdio launcher**
(`eclipse/tasks-tools.ps1`, configured in `opencode.json`) for TUI-only sessions — one tool
surface, two transports. Note: opencode prefixes tools with the server name, so TUI
sessions see them as `tasks_task_*`; in-Eclipse agents as `eclipse-build_task_*`.

Plus the H6 readiness/stage pack: `task_readiness` (per-ticket READY / WAIT_UPSTREAM /
STALE / BLOCKED / RUNNING / NOT_APPLICABLE over the current board), `task_advance`,
`task_send_back` — the stage-flow tools the V-pipeline dispatcher builds on.

### Artifact kinds (the hand-off locator)

| kind | ref | when |
|---|---|---|
| `file` | `src/renderer/swapchain.cpp` | file(s) created/edited |
| `path` | `build/reports/cppcheck.xml` | a directory or report |
| `git` | `abc1234` or `branch: feat/x` | the commit / branch holding the work |
| `url` | `https://…/diff.png` | a remote resource |
| `doc` | `ARCH-003` | another artifact/ticket id |

**Rule:** a worker records its artifact with `task_add_artifact` *before* moving the ticket
to `in-review`, so the next agent needs no questions.

## Concurrency contract

- **Self-claim:** workers loop `task_claim(role=…)`; the call is atomic (an in-JVM lock
  plus an OS file lock on the project's `.lock`, whole-directory transactions, temp-rename
  writes) so concurrent agents — Eclipse-hosted sessions and stdio TUI processes alike —
  get distinct tickets. A claim with nothing to do returns the JSON literal `null`; worker
  loops terminate on it.
- **Reassign:** a returned ticket (`task_release` → `sprint-backlog`, `assignee` cleared)
  can be claimed by a *different* agent.
- **Store:** `eclipse/bundles/com.opencode.ide.tasks` — one Markdown file per ticket,
  atomic transactions, id counters persisted in `_meta.json` (task ids are never reused,
  even after a lost sidecar — they are recovered from the files; the sprint counter is
  not, so a lost sidecar can re-mint a sprint id — harmless because the board derives
  from the tickets).
- **Direct edits:** hand edits to a task file are allowed between tool writes but the
  tools rewrite whole files — last writer wins. Treat direct edits as read-only hints.

## Role → skill/agent dispatch

| role | owns/claims via | verifies via |
|---|---|---|
| `architect` | `software-system`, `software-architecture` | `test-software-system`, `test-software-architecture` |
| `developer` | `software-design`/`software-implementation` | matching `test-software-*` |
| `tester` | `test-software-*` | (itself) |
| `pm` | `software-requirements`, `project-manager-*` skills | — |
| `cpp-engineer` | `cpp-tools` agent | `cpp-tools` agent |
| `graphics-engineer` | `mcp.graphics` (+ `graphics-expert` for `very-high` work) | `graphics-render-comparison` |

## Model-tier contract

Agents/docs reference **tiers**, never hard-coded model IDs (except `graphics-expert`, which
is pinned to the `very-high` model). The authoritative tier **selection rules** live in
`project-manager-orchestrate-execution`; the concrete tier→model mapping lives in
`opencode.json` (default `model`) plus per-agent frontmatter overrides (only `graphics-expert`
overrides). Tiers: `very-low`, `low` (default executor), `mid`, `high`
(plan/review), `very-high` (run twice & reconcile). Pick the lowest tier that satisfies the
task; escalate, never de-escalate.
