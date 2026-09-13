# OpenCode IDE plugin — install & run guide

Eclipse plugin integrating [opencode](https://opencode.ai) into the Eclipse CDT IDE
(`<eclipse-install>` — default `C:\eclipse-cpp`; the deploy scripts accept an
`-EclipseRoot` argument or the `ECLIPSE_HOME` env var; Eclipse 4.40 / Java 21 / CDT 12.5).
Source lives in the repo's `eclipse/` folder.

## Prerequisites

- **opencode** installed and on PATH (`opencode --version` → 1.18.x). Pinned and endpoint-verified against 1.18.21 (stable through 1.18.30 — see `ServerVersionPin`).
- A JDK 17+ on the machine (the `build.ps1` wrapper auto-detects one;
  `JAVA_HOME` does not have to be valid).
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

Both run the 1048 Java tests (plus 16 in the `opencode-tasks` mojo module); `verify` also runs
the 153 Node checks (51 renderer + 94 bridge + 8 mermaid against `components/chat-web`) when
Node is available (`-DskipNodeChecks=true` to skip). Produces plugin JARs in
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

- Re-run both lines after any code change, then restart Eclipse.
- If the perspective/views don't refresh after a change, run Eclipse once with `-clean`
  (add a line `-clean` near the top of `<eclipse-install>\eclipse.ini`, start once, remove it).
- **Undo:** delete `<eclipse-install>\dropins\opencode-ide\` and restart — plugin is gone.
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

1. **Start an opencode server** (one of):
   - *Connect mode* (default): in a terminal run
     ```
     opencode serve --hostname 127.0.0.1 --port 4096
     ```
     (If you set `OPENCODE_SERVER_PASSWORD`, also enter it in the preference page below.)
   - *Spawn mode*: in **Window → Preferences → OpenCode** set **Mode = SPAWN**. The plugin
     starts and manages an `opencode serve` child process itself (resolves the binary from
     the preference or PATH; kills the process tree on stop).
2. **Window → Perspective → Open Perspective → Other… → OpenCode**.
3. The **Server** view (left) shows one root per connection (the primary plus any remote
    connections configured in the preferences) with **Agents**, **Sessions** (subagents
    nested, thinking/running-tool indicators), **Active files**, **MCP servers** and
    **Skills** categories (virtualized for scale). The **Providers** view
    (bottom) lists all models with filter + column sorting (virtualized; provider logos with
    letter-badge fallback). Use the views' **Refresh** action to re-query. A session's context
    menu offers **Session details** — a per-session transcript view (messages, reasoning,
    tool lines, tokens/cost) that refreshes live over SSE; double-clicking a session resumes
    it in a chat window.
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
    - The plugin tells the model what the view can render (markdown/math/code fences) via a
      per-request system prompt — toggle in *Preferences → OpenCode → Advertise rendering*.
5. The **Board** view (PM kanban over the repo's `.opencode/tasks/` store: five status
    columns, sprint selector + goal, blocked flags, ticket details with artifact links,
    live refresh) and the **Fleet** view (launched fleet jobs: task → session → worktree →
    state, per-job diff/folder/take-over) drive the headless fleet: select a
    sprint-backlog/in-progress ticket and **Launch task** to run it in an isolated git
    worktree with a role-mapped agent (merge-back and ticket bookkeeping are automatic).
6. If the server URL or credentials differ, set them in
    **Window → Preferences → OpenCode** — primary connection incl. spawn settings and
    working directory, plus the **Defaults** group (chat model `provider/model` + variant —
    default `zai-coding-plan/glm-5.3` with `max`; task-store root + Board project — default
    this repo and `hephaestus`; remote-connections list with passwords in secure storage) —
    then hit **Refresh**.
6. On startup the plugin also starts a local **MCP endpoint** for agents
   (log line: `eclipse-build MCP listening on http://127.0.0.1:<port>/mcp`) exposing
   cmake build/test, run, gdb-batch debug, clang-tidy/cppcheck lint and clang-format tools
   across the detected toolchains (MSVC + MSYS2 clang64/mingw64/ucrt64).

## Connection modes at a glance

| Mode | Where the server comes from | Preference fields |
|---|---|---|
| **CONNECT** (default) | You start `opencode serve` | Server URL, Username, Password |
| **SPAWN** | Plugin starts/owns `opencode serve` | (optional) opencode binary, hostname, port, Password, **working directory** (the repo whose `.opencode/` agents/skills/MCP config load; default this repository — an open CDT project still wins) |

> In SPAWN mode the server runs in the configured working directory, so the **Hephaestus
> harness itself is what the plugin hosts**: its agents, skills and MCP servers (visible in
> the Server view) are the repo's `.opencode/` configuration.

## Troubleshooting

- **Perspective not visible** after a dropins/p2 update → start Eclipse once with `-clean`.
- **Views show "Error: …"** → check the server is reachable:
  `Invoke-RestMethod http://127.0.0.1:4096/global/health` (should report `healthy=true`).
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
