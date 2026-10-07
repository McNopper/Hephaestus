# Testing Hephaestus

## About this document
- **Kind:** `doc` / test guide: how to build, deploy and exercise the
  harness, and how to report what you find.
- **Read by:** anyone asked to test the harness (no repo knowledge
  assumed); **written by:** maintainers.
- **Related:** `README.md` (install), `ROADMAP.md` (what is open),
  `docs/HISTORY.md` (what changed), the task store
  `.opencode/tasks/hephaestus/` (where findings become tickets).

## 1. Setup (once)

1. **Clone + configure your model first.** No model ids are committed —
   set your own default in `opencode.json` (`"model": "<your provider>/<model>"`)
   for whichever opencode provider you use.
2. Install **opencode** (`npm install -g @opencode/cli`), **Java 21**,
   **PowerShell 7** (the launchers use `?.`), and for graphics: `pip install
   mcp/graphics/requirements.txt`.
3. **Build the harness:** `cd eclipse; .\build.ps1 verify -Pquality` —
   green (27 modules) is the baseline. First run downloads the Eclipse
   target platform (slow); later runs are ~5 minutes.
4. **Deploy to your Eclipse:** close Eclipse, then `.\deploy-dev.ps1`
   (default target `C:\eclipse-cpp`, override with `ECLIPSE_HOME` or
   `-EclipseRoot`). Start Eclipse once with `-clean` after the first deploy.

## 2. What to test (the current build's surfaces)

Work through these in Eclipse; every row is a shipped ticket, so a failure
maps directly to a ticket.

| Area | Check | Ticket |
|---|---|---|
| **Board cards** | Switch *Group by* across Progress / V-model stages / Epic: every card shows `status emoji │ type emoji │ title │ points` as real sortable columns (click a column header to cycle ascending / descending / snapshot order); headers carry their text markers (status: emoji + name, stage: number + connector, lane: a box marker); row tooltips still show readiness + journey | U-065 |
| **Chat background** | In the composer: `Ctrl+B` (or the `⤵ Background` button) backgrounds the session and keeps it working; `Ctrl+Alt+Shift+B` works from any view; Build All still owns `Ctrl+B` outside the chat | U-064 |
| **Long turns** | A reply that runs tools for minutes never fails with "Send failed … 300s" — progress keeps it alive up to an hour; a dead session hands off silently to the late-reply watcher | B-024 |
| **Version pin** | No version-mismatch warning in the Error Log (pin is 2.0.21) | U-063 |
| **Subagents & shells** | Session Details → *Subagents* and *Shell tasks* sections; output tails open in a console; the Fleet tree nests them under sessions | U-041 |
| **Traceability** | `task_traceability` reports `via`/`self_verified` rows; a ticket that walked to a test stage pairs with itself; pm requirements appear as definitions | U-070 |
| **Store lint** | `task_doctor` reports nothing (unparsable files, unresolvable artifact refs, bad flag combos are all checked) | U-053 |
| **Card sort** | Click a card column header: cycles ascending (up) -> descending (down) -> snapshot order (both arrows); unset values sort last in both directions | U-065 |
| **Tooltips** | Hover: status group headers explain the status, epic lanes and blocked counts explain themselves, card column headers explain the sort | U-065 |
| **Chat queue** | While a reply streams, ENTER queues a message: numbered table with header count + full-text row tooltips; pencil Edit / bin Remove / shuffle Fork, double-click or ENTER edits | U-071 |
| **Stage models** | Dispatch settings dialog: one model field per V-stage (blank = server default), malformed value rejected with a red reason; launch precedence fleet_dispatch > ticket > stage > default | U-072, U-073 |
| **Emoji vocabulary** | `docs/emoji-vocabulary.md` lists every concept with one canonical emoji; no concept shows two different emoji across surfaces | U-074 |
| **Blocked filter** | Blocked is a STATUS (U-067): the status filter menu lists it as the seventh entry; its 🚫 renders **red** in the status cell, the title stays clean (no duplicate marker), and the toolbar Needs me toggle filters the same state | U-065, U-067 |
| **Readiness badges** | done tickets that entered mid-pipeline never badge as "waiting" | B-007-era fix |
| **Chat streaming** | live text while a reply generates; a tool-using turn stays open until the final answer | T-010 |

## 3. The headless path (no Eclipse)

- The **quality gate** is the machine verdict: `cd eclipse;
  .\build.ps1 verify -Pquality`.
- The **store** can be exercised from any opencode session via the
  `task_*` tools (project `hephaestus`): create a ticket, claim it,
  advance/send back a stage, run `task_readiness` and `task_doctor`.

## 4. Reporting feedback

- **File it as a ticket** (preferred): `task_create` with `type: bug`,
  a title that states the outcome, and steps-to-reproduce in the
  description — or drop a comment on the related ticket. Findings become
  store state; that is how this project tracks effort and evidence.
- Say what you **expected** vs what happened, and include the build time
  (`deploy-dev.ps1` output) if a UI behavior is involved.
- Known-open work lives in `ROADMAP.md`; open decisions in its
  *Decisions needed* section. Don't re-file those.
