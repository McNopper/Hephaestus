# Architecture — opencode-eclipse

The modular architecture of the **agentic C++ harness**. This document defines the separation
axes, the current state (verified against manifests/imports), and the deliberate deferrals.
It is the reference for every refactor session (see the review checklist at the bottom and
the repo `../ROADMAP.md`).

## About this document

- **Kind:** `doc` / architecture reference for the `eclipse/` reactor.
- **Read by:** anyone changing bundle boundaries or adding a module; **written by:**
  maintainers.
- **Related:** `README.md` (bundle map), `QUALITY.md` (the build-enforced rules),
  `components/chat-web/README.md` (the web bridge contract), `../ROADMAP.md`.

## The four separation axes

Code is separated along four independent axes. A module's position on each axis is a **deliberate,
reviewed decision**, not an accident:

1. **Eclipse vs. non-Eclipse (Java):** pure Java must stay usable *outside* Eclipse/OSGi — as a
   plain library, in a CLI, or under another IDE. Eclipse APIs (SWT/JFace/Workbench/preferences/
   equinox) are allowed only in the UI layer.
2. **Java vs. non-Java:** the web renderer (HTML/JS/CSS + KaTeX/highlight.js/mermaid) is a
   standalone front-end component with a **versioned bridge contract** — reusable in any host
   (Eclipse Browser, VS Code webview, plain web page) without Java.
3. **Development environment packs (C++ today, Python/other later):** everything language-specific
   (toolchains, build systems, linters, debuggers) lives behind the **`ToolProvider` SPI**. The
   harness core never knows which language it is driving.
4. **Coding-agent backend (opencode today, others later):** all agent interaction goes through the
   core client layer. The seam for a second backend is the client interface + event model; we do
   NOT pre-build a generic abstraction — we keep the boundary clean so a `CodingAgent` port can be
   extracted when a second implementation actually arrives (rule: second implementation justifies
   the abstraction; until then we document, not speculative-build).

## Module organization - the three tiers (verified against manifests/poms)

Every module sits in exactly one tier. **Tier 1** is plain Java, build-enforced
Eclipse/OSGi-free (pwsh import scans in the bundle poms: `ban-eclipse-imports` in
client/git/fleet — skippable with `-DskipEclipseBan=true` — and the non-skippable
`check-eclipse-free` in tools/tasks) — usable
as plain libraries (the mojo and the stdio launchers prove it). Client's one allowlisted
platform dependency is the **JobManager runtime** (`org.eclipse.core.jobs` +
`org.eclipse.equinox.common`, both plain-JVM-safe): `WorkerPools` is a thin `JobGroup`
bridge, never a home-grown thread pool. **Tier 2** is the Eclipse harness.
**Tier 3** carries the C++-development specifics; everything else is language-agnostic behind
the `ToolProvider` SPI.

### Tier 1 — reusable plain Java (Eclipse-free, build-enforced)

| Module | Depends on | What it is |
|---|---|---|
| `com.opencode.ide.client` (+ tests) | gson + the JobManager runtime (`org.eclipse.core.jobs`, `org.eclipse.equinox.common`) | opencode REST/SSE client, DTOs, `ChatRequests`/`McpRequests`, `OpencodeEventStream`, server launcher; hardened error semantics; `RuntimeTuning` (live poll/stall/budget/worker knobs) + `WorkerPools` (one bounded shared worker pool over the JobManager — no thread per call) |
| `com.opencode.ide.tools` (+ tests) | gson | `ToolProvider` SPI + `McpDispatcher` (JSON-RPC) — language-agnostic; the C++ pack lives beside it (tier 3) |
| `com.opencode.ide.tasks` (+ tests) | tools, gson | the Markdown task store (`.opencode/tasks/`), the V-pipeline (`VStages`, advance/sendBack), `StageReadiness` (pure dataflow readiness) + `recordInvalidations` (upstream-changed history markers), the `task_*` tool pack (incl. `task_readiness`), `TasksStdioMain` (stdio transport) |
| `com.opencode.ide.git` (+ tests) | client | `WorktreeManager` + `FleetGit` conventions (branch/worktree naming, `STORE_PATH`) over the git CLI; `RepoGate` (repo-root-keyed single-writer gate serializing ALL main-tree mutations — create/commit/merge/sync — across every engine in the process); `StoreGitStatus`/`StoreSync` (distributed-fleet store status + commit→pull-rebase→push discipline, **path-scoped to the store subtree, transient `.lock` excluded**) |
| `com.opencode.ide.fleet` (+ tests) | client, git, tasks, tools, gson | the fleet engine: `FleetRunner` (`begin`: async prompt POST on the shared bounded `WorkerPools` — the worker is interrupted when the run settles, so a blocked POST can never starve the pool — + watchdog probes + guarded mergeBack), `TaskFleet` (V-pipeline launch loop, stall watchdog with permission-pause), `RoleAgents` dispatch, `SelfClaimPrompt`, `FleetTuning` knobs (runtime-tunable live via `RuntimeTuning`), telemetry, `PermissionQueue`/`FleetPermissionBridge` (unattended permission gating), `GlobalEventsAggregator` (merged cross-connection event feed); chat-first control plane: `FleetControl`/`FleetToolProvider`/`FleetStdioMain` (the `fleet_*` stdio server). There is **no detached fleet daemon**: the automatic pump (auto-dispatch / recurring waves) runs in whichever host starts it — the Eclipse Board (its `DispatchScheduler` over the JobManager) or the fleet stdio JVM (`fleet_auto_start` / `fleet_waves_start`) — and pumps in that host's `FleetControl` for as long as that host runs |
| `mojo/opencode-tasks` (plain maven-plugin, not OSGi) | tasks (plain jar), gson, maven-api | `opencode-tasks:sync` / `:plan` over the same store — Maven plans, CMake builds |

### Tier 2 — the Eclipse harness (OSGi bundles, thin adapters + UI)

| Module | Depends on | What it is |
|---|---|---|
| `com.opencode.ide.core` (+ tests) | client, **mcp**, equinox.security, eclipse runtime | the Eclipse adapter: preferences (secure remote credentials), `OpencodeConnection` + `ConnectionsManager` (plural), `ClientLog`/`ProjectContext` service glue, MCP registration, `TasksRootResolution` (the ONE task-store root order, B-016) + its bridge into the endpoint, the repo nature + adoption logic (`OpenCodeRepoNature`/`RepoProjectSetup`, O-001) |
| `com.opencode.ide.mcp` (+ tests) | tools, **tasks**, client, gson | the `eclipse-build` MCP endpoint (Streamable HTTP, 127.0.0.1, per-start token auth: registered `?token=` URL or `Bearer` header, 401 otherwise) + DS lifecycle only; service-driven activation (activates when core binds `McpInfo`) |
| `com.opencode.ide.ui` (+ tests) | client, core, tools, gson, workbench | Server (multi-root, virtualized, MCP/skills/sessions with subagent nesting) + Providers + Repo + Session-details views (subagent + shell-task sections, revert/environment/import, view marking), perspective, connection preference page, saved-permissions/integrations (catalog + connect flows)/skill-attach surfaces, `ui.attention` event-stream notifications (IStartup); `ViewLoadSupport` |
| `com.opencode.ide.chat` (+ tests) | client, core, workbench, gson | the Browser host for `components/chat-web` (`ChatPage` + SWT-free `ChatSessionController` + embedded web server); built-in slash commands, `@`-file fuzzy references and cross-restart continuity (`ChatViewSettings`); does NOT depend on ui |
| `com.opencode.ide.board` (+ tests) | client, core, tasks, fleet, git, chat, gson, workbench | the PM surface: Board (flat + V-pipeline layouts, stage filter, blocked rendering, stage-journey trace + wave digest (U-026), cost overview, *Auto ▶* self-draining dispatch via `DispatchScheduler`/`AutoDispatch`/`StageReadiness`, dispatch settings, store git header + *Sync store*, repo adoption action) + Fleet views (U-040 tree: engine → wave → job → session → subagents → shell tasks via `FleetTree` + `SessionObserver`, server diff, permissions dialog, events dialog; *Take over* opens the worktree and marks the job taken over — `TakeoverRouter` still carries the v1 TUI steering path, but v2 removed `POST /tui/:action`, so `tuiAction` is always false and the fallback always wins), fleet-row graceful Shutdown + activation refresh/freshness stamp; SWT-free model unit-tested |
| `com.opencode.ide.cdt` (+ tests) | core, CDT bundles | the CDT bridge (tier 3) |

### Tier 3 — C++-development specifics

| Module / package | What it is |
|---|---|
| `tools` bundle → `tools.cpp` package | `CppToolProvider`: toolchain detection (MSVC via vswhere, MSYS2 envs), cmake configure/build, ctest, run, gdb-batch, clang-tidy/cppcheck, clang-format — behind the `ToolProvider` SPI; extracted into its own bundle when a second language pack lands |
| `com.opencode.ide.cdt` | `CdtProjectContext` (active `ICProject` → spawn cwd) + `DiagnosticsMarkers`/`MarkerApplier` |

**Verified dependency graph (Require-Bundle / maven edges, no cycles):**

```
client → gson, {jobs, equinox.common}      ← the JobManager runtime allowlist
tools  → gson
tasks  → tools, gson
git    → client
fleet  → client, git, tasks, tools, gson
mcp    → tools, tasks, client, gson
core   → client, mcp, {runtime, resources, equinox.security}
ui     → client, core, tools, gson, {workbench}
chat   → client, core, gson, {workbench}   ← no ui edge
board  → client, core, tasks, fleet, git, chat, gson, {workbench, compare}   ← no ui edge
cdt    → core, {cdt.core, resources, ui, ui.ide}
mojo   → tasks (plain jar), gson, maven-api (provided)
```

Cross-tier rules (review checklist): tier-2/3 may depend on tier 1, never the reverse;
`x-friends` on `internal` packages only ever names `.tests` fragments; language-specific
logic stays in `tools.cpp`/cdt behind the SPI. The task store's single root is resolved by ONE
core seam — `com.opencode.ide.core.TasksRootResolution` (explicit Board override → workspace
climb → open-project repo adoption → `tasksRoot` preference → historical guess; the order is
unit-pinned by `TasksRootResolutionTest`) — and the core activator bridges that resolution into
the endpoint (`opencode.tasks.root`), so Board, fleet and in-session `task_*` tools always see
one store (B-016; doc currency per T-016).
Agent backends (axis 4):  opencode HttpOpencodeClient (today) · the deferred `CodingAgent` port below

### Deliberate deferrals

- **Second coding-agent backend:** when a second backend is seriously evaluated, extract a
  `CodingAgent` port (createSession/sendMessage/event stream/cancel) from `OpencodeClient` +
  `OpencodeEventStream`. Until then: keep client's API small; do not speculative-build the
  abstraction.
- **Second language pack:** when one lands, extract `tools.cpp` into its own bundle depending
  on `tools` only (the `ToolProvider` SPI is the seam).

## Live activity derivation (`client.activity` — ported from the retired opencode-viewer)

`ActivityTracker` consumes the same `/api/event` SSE stream the Server view subscribes to and derives:
per-session `running` (from `session.status`, whose `data.status` is an object `{type: busy|idle|retry}`
in v2, vs `session.idle`/deleted), `thinking` (`session.reasoning.started` on, `reasoning.ended`/
`session.text.started`/any tool event off), tool invocations (v2 flat events: `session.tool.called`/
`tool.input.started` carry `id`, `name` and `input` → RUNNING; `session.tool.success`/`tool.failed`
carry only the invocation `id` → COMPLETED/ERROR, so the tracker **inherits name and file from the
tracked invocation**), and the **active-files map** — a file (first non-blank of
`input.filePath|path|file|absolutePath`) is listed while its tool is RUNNING and removed on
COMPLETED/ERROR. Snapshots are immutable; listeners fire only on real changes. The ui Server
view renders `thinking…` / `tool: <name> — <file>` labels and the "Active files" node from it.

## The web bridge contract (chat-web's public API)

The renderer is hostable anywhere that can (a) serve static files over HTTP and (b) call JS with
string arguments. The contract itself — every host→page and page→host entry point (including
the block-level progressive streaming, session-inbox, @-autocomplete, question-form and
doc-mode surfaces), the payload forms, and the rendering rules that ARE the contract (math
extracted before markdown, the mermaid fallback chain, XSS hardening) — lives in, and is
versioned with, the component: **`components/chat-web/README.md`** (the consumer doc). It is
enforced by the component's checks inside the chat bundle's `mvn verify`:
`renderer-check.mjs` (58) + `bridge-check.mjs` (194) + `mermaid-check.mjs` (8 — drives the
real page in headless Microsoft Edge via `puppeteer-core`; SKIPs when Edge/puppeteer-core are
absent); `-DskipNodeChecks=true` skips them, and `bridge-check.mjs` honours a `WEB_DIR` env
override.

## Review checklist (every refactor/PR session)

1. Did any `org.eclipse.*`/`org.osgi.*` import creep into an Eclipse-free module? (build check)
2. Did any language-specific logic (cmake/clang/msvc paths…) escape a `ToolProvider`?
3. Did the web bridge contract change without a `bridge-check.mjs` update?
4. Did any new opencode DTO/endpoint leak outside the client layer?
5. Is every new module position on the four axes documented here?
