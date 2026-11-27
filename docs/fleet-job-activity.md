# Fleet job activity - RETIRED 2026-11-14

## About this document

- **What it was:** RETIRED - the tool reference for the
  removed FleetRunner job-activity probe (`fleet_fleet_job_activity`) - one
  point-in-time `SessionObservation` of a dispatched worker (current
  activity, tools used, shell commands, subagents, cost/tokens), written for
  the fleet era.
- **Retired because:** the fleet was removed in the U-110 arc (design
  section 9, evidence in ticket U-111 AE-6), so the tool and its consumers
  (the Eclipse Fleet tree, the FleetView nodes built from the same
  `SessionObservation` data) are gone. The dead body was deleted 2026-11-26
  (T-233, docs consolidation lane T-d) and survives only in git history
  (`git log -- docs/fleet-job-activity.md`).
- **The replacement:** readiness + store history is the observable state
  (task_readiness, ticket history events); experimental.job.list (W13)
  is the upstream-native option if session-job probing is ever needed again.
- **Original record:** in-repo since 2026-10-31 (earliest reachable commit —
  the repository's history was single-commit squashed that day).

See also: `docs/fleet-observability-eclipse-reuse.md` (the retired Eclipse UI
that consumed this data; its still-current part now lives in
`eclipse/ARCHITECTURE.md`), and `AGENTS.md` (chat-first control plane).
