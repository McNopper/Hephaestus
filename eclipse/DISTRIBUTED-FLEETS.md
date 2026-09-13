# Distributed fleets — one board, many machines

## About this document
- **Kind:** doc / design note for the distributed-fleet operating mode.
- **Read by:** anyone planning to run Hephaestus with several opencode servers on different machines against one task store; **written by:** maintainers.
- **Related:** `ROADMAP.md` (the confirmed design principle), `eclipse/README.md` (bundle map), `eclipse/ARCHITECTURE.md` (single-machine architecture). Records the sharding-vs-single-repo decision and the sync discipline the tooling implements.

## The model

The task store (`.opencode/tasks/`, one Markdown file per ticket) is **plain
git-versioned text** — that is the entire distribution mechanism. Two layouts are
supported: the store as a **subtree of the host repo** (the Hephaestus default — all
store git discipline is path-scoped to `.opencode/tasks` and can never sweep the
host repo's own WIP into a store commit) or a **dedicated store repo** whose root
*is* the store. N machines run one Eclipse + opencode server each; they share the
board by sharing the store's git history. The store is the **only** shared state:

- each machine serves its own `eclipse-build` MCP endpoint and runs its own
  worktree sessions against its local clone of the *code* repo;
- tickets are claimed atomically **within one machine's store view** — across
  machines, claim semantics are **git discipline**: *pull → claim → push*;
- a push conflict means someone else claimed first: re-pull, re-claim.

## What the tooling gives you

| Piece | Where | Behavior |
|---|---|---|
| Store status in the Board header | Board view | `store main · ahead 2 · 3 changed` (from `StoreGitStatus.load`, one `git status -b --porcelain`) — refreshes with the board |
| *Sync store* action | Board view toolbar | commit local ticket changes (**scoped to `.opencode/tasks`**) → `pull --rebase` → `push` (`StoreSync.sync`); a pull conflict never auto-resolves — it offers `recover()` (abort the rebase, local commits intact) or manual git |
| `StoreGitStatus` / `StoreSync` | `com.opencode.ide.git` (Eclipse-free) | The primitives, unit-tested against real git in temp repos |

## Sharding vs single repo — the decision

**Single shared store repo (chosen).** All projects, all machines, one repository.

Why: the store's value is *the* global queue — cross-project dispatch
(`AutoDispatch`), cost rollups (`CostOverview`), traceability audits all assume
one linear history; a single repo makes "what's runnable right now" one
`git pull` away, keeps claim conflicts detectable by git itself, and needs zero
extra infrastructure (no hosting beyond one bare repo, no sync service).

The costs, accepted at hobby scale: one machine's noisy store commits
(invalidations, actuals) interleave with everyone's (pull often, commit small);
a wedged clone blocks only that machine; store size grows monotonically (it's
text — fine for years).

**When to shard instead:** if machine count grows past "fits in one standup" or
projects want independent write policies, split *per project* (the store is
already subdirectory-scoped: `.opencode/tasks/<project>/`) — each project dir
becomes its own repo, the Board's project selector picks the repo, and nothing
else changes. Shard by **project, never by V-stage**: stages of one epic must
share a queue or the dataflow pipeline (H6) stalls on cross-repo staleness it
cannot see.

## Operating checklist

1. One bare repo (any git host or a shared drive) holding `.opencode/tasks/`.
2. Each machine: clone it, point the Board's *Store* input (or the tasks-root
   preference) at the clone, configure its own `remote.origin` push access.
3. Rhythm: **pull → claim → push** — press *Sync store* before claiming
   (dispatch actions claim), sync again after a sprint-planning or
   bulk-edit change; pull conflicts are rebase conflicts — abort, pull fresh,
   redo the claim.
4. Never edit tickets on two machines simultaneously and expect git to merge
   meaningfully — the store is line-oriented Markdown; simultaneous edits to
   *different* tickets merge cleanly, edits to *one* ticket are last-writer
   wins after rebase.
