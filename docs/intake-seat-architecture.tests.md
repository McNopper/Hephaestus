# Product-owner seat — library tests (T-106)

## About this document

- **Kind:** `doc` / test-architecture-stage artifact of ticket T-106 (epic
  U-112, basis T-083, stage `test-architecture`): the paired verification of
  the architecture child T-105 — library criteria proving the architecture
  artifact's structure is complete, consistent and derivable 1:1 from the
  governing system definition, with every code claim checked read-only at
  HEAD.
- **Read by:** the reviewer and the engine's done/not-done decision for
  T-105; the design stage (its basis is validated here before it builds on
  it); the coherence pass (one recorded anchor staleness).
- **Related:** `docs/intake-seat-architecture.md` (T-105 — the subject under
  verification, commit `471ac08`); `docs/intake-seat-system.md` (T-083 — the
  governing system definition, source of truth for the 1:1 checks);
  `docs/design-stage-pipeline.md` (the machine's design — the no-invented-
  parallel-structure reference); the cited sources
  (`.opencode/agent/product-owner.md`, `OpenChatHandler.java`, `ChatView.java`,
  `SelectorGuard.java`, `ServerView.java`, `SessionViewIds.java`,
  `HttpOpencodeClient.java`, `opencode.json`); the store history of T-105.

## Library under test

- **Boundary:** the architecture artifact as a *document* contract — its
  component structure (C-1..C-6), dependency rules (DR-1..DR-7) and
  traceability matrix (§10) against the system doc it derives from.
- **Contract:** every integration element of `docs/intake-seat-system.md`
  traces through a component to real evidence; nothing is invented parallel
  to the machine's design; the recorded open question and defect disposition
  are faithful.
- **Architecture reference:** T-105 §1–§3 (structure), §10 (traceability),
  §8–§9 (open question + defect disposition), §11 (AC mapping).

## Derivation

The criteria below derive 1:1 from T-105's three acceptance criteria
(store, ticket T-105):

- **T-105 AC-1** → LT-001..LT-004 (artifact exists with About header;
  components complete; components derivable 1:1; dependency rules explicit
  and derivable).
- **T-105 AC-2** → LT-005..LT-009, LT-011 (matrix rows resolve; code anchors
  observed; Q-1 OPEN; defect disposition verified; no invented parallel
  structure; machine-keeping anchors observed).
- **T-105 AC-3** → LT-010 (artifact recorded before in-review; done never
  self-assessed).

## Test cases

| ID | Criterion (T-105 AC) | Oracle (source of truth) | Method | Expected |
|---|---|---|---|---|
| LT-001 | AC-1: the artifact exists as a `docs/` document with the repo About header | the file itself; git | read + `git show` | present, sole-file commit |
| LT-002 | AC-1: component structure complete — C-1..C-6 each with reality anchor, responsibility, depends-on | architecture doc §2 | read | 6 fully-specified rows |
| LT-003 | AC-1: components derivable 1:1 from the system doc's fixed parts; any refinement recorded with rationale | system doc §1 (four parts, three interfaces, diagram) + §2 (machine-keeping) vs architecture §2 | side-by-side derivation check | 1:1 map, refinement justified |
| LT-004 | AC-1: dependency rules explicit — DR-1..DR-7 each trace to a system-doc invariant or decision | system doc §1 invariants 1–6, §3 (D-004), §4 (candidate lists) vs architecture §3 | trace check | every DR sourced |
| LT-005 | AC-2: every §10 matrix row's system element resolves to a real section of the system doc | system doc §1–§5 | per-row section lookup | 16/16 rows resolve |
| LT-006 | AC-2: code-cited anchors observed read-only at HEAD | the cited sources (frontmatter, chat bundle, core bundle) | grep/read, instances recorded | all cited anchors present |
| LT-007 | AC-2: Q-1 stays OPEN — nothing resolved, decision request stands | architecture §8 vs system doc §5; git history | read + `git log` | OPEN, history matches |
| LT-008 | AC-2: store-path defect disposition recorded; ground truth verified | architecture §9; system doc §4 at HEAD; commit `97ea96e` | read + `git log` | disposition true at HEAD |
| LT-009 | AC-2: no invented parallel structure vs `docs/design-stage-pipeline.md` | design doc §1/§4/§5/§8/§9 vs architecture §1/§2 (C-4)/DR-2/DR-7 | quote-and-consistency check | no new machine construct |
| LT-010 | AC-3: artifact recorded on T-105 before the in-review move; done never self-assessed | T-105 store history | `task_get` | artifact event precedes status; done by engine |
| LT-011 | AC-2: C-6 machine-keeping anchors resolve to reality | client code + tests + `opencode.json`; AGENTS.md; `BoardView.java` + `docs/eclipse-gui-review-2026-11-15.md` | grep/read | anchors present (one recorded deviation) |

## Observed evidence

### LT-001 — artifact exists with About header — **PASS**

- `docs/intake-seat-architecture.md` exists (291 lines). Commit `471ac08`
  ("T-105: intake/product-owner seat architecture …") carries it as the
  **sole file**, 291 insertions, 0 deletions — one new file, matching the
  declared lane (T-105 §12).
- The About header is present (lines 3–19): Kind / Read by / Related, naming
  the basis artifact, the machine design doc, the seat definition and the
  chat bundle — the docs/ convention T-105 AC-1 requires.

### LT-002 — component structure complete — **PASS**

Architecture §2 defines exactly six components, each row carrying all three
required fields: C-1 seat definition, C-2 dispatch surfaces, C-3 task store,
C-4 stage-pipeline machine, C-5 evidence plane, C-6 machine-keeping surfaces
— reality anchor + responsibility + `Depends on` in every row. The
dependency column is consistent with §3: C-1→C-3 only (DR-1), C-2→C-1 by
name (DR-3), C-4→C-3 only, C-5 read-only over C-3 + git (DR-4), C-6 via host
endpoints. No orphan component, no unspecified edge.

### LT-003 — components derivable 1:1 from the system doc — **PASS**

Side-by-side derivation (system doc → architecture §2):

| System doc fixed part | Architecture component | Derivation |
|---|---|---|
| §1 "The seat" (loop, tool denials) | C-1 | 1:1 — loop steps quoted verbatim (classify → distill → link → register+cite → verify); the two autonomy dials and NEEDS-HUMAN honesty are AGENTS.md-sourced additions of the same loop |
| §1 "Dispatch surfaces" + §2 D-1/D-2/D-3 | C-2 | 1:1 — the three routes carried through with their anchors |
| §1 "The machine" — the store | C-3 | **the one refinement**: split out of "the machine"; recorded with rationale in the §2 preamble ("every dependency rule below (DR-1, DR-4) targets the store's interface… a naming move for the rules' sake, not new structure"). The machine's own design names the store as a separate core part (design doc: the D-005 minimal core — *store + engine verdict + spawn cap + Eclipse host*), so the split is consistent with the machine design, not parallel to it |
| §1 "The machine" — tickers, workers, verdicts | C-4 | 1:1 — same substrate as the design doc's tickers/workers/review pass |
| §1 "The evidence plane" | C-5 | 1:1 — the same two consumers named (seat verify step, spot-check reviewer) |
| §1 diagram "machine-keeping surfaces" + §2 machine-keeping dispatch | C-6 | 1:1 — the non-shell routes carried through |

Four parts map 1:1; the fifth component (C-6) is derivable from the system
view's own diagram edge plus §2; the store split is the single recorded
refinement, with rationale. **Derivable 1:1 holds.**

### LT-004 — dependency rules explicit and sourced — **PASS**

All seven DRs trace to the system doc (or its governing decisions):

| Rule | Source in the system doc / governing docs |
|---|---|
| DR-1 (store interface only) | §1 system view — seat → task store → machine; never receives work from it |
| DR-2 (no machine → seat edge) | §1 invariant 4, carried near-verbatim (Auto ▶ launches sprint tickets, worker roles only; only inbound edges owner-initiated) |
| DR-3 (dispatch by name) | §2 D-1/D-2/D-3 + §5 blast radius ("the name is the dispatch key everywhere") |
| DR-4 (evidence plane read-only) | §1 evidence plane ("neither writes it by hand") + §4 ("bans hand *writes*, not reads") |
| DR-5 (no shell / steward) | §1 invariant 1 + §2 machine-keeping dispatch (steward session, duty-not-tool) |
| DR-6 (no committed model ids) | §3 (D-004 — no `model:` frontmatter, local override only) |
| DR-7 (engine-owned verdicts; candidate lists only) | §1 invariants 2–3 + §4 ("the verdict is the reviewer's, never automated") |

Nothing in §1–§5 of the system doc is left without a rule; no DR exists
without a source.

### LT-005 — §10 matrix rows resolve to real system-doc sections — **PASS**

All 16 rows checked against the system doc at HEAD:

§1 view + invariants 1–6 → §1 ✓ · D-1 → §2 table ✓ · D-2 → §2 table ✓ ·
D-3 → §2 table ✓ · guarded switching semantics → §2 D-2 row ✓ ·
default-agent miss → §2 paragraph ("the common miss") ✓ · machine-keeping
dispatch → §2 paragraph ✓ · no committed model → §3 ✓ · tier position → §3 ✓
· ticket-level lever → §3 ✓ · identity whitelist → §4 ✓ · S-1 → §4 ✓ ·
S-2 → §4 ✓ · S-3 → §4 ✓ · cadence (T-041, retros) → §4 ✓ · Q-1 → §5 ✓.

**16/16 resolve.** Additionally, architecture §5's S-1/S-2/S-3 query shapes
were compared element-by-element against system doc §4 and carry identical
substance (store query: basis-empty implementation-lane tickets without
owner/fleet provenance; git query: `\b[TU]-\d+\b`, ids resolved via
`task_get`; `history[].by`/`artifacts[].by`; `git worktree list` +
`git branch --contains`; candidate lists only). The identity whitelist is
recorded word-for-word as claimed ("pass-through, recorded unchanged" —
verified true).

### LT-006 — code-cited anchors observed at HEAD — **PASS**

Every code anchor in the architecture doc was checked read-only; observed
instances:

- **C-1 frontmatter** (`.opencode/agent/product-owner.md` lines 1–14):
  keys are exactly `description`, `mode: primary`, `tools` —
  `write`/`edit`/`patch`/`bash` all `false`; **no `model:` key** (confirms
  §4 step 1 and DR-6). The override sentence ("The maintainer can override
  into Build explicitly", line 7) anchors invariant 5.
- **D-3 `openChat` path**: `OpenChatHandler.java:16` (class), `:23` reads
  parameter `com.opencode.ide.chat.openChat.agentId`, `:33` calls
  `chat.preselectAgent(agentId)`; `ChatView.java:1058–1059` —
  `preselectAgent(String agentId)` → `selectors.selectAgent(agentId)`;
  `ServerView.java:528` — `openChatParameter("agentId", agent.id()) // v2
  wants the id, not the display name`; `ServerView.java:1121` gets the
  command via the constant.
- **One seam (DR-3/AD-005)**: `SessionViewIds.CHAT_OPEN_COMMAND_ID =
  "com.opencode.ide.chat.openChat"` lives in the **core** bundle
  (`SessionViewIds.java:38`, B-017 comment at `:11`); the pinning test
  AD-005 cites exists — `SessionViewIdsTest.java:76` asserts the constant.
- **D-2 guarded combo (AD-004)**: `ChatView.java:438` —
  `agentGuard = wireGuardedCombo(agentCombo, text -> selectors.selectAgent(text))`
  (the agent combo reuses the same guard as the model/variant combos wired
  at `:444`/`:458`); `wireGuardedCombo` factory at `ChatView.java:957`;
  `SelectorGuard.java:7–10` javadoc states exactly the claimed semantics —
  commit only when armed (dropdown opened by mouse-down, or Enter), un-armed
  drift returns `null` so the caller reverts to `committed()` (`attempt()`,
  `:45–52`); the user-report date AD-004 cites (2026-09-16) is in the
  javadoc.

**All dispatch/model anchors observed.** No invented or stale cite found in
this set.

### LT-007 — Q-1 stays OPEN — **PASS**

- Architecture §8: "OPEN, owner decision… This architecture does not resolve
  Q-1; the decision request stands with the owner" — matches system doc §5
  ("Status: OPEN. Not resolved by this document."), including the blast
  radius and the both-options framing. Nothing resolved anywhere.
- Recorded history verified: commit `7aab59b` (intake agent, owner
  directive) and `55f2823` (renamed to product-owner per owner) exist with
  the messages the architecture doc cites.

### LT-008 — store-path defect disposition — **PASS**

- Architecture §9 records the disposition: reported by the brief, found
  already fixed upstream. Verified true at HEAD: commit `97ea96e` exists —
  "T-084 review rework: the store-path references corrected
  (.harness/tasks) + the path defect recorded as note five per the
  reviewer's rework scope" —
  and the system doc §4 at HEAD reads `.harness/tasks/hephaestus/*.md`
  (the store-files grep instruction). The architecture doc itself uses the
  ground-truth path throughout (C-3's anchor: `.harness/tasks/hephaestus/` +
  the `task_*` API); the directory exists at that path (320 ticket files).
- The disposition is honest: recorded, not re-fixed, system doc untouched —
  matching T-105's lane constraint.

### LT-009 — no invented parallel structure vs the machine design — **PASS**

Every machine-side claim in the architecture doc is sourced from
`docs/design-stage-pipeline.md` or is a verbatim pass-through of the system
doc:

- §1 pattern evaluation quotes the design doc exactly: "the tick boundary is
  the only synchronization point" and "stages are decoupled through the
  store — never a direct handoff" (design doc §4) — used, correctly, to
  reject the event-driven pattern.
- C-4's responsibility (consume from the store, divide + derive, engine
  verdicts) matches the design doc's roles table and tick algorithm; "spawn
  capacity and done authority live here" matches the design doc (engine
  verdict: only `done` authority; spawn cap owned by the tickers).
- DR-2's Auto ▶ phrasing is the system doc invariant 4 pass-through (which
  itself matches AGENTS.md's dispatcher description); DR-7's candidate-list
  clause matches system doc §4.
- No new machine construct appears anywhere: no new stage, no new store
  mechanic, no second coordination channel. The architecture's only
  structural move (the C-3 store split) is named by the machine design's own
  minimal core.

### LT-010 — artifact-before-review store discipline — **PASS**

T-105 store history (via `task_get`): artifact event
`docs/intake-seat-architecture.md` at `10:52:15.213Z` (by
`ticker-v:T-105`), status update at `10:52:15.306Z` (in-review) — the
artifact precedes the review move; the `done` transition at `10:58:50.818Z`
carries `by: null` (the engine's verdict), and the division comment
following it is by `ticker-v`. **Done was never self-assessed** — AC-3's
substance holds; T-106 does not touch T-105's status.

### LT-011 — C-6 machine-keeping anchors at HEAD — **PASS, one recorded deviation**

- **MCP connect endpoint: verified.** The generic surface is
  `POST /api/experimental/mcp/:server/connect` (`OpencodeClient.java:411–424`
  javadoc; `HttpOpencodeClient.java:622` builds the path; the client
  component test asserts the wire shape,
  `HttpOpencodeClientComponentTest.java:650–667`); `opencode.json:8`
  registers the `tasks` server — so the architecture's
  `POST /api/experimental/mcp/tasks/connect` is the real instantiation.
- **Steward-session rule: verified.** AGENTS.md doctrine (chat-first control
  plane; ticker arming via `ticker-setup.ps1` by a shell-capable steward) —
  DR-5's route exists as doctrine today.
- **Board schedules switch (Auto/Tickers): NOT present at HEAD.**
  `BoardView.java` carries only `untrackedToggle` (`:171`/`:484`) and
  `archiveToggle` (`:180`/`:523`); the toolbar inventory of the GUI review
  dated today (`docs/eclipse-gui-review-2026-11-15.md`, "post-fleet state,
  all verified live") records the retired fleet surfaces absent — "no
  Auto ▶ pump, no Waves ▶ toggle". The switch is the machine design's
  chunk-5 surface (design doc §9: the Fleet toggle becomes the tickers'
  schedules switch, "Re-labeled (e.g. Auto/Tickers) in Chunk 5").

**Disposition of the deviation:** the cite is a faithful 1:1 pass-through —
the governing system doc §2 makes the same present-tense claim ("the Board's
schedules switch (Auto/Tickers) flips the tickers"), so LT-003/LT-005
(derivation, traceability) are unaffected and PASS. The staleness lives in
the *upstream* docs' wording, not in the architecture's structure: C-6's
semantic content (non-shell machine-keeping routes exist and keep the
pipeline alive) is true at HEAD via the two verified routes, and DR-2's
direction claim ("nothing dispatches to the seat") is *strengthened* by the
dispatcher being absent. Non-blocking for the architecture child's done
decision; the wording fix ("designed surface, chunk 5; `ticker-setup.ps1`
until it lands") belongs to the coherence pass on the upstream docs.

## Verdict

| ID | Criterion | Result |
|---|---|---|
| LT-001 | artifact + About header | **PASS** |
| LT-002 | component structure complete | **PASS** |
| LT-003 | components derivable 1:1 | **PASS** |
| LT-004 | dependency rules sourced | **PASS** |
| LT-005 | matrix resolves 16/16 | **PASS** |
| LT-006 | code anchors observed | **PASS** |
| LT-007 | Q-1 stays OPEN | **PASS** |
| LT-008 | defect disposition true | **PASS** |
| LT-009 | no invented parallel structure | **PASS** |
| LT-010 | store discipline | **PASS** |
| LT-011 | machine-keeping anchors | **PASS** (one recorded deviation — LT-011, non-blocking) |

**Overall judgment: PASS — the architecture child T-105 is supported for
done.** The artifact's structure is complete (6 components, 7 sourced
dependency rules), derivable 1:1 from the governing system doc with the one
refinement properly recorded, its traceability matrix fully resolves, every
code-cited anchor was observed at HEAD except the Board schedules switch —
an inherited, chunk-5-pending surface recorded above with its disposition.
No criterion failed; nothing was argued around. Follow-ups (not this lane):
the upstream wording disposition rides the coherence pass; the design stage
(T-115) may build on the boundaries fixed here.

## Lane

One new file (`docs/intake-seat-architecture.tests.md`). Read-only on
everything else: no source, doc, skill or store write besides this ticket's
own bookkeeping (artifact + status + comment). T-105's status untouched;
`done` not self-assessed here — the verdict above is evidence for the
engine's decision, not the decision.
