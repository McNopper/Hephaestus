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
  `GET /openapi.json` of the running service (last cross-check: v2.2.5,
  169 operations, 2026-10-31 - see the cross-check section below).

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
-> <https://opencode.ai/v2/docs/> -> the `opencode` skill -> ask. If v2 lacks
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

## Current contract cross-check (live v2.2.5, 169 operations; the per-operation baseline snapshot is `opencode-v2-surface-2.2.5.txt` next to this file - the 2.2.2, 2.2.1, 2.2.0, 2.1.3, 2.1.0, 2.0.30, 2.0.29, 2.0.28, 2.0.27 and 2.0.21 snapshots stay for the diff history)

The probe rule ("check `GET /openapi.json` first") is a STANDING step - run it
every wave and diff it against this matrix (the committed snapshot makes the
diff mechanical). New surface classes need a tier decision before they land:

**Experimental surface policy (owner, 2026-10-27, revised 2026-10-29):**
routes marked `experimental` fall into two classes. **(1) No-overlap
surfaces** are never consumed and never become a design dependency - they are
watch-only (the list below), and an experimental route disappearing
(`voice.instructions`: added 2.1.3, removed 2.2.0) must never break or
re-shape our features. **(2) Overlap surfaces - an experimental feature that
replaces something we built or queued - are ADOPTED proactively: we use the
upstream surface (flag/gate included), remove our parallel implementation, and
track the migration as a ticket.** Rationale: a parallel implementation of
something upstream ships is exactly the drift the strict-reuse policy exists
to prevent; "experimental" shifts the risk onto configuration, where it
belongs. Watch-list rows (W#) name the guard that applies until adoption.

**Grandfathered consumption (same policy, backward-looking half):** the client
predates this policy and calls seven experimental path families as its normal
transport - `experimental/config` (PATCH), `experimental/mcp/*` (management),
`experimental/migration/v1`, `experimental/persistent-pty/*` (terminal
surfaces), `experimental/session/{id}/export|log|wait|skill|import`,
`experimental/session/stats`. These are **explicitly grandfathered**: they are
the shipped feature transport, watched here, and migrate when upstream
stabilizes them. New code must not add to this set.

### Experimental watch list (tracked; never consumed, never re-implemented in parallel)

The contract-level rule above, made operational. Every upstream surface
marked `experimental` goes on this list with its **parallel-implementation
guard**: the thing WE must not build ourselves while the upstream feature
matures. Each round re-checks every row (stable → adopt per the trigger;
gone → drop the row; changed → update). Adoption is a tier decision like any
other - it happens when the trigger fires, not before.

**Endpoint case (owner, 2026-10-29): this extends to our own `tasks` and
`fleet` MCP endpoints.** They are the current access layer to the store and
the current engine host - not sacred. If upstream natively covers their
function (stable ticket/orchestration/scheduling APIs instead of
`experimental.*`), they are removed like any other parallel implementation.
What survives by design: the store as plain Markdown files (the blackboard
needs no server to exist) and the human accountability surfaces. The realistic
sequence: experimental primitives (watch list) → stable APIs → our endpoints
retire one function at a time, each removal a tier decision like any other.
**The end-state, accepted by the owner (2026-10-29): Hephaestus may end up as
just the better frontend** - the Eclipse cockpit over upstream's engine. That
is a fine place to land: the differentiation there is real (project
governance, V-model geometry, store projections, quality-gate integration -
none of which the session-centric TUI/Desktop have), and the doctrine above
guarantees we arrive by adoption, not by competition.

| # | Upstream surface | Since | Status today | Parallel-implementation guard (do NOT build) | Adopt when |
|---|---|---|---|---|---|
| W1 | `experimental.schedule.*` (list/get/create/pause/resume/delete) + `experimental.schedules` flag | 2.2.1 | Disabled by default (ForbiddenError without the flag); session-bound recurring prompts `{sessionID, when, prompt}` | Any timer/cron/scheduling layer of our own - RecurringWaves is ticket-governed planning, not a cron; no schedules-derived triggers in the pump | Prefix drops AND schedules can express work-item/goal triggers (not just session prompts) |
| W2 | `experimental.usage.limits` | 2.2.0 | Live; coverage unprobed (suspected Console/Go-only) | Self-built quota tracking or limit models in the fleet (U-098 chains must not invent them) | Stable AND covers third-party providers → U-098 adds the preflight probe |
| W3 | `experimental.session.instructions.entry.*` | 2.1.0 | Live, documented (#56550); survives compaction + reverts | A second lesson-attachment channel next to the prompt path (U-100) | Stable → U-100 switches substrate, keeps prompt fallback, adds a survives-compaction check |
| W4 | `experimental.generate.{text,image,decisions}` (+ `textLegacy`) | 2.2.0 | Live; decisions = cheap constrained judgments | Custom cheap-classification plumbing (reviewer pre-screen, triage, flake judgment) | Stable → wire decisions to one concrete repeated judgment |
| W5 | `experimental.background_compaction` (config flag) | 2.1.4 | Opt-in; unused tokens if summary unused | Worker-side compaction management | Stable → set `true` for long fleet workers (config-only) |
| W6 | `experimental.fs.watch` / `unwatch` | 2.2.0 | Live; unused | Delegating Board's `TaskStoreWatcher` or chat-web watchers to it | Stable AND a real limitation of our local watcher appears |
| W7 | `experimental.client-mcp` + `/token` | 2.2.0 | Live; purpose unprobed | — (no need identified) | Probe before any MCP-management feature work |
| W8 | `experimental.media/{hash}` + `media_blob` | 2.2.0 | Opt-in blob storage for session media | Own media stores for transcripts/artifacts | Stable AND a media feature is wanted |
| W9 | `experimental.voice.*` (models, session) | 2.1.3 | Churning (instructions added 2.1.3, removed 2.2.0) | Any voice surface | Deliberate non-interest |
| W10 | `experimental.todo` (config) + `todowrite` | 2.1.0 | Opt-in; TUI-side display | A session-todo read path of our own | A stable read ROUTE appears → unblocks U-013 |
| W11 | `experimental.persistentPty.*` (operationIds renamed 2.2.1) | 2.1.0 | Live incl. Windows | Wiring our PTY surfaces to the experimental namespace | Prefix drops → revisit the platform-blocked PTY note |

| New since v2.2.2 (live 2.2.5) | Operations | Decision |
|---|---|---|
| 3 releases in 2 days (2.2.3/2.2.4/2.2.5, all Oct 30; the service auto-updated under us twice and the standing behavioral scan caught both rounds). | +7 added, 0 removed, 0 changed for anything consumed | The +7 are `experimental.artifacts.*` (2.2.4 #60351: agents publish/update/serve HTML or static artifacts, opt-in via `"experimental": { "artifacts": true }`) → **W12 (watch)**: no-overlap surface, never consumed, never a design dependency; the camelCase `{artifactID}` path form matches the endpoint-case doctrine. **W1 sharpens again** (2.2.3 #60140): experimental schedules gain Code-Mode JS checks, statically authorized tool calls, persistent state and custom wake messages - still session-prompt timers, still experimental, still not orchestration. In OUR transport lanes: MCP stdio messages >10 MiB no longer disconnect the server and large messages read faster (#60694), and idle locations no longer restart their MCP servers right after shutdown (#60668) - both harden the tasks/fleet stdio launchers; the `bash` tool name now aliases to `shell` with the same permissions (#60687 - we call `shell`); `compaction.buffer` accepts a context-window percentage (#60647); reasoning survives model switches and resumed responses (#60498). Breaking changes (2.2.3, both assessed non-hitting): TUI plugins require OpenTUI 0.8 (#60385 - we run zero TUI plugins) and managed policy `integration.use` split into `mcp.use`/`skill.use`/`plugin.use` with automatic conversion of old prefixes (#60315 - we hold no such policy rules). Watch directions: Claude Agent provider (#58782 - a fourth provider option, provider auth via Claude Code login), upstream's provider-fallback direction (#60097: dynamic provider fallback delayed until SDK hooks run - noted on parked U-098, no plan change), and upstream's continued question-UX investment (#60358/#60372 - context for U-096). Config note (D-004, setup-local, audited 2026-10-31, non-breaking): the user-global config now carries per-agent model overrides on built-in agents - the U-104 configuration surface in the wild; specifics are never recorded in this repo (no provider ids, no model ids, no config content in committed files). Pin bumped 2.2.2 → 2.2.5 |

| New since v2.2.1 (live 2.2.2) | Operations | Decision |
|---|---|---|
| 0 added, 0 removed, 0 changed (162 vs 162 - pure patch) | 0 / 0 / 0 | **No collisions, no client impact.** 2.2.2: Desktop/TUI polish, image generation default-on (`experimental_image_generate` when an image-capable provider is connected - we generate no images), and schedule SDK inputs (`if`/`until` conditions + check results on `experimental.schedule.create` - **W1 sharpens: schedules now carry conditions and deadlines**, still session-prompt timers, still experimental). Also now documented - 2.2.1's late-published changelog: **`/goal <objective>`** (#59437) - the session keeps working until a REVIEWER confirms the objective (`/goal pause\|resume\|clear`) - **the first upstream feature with semantic overlap on the V-loop's work→review→accepted cycle**: session-scoped self-review loop, no store/stages/artifacts/fleet. Verdict REVISED (owner, 2026-10-30): **ADOPT-AND-GOVERN via U-105** - goals are taken as intake and fulfilled THROUGH the V-model (our review pass is the verdict, never the session's self-assessment); upstream's primitive stays the session-side driver only |
|---|---|---|
| 0 added, 0 removed, 0 changed (162 vs 162 - pure patch) | 0 / 0 / 0 | **No collisions, no client impact.** 2.2.2: Desktop/TUI polish, image generation default-on (`experimental_image_generate` when an image-capable provider is connected - we generate no images), and schedule SDK inputs (`if`/`until` conditions + check results on `experimental.schedule.create` - **W1 sharpens: schedules now carry conditions and deadlines**, still session-prompt timers, still experimental). Also now documented - 2.2.1's late-published changelog: **`/goal <objective>`** (#59437, TUI/Desktop command: the session keeps working until a REVIEWER confirms the objective; `/goal pause\|resume\|clear`) - **the first upstream feature with semantic overlap on the V-loop's work→review→accepted cycle**: session-scoped self-review loop, no store/stages/artifacts/fleet, so still WATCH - phase 2 only if `/goal` ever integrates the store or review verdicts. Breaking changes: none listed for 2.2.1/2.2.2. Pin bumped 2.2.1 → 2.2.2 |

| New since v2.2.0 (live 2.2.1) | Operations | Decision |
|---|---|---|
| +18 / -12 net +6 (162 vs 156): **NEW surface - `experimental.schedule` family** (list/get/create/pause/resume/delete); the 12 "removed" are operationId renames only (persistent-pty `server.experimental.*` → `experimental.*`, `session.log` → `experimental.session.log`) - paths unchanged | 6 added, 0 removed, 0 changed | **THE COLLISION-WATCH MOMENT, phase 1: upstream shipped its first server-side scheduling surface** (W1 in the watch list). Assessed against RecurringWaves: a schedule is `{sessionID, name?, when, ends?, prompt}` bound to an EXISTING session - "re-send this prompt to this session on a schedule". It is a per-session TIMER PRIMITIVE, not orchestration: no ticket awareness, no backlog planning, no worktree isolation, no budget admission, no gate, no NEEDS-HUMAN. AND it is experimental + disabled by default (probe live: ForbiddenError without `experimental.schedules` in config). Verdict: **WATCH (W1), do not adopt** - coupling to an experimental surface AND violating the always-a-live-host governance stance (a server-side schedule fires with no host watching = the unattended-spend pattern U-037 must accept first). Watch sharpens to: (a) schedules graduating from experimental, (b) schedules referencing GOALS/WORK ITEMS instead of session prompts - THAT would be phase 2. Everything else in 2.2.1: operationId renames only (zero client impact - we key on paths), no behavioral changes found (changelog entry for 2.2.1 not yet published; surface + live probes are the record). Pin bumped 2.2.0 → 2.2.1 |

**Correction (2026-10-29, binary probe):** the research-based claim that a
native per-agent model-fallback chain ships in opencode is **false for our
2.2.1 build** - no fallback config key exists in the binary or config surface
(the fallback commits found on GitHub are not in the installed product; the
plugin ecosystem exists because of this gap). U-098 re-scoped accordingly:
the chain is delivered by a plugin or the fleet layer, not by config.

| New since v2.1.3 (live 2.2.0) | Operations | Decision |
|---|---|---|
| +12 / -2 (156 vs 146): fs.watch/unwatch, client-mcp + token, media/{hash}, usage/limits, plugin/gui, debug/heap-dump, generate/text+image+decisions+textLegacy; REMOVED: `POST /api/experimental/generate` (moved to `/text`, old path kept as `textLegacy`), voice instructions (added 2.1.3, removed 2.2.0) | 12 added, 2 removed (both non-consumed), 0 changed on consumed routes | **No collisions, no required client changes** - our client never wired the generate route (grep-verified), so the move costs nothing. Enhancements flagged: **`GET /api/experimental/usage/limits`** - the fleet can preflight quota/limits before dispatch instead of dying mid-run (U-098 design hook); **session instruction entries** (`experimental.session.instructions.entry.*`, routes live since 2.1.0, now documented as the sanctioned mechanism: attach instructions to a session without prompt-cache busts, surviving compaction and reverts) - the better substrate for U-100's wave-lesson injection; **`experimental.background_compaction`** (2.1.4) - prepares compaction summaries in the background so long fleet workers do not stall mid-run; **provider-reported session costs** (#58562) - sharper actuals; prompt-cache TTLs per model (#59157) - cost lever. Breaking changes assessed: `@opencode/ai` SDK restructure + GitHub Action removal + OpenTUI 0.7 requirement - all JS/TUI-side, zero impact on the Java client (no GitHub Action in our CI - T-027 runs the Maven gate). 2.1.4 (between rounds) was behavioral-only: MCP input forms surface (elicitation), background compaction flag. Collision watch unchanged: still no upstream orchestration/run-queue/scheduling surface. Pin bumped 2.1.3 → 2.2.0 |

| New since v2.1.0 (live 2.1.3) | Operations | Decision |
|---|---|---|
| +2 / -0 (146 vs 144): `GET /api/experimental/voice/models`, `POST /api/experimental/voice/session/{sessionID}/instructions` | 2 added, 0 removed, 0 changed | **No collisions.** Both adds are the experimental voice surface (model discovery + hosted-session instructions) - no Hephaestus overlap, note-only. Behavioral scan 2.1.1-2.1.3: Bun 1.4.4 security base (#58087); Windows service port moved to 28894 (#57428 - discovery reads the registration file, no hardcoded port on our side, verified); **free-limit recovery UX** (#57466-71: reset time, restored prompt, direct model-switch actions - the TUI/Desktop face of the quota path U-098 configures chains on; strengthens that ticket); `OPENCODE_DISABLE_FREE_MODELS=1` (#57478 - catalog/defaults/**fallbacks** hygiene lever, relevant to U-098 chain composition); compaction summaries use the full output limit (#57564); MCP resource handling hardened + delta-synced updates (#57640/#57626 - affects opencode's own client protocol, not our stdio tasks server); session titles from the lowest suitable variant (#51939 - server-internal cost saving). Collision watch unchanged: still no upstream route families for run-queues, server-side scheduling or agent orchestration - the store + fleet + board layers remain ours. Pin bumped 2.1.0 → 2.1.3 |

| New since v2.0.30 (live 2.1.0) | Operations | Decision |
|---|---|---|
| +2 / -0 (144 vs 142): `PATCH /api/permission/saved/{id}` [permission.saved.update], `POST /api/experimental/voice/session` [experimental.voice.session] | 2 added, 0 removed, 0 changed | **First material release since 2.0.28** (covers skipped 2.0.31/2.0.32 too). The 2 added: saved-permission EDIT (`permission.saved.update`, #56659) is a small TUI-parity adoption candidate for our permission surfaces (we list + delete rules today); `experimental.voice.session` (Gemini Live voice, #56888) is note-only - no surface for it. **Breaking payload renames (2.1 #57260) assessed against the client: NO IMPACT** - `providerState`/`resultState`/`state`→`native` on parts and events, tool-part merge to `tool.native`, `session.compacted` removed, credential `configuration`→`metadata`, compaction `recent`→`metadata.keepID`, `providerContext`→`native.messages`: grep evidence 2026-10-20 shows ZERO client references to every renamed/removed field; the client's only `state` read is `tool.state` (explicitly kept). Live payload probes on 2.1.0: `/api/info` answers `{version:"2.1.0",...}` (health lives at `/api/info`, unchanged), session rows keep our shape, message rows are flat `{type, content[], ...}` matching `ChatEntryDeserializer`, tool parts still carry `state.status` (SessionObserver's activity read intact), reasoning parts now carry `native` (unread by us). Behavioral notes worth adopting/watching: session ARCHIVING restored (#53759 - archive filter on `session.list`, archived flag via `session.update`; candidate for a Server-view archive filter), Windows persistent PTYs restored (#56245 - the feature-matrix PTY platform-blocked note is a candidate to revisit), `todowrite` restored behind `experimental.todo` (#56947 - U-013's upstream source may exist server-side now, but still NO todo route in the 144 ops: remains blocked pending a verified v2 source), transient generation failures retried instead of failing (#56497) and broken-pipe retries (#56307) - robustness context for U-098's fallback adoption, `streamUsage` model compat option (#57255). 2.0.32 while skipped: `/copy` latest reply, headless `auth login` fix - TUI-only. Pin bumped 2.0.30→2.1.0 (version-only; all routes we consume unchanged) |

| New since v2.0.29 (live 2.0.30) | Operations | Decision |
|---|---|---|
| *No operation changes* - 2.0.30 is identical to 2.0.29 (142 vs 142 operation lines; 0 added, 0 removed, 0 changed) | - | **Nothing to consume** - the pin bump is version-only. The routes we use are unchanged, verified live 2026-10-16: `POST .../prompt` is still `{id, text, files, agents, skills, metadata, delivery, resume}` under `additionalProperties:false` with `delivery` = `Session.Inbox.Delivery` (`steer`\|`queue`), `PATCH .../inbox/{id}` is still `{delivery}`, `GET /api/session` still takes `limit`/`order`/`search`/`parentID`/`directory`/`cursor` (plus `project`/`subpath`), `GET /api/event` is still `text/event-stream`, and `interrupt`/`fork`/`session.remove` are present. Client-side release - nothing touching a route we consume: TUI `/diff` docked side panel on by default (#55780), Desktop per-turn changed-files timeline (#55064), session-tab keyboard navigation (#54277), instructions glob/URL loading with hot-reload (#55539), Claude Code discovery toggles (#55774), dual-shape V1/V2 config schema (#55524), the retired session-sharing backend and `/share` removed (#55716 - upstream finalized what our chat already flagged as not implemented), the Windows background service defaulting to port 45278 (#55547 - the shared service our discovery attaches to), MCP compatibility fixes (elicitation, catalog/codemode skip, `*.localhost` OAuth), and MCP tool input exposed as `metadata.input` on permission requests (#51062 - a payload enrichment on the permission route we already consume; surfacing it in our permission dialogs is an optional enhancement, not an overlap). Overlap hunt 2026-10-16: every 2.0.30 feature is TUI/Desktop client UI or server-internal behavior - no RETIRE candidate; our diffs, steering, machine-wide views, live labels and permission surfaces stay (capability parity, not UI simulation) |

| New since v2.0.28 (live 2.0.29) | Operations | Decision |
|---|---|---|
| *No operation changes* - 2.0.29 is identical to 2.0.28 (142 vs 142 operation lines; 0 added, 0 removed, 0 changed) | - | **Nothing to consume** - the pin bump is version-only. The routes we use are unchanged, verified live 2026-10-14: `POST .../prompt` is still `{text, delivery(steer\|queue), ...}` under `additionalProperties:false`, `PATCH .../inbox/{id}` is still `{delivery}` (`Session.Inbox.Delivery` = `steer`\|`queue`), `GET /api/session` still takes `limit`/`order`/`search`/`parentID`/`directory`/`cursor`, `GET /api/event` is still `text/event-stream`, and `interrupt`/`fork`/`session.remove` are present. Behavior-only release notes - none touching a route we consume: responses hitting their output-token limit continue automatically (bounded retries), centrally managed configuration now takes precedence over local/user configuration, the access-policy config moves to the top-level `policies` key (still accepts `experimental.policies`), plus Desktop/TUI client UX (provider-account management, remote-pairing QR for the existing `pair` route, steered messages shown as pending immediately) |

| New since v2.0.27 (live 2.0.28) | Operations | Decision |
|---|---|---|
| *No operation changes* - 2.0.28 is identical to 2.0.27 (142 vs 142 operation lines; 0 added, 0 removed, 0 changed) | - | **Nothing to consume** - the pin bump is version-only. The routes we use are unchanged, verified live 2026-10-14: `POST .../prompt` body is still `{text, delivery(steer\|queue), ...}` under `additionalProperties:false`, `PATCH .../inbox/{id}` is still `{delivery}` (`Session.Inbox.Delivery` = `steer`\|`queue`), `GET /api/session` still takes `limit`/`order`/`directory`/`cursor`, `GET /api/event` is unchanged, and `opencode serve` still accepts `--hostname`/`--port` (the 2.0.28 `--port` removal only affected the VS Code extension launch path, opencode#49084) |

| New since v2.0.21 (live 2.0.27) | Operations | Decision |
|---|---|---|
| **External integration connect** | `POST integration/{id}/connect/external` (`integration.connect.external`) | **Not for us yet** - an external-identity OAuth connect verb; no plugin feature consumes it (the plugin uses the existing `integration/oauth` + `integration/key` flows). Re-visit with any provider-integration feature |

Policy (2026-10-12): the harness is not in production - **always ride the
latest opencode version**; the binary updates via npm and the pin follows the
live service after each cross-check.

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
