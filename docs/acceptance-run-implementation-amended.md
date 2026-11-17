# Implementation: the amended-attribution machinery — delta map (T-134)

## About this document

- **Kind:** `doc` / implementation-stage artifact of ticket T-134 (epic
  U-111, basis T-111, stage `implementation`): the amendment-delta
  implementation surface of the acceptance-run chain (the whole's
  implementation is sibling ticket T-127's — its lane is untouched here).
  For each amendment-touched component contract — C-A1 history &
  attribution, C-A2 review-pass evidence marks, C-A3 done-flip evidence
  flow — this delta map records either the **evidence of existing
  conformance** (engine code file:line, store verb surface, live ticket
  history) or the **implemented change**, exactly as the ticket's
  acceptance criteria demand — and, where a calibration-dependent claim
  does not survive the live store, the **recorded finding** that replaces
  it (F-1..F-3, from the 2026-11-17 rework pass).
- **Read by:** the paired `test-implementation` stage (its verification
  basis), the engine review pass, the reviewer.
- **Related:** `docs/acceptance-run-design-amended.md` (the governing
  design — the contracts this map verifies; cited [DSN]);
  `docs/acceptance-run-architecture.md` as amended 2026-11-15 (cited
  [ARCH]); `docs/acceptance-run-system.md` (the settled attribution
  re-specification, source of truth for the rule; cited [SYS]);
  `docs/design-stage-pipeline.md` (the U-110 design; cited [DSG]);
  `docs/acceptance-run-design.md` (the un-amended whole's design, T-095 —
  read-only neighbour; cited [WHOLE]);
  `.opencode/skills/stage-pipeline/SKILL.md` (module M5, the shared
  procedure; cited [SKILL]);
  `eclipse/bundles/com.opencode.ide.tasks/src/main/java/com/opencode/ide/tasks/TaskStore.java`
  (module M1's engine substrate; cited [STORE]);
  `eclipse/bundles/com.opencode.ide.tasks/src/main/java/com/opencode/ide/tasks/TaskToolProvider.java`
  (the `task_*` verb dispatch; cited [VERBS]).

## 1. Scope and method

The amendment (T-097 → T-098) added no modules and no edges — it
re-pointed attribution onto the store's observable reality ([DSN §1]).
Implementation therefore followed the same shape: a **substrate-first
reality check** per contract, with evidence-of-conformance preferred over
change. Method:

1. Read the governing contracts ([DSN §2 C-A1..C-A3], §4 data structures,
   §5 workflows, §9 calibration) and the amended architecture's invariants
   ([ARCH §Interfaces M1]; §D-point evidence flows).
2. For each contract, inspect the substrate that realizes it: the store
   engine ([STORE]/[VERBS]), the shared procedure ([SKILL]), and the live
   ticket histories (`.harness/tasks/hephaestus/` via `task_get`).
3. Implement only where a contract genuinely required it (exactly one
   change resulted — §3); verify everything else and record the evidence
   here.

Every timestamp below is read first-hand from the store (UTC); event shapes
are quoted as the store emits them. The first pass ran 2026-11-16; after the
engine's FAIL verdict of 2026-11-17 the C-A2 row was re-verified over the
**whole** store — a sweep of every `*.md` ticket file in
`.harness/tasks/hephaestus/` at **2026-11-17T14:40:53Z** (377 files, 328 with
`status: done`, 116 of those `done` on or after the 2026-11-15 cutoff; the
`done` flip = the last `updated:status` event of the chain). That sweep is the
source of every count in §2's C-A2 findings **F-1..F-3**: the first delivery's
containment and universal-mitigation claims were contradicted by it and are
replaced by recorded findings here, never restated as conformance.

Code locators (`[STORE :NNN]`, `[VERBS :NNN]`) were re-read against HEAD in
the same pass: `TaskStore.java` moves with every engine commit, so the numbers
are a dated snapshot (2026-11-17) — the cited *behaviors* (the `update`
signature's missing `by`, the `by`/`now()` stamps, the done-time artifact
guard, the correction verbs' appended events) are what carries the evidence
and re-verify independently of the line numbers.

## 2. The delta map

### C-A1 — store history & attribution surface (M1) — VERIFIED CONFORMANT, no change

The substrate realizes partial attribution **by API shape**, exactly as
[DSN §2 C-A1] contracts it:

| Contract clause | Substrate evidence | Live-store confirmation |
|---|---|---|
| `append(event)` — history append-only, internal to every verb write | [STORE] `transaction(...)` is the only write path; no verb rewrites or deletes history events (the correction verbs — `task_remove_artifact` [VERBS :227] — append *new* events recording what was removed, [STORE :1062-1075]) | every observed history is append-ordered; the A-003 duplicate-artifact remediation removed index entries and *appended* removal events |
| Status transitions record only the change — no `by`, no caller-stampable actor | [STORE] `update()` (:411) takes **no `by` parameter** and appends `t.history("updated:" + String.join(",", applied), null)` (:594) — the bare shape *cannot* accept an actor, so no caller can stamp one | `updated:status` / `updated:status,assignee` events with `by: null` on every observed chain: T-031 claim 17:45:38.733Z, T-095 claim 08:28:51.285Z, T-111 flip 08:28:57.364Z, T-113 flip 08:29:02.795Z, T-031/T-095/T-111/T-113 done flips — all `by: null` |
| Claim and artifact writes carry the acting identity (`by` present) | [STORE] `claim()` (:966) appends `claimed by <who>` with `by` (:997); `addArtifact()` (:1035) stamps `by` on both the artifact record (:1045) and the history event `artifact:<kind>:<ref>` (:1048) | T-031 artifact 17:48:41.187Z by `requirements-worker`; T-095 artifact 08:41:12.186Z by `ticker-v:T-095`; T-113 artifact 08:28:55.570Z by `ticker-v:T-113`; T-095 release 08:22:48.642Z by `ticker-v:resume` |
| The claim transition's identity travels as **data**, not a stamped event actor | the claim's identity is the `assignee` value written by the (bare) transition — caller-asserted data, e.g. the `ticker-v:<ticketId>` machinery namespace ([SKILL] kickoff step) | `updated:status,assignee` events carry `by: null` while the ticket's `assignee` field holds `ticker-v:T-095` / `ticker-v:T-111` / `ticker-v:T-113` — matching [SYS §Interfaces 1]'s amended D-1 wording |
| Identities caller-asserted, never authenticated (DS-005) | no validation code on any `by` parameter anywhere in [STORE] — values are recorded exactly as given | the observed identity zoo (`review`, `ticker-v:review`, `ticker-v:T-111`, `requirements-worker`, `system-ticker`) — recorded as given, judged at read time |
| Ordering guarantee (the input C-A2's decision needs) | history is append-ordered per ticket; `task_get` returns it ordered | every chain this map walks reads `in-review` strictly before `done` (§ C-A3 rows); the 30 post-cutoff `done`s with **no** `in-review` predecessor are recorded as the ordering anomaly (§ C-A2 F-3 point 2), not counted as conformant |

Deliberate absences verified ([DSN §2 C-A1] "deliberately absent"):
no actor-stamped status transition exists anywhere in the verb surface
(parameter absence makes it unwritable), and no identity authentication
exists (no auth code on `by`). Negative sweep: a repo-wide grep for
grandfathering/stamping logic in code finds nothing (§ Grandfathering
below).

### C-A2 — review-pass evidence marks (M4) — carrier & retrofit-proofing VERIFIED CONFORMANT; window / identity set / mitigation recorded as findings F-1..F-3, no change

**Mark-2 is the emission practice of the review pass** — engine-verdict
comments by `ticker-v:review` (and `review`) accompany the fleet review's
done flips — but three claims that depend on the §9 calibration data are
contradicted by the live store. They are recorded below as **F-1 (window), F-2
(identity set), F-3 (unmarked dones)** in the same shape the map gave the
mark-1 finding: observation, consequence, what follows — no change
implemented, because the calibration values are [DSN §9] *data* owned by the
design's lane. **Containment is asserted nowhere in this row.**

First-hand timed instances (offset = verdict-comment `ts` − done-flip `ts`;
negative = comment before the flip):

| Ticket | Mark-2 comment (`by`) | Verdict ts | Done flip ts | Offset |
|---|---|---|---|---|
| T-031 (2026-11-14, pre-cutoff) | "ENGINE VERDICT: PASS …" (`review`) | 17:50:09.779Z | 17:50:09.830Z (`updated:status`, bare) | **−51 ms** |
| T-095 (2026-11-16) | "Engine verdict PASS (design …)" (`ticker-v:review`) | 08:48:28.134Z | 08:48:28.203Z (bare) | **−69 ms** |
| T-113 (2026-11-16) | "Engine verdict PASS …" (`ticker-v:review`) | 08:39:17.448Z | 08:39:17.532Z (bare) | **−84 ms** |
| T-111 (2026-11-16) | "Engine verdict PASS (design …)" (`ticker-v:review`) | 08:59:15.770Z | 08:59:15.863Z (bare) | **−93 ms** |
| A-003 (2026-11-15) | "Engine verdict PASS (architecture …)" (`ticker-v:review`) | 09:03:47.336Z | 08:58:10.064Z (bare) | **+5 min 37.272 s** |

Table shape, stated exactly (the first delivery miscounted it): **five rows** —
three dated 2026-11-16 (T-095, T-113, T-111), one 2026-11-15 (A-003), one
2026-11-14 (T-031) — and **four of them are comment-before-flip** (T-031 plus
the three 11-16 rows); A-003 is the single comment-after-flip row. Of those
four, only T-031's −51 ms lies inside the governing `before = 51 ms` bound
([DSN §4]; [SYS §Interfaces 1]) — and **no post-cutoff instance at all**
falls inside that bound (F-1). All five offsets were re-read from the store
on 2026-11-17.

- **F-1 — `ReviewBatch` window: the observed cluster is NOT inside the
  calibrated bounds (recorded finding: basis-bound mismatch, not a defect
  fixed here).** Sweep 2026-11-17T14:40:53Z — 116 post-cutoff `done` flips;
  per done, the nearest `ReviewPassIdentity` comment measured against the
  flip:

  | Nearest review-identity comment vs the flip | Instances | Inside [DSN §4] `before = 51 ms` … `after ≈ 17 min`? |
  |---|---|---|
  | after-flip, +53 ms … +16 min 33 s | **21** (T-042, T-051, T-054–T-058, T-083, T-085, T-086, T-093, T-097–T-099, T-101, T-102, A-003, T-034, T-036, T-039, T-041) | yes |
  | before-flip, −594 … −60 ms | **52** (incl. T-095 −69, T-113 −84, T-111 −93; 2026-11-17 batch: T-069 −594, T-066 −545, T-125 −286, T-132 −197, T-060 −128) | **no** |
  | before-flip, < −594 ms | **5** (U-096 −3.155 s, T-096 −32.405 s, T-116 −5 min 9 s, T-038 −16 min 27 s, T-084 −34 min 13 s) | **no** |
  | after-flip, > 17 min | **1** (T-035 +18 min 58 s) | **no** |
  | no review-identity comment anywhere in the file | **37** (F-3) | no mark at all |

  116 = 21 + 52 + 5 + 1 + 37 (79 marked, 37 unmarked). What follows from the
  mismatch, stated exactly:

  1. `before = 51 ms` admits **none** of the 57 observed
     comment-before-flip marks. That is structural, not incidental: [DSN §5
     W-2] orders `recordVerdictComment` *then* `flipDone`, so the bound
     measures the flip's lag behind its own verdict comment — 51 ms is
     exactly T-031's lag (2026-11-14), while the observed post-cutoff lag
     runs **60 ms (T-117) … 34 min 13 s (T-084)**. The pre-cutoff `review`
     population does not fit the bound either (2026-11-10: U-094 −45 ms
     inside, but U-108 −61 ms, B-026 −65 ms, U-107 −161 ms outside).
  2. Applied literally to the sweep, the quoted bounds decide `engine-set`
     for **21 of 116** post-cutoff dones and `undecidable` → **fails D-3**
     for **95** ([DSN §5 W-3 step 6]) — 58 window-misses + 37 unmarked.
  3. The re-measured numbers a §9 extension would need are recorded as data,
     not adopted as bounds: `before` ≥ **594 ms** covers the tight
     11-16/11-17 cluster (73 of the 79 marked), while `before` ≥ **34 min
     13 s** with `after` ≥ **18 min 58 s** covers all 79. Neither rescues
     F-3's 37 unmarked dones, and a window stretched to fit every instance
     stops being a batch — so this map adopts no bound and asserts no
     containment. Re-calibration of [DSN §4]/[SYS §Interfaces 1] is a §9 data
     change in their lane (recorded as a follow-up in §4).

- **F-2 — `ReviewPassIdentity`'s recorded set is incomplete (recorded
  finding; the design's error clause applied honestly).** The recorded set
  {`review`, `ticker-v:review`} covers 77 of the 79 marked dones
  (`ticker-v:review`) plus 2 (`review`: U-096, U-101) — but two further
  verdict-writing identities exist in the same window:

  - `ticker-v` applied **A-002**'s engine verdict — "Engine verdict PASS
    applied (ticker-v pass 2026-11-15, reviewer ses_e2…)" at
    2026-11-15T11:00:05.625Z, **+68 ms** after A-002's post-cutoff done
    (11:00:05.557Z). Under [DSN §2 C-A2]'s error clause a `by` outside the
    observed set "is recorded as a plain comment and is **not a mark**" — so
    A-002's post-cutoff PASS done has **neither mark**: honestly recorded as
    **`undecidable` under the current set** (W-3 step 6 → fails D-3), even
    though verdict text, timing and reviewer session are all in the file.
    A-002 is basis-child of A-001 (its `basis: A-001`, epic U-111) — the
    sibling of A-003 in the very division this map's §2 cites, so the gap
    sits inside the cited chain, not outside it.
  - `po-build` writes verdict-shaped comments on owner closes — T-071
    "VERDICT PASS 2026-11-17" (−236 ms) and T-072 "VERDICT PASS 2026-11-17"
    (−172 ms) — non-review-pass identities, so [DSN §6]'s row "recorded as a
    plain comment; not a mark" applies verbatim.

  Adding a label to the set is [DSN §9] calibration data (an owner/design
  decision — [DSN §9] says a new review-pass label changes `Decision`
  inputs only) — and it is not cost-free, which is why this map records
  rather than adopts: `ticker-v` is also the ticker's bookkeeping identity
  on the same tickets (61 comments on post-cutoff dones, 45 of them
  division notes), so admitting it would turn T-105/T-107/T-109's division
  notes (+198 … +217 ms after their flips) into marks although those tickets
  carry **no** verdict comment at all.
- **Negative control holds**: A-001's escalation comment by
  `ticker-v:review` at 2026-11-15T09:03:46.828Z lands ≈15 h after A-001's
  done (18:02:20.790Z the day before) — far outside any batch, correctly
  **not a mark**. (That escalation is the finding that triggered the
  amendment itself — T-097.)
- **Mark-2's carrier is the existing comment verb on E3** — no new verb,
  no new edge ([DSN §2 C-A2]): `task_add_comment` [VERBS :207] → [STORE
  `addComment()` :1022], `by` recorded as given (:1027). Verified: the verb
  surface offers exactly the E2/E3 lists the design names.
- **Retrofit-proofing holds at the substrate level** (DS-003):
  `addComment` stamps `now()` ([STORE :1027]) — a comment written after a
  flip can never fall inside that flip's batch window, so a retrofitted
  verdict comment is structurally ineffective; grandfathering cannot be
  upgraded after the fact.
- **F-3 — 37 post-cutoff dones carry no mark at all (recorded finding).**
  Sweep 2026-11-17T14:40:53Z: of the 116 `done` flips on/after
  `CutoffDate`, **37 hold no comment by a `ReviewPassIdentity` anywhere in
  their file** — grouped by the identity accompanying the close (nearest
  comment to the flip):

  | Closing identity | Count | Tickets |
  |---|---|---|
  | `po-build` | 27 | T-044, T-045–T-050, T-052, T-053, T-071, T-072, T-129, T-140–T-143, T-145–T-154, U-109 |
  | `po-pause` | 1 | T-127 |
  | `ticker-v` | 5 | A-002 (its verdict is under that identity — F-2), T-105, T-107, T-109, T-144 (no comment at all near the flip; its only comment is a division note +24 h 38 min later) |
  | `pm-housekeeping` | 2 | U-110 (−53 ms), U-060 (−64 ms) |
  | `pm-closure (Build mode)` | 1 | U-113 (−93 ms) |
  | `stage-audit` | 1 | T-043 (nearest comment −44.962 s) |

  The reviewer's "~25 post-cutoff owner closes" enumerates to **29 ids**
  exactly — T-044, T-045–T-050, T-052, T-053, T-071, T-072, T-127, T-129,
  T-140–T-154, U-109 — of which 28 carry a `po-build`/`po-pause` closing
  comment and T-144 none (its artifact event is stamped `po-build`, so it is
  the same owner-mode class).

  What follows:

  1. All 37 classify `undecidable` → fail D-3 under [DSN §5 W-3] step 6 —
     that step reads "done on or after the cutoff, no mark" and does not
     depend on PASS-ness. The three fleet instances — **T-105**
     (10:58:50.818Z), **T-107** (11:09:11.034Z), **T-109**
     (11:11:14.760Z) — are full `claim → artifact → in-review → done`
     chains with **zero verdict comments**; their only post-flip comment is
     the ticker's division note by `ticker-v` (+214 / +217 / +198 ms), which
     F-2 shows is not a mark under the recorded set. A-002 is F-2's case.
  2. **27 of the 37 have no preceding bare `updated:status` at all** (T-043,
     T-044, T-045–T-050, T-052, T-053, T-071, T-072, T-127, T-129,
     T-140–T-144, T-146, T-150, T-153, T-154, U-060, U-109, U-110, U-113):
     for 24 of them the `done` is the ticket's **only** status transition in
     existence, and for the other three only claim-shaped
     `updated:status,assignee` events precede it — so no bare `in-review`
     precedes their `done` (four of the 27 even write the `done` itself
     claim-shaped: T-043, U-060, U-110, U-113). Three *marked* dones share
     the same shape (T-038, T-057, T-058 — two claim-shaped transitions then
     `done`), which makes **30 of the 116** post-cutoff dones fail W-3's
     ordering precondition (step 2): the marks cannot be read "together with
     the ordering" → `undecidable`, flagged as the store-integrity anomaly
     [DSN §5] prescribes.
  3. Owner-mode closes reach `done` through the same `update()` verb with no
     review-pass identity vouching for them, and T-071/T-072 carry a
     *non*-review-pass "VERDICT PASS" comment — [DSN §6]'s error rows, not a
     mark. Recorded, not re-decided: the product-owner's `done` authority is
     a product-owner question outside this doc's lane (§4 item 7).

- **Mitigation rule — scoped to what the evidence supports (this replaces
  the first delivery's universal claim).** The claim "*every* PASS done flip
  on or after 2026-11-15 carries mark-2 … no unmarked post-cutoff done
  exists" is **withdrawn as contradicted by F-3** (37 counter-instances;
  [SYS §Interfaces 1] carries the same over-claim in "recorded on every PASS
  flip since 2026-11-15" — recorded as F-1's re-calibration follow-up, §4).
  What the data does support, and all that this map asserts:

  - **79 of 116** post-cutoff dones (68%) carry a review-pass-identity
    verdict comment, and `ticker-v:review` emission is consistent across the
    fleet review's own batches — the −594 … −60 ms before-flip cluster of
    2026-11-16/17, and on 2026-11-15 a +53 … +188 ms after-flip cluster plus
    longer lags (+5 min 37 s … +16 min 33 s) that still sit inside
    `after ≈ 17 min`, T-035's +18 min 58 s excepted;
  - of the 37 counter-instances only **four** are fleet-stage dones (T-105,
    T-107, T-109, A-002); the other **33** are owner-mode closes (29) and
    housekeeping/audit closes (4), which the rule's PASS framing arguably
    does not cover but which W-3 step 6 still classifies `undecidable`;
  - therefore mark-2 coverage is a property of the **review pass's own
    flips**, not a store-wide invariant, and `undecidable` post-cutoff dones
    exist in the live store (37, or 95 under the quoted §4 bounds — F-1).

  C-A2 conformance is accordingly asserted for what is verified — the carrier
  verb, both mark shapes as decision inputs, and retrofit-proofing — while
  the window, identity-set and mitigation claims stand recorded as F-1..F-3
  for their owners (§9 data in [DSN]/[SYS]) to resolve.

**Recorded finding (observation, not a defect — no change implemented):**
mark-1 — the annotated done event **by a review-pass identity** ([DSN §2
C-A2]; the observed 2026-11-14 shape) — exists in history exactly once in
the observed store: A-001's `done (engine review pass: product PASS, lint
clean)` by `review` at 2026-11-14T18:02:20.790Z. The bare *shape* (an
annotated `done`) appears four more times, all legacy — `done: <text>` by
`chat-session` at 2026-09-16T13:45:00.000Z on C--001, U-003, U-004, W-005 —
annotated but written by a non-review-pass identity, so they are not mark-1
either, and all four are pre-cutoff → `grandfathered-legacy` at read time
(W-4). The **current verb surface cannot emit
mark-1**: `task_update` [VERBS :131] is the only `done` path and routes to
[STORE] `update()` (:411), which has neither an annotation nor a `by`
parameter — so every current done flip is a bare `updated:status`, and
mark-2 is the only mark today's machinery can produce. This matches the
design's own framing: mark-1 is recorded as an *observed* shape, the
identities and window are observed calibrations carried as data
([DSN §9]), and the mitigation rule requires *at least one* of the two
marks — met on the review pass's own batch flips (scoped in F-3; the
store-wide universal form of that rule is recorded contradicted there).
Implementing an
annotated-done write path would add a verb capability the settled basis
explicitly excludes ([DSN §8 consistency paragraph: "no … third mark
shape, a wider mark carrier, … any new edge or verb"]), so conformance
here means: both mark shapes remain valid *decision inputs* (W-3 step 3
reads history, where A-001's mark-1 is readable), and the *emission*
practice is mark-2.

### C-A3 — done-flip evidence flow (M3 → M4 across M1) — VERIFIED + ONE IMPLEMENTED CHANGE (the canonical sequence, §3)

**Worker side (M3, E2).**

- **Implemented change:** [SKILL]'s fulfillment section named the
  artifact step and the bare `in-review` stop but omitted the fulfillment
  comment between them — the canonical sequence was carried by the live
  spawn briefs and the design docs, not by the shared procedure text.
  §3 inserts the step, so [SKILL] now carries C-A3's sequence verbatim:
  artifact → fulfillment comment (naming the stage skill) → bare
  `in-review` flip as the worker's LAST write.
- **Observed canonical instance** (the sequence [DSN §2 C-A3] prescribes):
  T-095 — artifact 08:41:12.186Z (stamped) → fulfillment comment
  08:41:24.241Z → bare `in-review` flip 08:41:35.566Z. The T-095 engine
  verdict cites this chain as "the amended D-1 evidence chain exactly".
- **Observed deviation class** (recorded by the engine verdicts as
  non-blocking; history stays as written — append-only): T-111's
  fulfillment comment landed +22 s after its flip (08:28:57.364Z flip →
  08:29:19.576Z comment; the verdict's pattern note records exactly the
  canonical order this ticket codifies); T-113's +2.9 min
  (08:29:02.795Z → 08:31:58.846Z); T-031's +10.8 s (17:48:41.243Z →
  17:48:52.050Z, the original run). In every instance D-1 evidence stays
  decidable — [SYS] requires the identity comment *adjacent* to the flip,
  and artifact-before-flip held everywhere.
- **Bare flip as last write**: every observed `in-review` event is
  `updated:status` with `by: null` (timestamps above) — the worker's last
  write, no stamped actor, per [DSN §2 C-A3].

**Verdict side (M4, E3 + E7).**

- **Required ordering**: `in-review` strictly before `done` holds in every
  chain this section walks (e.g. T-095 08:41:35.566Z → 08:48:28.203Z; T-111
  08:28:57.364Z → 08:59:15.863Z) and in 86 of the 116 post-cutoff dones;
  the marks are read together with that ordering, and in the four
  comment-before-flip rows of the §2 table the verdict comment sits 51–93 ms
  ahead of the flip it marks (A-003's +5 min 37 s after-flip case and every
  window-miss are F-1). The **30 chains with no preceding bare transition**
  (F-3 point 2) are the recorded ordering anomaly, not a silent pass.
- **No fleet worker/ticker `done` in any chain this map cites**: T-031,
  T-095, T-113, T-111 each `done` directly trails its mark-2 comment, and no
  ticker-spawned stage worker (`ticker-v:<id>` / `*-worker`) shows a `done`
  it drove — every `done` is a bare `updated:status`, so the applier is in
  any case unstamped (C-A1). Enforcement layers observed in the machinery:
  the brief's NEVER-set-done rule ([SKILL] fulfillment; every spawn prompt),
  the done-time artifact guard ([STORE :568-580] — a definition-stage ticket
  cannot reach `done` without a recorded artifact), and the review pass as
  the `done` applier for the fleet passes. Honest boundary notes: (a) the
  store's `update` verb itself accepts `status: done` from any caller that
  reaches it — the M2/M3 → `done` denial of [ARCH §Dependency rules] is held
  by the three layers above (brief, guard, review discipline), which is the
  running machinery's conformance shape for FR-017/FR-021's write-surface
  limitation; (b) the review pass is **not** the only `done` applier in
  practice — 33 of F-3's 37 unmarked closes are accompanied by owner-mode or
  housekeeping/audit writes (29 owner-mode: `po-build` ×27, `po-pause` ×1,
  plus T-144's `po-build` artifact; 4 housekeeping/audit: `pm-housekeeping`
  ×2, `pm-closure (Build mode)`, `stage-audit`), so [SYS D-3]'s "sole `done`
  authority" / "no other `done`" does not describe the live store (F-3).
  Neither note was re-decided here: widening the store with a caller check
  would re-decide engine-owned substrate ([DSN §1 pass-through, AD-001]),
  and the product-owner's `done` authority is a product-owner question
  outside this doc's lane.

**Decidability invariant walked end-to-end** (W-3, read from M1 alone) on
T-095: claim (bare transition; identity in `assignee`) → artifact
(stamped, 08:41:12.186Z) → fulfillment comment (08:41:24.241Z) →
in-review (bare, 08:41:35.566Z) → verdict comment
(`ticker-v:review`, 08:48:28.134Z, 69 ms ahead of the flip) → done (bare,
08:48:28.203Z, post-cutoff) → **`engine-set` under the re-measured
calibration** (the comment sits in F-1's −594 … −60 ms cluster and *outside*
the quoted `before = 51 ms` bound — F-1 is why the verdict is conditional
rather than asserted). D-1 and D-3 both decided with no stamped transition
actor anywhere and no worker/ticker `done` in
the chain — the flow's whole point ([DSN §2 C-A3]).

### Grandfathering (DS-001 / W-4) — VERIFIED READ-TIME-ONLY, no write path exists

- **No write path in code**: a repo-wide search for grandfathering or
  back-fill logic across all code surfaces (Java, PowerShell, TypeScript,
  JavaScript, JSON) returns **zero** matches — the classification exists
  only as the read-time decision procedure ([DSN §5 W-3/W-4];
  SYS §Interfaces 1).
- **The store cannot execute a grandfathering write**: history events are
  only ever appended inside transactions; no verb mutates or deletes a
  past event; and the comment verb's `now()` stamp ([STORE :1027]) makes a
  retrofit verdict comment land outside any past flip's batch window by
  construction — DS-003's "retrofitting is structurally ineffective"
  holds at the substrate level.
- **Legacy instance**: A-001's done (2026-11-14, pre-cutoff) carries
  mark-1 anyway; any pre-cutoff unmarked done classifies
  `grandfathered-legacy` at read time and stays untouched — no verb could
  repair it short of a hand edit, which is a denied edge ([ARCH §Dependency
  rules]; AGENTS.md write discipline).

## 3. The implemented change (the delta map's one change)

**File:** `.opencode/skills/stage-pipeline/SKILL.md` (module M5's owning
file — the shared procedure every worker loads, [DSG §6: skills are the
carriage of process change]).

**Gap:** the fulfillment section stepped 1-2-3-4-5 (read → work →
artifact → `in-review` → blocked) with **no fulfillment-comment step** —
C-A3's step 3 was absent from the machinery's standing documentation,
while the live spawn briefs carried it (the T-111 verdict's pattern note
recorded the canonical order for exactly this purpose).

**Change:** inserted the fulfillment comment between the artifact step and
the flip, and marked the flip as the bare LAST write:

```text
   4. Write the fulfillment comment: `task_add_comment` with `by` = your
      own identity — the completion report naming your stage skill. It is
      your identity evidence for the flip and lands BEFORE it: the
      canonical write order is artifact → fulfillment comment →
      `in-review`.
   5. Set the ticket `in-review` — a bare flip and your LAST write. NEVER
      set `done`: the verdict is the engine's review pass + quality gate,
      never your self-assessment.
   6. If blocked, `task_set_blocked` with the reason naming what you need.
      Do not improvise around a blocker.
```

This changes no procedure shape the amendment did not already fix:
the fulfillment comment is [WHOLE]'s DD-01 sequence step and [DSN §2 C-A3]
step 3 — the skill text catches up to the standing design; [DSN §7]'s
E5/E6 pass-through ("the tick and fulfillment procedures are unchanged")
is respected, as is the tick procedure (steps untouched — C-A2's mark is
the review pass's standing practice, and the design prescribes no tick
text change).

## 4. Findings and non-goals

1. **Mark-1's emission path retired with the predecessor machinery**
   (§ C-A2 mark-1 finding) — recorded as calibration-consistent observation;
   the settled basis excludes adding a verb, and mark-2 is the emission the
   running machinery can produce (its store-wide coverage claim is *not*
   asserted: F-3 scopes it).
2. **Late fulfillment comments exist in live history** (T-031/T-111/T-113)
   — classified non-blocking by the engine verdicts (adjacency holds,
   D-1 decidable); the codified canonical order prevents recurrence;
   append-only history keeps the past as written.
3. **The worker-done denial is layered, not a store-side caller check**
   (§ C-A3 honest boundary note) — brief + done-time guard + review
   discipline hold it in the running machinery; recorded, not re-decided.
4. **Lane hygiene:** exactly two files carry this ticket's work (the delta
   map itself and [SKILL]; the 2026-11-17 rework touched only the delta
   map). Not touched: the sibling whole-implementation ticket T-127's
   lane (in flight), `docs/acceptance-run-design.md` ([WHOLE] — read-only
   neighbour), any store file, any engine code (no contract required an
   engine change — C-A1's substrate conforms by API shape). No concrete
   provider or model ids anywhere (D-004).
5. **F-1 — the governing `ReviewBatch` bound excludes most live marks**
   (§ C-A2): `before = 51 ms` / `after ≈ 17 min` matches one instance
   (T-031) and contains 21 of the 79 marked post-cutoff dones; the
   re-measured distribution (60 ms … 34 min 13 s before-flip; up to 18 min
   58 s after-flip) is recorded in §2 as [DSN §9] input. **Follow-up (their
   lane, not this one):** re-calibrate [DSN §4] and [SYS §Interfaces 1],
   whose "observed span: 51 ms before to ~17 min after" and "recorded on
   every PASS flip since 2026-11-15" over-claim the live store.
6. **F-2 — the recorded `ReviewPassIdentity` set is incomplete**
   (§ C-A2): `ticker-v` applies A-002's engine verdict (+68 ms) and
   `po-build` writes verdict-shaped owner comments (T-071/T-072); under
   [DSN §2]'s error clause neither counts, so A-002 decides `undecidable`.
   Promoting a label is §9 data with a real cost (division comments would
   become marks) — an owner/design decision, recorded not taken.
7. **F-3 — 37 post-cutoff dones are unmarked and 30 lack the ordering
   precondition** (§ C-A2): 29 owner-mode closes, 4 housekeeping/audit
   closes, T-105/T-107/T-109 (zero verdict comments) and A-002 (F-2);
   recorded as counter-evidence to the mitigation rule's universal form and
   as the D-3 `undecidable` population a verdict must weigh. The owner's
   `done` authority (29 closes) is deliberately *not* re-decided here —
   product-owner question, outside this doc's lane.

## 5. Acceptance criteria mapping (T-134)

- **AC-1** — *every contract C-A1..C-A3 holds in the running machinery:
  implemented or verified as already-conformant, with a recorded delta
  map*: §2 — C-A1 verified conformant (substrate by API shape + live
  histories); C-A2 verified for exactly what the store evidences — the
  mark-2 carrier verb, both mark shapes as decision inputs, and
  retrofit-proofing hold, with the mark-1 emission finding still recorded,
  while the three §9-calibration-dependent claims (window containment,
  identity-set completeness, universal mitigation) are **recorded as
  findings F-1..F-3** against the 2026-11-17 post-cutoff sweep instead of
  asserted (the rework after this ticket's 2026-11-17 FAIL verdict);
  C-A3 verified with one implemented change (§3), grandfathering
  verified read-time-only with no write path.
- **AC-2** — *the canonical worker write sequence (artifact → fulfillment
  comment → `in-review` flip) is honored by the machinery's
  documentation/users where the design prescribes it*: §3 — the sequence
  is now in [SKILL] (the machinery's standing documentation, M5); the
  observed canonical instance is T-095's chain; the known deviations are
  recorded with the engine verdicts' own non-blocking classification;
  this ticket's own fulfillment follows the canonical order (artifact →
  fulfillment comment → flip).
- **AC-3** — *the work is committed, the artifact recorded on this
  ticket, and the ticket is in-review; done is never self-assessed*:
  store discipline — commit citing T-134; `task_add_artifact` (kind
  `doc`) before the `in-review` flip; `done` is the engine's verdict
  alone.

## 6. Downstream hand-off

The paired `test-implementation` stage verifies against this map: the §2
evidence rows are the oracle anchors (code file:line, store timestamps),
the §3 change is the fail-to-pass surface (the skill text before/after),
and the §4 findings are the recorded non-goals a verdict must weigh
rather than re-litigate — F-1..F-3 included: they are *measured* evidence
(pinned to the 2026-11-17T14:40:53Z sweep), not open claims, and the
store is live, so a later sweep re-derives those totals instead of diffing
them. The paired criteria (T-135) re-derive their C1 from this corrected
C-A2 row, not from the first delivery's containment / universal-mitigation
wording. The W-3 decision procedure ([DSN §5]) run
against any cited chain is the integration-level check.
