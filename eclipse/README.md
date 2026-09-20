# opencode-eclipse

An **Eclipse-based agentic C++ harness**: [opencode](https://opencode.ai) agents create, build,
test, lint, format and debug C/C++ projects headless (CMake-first, multi-toolchain: MSVC +
MSYS2 clang64/mingw64/ucrt64), isolated in git worktrees, while the human uses Eclipse CDT for
overview and takeover. Built with Maven Tycho against Eclipse Platform 4.40 (SimRel 2026-06),
Java 21, CDT 12.5. The tool layer is a language-agnostic SPI — Python/other packs plug in later.

## Architecture — strictly separated OSGi bundles

**Four separation axes govern every module** (full rationale + migration plan in
[`ARCHITECTURE.md`](ARCHITECTURE.md)): **Eclipse vs. non-Eclipse** (pure Java stays reusable
outside OSGi), **Java vs. non-Java** (the `web/` chat renderer is a standalone component with a
versioned bridge contract), **dev-environment packs** (C++ today, Python etc. later — behind the
`ToolProvider` SPI), and **coding-agent backend** (opencode today; the client layer is the seam
for a second backend, extracted when one actually arrives).

| Bundle | Layer | Depends on | Purpose |
|---|---|---|---|
| `com.opencode.ide.client` | **opencode** | `com.google.gson` only — **Eclipse-free, build-enforced** | Pure-Java opencode HTTP client, DTOs (records mirroring the server's OpenAPI), `ChatRequests`/`McpRequests`, SSE parsing + event stream, server launcher, `ClientLog` seam. Reusable as a plain library outside Eclipse/OSGi. |
| `com.opencode.ide.core` | **eclipse adapter** | `client` + `mcp` + Eclipse runtime + equinox.security | Eclipse glue: preferences (secure remote credentials), activator (installs the Eclipse `ClientLog` adapter, bridges the tasksRoot preference into the MCP endpoint), `ProjectContext` service tracking, `OpencodeConnection` + `ConnectionsManager` (plural connections) lifecycle. |
| `com.opencode.ide.ui` | **eclipse** | `core` + `client` + `org.eclipse.ui`/`jface`/`swt` | The **OpenCode** perspective, the Server/Providers/**Repo** views, the connection preference page. Server view: project/VCS header (dirty + cwd-mismatch warning) + *Working set* category + agent-nested live sessions (double-click = transcript, **live busy icons** refreshed from the server's session-status map); Session details carries Fork/Summarize lifecycle actions plus **Open message/transcript in Editor** (snapshot copy) and the server rows offer an **MCP servers…** dialog; Providers view shows auth state + *Connect…* (OAuth URL); Repo view = lazy file tree + fuzzy file/`@`symbol/`/`text search over the opencode endpoints. |
| `com.opencode.ide.chat` | **chat ui** | `core` + `client` + SWT `Browser` (no `ui` dependency) | Eclipse **host** for the chat-web component: `ChatPage` (browser facade) + `ChatSessionController` (SWT-free) + embedded `ChatWebServer` serving the component's assets. Composer carries the `/command` picker (`CommandComposer`); exported `ChatPermissionSink` seam routes chat-session permission asks into the shared queue. |
| `components/chat-web` | **non-Java** | — (static assets + node checks) | The standalone chat renderer (markdown + KaTeX + highlight.js + mermaid) with a documented bridge contract — hostable in any environment that serves files and calls JS. |
| `com.opencode.ide.git` | **agentic git** | — (git CLI) — Eclipse-free | `WorktreeManager`: branch + worktree per agent task (under `.git/opencode-fleet/`), serial merge-back with clean conflict abort. Fleet isolation layer. `StoreGitStatus`/`StoreSync` = distributed-fleet store discipline (status summary; commit → pull-rebase → push with recover — see [`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md)). |
| `com.opencode.ide.fleet` | **fleet engine** | `client` + `git` + `tasks` + gson — **Eclipse-free, build-enforced** | `FleetRunner`: the headless loop — `begin` (worktree + directory-scoped session + optional `/shell` `Bootstrap`, prompt POST on its own thread) → watchdog probe (busy/messages/complete; stall = idle-and-silent only, aborted; budget-timeout aborts) → guarded merge back (auto-commits worker changes, refuses empty results, tolerates peer store writes; repo-gated via `RepoGate` — serialized against every other engine's git mutations). `TaskFleet` adds the V-pipeline launch loop (pre-claim, scoped to the store subtree → stage-mapped agent → merge → actuals); failed runs RELEASE their claim (sprint-backlog + blocked-with-reason — never a zombie in-progress); the headless `FleetControl` auto-syncs the store after every launch. `FleetTuning` is the engine's knob table (stall timeout, poll interval, budgets). `PermissionQueue` + `FleetPermissionBridge` buffer pending `permission.asked` requests for unattended runs (the watchdog's stall clock pauses while asks are pending); `GlobalEventsAggregator` merges `/api/event` streams across connections. **Chat-first control** (H7): `FleetControl`/`FleetToolProvider`/`FleetStdioMain` expose the `fleet_*` tool pack over stdio via `eclipse/fleet-tools.ps1` — `fleet_dispatch`, `fleet_jobs`, `fleet_job_details` (live progress), `fleet_permissions`(+`_answer`), `fleet_sync_store`, `fleet_status_store`, `fleet_recover_store`, `fleet_reset` (consume residue: worktree+branch removal + ticket release). Chat is the primary interface; the Board buttons are conveniences. **V-006 daemon (opt-in)**: `FleetDaemon` (authed TCP core over `McpDispatcher`, pidfile singleton in `.git/opencode-fleet/daemon.json`, graceful drain) + `FleetDaemonProxy`/`DaemonProwl` stdio attach (`FLEET_DAEMON=off/auto/always`, default `off`) + the detached `fleet-daemon.ps1` launcher — the engine outlives any client session; disconnecting never kills runs. |
| `com.opencode.ide.tools` | **agent tools** | `com.google.gson` only — **Eclipse-free, build-enforced** | **`ToolProvider` SPI** + JSON-RPC dispatch + the built-in C++ tool pack (`tools.cpp`: toolchains, build, lint, format). Future language packs = new providers depending on this bundle only. |
| `com.opencode.ide.tasks` | **task board** | `tools` + gson — **Eclipse-free, build-enforced** | The **task store** (`.opencode/tasks/<project>/`, one Markdown file per ticket) + the **`task_*` tool pack** (create/claim/release/sprint/traceability/readiness/doctor/invalidations; replaces the retired Python pm MCP server) + `StageReadiness` (the pure dataflow-readiness function behind H6 auto-dispatch and `task_readiness`). Also ships `TasksStdioMain` — the same tools over stdio via `eclipse/tasks-tools.ps1` for TUI-only sessions. |
| `com.opencode.ide.board` | **board ui** | `core` + `client` + `tasks` + `fleet` + `git` + `chat` + Eclipse UI | **PM Board view** (kanban over the task store: 5 columns, sprint selector + goal, blocked flags, artifact links with markdown/diagram rendering, *Launch task* → `TaskFleet` via `TaskFleetLauncher`, *Take over*, **Cost overview** dialog + `• $X spent` header suffix aggregating the `fleet actuals:` comments) + **Fleet view** (jobs = task → session → worktree → state, per-job **server diff** (`/session/:id/diff`) with local-git fallback, folder/takeover, **Permissions (n)** dialog — approve once/always/reject on pending `permission.asked` requests). SWT-free model (`BoardModel`, `TaskStoreWatcher`, `FleetJobsModel`, `CostOverview`, `DiffSource`/`SessionDiffText`, `FleetPermissions`) is unit-tested. |
| `mojo/opencode-tasks` | **maven plugin** | the `tasks` bundle store classes (plain jar dep) | **`opencode-tasks:sync`** (validate/normalize `.opencode/tasks/`: schema lint, id/counter consistency, LF; `-Dopencode.tasks.fix=true` applies safe fixes) and **`opencode-tasks:plan`** (render the sprint board as Markdown + standalone HTML into `target/opencode-tasks/`). Maven plans, CMake builds — never invokes a compiler. |
| `com.opencode.ide.mcp` | **agent endpoint** | `tools` + `tasks` + gson | Local **MCP server** (stateless Streamable HTTP on 127.0.0.1, **per-start token auth** — the registered `?token=` URL or a `Bearer` header; 401 otherwise, G-003): OSGi DS lifecycle + HTTP endpoint only; tool implementations live in `tools`/`tasks`. Service-driven activation — the endpoint comes up when core binds it, after the tasksRoot preference was bridged. |
| `com.opencode.ide.cdt` | **C++/CDT** | `core` + CDT bundles | Implements the `ProjectContext` seam (`CdtProjectContext`: active `ICProject` → spawn cwd) + `DiagnosticsMarkers`/`MarkerApplier` (opencode diagnostics → CDT markers). First cut landed. |

Dependency rules (enforced in the manifests, plus build-time Eclipse-import bans in
`client` and `tools`): **client and tools are Eclipse-free**; core/ui/cdt/chat depend on them;
`client` never depends on the others. The core/UI bundles talk to the CDT layer only through the
`ProjectContext` interface (defined in core's context package), implemented as an OSGi service in
the cdt bundle. `git`, `tools` and `components/chat-web` have no Eclipse dependencies at all —
agents run headless; the IDE is the human's overview + takeover surface. See
[`ARCHITECTURE.md`](ARCHITECTURE.md) for the four separation axes and the review checklist,
and [`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md) for running one shared board across
several machines (store git status + *Sync store* in the Board view).

```
opencode-eclipse/
├── build.ps1                      # thin wrapper: resolves a JDK, then runs the Maven Wrapper
├── deploy-dev.ps1                 # copies the 11 built JARs to <eclipse-install>\dropins\opencode-ide\plugins\
├── mvnw.cmd / mvnw / .mvn/        # Maven Wrapper (Maven 3.9.9) — no system Maven needed
├── pom.xml                        # Tycho 5.0.3 reactor parent
├── releng/opencode-eclipse.target # target platform (2026-06 repo)
├── components/
│   └── chat-web/                  # standalone chat renderer (web assets + node checks + README)
├── bundles/
│   ├── com.opencode.ide.client    # + client.tests — pure-Java opencode client (Eclipse-free)
│   ├── com.opencode.ide.core      # Eclipse adapter (preferences, activator, connection)
│   ├── com.opencode.ide.ui         # + ui.tests — views, perspective, session details
│   ├── com.opencode.ide.chat       # + chat.tests; consumes components/chat-web at build time
│   ├── com.opencode.ide.git        # + git.tests (worktree fleet isolation, Eclipse-free)
│   ├── com.opencode.ide.fleet      # + fleet.tests — headless fleet engine incl. TaskFleet (Eclipse-free)
│   ├── com.opencode.ide.tools      # + tools.tests — ToolProvider SPI + C++ pack (Eclipse-free)
│   ├── com.opencode.ide.tasks      # + tasks.tests — task store + task_* tool pack (Eclipse-free)
│   ├── com.opencode.ide.board      # + board.tests — PM Board + Fleet views (Eclipse UI; SWT-free model)
│   ├── com.opencode.ide.mcp        # + mcp.tests — MCP HTTP endpoint + DS lifecycle
│   └── com.opencode.ide.cdt        # + cdt.tests — CDT ProjectContext + markers bridge
├── mojo/opencode-tasks            # plain maven-plugin: opencode-tasks:sync / :plan over the store
├── features/com.opencode.ide.feature
└── releng/com.opencode.ide.repository   # p2 update site
```

## Development rules (conventions)

Apply these to every change so the plugin stays consistent:

- **Document the why, not the what.** Every public type gets a brief javadoc:
  purpose, contract/invariants, and its seam (who implements/calls it — this is
  what AI agents navigating the repo rely on). Method javadoc only where the
  signature isn't self-explanatory (validation rules, thread/lifecycle
  expectations, wire shapes). No noise comments, no change logs in code —
  history lives in git and the task store.
- **Views are always closeable + detachable.** Add views with `IPageLayout.addView(...)`
  or `IFolderLayout.addView(...)` — **never** `addStandaloneView(viewId, false, ...)`.
  A `showTitle=false` standalone view has no title bar, so it can't be closed, moved, or
  detached. Regular views come with a title bar (close **X**, drag to detach/float, dockable)
  and can be reopened via *Window → Show View*.
- **Never pass a tree element as the TreeViewer input.** `setInput(node)` whose `getElements`
  returns `[node]` (input == element) destabilizes the TreeViewer (we saw it render the root
  infinitely). Pass a wrapper, e.g. `setInput(List.of(node))`, and resolve the node back in
  `inputChanged`.
- **Dev rule (browser views):** never serve a bundled web page via `FileLocator.toFileURL` —
  jar'd bundles extract single files, so relative assets 404 and the page dies (the blank-chat bug).
  Use the embedded `ChatWebServer` (localhost HTTP) instead; it is component-tested.
- **Dev rule (Java→JS bridge):** every Java→JS call goes through **`ChatScripts`** and passes data
  as a **JSON *string* literal** (never a JS object literal) — the page's `payload()` accepts both
  and `guard()` reports errors. This matters because **`Browser.execute()` on the Edge backend
  returns `true` even when the script throws**, so a contract mismatch fails *silently*. The page
  also reports `page-ready` and every render via `__javaReport` — live verification means reading
  those log markers, never trusting `execute()`'s return value (`bridge-check.mjs` *executes* the
  real `chat.js` against a DOM shim to keep this honest).
- **Dev rule (math before markdown):** KaTeX math is extracted into `\uE000…\uE001` markers and
  rendered *before* markdown runs — auto-render after markdown breaks on multi-line `$$…$$`
  (`breaks:true` inserts `<br>` between the delimiters), on `\(...\)`/`\[...\]` (markdown eats the
  backslashes) and on `\\` (collapses). Currency (`$5`) and code fences are excluded.
- **Dev rule (PowerShell):** never patch source files with PowerShell regex replacements and never
  inline JS in `node -e` — `$ref`, `$5` and `\$` get mangled. Use the edit tool for files and write
  probe scripts to a real `.mjs`/`.cjs` file for Node.
- **Dev rule (tool providers):** agent tools live behind the mcp bundle's `ToolProvider` SPI
  (`language()` + `tools()` + `call()`); C++ specifics stay in `CppToolProvider`. New language
  packs = new providers, never edits to the dispatcher. Toolchains are *detected, never assumed*
  (MSVC via vswhere; MSYS2 envs by probing `C:\msys64\<env>\bin`) and every optional binary
  reports an install hint when absent.
- **Process rule (clean architecture):** every phase lands with tests; every second session
  starts by clearing the refactor backlog (ROADMAP "Recover next" §5 keeps the list).
- **Project-root resources (`plugin.xml`, `OSGI-INF/*`) are NOT auto-packaged** by Tycho 5/bnd —
  put them under `src/main/resources/` so the resources plugin copies them into the jar.
- **Three-layer separation.** `core` (opencode, no UI/CDT) → `ui` (eclipse, depends on core) →
  `cdt` (CDT, depends on core). Core/UI never import CDT; cross-layer talk goes through the
  `ProjectContext` service defined in core.
- **Keep the build light during iteration.** Build only what changed:
  `.\build.ps1 -pl bundles/com.opencode.ide.core -pl bundles/com.opencode.ide.ui clean package`
  (note: `ui` needs `core` in the reactor to resolve its `Require-Bundle`). Avoid repeated
  full-reactor `clean verify` unless you need the tests + p2 repo. Use `-pl a -pl b`
  (repeated), not `-pl a,b` (comma is an arg separator under `cmd.exe`).
- **dropins dev deploy uses the `plugins/` layout:** `<eclipse-install>\dropins\opencode-ide\plugins\*.jar`
  (a folder of loose JARs is rejected by p2 with "No repository found"). `deploy-dev.ps1` handles this.
- **Eclipse must be closed** before `deploy-dev.ps1` (the bundle jar is locked while Eclipse runs).
- **opencode v2 DTO contract.** `Agent.Info` carries `id`, `name`, `mode`, `hidden` and
  `permissions` — there is no `native` **and** no `builtIn` field (v1.18.x had `native`);
  `Provider.models` is a map keyed by model id. Every path is prefixed **`/api`**, list
  endpoints wrap their rows in `{data:[…]}`, and the server requires HTTP Basic auth.
  Re-validate the records against a live server on every opencode upgrade.
- **v2 connection model (2026-09-20).** The local/primary connection now prefers
  **attaching to the shared background service** every v2 client (TUI, CLI) uses:
  `OpencodeServiceDiscovery` reads the registration file
  (`~/.local/state/opencode/service.json`), probes `/api/info`, and starts
  `opencode serve --service` when absent — with the private-spawn path as fallback
  (preference *Attach to the shared opencode service (v2)*, default ON). Two v2
  realities shape the views: **session state is global per user** (every server
  lists every session), so the Server view scopes its list via
  `GET /session?directory=…`; **event streams stay per-process**, which is why the
  **fleet deliberately keeps its own spawned server** (a quiet stream, a private
  password, a killable budget). Chat is async in v2: `POST /session/:id/prompt`
  (+`/agent` `/model` `/synthetic`) then poll `GET …/message` until
  `time.completed`; shell commands follow the same split.
- **Server readiness ≠ health.** The spawn launcher must wait for `/api/info` **and** a data
  endpoint (`/api/agent`) before returning — `/api/info` answers before the data endpoints are
  populated. Views retry on failure as insurance.

## Build

**Fresh-machine checklist** (everything below is auto-resolved or overridable —
nothing in the repo pins an install path):

1. JDK 21+ (the `build.ps1` wrapper auto-detects one via the registry and
   common install dirs; a broken `JAVA_HOME` is tolerated) — Java 21 used in CI.
2. **Node.js on PATH** for the web renderer/bridge checks (or pass
   `-DskipNodeChecks=true`).
3. An Eclipse CDT install for deploying (default `C:\eclipse-cpp`; override
   with `-EclipseRoot` / `ECLIPSE_HOME`).
4. Optional, only for `cpp/` toolchain presets: LLVM clang + Ninja (default),
   MSYS2 `clang64`/`mingw64` shells, Visual Studio, or WSL (`cmake`+`ninja`+`gcc`).

Requirements: a JDK 21+ on the machine (the `build.ps1` wrapper auto-detects one;
the system `JAVA_HOME` does not have to be valid) and **Node.js on PATH** for the web
renderer/bridge checks (or pass `-DskipNodeChecks=true`).

Full reactor build (heavy — compiles everything, runs all tests, assembles the p2 site):

```powershell
cd eclipse   # from the repo root
.\build.ps1 clean verify
```

Scoped build (use this during iteration; repeat `-pl`, never commas — adjust the module list to
what you touched; add siblings like `core`+`client` when manifests require them):

```powershell
.\build.ps1 -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.client.tests `
            -pl bundles/com.opencode.ide.core -pl bundles/com.opencode.ide.core.tests `
            -pl bundles/com.opencode.ide.ui -pl bundles/com.opencode.ide.chat `
            -pl bundles/com.opencode.ide.chat.tests -pl bundles/com.opencode.ide.cdt `
            -pl bundles/com.opencode.ide.git -pl bundles/com.opencode.ide.git.tests `
            -pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.fleet.tests `
            -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.tools.tests `
            -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tasks.tests `
            -pl bundles/com.opencode.ide.board -pl bundles/com.opencode.ide.board.tests `
            -pl bundles/com.opencode.ide.mcp -pl bundles/com.opencode.ide.mcp.tests clean verify
.\deploy-dev.ps1     # close Eclipse first (jars are locked while it runs); copies all 11 jars
```

This runs **1048 Java tests** (34 core + 159 client + 75 chat + 41 git + 176 fleet + 23 tools +
144 tasks + 205 board + 24 cdt + 159 ui + 8 mcp, plus 16 in the `opencode-tasks` mojo module —
the mcp suite includes a real ucrt64 compile-and-run E2E test and the
endpoint↔task-store wiring; the tasks suite includes a cross-process claim race against a
spawned stdio JVM; the cdt suite drives a real headless workspace for marker application) plus
the three Node checks against `components/chat-web` (`renderer-check.mjs` 51, `bridge-check.mjs`
94, `mermaid-check.mjs` 8 — the last renders diagrams in **real headless Edge** and SKIPs
where Edge/puppeteer-core are absent — wired into `mvn verify` via `exec-maven-plugin`; the
client, tools, fleet and tasks bundles additionally fail the build on any
`org.eclipse.*`/`org.osgi.*` import). The full reactor additionally assembles the p2 update
site at `releng/com.opencode.ide.repository/target/repository/`.

## Install into Eclipse CDT

1. Build (above).
2. In Eclipse CDT (`<eclipse-install>`, default `C:\eclipse-cpp`): **Help → Install New Software…**
3. **Add…** a local repository pointing at:
   `<repo>/eclipse/releng/com.opencode.ide.repository/target/repository`
4. Select the **OpenCode IDE** feature, finish, restart.

## Use the first feature (query providers & agents)

1. Start an opencode server in a terminal — or, on v2, simply have any opencode
   client running: the plugin **attaches to the shared background service** by
   default and starts it when missing (it can also spawn a private server — the
   *Attach to the shared opencode service (v2)* preference controls this):
   ```
   opencode serve --hostname 127.0.0.1 --port 4096
   ```
   (If you set `OPENCODE_SERVER_PASSWORD`, also fill it in below.)
2. **Window → Perspective → Open Perspective → Other… → OpenCode**.
3. The perspective opens **chat-first**: the Chat view takes the large right
   area, and every other view (Server, Repo, Providers, Board, Fleet) is a tab
   in the single left column. If the server URL differs, set it in
   **Window → Preferences → OpenCode**, then hit
   the **Refresh** button on each view's toolbar.

The Server view's Agents category shows `name / mode / description`.
The Providers view shows providers as tree roots with their models as children
(`name / id / status / capabilities [R=reasoning A=attachment T=toolcall] / context`).

## Status & roadmap

- **Done (deployed/tested):** Phases 0–6 — toolchain, `core`/`ui`/`cdt` bundles, feature + p2 repo,
  spawn + connect modes, readiness probe + retry, JVM shutdown hook (no orphaned servers),
  a unified **Server** view (`Server → Agents / Sessions`, sessions nested by `parentID`,
  live via `/api/event` SSE with a thinking/running-tool indicator), a flat **Providers** view
  (per-model rows, filter + sort, server in header), icons, connection preference page.
- **Done (Phase 12 chat, live-verified):** a native **Chat** view (`com.opencode.ide.chat`) —
  markdown (code blocks, tables), **LaTeX math** ($…$, $$…$$ via KaTeX, extracted before
  markdown), **syntax highlighting** (highlight.js incl. c/cpp/cmake/makefile, offline assets,
  IDE-theme-synced), streaming reply text via `/api/event` SSE, agent + model + **variant** pickers,
  multi-window chat + session resume, external links opened in the system browser, and a
  capability **`system`** prompt so models format for the view unprompted (toggle:
  *Preferences → OpenCode → Advertise rendering*). opencode v1.18.x quirks handled:
  explicit-model requirement, HTTP/1.1 forced, flat `providerID`/`modelID` on assistant messages.
  **Mermaid diagrams render** — verified by an automated check that drives the real page in
  headless Edge (SVG output, visible degradation for broken sources; `mermaid-check.mjs`, part
  of `mvn verify`).
- **Done (Phase 13, first cut):** `com.opencode.ide.git` — worktree-per-task isolation with
  serial merge-back and clean conflict abort; `createSession(title, directory)` scopes
  sessions to a worktree.
- **Done (Phase 14, first cut, 29 tests):** `com.opencode.ide.mcp` + `com.opencode.ide.tools` —
  local MCP endpoint (stateless Streamable HTTP) + **`ToolProvider` SPI**; the C++ provider
  exposes **8 agent tools** (`toolchains_list`, `cmake_configure`, `cmake_build`, `ctest_run`,
  `run_binary`, `debug_batch`, `lint_run` clang-tidy/cppcheck, `format_run` clang-format)
  across **MSVC + MSYS2 clang64/mingw64/ucrt64** (auto-detected; E2E test compiles+runs
  hello-world via ucrt64). **Registration with the opencode server is wired** (OSGi services +
  `McpRegistrationComponent`; live check pending on next Eclipse start).
- **Done (Phase 15 engine, 12 tests):** `com.opencode.ide.fleet` — headless `FleetRunner`
  (submit → worktree + directory-scoped session → poll → mergeBack), Eclipse-free.
- **Done (hardening):** default-scheme **key bindings** (openPerspective Ctrl+Alt+Shift+O,
  refreshViews Ctrl+Alt+Shift+R, openChat Ctrl+Alt+Shift+C — rebindable via Preferences →
  Keys); `ViewLoadSupport` (no more stuck "Loading…" on unchecked failures); client error
  semantics, SSE trailing-frame fix, URL validation (85 client tests).
- **Done (architecture migrations M1–M3):** `core` split into the Eclipse-free
  **`client`** bundle (pure-Java opencode client, Eclipse-import ban enforced at build time) and
  the Eclipse **adapter** core; the chat web renderer extracted into the standalone
  **`components/chat-web`** component (own README + checks, hostable anywhere); the
  **`ToolProvider` SPI** promoted into the Eclipse-free **`tools`** bundle (mcp = endpoint only).
  See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the four separation axes, the verified module
  map and the review checklist. M4 (second agent backend) deliberately deferred.
- **Done (H1 complete — task board, 2026-08-18):** the Markdown task store + `task_*`
  tool pack (stdio + `eclipse-build` MCP), **FleetRunner v2** (`TaskFleet.launch`:
  pre-claim → worktree → role-mapped agent → self-claim prompt → serialized mergeBack →
  in-review + git artifact / blocked on failure), and the **`opencode-tasks` Maven
  plugin** (`:sync` store validation/normalization, `:plan` sprint-board Markdown/HTML).
  Dogfooding from S-01 on: the harness tracks its own work in `.opencode/tasks/hephaestus/`.
- **Done (H2 Eclipse surfaces, 2026-08-18 — live UI check pending first Eclipse start):**
  **PM Board view** (kanban, sprint selector + goal, blocked flags, artifact links,
  *Launch task* → `TaskFleetLauncher`, *Take over*, live `TaskStoreWatcher` refresh) and
  **Fleet view** (task → session → worktree → state, per-job diff/folder/takeover) in the
  new `board` bundle; **provider logos** vendored from Artificial Analysis (16 slugs,
  SVG+PNG, letter-badge fallback, `THIRD-PARTY.md` attribution).
  **470 Java tests + 145 JS checks green in one reactor build (2026-08-18 counts; today:
  1048 Java tests + 153 Node checks); 11 jars.**
- **Done (post-H4 polish, 2026-08-18):** Server view shows **MCP servers + Skills** sections
  per server root (`GET /mcp` / `GET /skill`, 404-tolerant); **preferred defaults**
  (chat model `provider/model` + variant, task-store root + board project, spawn working
  directory — default this repo, so the spawned server loads the Hephaestus agents/skills)
  in *Preferences → OpenCode*; chat **streaming cursor stops** on completion/failure/abort
  (`__stopStream` bridge; the final render targets the streamed bubble id; exposed a DOM-shim
  appendChild bug in the checks along the way).
- **Done (H3 scale & depth + H4 CDT/chat polish, 2026-08-18 — live UI check pending first
  Eclipse start):** `ConnectionsManager` (plural connections, per-remote SSE liveness,
  30s agents/providers cache) + virtualized Server/Providers views + remote-connections
  preference page; **Session details view** (messages/parts/tool lines/tokens, per-session
   secondary id, auto-refresh); **SSE event-driven fleet completion** (the
   `SessionEvents` seam — later removed with the watchdog redesign: the probe
   loop is authoritative, the primary event stream feeds only the permission
   bridge);
  **chat abort + tool-part rendering + copy-code + newChat/abort key bindings**;
  **CDT first cut** (`CdtProjectContext` service, `DiagnosticsMarkers`/`MarkerApplier`).
   Reviewed by rubberduck + reviewer gates (2 blockers, 7 majors found & fixed, incl. the
   real `ChatPart.state` wire shape and a TaskFleet double-launch guard).
- **Done (H5 + H6 complete, 2026-08-23, six parallel waves):** the full opencode v1.18
  deep-integration surface — permission queue for unattended fleets (chat sessions included),
  per-job server diffs, official `/tui` take-over (select-session → append → submit),
  Repo view + Working set + project/VCS header with cwd cross-check, `/command` picker +
  `/shell` bootstrap, fork/share/summarize lifecycle, provider auth + OAuth connect,
  global-events aggregator + Events dialog; and the **H6 dataflow V-pipeline**:
  `StageReadiness` → invalidation records → `task_readiness` tool → `AutoDispatch` policy
  (persisted, cost-calibrated) → background `DispatchScheduler` behind the Board's
  *Auto ▶* toggle — a stage runs as soon as its upstream is done and its inputs unchanged.
  Distributed-fleet store sync (status header + *Sync store*; see
  [`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md)). ~700 new unit/component tests, all
  headless; the Eclipse UI pass over the deployed jars is the remaining verification step.

**Strategic direction (see [`ROADMAP.md`](ROADMAP.md) for the full plan):**
- **Agentic C++ harness:** agents create/build/test/lint/format/debug headless in isolated git
  worktrees (Phases 13–14, first cuts landed); the **Fleet view + scheduler + user takeover**
  (Phase 15) make Eclipse the human's overview and control surface.
- **Scale to hundreds of agents** → few servers × many sessions; plural connections; **virtualized**
  viewers; core-side cache/throttle + a single `/api/event` SSE fan-out (Phases 7–8).
- **Maven sprint planning** → milestones/epics/sprints in a version-controlled `.opencode/tasks/`
  store, synced by `opencode-tasks:sync` and rendered by `opencode-tasks:plan`; agents read/write
  it via MCP tools; "launch from a task" feeds the fleet (Phase 9). Maven plans — CMake builds.
- **Multi-language later** → new `ToolProvider` implementations (Python first candidate); the
  chat/server/git layers are language-agnostic.
- **Native markdown chat** → shipped (Phase 12) incl. abort, tool parts, copy-code, `/command` picker.

## Notes

- The DTOs are modelled against the **installed** opencode v2 (2.0.10). In this version the
  agent object has neither `native` nor `builtIn` (v1.18.x had `native`); every path is
  prefixed `/api` and list endpoints answer with a `{data:[…]}` envelope. The records keep
  newer-only fields nullable for forward-compatibility — re-validate against a live server
  on every upgrade.
- The server password is stored in plain instance preferences (local server only);
  switch to `org.eclipse.equinox.security` for remote servers.
