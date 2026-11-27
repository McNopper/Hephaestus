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
- **Currency (de-fleet):** the fleet era ended with U-110 — this document and
  `QUALITY.md` carry **no fleet references**: ban lists, the layer graph, the
  board row and the gate scope were re-verified against the manifests (a grep
  for `TaskFleet`, `FleetRunner`, `TakeoverRouter`, `DispatchScheduler`,
  `FleetView` and `fleet` finds nothing stale in either file; the surviving
  `FleetGit` / `StoreGitStatus` names are real classes in
  `com.opencode.ide.git`). Verification pass: T-088 (findings F-12..F-16,
  F-21..F-24).

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
client/git — skippable with `-DskipEclipseBan=true` — and the non-skippable
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
| `com.opencode.ide.tasks` (+ tests) | tools, gson | the Markdown task store (`.harness/tasks/`) - stationary tickets, the `basis` link graph, done-time artifact guard, basis-chain readiness (`StageReadiness`), `recordInvalidations` (upstream-changed history markers), the reduced `task_*` tool pack (21 tools; wandering verbs retired U-110, todos/backlog consolidated + `task_links` added in T-032, opt-in `auto_archive` U-060, `task_route_review_doubt` added in T-220), `TasksStdioMain` (stdio transport) |
| `com.opencode.ide.git` (+ tests) | client | `WorktreeManager` + `FleetGit` conventions (branch/worktree naming, `STORE_PATH`) over the git CLI; `RepoGate` (repo-root-keyed single-writer gate serializing ALL main-tree mutations — create/commit/merge/sync — across every engine in the process); `StoreGitStatus`/`StoreSync` (distributed-fleet store status + commit→pull-rebase→push discipline, **path-scoped to the store subtree, transient `.lock` excluded**) |
| `mojo/opencode-tasks` (plain maven-plugin, not OSGi) | tasks (plain jar), gson, maven-api | `opencode-tasks:sync` / `:plan` over the same store — Maven plans, CMake builds |

### Tier 2 — the Eclipse harness (OSGi bundles, thin adapters + UI)

| Module | Depends on | What it is |
|---|---|---|
| `com.opencode.ide.core` (+ tests) | client, **mcp**, equinox.security, eclipse runtime | the Eclipse adapter: preferences (secure remote credentials), `OpencodeConnection` + `ConnectionsManager` (plural), `ClientLog`/`ProjectContext` service glue, MCP registration, `TasksRootResolution` (the ONE task-store root order, B-016) + its bridge into the endpoint, the repo nature + adoption logic (`OpenCodeRepoNature`/`RepoProjectSetup`, O-001) |
| `com.opencode.ide.mcp` (+ tests) | tools, **tasks**, client, gson | the `eclipse-build` MCP endpoint (Streamable HTTP, 127.0.0.1, per-start token auth: registered `?token=` URL or `Bearer` header, 401 otherwise) + DS lifecycle only; service-driven activation (activates when core binds `McpInfo`) |
| `com.opencode.ide.ui` (+ tests) | client, core, tools, gson, workbench | Server (multi-root, virtualized, MCP/skills/sessions with subagent nesting) + Providers + Repo + Session-details views (subagent + shell-task sections, revert/environment/import, view marking), perspective, connection preference page, saved-permissions/integrations (catalog + connect flows)/skill-attach surfaces, `ui.attention` event-stream notifications (IStartup); `ViewLoadSupport` |
| `com.opencode.ide.chat` (+ tests) | client, core, tasks, workbench, gson | the Browser host for `components/chat-web` (`ChatPage` + SWT-free `ChatSessionController` + embedded web server); built-in slash commands, `@`-file fuzzy references and cross-restart continuity (`ChatViewSettings`); does NOT depend on ui |
| `com.opencode.ide.board` (+ tests) | client, core, tasks, git, chat, gson, workbench | the PM surface: Board (flat + V-pipeline layouts, stage filter, blocked rendering, stage-journey trace + wave digest (U-026), Needs-me/Bugs-only/stage/status filters, refresh, store git header + *Sync store*, new/reset/adopt project actions, archive action; SWT-free model (`BoardModel`, `TaskStoreWatcher`) unit-tested) + **intake governance**: the store's stage field drives the V-pipeline layout; the fleet-era surfaces (cost overview, launch/take-over/shutdown, Auto/Waves pumps, the Fleet tree) were removed in U-110 - the orchestration moved to the ticker-v schedule |
| `com.opencode.ide.cdt` (+ tests) | core, CDT bundles | the CDT bridge (tier 3) |

### Tier 3 — C++-development specifics

| Module / package | What it is |
|---|---|
| `tools` bundle → `tools.cpp` package | `CppToolProvider`: toolchain detection (MSVC via vswhere, MSYS2 envs), cmake configure/build, ctest, run, gdb-batch, clang-tidy/cppcheck, clang-format — behind the `ToolProvider` SPI; extracted into its own bundle when a second language pack lands |
| `com.opencode.ide.cdt` | `CdtProjectContext` (active `ICProject` → spawn cwd) + `DiagnosticsMarkers`/`MarkerApplier` |

**Verified dependency graph (Require-Bundle / maven edges, no cycles):**

```text
client → gson, {jobs, equinox.common}      ← the JobManager runtime allowlist
tools  → gson
tasks  → tools, gson
git    → client

mcp    → tools, tasks, client, gson
core   → client, mcp, {runtime, resources, equinox.security}
ui     → client, core, tools, gson, {workbench}
chat   → client, core, tasks, gson, {workbench}   ← no ui edge
board  → client, core, tasks, git, chat, gson, {workbench, compare}   ← no ui edge
cdt    → core, {cdt.core, resources, ui, ui.ide}
mojo   → tasks (plain jar), gson, maven-api (provided)
```

Cross-tier rules (review checklist): tier-2/3 may depend on tier 1, never the reverse;
`x-friends` on `internal` packages only ever names `.tests` fragments; language-specific
logic stays in `tools.cpp`/cdt behind the SPI. The task store's single root is resolved by ONE
core seam — `com.opencode.ide.core.TasksRootResolution` (explicit Board override → workspace
climb → open-project repo adoption → `tasksRoot` preference → historical guess; the order is
unit-pinned by `TasksRootResolutionTest`) — and the core activator bridges that resolution into
the endpoint (`opencode.tasks.root`), so Board and in-session `task_*` tools always see
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

## Session Details rendering — Eclipse constructs reused (moved content)

Preserved from the retired as-built record
`../docs/fleet-observability-eclipse-reuse.md` (RETIRED 2026-11-14; T-215's
marker kept this part current, T-233 moved it here on 2026-11-26 when the
record was reduced to a pointer). It answers, for the surviving Session Details
view and its consoles, the same question this document answers for the bundles:
which Eclipse constructs are reused instead of custom code, and which platform
options were rejected.

**Prime rule applied:** never build in the plugin what the platform already
provides. Every observability need maps to an Eclipse construct that
already solves it, or is called out as genuinely custom.

| Need | Eclipse construct reused | Where it lives |
|---|---|---|
| Transcript drill-down for any session node | `SessionDetailsView` (`allowMultiple`, Auto Refresh) | serves any ordinary session of its server, chat sessions included (the `SessionDetailsView` class javadoc: "serves chat sessions and fleet worker sessions - both are ordinary sessions of their server") |
| Transcript viewing (one message or the whole transcript) | the workbench's default text editor over a `FileStoreEditorInput` (EFS file store; the modern workbench removed `IStorageEditorInput`) | read-only snapshot: edits change only the delete-on-exit temp copy, never the session; no live follow (the `SessionDetailsView` class javadoc, Batch C paragraph; `SessionTranscript`/`SessionTranscriptFiles`) |
| Shell-task output | a real Eclipse console per task (`org.eclipse.ui.console`) | `ShellTasksConsole` from Session Details (the `SessionDetailsView` class javadoc, U-041 paragraph), generalizing the `AgentToolsConsole` pattern (U-009) |
| Subagent nesting | `parentID`-driven sections above the transcript | Session Details live sections (U-041) |
| Progress/busy | the Jobs API (`ViewLoadSupport` / `Job.create`, `setSystem(true)`) | long view operations (transcript loads, lifecycle actions) run as background `Job`s off the UI thread - system jobs by design, so they never surface in the Progress view |

Platform decisions (verdicts as built):

| Option | Verdict |
|---|---|
| **Debug platform** (`ILaunch`, Debug view) for a session/job tree | **Rejected**: drags in launch configs, debug-model semantics and new dependencies for zero user gain over a plain `TreeViewer` + console |
| CNF (Common Navigator) for the session tree | **Rejected**: CNF serves resource navigators; a session/job tree is a model tree — plain `TreeViewer` is simpler |
| `org.eclipse.ui.console` for task output | **Adopted** (see the table above) |
| TM Terminal for interactive PTY input | **Parked**: read-only output tails ship in consoles/`MessageConsole`; the interactive host is the remaining "Adopt next" item in `opencode-v2-adoption.md` |

## The web bridge contract (chat-web's public API)

The renderer is hostable anywhere that can (a) serve static files over HTTP and (b) call JS with
string arguments. The contract itself — every host→page and page→host entry point (including
the block-level progressive streaming, session-inbox, @-autocomplete, question-form and
doc-mode surfaces), the payload forms, and the rendering rules that ARE the contract (math
extracted before markdown, the mermaid fallback chain, XSS hardening) — lives in, and is
versioned with, the component: **`components/chat-web/README.md`** (the consumer doc). It is
enforced by the component's checks inside the chat bundle's `mvn verify`:
`renderer-check.mjs` (75) + `bridge-check.mjs` (255) + `mermaid-check.mjs` (12 - drives the
real page in headless Microsoft Edge via `puppeteer-core`; SKIPs when Edge/puppeteer-core are
absent); `-DskipNodeChecks=true` skips them, and `bridge-check.mjs` honours a `WEB_DIR` env
override.

## Review checklist (every refactor/PR session)

1. Did any `org.eclipse.*`/`org.osgi.*` import creep into an Eclipse-free module? (build check)
2. Did any language-specific logic (cmake/clang/msvc paths…) escape a `ToolProvider`?
3. Did the web bridge contract change without a `bridge-check.mjs` update?
4. Did any new opencode DTO/endpoint leak outside the client layer?
5. Is every new module position on the four axes documented here?
