# Fleet observability - reusing Eclipse features and views - RETIRED 2026-11-14

## About this document

- **RETIRED (2026-11-14):** the fleet this record documents - `FleetView`,
  `FleetTree`, `FleetJob*` - was removed in the U-110 arc (evidence: ticket
  U-111 AE-6), so every row naming those symbols (the fleet tree, the tree
  model, job diffs, live activity text) was history, not a current surface.
- **Moved content (2026-11-26, T-233):** the still-current part T-215's
  marker kept live - `SessionDetailsView` and its Session Details sections,
  the read-only `MessageConsole` output tails, and the Eclipse construct
  analysis (prime rule + platform verdicts) - now lives in
  `eclipse/ARCHITECTURE.md`, section "Session Details rendering - Eclipse
  constructs reused (moved content)". Nothing current was dropped: the
  transcript/console rows, the prime rule and all four platform verdicts
  moved there; the fleet-specific rows and the "genuinely custom" fleet
  section were deleted here as dead as-built history (git keeps them:
  `git log -- docs/fleet-observability-eclipse-reuse.md`).
- **Kind:** `doc` / as-built record of the fleet observability layer: which
  Eclipse constructs the shipped UI reuses, which platform options were
  rejected, and what was genuinely custom.
- **Read by:** anyone looking for the record's history; for the still-current
  Session Details rendering facts read `eclipse/ARCHITECTURE.md` (above).
- **Related:** `eclipse/ARCHITECTURE.md` (the moved content's home),
  `docs/fleet-job-activity.md` (the retired data source),
  `opencode-v2-adoption.md` (the reuse policy).
- **Original record:** in-repo since 2026-10-31 (earliest reachable commit);
  RETIRED marker added 2026-11-23 (T-215); current content moved out and
  body reduced 2026-11-26 (T-233, docs consolidation lane T-d).
