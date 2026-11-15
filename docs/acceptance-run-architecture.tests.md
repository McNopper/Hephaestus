# Architecture tests: the amended acceptance-run architecture doc (U-111) — verifies T-098

- **About this document:** the library-level (test-architecture) verification
  record for the T-097 coherence child **T-098** — criteria derived 1:1 from
  T-098's acceptance criteria, each with an observable oracle (grep hits, git
  diff, encoding/alignment checks), and the recorded verdict. This record is
  amendment-scoped: it verifies the 2026-11-15 (T-097) attribution amendment
  of the architecture doc and complements, without replacing, the original
  A-004 verification of the same document. **Read by:** the engine review
  pass (weighs the verdict), the ticker-v stage ticker, the maintainer.
  **Related:** `docs/acceptance-run-architecture.md` (under verification, as
  amended by T-098), `docs/acceptance-run-system.md` (governing basis, as
  amended by T-097 — cited as [SYS]), `docs/acceptance-run-system.tests.md`
  (IT-A-102 basis context, read-only), tickets T-097 (basis), T-098 (under
  verification), T-099 (this record's fulfillment ticket).
  **Provenance note:** the tasks MCP namespace dropped before T-099's
  completion comment could be written; this file is the durable record of the
  criteria and verdict, to be linked onto ticket T-099 by the reviewer/ticker.

## How these tests run

Static documentation-level verification: every criterion is decided by an
observable oracle on the file itself or its git diff — ripgrep over
`docs/acceptance-run-architecture.md`, `git diff` against HEAD
(index 6f7e7b3 → working tree), and byte-level encoding checks. No store
writes are performed or needed for the decisions below. Verdict semantics:
PASS/FAIL per criterion; the overall verdict (APPROVE / REJECT /
APPROVE-WITH-NOTES) is advisory to the engine review pass, which alone sets
`done` (D-3).

## Criteria and results

### AT-A-901 · Zero contradictions with the amended basis (T-098 AC1, part 1) — PASS

- Oracle: ripgrep over the amended architecture doc for the superseded
  wording patterns `with its actor`, `engine actor`, `History-with-actor`,
  `history (with actors)`, `stamp history with actors`, `transition by
  worker`, `append-only history with actors`, `done (engine`.
- Evidence: **0 matches.**
- Residual-`actor` audit: 24 substring matches, classified — 1 substring
  false positive (`re`**`actor`**``, M4 row's `eclipse/QUALITY.md` reactor
  reference); run-participant sense (lines 49, 56, 57, 70, 92, 97, 146, 160,
  202, 207, 275, 289 — including the verbatim [SYS §Overview] quote "task
  store plus four actors around it"); true claim/artifact stamping, which
  [SYS §Interfaces 1] confirms (lines 100, 174, 220, 226, 228, 256); explicit
  negation or amendment context (lines 25, 27, 104, 270 — "no stamped
  actor", "not on stamped transition actors", "attribution is partial");
  amended diagram box (line 81, `history (actors partial)`).

### AT-A-902 · Coherence with the amended system definition (T-098 AC1, part 2) — PASS

- Oracle: sentence-level comparison of the amended D-1/D-3 flows and the
  D-point evidence table against [SYS §Interfaces 1 / §data flows].
- Evidence: the cross-cutting bullet (`History-with-attribution`) and the M1
  invariant mirror the settled rule — partial attribution, claim/artifact
  writes actor-stamped, status transitions bare ("record only the change"),
  identities caller-asserted, engine attribution via the review pass's
  observable marks. D-1 mirrors [SYS §data flows D-1] sentence-for-sentence
  (bare `in-review` flip, worker identity in the adjacent fulfillment
  comment). D-3 mirrors [SYS §data flows D-3]: `done` with the engine's mark
  — annotated done event OR engine-verdict comment within the review batch,
  pre-2026-11-15 dones grandfathered. The D-1 evidence-table row is
  character-identical to the system doc's D-1 row; the D-3 row mirrors its
  counterpart and, correctly for pass-through, omits only the observed
  identity names (`review` / `ticker-v:review`) that live in the source of
  truth.
- Pass-through discipline: the full mark rule is NOT restated — no observed
  spans (51 ms / ~17 min), no identity enumeration, no re-specification; the
  amendment note (header) cites `[SYS §Interfaces 1]` as amended by T-097 as
  source of truth and states the doc "re-points its wording only".
- Amendment note: present, cites T-097. ✓

### AT-A-903 · Minimal diff, lane-clean (T-098 AC2) — PASS

- Oracle: `git diff` on `docs/acceptance-run-architecture.md`.
- Evidence: exactly one file, **37 insertions(+), 19 deletions(-)** —
  byte-identical to T-098's completion report. Git groups the edits as 7
  hunks (adjacent edits merge); the report's "9 hunks" enumerates the same 9
  logical changes: header amendment note, cross-cutting bullet rename,
  diagram box, M1 invariant, D-1 flow, D-3 flow, D-1 evidence row, D-3
  evidence row, QA-1 — every one attribution-related, zero unrelated content
  in the diff.
- UTF-8 clean: no BOM (first bytes `23 20 41` = `# A`), zero mojibake markers
  (Ã / â€ / ï¿½ / U+FFFD), uniform LF endings (CRLF=0, lone-LF=359; git's
  LF→CRLF warning is repo-wide autocrlf behavior, pre-existing).
- ASCII diagram alignment intact: the amended box line
  `│ claims · history (actors partial) │` measures 69 characters, identical
  to its three sibling box lines (69/69/69, bottom border 49); interior
  padding compensated (4 spaces → 1), and the right-edge context
  `│      └────────────┘` is byte-identical in the diff's −/+ lines.
- Lane: `docs/acceptance-run-system.tests.md` untouched (clean in git
  status), as T-098 required. The also-modified `docs/acceptance-run-system.md`
  is T-097's own uncommitted amendment (same wave), not T-098's write.

### AT-A-904 · E-table matches the amended interface lists (no drift) — PASS

- Oracle: edge rows E1/E2/E3 vs [SYS §Interfaces 1] verb lists.
- Evidence: E1 (M2→M1) `task_readiness`, `task_plan_sprint`, `task_claim`,
  division/link creation, `task_close_sprint` — exact match (its
  `task_set_blocked` escalation is additionally cited from [DSG §4],
  basis-cited, no contradiction); E2 (M3→M1) `task_get`,
  `task_add_artifact`, `task_update` (→ `in-review`), `task_add_comment`,
  `task_set_blocked` — exact match; E3 (M4→M1) `task_get` /
  `task_traceability` reads + the `done` transition — exact match. No E-row
  appears in any diff hunk.

## Observed, non-blocking (for the reviewer)

- T-098 AC3 (artifact before `in-review`) holds on the ticket record:
  artifact 09:24:47.355Z → status flip 09:25:06.917Z.
- `docs/acceptance-run-system.tests.md` still carries pre-amendment wording
  (history "with actors", transition actors in IT-A-101/102). This drift is
  real but explicitly out of T-098's and T-099's scope ("do not touch the
  tests doc"; IT-A-102's basis stays A-002's re-verification lineage) — owned
  by that doc's own flow, not by this pair.

## Verdict

**APPROVE-WITH-NOTES** — all four criteria PASS with observable evidence;
the notes above are observations for the engine review pass, none blocking.
This verdict decides T-098's done/not-done in the reviewer's hands; T-099
sets no status.

Evidence snapshot taken 2026-11-15 against working tree @ HEAD 6f7e7b3.
