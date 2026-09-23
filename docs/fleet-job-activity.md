# `fleet_job_activity` — deep live observation of a fleet worker

## About this document
- **Kind:** `doc` / tool reference for one fleet MCP tool.
- **Read by:** agents and humans watching dispatched fleet work live;
  later, the Eclipse panels that will render this data.
- **Written by:** fleet worker, ticket T-001 (2026-09-22).
- **Related:** `docs/fleet-observability-eclipse-reuse.md` (the Eclipse UI
  plans), `AGENTS.md` (chat-first control plane), source in
  `eclipse/bundles/com.opencode.ide.fleet/.../FleetToolProvider.java`.

## What it is

`fleet_job_activity` is the fleet MCP tool that answers *"what is the worker
DOING right now"* — not just whether it is moving (`fleet_job_details` does
that), but what it is actually doing. It returns one point-in-time
observation of the session, built from pollable endpoints only (no event
stream needed), so you can poll it to watch a dispatched worker live.

## What it reports

| Field | Meaning |
|---|---|
| `activity` | The current activity: the in-flight tool/shell (non-terminal status — `running`, `streaming`, …) and its main target (file, command, pattern), when one is visible. |
| `tools` | Every tool used so far: `name`, lifecycle `status` (non-terminal `running`/`streaming`/… vs terminal `completed`/`error`), and the main `target` when known. |
| `shells` | Every shell command run: `command`, `status`, `exit` code, and a short `output_tail`. |
| `subagents` | Child sessions spawned by the worker (nested via `parentID`): `session_id`, `title`, `agent`, `status`, `cost_usd`, `tokens`. |
| `cost_usd` / `tokens` | Cumulative cost and token usage of the session. |
| `last_text` | The newest assistant text the session produced. |

Plus identity/status fields (`session_id`, `title`, `agent`, `model`,
`status`, `outcome`) and, on a `ticket_id` lookup, the job's `state`.
Fields are nullable-tolerant — a partially readable session still yields a
useful observation.

## Parameters

- `ticket_id` — observe the job dispatched for this ticket (e.g. `U-015`).
- `session_id` — observe any session directly, overriding the ticket lookup.
  Use this to drill into a subagent child taken from the `subagents` list.

One of the two is required. Unknown tickets return `state: "unknown"` with a
hint; a job without a live session reports `observation: "unavailable"`.

## Consumers

Today this is a chat/MCP tool for polling a worker live. **Eclipse panels
will consume it later** — the planned fleet tree view and session drill-down
(see `docs/fleet-observability-eclipse-reuse.md`) render exactly this data:
activity text per node, tools/shells as console tasks, and tokens/cost
rollups per subtree.
