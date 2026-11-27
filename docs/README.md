# Docs - the map of this directory

## About this document

- **Kind:** `doc` / index - a reader's map of `docs/`: what each document is,
  which records belong together, which file leads a family, and where to start.
  It adds orientation only - it replaces, merges and re-dates nothing.
- **Read by:** anyone opening `docs/` for the first time; the maintainer
  checking that a document has an obvious home.
- **Related:** `README.md` (the repository overview), `AGENTS.md` (the
  operating doctrine), `docs/HISTORY.md` (the chronological record),
  `ROADMAP.md` (current state and open work), `.opencode/docs/contracts.md`
  (the field/state/tool contract reference).

## Where to start

Different questions have different doors:

- **"I want to run this harness on my own project."** Start at
  `docs/own-project.md`; on a fresh machine, `docs/new-machine-setup.md`
  brings the machine up first.
- **"I want to know whether the current build works, and how to check it."**
  `docs/TESTING.md` - setup, the surfaces worth exercising, the headless path
  and the recurring live acceptance pass.
- **"How does the whole thing fit together?"** Read `AGENTS.md` once for the
  doctrine, then `docs/design-stage-pipeline.md` for why the stage pipeline is
  shaped the way it is, then `.opencode/docs/contracts.md` whenever you need
  the exact meaning of a ticket field or tool verb.
- **"Why is it this way - what happened, and when?"** `docs/HISTORY.md` is the
  changelog; what is still open lives in `ROADMAP.md`.
- **"What does the harness ship today?"** `docs/feature-matrix.md`, beside the
  root `README.md`.
- **"I am looking for one of the design / verification records."** Jump to
  [The six V-record clusters](#the-six-v-record-clusters) below - every family
  is listed with its leading file first.

## What is in here

89 tracked files: **70 Markdown documents** (65 at this level, 5 under
`docs/requirements/`) plus 19 support files - 17 `opencode-v2-surface-*.txt`
API snapshots kept beside the adoption matrix for diff history (newest:
`docs/opencode-v2-surface-2.4.2.txt`), the one-line fleet-era marker
`docs/fleet-smoke-marker.txt`, and the V-model animation
`docs/assets/v-model-agents.gif` used by the root `README.md`.

| Group | Files | What it is |
|---|---|---|
| Human guides & reference | 7 | how to run, set up, test and understand the harness (see [Guides and reference](#guides-and-reference)) |
| V-record clusters | 39 | six families of paired definition/verification records (see [The six V-record clusters](#the-six-v-record-clusters)) |
| Chat-surface inventories | 2 | three views of one subject, the third living in `docs/TESTING.md` (see [The chat-surface inventories](#the-chat-surface-inventories)) |
| Stage-pipeline records | 2 | the requirements and design records of the stage pipeline itself (see [The stage-pipeline records](#the-stage-pipeline-records)) |
| Single-ticket records | 3 | one-stage V records that never grew a family (see [Single-ticket records](#single-ticket-records)) |
| Requirements records | 5 | older requirements definitions, kept under `docs/requirements/` |
| Dated records | 6 | evidence written on a specific date; read as history (see [Dated records](#dated-records)) |
| The adoption matrix | 1 | `docs/opencode-v2-adoption.md` plus its 17 API snapshots |
| The changelog | 1 | `docs/HISTORY.md` |
| Retired fleet set | 4 | pointers left behind by the fleet's removal (see [Retired fleet documents](#retired-fleet-documents)) |

(The Phase-A audit of 2026-11-26 counted 64 prose docs; re-counting with
`git ls-files docs` gives 70 Markdown files, and the audit's own buckets sum to
331 of the 332 tracked Markdown files in the repository - one file sat outside
its map. This index covers all 70.)

## How to read a V record

Almost every substantial document here is a **V record**: the product of one
ticket at one stage of the V pipeline (requirements -> system -> architecture ->
design -> implementation, verified back up by the `test-*` stages). They all
share one shape:

- The **H1 names the stage, the subject and the ticket** ("Product-owner seat -
  system integration (T-083)").
- An **About block** follows - as a section (`## About this document`) or as a
  leading bullet - carrying **Kind** (which stage, which ticket), **Read by**
  and **Related**. Some add scope, currency or non-goals. A reader who lands
  cold gets their bearings from this block alone.
- Numbered sections carry the content; definition records typically close with
  an acceptance-criteria mapping and a "lane and non-goals" section.
- A **`.tests` sibling** (for example `docs/acceptance-run-design.tests.md`
  or `docs/intake-seat-system.tests.md`) is the record's **paired
  verification leg**: the document that decided whether its definition sibling
  was done. It opens with a criteria table (ID | upstream acceptance criterion
  | observable oracle | verdict), then one section per criterion, then a
  verdict. The IDs are short and leg-specific - `IC-n` integration, `CT-n`
  component, `LT-n` library, `AT-n` architecture-verification, `ITS-n`
  integration-of-the-system-flow - and at least one criterion in the
  acceptance-run family asserts, verbatim, that the artifact *"exists at the
  recorded path with the repo doc header"*.
- **The pair is the structure, not duplication.** Definition and `.tests`
  records are separate tickets' artifacts; ticket artifact references pin their
  exact file paths, and one contract test reads a record's text directly. That
  is why nothing in this directory gets merged or renamed into a tidier shape -
  orientation (maps, canonical markers) is how this directory stays navigable.

## The six V-record clusters

Six families of records look like duplication at a glance and are not: each is
the V-model's paired-verification structure laid out in files. The family map
for each lives here, in one place, rather than inside the records themselves -
the records stay exactly as their tickets recorded them.

### Cluster: acceptance-run (10 files)

**Family map.** The canonical file is `docs/acceptance-run-design.md` (ticket
T-095, the inside-of-modules design), verified by its paired
`docs/acceptance-run-design.tests.md` (T-096's component criteria, including
CT-D-001..007); read it first. Around it: the run's
requirements `docs/acceptance-stage-pipeline.md` (T-031's artifact), the system
leg `docs/acceptance-run-system.md` with `docs/acceptance-run-system.tests.md`,
and the architecture leg `docs/acceptance-run-architecture.md` with
`docs/acceptance-run-architecture.tests.md` - note that this one `.tests` file
carries **two tickets**: Part I is T-098's amendment record, Part II is A-004's
library criteria. `docs/acceptance-run-design-amended.md` (T-111) and its
`docs/acceptance-run-design-amended.tests.md` (T-112) are an **overlay, not a
twin**: the amended design covers only the surfaces the 2026-11-15 attribution
amendment touched (re-verified - the two designs share only 4 long lines), and
the canonical design is not superseded by it. The implementation leg exists
only as `docs/acceptance-run-implementation-amended.md` (T-134's delta map -
this family never had a non-amended implementation document). The family's
paths are pinned by the artifact references on T-095, T-111, T-112, T-134 and
T-135, so all ten files are read in place.

### Cluster: unified-ticker (7 files)

**Family map.** Start at `docs/unified-ticker-requirements.md` (T-035 - what
`ticker-v` must do and why), then `docs/unified-ticker-system.md` for how one
schedule, one steward and one record behave as a system. The family pairs
cleanly above that: `docs/unified-ticker-system.tests.md` verifies the system
leg, `docs/unified-ticker-architecture.md` + `docs/unified-ticker-architecture.tests.md`
cover the component structure (T-103), and `docs/unified-ticker-design.md` +
`docs/unified-ticker-design.tests.md` the component contracts (T-117/T-118);
the requirements record itself has no `.tests` sibling. There is **no
implementation-leg document by design**: the implementation is the executable
`.opencode/skills/stage-pipeline/ticker-setup.ps1` together with the
`.opencode/skills/stage-pipeline/SKILL.md` tick procedure, both recorded as
prototype provenance on T-035 (the ticket's own words: a prototype was built
first, the ticket's legs specify, verify and harden it). The missing
implementation document is the family's honest shape, not a gap.

### Cluster: release-cadence (6 files)

**Family map.** The leading file is `docs/release-cadence-flow.md` - and here
is the naming catch: **the file called `-flow` IS the system leg** (its own
Kind line says V stage: system; ticket T-093). The family has no separately
named system document; do not "fix" this by renaming - the
`ReleaseCadenceContractTest` reads `docs/release-cadence-flow.md` directly and
ticket artifact references pin the same path. Its verification is
`docs/release-cadence-flow.tests.md` (T-094). The remaining legs pair
normally: `docs/release-cadence-architecture.md` + `docs/release-cadence-architecture.tests.md`
(T-110) and `docs/release-cadence-design.md` + `docs/release-cadence-design.tests.md`
(T-121/T-122).

### Cluster: autonomy-spectrum (5 files)

**Family map.** The leading file is `docs/autonomy-spectrum-system.md` - the
flow definition (who records a run's position at intake, where it lives, how
the pump obeys it), which both other legs cite as their governing basis. Above
it: `docs/autonomy-spectrum-architecture.md` + `docs/autonomy-spectrum-architecture.tests.md`
(T-114's library criteria over T-101's architecture) and
`docs/autonomy-spectrum-system.tests.md` (the system leg's integration
criteria). **Known gap, recorded as an observation:** the design leg
`docs/autonomy-spectrum-design.md` (T-125) has **no paired `.tests`
document** - the family's paired chain stops one link short at the design
stage. Nothing in this index changes that; the record simply notes what
exists.

### Cluster: live-in-eclipse-recurrence (5 files)

**Family map.** The leading file is `docs/live-in-eclipse-recurrence-flow.md`
(T-085 - the system-leg flow: who mints the recurring live-in-Eclipse
acceptance ticket, when, and how non-blocking is guaranteed), verified by
`docs/live-in-eclipse-recurrence-flow.tests.md` (T-086). The design leg pairs
as `docs/live-in-eclipse-recurrence-design.md` + `docs/live-in-eclipse-recurrence-design.tests.md`
(T-119/T-120), and `docs/live-in-eclipse-recurrence-architecture.md` (T-107)
carries the component structure with no `.tests` sibling of its own.

### Cluster: intake-seat (6 files)

**Family map.** The leading file is `docs/intake-seat-system.md` (T-083) - the
governing system definition every sibling names as its basis - verified by
`docs/intake-seat-system.tests.md` (T-084). This is the family where the paired
shape is easiest to see, because it is complete:
`docs/intake-seat-architecture.md` + `docs/intake-seat-architecture.tests.md`
(T-105/T-106) and `docs/intake-seat-design.md` +
`docs/intake-seat-design.tests.md` (T-115/T-116), three definition legs each
with its verification sibling.

## The chat-surface inventories

One subject - what the Eclipse chat shows compared with the opencode TUI - seen
from three angles, so the three overlap on purpose:

- `docs/chat-parity-requirements.md` (T-034) = **capability**: which TUI
  capabilities the chat must match, as testable requirements.
- `docs/tui-visible-surface-review.md` (T-148) = **visible status**: what a
  user can SEE at a glance in the TUI and where each item stands in the chat.
- `docs/TESTING.md`'s "What to test" section (section 2 as of this writing) =
  **executable rows**: the same surfaces as a checklist a human runs against a
  build, each row naming the shipped ticket it verifies.

Three related single-ticket records sit beside them:
`docs/model-detail-system.md` (T-159, the chat model selector's detail line),
`docs/usage-limits-system.md` (T-157, budgets/spent/left per model) and
`docs/session-details-context-architecture.md` (T-163, the Session Details
context block).

## The stage-pipeline records

The pipeline that the six clusters run on has its own record family:

- `docs/design-stage-pipeline.md` - the **rationale**: why stationary tickets,
  basis links, one ticker-v pass and an engine-owned verdict (U-110's design;
  status: implemented - see its About block).
- `docs/requirements-stage-pipeline.md` - the **requirements**: the 23 EARS
  requirements the design derives from (the U-110 arc's requirements leg).
- `.opencode/skills/stage-pipeline/SKILL.md` - the **procedure**: what a tick
  actually does, step by step (with `ticker-setup.ps1` as its executable half).
- `.opencode/docs/contracts.md` - the **fields**: the single source of truth
  for ticket shape, states, tool verbs and review semantics.
- `AGENTS.md` - the **doctrine**: how the whole system is operated, including
  the V-enforcement and escalation rules.

## Single-ticket records

Three V records that never grew a family (they are listed above with the chat
inventories): `docs/model-detail-system.md`, `docs/usage-limits-system.md`,
`docs/session-details-context-architecture.md`.

## Requirements records

`docs/requirements/` holds five older requirements definitions in the same
About-block shape, kept together because they predate the current stage-tagged
titles: `docs/requirements/B-007-stage-aware-acceptance.md`,
`docs/requirements/U-026-v-flow-visibility.md`,
`docs/requirements/U-027-readiness-consults-archive.md`,
`docs/requirements/U-028-board-polish-pack.md`,
`docs/requirements/U-029-stage-pass-through.md`.

## Guides and reference

Documents a person reads to operate or understand the harness:

- `docs/own-project.md` - running the harness on your own project.
- `docs/new-machine-setup.md` - bringing a fresh machine up.
- `docs/TESTING.md` - building, deploying and exercising the current build.
- `docs/eclipse-deploy-recovery.md` - the deploy/install recovery runbook.
- `docs/feature-matrix.md` - the live inventory of shipped features.
- `docs/emoji-vocabulary.md` - the one canonical emoji mapping per concept.
- `docs/spice-alignment.md` - how the process model maps onto SPICE/ASPICE.

## Dated records

Evidence written on a specific date. Read them as history - their judgments
were true of the tree on that day, and later tickets may have overtaken them:

- `docs/competitive-landscape.md` - market-position snapshot, 2026-10-07.
- `docs/eclipse-gui-review-2026-11-15.md` - post-fleet GUI element inventory.
- `docs/w008-reverification-2026-11-15.md` +
  `docs/w008-reverification-pass-2026-11-15.md` - a pair: the FAILED
  re-verification and the evidence record of the PASS that followed.
- `docs/intake-agent-evaluation.md` - the T-036 evaluation of enforcing the
  intake boundary by role; still cited as a governing definition.
- `docs/research-epic-decomposition-2026-11.md` - research into epic
  decomposition across Scrum/SAFe/Jira/GitLab/GitHub.

## The adoption matrix

`docs/opencode-v2-adoption.md` carries two lifetimes in one file: durable
**policy** (the strict reuse policy - what the harness adopts from opencode's
v2 surface and what it deliberately rebuilds) and **as-of records** (each
stamped with its date). Beside it, the 17 `opencode-v2-surface-*.txt` snapshots
(one per cross-checked opencode version, newest `docs/opencode-v2-surface-2.4.2.txt`)
are the diff history the matrix's as-of claims were verified against.

## Retired fleet documents

The fleet (the harness's old headless dispatch machinery) was removed in the
U-110 arc on 2026-11-14. Its four documents remain as **pointers** - each keeps
its H1, what it was, when and why it was retired, where its successor lives and
its original record date, while the dead procedure bodies were removed on
2026-11-26 (they survive in git history, `git log -- <file>`):

- `docs/fleet-quickstart.md` - the old headless-dispatch how-to; successor: the
  linked stage pipeline (`ticker-v` + the stage-pipeline skill).
- `docs/fleet-job-activity.md` - the old job-activity tool reference;
  successor: readiness and store history (`task_readiness`, ticket history).
- `docs/fleet-observability-eclipse-reuse.md` - the as-built observability
  record; its still-current part (Session Details rendering, MessageConsole
  reuse) moved to `eclipse/ARCHITECTURE.md` before the body was reduced.
- `docs/fleet-redundancy-review-2026-11.md` - deliberately **kept whole** as
  dated research evidence, labelled "SUPERSEDED BY U-110" at the top.
- (Outside this directory: `eclipse/DISTRIBUTED-FLEETS.md` - the parked
  multi-machine mode, likewise reduced to a pointer.)
- `docs/fleet-smoke-marker.txt` - a one-line marker from the fleet era
  ("fleet smoke ok"); no in-repo consumer remains, kept as history.

## Ground rules for this directory

- **V records are never merged or renamed.** Ticket artifact references pin
  exact paths (a criterion asserts the artifact "exists at the recorded
  path"), and `ReleaseCadenceContractTest` reads a record's literal
  text. Duplication inside a family is the paired-verification structure; the
  fix for navigability is orientation - this index and the family maps - not a
  rename.
- **Dates are part of a record's meaning.** Dated records keep their dates;
  the adoption matrix keeps policy and as-of snapshots visibly apart.
- **Every document opens with an About block** (Kind / Read by / Related), so
  any reader can take their bearings without asking anyone.
- **This index is orientation, not status.** For current state read
  `ROADMAP.md`; for the sequence of what happened read `docs/HISTORY.md`.
