# opencode-v2-adoption.md - the strict reuse policy and the v2 adoption matrix

## About this document

- **Kind:** `doc` / architecture policy + technology-adoption review (review
  loop 2026-09-25, user direction: "what new features opencode v2 support, we
  need them in hephaestus as well ... strict policy: if opencode supports this
  feature, we use opencode. then, use Eclipse features and do not reinvent the
  wheel").
- **Read by:** every agent or human changing Hephaestus code; the PM when
  scoping tickets.
- **Related:** `AGENTS.md` (host discipline), `eclipse/ARCHITECTURE.md`
  (bundle layout), `ROADMAP.md` (the feature backlog), the live contract:
  `GET /openapi.json` of the running service (130+ operations, 2026-09-25).

## The strict policy

When Hephaestus needs a capability, the implementation MUST come from the
first tier that provides it. Writing our own mechanism is a defect, not an
option.

1. **opencode v2 first.** Sessions, prompts, shells, PTYs, worktrees, VCS
   state, permissions, forms, MCP management, websearch, file access - if the
   v2 API or config surface has it, we call it. Never wrap what already
   exists with our own equivalent.
2. **Eclipse platform second.** Background work (Jobs/JobGroup/timer),
   progress and overview (Progress view), preferences, scheduling rules,
   UI threading. Never a thread, pool, scheduler or dashboard of our own.
3. **Our own code only where neither host has the thing** - e.g. the task
   store (no v2 equivalent), the V-pipeline engine's steering logic, the
   PM workflow. Even there: thin glue over the two hosts, never parallel
   machinery.

Discovered capability question order: `GET /openapi.json` (the live contract)
-> https://opencode.ai/v2/docs/ -> the `opencode` skill -> ask. If v2 lacks
something, record it here as a justified exception instead of building around
it silently.

**A fleet is just many opencode agents** (user direction 2026-09-25): every
worker run is an ordinary v2 session (create, prompt, watch, abort over the
API). The fleet layer adds only what opencode does not have: scheduling and
admission, steering (waves, readiness, resolution), and git bookkeeping
(worktrees, merge-back - the justified git-CLI tier). Never a parallel agent
runtime, never our own model/session machinery.

**Panel review under the policy (2026-09-25):** every panel cross-checked
against the tiers. ChatView / SessionDetailsView = v2 capability parity;
ServerView / BackgroundView / ProvidersView / RepoView = projections of v2
data (RepoView stays SERVICE-scoped - local workspace browsing is Eclipse
Navigator's job, tier 2); BoardView / FleetView = the justified
store/orchestration tier (no v2 task API). No panel duplicates a host
capability; no retired (daemon-era) machinery remains. One internal
duplication recorded as a justified exception: `TextDialog` exists in both
`ui.session` (public) and `board.views` (package-private) - the bundles share
no dependency edge (board does not require ui), so consolidation needs either
a new bundle dependency or a move into `com.opencode.ide.core`; deferred to
the dependency-graph cleanup.

**Alignment means capability parity, not UI simulation** (user direction
2026-09-25: "we do not need to simulate the TUI in Eclipse. our chat window in
Eclipse and the whole harness needs to be aligned feature wise"). We adopt
what a session can DO (rename, fork, background, snapshots, forms,
permissions, shells, context usage) into the chat panel and the harness; we
never rebuild the TUI's interface inside Eclipse.

## Justified exceptions

| Our mechanism | Why not v2 / Eclipse |
|---|---|
| `StoreSync` / `GitStore` commit-pull-push over the git CLI | `vcs.*` is READ-ONLY (get/base/status/branch/diff); the store's write discipline has no v2 equivalent |
| `TaskStore` (`.opencode/tasks` Markdown store) | no v2 task/ticket API (session todos were checked again 2026-09-25: not in the contract) |
| SWT views, dialogs | the Eclipse presentation layer itself |
| `RepoGate` (git single-writer gate) | process-level mutual exclusion across engines; v2 worktree API has no cross-process lock |
| `SessionDiffSides` revision-content reads (git CLI) | `session.diff` shows patches, not resolved before/after file content at a revision; no v2 read serves a side-by-side view |
| `BuildRunner` / `StdioServer` raw threads | host plumbing of the plain-JVM tool processes (pump + reader/watcher); everything else runs on the Eclipse JobManager |

## The adoption matrix (live contract vs Hephaestus)

### Adopted (landed)

| v2 capability | Endpoint | Where it landed |
|---|---|---|
| **Session rename** | `PATCH /api/session/{id}` (`title`) | `renameSession` + "Rename session..." (Session Details) |
| **Context usage** | `GET /api/session/{id}/context` | `getSessionContext` (per-session token/context telemetry) |
| **Background sessions** | `POST /api/session/{id}/background` | `backgroundSession` + "Send to background" (U-042) |
| **Shells per session** | `POST /api/session/{id}/shell`, `shell.list/get/output/remove` | `runShell` + list/output/remove + "Run shell command..." + the Background view's Shells pane (U-041's subprocess visibility) |
| **opencode worktrees** | `worktree.list/create/refresh` | `worktreesVia(client, projectID)` in `PeerJobReconstructor` + the service-first ladder in `FleetView` (slice 2 consumer, live-probed contract) |
| **VCS reads** | `vcs.status`, `session.diff` | `getFileStatus` / `getSessionDiff` in the snapshot + diff surfaces; revision-level content reads stay git-CLI (exceptions table) |
| **Permission REST** | `permission.request.list`, `session.permission.*` | `PermissionQueue.reconcile` (floor semantics) + `FleetPermissionBridge`, reconciled at watchdog start |
| **Session snapshots** | `session.revert/stage/commit` | `revertMessage` / `unrevertSession` / `commitSessionRevert` + "Snapshot at this message" / Restore / Discard |
| **Session fork** | `session.fork` | `forkSession` + the existing fork button |
| **Forms** | `form.list`, `session.form.*` | `FormSchema` + `FormsDialog` + "Forms..."; the reply posts the declared `Form.Reply {answer}` shape |
| **MCP management** | `experimental.mcp.add/remove/connect/disconnect` | `registerMcp` + `removeMcp`/`connectMcp`/`disconnectMcp`, contract-tested (server name in the path) |
| **Commands** | `command.list`, `command.*` | `getCommands` / `runCommand` (chat slash-commands) |
| **FS reads** | `fs.read/list/find` | `getFileContent` / `listFiles` / `findFiles` (scope-aware browsing) |

| **Terminal read-only screen** | over `pty.*` + `experimental/session/{id}/terminal*` | the "Terminal..." pane renders `PersistentPty.ReadResult.screen` - the service renders, no emulator on our side |
|---|---|---|
| **Session move / switch** | `session.move`, `session.agent`, `session.model` | `moveSession` / `switchSessionAgent` / `switchSessionModel` + Session Details actions |
| **Compaction** | `session.compact` | `compactSession` + "Compact context" |
| **Session export/log/stats** | `experimental.session.export/log/stats` | `exportSession` / `sessionLog` / `sessionStats` + Export / Log / Stats actions |
| **PTY (capability + read-only surface)** | `pty.*`, `experimental/session/{id}/terminal*`, `experimental/persistent-pty/*` | `listPtys` / `createPty` / `removePty` / `readSessionTerminal` / `sessionTerminal` / `createSessionTerminal` / `persistentPtySnapshot` + the "Terminal..." screen pane (no emulator on our side) |
| **References, websearch** | `reference.list`, `websearch.*` | `listReferences` / `listWebsearchProviders` / `websearch` |
| **Project update, location** | `project.update`, `location.get/reload` | `updateProject` / `getLocation` / `reloadLocation` |
| **Credentials** | `credential.*` | `renameCredential` / `activateCredential` / `removeCredential` (no list endpoint exists in the contract; the OAuth connect flows were already in) |
| **Plugin management** | `plugin.list/check/update` | `listPlugins` / `checkPlugins` (empty body - live-probed) / `updatePlugins` + Server view Plugins / Check / Update actions |
| **MCP Server-view surface** | over the adopted `experimental.mcp.*` verbs | `McpServersDialog` (Connect / Disconnect / Remove) on the Server view menu |

Every verb is contract-tested (`HttpOpencodeClientComponentTest`), every body
shape was probed against the live service before coding, and the pure
formatting behind the dialogs is unit-tested (`ServiceTextTest`).

### Adopt next (what genuinely remains)

| v2 capability | Endpoint | Replaces / enables |
|---|---|---|
| **Interactive PTY host** | over the adopted `pty.*` verbs + the WebSocket `connect` stream | an Eclipse TM Terminal widget - the only remaining PTY piece; the capability, the read-only screen and the lifecycle verbs are in |
| **GitWorktreeManager shrink** | `worktree.create` / `worktree.remove` | the last git-CLI worktree *writer* on the consumer side; the v2 trio covers it (create/remove ride the same contract as `worktree.list`/`refresh`) |

### Contract notes (live-probed, current)

- `worktree.list` requires `projectID` as a query parameter;
  `worktree/refresh` requires it in the request body (the id comes from
  `getProjects()`, matched on `canonical`).
- `plugin/check` takes **no** argument — `{"target": ...}` is a 400.
- Forms reply with the declared `Form.Reply` wrapper: `{"answer": {...}}`;
  hidden fields are skipped, required fields are validated.
- A capability audit MUST include our own layers — six parallel client
  methods were once added and removed again as duplicates of the existing
  surface. The policy applies to us first.

### Not for us

`websearch.query` (no UI need yet), `rpc.*` (plugin authors), `config.shell`,
`debug.location.*` (support use), `experimental.generate.text`,
`experimental.migration.v1.status`, `experimental.integration.wellknown`.

## Compliance status

Everything in the matrix is either landed or tracked in "Adopt next"; the
justified exceptions are closed questions with reasons. The strict policy and
the panel review above are the audit trail.
