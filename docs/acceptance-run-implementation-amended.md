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
  acceptance criteria demand.
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

Every timestamp below is read first-hand from the store on 2026-11-16
(UTC). Event shapes are quoted as the store emits them.

## 2. The delta map

### C-A1 — store history & attribution surface (M1) — VERIFIED CONFORMANT, no change

The substrate realizes partial attribution **by API shape**, exactly as
[DSN §2 C-A1] contracts it:

| Contract clause | Substrate evidence | Live-store confirmation |
|---|---|---|
| `append(event)` — history append-only, internal to every verb write | [STORE] `transaction(...)` is the only write path; no verb rewrites or deletes history events (the correction verbs — `task_remove_artifact` [VERBS :209] — append *new* events recording what was removed, [STORE :872-885]) | every observed history is append-ordered; the A-003 duplicate-artifact remediation removed index entries and *appended* removal events |
| Status transitions record only the change — no `by`, no caller-stampable actor | [STORE] `update()` (:362) takes **no `by` parameter** and appends `t.history("updated:" + String.join(",", applied), null)` (:493) — the bare shape *cannot* accept an actor, so no caller can stamp one | `updated:status` / `updated:status,assignee` events with `by: null` on every observed chain: T-031 claim 17:45:38.733Z, T-095 claim 08:28:51.285Z, T-111 flip 08:28:57.364Z, T-113 flip 08:29:02.795Z, T-031/T-095/T-111/T-113 done flips — all `by: null` |
| Claim and artifact writes carry the acting identity (`by` present) | [STORE] `claim()` (:778) appends `claimed by <who>` with `by` (:808); `addArtifact()` (:846) stamps `by` on both the artifact record (:856) and the history event `artifact:<kind>:<ref>` (:858) | T-031 artifact 17:48:41.187Z by `requirements-worker`; T-095 artifact 08:41:12.186Z by `ticker-v:T-095`; T-113 artifact 08:28:55.570Z by `ticker-v:T-113`; T-095 release 08:22:48.642Z by `ticker-v:resume` |
| The claim transition's identity travels as **data**, not a stamped event actor | the claim's identity is the `assignee` value written by the (bare) transition — caller-asserted data, e.g. the `ticker-v:<ticketId>` machinery namespace ([SKILL] kickoff step) | `updated:status,assignee` events carry `by: null` while the ticket's `assignee` field holds `ticker-v:T-095` / `ticker-v:T-111` / `ticker-v:T-113` — matching [SYS §Interfaces 1]'s amended D-1 wording |
| Identities caller-asserted, never authenticated (DS-005) | no validation code on any `by` parameter anywhere in [STORE] — values are recorded exactly as given | the observed identity zoo (`review`, `ticker-v:review`, `ticker-v:T-111`, `requirements-worker`, `system-ticker`) — recorded as given, judged at read time |
| Ordering guarantee (the input C-A2's decision needs) | history is append-ordered per ticket; `task_get` returns it ordered | every observed chain reads `in-review` strictly before `done` (§ C-A3 rows) |

Deliberate absences verified ([DSN §2 C-A1] "deliberately absent"):
no actor-stamped status transition exists anywhere in the verb surface
(parameter absence makes it unwritable), and no identity authentication
exists (no auth code on `by`). Negative sweep: a repo-wide grep for
grandfathering/stamping logic in code finds nothing (§ Grandfathering
below).

### C-A2 — review-pass evidence marks (M4) — VERIFIED CONFORMANT (standing practice), one recorded finding, no change

**Mark-2 is the operative mark and is applied on every observed PASS done
flip since the cutoff.** Observed instances, first-hand from the store
(offset = verdict-comment `ts` − done-flip `ts`; negative = comment before
the flip):

| Ticket | Mark-2 comment (`by`) | Verdict ts | Done flip ts | Offset |
|---|---|---|---|---|
| T-031 (2026-11-14) | "ENGINE VERDICT: PASS …" (`review`) | 17:50:09.779Z | 17:50:09.830Z (`updated:status`, bare) | **−51 ms** |
| T-095 (2026-11-16) | "Engine verdict PASS (design …)" (`ticker-v:review`) | 08:48:28.134Z | 08:48:28.203Z (bare) | **−69 ms** |
| T-113 (2026-11-16) | "Engine verdict PASS …" (`ticker-v:review`) | 08:39:17.448Z | 08:39:17.532Z (bare) | **−84 ms** |
| T-111 (2026-11-16) | "Engine verdict PASS (design …)" (`ticker-v:review`) | 08:59:15.770Z | 08:59:15.863Z (bare) | **−93 ms** |
| A-003 (2026-11-15) | verdict comment (`ticker-v:review`) | — | — | **+5 min 37 s** (recorded on the A-002 rework report) |

- **Observed identity set** {`review`, `ticker-v:review`} - both observed
  on in-batch verdict comments, matching the §9 calibration data
  ([DSN §4 `ReviewPassIdentity`, §9]).
- **ReviewBatch window**: the observed cluster sits within the calibrated
  `before = 51 ms` … `after ≈ 17 min` bounds ([DSN §4 `ReviewBatch`;
  SYS §Interfaces 1]). The four 2026-11-16 instances are all
  comment-before-flip; the widest observed after-flip instance is A-003's
  +5 min 37 s.
- **Negative control holds**: A-001's escalation comment by
  `ticker-v:review` at 2026-11-15T09:03:46.828Z lands ≈15 h after A-001's
  done (18:02:20.790Z the day before) — far outside any batch, correctly
  **not a mark**. (That escalation is the finding that triggered the
  amendment itself — T-097.)
- **Mark-2's carrier is the existing comment verb on E3** — no new verb,
  no new edge ([DSN §2 C-A2]): `task_add_comment` [VERBS :189] → [STORE
  `addComment()` :833], `by` recorded as given (:838). Verified: the verb
  surface offers exactly the E2/E3 lists the design names.
- **Retrofit-proofing holds at the substrate level** (DS-003):
  `addComment` stamps `now()` ([STORE :838]) — a comment written after a
  flip can never fall inside that flip's batch window, so a retrofitted
  verdict comment is structurally ineffective; grandfathering cannot be
  upgraded after the fact.
- **Mitigation rule satisfied**: every PASS done flip on or after
  2026-11-15 carries mark-2 (all observed instances above); no
  unmarked post-cutoff done exists in the observed chains.

**Recorded finding (observation, not a defect — no change implemented):**
mark-1 — the annotated done event ([DSN §2 C-A2]; the observed
2026-11-14 shape) — exists in history exactly once in the observed store:
A-001's `done (engine review pass: product PASS, lint clean)` by `review`
at 2026-11-14T18:02:20.790Z. The **current verb surface cannot emit
mark-1**: `task_update` [VERBS :130] is the only `done` path and routes to
[STORE] `update()` (:362), which has neither an annotation nor a `by`
parameter — so every current done flip is a bare `updated:status`, and
mark-2 is the only mark today's machinery can produce. This matches the
design's own framing: mark-1 is recorded as an *observed* shape, the
identities and window are observed calibrations carried as data
([DSN §9]), and the mitigation rule requires *at least one* of the two
marks — met by standing mark-2 practice. Implementing an
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
  observed chain (e.g. T-095 08:41:35.566Z → 08:48:28.203Z; T-111
  08:28:57.364Z → 08:59:15.863Z) — the marks are read together with that
  ordering, and every mark-2 above falls between the flip and the verdict
  or immediately before the done.
- **No worker/ticker `done` anywhere**: every observed `done` follows the
  review verdict comment (T-031, T-095, T-113, T-111 — the done event
  directly trails the mark-2 comment in each history). Enforcement layers
  observed in the machinery: the brief's NEVER-set-done rule ([SKILL]
  fulfillment; every spawn prompt), the done-time artifact guard
  ([STORE :478-490] — a definition-stage ticket cannot reach `done`
  without a recorded artifact), and the review pass as the only done
  applier in practice. Honest boundary note: the store's `update` verb
  itself accepts `status: done` from any caller that reaches it; the
  M2/M3 → `done` denial of [ARCH §Dependency rules] is held by the three
  layers above (brief, guard, review discipline), which is the running
  machinery's conformance shape for FR-017/FR-021's write-surface
  limitation. No change: widening the store with a caller check would
  re-decide engine-owned substrate ([DSN §1 pass-through, AD-001]).

**Decidability invariant walked end-to-end** (W-3, read from M1 alone) on
T-095: claim (bare transition; identity in `assignee`) → artifact
(stamped, 08:41:12.186Z) → fulfillment comment (08:41:24.241Z) →
in-review (bare, 08:41:35.566Z) → verdict comment
(`ticker-v:review`, 08:48:28.134Z, within the batch) → done (bare,
08:48:28.203Z, post-cutoff) → **`engine-set`**. D-1 and D-3 both decided
with no stamped transition actor anywhere and no worker/ticker `done` in
the chain — the flow's whole point ([DSN §2 C-A3]).

### Grandfathering (DS-001 / W-4) — VERIFIED READ-TIME-ONLY, no write path exists

- **No write path in code**: a repo-wide search for grandfathering or
  back-fill logic across all code surfaces (Java, PowerShell, TypeScript,
  JavaScript, JSON) returns **zero** matches — the classification exists
  only as the read-time decision procedure ([DSN §5 W-3/W-4];
  SYS §Interfaces 1).
- **The store cannot execute a grandfathering write**: history events are
  only ever appended inside transactions; no verb mutates or deletes a
  past event; and the comment verb's `now()` stamp ([STORE :838]) makes a
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
   (§ C-A2 finding) — recorded as calibration-consistent observation; the
   settled basis excludes adding a verb, and mark-2 standing practice
   satisfies the mitigation rule.
2. **Late fulfillment comments exist in live history** (T-031/T-111/T-113)
   — classified non-blocking by the engine verdicts (adjacency holds,
   D-1 decidable); the codified canonical order prevents recurrence;
   append-only history keeps the past as written.
3. **The worker-done denial is layered, not a store-side caller check**
   (§ C-A3 honest boundary note) — brief + done-time guard + review
   discipline hold it in the running machinery; recorded, not re-decided.
4. **Lane hygiene:** exactly two files changed (the delta map itself and
   [SKILL]). Not touched: the sibling whole-implementation ticket T-127's
   lane (in flight), `docs/acceptance-run-design.md` ([WHOLE] — read-only
   neighbour), any store file, any engine code (no contract required an
   engine change — C-A1's substrate conforms by API shape). No concrete
   provider or model ids anywhere (D-004).

## 5. Acceptance criteria mapping (T-134)

- **AC-1** — *every contract C-A1..C-A3 holds in the running machinery:
  implemented or verified as already-conformant, with a recorded delta
  map*: §2 — C-A1 verified conformant (substrate by API shape + live
  histories), C-A2 verified conformant (five first-hand timed mark-2
  instances, identities and window matching the §9 calibration; the
  mark-1 emission finding recorded), C-A3 verified with one implemented
  change (§3), grandfathering verified read-time-only with no write path.
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
rather than re-litigate. The W-3 decision procedure ([DSN §5]) run
against any cited chain is the integration-level check.
