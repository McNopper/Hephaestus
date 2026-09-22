# New machine setup — Hephaestus

## About this document
- **Kind:** `doc` / setup runbook for bringing a fresh machine up.
- **Read by:** the maintainer on a new PC, or any agent bootstrapping one.
- **Related:** `eclipse/INSTALL.md` (build/deploy detail), root `AGENTS.md`
  (workflow), `ROADMAP.md` (where work resumes), `docs/HISTORY.md` (the archive).

## Prerequisites

| Tool | Needed for |
|---|---|
| JDK 21+ (`java` on PATH) | Eclipse plugin build + stdio MCP servers |
| Git | everything |
| opencode **v2** (2.0.10+; developed against 2.0.10/2.0.11) | the agent harness itself |
| PowerShell 7 (`pwsh`) | **required** — the build itself fails without it (the tools/tasks Eclipse-import-ban scan runs through pwsh); also the deploy scripts and the `tasks`/`fleet` MCP stdio launchers. Install: `winget install Microsoft.PowerShell` |
| Node.js | chat-web checks inside `mvn verify` |
| MSYS2 `clang64` on PATH (`cmake`, `ninja`, clang tools) | `cpp/` presets + `verify` |
| Eclipse CDT (default `C:\eclipse-cpp`) | the IDE harness (optional for TUI-only work) |

## Bring-up

1. `git clone <repo>` — the task store (`.opencode/tasks/`) and all docs come
   with it; **the board state is in the repo, so it survives the switch**.
2. Build + gate: `cd eclipse; .\build.ps1 clean verify` (Java 21, Tycho).
   This also populates the Tycho p2 cache (`~/.m2/repository/p2/`) that the
   stdio MCP launchers resolve gson from — the `tasks`/`fleet` servers cannot
   start before one successful build.
3. C++ gate (optional): `cd cpp; cmake --preset default` with MSYS2 clang64 on
   PATH, then `cmake --build build-clang64 --target verify`.
4. Eclipse harness (optional): install CDT, close Eclipse,
   `cd eclipse; .\deploy-dev.ps1`, restart Eclipse. Set `ECLIPSE_HOME` when
   Eclipse lives elsewhere — `deploy-dev.ps1` and the stdio launchers
   (optional gson source) honor it; no script hardcodes an install path.
5. opencode: start it in the repo root. The shared background service
   self-registers (`~/.config/opencode/service.json`, generated per machine).
   Re-connect providers (`/connect`) — credentials are machine-local.
   If the TUI shows `tasks`/`fleet` as failed (`Connection closed`), they were
   spawned before pwsh or the built jars existed. The MCP servers belong to
   the shared background service, so a TUI restart alone keeps the stale
   state — reconnect per server instead:
   `POST /api/experimental/mcp/<name>/connect?location[directory]=<repo>`
   (Basic auth; password in `~/.local/state/opencode/service.json`), or kill
   the service so the next client start respawns it. The launchers need pwsh,
   java 21+ on PATH, the built jars (step 2) and gson from the Tycho cache.

## Machine-local state (never committed, re-created per machine)

- `~/.config/opencode/` and `~/.local/share/opencode/` — service registration,
  generated passwords, provider credentials, logs.
- `.git/opencode-fleet/` — fleet worktrees + daemon pidfile (clean at the last
  wrap; stale residue is reclaimed by `fleet_reset`/dispatch, see AGENTS.md).
- Build output (`eclipse/**/target/`, `cpp/build-*`) — gitignored.

**Public repo — hard rule:** no credentials, service passwords, tokens, or
private machine details in the repo. Runtime passwords are generated and never
logged; a tracked-file scan is part of this checklist (`git grep -i
'(password|api_key|token)\s*[:=]'` should only hit code).

## MCP servers

From `opencode.json`: `tasks` (store tools) and `graphics` enabled; **`fleet`
is disabled by default** (user direction 2026-09-22 — no auto-started
fleets). Start fleet work deliberately: the Eclipse Board's `Auto ▶`/`Waves ▶`
toggles, or set `"enabled": true` for a dev session / connect via
`POST /api/experimental/mcp/fleet/connect`.

## Where work resumes

This machine is fully set up (2026-09-22: plugin deployed + live-verified,
`tasks`/`graphics` MCP connected, `fleet` disabled). Next session starts
with **T-002** (Eclipse live pass: task-store root preference, perspective,
Board/Server/Chat, first Board *Launch task*), then the demo-filed defects
in priority order: **B-011** (critical: silent uncommitted-work release),
**B-008** (completion latency), **B-012** (stdio zombie pipes), **B-013**
(streaming tool status). Panels U-040/U-041 follow the engine-side
observability work. Details at `ROADMAP.md` top.
