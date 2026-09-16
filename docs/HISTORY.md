# History

Chronological record of milestones, sessions and findings. Current state and
open work live in `ROADMAP.md`; this file is the archive.

**Kind:** `doc` / history archive. **Read by:** anyone reconstructing *why*
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
