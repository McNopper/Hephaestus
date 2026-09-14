# Fleet quickstart — your first headless dispatch

## About this document
- **Kind:** `guide` (quickstart how-to for the task fleet's headless flow).
- **Read by:** newcomers to Hephaestus — humans or agents — who want to dispatch their first ticket; **written by:** maintainers (ticket V-005).
- **Related:** `AGENTS.md` (workflow conventions), `eclipse/README.md` and `eclipse/ARCHITECTURE.md` (engine internals), `eclipse/DISTRIBUTED-FLEETS.md` (multi-machine store discipline). This is the newcomer path: seed ticket → dispatch → poll → merge → verify.

## What runs where (read this first)

- **The task store is the ground truth.** `.opencode/tasks/` (one Markdown file per
  ticket) is the single coordination blackboard. Workers report to their *ticket* —
  status, artifacts, actuals — never back to the session that dispatched them.
- **Your chat session is a launcher and a peer**, not a command tree. After a
  dispatch, follow progress by polling the store (`task_get`) and `fleet_jobs`; do
  not wait on or address the worker session.
- **Eclipse is optional.** The Board's Launch/Sync buttons are conveniences over the
  exact same engine; this guide never needs the IDE.

## Prerequisites

| Need | Check | Notes |
|---|---|---|
| JDK 21+ | `java -version` | On PATH or via `JAVA_HOME`. The fleet bundles are JavaSE-21 (17+ covers only the `tasks` server). |
| git | `git --version` | Creates the worktrees/branches and, if you use store sync, the store's git repo. |
| opencode binary | `opencode --version` | The engine spawns `opencode serve` on first dispatch. Version is pinned to 1.18.30 (endpoint-verified 2026-09-13 — on upgrade, rerun the endpoint smoke, then bump the pin in `ServerVersionPin`); a mismatch warns, never fails. |
| pwsh 7+ | `pwsh --version` | The MCP launchers are PowerShell scripts. |
| Built jars | see below | The stdio servers load jars from `eclipse/bundles/*/target/`. |

Build the bundles the two MCP servers need (from the repo root, Windows):

```pwsh
cd eclipse
.\build.ps1 clean verify
```

Use the full reactor: isolated `-pl` builds can fail Tycho dependency resolution.
The first build also populates the Tycho p2 cache the launchers resolve gson from.

Optional hardening: set `OPENCODE_SERVER_PASSWORD` before starting opencode. The
spawned server honors it; otherwise the engine generates a fresh random password so
it never runs unauthenticated.

## The two MCP servers

Both come from `opencode.json` — no Eclipse running required:

| Server | Launcher | Serves |
|---|---|---|
| `tasks` | `eclipse/tasks-tools.ps1` | the `task_*` pack — tickets, sprints, claims, comments, artifacts |
| `fleet` | `eclipse/fleet-tools.ps1` | the `fleet_*` pack — dispatch, jobs, permissions, store sync |

opencode prefixes tool names with the server name, so in a session you call
`tasks_task_create` / `fleet_fleet_dispatch`; on the JSON-RPC wire the names are
bare (`task_create`, `fleet_dispatch`). When Eclipse *is* running, its
`eclipse-build` endpoint serves the same `task_*` pack (`eclipse-build_task_*`).
You don't type these names — ask in chat ("create a ticket …") and the model calls
the tool — but the steps below name them so you know what happened.

## First run, end to end

### Optional automatic dispatch

After planning tickets into a sprint, call `fleet_fleet_auto_start`:

```json
{ "project": "myproject", "sprint": "S-01", "max_concurrent": 4,
  "cost_budget_usd": 5, "include_stale": false }
```

`fleet_fleet_auto_status` reports this engine's loop and scope;
`fleet_fleet_auto_stop` stops admissions while accepted jobs settle normally.
The loop polls every five seconds and uses the same readiness policy as the Board.
Board and chat schedulers count the shared repository reservations and serialize
capacity-check plus reservation. Explicit manual dispatch can exceed the scheduler
cap. Cost is an admission estimate (recorded project spend plus estimated in-flight
work), not a hard billing limit. Failed tickets stay blocked until reviewed/reset.
Loop settings last for this MCP process; restart explicitly after a process restart.

`fleet_reset` reserves the ticket before cleanup and refuses a live peer's marker.
Dead-owner markers are swept; unknown/malformed ownership is retained for inspection.

Use distinct ticket prefixes across projects in the same repository. Fleet branch
names remain ID-based; persistent `.project` metadata binds each slot to its owning
project, even after reset/reaping. A colliding project is refused. Legacy unowned
worktree/branch residue requires ownership investigation and explicit recovery;
it is not automatically adopted or deleted.

### One-ticket dispatch

**1. Seed a ticket** — `tasks_task_create` (status lands in `product-backlog`):

```json
{ "project": "myproject", "title": "Add a greeting endpoint",
  "description": "Return a friendly greeting from the API.",
  "role": "developer", "story_points": 2,
  "acceptance_criteria": ["GET /greet returns 200 with a greeting body"] }
```

**2. Plan a sprint** — `tasks_task_plan_sprint` moves tickets to `sprint-backlog`
(the dispatchable state):

```json
{ "project": "myproject", "sprint_id": "S-01",
  "goal": "Ship the greeting.", "ticket_ids": ["T-001"] }
```

**3. Dispatch** — `fleet_fleet_dispatch` is async and returns immediately:

```json
{ "project": "myproject", "ticket_id": "T-001", "timeout_minutes": 30 }
```

Guards: the ticket must exist, be unblocked, not `done`, and not already in flight.
`timeout_minutes` defaults to 30, max 1440.

**4. Poll** — `fleet_fleet_jobs` shows the live snapshot per ticket:
`RUNNING` → `COMPLETED` → `MERGED`, or `FAILED` with a `detail`, plus `session_id`
and `worktree`. An empty list means nothing has been dispatched yet (the engine and
its `opencode serve` spawn lazily on first dispatch).

**5. Verify on the ticket** — `tasks_task_get`:

```json
{ "project": "myproject", "ticket_id": "T-001" }
```

A successful run shows status `in-review`, a `git` artifact (`opencode/T-001`,
the merged fleet branch), and an actuals comment like:

```
fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22), agent executor, model zai/glm-5.3
```

Those comments are the measured cost baseline; they accumulate on tickets.

### What a dispatch does under the hood

1. Pre-claims the ticket in the main store (`in-progress`, assignee `fleet`).
2. Creates branch `opencode/T-001` and worktree `<repo>/.git/opencode-fleet/T-001`
   (hidden from `git status`).
3. Maps the ticket's role to an agent — developer/tester → `executor`, architect →
   `manifest-author`, pm → `project-manager`, cpp-engineer → `cpp-tools`,
   graphics-engineer → `graphics-expert` (unknown role = server default) — and sends
   it the ticket as a self-claim prompt in the worktree.
4. Awaits completion, merges the branch back (serialized), records bookkeeping,
   then best-effort syncs the store's git repo.

## When the worker asks for permission

Unattended sessions that hit a permission ask stall until you answer (within the
timeout). Watch and answer from chat:

- `fleet_fleet_permissions` — lists pending asks: `permission_id`, `session_id`,
  `permission`, `title`, `patterns`. Empty when nobody is waiting.
- `fleet_fleet_permissions_answer`:

```json
{ "permission_id": "per_1", "response": "once", "remember": false }
```

`once` approves this occurrence, `always` approves every matching ask, `reject`
denies it; `remember: true` persists the decision as a rule. Practical corollary:
don't dispatch blocked or risky tickets unattended — an unanswered ask burns the
run's whole timeout.

## Store sync and recover

For one machine you can ignore this. With several machines sharing the store's git
repo (see `eclipse/DISTRIBUTED-FLEETS.md`), keep the rhythm **pull → claim → push**:

- `fleet_fleet_status_store` — one line: branch, ahead/behind, changed files.
- `fleet_fleet_sync_store` — `add -A` (scoped to the store subtree — it can never
  sweep unrelated repo WIP into a fleet commit), commit, `pull --rebase`, push (optional
  `message` for the commit). Runs automatically, best-effort, after every launch.
- If sync reports `PULL_CONFLICT` (a rebase is wedged): `fleet_fleet_recover_store`
  aborts the rebase and **keeps local commits**; then sync again or resolve by
  hand. A lost claim simply means re-dispatch.

## When things go wrong

| Symptom | Meaning | Recovery |
|---|---|---|
| job `FAILED`, ticket **released to sprint-backlog + `blocked` with reason** (the claim never lingers as in-progress) | submit failure; budget timeout (the session is aborted); stall (idle and silent ~5 min — aborted); merge conflict; empty result ("worker produced no changes") | fix the cause, `tasks_task_clear_blocked`, re-dispatch |
| worktree still in `.git/opencode-fleet/` | kept deliberately for post-mortem (also on success, until cleaned) | inspect it, then delete |
| dispatch refused: "already in flight" | one launch per ticket at a time | poll `fleet_fleet_jobs`, wait for `MERGED`/`FAILED` |
| dispatch refused: "is blocked" | a blocker flag is set | read it via `tasks_task_get`, clear it first |

## Next steps

- `tasks_task_board` / `tasks_task_readiness` — what's in flight and what's runnable now.
- `AGENTS.md` — states, the V-pipeline stages, and the chat-first control plane.
- `eclipse/DISTRIBUTED-FLEETS.md` — running one store across many machines.
