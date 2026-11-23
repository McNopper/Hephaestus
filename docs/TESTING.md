# Testing Hephaestus

## About this document

- **Kind:** `doc` / test guide: how to build, deploy and exercise the
  harness, and how to report what you find.
- **Read by:** anyone asked to test the harness (no repo knowledge
  assumed); **written by:** maintainers.
- **Related:** `README.md` (install), `ROADMAP.md` (what is open),
  `docs/HISTORY.md` (what changed), the task store
  `.harness/tasks/hephaestus/` (where findings become tickets),
  `docs/live-in-eclipse-recurrence-flow.md` (the acceptance recurrence —
  §4 executes the instances it mints).

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
| **Board cards** | Switch *Group by* across Progress / V-model stages: every card shows `status emoji │ type emoji │ title │ points` as real sortable columns (click a column header to cycle ascending / descending / snapshot order); headers carry their text markers (status: emoji + name, stage: number + connector, lane: a box marker); row tooltips still show readiness + journey | U-065 |
| **Chat background** | In the composer: `Ctrl+B` (or the `⤵ Background` button) backgrounds the session and keeps it working; `Ctrl+Alt+Shift+B` works from any view; Build All still owns `Ctrl+B` outside the chat | U-064 |
| **Long turns** | A reply that runs tools for minutes never fails with "Send failed … 300s" — progress keeps it alive up to an hour; a dead session hands off silently to the late-reply watcher | B-024 |
| **Version pin** | No version-mismatch warning in the Error Log (pin is 2.4.0; bump with each cross-check) | U-063 |
| **Subagents & shells** | Session Details → *Subagents* and *Shell tasks* sections; output tails open in a console; the Fleet tree nests them under sessions | U-041 |
| **Traceability** | `task_traceability` reports `via`/`self_verified` rows; a ticket that walked to a test stage pairs with itself; pm requirements appear as definitions | U-070 |
| **Store lint** | `task_doctor` reports nothing (unparsable files, unresolvable artifact refs, bad flag combos are all checked) | U-053 |
| **Card sort** | Click a card column header: cycles ascending (up) -> descending (down) -> snapshot order (both arrows); unset values sort last in both directions | U-065 |
| **Tooltips** | Hover: status group headers explain the status and blocked counts explain themselves, card column headers explain the sort | U-065 |
| **Chat queue** | While a reply streams, ENTER queues a message: numbered table with header count + full-text row tooltips; pencil Edit / bin Remove / shuffle Fork, double-click or ENTER edits | U-071 |
| **Ticket model lever** | the ticket's optional `model` field (`provider/model`, via `task_create`/`task_update`) decides what a run costs; small well-specified tickets deserve cheap models (the fleet-era per-stage dispatch dialog was removed with the fleet in U-110) | U-072, U-073 |
| **Emoji vocabulary** | `docs/emoji-vocabulary.md` lists every concept with one canonical emoji; no concept shows two different emoji across surfaces | U-074 |
| **Blocked filter** | Blocked is a STATUS (U-067): the status filter menu lists it as the seventh entry; its 🚫 renders **red** in the status cell, the title stays clean (no duplicate marker), and the toolbar Needs me toggle filters the same state | U-065, U-067 |
| **Readiness badges** | done tickets that entered mid-pipeline never badge as "waiting" | B-007-era fix |
| **Chat streaming** | live text while a reply generates; a tool-using turn stays open until the final answer | T-010 |
| **Chat compaction** | `/compact` opens the divider row (spinner while running), streams the summary into it, finalizes with the `<in> in / <out> out` usage suffix; the row survives a view reload from history | T-147 |
| **Chat status strip** | the footer strip shows context-window usage (`N (M%)` of the model's window), the session cost and the goal chip (◎); clicking the chip opens the goal dialog (objective, status, reason, pause/resume/clear); the strip hides with no session and survives a view reload | T-149 |
| **Stage artifact gate** | a definition-stage `done` with zero recorded artifacts is refused by the store's done-time guard, naming `task_add_artifact` (a rationale doc counts); after recording one the transition succeeds; `task_doctor` reports done/in-review definition tickets that carry no artifact | U-080, U-110 |
| **Upstream injection** | a staged dispatch prompt carries `UPSTREAM DEFINITIONS` (governing tickets + artifact refs); a governing `done` ticket without artifacts shows `GOVERNING DEFINITION MISSING`; non-bug work with nothing above it starts top-down with the backfill instruction | U-079 |
| ~~**Horizontal loop**~~ | `task_report_horizontal` retired in U-110; a reviewer FAIL now blocks the ticket in its own stage and escalates to its basis | U-082 → U-110 |
| **Evidence-based readiness** | an upstream claiming done with no artifact reports `WAIT_UPSTREAM` ("claims done but has no artifact"); recording the artifact unblocks the downstream to READY | U-081 |
| **V-enforcement docs** | every software-*/test-software-* skill carries its *Stage Contract* section; executor/manifest-author/project-manager/reviewer carry the done-as-four-box contract; AGENTS.md has the V-enforcement block | U-078 |

## 3. The headless path (no Eclipse)

- The **quality gate** is the machine verdict: `cd eclipse;
  .\build.ps1 verify -Pquality`.
- The **store** can be exercised from any opencode session via the
  `task_*` tools (project `hephaestus`): create a ticket, claim it,
  advance/send back a stage, run `task_readiness` and `task_doctor`.

## 4. The acceptance live pass (recurring instances)

Every completed wave mints a `test-requirements` acceptance instance —
`Acceptance (live in Eclipse): …` (owner requirement 2026-11-15, T-039;
flow: `docs/live-in-eclipse-recurrence-flow.md`). Instances sit
`in-progress`, assignee `owner-acceptance` — "awaits the owner" — and are
NON-BLOCKING: no verdict, wave or release waits on them (the engine's
review is their only `done` authority). One live pass evidences several
open instances — the checklist is shared, the verdicts are separate:

1. **Execute:** work the instance's check lines (console checks, UI
   passes, panel round trips) in Eclipse; record the evidence — a
   screenshot or panel transcript reference — with `task_add_artifact`
   on that instance; flip it to `in-review`.
2. **File findings:** a failed check becomes a `type: bug` ticket (the
   reporting rules below); findings ride the NEXT wave — finished work
   is never re-opened.
3. **Failed pass:** record the failed evidence on the instance first,
   then block it (`task_set_blocked`, resume to `ready`) with
   the blocker naming the finding tickets; when the findings land, clear
   the blocker (only the owner does) and re-run the pass.

## 5. Reporting feedback

- **File it as a ticket** (preferred): `task_create` with `type: bug`,
  a title that states the outcome, and steps-to-reproduce in the
  description — or drop a comment on the related ticket. Findings become
  store state; that is how this project tracks effort and evidence.
- Say what you **expected** vs what happened, and include the build time
  (`deploy-dev.ps1` output) if a UI behavior is involved.
- Known-open work lives in `ROADMAP.md`; open decisions in its
  *Decisions needed* section. Don't re-file those.
