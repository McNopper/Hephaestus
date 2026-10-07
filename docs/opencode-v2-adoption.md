# opencode-v2-adoption.md - the strict reuse policy and the v2 adoption matrix

## About this document

- **Kind:** `doc` / architecture policy + technology-adoption matrix (origin: the
  v2 alignment review, user direction: "what new features opencode v2 support, we
  need them in hephaestus as well ... strict policy: if opencode supports this
  feature, we use opencode. then, use Eclipse features and do not reinvent the
  wheel").
- **Read by:** every agent or human changing Hephaestus code; the PM when
  scoping tickets.
- **Related:** `AGENTS.md` (host discipline), `eclipse/ARCHITECTURE.md`
  (bundle layout), `ROADMAP.md` (the feature backlog), the live contract:
  `GET /openapi.json` of the running service (last cross-check: v2.0.19,
  138 operations - see the cross-check section below).

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

**A fleet is just many opencode agents:** every worker run is an ordinary v2
session (create, prompt, watch, abort over the API). The fleet layer adds only
what opencode does not have: scheduling and admission, steering (waves,
readiness, resolution), and git bookkeeping (worktrees, merge-back - the
justified git-CLI tier). Never a parallel agent runtime, never our own
model/session machinery.

**Panel review under the policy:** every panel cross-checked against the
tiers. ChatView / SessionDetailsView = v2 capability parity; ServerView /
BackgroundView / ProvidersView / RepoView = projections of v2 data (RepoView
stays SERVICE-scoped - local workspace browsing is Eclipse Navigator's job,
tier 2); BoardView / FleetView = the justified store/orchestration tier (no
v2 task API). No panel duplicates a host capability; no retired (daemon-era)
machinery remains.

**Alignment means capability parity, not UI simulation** ("we do not need to
simulate the TUI in Eclipse. our chat window in Eclipse and the whole harness
needs to be aligned feature wise"). We adopt what a session can DO (rename,
fork, background, snapshots, forms, permissions, shells, context usage) into
the chat panel and the harness; we never rebuild the TUI's interface inside
Eclipse.

## Justified exceptions

| Our mechanism | Why not v2 / Eclipse |
|---|---|
| `StoreSync` / `GitStore` commit-pull-push over the git CLI | `vcs.*` is READ-ONLY (get/base/status/branch/diff); the store's write discipline has no v2 equivalent |
| `TaskStore` (`.opencode/tasks` Markdown store) | no v2 task/ticket API (session todos are not in the contract) |
| SWT views, dialogs | the Eclipse presentation layer itself |
| `RepoGate` (git single-writer gate) | process-level mutual exclusion across engines; v2 worktree API has no cross-process lock |
| `SessionDiffSides` revision-content reads (git CLI) | `session.diff` shows patches, not resolved before/after file content at a revision; no v2 read serves a side-by-side view |
| `BuildRunner` / `StdioServer` raw threads | host plumbing of the plain-JVM tool processes (pump + reader/watcher); everything else runs on the Eclipse JobManager |
| `TextDialog` exists twice: `ui.session` (public) and `board.views` (package-private) | the bundles share no dependency edge (board does not require ui), so consolidation needs either a new bundle dependency or a move into `com.opencode.ide.core`; deferred to the dependency-graph cleanup, untracked |
| `GitWorktreeManager` worktree writes (git CLI) | the guarded create (reclaim stale residue, refuse unmerged/dirty work) is fleet bookkeeping over git-level checks the v2 worktree API does not expose; v2 has no worktree-remove verb at all (only `worktree.list/create/refresh` - `listWorktrees`/`createWorktree`/`refreshWorktrees` landed, see below). Untracked shrink: route the raw creates through `worktree.create` where the guarded semantics allow |

## The adoption matrix (live contract vs Hephaestus)

### Adopted (verb landed AND a production consumer calls it)

| v2 capability | Verb(s) in `HttpOpencodeClient` | Consumer |
|---|---|---|
| **Session rename** | `renameSession` | "Rename session..." (SessionDetailsView.java:601 -> SessionDetailsController.java:181) |
| **Background sessions** | `backgroundSession` | ChatView.java:1533 + "Send to background" (SessionDetailsController.java:343) |
| **Shells per session** | `runShell` (+ `listShellTasks`/shell output reads) | "Run shell command..." (SessionDetailsController.java:414), the fleet's agent bootstrap (FleetRunner.java:435), the Background view's Shells pane (BackgroundView.java:238) |
| **Queue/steer inbox** | `sendMessage(..., delivery)` = `POST /prompt` with `delivery`, `listInbox`, `updateInboxItem`, inbox cancel | the chat's Steer now / Deliver next / Cancel (T-005): ChatSessionController.java:701/:986/:927-934, ChatView.java:350 |
| **opencode worktrees (reads)** | `listWorktrees` | `PeerJobReconstructor.worktreesVia` (PeerJobReconstructor.java:139) in `FleetView`'s service-first ladder (FleetView.java:1093) |
| **VCS reads** | `getFileStatus`, `getSessionDiff` | WorkingSet.java:60 / ProjectVcs.java:165; FleetView.java:1146; revision-level content reads stay git-CLI (exceptions table) |
| **Permission REST** | `listPermissionRequests` (+ reply/set) | `PermissionQueue.reconcile` in the watchdog (TaskFleet.java:1025), BackgroundView.java:243, `ChatPermissionRecovery` |
| **Two-phase revert** | `revertMessage`, `unrevertSession`, `commitSessionRevert` | "Revert to here…" / "Undo revert…" / "Commit revert…" (SessionDetailsView.java:666-722 -> SessionDetailsController.java:198); the chat's message revert (ChatSessionController.java:1949) |
| **Session fork** | `forkSession` | the chat's fork button (ChatSessionController.java:1255) + Session Details (SessionDetailsController.java:503) |
| **Forms** | `listForms` (+ reply/cancel) | form cards in the chat (ChatSessionController.java:1059) + "Forms..." (SessionDetailsController.java:314, `FormsDialog` over `FormSchema`); the reply posts the declared `Form.Reply {answer}` shape |
| **MCP management** | `registerMcp` | `McpRegistration.java:44` (the tasks stdio server registers itself) |
| **MCP server actions** | `removeMcp` / `connectMcp` / `disconnectMcp` | `McpServersDialog` (McpServersDialog.java:86-96) on the Server view - **current limitation:** the three actions send no `location[directory]`, so they act on the HOME location, not the listed repo instance; B-023 (in progress) scopes them |
| **Commands** | `getCommands` / `runCommand` | the chat's slash-commands (ChatSessionController.java:763) |
| **FS reads (scope-aware browsing)** | `listFiles` / `findFiles` | RepoView.java:309/338/375 + the composer's `@`-file fuzzy dropdown (ChatSessionController.java:1777) |
| **PTY read-only screen** | `readSessionTerminal` | the "Terminal..." pane renders `PersistentPty.ReadResult.screen` (SessionDetailsController.java:305 via `ServiceText`) - the service renders, no emulator on our side. Interactive input has **no REST route**: `PUT /api/pty/{id}` is title/resize only; stdin rides the WebSocket `GET /api/pty/{id}/connect` stream (see "Adopt next") |
| **Session move / switch** | `moveSession` / `switchSessionAgent` / `switchSessionModel` | Session Details actions (SessionDetailsController.java:238/248/258) |
| **Compaction** | `compactSession` | "Compact context" (SessionDetailsController.java:268) |
| **Session export/log/stats** | `exportSession` / `sessionLog` / `sessionStats` | Session Details Export / Log / Stats (SessionDetailsController.java:278/287/296, SessionDetailsView.java:835) |
| **References** | `listReferences` | the composer's `@alias` reference catalog (ChatSessionController.java:1793, U-047) |
| **Plugin management** | `listPlugins` / `checkPlugins` / `updatePlugins` | Server view Plugins / Check / Update (ServerView.java:624/592/616) |
| **Provider OAuth connect** | `beginProviderOauth` (+ the typed auth catalog) | the Providers view's Connect (ProvidersView.java:231) |
| **Integrations (GitHub/GitLab)** | `startIntegrationCommand/Oauth`, `integrationOauthAttempt/Complete/Abort`, catalog reads | the Server view's Integrations dialog (IntegrationsDialog.java:273): command start + poll + abort, key entry, oauth poll -> URL -> complete/paste; failures are notices, never faked |
| **Skills API** | `listSkills`, session skill attach | Session Details *Attach skill…* (SessionDetailsView.java:624 -> SessionDetailsController.java:358); `GET` returns full skill bodies (heavy: paginate/prefetch); the attach POST is experimental and appends a `skill` message to history. **Body is `{"skill": "<id>"}`** - the schema's `id` member is a `^msg_` message anchor, so `{"id": ...}` 400s; route under `/experimental` on v2.0.19 (later builds publish it without the prefix - re-probe on upgrade); resumes unless `"resume": false`; 404 = SkillNotFoundError |
| **Session wait** | `waitForSession` | the fleet watchdog long-polls it with a deadline-aware window (TaskFleet.java:1175 -> FleetRunner.java:312; 30 poll ticks cap, stall/budget/cap deadlines shrink it, anti-spin guard, pause-while-asks-pending); any failure falls back to the poll loop. Wire truth: 204 on settle, no body, route under `/experimental` on v2.0.19 |
| **Saved permissions** | `listSavedPermissions` / `deleteSavedPermission` | the Server view's *Saved permissions…* manager (ServerView.java:447, SavedPermissionsDialog.java:87/115) - the TUI's "remember this choice" |
| **`session/generate`** (context-conditioned LLM call, **no history mutation**) | `generateOnSession` (HttpOpencodeClient.java:1334) | Session Details *Suggest title* (SessionDetailsView.java:333 -> SessionDetailsController.java:386; prefills Rename, never auto-renames) |
| **Session view marker** | `markSessionViewed` | Session Details marks the session viewed on open/refresh (SessionDetailsController.java:455; feeds the unread/idle badges) |
| **Session import** | `importSession` (:1478) over the adopted `exportSession` | Session Details: pick-export -> preview -> *Import session…* (SessionDetailsView.java:907/1088; parents before children) |
| **Session environment** | `replaceSessionEnvironment` | the environment dialog states the full-replace semantics (PUT = **full replace** of the variable map, no GET route exists; SessionDetailsController.java:470) |
| **v1 migration status** | `getV1MigrationStatus` | the Server view shows the banner while running/error, completed/404 hidden, polled once on refresh ("migrating session history..."; ServerView.java:1099, `MigrationBanner`) |

Every verb above is contract-tested (`HttpOpencodeClientComponentTest`), every
body shape was probed against the live service before coding, and the pure
formatting behind the dialogs is unit-tested (`ServiceTextTest`).

### Verbs landed, no consumer yet (adopted on the client, nothing calls them)

These are NOT adopted capabilities - the verb exists in `HttpOpencodeClient`,
is contract-tested, and waits for a consumer. Naming one here without a
consumer in the table above would be a lie.

| Route | Verb(s) | Would enable |
|---|---|---|
| `GET /api/session/{id}/context` | `getSessionContext` (:493) | per-session token/context telemetry |
| `POST /api/websearch`, `GET /api/websearch/provider` | `listWebsearchProviders` (:383), `websearch` (:389) | in-chat web search |
| `pty.*`, `experimental/session/{id}/terminal*`, `experimental/persistent-pty/*` | `listPtys` (:400), `createPty` (:406), `removePty` (:438), `sessionTerminal` (:478), `createSessionTerminal` (:485), `persistentPtySnapshot` (:450) | PTY lifecycle beyond the read-only screen (interactive host: see "Adopt next") |
| `project.update`, `location.get/reload` | `updateProject` (:347), `getLocation` (:336), `reloadLocation` (:342) | project/location management UI |
| `credential.*` | `renameCredential` (:366), `activateCredential` (:373), `removeCredential` (:378) | credential label/activation management (no list endpoint exists in the contract; the OAuth *connect* flows are separate, adopted verbs) |
| `GET /api/config/shell` | `getShellConfig` (:1209) | a terminal/shell picker; pure read (answers a BARE array) |
| `fs.read` | `getFileContent` (:1850) | file-content preview (RepoView and the composer use `list`/`find` only) |
| `worktree.create` | `createWorktree` (:556) | the fleet's raw worktree writes (guarded writes stay git-CLI - exceptions table) |
| `POST /session/{id}/synthetic` | posted by `sendMessage` (HttpOpencodeClient.java:770) | **not the inbox path**: it carries only the per-request system prompt (`ChatRequests.syntheticBody`, sent only when one is set; `steer`\|`queue`, `msg_` id = idempotency key, 409 on dup) |

### Adopt next (what genuinely remains)

| v2 capability | Endpoint | Replaces / enables |
|---|---|---|
| **Interactive PTY host** | over the adopted read-only surface + the WebSocket `GET /api/pty/{id}/connect` stream | an Eclipse TM Terminal widget - the only remaining PTY piece; the capability, the read-only screen and the lifecycle verbs are in (`org.eclipse.tm.terminal` is absent from the 2026-06 target, so the interactive host is parked on target-platform work) |

### Contract notes (live-probed, current)

- `worktree.list` requires `projectID` as a query parameter;
  `worktree/refresh` requires it in the request body (the id comes from
  `getProjects()`, matched on `canonical`).
- `plugin/check` takes **no** argument - `{"target": ...}` is a 400.
- Forms reply with the declared `Form.Reply` wrapper: `{"answer": {...}}`;
  hidden fields are skipped, required fields are validated.
- A capability audit MUST include our own layers - the policy applies to us
  first.
- **MCP recovery recipe:** a server stuck at `status: failed` /
  "Connection closed" is revived without a restart by
  `POST /api/experimental/mcp/{name}/connect?location[directory]=<repo>`
  ("overriding a disabled configuration until restart"). Location scoping
  is the **deepObject** query `location[directory]` - a bare `?directory=`
  is silently ignored and resolves to the home location.
- The queue/steer inbox is **not** `session/synthetic`: sending with a
  delivery is `POST /prompt` with a `delivery` field (`"queue"` parks the
  prompt in the session inbox, `"steer"` is the default shape); pending
  items are read/patched/cancelled via
  `GET/PATCH/DELETE /session/{id}/inbox[/{msgID}]`. `session/synthetic`
  carries only the per-request system prompt.

### Not for us

`experimental.generate.text` (a context-free LLM text call;
`session/generate` - adopted above - covers our need with session context).
`experimental.integration.wellknown.add` (POST; registers a custom well-known
integration - enterprise/server config, we consume the catalog).
`experimental.fs.write` (POST; the harness edits files locally - a
server-side write verb has no consumer here).

## Compliance status

Every capability is in exactly one bucket: adopted with a consumer, verb
landed waiting for one, adopt-next, justified exception, or rejected. The
strict policy and the panel review above are the audit trail; the store is
the place to track new adoption work (nothing in "Adopt next" or the
no-consumer list has a ticket today).

## Current contract cross-check (live v2.0.21, 141 operations; the per-operation baseline snapshot is `opencode-v2-surface-2.0.21.txt` next to this file)

The probe rule ("check `GET /openapi.json` first") is a STANDING step - run it
every wave and diff it against this matrix (the committed snapshot makes the
diff mechanical). New surface classes need a tier decision before they land:

| New since v2.0.19 (live 2.0.21) | Operations | Decision |
|---|---|---|
| **Well-known integration registration** | `POST experimental/integration/wellknown` (`integration.wellknown.add`) | **Not for us** - server/enterprise config; we consume the catalog |
| **Server-side file write** | `POST experimental/fs/write` | **Not for us** - the harness edits locally |
| **PTY connect token + persistent-PTY lifecycle** (`shutdown`, `handoff` between clients, `connect-token`) | `POST pty/{id}/connect-token`; `experimental/persistent-pty/*` (3) | **Evaluate with the parked PTY host** (ROADMAP upstream-blocked): these are the WebSocket stdin handshake and session-to-client handoff verbs the interactive terminal would need |

| New since the matrix | Operations | Decision |
|---|---|---|
| **Integrations** (GitHub/GitLab connect: command/key/oauth + attempts, wellknown) | `integration/*` (8) | **Adopted** - see the matrix above (IntegrationsDialog); the old "PR/CI is a non-goal (gh + Actions)" is superseded |
| **Skills API** (list + per-session skill attach) | `GET /skill`, `POST .../session/{id}/skill` | **Adopted** - see the matrix above (Attach skill) |
| **Session wait** | `POST .../session/{id}/wait` | **Adopted** - see the matrix above (fleet watchdog) |
| **Saved permissions** (remembered allow/deny) | `permission/saved*` (3) | **Adopted** - see the matrix above (Saved permissions manager) |
| `session/generate` (context-conditioned LLM call, **no history mutation**) | `POST .../session/{id}/generate` | **Adopted** - see the matrix above (Suggest title) |
| `session/synthetic` (automation input) | `POST .../session/{id}/synthetic` | **Adopted** - carries the per-request system prompt (posted by `sendMessage` only when one is set); the queue/steer inbox is `POST /prompt` + `delivery` + `/session/{id}/inbox` - see the contract notes |
| `session/view` (read-marker for unread/idle badges) | `POST .../session/{id}/view` | **Adopted** - see the matrix above (view marker) |
| Session import, two-phase revert (`revert/stage` + `revert/commit` + `DELETE .../revert`), `environment` (PUT = **full replace**) | `experimental/session/import`, `.../revert/*`, `.../environment` | **Adopted** - "Revert to here…" / "Undo revert…" / "Commit revert…" (the two-phase truth in the confirm dialogs; never auto-commits). Note: `revert/stage` RESTORES working-tree files by default (`files:false` stages only) |
| `config/shell` (shell-executable discovery: `{path, name, acceptable}`) | `GET /api/config/shell` | **Verb only, no consumer yet** - `getShellConfig`; would feed a terminal/shell picker |
| `experimental/migration/v1` (v1->v2 session-history migration status; auto-runs at server boot) | `GET` | **Adopted (banner)** - see the matrix above (Server view banner) |
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
| Queue/steer inbox, subagent nesting, session todos | inbox landed (T-005: Steer now / Deliver next / Cancel, via `POST /prompt` + `delivery` + the inbox routes); subagent nesting landed (U-041: Server view + Session Details + the Fleet tree, parentID layer, per-session shell tasks with output tails); session todos (U-013) tracked - no v2 endpoint exists |
| ACP (`opencode acp` in other editors) | not applicable here - Eclipse is the native host; relevant if Hephaestus agents must be embeddable elsewhere |
| Policies, Zen mode | unevaluated (new config surfaces) |
| LSP | host tier: Eclipse LSP (strict reuse) |
