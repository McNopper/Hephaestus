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
| opencode **v2** (pinned & endpoint-verified against **2.0.19**, see `ServerVersionPin`; a mismatch warns, never fails) | the agent harness itself |
| Python 3 + pip | the `graphics` MCP server — `pip install -r mcp/graphics/requirements.txt` |
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
3. C++ gate (optional): `cd cpp; cmake --preset clang64` from an MSYS2 CLANG64
   shell, then `cmake --build build-clang64 --target verify`. With native LLVM
   on PATH instead: `cmake --preset default`, then
   `cmake --build build --target verify`. Each preset owns its own build dir
   (`clang64` → `build-clang64`, `default` → `build`) — pair them, never mix.
4. Eclipse harness (optional): install CDT, close Eclipse,
   `cd eclipse; .\deploy-dev.ps1`, restart Eclipse. Set `ECLIPSE_HOME` when
   Eclipse lives elsewhere — `deploy-dev.ps1` and the stdio launchers
   (optional gson source) honor it; no script hardcodes an install path.
   `deploy-dev.ps1` also pins the repo into `eclipse.ini`
   (`-Dopencode.repo=<repo>` in the vmargs) — **when set it wins over every
   preference and ini key**, so connection scope and task store resolve from
   it and the workspace location stops mattering. **Without that pin, if the
   Eclipse workspace is not the repo** (e.g. the default
   `~\eclipse-workspace`), the plugin derives a wrong scope: the Server view
   asks the shared service for agents/skills/MCP servers per *directory*,
   and for the workspace directory the service answers empty — MCP servers
   look dead while they are alive. Fix once in *Preferences → OpenCode →
   Connection*: **Working directory** = the repo root (next to it: task-store
   root = `<repo>\.opencode\tasks` for the Board).
5. opencode: start it in the repo root. The shared background service
   self-registers (`~/.local/state/opencode/service.json`, generated per
   machine; `~/.config/opencode/` is the legacy password fallback).
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

- `~/.local/state/opencode/` — service registration (`service.json`: port +
  generated password), logs; `~/.local/share/opencode/` — provider
  credentials; `~/.config/opencode/` — legacy password fallback only.
- `.git/opencode-fleet/` — fleet worktrees plus the staged launcher-jar
  copies (`lib/`, refreshed per server start) and bookkeeping markers. No
  pidfile (the detached daemon is retired); stale residue is reclaimed by
  `fleet_reset`/dispatch, see AGENTS.md.
- Build output (`eclipse/**/target/`, `cpp/build-*`) — gitignored.

**Public repo — hard rule:** no credentials, service passwords, tokens, or
private machine details in the repo. Runtime passwords are generated and never
logged; a tracked-file scan is part of this checklist:

```pwsh
git grep -iE "(password|api_key|token)\s*[:=]"
```

The `-E` is load-bearing: without it git grep runs in basic-regex mode where
`(...)` is a literal and the pattern matches **nothing** — the check silently
passes. Expect roughly a hundred hits, all of them code or config (118 lines
across 26 files at v0.1.1); zero hits means the pattern is wrong, not that the
repo is clean.

## MCP servers

From `opencode.json`: `tasks` (store tools) and `graphics` enabled; **`fleet`
is disabled by default** — the entry reads `"disabled": true` (no
auto-started fleets; it eats tokens). Start fleet work deliberately:

- **Eclipse:** the Board's `Auto ▶` / `Waves ▶` toggles, or the Fleet view's
  *Enable Fleet* button (that button lives in the Fleet view only).
- **Chat/TUI:** flip the fleet entry to `"disabled": false`. v2's config
  schema knows `disabled`, not the legacy `enabled` key — the v2 config
  normalizer silently drops unknown keys, so `"enabled": true` does nothing
  (root cause archived in `docs/HISTORY.md`, T-003). Then connect the server
  scoped to this repo:
  `POST /api/experimental/mcp/fleet/connect?location[directory]=<repo>`
  (Basic auth; password in `~/.local/state/opencode/service.json`). The
  `location[directory]` **deepObject** query scopes the call to THIS repo's
  MCP instance — a bare `?directory=` resolves to the home location.
- To check what is actually running:
  `GET /api/mcp?location[directory]=<repo>` shows the per-server state; the
  matching `POST /api/experimental/mcp/<name>/disconnect?location[directory]=<repo>`
  stops a running launcher (in-memory only — the next service restart re-reads
  `opencode.json`).

## Where work resumes

Not hardcoded here — the plan lives in the store and the roadmap, and this
file never duplicates their state:

- `ROADMAP.md`, top section *How to take over (for agents)* — the current
  wave and the pointer to open work and its priorities.
- `task_board` / `task_backlog` (project `hephaestus`) — the live ticket
  state; the task store is the source of record.
