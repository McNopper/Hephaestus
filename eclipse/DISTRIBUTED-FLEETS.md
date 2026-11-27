# Distributed fleets - one board, many machines

## About this document

- **PARKED - done-and-dormant (mode parked 2026-11-14 with the U-110
  de-fleet; procedure body retired 2026-11-26, T-233):** what it was - the
  design note for the distributed-fleet operating mode: several opencode
  servers on different machines sharing ONE task store through its git
  history, each machine serving its own `eclipse-build` endpoint and its own
  worktree sessions against a local clone of the code repo. Built and
  recorded, not actively used.
- **Park record:** `../ROADMAP.md`, section "Parked / non-goals" - "distributed
  fleets are done-and-dormant" (the mention was dropped by the 2026-11-14
  ROADMAP rewrite and restored 2026-11-26 by T-233 so this pointer lands on
  a section that actually names the mode). Only the owner re-opens it.
- **Kind:** doc / design note for the distributed-fleet operating mode.
- **Read by:** anyone planning to run Hephaestus with several opencode
  servers on different machines against one task store; **written by:**
  maintainers.
- **Related:** `../ROADMAP.md` ("Parked / non-goals": distributed fleets are
  done-and-dormant), `README.md` (bundle map), `ARCHITECTURE.md`
  (single-machine architecture).
- **Original record:** in-repo since 2026-10-31 (earliest reachable commit;
  the repository's history was single-commit squashed that day); the
  procedure body was removed 2026-11-26 (T-233, docs consolidation lane T-d)
  and survives in git history (`git log -- eclipse/DISTRIBUTED-FLEETS.md`).

## What moved where (successors)

- **The mechanism still lives in the library:** the store is plain
  git-versioned text and the sync discipline is implemented and unit-tested -
  see `ARCHITECTURE.md` (the `git` bundle row: `StoreGitStatus`/`StoreSync`,
  path-scoped commit → pull-rebase → push with recover; the Board row's
  *Sync store* action) and `README.md` (the Board and git bundle rows).
- **The multi-machine operating procedure** (bare repo, per-machine clones,
  the *pull → claim → push* rhythm, the simultaneous-edit guidance): parked
  with the mode - no live successor by design; recover it from git history
  when the mode is re-opened.

## The decision it recorded (kept)

**Single shared store repo (chosen):** all projects, all machines, one
repository. The store's value is *the* global queue - readiness gating
(`task_readiness`) and cross-machine traceability assume one linear history;
one linear history makes "what's runnable right now" one `git pull` away,
keeps claim conflicts detectable by git itself, and needs no infrastructure
beyond one bare repo. Accepted costs at hobby scale: interleaved store
commits, a wedged clone blocking only that machine, monotonically growing
store size (text - fine for years).

**When to shard instead:** machine count past "fits in one standup", or
projects wanting independent write policies - split **per project, never by
V-stage**: stages of one requirement chain must share a queue or the
dataflow pipeline (H6) stalls on cross-repo staleness it cannot see.
