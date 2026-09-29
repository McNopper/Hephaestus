# History

Chronological record of milestones, sessions and findings - **the changelog**.
Current state and open work live in `ROADMAP.md`; this file is the only place
the historical evolution is kept.

**Kind:** `doc` / changelog. **Read by:** anyone reconstructing *why*
the current state is what it is. **Related:** `ROADMAP.md` (current plan).

## H0 — Consolidation ✅ 2026-08-17
Full reactor build green (~181 Java + 105 JS). Root hygiene, path
independence, `ActivityTracker` port. See git history for detail.

## H1 — Task board on Maven storage ✅ 2026-08-18
Store + tools landed 2026-08-17; v2 + mojos + dogfood landed 2026-08-18.
Dogfood cutover: `hephaestus` project seeded, S-01 carried the first
delivery tickets. Operational lesson: MCP servers started by a session
outlive config changes — restart sessions so tools rebind. The stdio tasks
server holds the tasks/tools jars open — during builds use
`-Dmaven.clean.failOnError=false` or stop the server PIDs.

## H2 — Eclipse surfaces ✅ 2026-08-18
Board + Fleet views, provider logos, Server view. Code-level; live UI check
rides the deferred Eclipse pass.

## H3 — Scale & depth ✅ 2026-08-18
Plural connections with virtualized viewers, SSE-driven fleet completion,
session detail window, chat abort/tool-parts/copy-code, first CDT
ProjectContext + markers bridge.

## H4 — CDT integration & chat polish ✅ 2026-08-18
Code-level; live checks ride the deferred pass.

## H5 — opencode deep integration ✅ 2026-08-23 (waves 1–6)
Every endpoint the client calls exists in the opencode v1.18.21 spec; the
entire previously-unused surface was consumed (TUI take-over, permissions
queue, command/shell, find/file, session lifecycle, project/vcs, config
PATCH, provider auth, global events). Six waves in one day.

## H6 — Dataflow V-pipeline ✅ functionally complete 2026-08-23
Stage field + advance/send-back, readiness machinery, V-pipeline board with
unmissable blocked state, per-stage fleet dispatch with SelfClaimPrompt.
Adopted as async pipeline (no phase gates).

## H7 — Chat-first control plane (adopted 2026-08-25)
Chat is the PRIMARY interface; Eclipse views are conveniences. `fleet_*`
MCP tools over stdio; store is the ground truth (never chat-rooted).
First cut landed 2026-08-25.

## Session 2026-08-25 — docs reconciliation
Split contracts.md/domains.md; review-wave defect fixes (32 files); docs
aligned with repo reality via four audit agents; positioning note (the
harness is deliberate weight for complex projects; the plain TUI suffices
for simple ones).

## Milestone P — Permissions & safety ✅ 2026-08-26 (S-04)
`fleet_permissions`/`fleet_permissions_answer` tools; the PermissionQueue
wired into the headless engine; generated server passwords
(`FleetControl.resolvePassword`); auto-dispatch defaults hardened
(`AutoDispatch.of(4, 5, false)`). Also landed: GitHub Actions CI
(`verify.yml`, windows-latest), `ServerVersionPin` (1.18.21),
store auto-sync after headless launches, staged live smoke suite
(`FleetLiveSmokeTest`), context-menu spike (U-001).

## Milestone V — First real fleet run ✅ PASSED 2026-08-28
The marker-file smoke (V-007) ran end-to-end: spawn/auth → pre-claim commit
→ worktree → executor worker (1345 tokens, $0.00) → completion detection →
merge-back → artifacts + `fleet actuals` → store auto-sync (which also
committed and pushed). **Seven defects found live, all fixed:** B-001
(worktree discovery by branch ref, not path); readiness probes didn't
authenticate (401s); the fixed 5-minute prompt cap killed longer runs;
`/session/status` busy-only semantics (absence = idle, TWICE — in the
poller AND in isComplete); the missing pre-claim commit (merge refused over
the engine's own dirty ticket file); the 90s store-clean flake (the sync
staged the TaskStore's transient `.lock` file — dirty forever once staged).
The quickstart landed as `docs/fleet-quickstart.md`.

## Session 2026-08-29 — watchdog redesign
Prompt POST on its own thread; probe-authoritative completion; stall
= idle-and-silent only; `fleet_job_details`; merge guard proven live
(W-004 auto-committed; a lazy worker refused honestly). Navi chain 4/6
stages MERGED (R/S/A/D verified against the pinned binding table).

## Session 2026-09-13 — review + rubberduck + opencode-docs + hardening
Store git discipline path-scoped to `.opencode/tasks` (repo-wide `add -A`
auto-committed+pushed unrelated WIP). Watchdog v2 (busy resets the stall
clock; permission waits pause it; budget timeout aborts). Peer store writes
committed before merge. `SelfClaimPrompt` explicit write scope. opencode API
verified stable 1.18.21→1.18.30 (pin bump recommended with an OpenAPI
`/doc` smoke; `prompt_async`, `DELETE/PATCH /session/:id`, `/children`
available since our pin). Stop-list confirmed. Full-repo docs consistency
audit applied. Evening session: F-001 (release-on-failure + readiness
BLOCKED>RUNNING + total-failure contract), R2 RepoGate, R3 (drain-then-kill
+ crash cleanup), F-002 (`fleet_reset`, merge reaping, startup
reconciliation), F-003 (cross-engine dispatch guard via atomic marker file)
— all landed, tested, pushed.

## Standing rules (live)
- Refactor cadence: every second session (due since 2026-08-18).
- On opencode upgrade: rerun the endpoint smoke (assert the OpenAPI `/doc`
  covers our whole surface), then bump `ServerVersionPin`.
- Once work runs on the board again, the store — not the roadmap — is the
  status source.

## Session 2026-09-16 ? the board goes empty

Parallel-wave session: four agent workstreams landed (peer-job Fleet view,
pristine Board labels + icons, store hardening with line quarantine,
chat-web polish) plus the live-complaint fixes - chat late-reply recovery
(a timed-out POST no longer strands a still-busy session; the watcher
settles from history, Stop stays armed, 30-min cap aborts), the model pin
(enabled_providers whitelist), and task_doctor (store lint as the 22nd
task_* tool). MCP endpoint token auth (G-003); U-005 type badges, B-002
peer-write Board visibility (watcher checksum, sprint auto-select,
repo-adoption root), U-007 live busy-session icons, U-002 batch C
(open-in-editor via temp-file FileStoreEditorInput - IStorageEditorInput is
gone from the platform - plus the MCP-servers dialog), G-004 tuning sweep
completed. V-006 fleet daemon slices a+b: authed TCP core, FLEET_DAEMON
stdio proxy with detach semantics, detached launcher; default stays off.
An independent clean-architecture review ran over the session diff; every
MUST/SHOULD finding fixed with regression tests (UI-thread store locks,
quarantine gap, x-friends coupling, mutable knob statics, read-side
materialization). Full reactor green throughout; documentation aligned
(README with/without-Eclipse matrix, JDK 21 everywhere, daemon quickstart).
Remaining: in-Eclipse verification pass only.

## Session 2026-09-20 - the wave runs, and the loop shows its teeth

Board focused on autonomy (wave-2026-09-20: reliability bugs B-004..B-007,
V-semantics U-029/030/031, features U-021/023/026/034 + new U-036/U-037).
B-006 landed in chat: dispatch reclaims stale merged branch residue in
GitWorktreeManager.createGuarded (5 real-git pins; 27/27 reactor green) -
the four-ticket stage-boundary stall class is gone, and it proved itself
live within the hour (B-004's stale merged branch reclaimed at dispatch).
The first full wave after the v2 migration then exposed the next layer:
TEN workers stalled identically (PT5M idle-silent). Root cause: opencode
resolves local MCP commands relative to the SESSION directory, worker
sessions live in worktrees, worktrees carry no target/ jars - every worker
silently lost its task_* tools and spiraled into hand-replicating store
semantics until the watchdog fired. Fixed in both MCP launchers
(worktree-aware resolution via git common-dir; verified by an MCP
handshake from inside a worktree). Second live gap: v2 completion
detection missed a FINISHED session - B-007's requirements worker
completed and reported, then got stall-aborted, and the settle never
merged its uncommitted deliverable (rescued by hand as 475d164; pinned on
B-008, which also tracks the PT30M budget killing busy U-024). U-024's and
U-030's WIP rescued on wip/* branches; the other stalls likewise. New
tickets: U-036 (daemon default flip), U-037 (live auto-dispatch
acceptance), U-038 (graceful full-stack shutdown - JDK-upgrade trigger;
includes the engine-addressing bug: a waves_stop via chat MCP hit the
chat's LOCAL engine, not the daemon), U-039 (chat session+model
persistence across restarts), U-040 (Fleet view as a tree), U-041 (v2
subagents+console tasks parity), U-042 (v2 background tasks), B-008
(watchdog semantics). Daemon lifecycle is scripted now (drive/status/stop
over the TCP protocol; docs updated: AGENTS.md, fleet-quickstart).

## H7.1 - Hardening, waves and the v2 migration (2026-09-14 to 2026-09-23)
The engine's reliability era. 2026-09-14: the production-readiness sweep
(G-001..G-006), UI batches A-C, the cross-process git/admission gates and the
first live deployment. 2026-09-16: the tuning-table sweep (G-004) and the V-006
fleet daemon slices. 2026-09-20: the v1 to v2 API migration (27/27 green,
commit df28c93), the waves-era run that filed B-008..B-013 plus U-036..U-043,
and host discipline adopted: "opencode or Eclipse - everything else is
reinventing the wheel". 2026-09-21: the v2-only cleanup cross-checked against
the live 2.0.11 service. 2026-09-22: new-machine bring-up (machine traps fixed,
cb82e17) and the no-hidden-work rule. 2026-09-23: the defect wave resolved
(B-008/B-011/B-012/B-013 - message-ordering normalization and the Turns
completion judge), the JobManager everywhere move, and v2/TUI feature parity
(plan mode = the plan agent, permission-ask recovery, background tasks,
T-004/T-005 chat surfaces). Tickets are the record of each finding.

## H8 - v2 capability alignment - 2026-09-25
Strict reuse policy audited against the live `GET /openapi.json` contract
(130+ operations, docs/opencode-v2-adoption.md): a capability comes from the
first tier that has it - opencode v2, then Eclipse, our own code only where
neither host has the thing; capability parity, not TUI simulation. Adopted:
session rename, background, shells, snapshots, fork, schema-driven forms
(the declared Form.Field types drive the typed Form.Reply answer), context
usage, permission REST reconciliation (floor semantics, wired into the
watchdog), the v2 worktree API for peer job reconstruction, MCP management
verbs. Quality: mojibake repaired repo-wide (124 sequences), B-010 (prose is
never paths), G-004 (timeout failures name their knob), javadoc placements.
Paired verification law (AGENTS.md): verification criteria are created and
linked at definition-stage entry. T-006 verified live (Defender exclusions:
git spawns ~22 ms). Squashed to one commit on top of v0.1.0 for push; the
sliced review trail is on wip/slices-v2-adoption. Remaining v2 rows stay in
the adoption matrix as 'adopt next'.

## H9 - Stage-aware acceptance and the wave hardening pass - 2026-09-29

- **B-007 stage-aware acceptance** (the big one): `StageEvidence` beside
  `VStages` is the single per-stage evidence matrix all three checkpoints
  consult (FR-005) - definition legs accept ticket/doc/store work and never
  trip the merge gate (the U-026 false rejection is regression-pinned),
  implementation keeps the AC-path rule, test-* stages expect tests/goldens.
  Store-side deliveries count as produced work (the settle check gained the
  `allowEmptyDiff` allowance), reviewer doubt round-trips to the ORIGINATOR
  (`review doubt retry (1/1)`, one per stage visit) before anything blocks,
  and the review prompt quotes the matrix row. Paired tests: StageEvidenceTest
  (one row per V stage), ReviewDoubtTest, StageAwareAcceptanceTest,
  ReviewDoubtRoutingTest, plus the git-level empty-branch allowance case.
- **Board polish pack (U-028)**: nine directional chevron connectors along
  the V reading order (derived from VStageLayout) and WIP counts - per
  column and board-wide on the readiness badge - strictly a count, no
  WIP-limit concept (BoardPolishPackTest).
- **O-002 project actions**: New project / Reset project on the Board store
  row (scaffold + explicit-confirm wipe of ONE project; ProjectActionsTest) -
  no more hand-deleting task-store files.
- **U-034 auto-deploy**: Eclipse-facing merge-backs trigger the reactor
  build + dropins refresh asynchronously (AutoDeploy + auto-deploy.ps1);
  red builds never deploy and surface as NEEDS-HUMAN; the user is told
  "restart Eclipse" only when jars landed.
- **U-045/B-005 shutdown + launcher hardening**: the `fleet_shutdown` tool
  and `eclipse/fleet-stop.ps1` complete U-038's one-action surfaces; both
  stdio launchers stage their jars into versioned dirs (the B-005 class of
  build-vs-running-server corruption is gone on the living processes).
- **U-026** requirements leg delivered (docs/requirements/U-026-v-flow-visibility.md)
  and the chain advanced to `system`; **U-030** coherence pass aligned 11
  skill/agent files with wave terminology and the autonomy contract.
- **Review leg run over the 17-ticket in-review queue**: 13 accepted
  (B-006, B-008, B-011, B-012, B-013, T-001, U-010, U-016..U-020, U-022 -
  the U-022 store record's conflict markers repaired), 4 sent back blocked
  with precise gaps (T-004/T-005/T-009/U-009). Store truth-up also closed
  verified-landed work (U-011, B-004, U-015) and retired U-024 (the daemon
  is gone).

## H10 - The autonomous feature-completion round - 2026-09-29 (afternoon)

Four parallel workers on disjoint file lanes, gated centrally, one amend:

- **Board polish complete**: equal column growth on resize (U-033 - root cause:
  style-only GridData never set grabExcess), refresh-on-activate via a 5s rate
  gate + visible freshness stamp (U-035), the Board Shutdown button wired
  through a new FleetLauncher seam to the public TaskFleet.shutdownForMaintenance
  (U-045's last surface; engine-less shutdown parks the gate directly).
- **U-046 slice 2 - the v2.0.19 surface gets its UI**: Saved-permissions manager
  (list + remove), read-only Integrations view, v1-migration banner,
  Attach-skill in Session Details (SkillInfo gained the id; the attach body is
  {"skill": id} per the official spec - the OpenAPI's "id" member is a msg_
  anchor, caught before shipping a 400), and Suggest-title via generateOnSession
  (prefills Rename; never auto-renames).
- **U-047 TUI parity - chat side**: /init /help /thinking (the /help list derives
  from the same registry recognition uses - it cannot drift), @-file fuzzy
  references in the composer (U-012; existing client fs/find, no content
  injection), chat continuity across restart (U-039: last session + model in
  DialogSettings, primary-view restore, silent degrade), and /share + /unshare as
  honest not-implemented notices - the v2 TUI itself toasts "Sharing is not
  implemented for V2 sessions yet" (verified against its source).
- **Attention parity**: jface NotificationPopup (the org.eclipse.ui.notification
  package does not exist in the 2026-06 target - verified, not guessed) +
  Display.beep, off by default like the TUI; permission asks, session errors and
  completed sessions classified from the live event stream via the existing
  public OpencodeConnection seam; IStartup-registered, zero edits to existing
  views. QUESTION kind reserved until a form-ask SSE type is confirmed.

Also this round: deploy of the morning commit to C:\eclipse-cpp, six tickets
claimed and closed in the store, and the roadmap now reflects the true
remainders (U-043 deliberately deferred - it changes core store semantics).

## H11 - The full-surface round (quota-split) - 2026-09-29 (afternoon)

Five parallel workers landed wave 1, then the shared model quota died mid-wave-2;
the orchestrator (this session's model) completed the remaining four lanes and the
gate caught what unverified worker output hides (7 test bugs, all fixed in-review):

- **U-048 - the v2 surface remainder is complete**: integration connect flows
  (command/key/oauth with attempt polling + abort in the Integrations dialog),
  the fleet watchdog long-polls session/wait (deadline-aware window, anti-spin,
  pause-while-asks-pending, poll fallback; 204 = settled), Session Details marks
  viewed, two-phase revert/undo/commit actions, environment full-replace dialog,
  session import with preview. waitForSession verb + 10 integration/env/import
  verbs (16 component tests).
- **U-041 - subagents and console/shell tasks for every session**: nested under
  their parent in the Server view AND Session Details AND the Fleet tree; shell
  tasks carry status/exit and their output tail opens in an Eclipse console
  (Session Details) or an in-board dialog (the board bundle has no ui dep).
- **U-040 - the Fleet view is a tree**: engine -> wave -> job (ticket badges in
  the Board's U-005/U-006 language) -> session -> subagents -> shells, live
  activity per node, tokens/cost rolled up, level-appropriate actions, peer
  engines as a named first-class root.
- **U-026 - V-flow visibility**: per-card stage progress (visited/10), the
  stage-journey trace in ticket details (advance/send-back/pass with reasons),
  the wave digest. Caught a real defect: TicketRow.stageDepth() NPE'd on
  role-untrackable tickets (List.of().indexOf(null)) - one such ticket crashed
  the entire board refresh; fixed null-safely with a regression test.
- **U-014 - chat prompts**: evaluation first (banner path existed); the in-chat
  permission dialog (one per request, current session only), answerable
  question-form cards polled while a send is in flight (no form-ask SSE type
  exists), pending-state notices. @alias reference roots merged into the
  @-dropdown (empty catalogs degrade).
- **O-001 - repo-driven self-configuration remainder**: the OpenCode repo
  nature + the Board's "Adopt repo..." action (root .project writer, nested-
  project refusal with the auto-discovery explanation, pruned scan).
- **T-003 root-caused (upstream)**: v2 renames the per-server off-switch to
  `disabled` and silently drops the legacy `enabled` key (normalize.ts +
  mcp/index.ts citations in docs/new-machine-setup.md); our opencode.json now
  spells "disabled": true for fleet. Also landed: the mojibake repair sweep
  (AGENTS.md carried QUINTUPLE damage; TaskStore.java's comment triangles
  forensically reversed with round-trip proof), the PTY-host finding (no TM
  Terminal in the target; no stdin REST route - parked), and a build.ps1
  3-arg Join-Path fix (PS 5.1 broke with JAVA_HOME set).
