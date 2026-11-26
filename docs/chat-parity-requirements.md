# Chat parity requirements: the Eclipse chat vs the opencode TUI

## About this document

- **Kind:** `doc` / requirements artifact of ticket `T-034` (stage
  `requirements`, epic `U-112`) — the parity gap list as testable
  requirements, each gap a candidate child ticket for the ticker to divide.
- **Read by:** the PM/tickers (division), designers and implementers of the
  Eclipse chat; **written by:** the `T-034` fulfillment run
  (owner requirement 2026-11-14 governs).
- **Related:** `ROADMAP.md` *Standing rules* ("TUI parity is the floor, not
  the goal"), `docs/TESTING.md` §2 (the shipped chat lanes),
  `docs/opencode-v2-adoption.md` (adoption matrix + the older parity-floor
  table this document supersedes in detail), `eclipse/components/chat-web/README.md`
  (the renderer bridge contract), `eclipse/ARCHITECTURE.md` (bundle layout).
- **Scope guard:** capability parity, **not** UI simulation — the harness
  adopts what a session can *do*; it never rebuilds the TUI's interface
  inside Eclipse (`docs/opencode-v2-adoption.md`, "Alignment means capability
  parity"). Requirements below name capabilities and observable behavior,
  never TUI visuals.

## Goal

The chat inside Eclipse shall have the same features as the opencode TUI with
similar behavior; the desktop opencode client is a second reference. This
document inventories both sides, records where the desktop client's take
differs usefully, and specifies the gap as testable requirements so the
ticker can divide each gap into a child ticket.

## Users

- **Primary:** the maintainer working through the Eclipse IDE — prompts,
  steers, approvals, diffs and session management happen in the chat view
  without switching to a terminal.
- **Secondary:** fleet operators watching/steering sessions from the same
  surfaces; contributors verifying parity lanes from `docs/TESTING.md`.

## User stories

- US-001: As an Eclipse user, I want every composer capability the TUI has
  (mentions, shell lines, attachments, history), so that I never drop to a
  terminal to drive a session.
- US-002: As an Eclipse user, I want steered/queued turns to behave as
  predictably as in the TUI, so that typing ahead during a long run never
  surprises me.
- US-003: As an Eclipse user, I want the transcript to show me what a turn
  did (tools, changed files, cost), so that I can judge and steer the work
  in place.

## Reference implementations and the floor rule

| Reference | Role | Citation |
|---|---|---|
| opencode **TUI** (v2 docs; repo pin 2.4.2) | the parity floor | <https://opencode.ai/docs/cli/tui/>, <https://opencode.ai/docs/cli/keybinds/>, <https://opencode.ai/docs/cli/commands/>, <https://opencode.ai/docs/permissions/>; V1 floor commands archived at <https://opencode.ai/docs/tui/> |
| opencode **desktop client** | second reference where its behavior differs usefully | `docs/opencode-v2-adoption.md` release-note rows (cited inline below) — no local desktop source; repo/docs only per ticket scope |
| **Standing rule** | "TUI parity is the floor, not the goal": hold "same or more features than the official TUI" and use the harness where it is genuinely better | `ROADMAP.md` L168-169 |
| Doctrine | capability parity, not UI simulation; host tier (Eclipse editor/themes/keybindings/LSP) strictly reused | `docs/opencode-v2-adoption.md` L55-60, L352, L359 |

Feature citations below use `[TUI]` for the v2 docs pages, `[V1]` for the
V1 TUI page (the floor the parity table was written against), `[ADOPT]` for
`docs/opencode-v2-adoption.md` (with line), `[LANE]` for `docs/TESTING.md`
(with row), and `file:line` for repository code.

## Inventory A — the opencode TUI chat feature set

Grouped by family; this is the completeness bar for Inventory B.

**A1. Composer input.** `@` fuzzy file references; configured reference
roots appear in `@` autocomplete (`@alias`, `@alias/`) [TUI Context, V1
File references]; `@path#20-45` line/range suffix [TUI Context]. `!` shell
mode at prompt start — command runs in the session working directory,
output stays in the session [TUI Shell]. Newlines via Shift+Enter /
Ctrl+Enter / Alt+Enter / Ctrl+J [TUI, keybinds `input.newline`]. Prompt
history recall (Up/Down) [keybinds `prompt.history.previous/next`]; prompt
stash/pop/list [keybinds Prompt commands]. External editor for the current
prompt (`VISUAL`/`EDITOR`) [TUI Editor]. Image attachments with a viewer
[keybinds `prompt.images.view`; CLI `run --file` shows the attachment
contract]. Skill selector [keybinds `prompt.skills`].

**A2. Steering and queueing.** While a reply streams, Enter **steers** the
active run; Ctrl+X then Enter (leader+Enter) **queues** the prompt for
later [TUI intro; keybinds `prompt.queue`]. Queued prompts are manageable
in place [keybinds `session.queued_prompts`, `queued_prompt.undo`,
`queued_prompt.delete`]; steered messages show as pending immediately in
the TUI/Desktop UX [ADOPT L310].

**A3. Commands.** `/` lists slash commands with type-to-filter [TUI
Commands]. Built-in set includes `/new`, `/sessions`, `/models`,
`/agents`, `/undo`, `/redo`, `/editor`, `/compact`, `/btw`, `/init`,
`/help`, `/thinking`, `/highlights` [TUI; V1 adds `/connect`, `/export`,
`/themes`, `/update`, `/share`-era commands]. Ctrl+P opens the command
palette of every action in the current view [TUI; keybinds
`command.palette.show`]. `/btw <question>` runs a **side question** on the
session's context whose answer opens in a dialog and never enters the
conversation [TUI Side questions; keybinds `session.aside`].

**A4. Model / agent / variant selection.** Model dialog with provider
list, favorites and usage limits [keybinds `model.list`, `model.dialog.*`,
`usage.show`]; cycle recently used models (F2 / Shift+F2) and favorite
models [TUI Models; keybinds]; agent list + Shift+Tab cycling [TUI;
keybinds `agent.*`]; model **variant** cycling (reasoning effort) Ctrl+T
[keybinds `variant.cycle`].

**A5. Sessions.** New / list / resume [TUI Sessions]; recent sessions and
projects together (Ctrl+O) [TUI]; **tabs** by default with next/previous,
close, reopen closed, unread markers, history back/forward, quick slots
1-10 [TUI; keybinds Sessions]; rename (Ctrl+R), delete, fork from a
message, move session to a worktree, pin in the session list, change
working directory [keybinds Sessions + dialog.move_session]; session
timeline view [keybinds `session.timeline`]; background a blocking session
(Ctrl+B) and compact the session [keybinds `session.background`,
`session.compact`]; child/subagent navigation (picker, next/previous
child, parent) [keybinds `session.child.*`, `session.parent`];
interrupt with Escape [keybinds `session.interrupt`]; undo/redo the last
exchange — the reverted prompt returns to the composer for editing [TUI
History].

**A6. Diffs and tool-call rendering.** A dedicated diff viewer with review
scopes — All / Committed / Uncommitted / Last turn / Base branch — split
or unified view, file tree, hunk navigation, mark-file-reviewed with
auto-advance, fullscreen; `/diff` side panel on by default [keybinds Diff
viewer; ADOPT L306 #55780]. Desktop adds a **per-turn changed-files
timeline** [ADOPT L306 #55064]. Tool calls render with state and
grouping (related tool-call grouping toggle) [keybinds
`session.toggle.exploration_grouping`].

**A7. Permissions.** Config-driven allow/deny/ask rules; when a check
resolves to `ask` the client replies **Allow once / Allow always (saves
the tool's proposed patterns) / Reject (optionally with feedback)**;
saved approvals are durable, project-scoped, and never override a deny
[Permissions → Approvals]. The permission prompt can go fullscreen
[keybinds `permission.prompt.fullscreen`]; MCP tool input is exposed as
`metadata.input` on permission requests [ADOPT L306 #51062].

**A8. Questions and forms.** Agent questions surface as answerable prompts
(permission action `question`); the TUI renders form-style prompts
[Permissions → Actions; V1 attention `question` event].

**A9. Attention.** Desktop notifications and sounds for question,
permission, session error, done and subagent-done events; per-event sound
overrides and volume [V1 Configure → Attention; notifications fire when
the terminal is unfocused].

**A10. Context, cost, status.** Usage limits view [keybinds `usage.show`,
`model.dialog.usage`]; status view [keybinds `opencode.status`]; sessions
report tokens and cost (`session.generate` returns them since 2.2.7)
[ADOPT L265]; free-limit recovery UX (reset time, restored prompt, direct
model-switch actions) [ADOPT L298].

**A11. Terminal.** Toggleable terminal pane and terminal selection
[keybinds `terminal.*`, `composer.terminal.*`].

**A12. Appearance/help.** Themes, light/dark mode [keybinds `theme.*`];
which-key panel [keybinds `which-key.*`]; `/help`; session export to
editor and transcript copy [keybinds `session.export`, `session.copy`];
verbosity cycle and exploration grouping [keybinds `session.verbosity.cycle`].

## Inventory B — the Eclipse chat's current surface

What the chat (view + renderer) does **today**, per the shipped lanes and
the code. "Host tier" = deliberately served by Eclipse, not the chat.

| Family | State in the Eclipse chat | Citation |
|---|---|---|
| Composer input: `@` mentions | **Landed:** `@` fuzzy file dropdown merging `@alias` reference roots above the files; picks stay plain text; Esc dismiss/re-arm per token | [LANE] floor table [ADOPT L350]; `ChatView.java:1207-1297`, `FileReferenceToken.java`, chat-web README bridge `__setFileQuery/__setFileCompletions` |
| Composer input: line/range suffix | **Landed (T-065 / CP-10):** a pick carries the typed `#20-45` suffix verbatim (re-scan of the whole word, caret after path+suffix); the autocomplete query strips it so an annotated reference still matches | `FileReferenceToken.java` (`suffixOf`, `queryOf`, `replaceToken`), ChatSessionControllerTest `fileReferenceKeepsItsLineSuffixVerbatimThroughAPick` |
| Composer input: `!` shell mode | **Landed (T-058 / CP-02):** `!`-prefixed composer line runs in the session and renders an output strip in the transcript; a `!`-send is text-only (chips dropped); blank/`!` alone is a notice | [ADOPT L83]; `ChatSessionController.inlineShellCommand` + `runInlineShell`, ChatView send branch, chat.js `__appendShell` |
| Composer input: newline / submit | **Landed:** multi-line composer, ENTER sends, Shift+ENTER newline, `/` commands [tooltip `ChatView.java:581-584`] | `ChatView.java:581-594` |
| Composer input: prompt history recall | **Landed (T-063 / CP-08):** ArrowUp/ArrowDown walk the view's sent prompts (oldest stop, no wrap) and the first ArrowUp stashes the in-progress draft so ArrowDown restores it; recorded on the send path (built-ins and `!` runs excluded), capped at 100, view-scoped | `PromptHistory.java`, ChatView key branch + `recallPrompt`, PromptHistoryTest 6/6 |
| Composer input: attachments/images | **Landed (CP-01 / T-014-era):** `ChatRequest.files` (`{uri,name}` per attachment), the **Attach** button + chips row above the input, `clearAttachments` on send, and the transcript renders each sent prompt's attachment rows (name + kind) | `ChatRequest.java`, `ChatRequests.addFiles`, ChatView attach row, `ChatSessionController.attachmentViews`, chat.js `renderAttachments` |
| Steering / queue | **Landed, richer than TUI:** ENTER during a run **steers immediately** (U-092); Alt+ENTER parks in the inbox; queue row = numbered table with Edit / Remove / Fork (Fork is an Eclipse extra); Steer now / Deliver next / Cancel per item | [LANE] U-071 row; `ChatView.java:1351-1369` (steer), `:551-571` (queue actions), chat-web README `__setInboxItems/__javaInboxAction`; [ADOPT L84] |
| Built-in slash commands | **Landed (registry of 7):** `/help` `/init` `/thinking` `/undo` `/redo` `/share` `/unshare` — `/help` derives from the same registry (cannot drift); `/share`-`/unshare` show the honest not-implemented notice (upstream has since retired sharing entirely) | `ChatSessionController.java:58-74` (registry), `ChatView.java:1316-1344` (dispatch); [ADOPT L353-L354, L306 #55716] |
| Project slash commands | **Landed:** picker over `GET /command` (the project's `.opencode/command/`), prefix filter, exact-name resolve, `POST /session/:id/command` run | `CommandComposer.java` (whole), [ADOPT L93] |
| Command palette / keybinds | **Host tier:** Eclipse command palette + keybinding scheme; chat commands registered: open/new/abort/background with Ctrl+Alt+Shift+C/N/A/B; Ctrl+B in the composer backgrounds (U-064) | `plugin.xml:15-86`; `BackgroundKeys.java`; [LANE] U-064 row |
| Model / agent / variant selectors | **Landed in-view:** Agent combo, Model combo, Reasoning-effort (variant) combo — "as in opencode"; last model + last session persist across restarts with restore offer | `ChatView.java:422-450`, `ChatViewSettings.java` (U-039); variant row [ADOPT L96] |
| Session resolution on open (T-180) | **One ground of truth:** primary view resumes the *project's most recently active session* first (`ActiveSession.mostRecent` over the scoped `getSessions`), the stored `lastSessionId` only as fallback, fresh view last; secondary/"new chat" opens unaffected | `ChatView.restorePreviousSession`, `chat/internal/ActiveSession.java` (T-180) |
| Model favorites / recents cycling | **Landed (T-064 / CP-09):** the last 5 deliberately used models persist (`KEY_RECENT_MODELS`) and list most-recent-first in the combo (vanished recents ignored; the server-default `""` stays first); favorites still not built (the FR's optional MAY) | `ChatViewSettings.recentModels/recordModelUsed`, `ModelRecents.java`, `ChatSelectorState.load(…, recents)`, ModelRecentsTest 5/5 |
| Sessions: new / resume | **Landed:** New Session action; open with `sessionId` resumes; restore-last-session across workspace starts | `plugin.xml:16-40`, `ChatView.java:1020-1067` (`resume`), `ChatViewSettings.java` |
| Sessions: switcher / tabs / pin / delete | **Landed in-view (T-060 / CP-05):** the `Sessions…` button opens the recent-sessions dialog - switch rebinds the open chat in place (`controller.resume`), plus Pin/Unpin (host-side marker; no v2 pin verb) and Delete (unbinds the bound session); tabs still not in-chat (Eclipse tabs own that) | `session/SessionsDialog.java`, `ChatView.openSessionSwitcher`, `SessionPins.java`, SessionPinsTest 4/4 + ChatSelectorState's switcher cases |
| Sessions: rename / fork / move / compact / export / stats | **Landed one hop away** (Session Details): Rename, Fork (chat also has per-message ⑂ Fork here), Move/switch agent/model, Compact context, Export/Log/Stats | [ADOPT L81, L88-L90, L96-L98]; `ChatSessionController.java:1255` (fork); chat-web README `__javaForkAt` |
| Steering display | **Landed (T-066 / CP-11):** steered sends go out with `delivery=steer` (U-092) AND the steered text echoes into the transcript immediately (verified by `aSteeredMessageEchoesIntoTheTranscriptImmediately` with the delivery job held in flight); the stale "ENTER queues" help texts were corrected to ENTER steers / Alt+ENTER parks | `ChatSessionController.send` (immediate `appendUser`), ChatView `QUEUE_HELP` + composer tooltip, [ADOPT L310] |
| Streaming + reasoning | **Landed:** live block-level progressive markdown, thinking indicator, streamed reasoning in collapsible details, authoritative final render, `/thinking` toolbar toggle persisted | [LANE] T-010 row; chat-web README (bridge contract + rendering rules); [ADOPT L354-L355] |
| Tool-call rendering | **Partial:** compact state lines `tool: <name> — running…/completed ✓/error ✗`; no tool input/output expansion, no diff inside the transcript | chat.js `TOOL_LABELS` `:300-321`; chat-web README `__setAssistantText` tools field |
| Diffs / changed files | **Landed (T-059 / CP-04):** after each turn the chat shows THAT turn's changed files (message-scoped `session.diff`) and a chip opens the in-chat diff surface (patch as textContent); no git-CLI side read beyond the documented exceptions | [ADOPT L70, L86] (exceptions + reads); `ChatSessionController.surfaceTurnDiff`/`showDiff`, chat.js `__appendChangedFiles`/`__showDiff` |
| Permissions | **Landed:** in-chat ask row - Allow once / Always / Reject **with optional feedback** (matches the approval contract) plus the tool's `metadata.input` line (T-062 / CP-07); recovery of orphaned asks; saved-permission manager on the Server view with **list/delete + EDIT** (`PATCH /permission/saved/{id}` adopted) and always-replies verified against the saved list | `ChatView` ask row + `answerAsk`, `ChatPermissions*.java`, `HttpOpencodeClient.updateSavedPermission`; [ADOPT L87, L105, L302, L306 #51062] |
| Questions / forms | **Landed:** answerable form cards below the transcript (Submit/Cancel, union-schema field rendering), persisted until answered | [ADOPT L90, L355]; chat-web README `__setForms/__javaFormReply` |
| Attention | **Landed (default off):** jface notification popups + beep for permission asks, **questions (`form.created` - mapped by T-067 / CP-12 from the live event enum)**, session errors, completions; preference-controlled; per-event sound configuration not built (the FR's optional MAY) | [ADOPT L355]; `AttentionClassifier` (`case "form.created"`), `AttentionNotifications.java` |
| Context / cost / usage in-chat | **Landed:** the status strip carries context usage + session cost (T-149), Session Details renders the `Context` block (T-151), and each turn's own tokens/cost ride its meta line from the 2.2.7 actuals (`session.generate`, T-061) | `ChatSessionController.refreshStatus` + `metaWithUsage`, `ContextUsage`, `GenerateResult`, [ADOPT L265, L98] |
| Terminal | **Read-only, one hop away:** Session Details Terminal pane renders the PTY screen (service-side); no interactive stdin (route is WebSocket-only; no REST stdin) | [ADOPT L95] |
| Long-turn reliability | **Landed:** progress keeps a tool-heavy turn alive up to an hour; dead-session hand-off to the late-reply watcher | [LANE] B-024 row |
| Backgrounding | **Landed:** composer Ctrl+B / ⤵ Background button / global Ctrl+Alt+Shift+B; backgrounded sessions keep working | [LANE] U-064 row; `BackgroundKeys.java`; `plugin.xml:46-48,82-85` |
| Subagents / shells | **Landed one hop away:** Session Details → Subagents and Shell tasks; Fleet tree nesting; output tails in consoles | [LANE] U-041 row; [ADOPT L83] |
| Undo / redo | **Landed:** toolbar Undo/Redo + `/undo` `/redo` (git snapshot restore); two-phase revert (revert-to-here, undo/commit revert) in Session Details and per-message revert | `ChatView.java:713-731`; `ChatSessionController.java:1949`; [ADOPT L88] |
| Help / doc surface | **Landed (T-068 / CP-14):** `/help` lists the built-ins AND the capability map - every TUI command says where it lives (chat / Session Details / view / host tier / upstream-blocked / not-yet), coverage pinned to this document's Inventory A3 by CapabilityMapTest | `CapabilityMap.java`, `ChatSessionController.showHelp`, CapabilityMapTest 5/5 |
| Stale in-app help | **Fixed (T-066 / CP-11):** the composer tooltip and the queue tooltip now state the shipped semantics (ENTER steers, Alt+ENTER parks); repo-wide grep for the stale phrasing returns 0 hits | `ChatView` `QUEUE_HELP` + composer tooltip + `submitSelection` javadoc |

**Host-tier (no work by design):** `/editor` `/themes` keybinds and
palette → Eclipse editors/themes/keybindings [ADOPT L352]; `/connect` →
Providers view OAuth connect [ADOPT L101]; LSP → Eclipse LSP [ADOPT
L359]; `/update` → Eclipse update path. **Upstream-blocked:** session
todos (U-013) — no v2 endpoint; keep parked [ADOPT L256, L356].

## Desktop-client reference notes

Where the desktop client's take differs usefully (all from the adoption
doc's release-note scans; repo/docs only):

- **Per-turn changed-files timeline** (#55064, [ADOPT L306]): the desktop
  surfaces *what a turn changed* as a first-class timeline. This is the
  reference shape for gap FR-CP-04 — better in an IDE than a raw patch
  list, and it composes with our existing `session.diff` reads.
- **Steered messages shown as pending immediately** ([ADOPT L310]): after
  a steer, the user sees the steered text right away. Cheap trust win;
  folded into FR-CP-11's verification.
- **Provider-account management + remote-pairing QR** ([ADOPT L310]): the
  desktop treats accounts and device pairing as client surfaces. Our
  Providers view already covers accounts [ADOPT L101]; pairing stays an
  Evaluate row [ADOPT L342] — no chat gap.
- **Free-limit recovery UX** (#57466-71, [ADOPT L298]): reset-time +
  restored-prompt + model-switch actions on quota exhaustion — the
  TUI/Desktop face of the quota chains U-098 configures. Noted as input
  to FR-CP-06 (usage display) and U-098; not a separate chat requirement.
- **`/goal`** (#59437, [ADOPT L277/L279]): TUI/Desktop session-level
  self-review loop. Governing verdict already recorded: adopt-and-govern
  through the V-model (U-105 intake), never a chat-side feature — the
  chat exposes nothing for it by design. **Reconciled (owner requirement
  2026-11-16, `docs/tui-visible-surface-review.md` R1):** the verdict
  governs the goal *loop* (intake/acceptance stays the V-model's) and is
  NOT revoked — but the goal's *visible state* (footer chip, dialog with
  the TUI's pause/resume/clear, synthetic notice row) is chat-side
  display parity the owner now explicitly wants; landed as G1/T-149.
- **Image generation default-on** (2.2.2, [ADOPT L277/L279]): upstream
  client feature for image-capable providers; we generate no images —
  out of scope for chat parity (attachments in FR-CP-01 are a different
  capability: handing files *to* the session).

## Gap list — testable requirements (each gap = one candidate child ticket)

EARS syntax; "the chat" = the Eclipse chat view + its renderer. Each
requirement names its evidence and the candidate child ticket a ticker
can divide. Priorities: H = user-visible floor gap, M = useful parity, L =
polish. Route-gated items MUST probe the live contract first (standing
rule, `ROADMAP.md` L153-161: never adopt an unprobed call).

- **FR-CP-01 (H) — attachments.** WHEN the user attaches or pastes a file
  or image into the composer, THEN the chat SHALL send it with the prompt
  (the v2 `POST /prompt` body's `files` field, [ADOPT L306]) and the
  transcript SHALL show the attachment on the sent message. Evidence:
  `ChatRequest.java:20-27` (no files field), [TUI] `prompt.images.view`,
  CLI `run --file`. *Child: "Chat attachments: files/images on the
  composer (prompt files field)".* M/S.
- **FR-CP-02 (H) — inline shell.** WHEN the user submits a message
  starting with `!`, THEN the chat SHALL run the remainder as a session
  shell command (the adopted `runShell` verb) and the output SHALL be
  reachable from the chat without opening Session Details first (inline
  render or a chat-side shell strip — design decides). IF the command is
  permission-gated, THEN the existing ask row SHALL gate it. Evidence:
  [TUI] Shell; no composer handling today; [ADOPT L83]. *Child: "`!`
  inline shell mode in the chat composer".* M/S.
- **FR-CP-03 (M, route-gated) — side questions.** WHEN the user runs
  `/btw <question>`, THEN the chat SHALL answer from the session's
  context in a dialog without appending to the conversation, with the
  answer copyable — provided the live contract exposes a side-question
  verb; IF the probe finds none, THEN the gap SHALL be recorded
  upstream-blocked (like session todos) and the ticket parked with the
  probe evidence. Evidence: [TUI] Side questions; keybinds
  `session.aside`; absent in the chat bundle. *Child: "/btw side
  questions (probe-first)".* S.
- **FR-CP-04 (H) — turn diffs.** WHEN a turn completes edits, THEN the
  chat SHALL surface that turn's changed files in place, and WHEN the
  user opens one, THEN the chat SHALL show its diff (reuse
  `session.diff`; revision-content side reads stay git-CLI per the
  exceptions table [ADOPT L70]). Reference shapes: TUI diff scopes
  (Last turn / All / Committed / Uncommitted) and the Desktop per-turn
  changed-files timeline (#55064, [ADOPT L306]). Evidence: chat.js
  renders tool-state lines only; diffs live outside the chat today.
  *Child: "Per-turn changed-files + in-chat diff surface".* M/L.
- **FR-CP-05 (M) — in-view session management.** WHILE a chat view is
  open, WHEN the user invokes switch, THEN the chat SHALL offer the
  location's recent sessions and rebind in place (no new view per
  switch), and the same surface SHALL expose delete and pin for the
  bound session. Rename/fork/move/compact/export already exist one hop
  away (Session Details) and stay there. Evidence: `ChatView.java` binds
  one session (open/resume only); TUI tabs/list/pin/delete [TUI
  Sessions; keybinds]. *Child: "In-chat session switcher (list, switch,
  delete, pin)".* M/M.
- **FR-CP-06 (M) — usage and cost in place.** THE chat SHALL display the
  session's context-usage indicator, and WHEN the server reports a
  turn's tokens/cost (`session.generate` since 2.2.7, [ADOPT L265]),
  THEN the chat SHALL show them for the turn. Evidence: no cost/token
  surface in the chat bundle; Stats in Session Details [ADOPT L98];
  [TUI] `usage.show`. *Child: "Context-usage + per-turn cost in the
  chat".* S. **Spawned (2026-11-16):** G1 of
  `docs/tui-visible-surface-review.md` → ticket T-149 (status strip:
  context window + cost + current goal chip; wire contracts probed live
  that day).
- **FR-CP-07 (M) — permission depth.** WHEN a permission ask carries the
  tool's input (`metadata.input`, #51062 [ADOPT L306]), THEN the chat's
  ask row SHALL show that input so the decision is informed; and the
  saved-permission manager SHALL adopt the saved-permission EDIT route
  (2.1, [ADOPT L302]); and WHEN "Allow always" is chosen, THEN the saved
  pattern the server proposes SHALL be verifiable (probe + test).
  Evidence: `ChatView.java:478-494` (ask row without input detail);
  [ADOPT L105]. *Child: "Permission surface depth: tool input, saved
  rule edit, always-pattern verification".* S.
- **FR-CP-08 (L) — prompt history.** WHEN the user presses ArrowUp in an
  empty composer, THEN the chat SHALL recall the previous sent prompt
  (ArrowDown forward, edit before send). Stash/pop is OPTIONAL (desktop-
  first UX; skip unless trivial). Evidence: no recall handling in the
  chat bundle; [TUI] keybinds `prompt.history.*`. *Child: "Composer
  prompt-history recall".* S.
- **FR-CP-09 (L) — model recents/favorites.** THE chat SHALL keep a
  recent-models list (the last N used, most recent first, persisted) in
  the model combo's ordering, and MAY add favorite marking. Evidence:
  `ChatViewSettings.java:38-47` (single last model); [TUI] F2 cycling,
  favorites. *Child: "Model selector recents (+ optional favorites)".* S.
- **FR-CP-10 (L) — `@` line ranges.** WHEN an `@` reference in the
  composer carries a `#line` or `#start-end` suffix (typed or appended
  after a pick), THEN the chat SHALL preserve the suffix verbatim in the
  sent message (the server resolves ranges, as in the TUI). Evidence:
  `ChatView.java:1297` / `FileReferenceToken.java` replace bare paths
  only; [TUI] Context (`@src/auth.ts#20-45`). *Child: "@ file
  references: line/range suffix".* S.
- **FR-CP-11 (H, small) — steering truth-in-UI.** THE chat's visible help
  SHALL state the shipped delivery semantics (ENTER steers a running
  turn — U-092; Alt+ENTER parks in the inbox; Shift+ENTER newline), and
  WHEN a steer is delivered, THEN the steered text SHALL appear to the
  user immediately as sent/pending (desktop reference, [ADOPT L310]).
  Fixes the stale texts at `ChatView.java:122` and `:583-584`. *Child:
  "Steering UX pass: fix stale ENTER-queues help; steered-message
  echo".* S.
- **FR-CP-12 (L) — attention coverage.** WHEN a question form arrives
  while notifications are enabled, THEN the chat SHALL raise the
  question attention kind (popup kind currently unmapped — no form-ask
  SSE type [ADOPT L355]); and the preference page MAY expose per-event
  sound/volume configuration to match the TUI attention surface. IF the
  event stream grows a form-ask type, THEN the mapping SHALL use it.
  Evidence: `AttentionNotifications.java`; [V1] Attention events.
  *Child: "Attention: question-kind notifications (+ sound config)".* S.
- **FR-CP-13 (M, route-gated) — interactive terminal.** WHEN the user
  opens a terminal pane from the chat/Session Details, THEN the harness
  SHALL support typing into the session's PTY (stdin rides the
  WebSocket `GET /api/pty/{id}/connect`; the REST route is
  title/resize-only [ADOPT L95]) — probe the stream contract first and
  scope a minimal WebSocket client; IF the probe shows the stream
  unusable from the harness, THEN record the blocker on the ticket.
  Evidence: [ADOPT L95] (read-only today); [TUI] terminal pane.
  *Child: "Interactive PTY: stdin over the terminal stream".* M/L.
- **FR-CP-14 (L) — /help as a capability map.** THE `/help` registry
  SHALL, for every TUI command, state where its capability lives in the
  harness (in-chat, Session Details, Server/Providers view, host tier,
  upstream-blocked) so `/help` cannot drift from this document's
  inventories; in-composer aliases for `/compact` and `/export` are
  OPTIONAL (capabilities exist in Session Details [ADOPT L97-L98]).
  Evidence: registry lists only the 7 built-ins
  (`ChatSessionController.java:62-74`). *Child: "/help capability map
  (+ optional /compact //export aliases)".* S.

**Explicitly not gaps** (recorded to keep division honest): `/editor`
`/themes` palette/keybinds, `/connect`, `/update`, LSP — host tier by
doctrine [ADOPT L352, L359]; session todos — upstream-blocked (U-013)
[ADOPT L356]; `/goal` — the goal *loop* governed through the V-model,
never a chat feature [ADOPT L277]; its *visible state* (chip/dialog/
notice row) is chat display parity per `docs/tui-visible-surface-review.md`
R1 (2026-11-16), landed as T-149; `/share` — upstream retired sharing (#55716,
[ADOPT L306]); our honest notice stays; ACP — not applicable (Eclipse is
the native host) [ADOPT L357].

## Non-functional requirements

- NFR-UX-001: parity additions live in the existing chat surfaces
  (composer, transcript, ask/queue rows) — no new windows per feature;
  Session Details remains the "one hop away" home for heavy views.
- NFR-SEC-001: attachment and shell features pass through the server's
  permission rules unchanged (allow/deny/ask) — the chat adds no bypass.
- NFR-REL-001: every gap child lands with its paired verification lane
  (a `docs/TESTING.md` row or a unit/component test per the V pairing).
- NFR-MAINT-001: `/help` and in-app help text derive from registries
  (the `BUILT_IN_COMMANDS` pattern), never hardcoded strings that can
  go stale (the FR-CP-11 defect class).

## Constraints & assumptions

- C-001: the parity bar is the **v2 TUI at the repo's pin** (2.3.2 at
  writing); upstream ships daily, so each child re-checks the docs
  against the live contract at implementation time.
- C-002: doctrine constraints hold: capability parity, not UI
  simulation; keep-the-harness-thin adopt-or-retire (`ROADMAP.md`
  L162-167); host tier strictly reused.
- C-003: no concrete provider/model ids in any artifact (D-004).
- C-004: route-gated requirements (FR-CP-03, FR-CP-13) begin with a live
  probe; the probe result decides adopt vs upstream-blocked.
- C-005: the older parity-floor table in `docs/opencode-v2-adoption.md`
  (L346-359) predates U-092 and the current `BUILT_IN_COMMANDS`
  registry; where it disagrees with Inventory B above, **this document
  is current** (its owning lane may reconcile the table later).

## Acceptance criteria (of this document, mapping to ticket T-034)

- AC-001 (TUI inventory complete): Given Inventory A, when a reader
  checks any TUI chat family from the v2 docs (composer, steering,
  commands, models, sessions, diffs, permissions, questions, attention,
  usage, terminal, appearance), then it appears with a `[TUI]`/`[V1]`
  citation. — verified: A1-A12 cover all twelve families.
- AC-002 (Eclipse inventory complete): Given Inventory B, when a reader
  checks any row against `docs/TESTING.md` chat lanes or the named code,
  then the state matches with a `[LANE]`/`[ADOPT]`/`file:line` citation.
  — verified: B rows cite TESTING rows U-064/B-024/U-041/U-071/T-010 and
  the named classes.
- AC-003 (desktop notes): Given the desktop-notes section, when a reader
  follows a citation, then it lands in `docs/opencode-v2-adoption.md`
  (line-cited) — no source outside repo/docs is used.
- AC-004 (gaps as testable requirements): Given FR-CP-01..14, when a
  reviewer reads any requirement, then it is EARS-phrased, evidence-
  cited, sized, and carries a candidate child-ticket title; the same
  list is recorded as a ticket comment for division.
- AC-005 (artifact before in-review): this file is recorded with
  `task_add_artifact` before `T-034` moves to `in-review`.

## Open questions

- Q-001: does the live v2 contract expose a side-question verb (for
  FR-CP-03)? Probe at child start; none was visible in the repo docs.
- Q-002: is the WebSocket PTY stream driveable from the harness's
  HTTP client layer (FR-CP-13), or does it need a new transport piece?
- Q-003: should the in-chat session switcher (FR-CP-05) reuse the
  Server view's session list data path (recommended) or a separate
  query — design decides.
- Q-004: FR-CP-01 — does the chat's `sendMessage` path need the
  synthetic/system-prompt side channel preserved when `files` are
  attached (payload-shape probe at child start)?
- Q-005: should `/share`-`/unshare` be dropped from the registry now
  that upstream retired sharing (#55716), or kept as honest notices?
  Owner call at division time.
