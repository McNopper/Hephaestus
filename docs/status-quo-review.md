# Status quo review — 2026-09-14

## About this document
- **Kind:** review/status report for the follow-up implementation.
- **Read by:** maintainers and operators; **written by:** the implementation agent.
- **Related:** `../ROADMAP.md`, `fleet-quickstart.md`, and the Eclipse test bundles.

## Confirmed issues addressed

| Issue | Change | Verification |
|---|---|---|
| Reset could remove a live peer's worktree | Reset acquires the same ticket reservation as Board/chat dispatch before cleanup | `FleetToolProviderTest.resetRefusesAnotherEnginesReservationWithoutChangingTicket` |
| Shutdown could accept a new engine/leave a marker | Closed lifecycle check and submission-failure cleanup | `FleetControlTest.dispatchAfterCloseCannotSpawnOrLeaveAReservation` |
| Retry status could show the preceding failed job | Accepted launch supersedes terminal entries in the in-flight snapshot | Fleet control tests |
| Linked worktrees used incompatible `.git` paths | Resolve common git directory for worktrees and reservations | `DispatchGuardTest` |
| Reconciliation could release a live pre-claim | Skip claims with shared reservations | Task fleet regression suite |
| AC-path verification silently failed open | Unreadable diff blocks merge and preserves the worker tree | Task fleet regression suite |
| Chat lacked automatic scheduling | Move existing pure scheduler/policy/cost classes into fleet; add start/status/stop tools | Shared policy tests and tool lifecycle test |
| Scheduler budgets ignored in-flight costs | Reserve the estimate per running job; serialize capacity admission across processes | `AutoDispatchTest`, `DispatchGuardTest` |
| GUI Batch B missing | Delete, abort, primary-agent new chat; selected-server mutation routing | HTTP client tests; visual acceptance pending |
| Model/agent preselection lost during loading | Retain requested selections through asynchronous selector population | Build; visual acceptance pending |
| Different projects could reset the same ID's retained work | Persistent project ownership checked before launch/reset | `FleetOwnershipAndReworkTest` |
| Process-local Git locks did not serialize peer JVMs | Reentrant file gates shared across linked roots; scoped commits preserve unrelated staged files | `RepoGateProcessTest`, `DispatchProcessTest` |
| Completed stale tickets were admitted but could not run | Atomic task-store readiness/reopen transaction, guarded auto-launch contract | `AutoDispatchPreparationTest`, real-Git rework tests |
| Async preparation deferral suppressed unchanged children forever | Per-submission feedback tokens release only that deferred attempt | `DispatchLifecycleTest`, `TaskFleetLauncherAdmissionTest` |
| Board launch/stop could block SWT or outlive scope cancellation | Extracted `BoardDispatch`, background waits, cancellation checked under admission | `BoardDispatchTest` |
| Linux classpaths, discovery and path comparisons assumed Windows | Native path separators/executable checks; GCC/Clang discovery; Unix case-sensitive paths | Cross-process claim, native toolchain, binary resolver and CWD tests |

## Architecture review outcome

The independent review raised five concrete blockers over successive passes; all
were fixed with regressions and the final review approved the source. Dependency
direction remains Board → fleet → git/tasks/client, with no reverse UI dependency.
Task readiness transitions belong to tasks; ownership/file gates belong to git;
fleet owns scheduling; views use focused selection and dispatch models.

## Verification status

- A real temporary git repository regression injects a session-creation failure,
  checks blocked/released state, resets via the tool, then retries and merges an
  actual file. The model client is a deterministic fake; this is not a live-model run.
- **PASS:** full 27-module Maven/Tycho reactor on Windows, 2026-09-14 14:08 local:
  `eclipse/build.ps1 clean verify -o "-Dmaven.clean.failOnError=false"`.
  Includes real HTTP stubs, git worktrees, CDT workspace markers, and Edge-backed
  chat rendering. The failure/reset/retry regression passed. The opt-in live-model
  smoke remains skipped. `git diff --check` also passed.
- **PASS:** separate native SWT/JFace Server menu smoke, including equal session IDs
  under different server roots. Deterministic selection/catalog and queued launch
  cancellation tests also pass. Full workbench screenshots, first-launch UX, and
  live-model acceptance remain open; the native smoke is opt-in.
- **PASS:** WSL Ubuntu GCC/G++ 13.3 configured, built and ran the mixed-language
  fixture with Ninja and Unix Makefiles, including compile-command export.
  Linux Java/Tycho integration remains unverified because WSL lacks a JDK (also
  Maven, PowerShell and Node). Ubuntu CI remains non-blocking and now archives
  failure reports. No new CI run was triggered in this session.

## Operating boundaries

- Auto-dispatch is opt-in and scoped to an explicit sprint. Start/status/stop affect
  the current MCP engine; accepted jobs continue after stopping the loop.
- The admission budget is an estimate, not a billing guarantee. Explicit manual
  launches are outside the scheduler cap. Use consistent caps for peer schedulers.
- Unknown/malformed marker ownership is retained; reset only sweeps known dead PIDs.
- Fleet branch names still use ticket IDs, so each ID slot is permanently bound to
  its project via `.project` metadata, including after reset/reaping. Use distinct
  ticket prefixes across projects. Colliding projects are refused rather than
  sharing or deleting one another's work. Legacy unowned residue needs explicit
  ownership investigation and recovery before reuse; reset does not adopt it.
- Chat/transcript views currently use the primary connection, so remote rows do not
  open those views. Remote delete/abort use the selected connection's client.
- The AC-path check proves a named path changed, not correctness or model reliability.

## Live first-launch pass (2026-09-14, second session — first real deployment)

The first actual `deploy-dev.ps1` deployment into the running Eclipse CDT
install exposed four bugs no headless test could catch, all fixed and rebuilt
(the full reactor passed again after each fix):

| Finding | Cause | Fix |
|---|---|---|
| Whole GUI broken: every view "Could not create", `IllegalStateException: instance data location not specified` | `CoreActivator.start()` eagerly built `OpencodeConnection`/`ConnectionsManager` → `InstanceScope` before the runtime's instance area exists | Lazy OSGi `ServiceFactory` registrations; preferences materialize on first use; graceful tasksRoot-bridge retry (`LinkageError`-safe) |
| CDT integration disabled: Felix SCR `Circular reference ... CdtProjectContext` | The same activator opened the `ProjectContext` tracker in `start()`, re-entering the CDT component creation that had triggered core's lazy activation | Tracker opens lazily on first `getProjectContext()` |
| Views up but every REST/SSE call HTTP 401 | `buildSpawnConfig()` called the random-password generator twice — server and client held different passwords | Single `spawnPassword` per launcher lifecycle |
| Stale orphaned `opencode serve` on a configured port caused endless 401s | Launcher trusted an already-occupied fixed port | `requirePortFree` fast-failure with a named cause + regression test (`OpencodeServerLauncherPortTest`) |

The deployment also **damaged the Eclipse install itself** (an interrupted
first start after cache clearing left no `bundles.info`; the subsequent p2
rewrite stripped 13 exploded-directory bundles including the product bundle).
Recovery procedure and deploy-script hardening are documented in
`eclipse-deploy-recovery.md`; the install now runs on hand-maintained
`bundles.info` lines that `deploy-dev.ps1` refreshes on every deploy.

**Live evidence after the fixes:** workbench up with an explicit `-data`
workspace, all opencode views created, spawned server connected with
authenticated event streams (zero 401s after the final spawn), and a real
chat round-trip through the Chat view answered by `glm-5.3`.

## Next live acceptance pass

1. Deploy the verified jars with `eclipse/deploy-dev.ps1`, then launch Eclipse
   (see `eclipse-deploy-recovery.md` for the launch rules).
2. Open Board, Fleet, Server, Repo, Chat, Providers and Session Details; capture each
   view and exercise its context menu with both a valid selection and empty selection.
3. Create a disposable primary session. Abort an active reply, then delete the
   session; confirm the row disappears and an HTTP failure is shown rather than hidden.
4. Choose **New session with this agent** on a primary agent. Wait for selectors to
   finish loading and confirm the requested agent is still selected. Repeat a
   model-specific new chat from Providers.
5. With a remote connection selected, verify delete/abort target that connection
   and primary-only chat/transcript actions are disabled.
6. Start automatic dispatch on a disposable sprint; check start/status/stop, blocked
   failure handling, peer concurrency accounting, and one completed artifact.
7. Complete first-launch and CDT Problems-marker UI checks. Record observations and
   screenshots before closing Milestone U; the headless marker tests alone do not close it.
