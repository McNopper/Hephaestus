# Architecture: the Session Details Context block — the landed context surface over `getSessionContext` (T-163)

## About this document

- **Kind:** `doc` / architecture-stage artifact of ticket **T-163** (stage `architecture`, role
  `architect`, basis **T-151**, claim `ticker-v:T-163`): the internal structure of the LANDED
  Session Details context surface — components, per-component contracts, and the dependency rules
  that keep them together — derived 1:1 from the carried basis artifacts (git `e296778`) and the
  governing wording table. This is a **brownfield architecture**: it records what the tree
  actually contains and fixes the rules future changes must keep; it invents no parallel structure.
- **Read by:** the downstream design stage of the T-151 division (this document is its basis),
  the paired test-architecture ticket **T-164**, the engine review pass, and anyone touching the
  Context block, the shared number seam, or the `getSessionContext` verb.
- **Related:** `docs/tui-visible-surface-review.md` **§4b** (the governing verbatim-TUI-wording
  table, T-150 artifact — §1 quotes it; nothing here contradicts it), and its §3 (context-usage
  semantics: what the number means); the basis ticket **T-151** (git `e296778`, the
  implementation described here); `docs/usage-limits-system.md` (T-157 — the limits surface and
  its own wording port), `docs/model-detail-system.md` (T-159 — the model-detail surface reusing
  this seam's helpers), `docs/opencode-v2-adoption.md` (the rows where `getSessionContext` moved
  from "landed, no consumer" to consumed); and the landed test oracles `ContextUsageTest`,
  `SessionDetailsControllerTest` (context cases) and
  `HttpOpencodeClientComponentTest.sessionContextParsesTheContextItemsAsChronologicalMessages`.

## 1. Scope and governing definition

The surface modeled here is the one §4b records as **Landed (T-151)**, quoted verbatim:

| TUI element (verbatim wording) | Source | Eclipse side (as landed) |
|---|---|---|
| Sidebar Context block: `Context` / `1,234 tokens` / `57% used` / `$9.96 spent` | `[TUI] feature-plugins/sidebar/context.tsx:21-41` | Session Details renders the labeled block under the header (`SessionDetailsController.contextBlock` over the shared `ContextUsage` seam: assembled context via `getSessionContext` + `/model` limits + session cost); the raw "Session stats..." key-values dialog stays as a fallback action |

The **numbers** behind that wording are fixed by §3 of the same review: the last assistant step
that carries usage, at or after the newest completed compaction, with all five token fields
summed; `percent = round(tokens / limit.context)` of the matched model. The block's acceptance
evidence rides the `session-details` intercept channel as `context=<tokens> / <percent> / <spent>`
(T-151 live acceptance: `context=833,393 tokens | 79% used | $9.96 spent`).

Explicitly **outside** this model (recorded so no reviewer mistakes them for gaps):

- the chat status strip and the model-detail line are *sibling consumers* of the same seam —
  listed below only where the dependency rules bind them;
- the workspace-limits budget columns (`docs/usage-limits-system.md`), the Providers `Context`
  column's short `128k`-style text, `SessionTranscript.header`'s aggregate line, and the
  subagent-row labels each carry their own recorded wording and are not context-block surfaces;
- turn-usage token tables are TUI-optional (`config.debug.turn_tokens`, §5 of the review) and
  have no place in this surface.

## 2. Chosen pattern

Evaluated against what the surface is (read-only telemetry → words under a view header):

- **Layered alone** describes the bundle direction but not the surface: it says nothing about
  who computes the wording versus who paints it.
- **MVC alone** describes the surface but not the seam: the shared formatters are consumed by
  three surfaces in two bundles, which is a layering question, not a controller question.
- **Event-driven** would be wrong — the surface is refreshed on load, and telemetry failure must
  degrade silently rather than emit; **microservices/SOA/CQRS/P2P** are unjustifiable at one
  process boundary.

**Chosen: an MVC slice for the surface, sitting on a shared pure formatting seam one layer
below it.**

- **Model (client bundle):** `ContextUsage` — pure static numbers + wording over the already
  parsed records; no fetch, no state.
- **Controller (ui bundle):** `SessionDetailsController` — composes verb output, catalog limits
  and session cost into one immutable snapshot record.
- **View (ui bundle):** `SessionDetailsView` — paints the snapshot and toggles visibility;
  computes nothing.

**Cost:** pull-based refresh (the block updates when the snapshot reloads, no push), and every
renderer must handle `null` (hidden). Both are accepted: the TUI itself hides rather than
staleness-shows, and refresh already drives the view.

## 3. Components

| Component | Location | Responsibility | Contract / tolerance |
|---|---|---|---|
| `ContextUsage` (shared seam) | `eclipse/bundles/com.opencode.ide.client/src/main/java/com/opencode/ide/client/model/ContextUsage.java` | The TUI's context numbers (`compute`) plus both wording dialects and the shared token/USD helpers | Pure static; `null` means hidden; `Locale.ROOT` everywhere |
| `OpencodeClient.getSessionContext` (client verb) | `.../client/OpencodeClient.java` (interface default) + `.../client/internal/HttpOpencodeClient.java` | The session's assembled context as chronological `ChatEntry` | Default throws `UnsupportedOperationException` (fakes stay minimal); HTTP impl throws `OpencodeException` on refusal/absent route/malformed body |
| `SessionDetailsController.contextBlock` + `contextLimits` | `.../ui/session/SessionDetailsController.java` | The composition point: verb + model-catalog limits + session cost → `ContextBlock` | Any exception → `null` (block hides); catalog failure → tokens/spent without a percent |
| `SessionDetails.ContextBlock` | same file (snapshot record) | Pre-worded, immutable view model: `tokens` / `percent` / `spent` lines | `null` block hides the section; a `null` line hides just that line |
| `SessionDetailsView` header + context labels | `.../ui/views/SessionDetailsView.java` | Renders the one-line header, then the `Context` block directly beneath it | Renders only; whole label hidden when nothing to show; layout re-run only on visibility change |
| Session stats dialog (fallback) | same file (`Session stats...` action) → `SessionDetailsController.stats()` → `sessionStats` verb | Raw service key-values in a text dialog | Fallback action only, never the primary surface; empty map on failure |
| `session-details` intercept | `SessionDetailsController.load()` → `ViewIntercept.record` | Evidence channel carrying `context=…` per refresh | Acceptance evidence, not a user surface |
| Sibling consumers of the seam | `.../chat/internal/ChatSessionController.java` (footer dialect), `.../client/model/ModelDetail.java` (token/USD helpers) | The chat status strip and the model-detail line render the same vocabulary | Must word through the seam (D-2), never format inline |

### 3.1 The shared seam — `ContextUsage`

`compute(List<ChatEntry>, Map<String, Long>)` is the port of the TUI's
`util/session.ts contextUsage` (probed against the TUI source 2026-11-16): scan from the end for
the newest **completed** compaction and only consider assistant steps after it; take the last
assistant step that carries tokens; sum `input + output + reasoning + cache.read + cache.write`;
`percent = round(sum / limit * 100)` with the limit looked up as
`"<providerId>/<modelId>" -> limit.context`. Degradation, all of it TUI-faithful: no entries, no
qualifying assistant step, or a zero-sum newest usage → `null` (hidden, with **no fall-through**
to older steps); an unknown model → `Usage(tokens, null)` — tokens without a percent.

The two dialects over those numbers (this document's block uses the sidebar one):

- **footer** (chat status strip / TUI prompt footer): `596.1K (7%)` via `footerLabel` +
  `compactNumber` (≥ 1000 → `X.XK`, ≥ 999950 → `X.XM`, below → plain number);
- **sidebar** (this block): `tokensLine` → `1,234 tokens`, `percentLine` → `57% used`,
  `spentLine` → `$9.96 spent` — grouped plain numbers, one per line, each returning `null` to
  hide itself (`costLabel` hides `null`, `0` and negative spend).

Shared wording helpers on top of the dialects: `tokensLabel(long)`, `usdLabel(double)`,
`costLabel(Double)` — the vocabulary other surfaces reuse (D-2).

### 3.2 The verb — `getSessionContext`

Contract on the interface (live probe 2026-11-16, opencode 2.3.0): `GET /api/session/{id}/context`
answers `{data:[contextItems]}` where every item is message-shaped — the same union `/message`
serves, with the kept compaction marker typically first. The HTTP implementation unwraps the v2
list envelope, **normalizes chronologically** (stable sort by creation time, same as
`getMessages` — the wire may be newest-first) and returns an immutable copy. Failures throw:
this verb deliberately does **not** use the `getListOrEmptyOn404` auxiliary tolerance — an
absent or refused route surfaces as `OpencodeException` and the consumer decides (D-3). The
interface default throws `UnsupportedOperationException` so test fakes stay minimal.

### 3.3 The composition point — `SessionDetailsController.contextBlock`

Inputs: `client.getSessionContext(sessionId)`, `contextLimits(client)` (a
`"<provider>/<model>" -> limit.context` map from `getModels(null)`, **empty map on any failure**),
and the session's own `cost()`. Show condition, straight from the TUI
(`context.tsx:21`, `state() || cost() > 0`): the block renders when there is usage **or** the
session cost is positive; cost alone yields a block whose token and percent lines are `null`;
nothing at all yields a `null` block. The whole method is wrapped in a catch-all → `null`, so
telemetry failure hides the block and never breaks the rest of the snapshot. The result is
carried as the `context` component of the immutable `SessionDetails` snapshot record
(`ContextBlock(String tokens, String percent, String spent)` — already-worded strings, not
numbers).

### 3.4 The render — header label and block label

`SessionDetailsView` creates two labels under the view header: `headerLabel` (title • id • model
• cost • tokens via `SessionTranscript.header`, plus the error note when present) and, directly
beneath it, `contextLabel` — created hidden. `applyContext(block)` shows iff the block is
non-null and carries at least a tokens or a spent line; the text is the literal `Context` header
followed by whichever lines the snapshot carries; `setVisible` + a parent layout run only when
visibility actually changes. The view never formats: it prints snapshot strings and toggles
visibility.

### 3.5 The stats-dialog fallback

The `Session stats...` action opens a raw key-values text dialog
(`ServiceText.keyValues(controller.stats())` over the `sessionStats` verb,
`GET /api/experimental/session/stats`, empty map on failure). Per T-151's acceptance criteria and
§4b, the labeled block is the **primary** surface; the dialog stays reachable as the raw fallback
escape hatch. It shares no formatting with the block (D-4).

### 3.6 The evidence channel

Every successful `load()` records `session=… title=… rows=… … context=<tokens> | <percent> |
<spent>` (or `context=none`) on the `session-details` intercept channel — the desktop-safe
acceptance evidence used by T-151's live test. It observes the snapshot; it never feeds it.

## 4. Dependency rules

- **D-1 — The seam sits below the views; views never word numbers.** `ContextUsage` lives in the
  client model package and imports only model records and the JDK. The ui and chat bundles may
  depend on the seam; the seam never depends on ui/chat (no view imports, no SWT, no fetching).
  The view layer consumes the pre-worded snapshot only.
- **D-2 — One helper set per dialect: surfaces REUSE their dialect's wording helpers instead of
  formatting inline.** The chat status strip reuses the footer helpers (`compactNumber`,
  `footerLabel`, `costLabel`); this block reuses the sidebar helpers (`tokensLine`, `percentLine`,
  `spentLine`); the **model-detail surface reuses the seam's shared helpers** — `tokensLabel` for
  the model's context **limit** (`limit.context` → `1,048,576 tokens`) and `usdLabel` for prices —
  so the selector, the strip and this block can never disagree about a number (contracted as
  `model-detail-system.md` R-5, tested by `ModelDetailTest`). The **usage-limits surface reuses
  its own recorded helper port** — `UsageLimits.formatDollars` / `leftLabel`, the TUI's budget
  dialect from `util/usage-limits.ts` (`$100`, `<$0.01`, `$100 left`) — a different TUI source and
  therefore a different dialect; the rule is *reuse within a dialect*, and the two USD dialects may
  never borrow each other's formatter (two-decimal seam vs whole-dollar/sliver budget rules).
  Any new surface words a TUI-verbatim string through exactly one helper, never locally.
- **D-3 — The verb is honest; tolerance lives at the composition point.** `getSessionContext`
  throws on a refused, absent or malformed route (no silent empties), and the interface default
  throws so fakes stay minimal. The single composition point (`contextBlock`) catches everything
  and hides; a failed catalog degrades to no percent (tokens and spend still shown, like the TUI
  with an unknown model); an empty context list degrades to hidden through `compute` returning
  `null`. No layer fabricates a number, and telemetry failure never fails the snapshot (oracle:
  `contextBlockHidesWhenTheContextCallFails` keeps the rest of the snapshot intact).
- **D-4 — Block primary, stats dialog fallback only.** The raw key-values dialog may not be
  re-promoted to primary and does not share formatting with the block; the block does not read the
  stats route. §4b records this split; removing either side would contradict the landed criteria.
- **D-5 — Snapshot records are immutable and pre-worded.** `ContextBlock` carries `String` lines
  (already in the TUI's wording) and is produced by the controller; the view renders and toggles
  visibility only. Wording changes therefore happen in the seam (or the composition point's line
  selection), never in the view.

## 5. Key quality attributes

- **QA-001 — Verbatim wording parity with the TUI (§4b).** All rendering goes through
  `Locale.ROOT`-pinned helpers, so the wording is machine-independent and byte-equal to the TUI's
  documented examples; the oracles in §7 assert the exact strings.
- **QA-002 — Failure containment (telemetry must never break the view).** Three stacked layers
  enforce it: the verb throws honestly, the composition point catches-and-hides, the seam returns
  `null` for anything uncomputable. A refusal costs the user a hidden block, never an error
  dialog or a stale number.
- **QA-003 — No drift between surfaces that share the number.** One `compute`, two dialects:
  the strip and this block derive from the same seam, so their *wording and rounding* cannot
  diverge. Their *inputs* differ by design (the strip sums the message list, the block the
  assembled context from `getSessionContext`); the live acceptance recorded a ~0.2% basis
  difference between the two sources with both surfaces showing the same 79% — an honest input
  difference, not a formatter drift, and not a defect.

## 6. Architecture decisions

- **AD-001 — The seam lives in the client model package, below ui/chat.** Reason: it words
  records the client already owns and is consumed from two bundles. Trade-off: the client bundle
  carries a formatting concern.
- **AD-002 — The snapshot carries pre-worded strings, not numbers.** Reason: wording decided
  once, view stays a thin shell, tests can assert final text. Trade-off: the view cannot re-wrap
  lines for a different layout — accepted, there is one TUI-parity layout.
- **AD-003 — Throw at the wire, hide at the composition point.** Reason: honest errors plus an
  unbreakable view. Trade-off: any new consumer must implement its own degrade policy rather than
  inheriting one.
- **AD-004 — Percent is optional (`Long`, `null` = unknown).** Reason: mirrors the TUI's
  unknown-model omission instead of guessing a denominator. Trade-off: every renderer handles a
  missing percent (the block hides just that line).
- **AD-005 — The stats dialog is retained as fallback, per T-151 AC1 and §4b.** Reason: a one-hop
  raw view of whatever the service reports. Trade-off: two stats surfaces exist — by design, and
  documented here so neither is "the duplicate".
- **AD-006 — Live acceptance rides the `session-details` intercept, not UI assertions.**
  Reason: a desktop-safe evidence channel per refresh. Trade-off: the intercept is a channel,
  not a contract — the string shape is observation, not API.

## 7. Test oracles this document is written against

Read before any change; this document asserts nothing they contradict:

- `ContextUsageTest` (9/9) — five-field sum + `60%` percent of the model limit; unknown model →
  tokens with no percent; newest completed compaction ends the window; null/empty/zero-sum →
  `null` with **no fall-through**; footer `600.0K (60%)` and compact thresholds (`999`, `1.2K`,
  `1.0M`); sidebar verbatim `600,000 tokens` / `60% used` / `$9.96 spent`; per-line hiding
  (including `cost <= 0`); USD formatting `$9.96` / `$1,234.50`.
- `SessionDetailsControllerTest` context cases (5) — TUI wording from the assembled context
  (`600,000 tokens | 60% used | $9.96 spent`); hidden with no usage and no cost (the TUI's
  `state() || cost() > 0`); cost-only block (`$0.50 spent`, token/percent lines `null`); a failing
  context call hides the block while the rest of the snapshot still loads; a failing model
  catalog keeps tokens and spent but drops the percent.
- `HttpOpencodeClientComponentTest.sessionContextParsesTheContextItemsAsChronologicalMessages`
  — path `/api/session/ses_123/context`, both context items parse, chronological normalization
  (`user` before `assistant`), usage and the model triple ride the item (they drive the limit
  lookup).
- `ModelDetailTest` — the model-detail line formats through the same `tokensLabel` / `usdLabel`
  helpers (D-2's reuse oracle).

## 8. Traceability to the T-163 acceptance criteria

- **AC1 — architecture artifact with About header, components and dependency rules derived 1:1
  from the carried basis artifacts, citing §4b:** this document; §1 quotes the §4b row verbatim;
  §3 derives each component from git `e296778`'s landed files; §4 carries the dependency rules.
- **AC2 — every landed element resolves into the model; no contradiction with the landed test
  oracles; no invented parallel structure:** §3.1–§3.6 cover `ContextUsage`,
  `SessionDetails.ContextBlock` + the header/context labels, the `getSessionContext` verb and the
  stats-dialog fallback (plus the intercept and the sibling consumers); §7 maps the oracles;
  §1's "explicitly outside" list keeps the scope identical to the landed work.
- **AC3 — artifact recorded on the ticket before `in-review`, lint-clean (`releng/lint.ps1`):**
  recorded as a `doc` artifact on T-163; the lint command's output is quoted in the fulfillment
  comment on the ticket.

## 9. Open questions

None blocking. Recorded for the next stage: whether a future refresh policy should push context
updates (the seam is pure, so only the composition point and the view would change — no seam or
verb change), belongs to design, not to this structure.
