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
| **PTY (capability + read-only surface)** | `pty.*`, `experimental/session/{id}/terminal*`, `experimental/persistent-pty/*` | `listPtys` / `createPty` / `removePty` / `readSessionTerminal` / `sessionTerminal` / `createSessionTerminal` / `persistentPtySnapshot` + the "Terminal..." screen pane (no emulator on our side). Note: interactive input has **no REST route** - `PUT /api/pty/{id}` is title/resize only; stdin rides the WebSocket `GET /api/pty/{id}/connect` stream (and `org.eclipse.tm.terminal` is absent from the 2026-06 target, so the interactive host is parked on target-platform work) |
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
- **MCP recovery recipe** (live-proven 2026-09-29): a server stuck at
  `status: failed` / "Connection closed" is revived without a restart by
  `POST /api/experimental/mcp/{name}/connect?location[directory]=<repo>`
  ("overriding a disabled configuration until restart"). Location scoping
  is the **deepObject** query `location[directory]` - a bare `?directory=`
  is silently ignored and resolves to the home location.

### Not for us

`websearch.query` (no UI need yet), `rpc.*` (plugin authors), `config.shell`,
`debug.location.*` (support use), `experimental.generate.text`,
`experimental.migration.v1.status`, `experimental.integration.wellknown`.

## Compliance status

Everything in the matrix is either landed or tracked in "Adopt next"; the
justified exceptions are closed questions with reasons. The strict policy and
the panel review above are the audit trail.

## Current contract cross-check (live v2.0.19, 138 operations)

The probe rule ("check `GET /openapi.json` first") is a STANDING step - run it
every wave and diff it against this matrix. The current surface adds classes
the matrix above predates; each needs its tier decision before it lands:

| New since the matrix | Operations | Decision |
|---|---|---|
| **Integrations** (GitHub/GitLab connect: command/key/oauth + attempts, wellknown) | `integration/*` (8) | **Adopted** - catalog + real connect flows in the Server view's Integrations dialog (command: start + poll + abort; key: masked entry; oauth: poll -> open URL -> complete/paste-code; failures are notices, never faked); the old "PR/CI is a non-goal (gh + Actions)" is superseded |
| **Skills API** (list + per-session skill attach) | `GET /skill`, `POST .../session/{id}/skill` | **Adopted** - client verbs + Session Details *Attach skill*; `GET` returns full skill bodies (heavy: paginate/prefetch); the attach POST is experimental and appends a `skill` message to history. **Body is `{"skill": "<id>"}`** - the schema's `id` member is a `^msg_` message anchor, so `{"id": ...}` 400s (missing required property `skill`); route is under `/experimental` on 2.0.19 (later builds publish it without the prefix - re-probe on upgrade); resumes unless `"resume": false`; 404 = SkillNotFoundError |
| **Session wait** | `POST .../session/{id}/wait` | **Adopted** - `waitForSession` verb + the fleet watchdog long-polls it with a deadline-aware window (30 poll ticks cap, stall/budget/cap deadlines shrink it, anti-spin guard, pause-while-asks-pending); any failure falls back to the poll loop. Wire truth: 204 on settle, no body, route under `/experimental` on 2.0.19 |
| **Saved permissions** (remembered allow/deny) | `permission/saved*` (3) | **Adopted** - client verbs + the Server view's *Saved permissions...* manager (list + remove with confirm); the TUI's "remember this choice" |
| `session/generate` (context-conditioned LLM call, **no history mutation**) | `POST .../session/{id}/generate` | **Adopted** - powers Session Details *Suggest title* (prefills Rename; never auto-renames) |
| `session/synthetic` (automation input; `steer`\|`queue`; `msg_` id = idempotency key, 409 on dup) | `POST .../session/{id}/synthetic` | **Adopted** - the chat's queue/steer inbox (T-005) delivers through it |
| `session/view` (read-marker for unread/idle badges) | `POST .../session/{id}/view` | **Adopted** - Session Details marks the session viewed on open/refresh (feeds the unread/idle badges) |
| Session import (transcript restore/move; parents before children), `revert/stage` + `revert/commit` + `DELETE .../revert` (two-phase undo with preview), `environment` (PUT = **full replace** of the variable map) | `experimental/session/import`, `.../revert/*`, `.../environment` | **Adopted** - Session Details: *Revert to here* / *Undo revert* / *Commit revert* (the two-phase truth in the confirm dialogs; never auto-commits), the environment dialog states the full-replace semantics (no GET route exists), import is a pick-export -> preview -> import flow. Note: `revert/stage` RESTORES working-tree files by default (`files:false` stages only) |
| `config/shell` (shell-executable discovery: `{path, name, acceptable}`) | `GET /api/config/shell` | **Adopt-lite** - feeds a terminal/shell picker; pure read |
| `experimental/migration/v1` (v1→v2 session-history migration status; auto-runs at server boot) | `GET` | **Adopted (banner)** - the Server view shows it while running/error (completed/404 hidden), polled once on refresh ("migrating session history…") |
| `pair` (client/device pairing: 5-min single-use code, redeemed at the **unauthenticated** `GET /auth/connect/{code}` for a session token) | `POST /api/pair` | **Evaluate** - useful for multi-client onboarding; the pairing code IS a credential, handle it as one |
| `rpc/{rpcID}/{method}` (the plugin RPC bridge; methods are whatever plugins register) | `POST` | **Evaluate** - only valuable when users install RPC-defining plugins |
| `debug/location` (per-directory service cache: `GET` lists, `DELETE` **evicts** live caches) | `GET`/`DELETE` | **Evaluate** - diagnostics only; the DELETE has real side effects despite the name |

TUI-feature parity floor ("same or more than the official TUI"):

| TUI feature | State here |
|---|---|
| `@` file references + `@alias` reference roots | landed (U-012 + U-047): composer fuzzy dropdown over `fs/find` merges the reference catalog (`GET /api/reference`) as an alias group above the files; picks stay plain text (no content injection); empty catalog degrades to files-only |
| `!` bash inline, `/compact` `/export` `/models` `/new` `/sessions` `/connect` `/details` `/undo` `/redo` | adopted (U-010, Wave A) |
| `/editor` `/themes` keybinds command palette | host tier: Eclipse editor/themes/keybindings (strict reuse) |
| `/share` `/unshare` | not implementable on v2.0.19: the TUI's own `/share` toasts "Sharing is not implemented for V2 sessions yet" (verified in the v2.0.19 TUI source) and no server route exists; the chat shows the same honest notice - revisit when the server grows a route |
| `/init` (AGENTS.md wizard), `/help`, `/thinking` (reasoning display) | landed (U-047): `/init` guided prompt + session title, `/help` derived from the command registry (cannot drift), `/thinking` persisted and re-applied on every render |
| Attention (desktop notifications + sounds on question/permission/error/done) | landed (U-047 + U-014): jface `NotificationPopup` + `Display.beep`, off by default (preference page), fed from the live event stream - permission asks, session errors, completions; question prompts are answerable IN the chat (form cards, pull-based polling while a send is in flight - there is no form-ask SSE type, so the QUESTION popup kind stays unmapped). Note: `org.eclipse.ui.notification` does NOT exist in the 2026-06 target; the real bundle is `org.eclipse.jface.notifications` (0.8) |
| Queue/steer inbox, subagent nesting, session todos | inbox landed (T-005: Steer now / Deliver next / Cancel); subagent nesting landed (U-041: Server view + Session Details + the Fleet tree, parentID layer, per-session shell tasks with output tails); session todos (U-013) tracked - no v2 endpoint exists |
| ACP (`opencode acp` in other editors) | not applicable here - Eclipse is the native host; relevant if Hephaestus agents must be embeddable elsewhere |
| Policies, Zen mode | unevaluated (new config surfaces) |
| LSP | host tier: Eclipse LSP (strict reuse) |