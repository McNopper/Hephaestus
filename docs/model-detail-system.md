# System: the model-detail surface — the chat selector's `price · context window · Reasoning` line (T-159)

## About this document

- **Kind:** `doc` / system-stage artifact of ticket **T-159** (stage `system`, basis T-153,
  claim `ticker-v:T-159`): the model-detail surface modeled AS A SYSTEM — its parts, its
  catalog interface (which consumes **no new network verb**), and its wording/badge/empty
  rules — over the product T-153 landed for gap G5.
- **Read by:** the downstream architecture/design stages of the T-153 division, the reviewer /
  engine review pass verifying T-159's acceptance criteria, anyone extending the chat selector
  row or the shared number formatters, and the maintainer tracking G5.
- **Related:** the carried basis artifacts — `git ea7c7b1` (T-152 + T-153, landed 2026-11-17)
  with `eclipse/bundles/com.opencode.ide.client/src/main/java/com/opencode/ide/client/model/ModelDetail.java`,
  `eclipse/bundles/com.opencode.ide.chat/src/main/java/com/opencode/ide/chat/views/ChatView.java`
  (`modelDetailLabel` + `renderModelDetail` + the `model-detail` intercept),
  `eclipse/bundles/com.opencode.ide.chat/src/main/java/com/opencode/ide/chat/internal/ChatSelectorState.java`
  (catalog map + `detail(modelId)`), test
  `eclipse/bundles/com.opencode.ide.client.tests/src/main/java/com/opencode/ide/client/ModelDetailTest.java`;
  the governing wording record `docs/tui-visible-surface-review.md` **§4b** (T-150 artifact,
  verbatim TUI model-options wording); `docs/feature-matrix.md` (the live-accepted G5 row);
  the paired test-system criteria linked on the ticket.

## 1. Overview

The model-detail surface is one line of text beside the chat's model combo: the selected
model's **price**, its **context window**, and a **`Reasoning`** badge when the model exposes
effort variants — the facts the opencode TUI's model options show (`component/model-options.tsx:49`,
read into `docs/tui-visible-surface-review.md` §4b). The Eclipse surface is not a second
implementation of those facts: it is a **pure formatting pass over catalog records the chat
already fetched**, wording through the same helpers the status strip and the Session Details
Context block use. Five parts, one read-only interface, six rules — no state of its own, no
network call of its own, and no fact it was not given.

## 2. System view

```text
 GET /api/provider (+ /api/model)         OpencodeClient.getProviders / getModels
      |                                   (EXISTING verbs - detail adds none)
      v
 ChatSessionController.loadSelectorData ---> ChatView.fillSelectors
      |                                          |
      v                                          v
 ChatSelectorState.load(...)              selectors.model()  (the picked entry)
      |  catalog: Map<"<provider>/<model>", Model>
      |  cleared + refilled on EVERY load          |
      +---- detail(modelId) ---------------> ModelDetail.line(record)
                (null when unknown)                |
                                    +---------------+----------------+
                                    |               |                |
                                    v               v                v
                              priceLine       contextLine      reasoningLine
                              ContextUsage.   ContextUsage.    "Reasoning" iff
                              usdLabel        tokensLabel      variantNames() != {}
                              ($2.00 /        (1,048,576       else ""
                               $10.00          tokens)
                               per 1M)
                                    |
                                    v  facts joined by " · " (U+00B7)
                     ChatView.renderModelDetail()
                       +-- modelDetailLabel.setText(line)   ("" while unknown/loading)
                       +-- tooltip ("...nothing known yet" when empty)
                       +-- intercept "model-detail model=<id> detail=<line>"
```

**Invariants (hold on every route):**

1. **Formatting is pure.** `ModelDetail` and `ContextUsage` never fetch, never cache, never
   guess — they turn a `Model` record already in memory into words.
2. **No new network verb.** The surface consumes the pre-existing `getProviders` /
   `getModels` responses only (§3); nothing in this system opens a route.
3. **No fabricated fact.** A field the catalog does not carry empties its segment; a record
   the catalog does not know empties the whole line. An honest empty beats a plausible number.
4. **One wording source.** Every dollar and every token count renders through the shared
   `ContextUsage` helpers, so the selector, the status strip and the Context block can never
   disagree about a number (T-153 acceptance criterion 2).
5. **No live identifiers in committed content.** Examples carry `<provider>/<model>`
   placeholders only (D-004 gate: `releng/check-no-provider-ids.ps1`).

## 3. Major parts

- **P-1 — The catalog record (`Model`, `ProviderList`).** The shape the surface reads:
  `Model.limit().context()` (context window in tokens), `Model.cost()` (price tiers in USD per
  million tokens; the base row via `Model.baseCost()`, `Cost.input()` / `Cost.output()`),
  `Model.variantNames()` (reasoning-effort variants: `none, low, …` or none at all). Fed by
  `OpencodeClient.getProviders(directory)` (`GET /api/provider` and `/api/model`,
  `OpencodeClient.java:62-67`) through `ChatSessionController.loadSelectorData`
  (`ChatSessionController.java:1379-1396`). The flat `getModels(directory)` catalog
  (`GET /api/model`, `OpencodeClient.java:210-217`, T-149's verb) carries the same `Model`
  type and the same fields — the two routes are one catalog seen twice, not two sources.
- **P-2 — The selector's catalog holder (`ChatSelectorState`).** Keeps the `Model` records
  behind the combo entries in a `LinkedHashMap` keyed `<provider>/<model>` (`ChatSelectorState.java:19`),
  cleared and refilled on every `load(...)` (`:59-76`), and exposes `detail(modelId)`
  (`:193-195`): the catalog record for a combo entry, `null` for the server-default
  placeholder `""`, an id outside the catalog, or a model that vanished — a reload drops
  stale records, they never survive the catalog that carried them.
- **P-3 — The formatter (`ModelDetail`).** Static, SWT-free, unit-tested
  (`ModelDetailTest`, 6/6): `isFree` (every price tier zero), `priceLine` (`$2.00 / $10.00
  per 1M`, `Free`, or `""`), `contextLine` (`1,048,576 tokens` or `""`), `reasoningLine`
  (`Reasoning` or `""`), and `line(...)` — the facts joined by the TUI's ` · ` separator in
  the TUI's order: price first, then context, then badge. A `null`/unknown model renders `""`,
  never a guess (`ModelDetail.java:82-91`).
- **P-4 — The selector-row render (`ChatView`).** `modelDetailLabel` sits in the selector
  row's five columns — agent | model | variant | **model-detail** | sessions — directly after
  the variant combo it describes (`ChatView.java:474-517`). `renderModelDetail()`
  (`ChatView.java:1055-1066`) resolves the line from `selectors.detail(selectors.model())`,
  sets the label (empty tooltip text becomes "…(nothing known yet)"), re-lays out the row,
  and records the desktop-safe acceptance channel:
  `intercept "model-detail model=<id> detail=<line>"` (truncated at 300 chars). It runs on
  model pick (`:500`) and after every catalog load (`renderModelSelection`, `:1044`).
- **P-5 — The shared wording seam (`ContextUsage`).** `usdLabel(double)` → `$9.96` /
  `$0.00` (`Locale.ROOT`, two decimals) and `tokensLabel(long)` → `1,234 tokens`
  (`ContextUsage.java:118-138`) — the ONE formatter for every surface: T-149's status strip,
  T-151's Context block, and this line. Its sibling `costLabel` (hides `null` and `<= 0`,
  the footer's "hide zero spend" behavior) is NOT part of the detail line — the detail shows
  the catalog's price, not session spend.

## 4. Catalog interface — no new network verb

| Verb (existing) | Wire | What this system reads |
|---|---|---|
| `OpencodeClient.getProviders(directory)` | `GET /api/provider` (+ `/api/model`) | the `ProviderList` whose per-provider `models` feed `ChatSelectorState.load` → P-2 |
| `OpencodeClient.getModels(directory)` | `GET /api/model` | the flat `Model` catalog with `limit.context` (T-149's verb; same record type) |

Consumed fields, and only these:

| Field | Turns into | Empty case |
|---|---|---|
| `limit.context` | context segment via `ContextUsage.tokensLabel` | `<= 0` / absent → segment omitted |
| `cost[]` (base row: `input`, `output` per 1M) | price segment via `ContextUsage.usdLabel`, or `Free` when every tier is zero | no base row → segment omitted; `null`/empty `cost` is not free |
| `variants` (`variantNames()`) | `Reasoning` badge | no variants → no badge |

Input modalities, capabilities, status and pricing tiers above a context threshold are
deliberately **out of the read set** — §4b keeps "input modalities stay out of the G5 floor",
and the line is the TUI floor's price · context · badge, nothing more. A refused or absent
catalog load reaches this system as `null`/empty records and renders as the honest empty —
the same discipline the usage surface applies to a refused route (T-152).

## 5. Rules (the contract)

- **R-1 — Order and separator.** `price · context · Reasoning`, joined by ` · `
  (space, U+00B7, space), price first — the TUI's order (`component/model-options.tsx:49`,
  recorded verbatim in §4b: *"Model options: price, modalities, context window, reasoning"*).
- **R-2 — The badge only when variants exist.** `Reasoning` renders iff `variantNames()` is
  non-empty — §4b's rule: *"the `Reasoning` badge follows the TUI rule (only when effort
  variants exist)"*. A model without effort variants gets no badge, ever.
- **R-3 — Honest empty while the catalog loads.** Before the catalog arrives, for the
  server-default placeholder entry, for an id the catalog does not carry, and for a model
  that vanished on reload: the line (or its unknown segment) is `""` — a label with nothing
  in it, tooltip "…(nothing known yet)", intercept `detail=` empty. Never a guessed or
  remembered number from a previous catalog.
- **R-4 — Price honesty.** Every price tier zero → `Free`; a base price row →
  `$2.00 / $10.00 per 1M` (input / output per million, through `usdLabel`); no price row at
  all → the price segment vanishes while the remaining facts still render (an unpriced model
  still shows its context window).
- **R-5 — Wording identical across surfaces.** Dollars only via `ContextUsage.usdLabel`,
  tokens only via `ContextUsage.tokensLabel`. The selector, the T-149 strip and the T-151
  Context block share the seam, so no two surfaces can disagree about a number — this line
  formats through the SAME helpers as the strip (the test class says so in its own words:
  `ModelDetailTest.theLineFormatsThroughTheSharedStripHelpers`).
- **R-6 — Placeholders, never live ids.** Every example, sample intercept and fixture in
  this document and in any future extension uses `<provider>/<model>`-style placeholders —
  provider/model choices are local settings, never committed content (D-004).

### 5.1 Oracle alignment (ModelDetailTest ↔ rules)

No behavior documented here may contradict the test; this table is the check:

| `ModelDetailTest` oracle | System rule |
|---|---|
| `aPaidModelReadsPriceContextAndReasoningInTheTuiOrder` — `$2.00 / $10.00 per 1M · 1,048,576 tokens · Reasoning` | R-1, R-4 |
| `aFreeModelSaysFreeAndKeepsItsFacts` — `Free · 262,144 tokens`, `isFree` true | R-4 |
| `aModelWithoutAPriceStillShowsItsContextWindow` — price `""`, line `128,000 tokens` | R-3, R-4 |
| `anUnknownModelRendersNoDetailRatherThanAGuess` — all four helpers `""` on `null` | R-3 |
| `theBadgeOnlyAppearsWhenTheModelExposesEffortVariants` — `Reasoning` vs `""` | R-2 |
| `theLineFormatsThroughTheSharedStripHelpers` — `usdLabel(9.9627968)` = `$9.96`, `usdLabel(0)` = `$0.00`, `tokensLabel(1234)` = `1,234 tokens`, `costLabel(0.0)` = `null` | R-5 |

`ChatSelectorStateTest` (added in the same commit) pins P-2's half: `detail` is the catalog
record behind a combo entry, `null` outside the catalog and for the `""` placeholder, and a
reload drops records of models that left the catalog — all R-3.

## 6. End-to-end data flows

- **F-1 — Catalog load fills the detail.** `loadSelectorData` fetches agents + `getProviders`
  → `fillSelectors` → `ChatSelectorState.load` clears and refills the catalog map →
  `renderModelSelection` → `renderModelDetail` → label + intercept. The detail appears
  exactly when the catalog does; before that the label is honestly empty (R-3).
- **F-2 — A model pick re-renders.** The model combo's guard handler commits the selection,
  re-fills variants, then calls `renderModelDetail` — the facts follow the picked model in
  the same UI action.
- **F-3 — A reload keeps detail honest.** A second `load(...)` clears the map first: records
  of models that left the catalog disappear, and the next `renderModelDetail` degrades to
  `""` rather than showing a fact the catalog no longer carries (R-3, test-pinned).

## 7. Technology choices

| Concern | Choice | Why |
|---|---|---|
| Detail source | existing `getProviders` / `getModels` responses, records already in memory | no new network verb; the detail is a view over data every other surface already consumes |
| Formatter home | `ModelDetail`, static functions in the client bundle's model package | SWT-free and unit-testable (`ModelDetailTest` 6/6); the chat view only renders strings |
| Number wording | `ContextUsage.usdLabel` / `tokensLabel` (the T-149/T-151 seam) | one formatter, three surfaces, disagreement structurally impossible |
| Render | SWT `Label` in the selector row, re-laid out on change | the native combo cannot render per-row details; a beside-the-row label is the desktop floor of the TUI's model options |
| Acceptance channel | `ViewIntercept.recordTruncated("model-detail", …, 300)` | desktop-safe evidence: no clicks, no screenshots — the live run intercepted `detail=Free · 1,048,576 tokens` and the honest empty during load |
| Tests | `ModelDetailTest` (formatting rules) + `ChatSelectorStateTest` (catalog holder) | every rule in §5 is oracle-backed; §5.1 maps each oracle to its rule |

## 8. Key trade-offs / open questions

- **The label is the selected model's line, not a per-option dropdown render.** The TUI shows
  facts inside its model-options dropdown; the Eclipse combo is a native SWT widget with no
  per-row rendering, so the floor landed as a label beside the row (§4b records exactly that
  shape). A custom-rendered dropdown would be a new ticket, not an extension of this system.
- **Only the base price tier is shown.** `cost[]` may carry tiers above a context threshold;
  the line shows the base row (the TUI floor). Tiered-price display is an open extension —
  it would grow R-1's order and needs its own wording decision against §4b.
- **Input modalities stay out (recorded, not a defect).** §4b explicitly keeps modalities
  outside the G5 floor; if that floor moves, P-3 gains one segment and R-1 one position —
  the interface (§4) already exposes the field through the same records.
- **The intercept truncates at 300 characters.** Plenty for this line (its longest realistic
  form is well under 100); a future longer detail would silently truncate — noted for whoever
  extends R-1.

## 9. Traceability (T-159 acceptance criteria)

- **AC-1 — system artifact exists, About header, parts/catalog/rules, basis + §4b cited:**
  this document — header above (Kind / Read by / Related); §3 parts resolve every landed
  element; §4 the catalog interface; §5 the wording/badge/empty rules; the basis artifacts
  (`git ea7c7b1`, `ModelDetail.java`, `ChatView.java`, `ChatSelectorState.java`,
  `ModelDetailTest.java`) and `docs/tui-visible-surface-review.md` §4b are cited in the
  header and at each rule.
- **AC-2 — every landed element resolves into the system model; no contradiction with the
  oracles; no new network verb:** P-3 = `ModelDetail`, P-4 = `renderModelDetail` +
  `modelDetailLabel`, P-2 = `ChatSelectorState.detail`, P-5 = the `ContextUsage` seam (§3);
  §5.1 maps every `ModelDetailTest` oracle to its rule (no documented behavior differs from
  an assertion); §4 shows the read set is the pre-existing verbs only — invariant 2.
- **AC-3 — artifact recorded before `in-review`; lint-clean:** `task_add_artifact`
  (kind `doc`, `docs/model-detail-system.md`) is written before the `in-review` flip; the
  file passes `pwsh releng\lint.ps1` (markdownlint-cli2 over the repo, `markdown: clean`),
  evidence in the completion report on the ticket.

## 10. Lane and non-goals

T-159's lane is this one new file plus the ticket store record. Not touched: any Java source,
tests, `AGENTS.md`, `docs/tui-visible-surface-review.md`, any skill. Non-goals: new network
verbs, per-option dropdown rendering, modalities, tiered pricing, and any wording change —
the system documents what T-153 landed; changing behavior is a new ticket with its own
basis.
