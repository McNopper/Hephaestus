# Fleet observability — reusing Eclipse features and views

## About this document

- **Kind:** `doc` / as-built record of the fleet observability layer: which
  Eclipse constructs the shipped UI reuses, which platform options were
  rejected, and what is genuinely custom.
- **Read by:** maintainers of `FleetView` / `SessionDetailsView` and anyone
  extending fleet observability.
- **Related:** `eclipse/ARCHITECTURE.md`, `fleet-job-activity.md` (the data
  source), `opencode-v2-adoption.md` (the reuse policy).

**Prime rule applied:** never build in the plugin what the platform already
provides. Every observability need below maps to an Eclipse construct that
already solves it, or is called out as genuinely custom.

## What shipped (as-built)

| Need | Eclipse construct reused | Where it lives |
|---|---|---|
| The fleet tree | JFace `TreeViewer` (Node + "Activity · tokens · cost" columns) | `FleetView` (FleetView.java:140/286/293-298, board bundle) |
| Tree model (wave → job → session → subagents → shell tasks) | plain viewer model, no navigator framework | `FleetTree`, with an own-engine root and a peer-engine root (FleetTree.java:82-96), fed by `SessionObservation` (FleetView.java:980-1004) |
| Transcript drill-down for any session node | `SessionDetailsView` (`allowMultiple`, Auto Refresh) | serves chat sessions AND fleet worker sessions — both are ordinary sessions of their server (SessionDetailsView.java:127-134) |
| Transcript viewing (one message or the whole transcript) | the workbench's default text editor over a `FileStoreEditorInput` (EFS file store; the modern workbench removed `IStorageEditorInput`) | read-only snapshot: edits change only the delete-on-exit temp copy, never the session; no live follow (SessionDetailsView.java:117-125, `SessionTranscript`/`SessionTranscriptFiles`) |
| Shell-task output | a real Eclipse console per task (`org.eclipse.ui.console`) | `ShellTasksConsole` from Session Details (SessionDetailsView.java:127-134), generalizing the `AgentToolsConsole` pattern (U-009) |
| Subagent nesting | `parentID`-driven tree sections above the transcript | Session Details live sections (U-041) + the Fleet tree |
| Job diffs | the platform compare editor | job rows open compare, not custom dialogs (U-019) |
| Progress/busy | Jobs API + the Progress view | long view operations (transcript loads, diff computations) run as visible `Job`s |
| NEEDS-HUMAN attention | view title/description decorations | the existing badge; no Mylyn/notification dependency |

## Platform decisions (verdicts as built)

| Option | Verdict |
|---|---|
| **Debug platform** (`ILaunch`, Debug view) for the job hierarchy | **Rejected**: drags in launch configs, debug-model semantics and new dependencies for zero user gain over a plain `TreeViewer` + console. Revisit only if users ask for launch-style run/debug control of fleet jobs |
| CNF (Common Navigator) for the session tree | **Rejected**: CNF serves resource navigators; a session/job tree is a model tree — plain `TreeViewer` is simpler |
| `org.eclipse.ui.console` for task output | **Adopted** (see the table above) |
| TM Terminal for interactive PTY input | **Parked**: not in the 2026-06 target platform; read-only output tails ship in consoles/`MessageConsole`, the interactive host is the remaining "Adopt next" item in `opencode-v2-adoption.md` |

## Genuinely custom (no platform equivalent)

- The **tree model** itself (`FleetTree`): composing this engine's jobs and
  reconstructed peer-engine jobs into one live tree of sessions, subagents
  (v2 `parentID`) and shell tasks (v2 `shell.*`), with tokens/cost rollups
  per subtree.
- **Live activity text per node** (last assistant snippet / current tool):
  built by the runner's `Activity` probe and the event aggregator, delivered
  as `SessionObservation` — the same data the `fleet_job_activity` MCP tool
  serves (see `fleet-job-activity.md`).
- **Own/peer engine addressing** made explicit in the UI: two labeled roots
  (`ownEngineLabel` / `peerEngineLabel`) instead of one fused list, so it is
  always visible which engine a job belongs to.
