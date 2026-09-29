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
| opencode **v2** (2.0.10+; last live cross-check: 2.0.19, 2026-09-29) | the agent harness itself |
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
   **If the Eclipse workspace is not the repo** (e.g. the default
   `~\eclipse-workspace`), the plugin derives a wrong scope: the Server view
   asks the shared service for agents/skills/MCP servers per *directory*,
   and for the workspace directory the service answers empty — MCP servers
   look dead while they are alive. Fix once in *Preferences → OpenCode →
   Connection*: **Working directory** = the repo root (next to it: task-store
   root = `<repo>\.opencode\tasks` for the Board).
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

### `fleet` reconnects itself after a service restart (T-003)

**Behavior** (observed 2026-09-23 on v2.0.14; unchanged in source through
v2.0.19): after a shared-service restart the repo-scoped MCP list shows
`fleet` **connected** and `FleetStdioMain` is running — although
`opencode.json` keeps `"enabled": false` on the fleet entry (it stays off
deliberately: token eater, user direction 2026-09-22).

**Root cause — upstream, not ours.** v2 renamed the per-server off-switch:
the v2 config schema knows `disabled`, not `enabled`
(`packages/schema/src/mcp.ts`, `Mcp.LocalConfig`/`Mcp.RemoteConfig`). The
v2 config normalizer routes the nested `mcp.servers.<name>` map through
that NATIVE schema and silently drops unknown keys
(`packages/core/src/config/normalize.ts` at tag v2.0.19: line 45
`onExcessProperty: "ignore"`, line 269 the `name === "servers"` branch) —
so `enabled: false` never reaches the MCP service. At location start the
service auto-starts every server not marked `disabled`
(`packages/core/src/mcp/index.ts` lines 539-545:
`if (entry.config.disabled) ... continue; fork(startServer(...))`). Only
the FLAT legacy spelling (`"mcp": { "fleet": { ... } }`) goes through the
v1 migration, which maps the flag correctly
(`packages/core/src/v1/config/migrate.ts` line 203:
`const disabled = info.enabled === undefined ? undefined : !info.enabled`).
Nothing on our side connects `fleet` — the plugin auto-registers only the
`eclipse-build` HTTP endpoint (`McpRegistration`), connect is a manual
dialog action — so an "explicit client connect" is ruled out. The drop is
not even new in 2.0.14 (normalizer and auto-start are identical at
v2.0.11); the 09-22 "2.0.10/2.0.11 did not connect it" observation most
likely had a local confound (a launcher that fails to spawn reads as
failed, not connected). Upstream issue: **placeholder — not yet filed**
(anomalyco/opencode: `mcp.servers.<name>.enabled` silently dropped by the
v2 config normalizer).

**Workaround.** Immediate — the disconnect recovery recipe (same shape as
the connect recipe in `docs/opencode-v2-adoption.md`, "Contract notes"):
`POST /api/experimental/mcp/fleet/disconnect?location[directory]=<repo>`
(Basic auth; password in `~/.local/state/opencode/service.json`; the
`location[directory]` **deepObject** query scopes the call to THIS repo's
MCP instance — a bare `?directory=` resolves to the home location). That
stops the running launcher and marks `fleet` disabled, but the state is
in-memory only: the next service restart re-arms it. Durable — spell the
off-switch the way v2 reads it: `"disabled": true` on the fleet entry
(keep `"enabled": false` alongside; builds that read the legacy key again
are covered too, the two flags never contradict).

**Verify** (T-003's acceptance): kill the service, start a TUI in the
repo, then the repo-scoped `GET /api/mcp?location[directory]=<repo>` must
show `fleet` **disabled**.

## Where work resumes

This machine is fully set up (2026-09-22: plugin deployed + live-verified,
`tasks`/`graphics` MCP connected, `fleet` disabled). Next session starts
with **T-002** (Eclipse live pass: task-store root preference, perspective,
Board/Server/Chat, first Board *Launch task*), then the demo-filed defects
in priority order: **B-011** (critical: silent uncommitted-work release),
**B-008** (completion latency), **B-012** (stdio zombie pipes), **B-013**
(streaming tool status). Panels U-040/U-041 follow the engine-side
observability work. Details at `ROADMAP.md` top.
