> **Superseded** — the consolidated roadmap now lives at the repository root: [`../ROADMAP.md`](../ROADMAP.md). This file remains as the detailed pre-consolidation history.

# Roadmap — opencode-eclipse

What we're building, why, and in what order. This lives alongside `README.md` (architecture/build)
and `INSTALL.md` (run options).

## Vision

**An Eclipse-based agentic C++ harness**: autonomous agents create, build, test, lint, format and
debug C/C++ projects headless (CMake-first, multi-toolchain), while the human uses Eclipse CDT for
overview, inspection and takeover. The architecture is language-agnostic at the seams — the MCP
tool layer is a `ToolProvider` SPI so Python/other language packs can plug in later.

## Strategic inputs (the shape of the future)

1. **Hundreds of agents** running from this IDE. Realistically that means **a few opencode
   servers, each running many sessions** (one server process ~1 GB; never one process per agent).
   The UI and the core client must be designed for *many sessions per server* and *multiple servers*.
2. **Todos & tasks synchronized via Maven.** opencode per-session todos (`/session/:id/todo`) must
   become first-class Eclipse tasks, persisted in the project and shared across the team / CI through
   Maven — grown into **sprint planning**: milestones, epics and sprints live in the same
   Maven-synced store (`mvn opencode-tasks:sync`).
3. **Agents work independently; Eclipse is for the human.** Agents get isolated git worktrees,
   headless toolchains and MCP tools; the IDE's job is overview of the whole fleet, results, and
   **user takeover** of any task when needed. The user only ever edits the main CDT worktree.
4. **Multi-toolchain by default:** MSYS2 clang64/mingw64/ucrt64 **and** MSVC (VS 2026) — detected,
   selectable per build, and exposed to agents (`ToolchainRegistry`).

## Design implications (rules that follow from the above)

- **One server → many sessions.** The unit of scaling is the *session*, not a process. Connections are
  plural and first-class.
- **Every list/tree viewer must be virtual** (`SWT.VIRTUAL` / `ILazyContentProvider`) once item counts
  can exceed ~200 (sessions, messages). Current `ArrayContentProvider` viewers are fine only while
  counts stay small.
- **All server access funnels through `com.opencode.ide.core`'s `OpencodeClient`**, with a
  request **cache + throttle** and a single **SSE fan-out** (`/api/event`) in core. The UI must never
  issue one request per row per refresh.
- **Reuse opencode's own endpoints** (`/api/session`, `/api/session/active`, `/api/session/:id/message`,
  `/api/session/:id/prompt`, `/api/event`, `/tui`, `/pty`). Do not reimplement state the server already owns.
- **Prefer new bundles for new concerns** (tasks, orchestration) over bloating `ui`. Keep the
  `core → ui → cdt` rule; add siblings (`tasks`, `tasks.maven`, …).
- **The opencode v2 DTO contract is empirical.** Re-validate records against a live server on
  upgrades (no `native`/`builtIn` on `Agent.Info`; `models` map; `/api` path prefix;
  `{data:[…]}` list envelopes; Basic auth; dynamic port).

## Where we are now (deployed / tested)

- **Foundation (Phases 0–5):** Tycho 5 reactor + Maven Wrapper; bundles `core`/`ui`/`chat`/`cdt` +
  feature + p2 repo; target = Eclipse 4.40 / Java 21 / CDT 12.5.
- **Core:** `HttpOpencodeClient` (Gson, basic-auth, HTTP/1.1), `OpencodeConnection` with
  **shared-service attach → spawn** (v2: discovers the per-user background service from its
  registration file, starts `opencode serve --service` when absent, falls back to a private
  spawn; `attachSharedService` preference) **+ connect**, readiness probe (`/api/info` **and**
  `/api/agent`), retry, **JVM shutdown hook** (no orphaned servers), `ProjectContext` seam,
  `OpencodePreferences` (default = spawn + attach), and the **`/api/event` SSE fan-out**
  (`OpencodeEventStream` + `Sse`) that drives live updates.
- **UI:** unified **Server** view (per-server explorer, subagents nested by `parentID`, live via SSE
  with a per-session activity indicator), flat **Providers** view (one row per model, filter + sort,
  provider badge icons), OpenCode perspective, connection preference page.
- **Chat (Phase 12):** working end-to-end — markdown + KaTeX math + **syntax highlighting**,
  streaming over SSE, multi-window + session resume, model **variant** selector, external links.
  ⚠ **Mermaid is broken in practice** (diagnostics deployed — see "Recover next" §0).
- **Agentic foundation (Phases 13–14, first cuts):** `com.opencode.ide.git` — fleet worktree
  manager (branch+worktree per task, serial merge-back, conflict abort) with 13 tests;
  `com.opencode.ide.mcp` — local MCP server (Streamable HTTP, stateless) with a **ToolProvider SPI**
  and a C++ provider exposing **8 agent tools** (cmake configure/build, ctest, run, gdb-batch
  debug, **clang-tidy/cppcheck lint, clang-format**, multi-toolchain list) across MSVC +
  MSYS2 clang64/mingw64/ucrt64; 29 tests. **Core** grew `createSession(title, directory)` and
  `registerMcp(name, config)` (verified against the OpenAPI spec).
- **Architecture migrations M1–M3 (landed in the refactor session, see
  [`ARCHITECTURE.md`](ARCHITECTURE.md)):** `client` (pure-Java opencode client, Eclipse-import
  ban at build time) split from `core` (now the Eclipse adapter); `components/chat-web`
  (standalone renderer component, own README + checks); `tools` (ToolProvider SPI + JSON-RPC
  dispatch + C++ pack, Eclipse-free) split from `mcp` (endpoint + DS only).
- **Tests: 171 Java (6 core + 85 client + 26 chat + 13 git + 12 fleet + 23 tools + 6 mcp)
  + 105 JS checks (43 renderer + 54 bridge + 8 mermaid-in-real-Edge), all green in one
  reactor build; 9 jars deployed to dropins.**

## Verified state — 2026-08-15 (end of session 2)

Build command (scoped to all bundles; a full reactor incl. p2 repo is rarely needed):

```powershell
.\build.ps1 -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.client.tests `
            -pl bundles/com.opencode.ide.core -pl bundles/com.opencode.ide.core.tests `
            -pl bundles/com.opencode.ide.ui -pl bundles/com.opencode.ide.chat `
            -pl bundles/com.opencode.ide.chat.tests -pl bundles/com.opencode.ide.cdt `
            -pl bundles/com.opencode.ide.git -pl bundles/com.opencode.ide.git.tests `
            -pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.fleet.tests `
            -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.tools.tests `
            -pl bundles/com.opencode.ide.mcp -pl bundles/com.opencode.ide.mcp.tests clean verify
.\deploy-dev.ps1     # close Eclipse first; now copies all 9 jars
```

**BUILD SUCCESS — 171 Java tests** (6 core + 85 client + 26 chat + 13 git + 12 fleet + 23 tools
+ 6 mcp) **+ 105 JS checks** (`renderer-check.mjs` 43 + `bridge-check.mjs` 54 +
`mermaid-check.mjs` 8 — the last drives **real headless Edge** and SKIPs when Edge or
puppeteer-core is absent — against `components/chat-web`, run inside `mvn verify`, Node on PATH
required, skip with `-DskipNodeChecks=true`; `client`/`tools`/`fleet` additionally enforce the
Eclipse-import ban `-DskipEclipseBan=true`). Deployed: 9 jars to
`C:\eclipse-cpp\dropins\opencode-ide\plugins\`.

### Landed this session (session 3 — parallel sprint: mermaid close-out, MCP wiring, fleet engine, hardening)

1. **Mermaid verified working (Recover next §0 closed):** `mermaid-check.mjs` renders real
   diagrams in headless Edge through the public bridge — SVG output, visible degradation for
   broken sources, re-render after clear, no JS errors. Wired into `mvn verify`.
2. **MCP registration wired (§1, code-level):** `OpencodeConnection` + `McpInfo` are OSGi
   services; `McpRegistrationComponent` registers `eclipse-build` once per client+url, non-fatal
   failures, retried when services re-appear (6 tests). Live check pending (first Eclipse launch).
3. **Phase 15 headless engine (§2 first slice):** `com.opencode.ide.fleet` (Eclipse-free,
   import-banned): `FleetTask`/`FleetJob`/`FleetRunner` — submit (worktree + directory-scoped
   session + prompt), poll-based completion (session idle + final assistant text), mergeBack
   (MERGED / FAILED-on-conflict), 12 tests with fakes.
4. **Key bindings first cut (§4):** default-scheme rebindable commands — openPerspective
   (Ctrl+Alt+Shift+O), refreshViews (Ctrl+Alt+Shift+R), openChat (Ctrl+Alt+Shift+C, existing
   handler now declarative).
5. **Refactor backlog cleared (§5):** `ViewLoadSupport` (both views harden against unchecked
   failures), `HttpOpencodeClient` error semantics, `Sse` trailing frame, `ConnectionConfig`
   validation; client bundle hardened 69→85 tests.

### Landed this session (session 2 — Track B kickoff)

1. **Core client**: `createSession(title, directory)` — `POST /session?directory=…` (worktree
   scoping for fleet sessions); `registerMcp(name, McpServerConfig)` — `POST /mcp` with
   `type:"remote"`, `oauth:false` explicitly (never start an OAuth flow against our endpoint).
   Both component-tested against a real HTTP stub, request shape asserted.
2. **Phase 13 first cut — `com.opencode.ide.git`:** `WorktreeManager` over the git CLI (probes
   PATH + `C:\Program Files\Git`): branch `opencode/<taskId>` + worktree under
   `.git/opencode-fleet/<taskId>` (invisible to `git status`), list/find/remove, **mergeBack with
   conflict abort** (never leaves the main worktree mid-merge, conflicted files reported),
   status (dirty files, HEAD). 13 tests incl. real merge + conflict scenarios.
3. **Phase 14 first cut — `com.opencode.ide.mcp`:** DS component starts a **stateless Streamable
   HTTP MCP server** on `127.0.0.1:<ephemeral>/mcp` (minimal JSON-RPC: initialize, tools/list,
   tools/call, 202 for notifications, GET→405 — spec-verified sufficient, opencode tries Streamable
   HTTP first). **`ToolProvider` SPI** (`language()` + `tools()` + `call()`) with
   `CppToolProvider` — the seam for future Python/other language packs. **8 tools:**
   `toolchains_list`, `cmake_configure`, `cmake_build`, `ctest_run`, `run_binary`, `debug_batch`
   (gdb `--batch`; clear install hint when absent — this machine has none), `lint_run`
   (clang-tidy w/ compile_commands + cppcheck w/ `--template=gcc`), `format_run` (clang-format
   check/apply). **ToolchainRegistry** detects MSVC (vswhere → VS generator from `cmake --help`)
   + MSYS2 clang64/mingw64/ucrt64 (Ninja), default order msvc→clang64→mingw64→ucrt64. E2E test
   compiles and runs a real hello-world via ucrt64 in ~9 s.
4. **ChatView split (Agent A):** 732 → 450 lines. New `ChatPage` (browser facade: bridge,
   page-ready queue, links, theme), `ChatSessionController` (SWT-free send/resume/delta logic,
   11 new tests with fakes), `ChatServerConnection` seam, `ChatLog`. All log markers preserved.
5. **Docs validation (research agent, sourced against opencode v1.18.18 code + MCP spec):**
   - `system` field on `POST …/message` **APPENDS** to the agent prompt (joined `\n`, after
     runtime context) — our capability prompt is safe; keep it short (persists per session).
   - MCP remote registration: **stateless POST-only JSON server is sufficient** (no SSE, no
     sessions) — exactly what we built.
   - `ISecurePreferences` is a drop-in for the password (`put(key, value, true)`, add
     `org.eclipse.equinox.security` dep) — migration still open (see review findings).
   - Raw REST+SSE is the officially sanctioned embedder path (the SDK itself uses it);
     `/tui/*` endpoints exist but are terminal-centric — not for us.

### Fixed earlier this session (session 1, all live-verified)

1. **Chat rendered nothing at all — root cause: Java↔JS contract.** Java emitted a JS *object literal*
   (`window.__appendUser({"text":…})`) while the page did `JSON.parse(arg)` (needs a *string*). Every
   render threw inside the page, and **`Browser.execute()` on the Edge backend returns `true` even
   when the script throws**, so it failed silently. Only `__setTheme`/`__setNotice` (plain string args)
   ever worked — which is exactly what the logs showed. Fixed with `ChatScripts` (one tested place that
   emits JSON *string literals*) + a tolerant, **guarded** page bridge that reports JS errors to the
   Eclipse log. Regression-proofed by `bridge-check.mjs`, which *executes* the bridge against a DOM
   shim (mutation-tested: reverting the page makes it fail with the exact production error).
2. **Model badge always empty** — the live server sends **flat** `providerID`/`modelID` on *assistant*
   message info (only *user* info has a nested `model` object). Our DTO only read the nested shape.
   `ChatMessageInfo.modelLabel()` now handles both; fixtures replaced with **verbatim live captures**.
3. **Math wasn't rendering properly** — the old pipeline ran markdown first and KaTeX auto-render after,
   which is broken for real model output: `breaks:true` puts `<br>` inside multi-line `$$…$$` (delimiters
   never match), markdown eats the backslashes of `\(…\)`/`\[…\]`, and `\\` collapses. Math is now
   **extracted before markdown** and re-inserted as KaTeX HTML (code fences/inline code and currency
   like `$5` are skipped).
4. **Syntax highlighting** (requested for C/C++): highlight.js bundled offline (36 languages incl.
   `c`, `cpp`, `makefile`, `bash`; `cmake` added separately), light/dark themes switched with the IDE
   theme, mermaid fences deliberately left un-highlighted, code always escaped (XSS).
   *Note: mermaid itself is still broken in practice — see "In flight".*
5. **Model variants** (as in opencode): `Model.variants` → a variant combo **on the same row, directly
   after the model**; sent as the top-level `variant` field. 118 models expose variants
   (`none/low/medium/high/xhigh/max`, `none/thinking`).
6. **The AI now formats for our renderer without being asked** — no skill needed. `POST
   /session/:id/message` has a per-request **`system`** field; we send a short capability prompt
   (`ChatCapabilities`), toggleable via the `advertiseRendering` preference. Verified live: asked
   "quadratic formula + 5-line C function" with **no** formatting hints and got `$$…$$` math *and* a
   ```` ```c ```` fence.
7. **Two scroll bars** — the document scrolled *and* `#chat` scrolled; only `#chat` scrolls now.
8. **Links open in the external browser** (page-level click interception + a `LocationListener`
   backstop), so a click can never replace the chat page.
9. **SSE stream leaked** a connection + `HttpClient` (selector thread) per reconnect, and hot-looped at
   1 req/s against an immediately-closing endpoint. Now closed properly, with a stability-gated
   back-off and a connect/disconnect listener.

### Refactoring done

- `web/chat.js` extracted out of `chat.html` (the tests load the real module instead of regex-scraping
  an inline `<script>`).
- `ChatScripts` — single tested source of truth for every Java→JS call.
- `ChatRequest` parameter object + `ChatRequests.messageBody(request)` (the client signature was
  growing a new argument per server feature: model, variant, system, …).
- `ChatCapabilities` — the client-capability system prompt, isolated and testable.

## Recover next (in this order)

0. ~~**Verify the mermaid fix**~~ **✅ done + verified (session 3):** `components/chat-web/mermaid-check.mjs`
   drives the real `chat.html` + bundled `mermaid.min.js` in **headless Microsoft Edge** (same
   engine family as the Eclipse WebView2 view) through the public bridge: flowcharts render to
   **SVG** (fallback chain finds the API: `api: yes`), broken diagrams **degrade visibly** with a
   `mermaid render FAILED` report, re-render after `__clear` works, zero page JS errors — 8/8 PASS,
   wired into `mvn verify` (SKIPs where Edge/puppeteer-core are absent). No SendKeys needed: the
   component-level proof replaces the unreliable in-IDE automation.
1. ~~**Wire MCP registration into the connection lifecycle**~~ **✅ done (session 3, code-level):**
   `OpencodeConnection` is an OSGi service (CoreActivator), `McpInfo` is an OSGi service (mcp DS),
   and `McpRegistrationComponent` (core) registers `eclipse-build` with the server when both are
   up — once per client+url, failures non-fatal + retried when services re-appear; 6 tests.
   **Remaining: live verification** — start Eclipse, check the log for registration, ask an agent
   "what build tools do you have?" (must list the 8 tools).
2. ~~**Phase 15 minimal slice — the Fleet loop end-to-end**~~ **✅ headless engine landed (session 3):**
   `com.opencode.ide.fleet` (Eclipse-free, import-banned like client/tools): `FleetTask`/`FleetJob`
   records + `FleetRunner` (`submit` → worktree + directory-scoped session + prompt;
   `awaitCompletion` polls session idle + final assistant text; `mergeBack` → MERGED or FAILED-on-
   conflict), 12 tests with fake client/worktree-manager. **Remaining:** task store record, SSE
   monitor (poll → event-driven), job-driven scheduling beyond one task, then the **Fleet view**
   (virtualized task×agent matrix, per-task status/diff/log, **"Take over" action** = read-only
   worktree inspection + chat resume; "Open in IDE" = import on demand) — the human otherwise
   stays in the main CDT worktree.
3. **Phase 9 expanded — Maven sprint planning:** task store schema grows `kind`
   (epic/milestone/task), `sprint`, `milestone`, `assignee` (agent id), `worktree`, `attempts`;
   `opencode-tasks:sync` mojo + MCP read/write tools (agents update their own tasks);
   `mvn opencode-tasks:plan` renders the sprint board (Markdown/HTML report). Language-agnostic —
   the store is plain JSON.
4. ~~**Key bindings (Eclipse-native)**~~ **✅ first cut landed (session 3):** default-scheme
   commands + rebindable bindings — `openPerspective` Ctrl+Alt+Shift+O, `refreshViews`
   Ctrl+Alt+Shift+R (ui), `openChat` Ctrl+Alt+Shift+C (chat; existing handler, now declarative).
   **Remaining:** Chat-view context bindings + more commands (newChat, focusFleet, takeoverTask)
   as those features land.
5. **Refactor cadence (process rule — clean architecture is a standing requirement):** every phase
   lands with tests; every second session starts by clearing the refactor backlog. **Cleared in
   session 3:** shared async-load+retry+error helper (`ViewLoadSupport`, unchecked failures no
   longer stick a view on "Loading…"); `HttpOpencodeClient` error semantics (bad/empty bodies →
   `OpencodeException` with endpoint+status+snippet); `Sse.parseFrames` trailing frame;
   `ConnectionConfig` URL validation (+trailing-slash normalization). **Still on the backlog:**
   `OpencodeConnection` holds its lock across a ≤60 s spawn on the UI thread; spawn prefs missing
   from the preference page; label providers can return `null`; **`ISecurePreferences`
   migration** — researched, drop-in.
   **Architecture migrations ([`ARCHITECTURE.md`](ARCHITECTURE.md)):** **M1–M3 landed**
   (2026-08-15): `client` split from `core` with a build-time Eclipse-import ban, chat renderer
   extracted to `components/chat-web`, ToolProvider SPI promoted to `tools` (mcp = endpoint only).
   Remaining: **M4** defer the `CodingAgent` port until a second agent backend actually arrives;
   follow-ups when a second language pack lands (extract `tools.cpp` into its own bundle) and the
   `ISecurePreferences` migration. The ARCHITECTURE.md review checklist applies every refactor
   session.
6. **Then resume the scale roadmap** at Phase 7 (multi-connection + virtualized viewers — the
   Fleet view at hundreds of tasks needs it).



## Consolidated TODO list (sorted by priority / dependency)

### Tier 0 — view polish (quick wins, no architecture change) · Phase 6.1 ✅ done
- ✅ Expand All / Collapse All toolbar actions on the Server tree.
- ✅ Subagent sessions collapsed by default (load shows server → Agents + top-level Sessions; subagents hidden until expanded or "Expand All").
- ✅ Distinct per-provider icons in the Providers view (deterministic colored badge with the provider's initial via `ProviderColors` + `ProviderIcons`). Official brand logos are a follow-up (asset/licensing).

### Tier 1 — scale foundation · Phase 7  *(SSE fan-out ✅ done; rest pending)*
- ✅ `/event` SSE subscription + fan-out in core (`OpencodeEventStream`, `Sse`); live Server view.
- ☐ Multi-connection `ConnectionsManager` (a list of servers: local spawn + remote connect).
- ☐ Virtualize viewers (`SWT.VIRTUAL`) for hundreds of sessions/messages.
- ☐ Core: request cache/throttle (one SSE stream per server already in place).

### Tier 2 — live session depth · Phase 8  *(basic live indicator ✅ done)*
- ✅ Live per-session activity indicator ("thinking" / "running tool" / "responding") via SSE.
- ☐ Double-click a session → dedicated session window (messages, parts, tools, tokens, todos).
- ☐ Session actions: abort, share, fork, revert.
- ☐ Show streamed text deltas inside the session window (chat-like transcript).

### Tier 3 — tasks, Maven sprint sync, agent bridge · Phase 9 ★ strategic
- Pull session todos (`/session/:id/todo`) → Eclipse Tasks, linked to the session.
- Project task store at `.opencode/tasks/` (JSON, version-controlled) — grown into a **sprint
  planning store**: `kind` (epic/milestone/task), `sprint`, `milestone`, `assignee`, `status`
  (`planned → assigned → running → gated → merged/failed/needs-human`), `worktree`, `attempts`.
- **Maven mojos** `opencode-tasks:sync` (team/CI convergence) and `opencode-tasks:plan`
  (render the sprint board as a Markdown/HTML report) — Maven plans, CMake builds.
- **MCP tools** exposing the store (`read_todos` / `write_todo` / `update_todo_status`) so
  **opencode agents read and write the shared Maven task/sprint list** — registered via
  `POST /mcp` (client method landed). Closes the "agents ↔ Maven planning" loop.
- **"Launch session from a todo"**: pick agent + model → worktree + `createSession(directory)` +
  prompt = task text → a working session for that task (feeds Phase 15 lanes).

### Tier 4 — native chat · Phase 12 ✅ working (mermaid ⚠ broken, fix in verification)
- ✅ Native chat view: markdown + **KaTeX math** + **syntax highlighting** (C/C++ first),
  streaming over SSE, multi-window + session resume, model **variant** selector, external links,
  capability **`system`** prompt so models format for the view unprompted.
- ⚠ **Mermaid**: fences render as nothing in real chats — API-global fallback + diagnostics
  deployed, awaiting live verification ("Recover next" §0).
- ☐ Abort button, tool-part rendering, official provider logos, copy-code button.

### Tier 5 — fleet orchestration & user takeover · Phases 13–15
- ✅ **Phase 13 first cut:** `WorktreeManager` (branch+worktree per task under
  `.git/opencode-fleet/`, serial merge-back with conflict abort) — the fleet's isolation layer.
- ✅ **Phase 14 first cut:** local MCP endpoint + `ToolProvider` SPI; C++ provider with
  cmake/ctest/run/debug/lint/format tools across MSVC + MSYS2 clang64/mingw64/ucrt64.
- ☐ **Phase 15:** fleet scheduler + **Fleet view** (task×agent matrix, virtualized): task →
  worktree → directory-scoped session → SSE monitor → build+test gate via MCP → serial merge →
  next task; failure policy (retry → `failed/needs-human`). Back-pressure (max lanes/server).
- ☐ **User takeover:** the human works in the main CDT worktree; "Take over" on a task = pause
  agent, inspect its worktree (diff/log), fix or steer via chat resume, then resume/reassign.
  Eclipse is the overview + control surface; agents run independently underneath.
- ☐ `ProjectContext` → active `ICProject` worktree; editor ↔ opencode (selection, `@file`,
  diagnostics markers).

### Dependencies (what blocks what)
- **Native chat (Tier 4)** needs SSE + message-sending primitives (Tier 2).
- **MCP todo bridge & launch-from-todo (Tier 3)** need the todo store + session-creation primitives.
- **Orchestration (Tier 5)** needs multi-connection + virtualization (Tier 1) + session detail (Tier 2).
- **Tier 0** is independent and can ship first.

## In flight (deployed, not yet live-verified)

- **`git` + `mcp` + `fleet` bundles + MCP registration (Phases 13/14/15 first cuts):** built,
  171 Java tests green, 9 jars deployed — but no Eclipse instance has started with them yet.
  First launch after deploy: check the workspace log for `eclipse-build MCP listening on
  http://127.0.0.1:<port>/mcp` (DS component) **and** the `eclipse-build` registration with the
  opencode server (`McpRegistrationComponent`); then ask an agent "what build tools do you
  have?" — it must list the 8 tools. Everything else from sessions 1–3 is verified.

## Phases — what, when

| Phase | Theme | Status | Depends on |
|---|---|---|---|
| **6** | UI consolidation + running agents (unified server tree, sessions w/ subagent nesting, flat providers, icons) | **done** | — |
| **6.5** | **SSE `/event` fan-out in core + live Server view + "thinking" indicator; clean-code refactor + 30 unit tests** | **done** | 6 |
| **6.1** | View polish: Expand/Collapse All, subagents collapsed by default, per-provider badge icons | **done** | 6 |
| **7** | Multi-connection & viewer scaling (SSE fan-out ✅; remaining: plural connections, `SWT.VIRTUAL`, cache/throttle) | next | 6 |
| **8** | Sessions detail window (messages/parts/tools/todos) + actions (basic live indicator ✅) | next | 6, 7 |
| **9** | Tasks + **Maven sprint planning** (milestones/epics/sprints) + MCP agent bridge + launch-from-todo | planned | 8 (todos) |
| **10** | Agent orchestration (run hundreds) — folded into Phase 15 | folded | — |
| **11** | Real CDT integration (user worktree, markers, editor bridge) | planned | 6 |
| **12** | Chat layer: **native markdown chat** (math + highlighting + **mermaid ✅ verified in real Edge**) | **working** | 8, 11 |
| **13** | **Agentic git**: worktree manager (branch/isolate/merge-back) | **first cut done** | — |
| **14** | **Build/run/debug/lint/format MCP bridge** (multi-toolchain, ToolProvider SPI; registration wired, live check pending) | **first cut done** | — |
| **15** | **Fleet orchestration + user takeover** (scheduler, Fleet view, take-over; headless `FleetRunner` ✅) | **engine done, UI planned** | 7, 9, 13, 14 |

### Phase 7 — Multi-connection & scaling  *(SSE fan-out ✅ done)*
- ✅ `/event` SSE stream in core (`OpencodeEventStream` + `Sse`) fanning events to listeners; the
  Server view is already live off it (no polling). Pure SSE parsing is unit-tested.
- ☐ `ConnectionsManager` (plural) replacing the `OpencodeConnection` singleton: register many servers
  (local spawn + remote connect); per-server health, agents, sessions, and one SSE stream each.
- ☐ `ServerView` shows multiple `Server` roots (one per connection).
- ☐ **Virtualize** Agents/Sessions/Providers viewers (lazy, `SWT.VIRTUAL`) + paginate sessions so
  hundreds load without blocking.
- ☐ Core request cache + throttle.
- ☐ Preferences become a **list of connections**, each with mode/url/auth/binary.

### Phase 8 — Sessions detail & live activity
- **Live per-session activity indicator** ("thinking…", "running tool: X", "idle") on the Sessions
  rows — driven by `/api/session/active` (`session.status` carries `{type: busy|idle|retry}`) and
  `/api/event` SSE (`session.reasoning.delta` = thinking, `session.tool.called` = running a tool).
  Near-term: short-poll `/api/session/active` to flip a "thinking" badge; full version streams the
  current activity.
- **Double-click a session → a dedicated session window/editor** (a new workbench part): messages
  (`/api/session/:id/message`), parts, tool calls, token/cost, todos — the live transcript of that run.
- Replace polling with **`/api/event` SSE** (`session.status`, `session.created`, `session.renamed`,
  `session.text.delta`, `session.usage.updated`) — one stream per server, fanned out by core.
- Session actions: interrupt (`/api/session/:id/interrupt`), fork, revert.
- **Per-provider official icons** in the Providers view (Anthropic/OpenAI/Google/…) to distinguish models.

### Phase 9 — Tasks & Maven sprint planning (sync + agent bridge) ★ strategic
- New bundle **`com.opencode.ide.tasks`**:
  - Pull opencode todos (`/session/:id/todo`) → Eclipse **Tasks**; link task ↔ session.
  - Map opencode `Todo {content, status, priority, id}` ↔ Eclipse task.
- **Project task/sprint store** at `.opencode/tasks/` (JSON, version-controlled) with
  `kind` (epic/milestone/task), `sprint`, `milestone`, `assignee`, `status`
  (`planned → assigned → running → gated → merged/failed/needs-human`), `worktree`, `attempts`.
- Maven module **`com.opencode.ide.tasks.maven`**:
  - `opencode-tasks:sync` — read/write the store at build time so CI and teammates converge.
  - `opencode-tasks:plan` — render the sprint board (per-sprint tasks, owners, burndown) as a
    Markdown/HTML report. **Maven plans, CMake builds** — the mojos never invoke the compiler.
- **MCP tools** (on our existing `eclipse-build` endpoint as a second `ToolProvider` — no new
  server): `read_todos` / `write_todo` / `update_todo_status` so **agents read and write the
  shared Maven task list** (`registerMcp` landed in core).
- **Launch session from a task:** "Run" picks agent + model → `WorktreeManager.create` +
  `createSession(title, worktreePath)` + prompt = task text → a working session (feeds Phase 15).
- **Task detail editor:** rich-markdown view of task/epic content (KaTeX/mermaid via the shared
  browser-page approach).
- Bidirectional **sync service** in core reconciling IDE ↔ store ↔ MCP ↔ server.

### Phase 13 — Agentic git (worktree fleet isolation) · first cut ✅
- ✅ `com.opencode.ide.git`: `WorktreeManager` — branch `opencode/<taskId>` + worktree at
  `.git/opencode-fleet/<taskId>` (never pollutes `git status`), list/find/remove, status
  (dirty count, HEAD), **mergeBack** (serial lane; conflicts → abort cleanly, report files).
- ✅ Core: `createSession(title, directory)` — sessions scoped to a worktree.
- ☐ `POST /project/git/init` wiring for agent-created repos; branch badges in the Server view;
  worktree pruning on startup (stale `opencode/*` branches).

### Phase 14 — Build/run/debug/lint MCP bridge · first cut ✅
- ✅ `com.opencode.ide.mcp`: DS component starts a **stateless Streamable HTTP MCP server**
  (127.0.0.1, `/mcp`, minimal JSON-RPC; spec-verified, opencode-compatible).
- ✅ **`ToolProvider` SPI** (`language()`, `tools()`, `call()`) — future `PythonToolProvider` etc.
  plug in beside `CppToolProvider`; constructor wiring today, ServiceLoader when it grows.
- ✅ **8 tools:** `toolchains_list`, `cmake_configure`, `cmake_build`, `ctest_run`, `run_binary`,
  `debug_batch` (gdb-batch; honest error + `pacman -S gdb` hint when absent), `lint_run`
  (clang-tidy needs `compile_commands.json` → tells the agent to configure first; cppcheck
  `--template=gcc`), `format_run` (clang-format check/apply, `style:"file"` → LLVM fallback).
- ✅ **ToolchainRegistry:** MSVC (vswhere → VS generator, no vcvars needed) + MSYS2
  clang64/mingw64/ucrt64 (Ninja), default msvc→clang64→mingw64→ucrt64; per-toolchain
  clang-tidy/clang-format/cppcheck detection with install hints; output capped, timeouts
  per tool, process-tree kill.
- ☐ Register with the server on connect ("Recover next" §1); agent-visible verification;
  tool allow-list preference (which tools agents may call); concurrent-build limits.

### Phase 15 — Fleet orchestration & user takeover (reworks Phase 10)
- **Minimal slice first** (no UI): one Job that walks the lane loop task → worktree →
  directory-scoped session → SSE monitor → gate (cmake_build + ctest_run green) → `mergeBack`
  → task status; failure policy: 1 retry → `failed/needs-human`.
- **Fleet view** (virtualized via Phase 7): task×agent matrix, per-task lane status, diff/log
  drill-down, build results; bulk actions (start/abort/retry).
- **User takeover:** the human works in the **main CDT worktree only**; agents never touch it.
  "Take over" on a task = pause the lane, inspect the task worktree (diff/log/chat resume),
  fix or steer, then resume/reassign. Conflict tasks land in a `needs-human` bucket.
- Back-pressure: max concurrent lanes per server, max queue depth, per-server resource limits.

### Phase 11 — Real CDT integration
- `ProjectContext` impl: resolve the active `ICProject` worktree → spawn cwd + `/project` context.
- Editor ↔ opencode: selection/file refs, `@file` insertion, opencode diagnostics → CEditor markers.

### Phase 12 — Chat layer (native markdown chat + optional TUI) · working ✅
- ✅ **Native chat view** (`com.opencode.ide.chat`): send via `POST /api/session/:id/prompt` (async —
  the POST returns the queued user message, not the reply), stream reply text over `/api/event` SSE
  (`session.text.delta`), render markdown + **KaTeX math** + **mermaid** + **highlight.js** from
  **bundled offline assets** (no CDN), dark/light
  theme sync, agent + model + **variant** pickers, per-view session, XSS-hardened.
- ✅ Assets are served by an embedded **localhost HTTP server** (`ChatWebServer`): `FileLocator.toFileURL`
  on a jar'd bundle extracts *single files*, so a `file://` page 404s all its relative assets.
- ✅ Java→JS goes through **`ChatScripts`** only (JSON *string literal* payloads); the page **guards and
  reports** every bridge call, because `Browser.execute()` returns `true` even when the script throws.
- ✅ Math is **extracted before markdown** (markdown otherwise destroys `\(…\)`, `\\` and multi-line `$$`).
- ✅ opencode v1.18.x workarounds: explicit valid model required (`DefaultModels` validates `/config`
  against the live provider list); JDK client forced to **HTTP/1.1** (server hangs on h2c upgrade POSTs);
  assistant message info carries **flat** `providerID`/`modelID`.
- ✅ **Multi-window chat + resume** via the `openChat` command (no ui→chat bundle dependency).
- ✅ **Live-verified** through the page's own render reports in the Eclipse log
  (`user bubble rendered` / `assistant bubble rendered (… meta=provider/model)`), not just Java-side
  dispatch — the earlier false "it works" came from trusting `execute()`'s return value.
- ☐ Abort, tool parts, copy-code button, provider logos, optional TUI terminal alongside.

## Backlog / cross-cutting

- **Key bindings (Eclipse-native):** commands + bindings in the default scheme (rebindable in
  Preferences → Keys) for openChat / newChat / focusFleet / takeoverTask; Chat-view context
  scopes. See "Recover next" §4.
- **Regular refactor sessions (process):** clean code/clean architecture is a standing requirement —
  every phase lands with tests, every second session starts on the refactor backlog (§5).
- **Rich markdown renderer** (shared `RichMarkdownRenderer`): SWT `Browser` widget + an offline-bundled
  HTML page (markdown-it + **KaTeX** for `$…$`/`$$…$$` + **mermaid.js** for ``` ```mermaid ```
  diagrams). Used by the task detail (P9), session detail (P8) and native chat (P12) so KaTeX and
  Mermaid render everywhere. Content is sanitized (no raw `<script>` from agent output); JS libs are
  bundled locally (no CDN) for offline/deterministic rendering. Requires the SWT Browser/WebView2.
- Security: `org.eclipse.equinox.security` (`ISecurePreferences`) for the password — researched,
  drop-in (`put(key, value, true)`); provider OAuth (`/provider auth`).
- **Multi-language packs:** Python/Rust/Go providers implement `ToolProvider` (mcp bundle SPI);
  toolchain detection stays per-language-pack.
- Structured logging to opencode via `/log` (service = `opencode-eclipse`).
- Theming.
- **CI**: Tycho on GitHub Actions producing the signed p2 update site (replaces manual dropins).
- Tests: extend `client.tests`; add `ui.tests` (Bot) and a live-server integration suite (guarded).

## Sequencing rationale

- **6 before 7:** consolidate the UI shape around "per-server" *before* generalizing to many servers.
- **7 before 15:** you can't show a fleet of hundreds without plural connections + virtualized viewers.
- **8 before 9:** tasks come *from* sessions; solid live session data (SSE) is the source of truth.
- **9 + 13 + 14 before 15:** the fleet loop is exactly store + worktree + MCP tools + scheduler —
  13/14 first cuts are landed; 9's store is the remaining input.
- **9 (Maven planning) is independent of 11/12** and high-value for team workflows.
- **11 and 12 are CDT/chat depth** and proceed once the metadata + sessions layers are stable.
