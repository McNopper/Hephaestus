# process-retro-2026-09-23.md - why the flake ate eight build cycles

## About this document

- **Kind:** `doc` / process retrospective (failure-of-process review, written
  during the 2026-09-23 close-out at user direction: "review the process and
  failure of the process").
- **Read by:** maintainers and agents that debug against this harness;
  referenced by `AGENTS.md`'s debugging discipline.
- **Related:** `AGENTS.md`, `eclipse/QUALITY.md` (findings policy),
  `G-004` (knob tables), `T-006` (Defender exclusions).

## The failure under test

`FleetControlTest.dispatchAutoSyncsTheStoreAfterTheLaunchSettles` /
`dispatchSyncsAndNeverPropagatesWhenTheLaunchThrows` failed intermittently
over ~8 gate runs (each 4-7 minutes). The stack of WRONG theories, in order:

1. "a leaked store transaction" - disproven by the dump (`.lock` is
   persistent by design).
2. "Defender/git latency" - plausible (git steps ran ~90s apart) but only a
   contributing factor.
3. "60s per-command timeouts kill git mid-sequence" - real bug (fixed:
   GitTuning 3/5 min) but not the only cause.
4. "the common-pool drain loses command output" - real bug (fixed: private
   git-drain pool) but not the only cause.
5. THE ACTUAL LAST CAUSE: the test pinned the sync commit as the NEWEST
   commit (`log -1`), while the engine's claim/merge `commitAll` may
   legitimately follow or coalesce it. The contract is "the bookkeeping lands
   IN A COMMIT" - the assertion over-pinned ordering.

Every layer 1-4 was a REAL defect worth fixing - but none was THE defect, and
each fix cycle cost a full gate run.

**Addendum (same day, 16:00):** "THE ACTUAL LAST CAUSE" aged badly - there was
a SIXTH layer. Under machine load the 5&nbsp;s post-exit output-drain window
blew, a successful git command read as "output drain lost", and the sync
aborted MID-SEQUENCE (index stranded staged-but-uncommitted). The lesson is
the point of this doc: there is no "last cause" until the gate is green twice
under DIFFERENT conditions (quiet machine AND loaded machine). The loud
logging earned its keep - the failure was named in one grep instead of eight
build cycles.

## What the process got wrong

1. **Theory before evidence.** The failing run's output ALREADY contained the
   decisive facts (`[storeClean] status/log`, later `store auto-sync
   outcome: ...`); several cycles were spent on theories the logs would have
   ruled out in one grep.
2. **One fix per build.** Builds were kicked per hypothesis instead of after
   a full evidence pass. Rule: extract ALL available evidence (logs, dumps,
   the caller/callee code) BEFORE editing; then fix the whole failure class.
3. **Silent observability.** The sync outcome was a dropped return value; the
   first real diagnosis came only AFTER adding outcome logging. Rule: an
   engine must log every non-success outcome at the moment of failure -
   debugging by speculation is a symptom of missing telemetry.
4. **Edits racing the build.** Edits landed mid-build, producing stale/failing
   runs and one broken script (`deploy-dev.ps1` parse break). Rule: no source
   edits while a gate runs; one build at a time; scripts are validated by a
   real run (or a parser) after editing.
5. **Overconfident status.** One status claimed "the fix is complete" while a
   declaration edit had FAILED - the follow-up run caught it. Rule: a status
   claim about code is made from tool RESULTS, never from intent.
6. **Assertion drift in tests.** The test encoded an implementation detail
   (commit recency) instead of the contract (commit existence). Rule: tests
   assert the documented contract; ordering/lastness is an implementation
   detail unless the contract says otherwise.

## What the process got right (keep)

- The store as the coordination blackboard: evidence, findings and status
  flips were recorded on tickets as they happened.
- Cross-vendor review before tagging (the K3 review found 2 blockers the
  main agent had missed: the review-path worker leak and the un-wired
  sanitizer option).
- Diagnostics that fail loudly (full thread dumps, outcome logging) turned
  an undiagnosable flake into a one-grep diagnosis.

## Adopted rules (short form)

1. Evidence before theory: one decisive probe (grep the logs/dump) before
   the first hypothesis.
2. Fix the failure CLASS, not the instance.
3. No source edits while a gate runs; validate edited scripts by running
   them.
4. Every engine outcome that is not success is logged where it happens.
5. Tests assert contracts, not implementation details.
6. Status claims cite tool output.
