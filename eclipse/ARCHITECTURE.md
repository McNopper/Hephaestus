# Architecture — opencode-eclipse

The modular architecture of the **agentic C++ harness**. This document defines the separation
axes, the current state (verified against manifests/imports), and the migration steps toward the
target. It is the reference for every refactor session (see ROADMAP "process rules").

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

## Module organization — the three tiers (verified against manifests/poms 2026-08-20)

Every module sits in exactly one tier. **Tier 1** is plain Java, build-enforced Eclipse/OSGi-free
(pwsh import ban in each Tier-1 bundle pom, unconditional on every OS;
`-DskipEclipseBan=true` skips it in client/git/fleet — the tools/tasks bans are not skippable) — usable
as plain libraries (the mojo and the stdio launcher prove it). **Tier 2** is the Eclipse harness.
**Tier 3** carries the C++-development specifics; everything else is language-agnostic behind
the `ToolProvider` SPI.

### Tier 1 — reusable plain Java (Eclipse-free, build-enforced)

| Module | Depends on | What it is |
|---|---|---|
| `com.opencode.ide.client` (+ tests) | gson | opencode REST/SSE client, DTOs, `ChatRequests`/`McpRequests`, `OpencodeEventStream`, server launcher; hardened error semantics |
| `com.opencode.ide.tools` (+ tests) | gson | `ToolProvider` SPI + `McpDispatcher` (JSON-RPC) — language-agnostic; the C++ pack lives beside it (tier 3) |
| `com.opencode.ide.tasks` (+ tests) | tools, gson | the Markdown task store (`.opencode/tasks/`), the V-pipeline (`VStages`, advance/sendBack), `StageReadiness` (pure dataflow readiness) + `recordInvalidations` (upstream-changed history markers), the `task_*` tool pack (incl. `task_readiness`), `TasksStdioMain` (stdio transport) |
| `com.opencode.ide.git` (+ tests) | — | `WorktreeManager` + `FleetGit` conventions (branch/worktree naming, `STORE_PATH`) over the git CLI; `RepoGate` (repo-root-keyed single-writer gate serializing ALL main-tree mutations — create/commit/merge/sync — across every engine in the process); `StoreGitStatus`/`StoreSync` (distributed-fleet store status + commit→pull-rebase→push discipline, **path-scoped to the store subtree, transient `.lock` excluded**) |
| `com.opencode.ide.fleet` (+ tests) | client, git, tasks, gson | the fleet engine: `FleetRunner` (`begin`: async prompt POST + watchdog probes + guarded mergeBack), `TaskFleet` (V-pipeline launch loop, stall watchdog with permission-pause), `RoleAgents` dispatch, `SelfClaimPrompt`, `FleetTuning` knobs, telemetry, `PermissionQueue`/`FleetPermissionBridge` (unattended permission gating), `GlobalEventsAggregator` (merged cross-connection event feed); chat-first control plane: `FleetControl`/`FleetToolProvider`/`FleetStdioMain` (the `fleet_*` stdio server, `FLEET_DAEMON=off/auto/always` attach mode) + the V-006 daemon (`FleetDaemon` authed TCP core, `FleetDaemonProxy`/`DaemonProwl` client attach — engine outlives any client) |
| `mojo/opencode-tasks` (plain maven-plugin, not OSGi) | tasks (plain jar), gson, maven-api | `opencode-tasks:sync` / `:plan` over the same store — Maven plans, CMake builds |

### Tier 2 — the Eclipse harness (OSGi bundles, thin adapters + UI)

| Module | Depends on | What it is |
|---|---|---|
| `com.opencode.ide.core` (+ tests) | client, **mcp**, equinox.security, eclipse runtime | the Eclipse adapter: preferences (secure remote credentials), `OpencodeConnection` + `ConnectionsManager` (plural), `ClientLog`/`ProjectContext` service glue, MCP registration, tasksRoot bridge into the endpoint |
| `com.opencode.ide.mcp` (+ tests) | tools, **tasks**, gson | the `eclipse-build` MCP endpoint (Streamable HTTP, 127.0.0.1, per-start token auth: registered `?token=` URL or `Bearer` header, 401 otherwise) + DS lifecycle only; service-driven activation (activates when core binds `McpInfo`) |
| `com.opencode.ide.ui` (+ tests) | client, core, workbench | Server (multi-root, virtualized, MCP/skills/sessions) + Providers + Repo + Session-details views, perspective, connection preference page; `ViewLoadSupport` |
| `com.opencode.ide.chat` (+ tests) | client, core, workbench, gson | the Browser host for `components/chat-web` (`ChatPage` + SWT-free `ChatSessionController` + embedded web server); does NOT depend on ui |
| `com.opencode.ide.board` (+ tests) | client, core, tasks, fleet, git, chat, gson, workbench | the PM surface: Board (flat + V-pipeline layouts, stage filter, blocked rendering, cost overview, *Auto ▶* self-draining dispatch via `DispatchScheduler`/`AutoDispatch`/`StageReadiness`, dispatch settings, store git header + *Sync store*) + Fleet views (jobs, server diff, permissions dialog, events dialog, TUI-first take-over); SWT-free model unit-tested |
| `com.opencode.ide.cdt` (+ tests) | core, CDT bundles | the CDT bridge (tier 3) |

### Tier 3 — C++-development specifics

| Module / package | What it is |
|---|---|
| `tools` bundle → `tools.cpp` package | `CppToolProvider`: toolchain detection (MSVC via vswhere, MSYS2 envs), cmake configure/build, ctest, run, gdb-batch, clang-tidy/cppcheck, clang-format — behind the `ToolProvider` SPI; extracted into its own bundle when a second language pack lands |
| `com.opencode.ide.cdt` | `CdtProjectContext` (active `ICProject` → spawn cwd) + `DiagnosticsMarkers`/`MarkerApplier` |

**Verified dependency graph (Require-Bundle / maven edges, no cycles):**

```
client → gson
tools  → gson
tasks  → tools, gson
git    → (none)
fleet  → client, git, tasks, gson
mcp    → tools, tasks, gson
core   → client, mcp, equinox.security, eclipse.core.runtime
ui     → client, core, {workbench}
chat   → client, core, {workbench}, gson        ← no ui edge
board  → client, core, tasks, fleet, git, chat, gson, {workbench}
cdt    → core, {cdt.core, resources, ui, ui.ide}
mojo   → tasks (plain jar), gson, maven-api (provided)
```

Cross-tier rules (review checklist): tier-2/3 may depend on tier 1, never the reverse;
`x-friends` on `internal` packages only ever names `.tests` fragments; language-specific
logic stays in `tools.cpp`/cdt behind the SPI. The task store's single root is bridged from
core's preference into the endpoint (`opencode.tasks.root`), so Board, fleet and in-session
`task_*` tools always see one store.
Agent backends (axis 4):  opencode HttpOpencodeClient (today) · M4 below
```

### Migration status & follow-ups

- **M1 ✅ (2026-08-15):** `client` split out (69 tests moved, core.tests deleted); Eclipse-import
  ban enforced in the client pom (`-DskipEclipseBan=true` skips). `OpencodeServerLauncher` and
  `OpencodeEventStream` are public in client (core constructs them directly — no extra
  interfaces invented); only `HttpOpencodeClient`/`Auth` stay internal. Follow-up candidates:
  eclipse-ize only when needed — `ClientLog` adapter lives in core's activator.
- **M2 ✅ (2026-08-15):** `components/chat-web` extracted (70 web files, checks moved + green,
  standalone README = consumer doc); chat bundle copies the web assets into its jar at
  process-resources; jar packaging verified identical. The bridge contract section below is now
  **verified against the implementation** (earlier drafts had wrong entry-point names).
- **M3 ✅ (2026-08-15):** SPI + dispatcher + C++ pack moved to `tools` (23 tests), mcp keeps
  endpoint + DS (6 tests); Eclipse-import ban enforced in the tools pom. When a second language
  pack arrives, extract `tools.cpp` into its own bundle depending on `tools` only.
- **M4 — deferred (unchanged):** when a second coding-agent backend is seriously evaluated,
  extract a `CodingAgent` port (createSession/sendMessage/event stream/cancel) from
  `OpencodeClient` + `OpencodeEventStream`. Until then: keep client's API small; do not
  speculative-build the abstraction.

## Live activity derivation (`client.activity` — ported from the retired opencode-viewer)

`ActivityTracker` consumes the same `/event` SSE stream the Server view subscribes to and derives:
per-session `running` (from `session.status` busy/retry vs `session.idle`/deleted), `thinking`
(`message.part.updated` with `part.type=="reasoning"` on, any other non-tool part off), tool
invocations (`part.type=="tool"`: name from `part.tool`, state from `part.state.status`
running/completed/error, null ⇒ running), and the **active-files map** — a file (first non-blank
of `part.input.filePath|path|file|absolutePath`) is listed while its tool is RUNNING and removed
on COMPLETED/ERROR. Snapshots are immutable; listeners fire only on real changes. The ui Server
view renders `thinking…` / `tool: <name> — <file>` labels and the "Active files" node from it.

## The web bridge contract (chat-web's public API)

The renderer is hostable anywhere that can (a) serve static files over HTTP and (b) call JS with
string arguments. Contract (enforced by `bridge-check.mjs`, 94 checks):

- **Host → page** (via `ChatScripts`; payloads are JSON *string literals* — objects tolerated;
  every entry point is guarded, returns `true`/`false`, reports errors via
  `JS ERROR in <fn>: …` so nothing fails silently the way `Browser.execute()` does):
  `__setTheme(theme)` and `__setNotice(text)` take **plain strings**; data calls are
  `__appendUser({text})`, `__startAssistant({mid})`,
  `__setAssistantText({mid, text, reasoning?, meta?, tools?})` (`tools` = optional
  `[{name, state}]` rendering as compact `tool: name — state` lines),
  `__appendDelta({mid, text})` (raw text stream + cursor, no markdown),
  `__setMessages([…])` (history, same per-entry shape incl. `tools`), `__clear()`,
  `__stopStream({mid})` (removes the streaming cursor when a send settles/fails/aborts —
  idempotent, keeps the streamed text).
  (`__linkClick(event)` exists but is the page's internal test-exposed click interceptor.)
- **Page → host:** `__javaReport(type, detail)` — `page-ready` flush signal, render
  confirmations (`user/assistant bubble rendered…`, `history rendered (N entries)`,
  `mermaid initialised/diagram rendered`, `KaTeX failed…`), JS errors; and
  `__javaOpenExternal(url)` for link clicks.
- **Rendering rules that ARE the contract:** math extracted before markdown (KaTeX; pass skips
  fenced/inline code and currency like `$5`; `throwOnError:false` → visible `<code class="error">`
  degradation), mermaid via the `mermaidApi()` fallback chain (`window.mermaid` →
  `globalThis.mermaid` → `globalThis.__esbuild_esm_mermaid.default`; missing mermaid leaves the
  visible source, not a gap), highlight.js for code fences with the `#hljs-light`/`#hljs-dark`
  theme pair toggled by `__setTheme` (which also resets mermaid for themed re-init), markdown
  `html:false` + mermaid `securityLevel:strict` (XSS hardening), single scrolling container.
- **Checks:** `components/chat-web` runs `renderer-check.mjs` (51) + `bridge-check.mjs` (94) +
  `mermaid-check.mjs` (8 — drives the real page in **headless Microsoft Edge** via
  `puppeteer-core`: diagrams must render to SVG, broken sources degrade visibly, no silent drops;
  SKIPs when Edge/puppeteer-core are absent) — all wired into the chat bundle's `mvn verify`
  (`-DskipNodeChecks=true` skips); `bridge-check.mjs` honours a `WEB_DIR` env override.

## Review checklist (every refactor/PR session)

1. Did any `org.eclipse.*`/`org.osgi.*` import creep into an Eclipse-free module? (build check)
2. Did any language-specific logic (cmake/clang/msvc paths…) escape a `ToolProvider`?
3. Did the web bridge contract change without a `bridge-check.mjs` update?
4. Did any new opencode DTO/endpoint leak outside the client layer?
5. Is every new module position on the four axes documented here?
