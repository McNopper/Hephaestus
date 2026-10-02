# OpenCode IDE plugin — install & run guide

Eclipse plugin integrating [opencode](https://opencode.ai) into the Eclipse CDT IDE
(`<eclipse-install>` — default `C:\eclipse-cpp`; the deploy scripts accept an
`-EclipseRoot` argument or the `ECLIPSE_HOME` env var; Eclipse 4.40 / Java 21 / CDT 12.5).
Source lives in the repo's `eclipse/` folder.

## About this document

- **Kind:** `doc` / install & run guide for the `eclipse/` plugin.
- **Read by:** anyone building or running the plugin (or the `tasks`/`fleet`
  stdio launchers); **written by:** maintainers.
- **Related:** `README.md` (bundle map), `ARCHITECTURE.md` (layer rules),
  `QUALITY.md` (the quality gate), `DISTRIBUTED-FLEETS.md` (multi-machine stores).

## Prerequisites

- **opencode** installed and on PATH (`opencode --version` → 2.x). Pinned and endpoint-verified against 2.0.19 (see `ServerVersionPin`; last live cross-check 2026-09-29).
- A JDK 21+ on the machine (the `build.ps1` wrapper auto-detects one;
  `JAVA_HOME` does not have to be valid).
- **PowerShell 7 (`pwsh`) on PATH** — required by the build itself (the
  `tools`/`tasks` Eclipse-import-ban scan runs through it) and by the
  `tasks`/`fleet` stdio MCP launchers. Install: `winget install Microsoft.PowerShell`.
- **Node.js on PATH** — only needed for the chat web renderer/bridge checks that run inside
  `mvn verify` (skip with `-DskipNodeChecks=true` if you just want jars).
- **WebView2 Runtime** (preinstalled on Windows 10/11) for the Chat view.
- **Toolchains (all optional — auto-detected, agents get install hints for anything missing):**
  - **MSYS2** at `C:\msys64` with one or more of `clang64` / `mingw64` / `ucrt64`
    (each env should have `cmake` + `ninja`; `clang-tidy`/`clang-format` in clang64/mingw64).
  - **MSVC** — Visual Studio (2022/2026) is found via `vswhere`; builds use CMake's
    Visual Studio generator (no developer prompt needed).
  - **Cppcheck** (standalone install, e.g. `C:\Program Files\Cppcheck`) for `lint_run`.
  - **gdb** — *not present on the reference machine*; `debug_batch` reports an install hint
    (`pacman -S gdb` in the MSYS2 env) until installed.

## Build (always)

Full reactor (heavy; also builds the p2 site):

```powershell
cd eclipse   # from the repo root
.\build.ps1 clean verify
```

Scoped build during iteration (repeat `-pl`, never commas; adjust to the modules you touched):

```powershell
.\build.ps1 -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.client.tests `
            -pl bundles/com.opencode.ide.core -pl bundles/com.opencode.ide.core.tests `
            -pl bundles/com.opencode.ide.ui -pl bundles/com.opencode.ide.ui.tests `
            -pl bundles/com.opencode.ide.chat -pl bundles/com.opencode.ide.chat.tests `
            -pl bundles/com.opencode.ide.cdt -pl bundles/com.opencode.ide.cdt.tests `
            -pl bundles/com.opencode.ide.git -pl bundles/com.opencode.ide.git.tests `
            -pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.fleet.tests `
            -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.tools.tests `
            -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tasks.tests `
            -pl bundles/com.opencode.ide.board -pl bundles/com.opencode.ide.board.tests `
            -pl bundles/com.opencode.ide.mcp -pl bundles/com.opencode.ide.mcp.tests clean verify
```

Both run the Java test suites (1913 `@Test` methods across the 11 test
fragments, plus 16 in the `opencode-tasks` mojo module); `verify` also runs
the 260 Node checks (58 renderer + 194 bridge + 8 mermaid against
`components/chat-web`) when Node is available (`-DskipNodeChecks=true` to skip).
Produces plugin JARs in
`bundles\<name>\target\` and (full build) a p2 update site in
`releng\com.opencode.ide.repository\target\repository\`.

---

## Three ways to run the plugin

### Option A — `dropins/` (fastest, no setup) ★ for iterating

Copies the freshly built plugin JARs straight into the Eclipse dropins folder.

```powershell
.\build.ps1 clean verify
.\deploy-dev.ps1            # copies the 11 JARs to <eclipse-install>\dropins\opencode-ide\plugins\
```

Then **(re)start Eclipse CDT** and open the **OpenCode** perspective.

Besides the jars, `deploy-dev.ps1` also wires the harness defaults once per
install (each edit is skipped when its flag is already present):

- it copies `plugin_customization.ini` next to `eclipse.exe` and adds
  `-pluginCustomization plugin_customization.ini` to `eclipse.ini`. That file
  seeds a **fresh workspace** with the Board's default project — it carries
  no paths (B-019): machine paths never ship; every value stays overridable
  in Preferences.
- it adds `-Dopencode.repo=<repo>` to the `-vmargs` block, pinned to the repo
  the script ran from. This pin is the **one authoritative repo path**: when
  set it wins over the working-directory / tasks-root preferences (an open
  CDT project still wins for the spawn working directory), so a deploy to a
  different clone path needs no hand edits. Without the pin the task store
  resolves from the workspace itself (climb / repo adoption / preference —
  see `TasksRootResolution`), and the spawn working directory derives from
  the workspace/project context.

- Re-run both lines after any code change, then restart Eclipse.
- If the perspective/views don't refresh after a change, run Eclipse once with `-clean`
  (add a line `-clean` near the top of `<eclipse-install>\eclipse.ini`, start once, remove it).
- **Undo:** delete `<eclipse-install>\dropins\opencode-ide\`, remove the
  `-pluginCustomization` and `-Dopencode.repo=…` lines from `eclipse.ini`,
  delete the copied `plugin_customization.ini`, and restart — plugin and
  defaults are gone.
- No debugging/breakpoints with this route.

### Option B — p2 install (stable / "production")

Install the built p2 repository into Eclipse once:

1. In Eclipse CDT: **Help → Install New Software…**
2. **Add…** → **Local** → select
   `<repo>/eclipse/releng/com.opencode.ide.repository/target/repository`
3. Select **OpenCode IDE**, finish, restart.

To update after a rebuild: Help → **Installation Details** → uninstall, then reinstall,
or just re-run the repository and it will offer an update. For frequent changes prefer Option A.

### Option C — PDE runtime launch (dev + debugging)

The proper Eclipse dev workflow. **PDE is not installed in `eclipse-cpp` today**, so it's a
one-time setup:

1. *Help → Install New Software → 2026-06 repo* (`https://download.eclipse.org/releases/2026-06/`)
   → install:
   - **Eclipse Plugin Development Environment**
   - **Eclipse Java Development Tools**
   - **Maven Integration for Eclipse (m2e)** (+ the m2e Tycho/PDE connector if prompted)
2. Restart Eclipse.
3. *File → Import → Maven → Existing Maven Projects* → select the repo's `eclipse` folder.
4. **Run → Debug As → Eclipse Application** — launches a 2nd Eclipse with your workspace
   plugins live. Set breakpoints in `ServerView`, `ProvidersView`, `HttpOpencodeClient`, etc.
   Relaunch picks up code changes; no restart/copy needed.

---

## Using the plugin

1. **Connection** — the default is **SPAWN with *Attach to the shared opencode
   service (v2)* on**: the plugin joins the per-user background service every
   v2 client (TUI, CLI) shares, and falls back to spawning a private
   `opencode serve` child process when the service cannot be discovered or
   started (binary resolved from the preference or PATH; process tree killed
   on stop; the port is **dynamic** — the preference page has no port field,
   only the bind hostname). Alternatively set **Mode = CONNECT** in
   **Window → Preferences → OpenCode** and start the server yourself:
   ```
   opencode serve --hostname 127.0.0.1 --port 4096
   ```
   (If you set `OPENCODE_SERVER_PASSWORD`, also enter it in the preference page.)
2. **Window → Perspective → Open Perspective → Other… → OpenCode** — the chat
   fills the right side, every other view is one tab in the left stack.
3. The **Server** view (left) shows one root per connection (the primary plus any remote
    connections configured in the preferences) with **Agents**, **Sessions** (subagents
    nested, thinking/running-tool indicators), **Active files**, **Working set** (the
    project's changed files with per-status counts), **MCP servers** and
    **Skills** categories (virtualized for scale). The **Providers** view
    lists all models with filter + column sorting (virtualized; provider logos with
    letter-badge fallback). The **Repo** view is a lazy file tree plus fuzzy
    file/symbol/text search over the server's file endpoints; the **Background** view is
    the "what is going on" cockpit (every session and subagent with live activity, shells
    with their output, pending permission asks answerable once/always/reject). A session's
    context menu offers **Session details** — a per-session transcript view (messages,
    reasoning, tool lines, tokens/cost) that refreshes live over SSE; double-clicking a
    session resumes it in a chat window. Use the views' **Refresh** action to re-query.
4. The **Chat** view (right) is a native markdown chat: pick an agent + model (+ **variant** for
    models that expose them, e.g. `high`/`thinking`; `(default)` omits it), type a prompt
    (**ENTER** sends, **Shift+ENTER** = newline). Replies render markdown, **LaTeX math**
    (`$x^2$`, `$$…$$`), **mermaid diagrams**, and **syntax-highlighted code** (c/cpp/cmake/…)
    with streaming text while the model works; tool invocations render as compact
    `tool: name — state` lines and every code fence carries a **Copy** button. Toolbar:
    **New Session**, **Abort** (stops an in-flight reply; also Ctrl+Alt+Shift+A; new chat
    window Ctrl+Alt+Shift+N). Double-clicking a model in Providers or a session in
    the Server view opens a chat window pre-set to it / resuming it.
    - Requires **WebView2**; the view shows a hint if unavailable.
    - The plugin tells the model what the view can render (markdown, LaTeX math, mermaid,
      highlighted code fences) via a per-request system prompt
      (`ChatCapabilities.RENDERER_SYSTEM_PROMPT`), **on by default**; there is no
      preference-page toggle for it yet (U-055).
5. The **Board** view (PM kanban over the repo's `.opencode/tasks/` store: six status
    columns — *paused* included — or the ten V-stage pipeline columns, sprint selector
    + goal, blocked flags, ticket details with artifact links, live refresh) and the
    **Fleet** view (a tree of engine → wave → job (ticket) → worker session → subagents →
    console/shell tasks, with per-job diff/folder/watch actions; jobs launched by peer
    engines group under their own root; *Take over* opens the worktree and marks the job
    taken over — v2 has no TUI steering channel any more) drive the headless fleet: select
    a sprint-backlog/in-progress ticket and **Launch task** to run it in an isolated git
    worktree with a role-mapped agent (merge-back and ticket bookkeeping are automatic).
6. If the server URL or credentials differ, set them in
    **Window → Preferences → OpenCode** — primary connection (mode; SPAWN: the attach
    checkbox, opencode binary, hostname, working directory — no port; CONNECT: server URL,
    username, password) plus the **Defaults** group (chat model `provider/model` + variant —
    default `zai-coding-plan/glm-5.3` with `max`; task-store root + Board project — blank
    root = derived from the workspace (the Board walks up looking for `.opencode/tasks`),
    project default `hephaestus`; remote-connections list with passwords in secure storage) —
    then hit **Refresh**.
7. On startup the plugin also starts a local **MCP endpoint** for agents
   (log line: `eclipse-build MCP listening on http://127.0.0.1:<port>/mcp`) exposing
   cmake build/test, run, gdb-batch debug, clang-tidy/cppcheck lint and clang-format tools
   across the detected toolchains (MSVC + MSYS2 clang64/mingw64/ucrt64), **plus the
   `task_*` tool pack** (the same 22 tools the `tasks` stdio server serves).

## Connection modes at a glance

| Mode | Where the server comes from | Preference fields |
|---|---|---|
| **SPAWN** (default) | Plugin joins the shared v2 background service, or spawns/owns `opencode serve` | **Attach to the shared service** checkbox (default on), (optional) opencode binary, hostname, Password, **working directory** (the repo whose `.opencode/` agents/skills/MCP config load; blank = derived from the workspace — an open CDT project still wins). No port field: the port is dynamic |
| **CONNECT** | You start `opencode serve` | Server URL, Username, Password |

> In SPAWN mode the server runs in the configured working directory, so the **Hephaestus
> harness itself is what the plugin hosts**: its agents, skills and MCP servers (visible in
> the Server view) are the repo's `.opencode/` configuration.

## Troubleshooting

- **`tasks`/`fleet` MCP servers fail with `Connection closed` (opencode TUI)** → the stdio
  launchers (`eclipse/tasks-tools.ps1` / `fleet-tools.ps1`) died at spawn. They are **pwsh**
  scripts (install PowerShell 7) needing a JDK 21+ and the **built bundle jars** — run one
  `.\build.ps1 clean verify` first (the `tasks` launcher resolves tasks+tools, the `fleet`
  launcher fleet+client+git+tasks+tools; gson comes from the Tycho p2 cache `~/.m2` or
  `$env:ECLIPSE_HOME`; a launcher spawned from a fleet *worktree* falls back to the main
  checkout's jars — worktrees carry no `target/` output). Then reconnect —
  the MCP servers belong to the shared background service, so a TUI restart keeps the
  stale state: `POST /api/experimental/mcp/<name>/connect?location[directory]=<repo>`
  (Basic auth; password in `~/.local/state/opencode/service.json`), or restart the service.
- **Perspective not visible** after a dropins/p2 update → start Eclipse once with `-clean`.
- **Views show "Error: …"** → check the server is reachable:
  `opencode api get /api/info` (answers with `version`/`pid`/`urls` — reachability *is* health in v2;
  it also prints the server's actual base URL, since the port is dynamic).
- **Spawn mode: "opencode binary not found"** → set the binary path in Preferences → OpenCode
  (e.g. `C:\Users\<you>\AppData\Roaming\npm\node_modules\opencode-ai\bin\opencode.exe`).
- **Chat renders blank / a feature (math, mermaid) stops working** → the chat page reports every
  render and JS error to the Eclipse log as `[chat-page] …` messages — check the **Error Log**
  view (*Window → Show View → Error Log*) or the workspace `.metadata\.log`; those markers say
  exactly which bridge call or renderer stage failed.
- Errors are logged to the **Error Log** view (*Window → Show View → Error Log*).

## Uninstall

- **dropins:** delete `<eclipse-install>\dropins\opencode-ide\` and restart.
- **p2:** Help → Installation Details → select **OpenCode IDE** → Uninstall.
