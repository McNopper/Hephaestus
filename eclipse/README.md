# opencode-eclipse

An **Eclipse-based agentic C++ harness**: [opencode](https://opencode.ai) agents create, build,
test, lint, format and debug C/C++ projects headless (CMake-first, multi-toolchain: MSVC +
MSYS2 clang64/mingw64/ucrt64 + native GCC/Clang on Unix), isolated in git worktrees, while the
human uses Eclipse CDT for overview and takeover. Built with Maven Tycho against Eclipse
Platform 4.40 (SimRel 2026-06), Java 21, CDT 12.5. The tool layer is a language-agnostic SPI —
Python/other packs plug in later.

## About this document
- **Kind:** `doc` / subproject README (the Eclipse harness's entry point).
- **Read by:** humans building, deploying or hacking on the harness; agents orienting in `eclipse/`.
- **Written by:** maintainers.
- **Related:** [`ARCHITECTURE.md`](ARCHITECTURE.md) (separation axes + review checklist),
  [`QUALITY.md`](QUALITY.md) (the quality gate), [`INSTALL.md`](INSTALL.md) (detailed install),
  [`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md) (one board across machines),
  [`../ROADMAP.md`](../ROADMAP.md) (the repo-level roadmap — supersedes the retired
  `eclipse/ROADMAP.md`), [`../docs/HISTORY.md`](../docs/HISTORY.md) (the changelog).

## Architecture — strictly separated OSGi bundles

**Four separation axes govern every module** (full rationale + migration plan in
[`ARCHITECTURE.md`](ARCHITECTURE.md)): **Eclipse vs. non-Eclipse** (pure Java stays reusable
outside OSGi), **Java vs. non-Java** (the `web/` chat renderer is a standalone component with a
versioned bridge contract), **dev-environment packs** (C++ today, Python etc. later — behind the
`ToolProvider` SPI), and **coding-agent backend** (opencode today; the client layer is the seam
for a second backend, extracted when one actually arrives).

| Bundle | Layer | Depends on | Purpose |
|---|---|---|---|
| `com.opencode.ide.client` | **opencode** | `com.google.gson` + the Eclipse **JobManager** runtime (`org.eclipse.core.jobs`, `org.eclipse.equinox.common`) — the one allowlisted exception in the build-time Eclipse-import ban; both run in a plain JVM | Pure-Java opencode HTTP client, DTOs (records mirroring the server's OpenAPI), `ChatRequests`/`McpRequests`, SSE parsing + event stream, server launcher, `ClientLog` seam. Reusable as a plain library outside Eclipse/OSGi. `WorkerPools` runs **every** background task as a Job in one `JobGroup` (the same work scheduler in every host — Eclipse, the stdio tool JVMs, tests); `RuntimeTuning` is its live knob set (poll sleep, stall timeout, ticket budget, worker threads). `Turns` owns the turn-end rule (see development rules). |
| `com.opencode.ide.core` | **eclipse adapter** | `client` + `mcp` + Eclipse runtime/resources + `equinox.security` | Eclipse glue: preferences (remote-connection passwords go through `SecureRemoteCredentials` into Equinox **secure storage**, degrading to non-persisting in-memory storage when unavailable), activator (installs the Eclipse `ClientLog` adapter, bridges the tasksRoot preference into the MCP endpoint), `ProjectContext` service tracking, `OpencodeConnection` + `ConnectionsManager` (plural connections) lifecycle. |
| `com.opencode.ide.ui` | **eclipse** | `core` + `client` + `tools` + gson + Eclipse UI (`ui`, `ui.ide`, `ui.console`, `jface.notifications`, swt) | The **OpenCode** perspective, the Server/Providers/**Repo**/**Background**/**Session Details** views, the connection preference page. Server view: project/VCS header (dirty + cwd-mismatch warning) + *Working set* category + agent-nested live sessions (double-click = transcript, **live busy icons** refreshed from the server's session-status map); Session Details carries Fork/Summarize lifecycle actions plus revert/undo/commit (the two-phase truth), environment replace (full-replace stated), import-with-preview, **Attach skill**, **Suggest title**, mark-viewed, and subagent + shell-task sections (U-041; shell output opens in an Eclipse console), plus **Open message/transcript in Editor** (snapshot copy) and the server rows offer an **MCP servers…** dialog, **Saved permissions...** (remembered allow/deny: list + remove) and **Integrations...** (catalog + real command/key/oauth connect flows with abort, U-048) plus a v1-migration banner while a migration runs; the `ui.attention` package adds event-stream desktop notifications + sound (off by default; own preference page); the **Agent Tools console** streams every agent tool call (argument summary + output tail); Providers view shows auth state + *Connect…* (OAuth URL); Repo view = lazy workspace file tree + **fuzzy file search** (`GET /api/fs/find`) — search is file-name-only. The **Tuning dialog** (from the Background view) adjusts `RuntimeTuning` live. |
| `com.opencode.ide.chat` | **chat ui** | `core` + `client` + gson + SWT `Browser` (no `com.opencode.ide.ui` dependency) | Eclipse **host** for the chat-web component: `ChatPage` (browser facade) + `ChatSessionController` (SWT-free) + embedded `ChatWebServer` serving the component's assets. Composer carries the `/command` picker (`CommandComposer`), built-in slash commands (`/undo` `/redo` `/init` `/help` `/thinking`; `/share` `/unshare` show honest not-implemented notices - v2 sessions cannot be shared), `@`-file fuzzy references merged with `@alias` reference roots, answerable question-form cards and an in-chat permission dialog (U-014), and last-session/model continuity across restarts (`ChatViewSettings`); exported `ChatPermissionSink` seam routes chat-session permission asks into the shared queue. A capability **`system`** prompt tells models how to format for the view (the `isAdvertiseRendering` preference exists in code; **no preference-page control yet** — tracked by U-055). |
| `components/chat-web` | **non-Java** | — (static assets + node checks) | The standalone chat renderer (markdown + KaTeX + highlight.js + mermaid) with a documented bridge contract — hostable in any environment that serves files and calls JS. |
| `com.opencode.ide.git` | **agentic git** | `client` (git CLI) — Eclipse-free | `WorktreeManager`: branch + worktree per agent task (under `.git/opencode-fleet/`), serial merge-back with clean conflict abort. Fleet isolation layer. `StoreGitStatus`/`StoreSync` = distributed-fleet store discipline (status summary; commit → pull-rebase → push with recover — see [`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md)). |
| `com.opencode.ide.fleet` | **fleet engine** | `client` + `git` + `tasks` + `tools` — **Eclipse-free, build-enforced** | `FleetRunner`: the headless loop — `begin` (worktree + directory-scoped session + optional `/shell` `Bootstrap`; the prompt POST runs on the shared `WorkerPools` JobGroup, never a private thread pool) → watchdog probe (busy/messages/complete; stall = idle-and-silent only, aborted; the ticket budget is progress-aware — busy alone is not progress — with an absolute run cap as backstop, B-008; every abort records a diagnostic snapshot on the ticket) → guarded merge back (auto-commits worker changes, refuses empty results, tolerates peer store writes; repo-gated via `RepoGate`). `TaskFleet` adds the V-pipeline launch loop (pre-claim, scoped to the store subtree → stage-mapped agent → merge → actuals); failed runs RELEASE their claim (sprint-backlog + blocked-with-reason — never a zombie in-progress). The headless `FleetControl` auto-syncs the store after every launch. Knobs: the engine reads `RuntimeTuning` (live-adjustable via the Tuning dialog); the `FleetTuning` environment knobs (`FLEET_STALL_TIMEOUT_MS`, `FLEET_TICKET_BUDGET_MS`) are documented but **do not take effect yet** (tracked by B-015). `PermissionQueue` + `FleetPermissionBridge` buffer pending `permission.asked` requests for unattended runs (the watchdog's stall clock pauses while asks are pending); `GlobalEventsAggregator` merges `/api/event` streams across connections. **Chat-first control** (H7): `FleetControl`/`FleetToolProvider`/`FleetStdioMain` expose the `fleet_*` tool pack over stdio via `eclipse/fleet-tools.ps1` — `fleet_dispatch`, `fleet_jobs`, `fleet_job_details` (live progress), `fleet_job_activity` (deep per-job observation), `fleet_permissions`(+`_answer`), `fleet_sync_store`, `fleet_status_store`, `fleet_recover_store`, `fleet_reset` (consume residue: worktree+branch removal + ticket release), `fleet_auto_start`/`fleet_auto_stop`/`fleet_auto_status`, `fleet_waves_start`/`fleet_waves_stop`/`fleet_waves_status`, `fleet_shutdown` (the one graceful maintenance stop). Chat is the primary interface; the Board buttons are conveniences. The automatic pump (auto-dispatch / recurring waves) runs in **whichever host starts it** — the Eclipse Board, or this stdio host when a chat session calls `fleet_auto_start`/`fleet_waves_start` — for as long as that host runs; there is no detached fleet daemon and no third host. |
| `com.opencode.ide.tools` | **agent tools** | `com.google.gson` only — **Eclipse-free, build-enforced** | **`ToolProvider` SPI** + JSON-RPC/MCP dispatch (`McpDispatcher`, shared by every server surface incl. `ping`) + the built-in C++ tool pack (`tools.cpp`: toolchain detection + build, lint, format, debug). Future language packs = new providers depending on this bundle only. |
| `com.opencode.ide.tasks` | **task board** | `tools` + gson — **Eclipse-free, build-enforced** | The **task store** (`.opencode/tasks/<project>/`, one Markdown file per ticket) + the **`task_*` tool pack** (create/claim/release/sprint/traceability/readiness/doctor/invalidations) + `StageReadiness` (the pure dataflow-readiness function behind H6 auto-dispatch and `task_readiness`). Also ships `TasksStdioMain` — the same tools over stdio via `eclipse/tasks-tools.ps1` for TUI-only sessions. |
| `com.opencode.ide.board` | **board ui** | `core` + `client` + `tasks` + `fleet` + `git` + `chat` + gson + Eclipse UI | **PM Board view** (kanban over the task store: **six status columns — paused included** — sprint selector + goal, blocked flags, artifact links with markdown/diagram rendering, *Launch task* → `TaskFleet` via `TaskFleetLauncher`, *Take over*, fleet-row **Shutdown...** (graceful: park admissions, checkpoint + pause in-flight workers, kill a spawned serve - never an attached shared service), activation refresh + an `updated HH:mm:ss` freshness stamp, **Cost overview** dialog + `• $X spent` header suffix aggregating the `fleet actuals:` comments; **Auto ▶** arms the auto-dispatch pump and **Waves ▶/■** toggles the U-022 recurring-waves loop — both off by default) + **Fleet view** (U-040 tree: engine → wave → job (ticket badges) → worker session → subagent sessions → console/shell tasks, live activity per node + tokens/cost rolled up, per-job **server diff** (`/session/:id/diff`) with local-git fallback, folder/takeover, **Permissions (n)** dialog — approve once/always/reject on pending `permission.asked` requests). SWT-free model (`BoardModel`, `TaskStoreWatcher`, `FleetJobsModel`, `FleetTree`, `CostOverview`, `DiffSource`/`SessionDiffText`, `FleetPermissions`) is unit-tested. |
| `mojo/opencode-tasks` | **maven plugin** | the `tasks` bundle store classes (plain jar dep) | **`opencode-tasks:sync`** (validate/normalize `.opencode/tasks/`: schema lint, id/counter consistency, LF; `-Dopencode.tasks.fix=true` applies safe fixes) and **`opencode-tasks:plan`** (render the sprint board as Markdown + standalone HTML into `target/opencode-tasks/`). Maven plans, CMake builds — never invokes a compiler. |
| `com.opencode.ide.mcp` | **agent endpoint** | `tools` + `tasks` + `client` + gson | Local **MCP server** (stateless Streamable HTTP on 127.0.0.1, **per-start token auth** — the registered `?token=` URL or a `Bearer` header; 401 otherwise, G-003): OSGi DS lifecycle + HTTP endpoint only; tool implementations live in `tools`/`tasks`. Service-driven activation — the endpoint comes up when core binds it, after the tasksRoot preference was bridged. |
| `com.opencode.ide.cdt` | **C++/CDT** | `core` + CDT bundles | Implements the `ProjectContext` seam (`CdtProjectContext`: active `ICProject` → spawn cwd) + `DiagnosticsMarkers`/`MarkerApplier` (opencode diagnostics → CDT markers). |

Dependency rules (enforced in the manifests, plus **build-time Eclipse-import bans in five
bundles** — `client`, `tools`, `fleet`, `git` and `tasks` fail the build on any
`org.eclipse.*`/`org.osgi.*` reference under `src/main/java`; the `client` ban's allowlist
admits exactly `org.eclipse.core.jobs` + `org.eclipse.equinox.common`): **layering runs
`client` → `core` → `ui`/`cdt`** — `client` is the pure-Java opencode layer, `core` the Eclipse
adapter on top of it, `ui` and `cdt` the surfaces on top of `core`; `client` never depends on
the others. The core/UI bundles talk to the CDT layer only through the `ProjectContext`
interface (defined in core's context package), implemented as an OSGi service in the cdt
bundle. `git`, `tools` and `components/chat-web` have no Eclipse dependencies at all — agents
run headless; the IDE is the human's overview + takeover surface. See
[`ARCHITECTURE.md`](ARCHITECTURE.md) for the four separation axes and the review checklist, and
[`DISTRIBUTED-FLEETS.md`](DISTRIBUTED-FLEETS.md) for running one shared board across several
machines (store git status + *Sync store* in the Board view).

```
eclipse/
├── build.ps1                      # thin wrapper: resolves a JDK, then runs the Maven Wrapper
├── deploy-dev.ps1                 # copies the built bundle JARs to <eclipse-install>\dropins\opencode-ide\plugins\
├── auto-deploy.ps1                # post-merge reactor build + dropins refresh (U-034; red builds never deploy)
├── tasks-tools.ps1 / fleet-tools.ps1   # stdio MCP launchers for the task board / fleet (TUI sessions)
├── fleet-stop.ps1                 # one-action graceful fleet shutdown (pairs with the fleet_shutdown tool)
├── debug-launch.ps1 / shot.ps1    # dev helpers (debug launch config, window capture)
├── mvnw.cmd / mvnw / .mvn/        # Maven Wrapper (Maven 3.9.9) — no system Maven needed
├── pom.xml                        # Tycho 5.0.3 reactor parent
├── plugin_customization.ini       # recommended Eclipse instance settings
├── components/
│   └── chat-web/                  # standalone chat renderer (web assets + node checks + README)
├── bundles/
│   ├── com.opencode.ide.client    # + client.tests — pure-Java opencode client (Eclipse-free)
│   ├── com.opencode.ide.core      # + core.tests — Eclipse adapter (preferences, activator, connection)
│   ├── com.opencode.ide.ui        # + ui.tests — views, perspective, session details
│   ├── com.opencode.ide.chat      # + chat.tests; consumes components/chat-web at build time
│   ├── com.opencode.ide.git       # + git.tests (worktree fleet isolation, Eclipse-free)
│   ├── com.opencode.ide.fleet     # + fleet.tests — headless fleet engine incl. TaskFleet (Eclipse-free)
│   ├── com.opencode.ide.tools     # + tools.tests — ToolProvider SPI + C++ pack (Eclipse-free)
│   ├── com.opencode.ide.tasks     # + tasks.tests — task store + task_* tool pack (Eclipse-free)
│   ├── com.opencode.ide.board     # + board.tests — PM Board + Fleet views (Eclipse UI; SWT-free model)
│   ├── com.opencode.ide.mcp       # + mcp.tests — MCP HTTP endpoint + DS lifecycle
│   └── com.opencode.ide.cdt       # + cdt.tests — CDT ProjectContext + markers bridge
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
  returns `[node]` (input == element) destabilizes the TreeViewer (it renders the root
  infinitely). Pass a wrapper, e.g. `setInput(List.of(node))`, and resolve the node back in
  `inputChanged`.
- **Dev rule (browser views):** never serve a bundled web page via `FileLocator.toFileURL` —
  jar'd bundles extract single files, so relative assets 404 and the page dies.
  Use the embedded `ChatWebServer` (localhost HTTP) instead; it is component-tested.
- **Dev rule (Java→JS bridge):** every Java→JS call goes through **`ChatScripts`** and passes data
  as a **JSON *string* literal** (never a JS object literal) — the page's `payload()` accepts both
  and `guard()` reports errors. This matters because **`Browser.execute()` on the Edge backend
  returns `true` even when the script throws**, so a contract mismatch fails *silently*. The page
  also reports `page-ready` and every render via `__javaReport` — live verification means reading
  those log markers, never trusting `execute()`'s return value (`bridge-check.mjs` *executes* the
  real `chat.js` against a DOM shim to keep this honest).
- **Dev rule (turn settling):** the client's **`Turns`** judge owns turn-end.
  `Turns.replyEvidence` accepts only a trailing, complete (`time.completed`), non-blank
  assistant step with **no tool call or shell run still in flight** *and* a finish reason other
  than `tool-calls` — a completed step that ended by *calling a tool* explicitly means the turn
  continues (the boundary between two tool calls is invisible to both in-flight checks and
  quiet windows; T-010 regression-pins the live wire shape). Callers must additionally require
  their own "turn may be over" condition (the prompt call resolved, or a quiet window elapsed) —
  evidence alone is true at every inter-step boundary.
- **Dev rule (math before markdown):** KaTeX math is extracted into `\uE000…\uE001` markers and
  rendered *before* markdown runs — auto-render after markdown breaks on multi-line `$$…$$`
  (`breaks:true` inserts `<br>` between the delimiters), on `\(...\)`/`\[...\]` (markdown eats the
  backslashes) and on `\\` (collapses). Currency (`$5`) and code fences are excluded.
- **Dev rule (PowerShell):** never patch source files with PowerShell regex replacements and never
  inline JS in `node -e` — `$ref`, `$5` and `\$` get mangled. Use the edit tool for files and write
  probe scripts to a real `.mjs`/`.cjs` file for Node.
- **Dev rule (tool providers):** agent tools live behind the **`tools` bundle's** `ToolProvider`
  SPI (`language()` + `tools()` + `call()`); C++ specifics stay in `CppToolProvider`. New language
  packs = new providers, never edits to the dispatcher. Toolchains are *detected, never assumed*
  (MSVC via vswhere; MSYS2 envs by probing `C:\msys64\<env>\bin`; native GCC then Clang from PATH
  on Unix) and every optional binary reports an install hint when absent.
- **Process rule (clean architecture):** every change lands with tests; history lives in git
  and the task store, not in code or this README.
- **Project-root resources (`plugin.xml`, `OSGI-INF/*`) are NOT auto-packaged** by Tycho 5/bnd —
  put them under `src/main/resources/` so the resources plugin copies them into the jar.
- **Keep the build light during iteration.** The reliable recipe is the full reactor (below);
  isolated `-pl` builds fail Tycho resolution of sibling SNAPSHOT bundles unless you add
  `-am`. If you scope, include what you touched **and its `.tests` sibling** — e.g.
  `.\build.ps1 -pl bundles/com.opencode.ide.ui -pl bundles/com.opencode.ide.ui.tests -am clean verify`.
  Use repeated `-pl a -pl b`, not `-pl a,b` (comma is an arg separator under `cmd.exe`).
- **dropins dev deploy uses the `plugins/` layout:** `<eclipse-install>\dropins\opencode-ide\plugins\*.jar`
  (a folder of loose JARs is rejected by p2 with "No repository found"). `deploy-dev.ps1` handles this.
- **Eclipse must be closed** before `deploy-dev.ps1` (the bundle jars are locked while Eclipse runs).
- **opencode v2 DTO contract.** `Agent.Info` carries `id`, `name`, `mode`, `hidden` and
  `permissions` — there is no `native` **and** no `builtIn` field (v1.18.x had `native`);
  `Provider.models` is a map keyed by model id. Every path is prefixed **`/api`**, list
  endpoints wrap their rows in `{data:[…]}`, and the server requires HTTP Basic auth.
  Re-validate the records against a live server on every opencode upgrade.
- **v2 connection model.** The local/primary connection prefers **attaching to the shared
  background service** every v2 client (TUI, CLI) uses: `OpencodeServiceDiscovery` reads the
  registration file (`~/.local/state/opencode/service.json`), probes `/api/info`, and starts
  `opencode serve --service` when absent — with the private-spawn path as fallback
  (preference *Attach to the shared opencode service (v2)*, default ON). Two v2 realities shape
  the views: **session state is global per user** (every server lists every session), so the
  Server view scopes its list via `GET /session?directory=…`; **event streams stay per-process**,
  which is why the **fleet deliberately keeps its own spawned server** (a quiet stream, a private
  password, a killable budget). Chat is async in v2: `POST /session/:id/prompt`
  (+`/agent` `/model` `/synthetic`) then poll `GET …/message` until `time.completed`;
  shell commands follow the same split.
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
4. **PowerShell 7 (`pwsh`) on PATH** — only needed to *run* the harness's stdio
   MCP launchers (`tasks-tools.ps1`/`fleet-tools.ps1`), not to build it.
5. Optional, only for `cpp/` toolchain presets: LLVM clang + Ninja (default),
   MSYS2 `clang64`/`mingw64` shells, Visual Studio, or WSL (`cmake`+`ninja`+`gcc`).

The one build recipe (compiles everything, runs all tests, assembles the p2 site):

```powershell
cd eclipse   # from the repo root
.\build.ps1 clean verify
```

A full reactor run executes every bundle's JUnit suite (all `*.tests` modules plus the
`opencode-tasks` mojo's tests — among them a real ucrt64 compile-and-run E2E in the mcp suite,
a cross-process claim race against a spawned stdio JVM in the tasks suite, and a real headless
workspace for marker application in the cdt suite) and the three Node checks against
`components/chat-web` (`renderer-check.mjs`, `bridge-check.mjs`, `mermaid-check.mjs` — the last
renders diagrams in **real headless Edge** and SKIPs where Edge/puppeteer-core are absent;
wired into `mvn verify` via `exec-maven-plugin`). The client, tools, fleet, git and tasks
bundles additionally fail the build on any `org.eclipse.*`/`org.osgi.*` import (see the
dependency rules above). The reactor also assembles the p2 update site at
`releng/com.opencode.ide.repository/target/repository/`.

```powershell
.\deploy-dev.ps1     # close Eclipse first (jars are locked while it runs); copies every bundle jar
```

## Install into Eclipse CDT

1. Build (above).
2. In Eclipse CDT (`<eclipse-install>`, default `C:\eclipse-cpp`): **Help → Install New Software…**
3. **Add…** a local repository pointing at:
   `<repo>/eclipse/releng/com.opencode.ide.repository/target/repository`
4. Select the **OpenCode IDE** feature, finish, restart.

## First use

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
   area, and every other view — Server, Repo, Providers, Board, Fleet,
   **Background** (background jobs + live tuning), **Session Details** — is a
   tab in the single left column. Agent tool calls stream into the
   **Agent Tools** console (*Window → Show View → Console*). If the server URL
   differs, set it in **Window → Preferences → OpenCode**, then hit the
   **Refresh** button on each view's toolbar.

The Server view's Agents category shows `name / mode / description`.
The Providers view shows providers as tree roots with their models as children
(`name / id / status / capabilities [R=reasoning A=attachment T=toolcall] / context`).

## Status & roadmap

The harness is deployed and **dogfooded daily**: the Hephaestus project plans, executes and
reviews its own work through the Board, the fleet engine and the store.

- **Engine (all Eclipse-free, headless-tested):** worktree-per-task isolation with guarded
  merge-back; progress-aware watchdog with permission-aware stall clock and per-abort
  diagnostic snapshots; the permission queue for unattended runs; U-021 autonomous acceptance
  (the engine's review pass applies the verdict: PASS → done + advance, FAIL → staged
  send-back, UNCLEAR → human); U-022 recurring waves with NEEDS-HUMAN parking; the H6
  dataflow V-pipeline (`StageReadiness` → invalidations → `task_readiness` → auto-dispatch
  policy); distributed-fleet store sync; graceful shutdown with checkpointing.
- **IDE surfaces:** native chat view (markdown, KaTeX math, syntax highlighting, mermaid,
  streaming, abort, tool parts, copy-code, `/command` picker, `@`-file references, question
  forms + in-chat permissions, cross-restart continuity); Server/Providers/Repo views (MCP
  servers + Skills sections, saved permissions, integration connect flows, working sets,
  fuzzy file search); Session Details (lifecycle, two-phase truth, environment replace,
  import preview, attach skill, suggest title, subagents + shell consoles); Background view
  + attention notifications; the six-column PM Board with V-pipeline layout, cost overview,
  launch/take-over/shutdown, **Auto ▶** and **Waves ▶** pumps; the Fleet tree with per-job
  diffs and permissions; CDT ProjectContext + diagnostic markers.
- **TUI parity (chat-first control plane):** the whole ticket/sprint workflow via the `tasks`
  stdio server, the whole fleet via the `fleet` stdio server (ships disabled in
  `opencode.json` — enable by flipping `"disabled"` to `false`); the Board is the human's
  control surface on top of the same engine.
- **Verification posture:** the entire stack is unit/component-tested headless and the
  reactor is the gate; the live in-Eclipse UI pass over the deployed jars is the rolling
  verification step.

**Strategic direction (see [`../ROADMAP.md`](../ROADMAP.md) for the full plan):**
- **Multi-language later** → new `ToolProvider` implementations (Python first candidate); the
  chat/server/git layers are language-agnostic.
- **Second coding-agent backend** → behind the client seam, extracted when one actually
  arrives (deliberately deferred).
- **Scale** → few servers × many sessions; plural connections; virtualized viewers; core-side
  cache/throttle + a single `/api/event` SSE fan-out.

## Notes

- The DTOs are modelled against the **installed** opencode v2 (last live cross-check: 2.0.19,
  2026-09-29 - `docs/opencode-v2-adoption.md`). In this version the
  agent object has neither `native` nor `builtIn` (v1.18.x had `native`); every path is
  prefixed `/api` and list endpoints answer with a `{data:[…]}` envelope. The records keep
  newer-only fields nullable for forward-compatibility — re-validate against a live server
  on every upgrade.
- Remote-connection passwords are stored through `SecureRemoteCredentials`
  (`org.eclipse.equinox.security` secure storage); when secure storage is unavailable the
  harness degrades to a non-persisting in-memory implementation and says so in the log.
