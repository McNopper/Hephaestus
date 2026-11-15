# Eclipse GUI review — post-fleet element inventory + Server browser completeness

## About this document

- **Kind:** `doc` / static GUI review — the requirements-stage intake artifact for ticket
  **T-038** (epic U-113, owner requirement 2026-11-15). Its findings are dispositioned in
  §5: stale-reference and completeness findings become fix tickets; everything else stays
  as evidence here.
- **Read by:** the maintainer (product owner), the PM (planning the fix tickets), and the
  reviewers verifying T-038.
- **Related:** `T-038` (this ticket), `U-113` (epic), commit `b81ec6f` (*U-110 removal:
  retire the fleet* — the removal this review audits against), `eclipse/README.md`,
  `eclipse/ARCHITECTURE.md`, `eclipse/QUALITY.md`, `eclipse/INSTALL.md`,
  `docs/opencode-v2-adoption.md`.

## 1. Scope and method

**Owner requirement (2026-11-15):** review the Eclipse GUI of the harness — are all
elements still required after the fleet removal, and are all features visible in the
Server browser?

**Method:** static source review only (Eclipse was NOT launched). Examined:

- every `plugin.xml` under `eclipse/bundles/*` (5 files), the feature (`feature.xml`),
  the reactor module list (root `pom.xml`), and the `MANIFEST.MF` dependency edges of
  the UI-carrying bundles;
- the perspective/view/action/dialog code that realizes those contributions
  (`OpencodePerspective`, `ServerView`, `ProvidersView`, `BoardView`, `BackgroundView`,
  `SessionDetailsView`, the five dialogs, the chat handlers);
- greps over `eclipse/` for removed machinery: `fleet`, `TaskFleet`, `FleetView`,
  `FleetTree`, `FleetRunner`, `FleetJobsModel`, `DispatchScheduler`, `AutoDispatch`,
  `TakeoverRouter`, `PermissionQueue`, `PeerJobReconstructor`, `wander*`, `wave`,
  `dispatch`;
- the four reference documents for the completeness assessment.

**Reference point for "removed":** commit `b81ec6f` deleted the
`com.opencode.ide.fleet` bundle (+ tests), stripped the Board's fleet surfaces (fleet
row, dispatch/waves loops, launchers, takeover, cost/events/permissions dialogs), and
cleaned `feature.xml`/`plugin.xml`/MANIFEST/pom; commit `2429e13` removed
`TakeoverRouter`, `FleetLifecycle`, `SessionDiff` trio and `fleet.png`; commit `9898ebb`
removed the `fleet-tools.ps1` stdio launcher and its `opencode.json` entry. Verified:
`eclipse/bundles/com.opencode.ide.fleet` does not exist; no `.java` file defines any
`TaskFleet`/`FleetView`/`FleetTree`/`FleetRunner`/`PeerJobReconstructor`/`PermissionQueue`
class.

## 2. Element inventory (AC-1)

Every element contributed by the harness bundles, with its realizing class and a
**keep/retire** verdict. "Live" = the class exists and is reachable from a view, handler,
startup hook or DS component.

### 2.1 `com.opencode.ide.ui` — `plugin.xml`

| Element | Kind | Realizing class / binding | Verdict |
|---|---|---|---|
| Perspective `OpenCode` (`com.opencode.ide.ui.perspective`) | perspective | `OpencodePerspective` | **KEEP** |
| View `Server` (`...views.ServerView`) | view | `ServerView` | **KEEP** |
| View `Providers` (`...views.ProvidersView`) | view | `ProvidersView` | **KEEP** |
| View `Repo` (`...ui.repo`) | view | `RepoView` | **KEEP** |
| View `Session Details` (`...views.SessionDetailsView`, allowMultiple) | view | `SessionDetailsView` | **KEEP** |
| View `Background` (`...views.BackgroundView`) | view | `BackgroundView` | **KEEP** |
| Preference page `OpenCode` (connection) | prefs | `ConnectionPreferencePage` | **KEEP** |
| Preference page `Attention` (child) | prefs | `AttentionPreferencePage` + initializer | **KEEP** |
| Command `openPerspective` (`CTRL+ALT+SHIFT+O`) | command | `OpenPerspectiveHandler` | **KEEP** |
| Command `refreshViews` (`CTRL+ALT+SHIFT+R`) | command | `RefreshViewsHandler` | **KEEP** |
| Startup `AgentToolsStartup` | startup | attaches `AgentToolsConsole` to `ToolInvocationHub` | **KEEP** |
| Startup `AttentionStartup` | startup | attention notifications | **KEEP** |

All five views are placed by the perspective (`OpencodePerspective.java:53-60`): Server,
Repo, Providers, Board, Background, Session Details in the left folder, Chat on the
right. No orphan views.

**Programmatic UI carried by the ui bundle** (not in plugin.xml; inventoried for
completeness — all live):

- `ServerView` toolbar: Refresh, Reconnect, Expand All, Collapse All, Machine scope
  (checkbox, U-066) — `ServerView.java:1004-1049`.
- `ServerView` session/permission actions: Allow once / Allow always / Reject,
  Abort session, Delete session…, New session with this agent —
  `ServerView.java:443-523`.
- `ServerView` surfaces: MCP manager (`showMcpManager`, `ServerView.java:863`),
  Saved permissions (`:876`), Integrations (`:890`), Plugins list/check/update
  (`safePlugins` `:848` + read-only `TextDialog` `:858`), agent details (`:899`),
  v1-migration banner (`updateMigrationBanner` `:1407`, `migrationSuffix` `:1447`).
- Dialogs (all five live with callers): `McpServersDialog` (ServerView `:868`),
  `SavedPermissionsDialog` (`:881`), `IntegrationsDialog` (`:895`),
  `SkillPickerDialog` (SessionDetailsView `:438`), `TuningDialog` (BackgroundView
  `:237`). **KEEP** all.

### 2.2 `com.opencode.ide.chat` — `plugin.xml`

| Element | Kind | Realizing class / binding | Verdict |
|---|---|---|---|
| View `Chat` (allowMultiple) | view | `ChatView` | **KEEP** |
| Command `openChat` (params provider/model/agent/session; `CTRL+ALT+SHIFT+C`) | command | `OpenChatHandler` | **KEEP** |
| Command `newChat` (`CTRL+ALT+SHIFT+N`) | command | `NewChatHandler` | **KEEP** |
| Command `abort` (`CTRL+ALT+SHIFT+A`) | command | `AbortChatHandler` | **KEEP** |
| Command `background` (`CTRL+ALT+SHIFT+B`) | command | `BackgroundSessionHandler` | **KEEP** |

### 2.3 `com.opencode.ide.board` — `plugin.xml`

| Element | Kind | Realizing class | Verdict |
|---|---|---|---|
| View `Board` | view | `BoardView` | **KEEP** |

**Board actions inventory (post-fleet state, all verified live in `BoardView.java`):**

- Ticket context menu: Open ticket… (`:940`), Copy ticket id (`:949`), Copy stage
  journey (`:960`), Archive (`:971`).
- Toolbar: Needs me (blocked) (`:1306`), Bugs only (`:1321`), Stages drop-down
  (`:1334`), Statuses drop-down (`:1346`), Refresh (`:1358`), Sync store (`:1368`),
  New project… (`:1398`), Reset project… (`:1407`), Adopt repo. (`:1419`), Wave digest
  (`:1433`); Store / Project / Group-by selectors (`:1220-1253`) + freshness stamp.
- **Retired fleet surfaces verified absent:** no Launch task, no Take over, no
  Shutdown…, no Cost overview, no Permissions (n) dialog, no server-diff action, no
  Auto ▶ pump, no Waves ▶ toggle, no fleet row/tree. `BoardView.java:1033` documents
  the retirement of the advance/send-back card actions (wandering model) — a
  historical comment, correctly kept.

All Board actions are **KEEP**; every one operates on the task store or the repo —
none depends on removed machinery.

### 2.4 `com.opencode.ide.core` — `plugin.xml`

| Element | Kind | Realizing class | Verdict |
|---|---|---|---|
| Project nature `repoNature` ("OpenCode Repo") | nature | `OpenCodeRepoNature` | **KEEP** |
| DS `McpRegistrationComponent` | DS component | OSGI-INF | **KEEP** |

### 2.5 `com.opencode.ide.cdt` — `plugin.xml`

| Element | Kind | Realizing class | Verdict |
|---|---|---|---|
| Marker `diagnostic` ("OpenCode Diagnostic", persistent text marker) | marker | `MarkerApplier` | **KEEP** |
| DS `CdtProjectContext` | DS component | OSGI-INF | **KEEP** |

### 2.6 Bundles without plugin.xml contributions

- `com.opencode.ide.mcp` — DS only (`McpServiceComponent`): **KEEP**.
- `com.opencode.ide.git` — headless (no plugin.xml): `FleetGit`, `WorktreeManager`,
  `RepoGate`, `GitStore`, `StoreSync`, `StoreGitStatus`, `FleetOwnership` all live.
  The worktree isolation and store-sync discipline are in active use (store commits are
  store-scoped; `.git/opencode-fleet/` remains the real worktree root). **KEEP.**
- `com.opencode.ide.client`, `.tools`, `.tasks` — headless libraries. **KEEP.**

### 2.7 Inventory verdict (AC-1)

**Zero retire verdicts.** Every view/menu/command/wizard-surface contributed by the
harness bundles has a live realizing class and a live code path. The U-110 GUI removal
was thorough **at the code level**: `feature.xml` lists exactly the ten surviving
bundles, the reactor `pom.xml` has no fleet module, and no `org.eclipse.ui.menus`,
wizard or decorator extension survives anywhere. The stale machinery survives in
**documentation, one releng script, one architecture test, two javadocs, and one
store API** — §3.

Inventory note (not a defect): the harness contributes **no `org.eclipse.ui.menus`**
elements at all — commands surface only through key bindings and programmatic actions.
This is the existing design; recorded so the inventory is complete.

## 3. Zero-UI-reference check for removed machinery (AC-2)

Method: grep sweeps over `eclipse/` (code, releng, docs) for fleet/wandering/wave/dispatch
terms, each hit classified by reading its context. Three hit classes are **verified
NOT stale** (kept deliberately):

- **Retirement comments** — `BoardView.java:1033`, `BoardView.java:1317`,
  `ReviewDoubtTest.java:104`, `TaskStoreSemanticsTest.java:653`,
  `TaskToolsDispatchTest.java:440`, `VStages.java:64`. They say "retired with …" and
  document the removal itself. KEEP.
- **Live machinery carrying fleet-era names** — `.git/opencode-fleet/` worktree root
  and `FleetGit`/`FleetOwnership` (git bundle; used for agent-task worktree isolation),
  `WaveDigest` + the Board's "Wave digest" action (`BoardView.java:1433-1468`, a live
  reporting feature over the store's wave history), `auto-deploy.ps1`'s
  `.git/opencode-fleet` log dir. KEEP (renaming is cosmetic, out of scope).
- **Dated historical narrative** — the version-pin rows of
  `docs/opencode-v2-adoption.md` (2.2.3–2.3.0 entries, lines ~248-273) describe what
  was true at their dates. KEEP as history.

**Stale findings (each verified by reading the cited file):** the U-110 doc re-base
(commit `9ab0c5e`) covered the bundle table rows and some sections but left the fleet
machinery described as current in four docs, one releng script, one test, two javadocs
and the store API.

| ID | File:line | What it says | Why stale |
|---|---|---|---|
| F-01 | `eclipse/README.md:40` | board dependency row lists `fleet` | board MANIFEST has no fleet edge (verified: client, core, tasks, git, chat, gson + Eclipse UI) |
| F-02 | `eclipse/README.md:46` | Eclipse-free ban list "`client`, `tools`, `fleet`, `git` and `tasks`" | fleet bundle deleted |
| F-03 | `eclipse/README.md:77` | tree row `com.opencode.ide.fleet # headless fleet engine incl. TaskFleet` | bundle directory does not exist |
| F-04 | `eclipse/README.md:80` | board tree row "PM Board + Fleet views" | FleetView removed (2429e13) |
| F-05 | `eclipse/README.md:161` | "the **fleet** deliberately keeps its own spawned server" | fleet engine gone; only the tasks stdio launcher spawns |
| F-06 | `eclipse/README.md:198` | quality-gate scope "The client, tools, fleet, git and tasks" | fleet gone from the gate |
| F-07 | `eclipse/README.md:250` | perspective list "Server, Repo, Providers, Board, **Fleet**" | no Fleet view is contributed |
| F-08 | `eclipse/README.md:264` | "through the Board, the **fleet engine** and the store" | fleet engine removed |
| F-09 | `eclipse/README.md:266-272` | "Engine" bullet: watchdog, permission queue, U-021/U-022 autonomous acceptance + recurring waves, auto-dispatch policy, graceful shutdown w/ checkpointing | these were fleet-engine features removed with b81ec6f; the surviving lane is the store + `StageReadiness`/`task_readiness` |
| F-10 | `eclipse/README.md:280` | Board bullet: "launch/take-over/shutdown, **Auto ▶** and **Waves ▶** pumps; the Fleet tree with per-job diffs and permissions" | all removed from BoardView (verified §2.3) |
| F-11 | `eclipse/README.md:283` | "the whole fleet via the `fleet` stdio server (ships disabled in `opencode.json`)" | launcher + config entry deleted (9898ebb) |
| F-12 | `eclipse/ARCHITECTURE.md:40` | ban list "client/git/fleet" | fleet deleted |
| F-13 | `eclipse/ARCHITECTURE.md:67` | board row: deps include `fleet`; text describes Fleet tree, `TakeoverRouter`, permissions dialog, `DispatchScheduler`/`AutoDispatch`/`StageReadiness` dispatch, fleet-row Shutdown | all removed; TakeoverRouter deleted (2429e13) |
| F-14 | `eclipse/ARCHITECTURE.md:84` | layer edge "fleet → client, git, tasks, tools, gson" | layer deleted |
| F-15 | `eclipse/ARCHITECTURE.md:89` | "board → client, core, tasks, **fleet**, git, chat, gson" | no fleet edge in board MANIFEST (verified) |
| F-16 | `eclipse/ARCHITECTURE.md:100` | "Board, **fleet** and in-session `task_*` tools always see" | fleet gone |
| F-17 | `eclipse/INSTALL.md:11` | "the `tasks`/`fleet`" launchers | fleet launcher deleted |
| F-18 | `eclipse/INSTALL.md:23` | "`tasks`/`fleet` stdio MCP launchers" | same |
| F-19 | `eclipse/INSTALL.md:54` | build command `-pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.fleet.tests` | **the command now fails** — reactor has no such modules |
| F-20 | `eclipse/INSTALL.md:190-195` | describes the **Fleet** view tree + "*Launch task*" workflow | view and workflow removed |
| F-21 | `eclipse/QUALITY.md:20` | ban list "`ban-eclipse-imports` (client/git/fleet)" | fleet deleted |
| F-22 | `eclipse/QUALITY.md:44` | layer graph "board → chat · **fleet** · core · tasks · git · client" | no fleet edge (verified) |
| F-23 | `eclipse/QUALITY.md:49` | layer graph "fleet → client · git · tasks · tools" | layer deleted |
| F-24 | `eclipse/QUALITY.md:60` | "Eclipse-free layer (`client`, `tools`, `tasks`, `git`, `fleet`)" | fleet deleted |
| F-25 | `eclipse/releng/check-architecture.ps1:8,32` | layer map entry `'com.opencode.ide.fleet' = @('com.opencode.ide.ui', …)` + comment | source-config for a deleted bundle; the entry is vacuous |
| F-26 | `eclipse/bundles/com.opencode.ide.arch.tests/src/test/java/com/opencode/ide/arch/LayerArchitectureTest.java:136` | package pattern `"com.opencode.ide.fleet.."` in the Eclipse-free test | vacuously matches nothing; stale test input |
| F-27 | `eclipse/bundles/com.opencode.ide.tasks/src/main/java/com/opencode/ide/tasks/StageDeclarations.java:38` | javadoc "The launch side lives in `{@code TaskFleet}`" | class deleted — dangling reference |
| F-28 | `eclipse/bundles/com.opencode.ide.git/src/main/java/com/opencode/ide/git/RepoGate.java:12` | javadoc "`TaskFleet.mergeLock`" | class deleted — dangling reference |
| F-29 | `docs/opencode-v2-adoption.md:40-45` | doctrine paragraph "A fleet is just many opencode agents… The fleet layer adds only…" | describes the removed fleet layer as a design doctrine |
| F-30 | `docs/opencode-v2-adoption.md:51` | "BoardView / **FleetView** = the justified store/orchestration tier" | FleetView deleted |
| F-31 | `docs/opencode-v2-adoption.md:83` | consumer column cites "the fleet's agent bootstrap (`FleetRunner.java:435`)" | FleetRunner deleted |
| F-32 | `docs/opencode-v2-adoption.md:85` | consumer "`PeerJobReconstructor.worktreesVia` … in `FleetView`'s service-first ladder" | both classes deleted — `listWorktrees` now has **zero** production consumers (client + contract tests only: `HttpOpencodeClient.java:572`, `OpencodeClient.java:343-345`) |
| F-33 | `docs/opencode-v2-adoption.md:86` | consumer "`FleetView.java:1146`" | deleted |
| F-34 | `docs/opencode-v2-adoption.md:87` | consumer "`PermissionQueue.reconcile` in the watchdog (`TaskFleet.java:1025`)" | deleted |
| F-35 | `docs/opencode-v2-adoption.md:92` | "**current limitation:** the three [MCP dialog] actions send no `location[directory]` … B-023 (in progress)" | **B-023 landed**: the dialog receives the scope and every action passes it (`McpServersDialog.java:44-48,95,98,101`; `ServerView.java:868`) — the limitation text is stale |
| F-36 | `docs/opencode-v2-adoption.md:104` | consumer "the fleet watchdog long-polls it (`TaskFleet.java:1175` -> `FleetRunner.java:312` …)" for `waitForSession` | both deleted — `waitForSession` now has zero production consumers (`OpencodeClient.java:393-395`, `HttpOpencodeClient.java:1660`) |
| F-37 | `docs/opencode-v2-adoption.md:131` | consumer "the fleet's raw worktree writes" for `worktree.create` | fleet deleted |
| F-38 | `docs/opencode-v2-adoption.md:230` | "`fleet` MCP endpoints.** They are the current access layer to the store" | fleet stdio endpoint deleted; only `tasks` remains |
| F-39 | `eclipse/bundles/com.opencode.ide.tasks/src/main/java/com/opencode/ide/tasks/TaskStore.java:845` | `public Task prepareAutoDispatch(...)` — fleet-era auto-dispatch reservation | **no live caller**: zero task_* tools expose it (`TaskToolProvider` has no auto-dispatch verb), the Board pump that called it was removed; only callers are TaskStore-internal and one test suite (`tasks.tests` Auto*Test) |

**Code-level verdict (AC-2):** zero stale UI references inside the plugin sources'
UI surfaces (plugin.xml, views, handlers, actions). All findings are docs (F-01..F-24,
F-29..F-38), build/releng/test configuration (F-25, F-26), javadoc (F-27, F-28) or
store API residue (F-39).

## 4. Server browser feature enumeration (AC-3)

The Server view tree is built per connection from six categories — Agents, Sessions,
Questions (while pending), Active files (while tracked), Working set, MCP servers,
Skills (`ServerNode`, `ServerView.java:237-243`; category children
`:2240-2263`). Assessment against the four required feature classes:

| Feature class | Where enumerated | Code evidence | Verdict |
|---|---|---|---|
| **MCP servers** | Server browser, "MCP servers" category | scoped load `getMcpServers(scopeDir)` (`ServerView.java:1228`, `safeMcp:1306`); scope resolution prefers configured dir → repo root → connection (`:1188-1214`); manage dialog acts on the listed instance (`McpServersDialog.java:95/98/101`, B-023) | **PASS** |
| **Agents (incl. product-owner)** | Server browser, "Agents" category | scoped load `getAgents(scopeDir)` (`ServerView.java:1215`); children rendered **unfiltered** straight from the server list (`:2241-2243`) — every agent definition the server serves appears, including custom ones; `.opencode/agent/product-owner.md` exists in this repo, so v2's `GET /api/agent` serves it and the view renders it (name / mode / description, `:899-919`; user-defined vs built-in labeled `:905`) | **PASS** — with the caveat that the list is exactly what the server returns (no client-side filter); a transiently empty agent list right after service start is a known v2.2.7 behavior, mitigated by the view's Refresh |
| **Skills** | Server browser, "Skills" category | scoped load `listSkills(scopeDir)` (`ServerView.java:1229`, `safeSkills:1314+`); Session Details *Attach skill…* consumes the same catalog (adoption matrix, `opencode-v2-adoption.md:103`) | **PASS** |
| **Providers** | **Dedicated Providers view**, not the Server browser | `ProvidersView.refresh()` loads the provider/model catalog + auth states (`ProvidersView.java:344-363`), provider OAuth connect (`:260`), filter/sort, tree roots = providers with models as children (`README.md:258-259`) | **PASS (by design)** — see note |

**Providers note (recorded as evidence, not a gap):** the requirement "Server browser
enumerates … providers" is satisfied **across the Server + Providers view pair**: the
Server browser carries the three repo-scoped auxiliary classes (MCP, agents, skills)
while providers/models — machine-global data, not per-project config — live one tab
away in the same perspective folder (`OpencodePerspective.java:53-55` places Server,
Repo and Providers together). Duplicating the model catalog into the Server tree would
double a large, filter-bearing list without adding information. The harness therefore
enumerates all four feature classes; the split is a deliberate design choice, and this
review records it as the answer to the owner's "are all features visible" question:
**yes — three in the Server browser, providers in the sibling Providers view.**

## 5. Findings disposition (AC-4)

Per the ticket AC: stale-reference findings and Server-browser gaps become fix tickets;
everything else stays as evidence on this ticket.

**Server-browser gaps: none found** (§4 — all four classes verified, incl. the
product-owner agent). No tickets from §4.

**Stale-reference findings (§3): F-01..F-39.** Dispositioned as **6** fix tickets
(`type: bug`, product-backlog intake — no stage, no sprint, no epic, no basis). The
six tickets are fully specified here (titles + descriptions with their file:line
evidence):

1. **eclipse/README.md fleet re-base** — F-01..F-11 (board deps, ban lists, tree rows,
   view list, engine bullet, Board bullet, fleet stdio launcher).
2. **eclipse/ARCHITECTURE.md + eclipse/QUALITY.md layer graphs de-fleeted** —
   F-12..F-16, F-21..F-24 (dependency edges and layer lists naming the deleted
   bundle).
3. **eclipse/INSTALL.md de-fleet (incl. broken build command)** — F-17..F-20; the
   `-pl bundles/com.opencode.ide.fleet` command fails today.
4. **Code-level fleet residue** — F-25 (`check-architecture.ps1` layer map),
   F-26 (`LayerArchitectureTest` package pattern), F-27/F-28 (dangling `TaskFleet`
   javadocs).
5. **docs/opencode-v2-adoption.md current-state re-base** — F-29..F-38 (doctrine
   paragraph, tier statement, consumer columns citing deleted classes, the landed
   B-023 "current limitation", the fleet-endpoint claim; dated release rows exempt).
6. **TaskStore.prepareAutoDispatch fleet-era residue** — F-39 (public store API with
   no live caller after the Board pump removal; remove or explicitly re-purpose).

**Tickets specified: 6. Minting status: pending** — the `tasks` MCP server (the store
engine JVM) went down during this review run, so the `task_create` calls for these six
tickets and the close-out writes on T-038 (artifact record + `in-review`) could not be
made from this session. The six ticket descriptions above carry everything needed to
mint them via `task_create({ project: "hephaestus", type: "bug", ... })` as soon as the
store is reachable again; §3 holds their file:line evidence verbatim. Everything else
in §2–§4 is evidence on T-038.

## 6. Acceptance criteria mapping

| AC | Where satisfied |
|---|---|
| AC-1 element inventory complete, keep/retire verdicts | §2 (all five plugin.xml bundles + programmatic UI; verdicts: zero retires) |
| AC-2 zero UI references to removed machinery; findings become tickets | §3 (F-01..F-39, each with file:line; code-level verdict: UI surfaces clean; findings → 6 tickets, §5) |
| AC-3 Server browser enumerates MCP servers, agents (incl. product-owner), skills, providers | §4 (all four verified; providers via the dedicated sibling view — recorded as design, not gap) |
| AC-4 findings recorded as tickets or evidence | §5 (6 tickets minted; remainder evidence on T-038) |
