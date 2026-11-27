# Contracts

## About this document

- **Kind:** `doc` / reference (part of `.opencode/docs/`).
- **Read by:** agents and maintainers; **written by:** maintainers.
- **Related:** complements `domains.md`; `AGENTS.md` links to this file as its contract source (field/state/tool/review semantics are stated HERE, never restated there); the ticket/artifact rules are enforced by the `task_*` MCP tools (the `tasks` pack of the Eclipse harness's `eclipse-build` endpoint, also served over stdio via `eclipse/tasks-tools.ps1`) and the `project-manager-create-ticket` skill.

The shared, durable agreements every skill and agent honors. Change these centrally and
update all references.

## Mental model (read this first)

A ticket is one Markdown file carrying **four independent axes**. Grouping and
ancestry are plain link fields, not types - the engine treats them as data, and
every query (readiness, traceability, `task_links`) recomputes from the files
deterministically.

| Axis | Question it answers | Contract |
|---|---|---|
| `basis` | *where did I come from?* | the one upstream ticket whose completion spawned me (the derivation edge); entry tickets carry none; the basis chain up to the requirement IS the scope (owner rule 2026-11-19: the `epic` field is gone) |
| `stage` | *which V-level owns me?* | immutable at creation; drives dispatch and readiness |
| `status` | *where am I in the lifecycle?* | seven states (owner model 2026-11-20): `ready`, `in-progress`, `in-review`, `done`, `not-implemented`, `paused`, `blocked` - blocked/paused are states with resume rules; direct ENTRY refuses done/not-implemented/paused (the `setBlocked` verb throws), while CHAIN propagation deliberately re-opens them - a done, not-implemented or paused basis hop joins the chain with its pre-block state kept in `resume_to` (symbol-level authorities: the `setBlocked` javadoc's chain rule and its terminal-free chain-walk loop in `TaskStore.java`, pinned by `ChainBlockTest`'s paused-hop pin) |

**The derivation tree.** Division creates children: `basis` = the completed
ticket. The V walks down the definition leg and up the
verification leg by pair (`X` <-> `test-X`). Progress over a requirement = done
children / all children (`task_list`, `task_traceability`, `task_links`).
**There are no milestones** - the cross-tree anchors are the requirement's
basis chain and the release definition in `ROADMAP.md`. Classic mapping: Product Goal = GOAL
ticket, story = a ticket (stage-sized), sub-task =
todos.

## Ticket contract (`task_*` tools, `.harness/tasks/` store)

A ticket is the **hand-off unit**. Its authoritative shape (per project):

| Field | Type | Notes |
|---|---|---|
| `id` | `T-NNN` (or prefix, e.g. `FR-001`) | minted by `task_create(id_prefix=)` |
| `title` | string | active verb, states outcome; a staged ticket carries a leading `[stage]` tag (`[implementation] Encode the stage in the ticket name`, owner directive 2026-11-17 T-175) so the lane is visible wherever the name shows - chat listings, Board, comments - without opening the frontmatter. The **store owns the tag** at every write point (create, title update, stage override): re-derived, never doubled, follows a stage move, stripped when the stage clears, never on a stageless ticket. **Don't hand-write it** when filing; existing tickets pick it up on their next title/stage write |
| `description` | string | what + why |
| `type` | `story`/`task`/`bug`/`spike` | |
| `status` | enum | `ready` → `in-progress` → `in-review` → `done` (the normal case), plus `not-implemented` (a DONE state - decided not to build, reachable from any non-terminal state), `paused` (dependency wait: born paused while the basis is open; auto-resumes when it completes) and `blocked` (hard stop + `resume_to` + `blocker`; propagates up the basis chain to the requirement, clearing walks back down) |
| `blocked` + `blocker` | bool + string | **orthogonal** flag, any active state; `blocked` always means needs-a-human |
| `story_points` | int | relative size; `project-manager-estimate-costs` can later feed a `cost` field |
| `priority` | `low`/`medium`/`high`/`critical` | drives self-claim order |
| `role` | enum (extensible) | `architect`/`developer`/`tester`/`pm`/`cpp-engineer`/`graphics-engineer` → who claims it |
| `stage` | enum/null | V pipeline stage (canonical `VStages` order): `requirements`, `system`, `architecture`, `design`, `implementation`, `test-implementation`, `test-design`, `test-architecture`, `test-system`, `test-requirements`; `null` = legacy/untracked. Set at creation for definition work; verification tickets carry their test stage. The V is an **async pipeline**, not a phase gate (U-110): the ticket stays in its stage - completing it divides into linked downstream tickets (`basis` = the completing ticket; the chain to the requirement is the scope), which their own stages' tickers gate via readiness. The wandering verbs (`task_advance`/`task_send_back`/pass-through, U-029) are retired; legacy `stage N passed: reason` markers in old history remain readable. |
| `assignee` | string | set by `task_claim` |
| `acceptance_criteria` | string[] | GIVEN/WHEN/THEN; verification must satisfy all |
| `labels` | string[] | free tags |
| `basis` | string | upstream/derivation ticket id (validated live+archive at create/update; the chain to the requirement is the scope) |
| `artifacts` | `{kind, ref, note, by, ts}[]` | **where the produced output lives** |
| `todos` | `{text, done}[]` | checklist items (`task_todo` with `action=add/toggle/remove`) |
| `history` | append-only | state transitions |
| `comments` | append-only | human/agent notes |
| `created_at` / `updated_at` | ISO-8601 instant (UTC, e.g. `2026-08-28T03:25:20.519Z`) | drives ready-queue claim ordering |

### The store (Maven-ready Markdown storage)

One **file per ticket** — `<repo>/.harness/tasks/<project>/T-NNN.md` — Markdown with a
frontmatter block (scalars + one-line JSON lists) and tool-owned body sections
(`## Todos`, `## Artifacts`, `## Comments`, `## History`); the free-form description is the
body. A `_meta.json` sidecar holds the per-prefix id counters (the board is
derived from the tickets). The files are
**version-controlled** (`.gitattributes` pins LF; the codec tolerates CRLF/BOM on read) so
task changes ride the normal git/Maven workflow — the seam the future
`opencode-tasks:sync`/`plan` mojos build on.

**Two store layouts.** The store root is a plain directory, and it comes in two
shapes: a **subtree of the host repo** — `<repo>/.harness/tasks`, the Hephaestus
default and what `docs/own-project.md` step 1 creates — or a **dedicated store
repo** whose root *is* the store, sitting outside the code repo. Both are live
configuration, not history: the stdio transport defaults to `.harness/tasks`
under its working directory and takes `--root <dir>` (`TasksStdioMain`, exposed
as `-Root` in `eclipse/tasks-tools.ps1`), while the Eclipse side resolves ONE
root per session (`TasksRootResolution`, first hit wins — the five steps the
code actually walks, in order: (1) an explicit Board Store path honored as-is →
(2) workspace climb (the first ancestor carrying `.harness/tasks`) → (3)
open-project adoption (an open repo project contributes `<repo>/.harness/tasks`)
→ (4) the `tasksRoot` preference as a fallback *below adoption*, honored only
when that configured path exists on disk → (5) the historical guess
`<workspace>/../.harness/tasks`, kept last so resolution always yields a path
and the Board shows its not-found notice for it). The
git discipline fits either: `StoreSync` commits with `git add -A -- .` scoped to
the store root, so a subtree store never sweeps the host repo's own WIP into a
store commit, and a dedicated store repo commits its whole tree. One caveat on
the dedicated layout: the repo-root-dependent check (`task_doctor`'s file/path
artifact resolution) resolves only when the root is `<repo>/.harness/tasks` or
the legacy `<repo>/.opencode/tasks` — any other root makes it skip rather than
guess against an accidental parent (`TaskStore.repoRootOrNull`).

Store actions beyond the `task_*` tools: `newProject` scaffolds one project (directory +
`_meta.json` shape) and `resetProject` wipes one project's tickets (live + archived) -
**only that project**: other projects' tickets stay untouched, and ticket ids survive a
reset (never reused) - these are the Board's New/Reset project actions, store-level, not
`task_*` MCP tools. The store's review-doubt route (see the review contract below) is no
longer store-level-only: `routeReviewDoubt` is dispatched through the
`task_route_review_doubt` tool (T-220), alongside every other verb.

### Tool surface (`pm_*` → `task_*`)

21 tools (T-032 consolidation; the stage-agent pair followed under U-104, the review-doubt
route under T-220): `task_create`,
`task_get`, `task_list` (returns the
prioritized order for `status=ready`),
`task_update`, `task_set_blocked`, `task_clear_blocked`, `task_route_review_doubt` (the
UNCLEAR round-trip: doubt back to the originator, escalation to blocked after the
budget), `task_stage_agents` (the
per-stage dispatch agent map), `task_set_stage_agent` (sets or clears one V stage's
agent), `task_claim`, `task_release`,
`task_add_comment`, `task_add_artifact`, `task_remove_artifact`, `task_todo`
(`action=add/toggle/remove`), `task_board`,
`task_archive_done`, `task_traceability`, `task_doctor`, `task_readiness`,
`task_links` (the up/down/back link graph around a ticket). Served by the **`eclipse-build`
MCP endpoint** when the Eclipse harness runs (plus the C++ tool pack) and, for TUI-only
sessions, by the stdio `tasks` pack — `eclipse/tasks-tools.ps1`, declared in `opencode.json`
as the `tasks` MCP server (the retired `fleet-tools.ps1` launcher was removed with the fleet
in U-110). One tool surface, two transports. Each
launcher stages a private copy of its resolved jars at start (B-005 staging), so a reactor
rebuild can never rot a running server; both launchers resolve the built jars from the **main
checkout** when invoked inside a fleet worktree (worktrees carry no `target/` build output).
Note: opencode prefixes tools with the server name, so
TUI sessions see them as `tasks_task_*`; in-Eclipse agents as `eclipse-build_task_*`. There is
no detached fleet daemon: the former V-006 runtime (TCP core + proxy + pidfile + launcher) is
retired — the automatic fleet pump lives in Eclipse (JobManager).

**Live set vs archive (T-240).** The board verbs serve the **live set** — a project's top-level
`*.md`, the working set: `task_list`, `task_board` and `task_traceability` never return an id
parked in `<project>/_archive/`, and `task_get` *returns* a live ticket only. The VERDICT is
honest about the difference: `task_get` and every verb that resolves a ticket BY ID
(`task_update`, `task_set_blocked` / `task_clear_blocked`, `task_release`, `task_add_comment`,
`task_add_artifact` / `task_remove_artifact`, `task_todo`, `task_route_review_doubt`) answer
`ticket <id> is archived - see <path>` for an ARCHIVED id - the path is the archive file itself
(repo-relative `.harness/tasks/<project>/_archive/<id>.md` when the store sits in a repo, the
absolute path otherwise) - while a genuinely absent id answers `ticket <id> not found in project
<project>` — history vs hole, the two messages
stay distinguishable (and both stay `NotFound`/`isError` text, so no consumer's error routing
changes). Archived tickets remain reachable wherever the store reads BOTH pools: `task_links`
resolves its graph live + archive and marks an archived node `archived: true` (`missing: true` is
reserved for an id resolving nowhere, and an archived target opens its own graph marked at the top
level), `task_readiness` and `task_doctor` weigh the archive pool, basis validation accepts a live
or archived upstream (create/update), and the archived record itself is a version-controlled file
at the path the verdict names — read it directly, or `git show` it.

Plus the H6 readiness/stage pack: `task_readiness` (per-ticket READY / WAIT_UPSTREAM /
STALE / BLOCKED / RUNNING / NOT_APPLICABLE over the current board, basis-chain gating
per U-110), `task_set_blocked` (blocked escalation to the basis), `task_create` with
`stage`/`basis` (division + linking), `review` (`human | ai` - who must
verdict the ticket; human only on the first/last stage, T-155), `task_stage_agents` /
`task_set_stage_agent` (the per-stage dispatch agent map, U-104 - the ticker's
STAGE-AGENT MAP, settable without file edits), `task_doctor` - the stage-flow surface the
ticker pass builds on.

**Readiness verdict semantics** (what the ticker dispatches on; the per-ticket shape is
`{id, stage, kind, reason}`): **READY** = upstream (basis chain) done AND inputs unchanged
since the downstream's last run; **WAIT_UPSTREAM** = the basis still open; **STALE** = an
upstream (basis chain) had a STRUCTURED input write after the downstream ran - status/stage/
basis/role, the artifact list, the claim status moves (D-003 option B: comments and
bookkeeping never stale completed downstream work), decided per side on the
`inputs_changed_at` stamp with a legacy fallback to `updated_at` - STALE outranks READY in
the engine's severity order, and each production STALE verdict leaves exactly one
`inputs changed:` history marker on the downstream ticket (idempotent - later ticks append
nothing until the input changes again; U-052); **BLOCKED** = needs-a-human; **RUNNING** =
claimed; **NOT_APPLICABLE** = no stage, or done with fresh inputs, or having entered the
pipeline with no upstream ticket at all - never re-dispatched.

### Artifact kinds (the hand-off locator)

| kind | ref | when |
|---|---|---|
| `file` | `src/renderer/swapchain.cpp` | file(s) created/edited |
| `path` | `build/reports/cppcheck.xml` | a directory or report |
| `git` | `abc1234` or `branch: feat/x` | the commit / branch holding the work |
| `url` | `https://…/diff.png` | a remote resource |
| `doc` | `ARCH-003` | another artifact/ticket id |

**Rule:** a worker records its artifact with `task_add_artifact` *before* moving the ticket
to `in-review`, so the next agent needs no questions.

### Traceability pairing (`task_traceability`)

The definition -> verification matrix (D-001, implemented as U-070; the
`project-manager-audit-traceability` skill drives the manual artifact-level audit). Each row
reports `via` / `self_verified` from THREE signals:

- **role + basis link** - a verification-role row resolves `verifies` to its `basis`; a
  definition-role row collects `verified_by` from verification-role children (`pm`
  requirements count as definitions);
- **journey** - the ticket itself walked BOTH legs (a definition entry, then a `test-*`
  stage, from history - `advance()` rewrote the role on the way into the test leg) -> the
  wandered ticket pairs with itself (`via: journey`, `self_verified: true`);
- **test-shaped artifact** - a `*.tests/` path in `artifacts` -> `via: artifacts`,
  `self_verified: true`.

A definition row with no signal lands in `orphan_definitions` (an orphan is a genuine gap,
distinguishable from inflation). A definition stage without its linked verification
counterpart is incomplete work.

## Review contract (auto-acceptance + stage-shaped evidence)

The reviewer pass **auto-accepts** (U-021) when the ticket carries the evidence its stage
demands (the B-007 stage-shaped evidence matrix):

- **definition stages** (`requirements`, `system`, `architecture`, `design`) accept
  ticket-body / acceptance-criteria updates, recorded doc/path/url artifacts, and doc/store
  paths in the diff — code changes are permitted but never the required output;
- **`implementation`** expects code plus tests — at least one acceptance-criterion-named path
  in the diff, with test changes alongside;
- **test-\* stages** expect tests and, where used, golden/reference outputs.

**Verdict routing:** a review **FAIL** blocks the ticket **in its own stage** (`task_set_blocked`
with the reviewer's reasons - the maintainer-escalation signal) and, under U-110, escalates to
the ticket's **basis** (the upstream link): the upstream stage's ticker re-opens or reworks the
basis. An unstaged ticket keeps the code-diff expectation as the default (see the matrix above)
and, when its verdict is a routed doubt, stays in `in-review` with the doubt as a comment; a
U-029 stage pass-through is never judged (a passed stage produced no run to judge).

A review doubt never blocks first: it **round-trips to the originator** via the store's
`routeReviewDoubt` — one retry per stage visit (history marker `review doubt retry (1/1)`) —
and only a doubt recurring within the same stage visit escalates the ticket to `blocked`.
`blocked` always means needs-a-human.

## Concurrency contract

- **Self-claim:** workers loop `task_claim(role=…)`; the call is atomic (an in-JVM lock
  plus an OS file lock on the project's `.lock`, whole-directory transactions, temp-rename
  writes) so concurrent agents — Eclipse-hosted sessions and stdio TUI processes alike —
  get distinct tickets. A claim with nothing to do returns the JSON literal `null`; worker
  loops terminate on it.
- **What a claim filters (T-226, decided 2026-11-26):** `status: ready`, unassigned
  (`assignee` empty), unblocked, `role`-matching — plus the readiness verdict, computed
  inside the same locked transaction (the lock serializes verdict and claim, so there is no
  scan/claim race): a **basis-linked** candidate whose `StageReadiness` verdict is
  `WAIT_UPSTREAM` or `STALE` is skipped — a child born `ready` while its basis sits
  in-review without an artifact (the U-081 split between birth's `basisSatisfied` and
  readiness's artifact check) is never scooped by a blind role-claim, and `STALE` re-runs
  stay with the ticker's step-4b targeted claim (status + assignee in one `task_update`).
  `BLOCKED` was never claimable (the unblocked filter) and `RUNNING` cannot occur (the
  status filter). A basis-less candidate is claimable whatever its flow-history verdict
  reads — it has no derivation gate, and dispatch still gates where it belongs: the ticker
  scans `task_readiness` first and claims only ids whose step-1 row read `READY`. A
  stageless candidate is outside the stage pipeline and stays a plain role-claim.
- **Reassign:** a returned ticket (`task_release` → `ready`, `assignee` cleared)
  can be claimed by a *different* agent.
- **Store:** `eclipse/bundles/com.opencode.ide.tasks` — one Markdown file per ticket,
  atomic transactions, id counters persisted in `_meta.json` (task ids are never reused,
  even after a lost sidecar - they are recovered from the files).
- **Direct edits:** hand edits to a task file are allowed between tool writes but the
  tools rewrite whole files — last writer wins. Treat direct edits as read-only hints.

## Role → skill/agent dispatch

| role | owns/claims via | verifies via |
|---|---|---|
| `architect` | `software-system`, `software-architecture` | `test-software-system`, `test-software-architecture` |
| `developer` | `software-design`/`software-implementation` | matching `test-software-*` |
| `tester` | `test-software-*` | (itself) |
| `pm` | `software-requirements`, `project-manager-*` skills | — |
| `cpp-engineer` | `cpp-tools` agent - setup-supplied | `cpp-tools` agent - setup-supplied |
| `graphics-engineer` | `mcp.graphics` tools (+ `graphics-expert` for `very-high` work) - setup-supplied | `graphics-render-comparison` - setup-supplied |

**Availability (T-252, 2026-11-26):** the four targets in the last two rows (`cpp-tools`,
`graphics-expert`, `graphics-render-comparison`, `mcp.graphics`) are **not shipped in this
repo** - the 2026-10-15 curation moved them out (AGENTS.md, *C++ and graphics tooling lives
outside the repo*), so they come from the maintainer's setup. Dispatch to them only when the
setup resolves them; otherwise surface NEEDS-HUMAN naming the missing target.

## Model-tier contract

Agents/docs reference **tiers**, never hard-coded model IDs. The authoritative tier
**selection rules** live in `project-manager-orchestrate-execution`; the concrete tier→model
mapping is **setup-local and never committed in this repo** (D-004): the `opencode.json`
default `model` field plus, where a setup chooses one, an agent-frontmatter `model:` override.
No file under `.opencode/agent/` carries a `model:` key today - the agents resolve their tier
at runtime (the stage workers state that their `model:` frontmatter is *injected by the local
setup*). Tiers: `very-low`, `low` (default executor), `mid`, `high`
(plan/review), `very-high` (run twice & reconcile). Pick the lowest tier that satisfies the
task; escalate, never de-escalate.
