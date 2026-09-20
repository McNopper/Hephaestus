# Fleet observability — reusing Eclipse features and views

## About this document
- **Kind:** `doc` / design input (cross-check) for the observability tickets.
- **Read by:** whoever implements U-015, U-024, the fleet tree view and the
  v2 subagents/console-tasks parity tickets; **written by:** chat-session,
  user direction 2026-09-20 ("the fleet should be a tree view; show subagents
  and console tasks for chat AND fleet; cross-check Eclipse features and
  views to be reused").
- **Related:** `eclipse/ARCHITECTURE.md`, tickets U-009 (Agent Tools
  console), U-015 (watch live), U-024 (daemon rows), U-026 (V-flow visibility).

**Prime rule applied:** never build in the plugin what the platform already
provides. This note maps each observability need to the Eclipse construct
that already solves it, and flags where custom code is genuinely required.

## What the codebase already uses (build on these)

| Construct | Where | Reuse for |
|---|---|---|
| `org.eclipse.ui.console` — `MessageConsole` + `ConsoleManager` | `AgentToolsConsole` (ui bundle, U-009) | **Console-task output**: one `MessageConsole` per shell/PTY task, registered with the `ConsoleManager` — users get the standard Console view (pin, scroll-lock, clear, search) for free |
| JFace `TableViewer` + styled label providers | `FleetView` (board bundle) | Swap to `TreeViewer` — same viewer family, same label-provider pattern; the tree content provider composes wave → job → session → subagents → console tasks |
| `SessionDetailsView` (`allowMultiple`, Auto Refresh) | ui bundle | The transcript drill-down for any session node (chat or fleet) — already the U-015 target |
| Compare editor for diffs | U-019 | Keep: job diffs open in the platform compare editor, not custom dialogs |
| Busy/activity icons + peer-write refresh | U-007, B-002 (Board/Fleet rows) | Same decoration mechanism on tree nodes |

## Platform features evaluated

| Need | Eclipse feature | Verdict |
|---|---|---|
| Hierarchy of jobs/processes with consoles | **Debug platform** (`ILaunch`, Debug view) | **Rejected for now**: drags in launch configs, debug-model semantics and new deps for zero user gain over a plain `TreeViewer` + `IConsole`. Revisit only if users ask for launch-style run/debug control of fleet jobs |
| Console-task output | `org.eclipse.ui.console` | **Adopt** (already on the classpath; the `AgentToolsConsole` pattern generalizes to per-task consoles) |
| Interactive PTY (input to a session's shell) | TM Terminal | **Deferred**: not in the target platform today. First slice is read-only output tails in a `MessageConsole`; interactive PTY is a follow-up evaluating `org.eclipse.tm.terminal.*` |
| Progress/busy surfacing | Jobs API + Progress view | **Adopt where missing**: long view operations (transcript loads, diff computations) as `Job`s with user visibility |
| NEEDS-HUMAN attention | View title/description decorations (existing badge) | **Keep**; no Mylyn/notification dependency |
| Tree of sessions with subagent nesting | CNF (Common Navigator) | **Rejected**: CNF serves resource navigators; a session/job tree is a model tree, not a resource tree — plain `TreeViewer` is simpler |
| Transcript reading | Text editor (read-only, `IStorageEditorInput`) | **Optional later**: Session Details already renders transcripts; an editor variant buys search/word-wrap for huge sessions |

## Genuinely custom (no platform equivalent)

- The **tree model** itself: composing daemon jobs + local jobs + sessions +
  subagents (v2 `parentID`) + shell tasks (v2 `shell.*` events) into one live
  tree with tokens/cost rollups per subtree.
- **Live activity text per node** (last assistant snippet / current tool),
  fed by the existing runner `Activity` probe + the event aggregator.
- Engine-addressing (daemon vs local) made explicit in the UI (U-038 lesson).
