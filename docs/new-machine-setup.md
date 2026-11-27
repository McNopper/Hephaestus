# New machine setup — Hephaestus

## About this document

- **Kind:** `doc` / setup runbook for bringing a fresh machine up.
- **Read by:** the maintainer on a new PC, or any agent bootstrapping one.
- **Related:** `eclipse/INSTALL.md` (build/deploy detail), root `AGENTS.md`
  (workflow), `ROADMAP.md` (where work resumes), `docs/HISTORY.md` (the archive),
  `TESTING.md` (once this file has the machine up: exercise it — §1 setup, §3
  headless path).

## Prerequisites

| Tool | Needed for |
|---|---|
| JDK 21+ (`java` on PATH) | Eclipse plugin build + stdio MCP servers |
| Git | everything |
| opencode **v2** (pinned & endpoint-verified against **2.4.2**, see `ServerVersionPin`; a mismatch warns, never fails) | the agent harness itself |
| PowerShell 7 (`pwsh`) | **required** — the build itself fails without it (the tools/tasks Eclipse-import-ban scan runs through pwsh); also the deploy scripts and the `tasks` MCP stdio launcher. Install: `winget install Microsoft.PowerShell` |
| Node.js | chat-web checks inside `mvn verify` |
| MSYS2 `clang64` on PATH (`cmake`, `ninja`, clang tools) | `cpp/` presets + `verify` |
| Eclipse CDT (default `C:\eclipse-cpp`) | the IDE harness (optional for TUI-only work) |

This table is the **canonical prerequisite list** for the repo — `docs/TESTING.md`
links here instead of restating it. One thing deliberately has **no** row:
**Python** — this repo tracks no `mcp/` contents (`git ls-files mcp/` is empty;
the `graphics` server was curated out on 2026-10-15 and is setup-supplied, see
`own-project.md` step 0), and the single tracked `.py` file
(`eclipse/bundles/com.opencode.ide.core/generate-action-icons.py`) is a manual
icon generator.

## Bring-up

1. `git clone <repo>` — the task store (`.harness/tasks/`) and all docs come
   with it; **the board state is in the repo, so it survives the switch**.
2. Build + gate: `cd eclipse; .\build.ps1 clean verify` (Java 21, Tycho).
   This also populates the Tycho p2 cache (`~/.m2/repository/p2/`) that the
   stdio MCP launcher resolves gson from — the `tasks` server cannot
   start before one successful build.
3. C++ gate (optional): `cd cpp; cmake --preset clang64` from an MSYS2 CLANG64
   shell, then `cmake --build build-clang64 --target verify`. With native LLVM
   on PATH instead: `cmake --preset default`, then
   `cmake --build build --target verify`. Each preset owns its own build dir
   (`clang64` → `build-clang64`, `default` → `build`) — pair them, never mix.
4. Eclipse harness (optional): install CDT, close Eclipse,
   `cd eclipse; .\deploy-dev.ps1`, restart Eclipse. Set `ECLIPSE_HOME` when
   Eclipse lives elsewhere — `deploy-dev.ps1` and the `tasks` stdio launcher
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
   root = `<repo>\.harness\tasks` for the Board).
5. opencode: start it in the repo root. The shared background service
   self-registers (`~/.local/state/opencode/service.json`, generated per
   machine; `~/.config/opencode/` is the legacy password fallback).
   Re-connect providers (`/connect`) — credentials are machine-local.
   If the TUI shows `tasks` as failed (`Connection closed`), it was spawned
   before pwsh or the built jars existed. The MCP server belongs to the
   shared background service, so a TUI restart alone keeps the stale
   state — reconnect it instead:
   `POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`
   (Basic auth; password in `~/.local/state/opencode/service.json`), or kill
   the service so the next client start respawns it. The launcher needs pwsh,
   java 21+ on PATH, the built jars (step 2) and gson from the Tycho cache.
6. **Exercise it.** Once the server answers, walk `docs/TESTING.md` — §1 is
   this same bring-up, §2 is the surface checklist, §3 is the no-Eclipse
   path. A fresh machine that gets through it is up.

## Machine-local state (never committed, re-created per machine)

- `~/.local/state/opencode/` — service registration (`service.json`: port +
  generated password), logs; `~/.local/share/opencode/` — provider
  credentials; `~/.config/opencode/` — legacy password fallback only.
- `.git/opencode-fleet/` — the worktree root of the agentic-git isolation
  layer (`WorktreeManager`/`FleetGit` in `com.opencode.ide.git`: one worktree
  per dispatched ticket, hidden from `git status`), plus the staged
  launcher-jar copies (`lib/`, refreshed per server start). The directory name
  predates the fleet's retirement (U-110) and stays: there is no daemon and
  no pidfile, and stale residue is reclaimed automatically (`git worktree
  prune` plus the worktree manager's reclaim, which refuses to auto-reclaim
  residue that still carries commits main lacks).
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

`opencode.json` (committed) declares exactly **one** MCP server — the whole
`mcp.servers` block, quoted verbatim:

```json
"mcp": {
  "servers": {
    "tasks": {
      "type": "local",
      "command": ["pwsh", "-NoProfile", "-File", "eclipse/tasks-tools.ps1"]
    }
  }
}
```

- **`tasks`** — the `task_*` store tools (tickets, readiness, doctor). It is
  launched through `pwsh` from the repo root and needs pwsh + Java 21 on PATH,
  the built jars (step 2) and gson from the Tycho cache. When the Eclipse
  harness runs, the same tools are served by the Eclipse-hosted `eclipse-build`
  endpoint instead: TUI sessions see them prefixed `tasks_task_*`, in-Eclipse
  agents `eclipse-build_task_*` (`.opencode/docs/contracts.md`, *Tool surface*).
- **There is no `fleet` entry and no `graphics` entry** — and nothing to
  enable. The `fleet` MCP server, its `fleet-tools.ps1` launcher and the Fleet
  view's *Enable Fleet* button were removed with the fleet in U-110; the Board
  has no `Auto ▶`/`Waves ▶` pump either (retired fleet surfaces inventoried in
  `docs/eclipse-gui-review-2026-11-15.md` §2.3). The old advice to flip
  `"disabled": false` died with the entry — this config has no `disabled` key
  at all (the `enabled`-vs-`disabled` normalizer story, T-003, is archive in
  `docs/HISTORY.md`).
- **Check / reconnect:** `GET /api/mcp?location[directory]=<repo>` shows the
  per-server state; `POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`
  (Basic auth; password in `~/.local/state/opencode/service.json`) connects or
  reconnects the server, and the matching `.../disconnect` stops the launcher
  (in-memory only — the next service restart re-reads `opencode.json`). The
  `location[directory]` **deepObject** query scopes the call to THIS repo's
  MCP instance — a bare `?directory=` resolves to the home location.

## Where work resumes

Not hardcoded here — the plan lives in the store and the roadmap, and this
file never duplicates their state:

- `ROADMAP.md`, the *Release path* table — the ordered pointer to open work
  and its priorities.
- `task_board` / `task_list` (project `hephaestus`) — the live ticket
  state; the task store is the source of record.
