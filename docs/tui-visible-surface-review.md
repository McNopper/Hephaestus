# TUI visible-surface review: what the opencode TUI shows the user, and where each item stands in the Eclipse chat

## About this document

- **Kind:** `doc` / requirements review — artifact of ticket `T-148` (stage `requirements`).
- **Read by:** the PM/tickers (division into child tickets) and the designers/implementers of the
  Eclipse chat; **written by:** the `T-148` fulfillment run (owner requirement 2026-11-16 governs).
- **Related:** `docs/chat-parity-requirements.md` (capability parity — this document covers the
  *visible status surface* those two inventories skip: what the user can SEE at a glance),
  `docs/opencode-v2-adoption.md` (route/pin context), `ROADMAP.md` *Standing rules* ("TUI parity is
  the floor, not the goal").
- **Scope guard:** the owner's clarification (2026-11-16) fixes the mapping: **the chat view IS the
  Eclipse counterpart of the opencode TUI/GUI** — its visible status surface belongs in the chat;
  the harness's extras (Board, Session Details, Server view, Fleet) come from the tabs and views and
  are NOT re-implemented here. Capability doctrine (not UI simulation) still applies to everything
  outside this document's status-surface scope.

## Method

- **TUI truth:** shallow clone of `github.com/anomalyco/opencode` (`dev`, 11,492 files, 2026-11-16);
  citations are `packages/...:line` into that tree (`[TUI]`).
- **Wire truth:** live probes against the local service (2.3.0 pin) on 2026-11-16, including a
  45-second raw SSE capture of `/api/event` (933 blocks) — citations `[PROBE]`.
- **Eclipse state:** repository code, `file:line` / `[LANE]` (`docs/TESTING.md`) / `[ADOPT]`
  (`docs/opencode-v2-adoption.md`, line).

## Inventory — the TUI's visible status surface

What the user of the TUI actually SEES, per surface. "Eclipse state" is the chat view's status
today (tabs/views excluded per the scope guard — their **wording** audit against this inventory
lives in §4b, added on the owner's 2026-11-16 follow-up "the Eclipse tabs and views should be
aligned with this functionality and wording", tracked as T-150).

### 1. Prompt footer — the status line under the composer (the headline gap)

`[TUI] packages/tui/src/feature-plugins/prompt/footer.tsx:37-51,71-108` renders, left to right,
joined by ` · `: **live subagent/shell counts**, the **goal chip**, then **context usage and cost**:

| Footer element | TUI source | Exact rendering | Eclipse chat state |
|---|---|---|---|
| Context window usage | `footer.tsx:41-48` → `util/session.ts:46-66` | `formatContextUsage(tokens, percent)` → e.g. `68.9K (7%)`; percent omitted when the model has no `limit.context` | **Landed (T-149)** — the chat status strip shows e.g. `517.2K (49%)`; live intercept: `ctx=517.2K (49%)` in the deployed panel |
| Session cost | `footer.tsx:46-49` | `Intl.NumberFormat` USD, shown only when `cost > 0` → `$9.96` | **Landed (T-149)** — strip cost segment, live intercept `cost=$9.96`; the Session Details tab's raw stats dialog remains (§4b) |
| Goal chip | `footer.tsx:95-102` → `feature-plugins/prompt/goal.tsx:50-91` | `◎ goal` (muted) / `◎ goal paused` (warning) / `◎ goal met` (success); click opens the goal dialog | **Landed (T-149)** — strip chip with all three labels unit-proven; live intercept: `goal=goal` ⇄ `goal=goal paused` on pause/resume; the dialog open/close visual rides the owner's manual test. T-141's *store sprint goal* placeholder (`Goal: …`, `ChatView.java:1386`) stays a separate harness extra — two goals by design (D1), see §4b |
| Subagent/shell counts | `footer.tsx:23-36,73-94` | `N subagents · M shells`, click opens the child/shell picker | **Absent in-chat** (subagents/shells live one hop away: Session Details / Fleet — tabs) |
| Shortcut hints | `footer.tsx:111-132` | `ctrl+p commands`, `shift+tab agents` | **Host tier** — Eclipse command palette + keybindings (Inventory B "Command palette / keybinds") |

### 2. Goal chip dialog

`[TUI] feature-plugins/prompt/goal.tsx:94-171`: click on the chip opens a dialog showing the
**objective text**, a status line (`◎ Active` / `Paused` / `Met` + the reason), and actions
**pause / resume / clear**, each issued as `session.command({name: "goal", text})`
(`goal.tsx:109-117`). The goal state itself comes from the plugin RPC, fetched on session change
and kept current from its `changed` events (`goal.tsx:12-48`); a server without the plugin answers
an error and the session simply shows no goal (`goal.tsx:20`).

### 3. Context usage semantics (what the number MEANS)

`[TUI] packages/client/src/solid/usage.ts:8-22` + `packages/tui/src/util/session.ts:46-66`:

- the **latest assistant message that carries tokens**, **after the last completed compaction**
  and **before the revert boundary** (`session.revert.messageID`);
- `tokens = input + output + reasoning + cache.read + cache.write` (all five, `session.ts:53-54`);
- `percent = Math.round(tokens / model.limit.context * 100)`, model matched by the message's
  `providerID`+`id` (`session.ts:56-60`);
- formatted by `Locale.number` (`util/locale.ts:28-37`): `≥999950 → "X.XM"`, `≥1000 → "X.XK"`,
  else plain — the same format the chat already ports for compaction usage (`ChatSessionController.java:866-877`).

A completed compaction therefore HIDES the footer usage until the next assistant step carries
tokens again (`routes/session/index.tsx:2171` comment: "the resulting context size only shows on
the next assistant step").

### 4. Sidebar surfaces → tabs/views (NOT chat gaps, recorded for completeness)

| TUI sidebar element | Source | Eclipse mapping |
|---|---|---|
| Context block: `Context` / `1,234 tokens` / `57% used` / `$9.96 spent` | `[TUI] feature-plugins/sidebar/context.tsx:20-41` | **Session Details → Stats** (tab; already exists, one hop away) |
| Location footer `path:branch` | `[TUI] feature-plugins/sidebar/footer.tsx:87-111` | Server view / working-set displays (tab) |
| Getting-started / provider onboarding | `[TUI] sidebar/footer.tsx:9-75` | Providers view (tab) |

### 4b. Tabs & views wording alignment (owner follow-up 2026-11-16 — T-150)

The scope guard kept §1-§7 chat-only; the owner's follow-up adds the missing axis: *the Eclipse
tabs and views should be aligned with this functionality **and wording***. Every mapped element,
with the TUI's **verbatim** wording against what the tab/view actually shows (repo citations; the
in-chat rows are §1's, listed where the same data now exists in both places).

| TUI element (verbatim wording) | Source | Eclipse tabs/views today | Verdict / action |
|---|---|---|---|
| Sidebar Context block: **`Context`** / `` `1,234 tokens` `` / `` `57% used` `` / `` `$9.96 spent` `` | `[TUI] feature-plugins/sidebar/context.tsx:21-41` | Session Details now renders the labeled block under the header (`SessionDetailsController.contextBlock` over the shared `ContextUsage` seam: assembled context via `getSessionContext` + `/model` limits + session cost); the raw *"Session stats..."* key-values dialog stays as a fallback action | **Landed (T-151)** - intercept-carried on the `session-details` channel as `context=<tokens> / <percent> / <spent>` |
| Context window per model (model options) | `[TUI] component/model-options.tsx:49` | Providers view column **`Context`** (`ProvidersView.java:140`) — same word, same meaning | ✓ aligned (the chat model *combo* detail stays G5) |
| Session cost / tokens | `footer.tsx:46-49`, sidebar above | Background view columns **`Cost`** / **`Tokens`** (`BackgroundView.java:83-84`) | ✓ aligned wording |
| Footer context + cost **in the transcript** | `footer.tsx:41-49` | the chat status strip (T-149): intercept `ctx=517.2K (49%) cost=$9.96` | ✓ landed (T-149) — but the Session Details tab still lacks the Context block above (the one-hop sidebar equivalent stays raw) |
| Goal chips `goal` / `goal paused` / `goal met` | `[TUI] goal.tsx:50-91` | two surfaces coexist **by design (D1)**: the strip chip = opencode *session* `/goal` (T-149; intercept `goal=goal` ⇄ `goal=goal paused`); the composer placeholder **`Goal: <text>`** = the store *sprint* goal (T-141, `ChatView.java:1386`) | ✓ aligned-by-design; **wording rule**: the chip never prefixes "Goal:" (it carries `◎` + chip words only), the placeholder always prefixes `Goal:` — the two may never share a label shape |
| `N subagents · M shells` (footer, click = picker) | `[TUI] footer.tsx:23-36,73-94` | Session Details sections **`Subagents`** / **`Shell tasks`** (`SessionDetailsView`, U-041) + the Fleet tree | ~ **wording decision (recorded)**: keep the harness-explicit `Shell tasks` in tabs (it is a Fleet/tree noun, clearer for Eclipse users); the future in-chat counts row (G3) MUST use the TUI wording `N subagents · M shells` verbatim |
| `/status` dialog: MCP server list with status colors | `[TUI] component/dialog-status.tsx:6-50` | Server view + *MCP servers…* dialog (tab) | ~ host-tier (§6); no line-by-line label audit — deferred behind the Server view's own parity, not a wording defect today |
| `usage.show`: budgets, spent/left per model | `[TUI] component/usage.tsx` | **absent** | ✗ → G4 derived ticket (route live since 2.1.0) |
| Model options: price, modalities, context window, reasoning | `[TUI] component/model-options.tsx:49` | Providers column ✓; chat combo names-only | ~ → G5 derived ticket |

Derived fix legs (basis T-150): the **Context block** — **landed (T-151)**; **G4 usage-limits**
(**T-152**) and **G5 model detail** (**T-153**) remain registered and pending. The two-goal
distinction and the `Shell tasks` wording rule above are documentation decisions — no code change
needed to satisfy them.

### 5. Per-message rows

| Row | TUI source | Eclipse chat state |
|---|---|---|
| **Assistant footer**: `Build · <model> · 12.3s · 45.2 tok/s · interrupted` | `[TUI] routes/session/index.tsx:2017-2064` | **Partial** — the chat's meta line shows the model label only (`ChatSessionController.java:801` `modelLabel()`); agent name and duration are missing; `tok/s` is config-gated upstream (`session.tps`) |
| **Compaction divider row** | `[TUI] routes/session/index.tsx:2168-2236` | **Landed (T-147)** — divider + full summary + usage suffix, live-streamed, reload-proof |
| **Notice rows for synthetic/system messages**: icon (`◎` when `metadata.source == "goal"`, else `◈`) + `description`, muted; synthetic rows **without** a description are dropped | `[TUI] routes/session/index.tsx:1954,2113-2145`, `rows.ts:392` | **Landed (T-149)** — `ChatEntry.Notice` keeps the payload, the injected objective prompt never becomes a part; history + live render the muted row, blank descriptions dropped; live intercept: `synthetic row rendered: Goal: Review functionality…` |
| Turn-usage token tables (new/cached/total per step) | `[TUI] routes/session/rows.ts:57,350-378` | **TUI-optional** — behind `config.debug.turn_tokens`, not part of the default floor |
| Steered message pending echo | `[ADOPT L310]` | Recorded as Partial in Inventory B (FR-CP-11) — unchanged by this review |

### 6. Dialogs

| Dialog | TUI source | Eclipse mapping |
|---|---|---|
| **Status** (`/status`, `<leader>s`): MCP server list with per-server status colors | `[TUI] component/dialog-status.tsx:6-50` | **Server view** (tab) — MCP management exists; host tier |
| **Usage limits** (`usage.show`): budgets, spent/left per model | `[TUI] component/usage.tsx`, `util/usage-limits.ts` | **Absent** — route exists since 2.1.0 `GET /api/experimental/usage/limits` `[ADOPT L294]`; route-gated candidate (G4) |
| **Model options**: price, input modalities, **context window**, reasoning support | `[TUI] component/model-options.tsx:49` | **Partial** — the chat's model combo shows names only (`ChatView.java:422-450`); candidate (G5) |
| **Goal dialog** (see §2) | `[TUI] goal.tsx:102-171` | **Landed (T-149)** — in-page dialog (objective/status/reason + whitelisted pause/resume/clear over the fire-and-forget verb), bridge-check-executed; the open-dialog visual rides the owner's manual test |

### 7. Mini mode footer

`[TUI] packages/tui/src/mini/footer.view.tsx:214,486-489` — compact `57% ctx` variant of the same
data. **N/A**: Eclipse has no mini mode; the strip of §1 is the full-fat equivalent.

## Wire contracts (all probed live 2026-11-16)

Every row was exercised against the local 2.3.0 service — this is the adopt-or-block evidence for
T-149 (no unprobed call):

| Contract | Shape | Evidence |
|---|---|---|
| Goal read | `POST /api/rpc/opencode.goal/get` body `{"input":{"sessionID":…}}` → `{"output": Goal.State\|null}` | `[PROBE]` returned this session's ACTIVE goal: `{objective:"…", status:"active", rounds:0, maxRounds:50, boot:"…"}`; error ⇒ treat as no goal (TUI does the same) |
| Goal live events | event `rpc.opencode.goal.changed` payload `{sessionID, goal}` on `/api/event` | `[PROBE]` SSE capture: exactly 2 frames during set→pause — `{status:"active"…}` then `{status:"paused", reason:"Paused by the user"…}`; our stream subscribes unfiltered (`OpencodeEventStream.java:135-141` — no type query), so the events already reach every listener |
| Goal actions | `POST /api/session/{sid}/command` `{"name":"goal","text":"pause\|resume\|clear"}` → **204**, no body | `[PROBE]` set→active, pause→paused+reason, clear→`output:null` — **must NOT ride `runCommand`**, whose `awaitCommandReply` polls for an assistant reply up to `PROMPT_TIMEOUT` = 60 min (`HttpOpencodeClient.java:1354-1374`, `ClientTuning.java:27-28`) for a reply that never comes; a fire-and-forget verb is required |
| Goal persistence shape | goal-set creates `{"type":"synthetic","metadata":{"source":"goal","goalID":…},"text":"<full prompt>","description":"Goal: <objective>"}` | `[PROBE]` scratch session: 2 synthetic rows for 2 sets; TUI renders `description` only (`index.tsx:2121`) and drops description-less synthetic rows (`rows.ts:392`) |
| Live cost/tokens | event `session.usage.updated` `{sessionID, cost, tokens{input,output,reasoning,cache{read,write}}}` | `[PROBE]` 8 frames/45 s; `cost` is the session-cumulative figure the TUI footer shows (probe: `9.962796800000003`) — **note: the `tokens` block is session-cumulative, NOT context-window**; context must come from the last assistant message (§3) |
| Session fetch | `GET /api/session/{sid}` → `{data: Session}` incl. `cost`, `tokens`, `model`, `revert?` | `[PROBE]` 200 with full record; the Java client has NO single-session getter today (list only) → add `getSession` |
| Model limit | `GET /api/model` entries carry `limit.context` | `[PROBE]` the active model: `limit:{context:1048576, output:131072}`; `Model.Limit` already parsed (`Model.java:151`) |
| Message list | `GET /session/{sid}/message` → `{data:[…], cursor}` **newest-first, 50-row page** | `[PROBE]`; the client already normalizes to chronological (`HttpOpencodeClient.java:810-820`) and `historyRows` consumes that order — context math must run on the same normalized list |
| Revert boundary | `session.revert.messageID` (optional) `[TUI schema: packages/schema/src/session-revert.ts]` | The Java `Session` record does not parse `revert` yet (`Session.java:12-22`); while a revert is pending the chat knows `serverHasReverted` (`ChatSessionController.java:727`) — hide usage then (honest degradation) or parse the field (design decides, see G1 note) |

## Gaps → tickets

| ID | Gap | Size | Ticket |
|---|---|---|---|
| **G1** | **Status strip in the chat** (bottom of the page, directly above the host input row — the chat's composer boundary): context usage `68.9K (7%)`, session cost `$9.96`, goal chip `◎ …` with the goal dialog (objective/status/reason + pause/resume/clear via the fire-and-forget verb), live from `rpc.opencode.goal.changed` + `session.usage.updated`, computed per §3 semantics — **plus** synthetic goal messages as TUI notice rows instead of full-text bubbles | M | **T-149 — landed (805a254), in-review; owner visual test pending on the dialog** |
| **G2** | Assistant footer parity: agent name + duration on the message meta line (model label already there) | S | candidate child |
| **G3** | Footer live counts: `N subagents · M shells` for the bound session's family | M | candidate child (data: `getSessions`/shell list) |
| **G4** | Usage-limits surface (`usage.show` parity) — probe-first against `GET /api/experimental/usage/limits` (live since 2.1.0 `[ADOPT L294]`) | M | candidate child (route-gated) |
| **G5** | Model combo detail (price / context window / reasoning badge) — `model-options.tsx` parity | S | candidate child |
| G6 | `tok/s` per message, turn-usage tables, mini footer | — | **out of floor**: TUI ships them behind config/debug gates; revisit only if upstream defaults change |

**Explicitly not gaps here** (recorded to keep division honest): sidebar Context block, location
footer, onboarding, `/status` MCP dialog → tabs/views per §4/§6 (the owner's scope split); prompt
shortcuts → host tier; compaction row → landed T-147; `share`/`unshare` notices and the `[status]`
channel → the residual parity sweep parked before this requirement.

## Decisions and reconciliations

- **D1 (owner, 2026-11-16):** the chat view is the TUI/GUI-equivalent surface; harness extras come
  from tabs/views. The context window, current goal and cost belong **in the chat**; sidebar-shaped
  detail may live one hop away in tabs (§4).
- **R1:** `docs/chat-parity-requirements.md` "Explicitly not gaps" said `/goal` "never a chat-side
  feature… the chat exposes nothing for it by design". That verdict governs the goal **loop**
  (intake/acceptance stays the V-model's, U-105) and is NOT revoked — but the goal's **visible
  state** (chip, dialog with the TUI's own pause/resume/clear, notice row) is chat-side display
  parity the owner now explicitly wants. The parity doc's `/goal` line and the FR-CP-06 row are
  amended in the same flow.
- **R2:** FR-CP-06 ("context-usage + per-turn cost in the chat") is this review's G1; its child
  ticket is T-149.
- **R3:** T-141's store-sprint-goal placeholder stays — it is a harness extra (Board goal), a
  different datum than the opencode session goal; the strip's goal chip is the TUI's datum. Both
  may coexist without ambiguity (placeholder only shows while the input is empty; the chip is
  persistent).

## Acceptance (of this document, ticket T-148)

- **A1:** every §1/§2/§3/§5 element carries a `[TUI] packages/...:line` citation into the
  2026-11-16 clone; every Eclipse-state cell carries a repo citation or `[LANE]`/`[ADOPT]` ref.
- **A2:** every wire-contract row carries `[PROBE]` evidence from 2026-11-16 (no unprobed call
  reaches T-149 — standing rule `ROADMAP.md` L153-161).
- **A3:** the gap table maps each gap to a ticket id; G1's child exists and is basis-linked.
- **A4:** `docs/chat-parity-requirements.md` reconciled per R1/R2 (same flow, coherence rule).
- **A5:** recorded via `task_add_artifact` before `in-review`.
