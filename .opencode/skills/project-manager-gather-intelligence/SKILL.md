---
name: project-manager-gather-intelligence
description: >
  Use this skill on demand to gather LIVE intelligence from running opencode servers:
  pull per-session tokens, cost, duration, agent and model over the REST API, aggregate
  them per agent/model/ticket, record the actuals on the matching tickets in the task
  store, and emit a measured cost baseline that calibrates project-manager-estimate-costs. Read-only
  against the server; the tickets are the accumulation point. opencode workflow utility.
---

# Live Intelligence Gathering Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the `project-manager-*` domain set; the measured counterpart of `project-manager-estimate-costs`
  (a-priori estimates) and the data source for its calibration; works with the task store
  (`task_*` tools). Market-side model intelligence (intelligence index, cost per task,
  context windows) comes from `research-artificial-analysis-models` — together the three
  skills form the cost loop: market rates → estimate → run → measure.

## Purpose

`project-manager-estimate-costs` prices a plan **before** it runs, from scope guesses. This skill closes
the loop with **actuals**: what did the agents really spend? The accumulated actuals become
the measured baseline per role/task-type so future estimates stop guessing.

## Position

Standalone, on-demand. Invoke after a fleet batch, at wave close, or whenever the human
asks "what did this cost so far?". Never runs automatically.

## The live surface (opencode v2; `/openapi.json` last cross-checked live on 2.0.19, 2026-09-29)

Three v2 facts shape every query: **every path is prefixed `/api`**, the server **requires
HTTP Basic auth**, and the **port is dynamic** — so discovery comes first:

- **Base URL** — `opencode api get /api/info` → `{ version, pid, urls: ["http://127.0.0.1:<port>"], paths }`;
  take `urls[0]`. The old hard-coded `4096` default is gone.
- **Auth** — username `opencode`, password from
  `~/.local/state/opencode/service.json` (`$XDG_STATE_HOME/opencode/service.json`;
  `~/.config/opencode/service.json` is the legacy fallback) — the file holds
  `{"password": "…"}`. `opencode api get <path>` resolves the port **and** authenticates
  for you; prefer it over raw HTTP.
- When the Eclipse harness runs, the Server view / connection preferences hold the
  resolved URL.

`GET /api/session` returns a **list envelope**, not a bare array:

```
{ data: [ { id, projectID, title, agent, parentID,
            model: { id, providerID, variant },
            time: { created, updated, idle, viewed, archived },   // epoch millis → duration = updated - created
            cost,                              // server-computed USD (sum over messages)
            tokens: { input, output, reasoning, cache: { read, write } },
            outcome, location: { directory }, subpath, metadata, permissions, revert } ],
  cursor: … }
```

- **Unwrap `.data` first.** Every list endpoint wraps its rows this way (`/api/session`,
  `/api/agent`, `/api/model`, `/api/provider`, `/api/skill`, `/api/command`, `/api/mcp`).
- Filter to one worktree/fleet job **client-side** on `location.directory`.
- `parentID` nests subagent sessions under their parent — **aggregate children into the
  parent** before attributing cost to a ticket.
- `cache.read/write` tokens are billed at different rates than plain input; do not
  recompute cost from tokens — trust the server's `cost` field.

## Workflow

1. **Collect** — query every configured server. Simplest path (`opencode api get` does the
   port discovery **and** the Basic auth for you). PowerShell:
   ```powershell
   $sessions = (opencode api get /api/session | ConvertFrom-Json).data   # unwrap the envelope
   # flatten: children roll up into parents
   $roots = $sessions | Where-Object { -not $_.parentID }
   ```
   bash: `opencode api get /api/session | jq '[.data[] | select(.parentID == null)]'`

   Explicit alternative (raw HTTP — you resolve the base URL and send Basic auth yourself).
   PowerShell:
   ```powershell
   $base = (opencode api get /api/info | ConvertFrom-Json).urls[0]
   $pw   = (Get-Content "$HOME/.local/state/opencode/service.json" -Raw | ConvertFrom-Json).password
          # (legacy fallback: "$HOME/.config/opencode/service.json")
   $hdr  = @{ Authorization = "Basic " + [Convert]::ToBase64String(
                [Text.Encoding]::UTF8.GetBytes("opencode:$pw")) }
   $sessions = (Invoke-RestMethod "$base/api/session" -Headers $hdr).data
   ```
   bash:
   ```bash
   BASE=$(opencode api get /api/info | jq -r '.urls[0]')
   PW=$(jq -r '.password' ~/.local/state/opencode/service.json)   # legacy fallback: ~/.config/opencode/service.json
   curl -s -u "opencode:$PW" "$BASE/api/session" | jq '[.data[] | select(.parentID == null)]'
   ```
2. **Map session → ticket** — fleet sessions carry the ticket id in their
   title: workers are `[<id>] <title>`, reviews `[<id>] review: <title>`
   (U-054). Map by that prefix first; the belt-and-braces fallback is the
   worktree directory: a fleet worker session's `location.directory` is
   `<repoRoot>/.git/opencode-fleet/<ticket-id>` — the last path segment is the
   ticket id. Sessions in the main checkout without a ticket id in their title
   are overhead; keep them in the aggregates, attribute them to `(unassigned)`.
3. **Record actuals on each ticket** — append one comment in the engine's
   `fleet actuals:` format; the Board's cost overview and the dispatch budget
   parse **only that prefix** (any other format, e.g. `telemetry: …`, is
   invisible to them):
   ```
   task_add_comment(project, ticket_id,
     comment: "fleet actuals: cost <C> USD, tokens <T> (in <I> / out <O> / reasoning <R>), agent <name>, model <provider/model>")
   ```
   (The engine's `FleetTelemetry` emits exactly this shape; omit the parts you
   do not have — `cost 1.5 USD` alone parses.)
   Record once per session (check history first — idempotent re-runs must not double-count).
4. **Aggregate** — produce the rollup:
   - per ticket: total cost, tokens, duration, attempts (sessions count)
   - per agent and per model: totals and means
   - cost **per story point** and per task `type`/`role` — the estimator's leverage numbers
5. **Emit the baseline** — write a Markdown report (e.g.
   `.opencode/tasks/<project>/_reports/cost-baseline-<date>.md`) and attach it:
   `task_add_artifact(project, ticket_id="EP-…|none", kind="path", ref="<report>")`
   when a wave/epic owns the batch, else just report in-channel.
6. **Calibrate** — hand the measured numbers to `project-manager-estimate-costs`: per-tier actuals
   replace rate-card guesses for the workload classes that have ≥3 samples. Flag the rest
   as still-guessed. Recommend de-escalations where a lower tier consistently sufficed.
   If the rate card itself is stale (prices drifted from the market), refresh it via
   `research-artificial-analysis-models` (live leaderboard: intelligence index, cost per
   task, context windows) — do not invent prices.

## Guardrails

- Read-only against the server; writes go only to the task store and report files.
- Costs are the server's truth — never re-derive from token counts.
- A crashed/retried session still counts (it spent money); mark retries in the comment.
- Do not push, do not start/stop servers, do not touch worktrees.

## Hand-off map

- Estimate needed before a run → `project-manager-estimate-costs` (this skill feeds it).
- Wave close / review numbers → the `project-manager` agent consumes the report.
- Fleet telemetry automation (TaskFleet recording `fleet actuals:` comments on
  mergeBack) has landed; this skill remains the manual path for ad-hoc queries
  and calibration.
