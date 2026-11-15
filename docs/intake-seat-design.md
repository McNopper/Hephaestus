# Product-owner seat — component design (T-115)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket T-115 (epic U-112, basis
  T-105, stage `design`): the inside of the product-owner seat integration —
  component contracts (inputs → outputs | errors), data structures, key
  workflows and error behavior for the architecture's components C-1..C-6.
- **Read by:** the implementation stage (its basis — what gets built or
  verified per component), the `test-design` stage (component tests against
  these contracts), and the reviewer (traceability check against the
  architecture and the system doc).
- **Related:** `docs/intake-seat-architecture.md` (T-105 — the governing
  architecture: boundaries, C-1..C-6, DR-1..DR-7, AD-001..AD-007, read-only
  basis); `docs/intake-seat-system.md` (T-083 — the system definition whose
  invariants 1–6 every contract here preserves); `docs/design-stage-pipeline.md`
  (the machine's own design — pass-through basis for C-4);
  `.opencode/agent/product-owner.md` (the seat definition — C-1's reality);
  `AGENTS.md` §Chat-first control plane (the doctrine layer, invariant 6).

## 1. Design scope

The architecture (T-105) fixed **boundaries**: components, responsibilities,
dependency direction (DR-1..DR-7). This document designs the **inside** of each
component — what it takes in, what it returns, how it fails — per the
`software-design` skill. Where the inside is already designed elsewhere, this
document records an **explicit pass-through** and adds nothing:

- **C-3 (task store):** the contracts are the engine's own (history events,
  claims, readiness, done-time artifact guard, blocked state machine) — the
  architecture already treats the store as substrate (AD-001); redesigning it
  here would contradict the write-discipline boundary.
- **C-4 (stage-pipeline machine):** `docs/design-stage-pipeline.md` §4–§6
  **is** the design (the tick algorithm, the fulfillment loop, the stage-skill
  parameterization). DR-1 keeps the seat out of it; nothing to add.

Contract notation throughout: `name(inputs) -> output | errors`. Design
decisions made at this stage are recorded as **DS-001..DS-007** (§3, and at
their point of use).

## 2. Component contracts

### C-1 — the seat loop (product-owner)

Reality anchor: `.opencode/agent/product-owner.md` (`mode: primary`;
`write`/`edit`/`patch`/`bash` denied at role level). The architecture assigns
it the owner-side loop (classify → distill → link → register + cite →
verify); its machine contact is exclusively the `task_*` API of C-3 (DR-1).

Contracts (the loop, step-shaped):

- `classify(message: owner input) -> {requirement | bug | process-rule | question}`
  | errors: ambiguous input → a clarification round-trip to the owner, bounded
  at two ("ask before guessing; at most a couple of round-trips" — seat
  definition); past the bound, register with the open questions recorded **on
  the ticket**, never resolved by a private guess.
- `distill(input, clarifications?) -> TicketDraft` | errors: for bugs, the
  owner's wording is quoted and the distillation labeled; unresolved doubts
  ride on the ticket as open questions (the store, not memory, carries them).
- `link(draft, {epic, basis?}) -> TicketDraft` | errors: unknown `epic` or
  `basis` id → registration halted — no orphan ticket is created; the id goes
  back to the owner for correction.
- `register(draft) -> ticketId` via `task_create` | errors: store API failure
  → surfaced to the owner with the draft text preserved in the reply; a retry
  re-runs `register` from the draft — the seat holds **no state outside the
  store** (DS-006; AD-001's no-push corollary: nothing off the record).
- `price(ticketId, model?) -> void` via `task_update`'s `model` field.
  Optional; the value is a runtime `<provider>/<model>[#variant]` or absent —
  never a committed id (DR-6, system §3). This is the seat's cost lever: work
  is priced at creation.
- `cite(ticketId) -> void` — the id goes back to the owner in the same reply
  (loop step 4). Registration without a citation is an incomplete loop.
- `verifyRegistration(ticketId) -> evidence` via `task_get` | errors: the
  ticket missing or mis-linked → corrected via `task_update`, re-verified.
  The store's answer, not the seat's memory, is the proof.
- `verifyProgress() -> evidence digest` via `task_board`, `task_readiness`,
  `task_get`, `task_doctor` | errors: store unreachable → an honest "cannot
  verify" — status claims cite tool output or are not made.
- `escalate(ticketId, decision-needed) -> void` — records the blocker/decision
  request on the ticket via the `task_*` verbs, so NEEDS-HUMAN lands in the
  owner's queue with what is needed, not how to build it.

**Deliberately absent from the contract surface** (their absence is the
enforcement, DS-001): no `done` transition (DR-7, invariant 2), no worker
spawn (invariant 3 — spawn capacity is C-4's tickers'), no claim of
implementation-lane tickets, no file write or shell verb (invariant 1 — the
role-level denials make these fail closed in the host; a shell-bound duty
routes to C-6 per DR-5). The owner override (invariant 5) is the owner's own
act outside this contract surface — no contract attempts to prevent it; the
seat says "then they are in Build, not you."

### C-2 — dispatch surfaces

The architecture fixes three routes (D-1/D-2/D-3), all keyed on the seat's
agent *name* (DR-3, AD-003). Per route:

- **D-1 — TUI `/agents`:** `selectPrimaryAgent(name) -> session running the
  seat` | errors: name miss → the agent simply is not in the primary selector
  list; no partial session state. `mode: primary` is what lists it (system §2).
- **D-2 — Eclipse guarded agent combo** (AD-004 mechanized, DS-002):
  `commitAgentPick(gesture) -> committed agent | revert`. A two-flag state
  machine over `GuardState` (§4):
  - pointer-down on the dropdown → `armed := true`;
  - `pick(a)` while armed → `committed := a` (the switch happens);
  - `pick(a)` while **not** armed (wheel/pointer drift over the row) → draft
    reverts to `committed` — a no-op, not a switch;
  - `Enter` → arms and commits the current draft in one gesture;
  - focus loss / view disposal → draft reverts.
  Invariant: the agent serving the session changes only through a commit, and
  a commit requires an arming gesture. This is the same commit semantics the
  model selector documents (AGENTS.md; user report 2026-09-16).
- **D-3 — `openChat` command** (AD-005's string seam, DS-003):
  `openChat({agentId?, providerId?, modelId?, sessionId?}) -> chat view` —
  parameters are untyped strings across the bundle seam, pinned by the
  core-bundle test that asserts `SessionViewIds.CHAT_OPEN_COMMAND_ID`.
  errors: unknown `agentId` → the chat opens on the **default** agent
  selection — degrade, do not fail (a stale name from an old Server-view
  listing must never block opening a chat). Model resolution is untouched by
  any route (architecture §4: a surface preselects an agent without touching
  the model).

Error-behavior note shared by all routes: no route in this table is reachable
from the machine (DR-2, invariant 4 — every input is an owner gesture). The
default-agent miss (a requirement arriving in a plain session) is governed by
the doctrine layer, not by C-2: the chat agent registers + cites and does not
implement (system §2; invariant 6; caught by W-3's S-1..S-3 as the detective
control).

### C-3 — task store — explicit pass-through

Substrate (AD-001). Its contracts are engine-owned and out of this design's
scope: tickets, claims, readiness, history events with `by` provenance, the
done-time artifact guard, the blocked state machine, the write discipline.
Every contract above that names a `task_*` verb consumes this substrate as-is;
the seat's write set is exactly the registration/escalation verbs of C-1
(DR-1, DR-7).

### C-4 — stage-pipeline machine — explicit pass-through

`docs/design-stage-pipeline.md` §1–§8 is the machine's design: stationary
tickets, the link graph, per-stage tickers, goal-driven workers, the
engine-owned verdict. This design adds **no** contract for C-4 — the seat
never calls into it (DR-1) and the machine never calls into the seat (DR-2);
their only shared surface is C-3. Recorded here so AC-2's "every element
resolves" is explicit rather than silent.

### C-5 — evidence plane

Read-only observation for two consumers: the seat's verify step (C-1) and the
spot-check reviewer (DR-4: reads may use the `task_*` API or grep the store
files — hand *reads* are legal, hand *writes* are not). Base contracts:

- `boardSnapshot() -> wave kanban` (`task_board`);
- `readiness(ticketId | stage?) -> READY | WAIT_UPSTREAM | STALE | BLOCKED |
  RUNNING | NOT_APPLICABLE` (`task_readiness`);
- `ticketRecord(id) -> {ticket, history[], artifacts[]}` (`task_get`);
- `doctor() -> store integrity findings` (`task_doctor`);
- `commitsIn(window) -> [{sha, subject, touched lanes}]` (`git log`);
- `mergeSources() -> worktree-branch whitelist` (`git worktree list`);
- `branchesContaining(sha) -> [branch]` (`git branch --contains`);
- `isWhitelisted(by) -> bool` — the identity whitelist as **one** shared
  predicate (DS-005): fleet identities (`ticker-v:*` ticket-scoped sessions),
  `owner-directive` comments, engine/null created-events, recorded
  coordination sessions (reviewer, PM). Pass-through of system §4, unchanged.

Spot-check query contracts (S-1/S-2/S-3 mechanized per architecture §5; all
produce **candidate lists only**, DS-004):

- `s1_registrationCoverage(window: SpotCheckWindow) -> Candidate[]` —
  store side: implementation-lane tickets (`stage: implementation` or
  `stage: test-*`) updated in the window with empty `basis:` and no
  owner-directive/fleet provenance; git side: window commits touching
  implementation lanes whose message cites no ticket id (pattern
  `\b[TU]-\d+\b`), every cited id resolved via `ticketRecord` — an
  unresolvable reference **is a Candidate**, not an exception.
- `s2_provenanceByFields(window) -> Candidate[]` — per window ticket,
  `history[].by` and `artifacts[].by`; flag any value outside
  `isWhitelisted` on an implementation lane, in particular any bare
  chat-session id.
- `s3_mergeBackShape(window) -> Candidate[]` — a window commit reachable only
  on the main line, contained by no worktree branch (`branchesContaining`),
  carrying no ticket reference → direct-commit candidate.

errors: none of the three throws on a hit — hits **are** the output. Read-only
by construction: no contract in C-5 writes anything (DR-4). The queries are
specified, not scripted — a checking script is its own ticket with its own
tests (system §7, architecture §12).

### C-6 — machine-keeping surfaces

The non-shell routes that keep the pipeline alive (architecture §2 C-6; the
bash-denied seat's governance duties, system §2). Contracts are specified as
**outcomes**, verified by read-back — never by tool identity (DS-007, DR-5):

- `setTickers(armed: bool) -> armed-state` — the Board schedules switch
  (Auto/Tickers, host UI surface). Read-back: the schedules' state as shown by
  the host.
- `reconnectTasksServer() -> connection state` — the MCP connect endpoint
  (`POST /api/experimental/mcp/tasks/connect`). Read-back: the `task_*` tools
  answering again.
- `delegateSteward(duty) -> outcome` — a shell-capable steward session runs a
  shell-bound duty (boot-time `ticker-setup.ps1`); the seat specifies the
  **outcome** (tickers armed) and verifies it by read-back, not by having run
  the tool.

errors: a route unavailable (host down, endpoint failing) → the duty stays
**unmet** and is surfaced honestly — which duty, which decision or act is
needed (NEEDS-HUMAN). A shell-bound duty with no steward available is
escalated the same way; the seat never shells out itself (DR-5) and never
quietly accepts an unverified "done" from a delegate.

## 3. Design patterns used

- **Shared Repository** — chosen upstream (AD-001); pass-through, not
  re-chosen here. Listed because every contract above consumes it.
- **Facade** — C-5 aggregates heterogeneous read surfaces (`task_*` API + git
  plumbing) behind uniform query contracts that all return `Candidate[]` —
  one observation vocabulary for two consumers (seat verify, spot-check).
- **State** — the D-2 guard machine (DS-002): two flags (`armed`,
  `committed`/`draft`) is the smallest honest mechanization of AD-004's
  commit semantics and directly testable.
- Nothing else forced — C-1's loop and C-6's outcome contracts are plain
  functions; a pattern there would be weight, not design.

## 4. Data structures

- `OwnerInput {text, context}` — the raw owner message; never edited, only
  classified and (for bugs) quoted.
- `Classification ∈ {requirement, bug, process-rule, question}` — the
  C-1 `classify` output.
- `TicketDraft` — the `distill` product:
  `{title, description, acceptance_criteria[], kind: staged(stage) | GOAL,
  type: requirement | bug, epic, basis?, model?, dials?}`.
  Invariants: acceptance criteria are testable statements; `epic` per the
  active goal (U-112 while open); `basis` when it derives from an existing
  ticket; `model` is a runtime placeholder value or absent (DR-6); `dials`
  (goal horizon, review cadence) present only on GOAL roots and recorded on
  the ticket — never as acceptance criteria (seat definition, U-105).
- `SpotCheckWindow {t0, t1}` — the review window for S-1..S-3.
- `Candidate {signal: S-1 | S-2 | S-3, subject: ticketId | sha, reason}` —
  the only output type of the spot-check contracts (DS-004). No verdict field
  exists anywhere in C-5's data (DR-7, AD-007).
- `GuardState {committed: agent-name, draft: agent-name, armed: bool}` —
  D-2's machine state; invariant: `committed` changes only on an armed commit
  or Enter; `draft` reverts to `committed` on un-armed input and focus loss.

## 5. Key workflows

Only the non-obvious flows; each cites the dependency rules it upholds.

**W-1 — Intake registration** (owner message → verified ticket):

1. `classify` (C-1) — requirement / bug / process-rule / question.
2. Ambiguous → bounded clarification round-trips to the owner.
3. `distill` → `TicketDraft` (testable criteria; bugs quote the owner's
   wording, distillation labeled).
4. `link` — `epic` (U-112 while open), `basis` when derivational.
5. `register` via `task_create`; `price` via the `model` field (runtime value
   only — DR-6).
6. `cite` the id back; `verifyRegistration` via `task_get` — the store is the
   proof.

Upheld: DR-1 (the only machine contact is the `task_*` API), DR-6, DR-7 (the
ticket enters the backlog — verdicts come later, from the engine).

**W-2 — Machine-keeping** (tickers armed, tasks server connected):

1. Duty arrives → pick the non-shell route first (`setTickers`,
   `reconnectTasksServer` — C-6).
2. Shell-bound duty → `delegateSteward`, outcome specified (DR-5).
3. Read-back verification of the outcome — never trust of the tool run.
4. Unmet duty → honest NEEDS-HUMAN surface: which duty, what is needed.

Upheld: DR-5, DR-1 (no C-4 internals touched — outcomes only), AD-002 (the
seat stays outside the pipeline it keeps alive).

**W-3 — Spot-check at release review** (the detective layer):

1. Reviewer fixes `SpotCheckWindow` — at every human review that accepts a
   release (T-041 standing rule) and at retros.
2. `s1`/`s2`/`s3` run over C-5 → three independent `Candidate[]` lists.
3. Verdict per candidate is the **reviewer's** (DR-7); a hit means rework
   through the store plus a doctrine reminder — never a silent pass.

Upheld: DR-4 (read-only evidence), DR-7/AD-007 (candidates, not verdicts).

**W-4 — Dispatch to the seat** (deliberate entry, once per session):

1. Owner picks the seat **by name**: D-1 selector, D-2 guarded combo
   (commit requires the arming gesture), or D-3 `openChat` preselect.
2. No machine-initiated route exists (DR-2/invariant 4): the Auto ▶
   dispatcher maps stages → worker roles only; nothing addresses the seat.
3. Default-agent miss (a requirement lands in a plain session) → the doctrine
   layer governs (invariant 6): the chat agent registers + cites, does not
   implement, may suggest the switch; work performed anyway is a process
   violation caught by W-3 and re-enters through the store.

Upheld: DR-2, DR-3 (name-only dependence between C-2 and C-1), AD-003/AD-004.

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| Ambiguous owner input | bounded clarification (≤2 round-trips), else register with open questions on the ticket |
| Store API failure during `register` | owner surfaced with the draft preserved; retry from the draft; no local queue (DS-006) |
| Unknown `epic`/`basis` at `link` | registration halted — no orphan ticket; id returned for correction |
| Unknown `agentId` on D-3 | chat opens on default agent selection — degrade, don't fail (DS-003) |
| Un-armed drift on D-2 | revert to committed agent — not a switch (DS-002) |
| Store unreachable during verify | honest "cannot verify"; no cached status claims |
| Shell-bound duty, no steward available | duty unmet → NEEDS-HUMAN surfaced (DR-5) |
| Spot-check hit | rework through the store + doctrine reminder — never a silent pass |
| Unresolvable ticket cite in a commit (S-1) | `Candidate`, not an exception |
| Verify flags a mis-linked registration | correct via `task_update`, re-verify via `task_get` |

## 7. Dependency-rule compliance map

Every rule of the governing architecture (DR-1..DR-7) and the contract or
workflow that upholds it:

| Rule | Upheld by |
|---|---|
| DR-1 (store-interface-only machine contact) | C-1's verbs are exclusively `task_*`; W-1/W-2; no contract names a C-4 internal |
| DR-2 (no machine → seat edge) | W-4; C-2's inputs are owner gestures only; no contract admits a machine-initiated dispatch |
| DR-3 (dispatch by name) | C-2's contracts key on the agent name; D-2/D-3 share the host but no contract (the string seam, DS-003) |
| DR-4 (evidence plane read-only) | every C-5 contract reads; the write verbs live only in C-1's registration/escalation set |
| DR-5 (the seat holds no shell) | no shell verb in any C-1 contract; C-6 `delegateSteward`; invariant-1 denials fail closed |
| DR-6 (no committed provider/model ids) | `price`/`TicketDraft.model` carry runtime placeholder values only; §4's data structures add no ids |
| DR-7 (verdicts are engine-owned) | DS-001 (no `done`/spawn/claim verbs in C-1); DS-004 (`Candidate` has no verdict field); W-3 step 3 |

## 8. Traceability matrix (T-115 AC-2)

Every architecture element (T-105) → its design resolution here:

| Architecture element | Design resolution |
|---|---|
| C-1 seat loop | §2 C-1 contract set (DS-001, DS-006) |
| C-2 dispatch wiring D-1/D-2/D-3 | §2 C-2 route contracts |
| Guarded switching (AD-004) | §2 C-2 D-2 guard machine (DS-002); `GuardState` §4 |
| Model resolution (arch. §4) | explicit pass-through (a runtime configuration chain: session default → local override → ticket-level lever → tier position) + the one contract touchpoint, `price` in C-1 |
| C-3 task store | explicit pass-through — substrate; engine-owned contracts (§1, §2 C-3) |
| C-4 stage-pipeline machine | explicit pass-through — `docs/design-stage-pipeline.md` §4–§6 is the design; DR-1/DR-2 keep both directions out (§2 C-4) |
| C-5 evidence plane | §2 C-5 base contracts (Facade, §3) |
| Spot-check S-1/S-2/S-3 | §2 C-5 query contracts + `Candidate`/whitelist (DS-004, DS-005) |
| C-6 machine-keeping surfaces | §2 C-6 outcome contracts (DS-007) |
| Q-1 naming decision | §9 — **OPEN**, unchanged |

Consistency with the system doc (T-115 AC-2's "no contract contradicts"):
each contract above cites the invariant or system section it preserves —
invariant 1 (C-1's absent verbs + C-6's steward route), 2 and 3 (DS-001),
4 (W-4), 5 (C-1's override note), 6 (W-4 step 3 + W-3 as detective control),
the evidence-plane verb list (C-5 base contracts), the whitelist (DS-005,
pass-through), the two dials (`TicketDraft.dials`), the cost lever (`price`),
and the T-041 cadence (W-3).

## 9. Q-1 — intake vs product-owner naming: OPEN

**Status: OPEN — owner decision. Pass-through of system §5 and architecture
§8, unchanged by this design.** Nothing here resolves it.

What the design contributes: every contract treats the seat's name as a
**value** (the dispatch key of DR-3), never as a contract shape. A rename is
therefore data + docs: the dispatch surfaces' selector entries, the governing
docs' references, and the `isWhitelisted` labels if the session-label
convention moves with it — no contract signature changes (matching AD-003's
blast-radius statement). The decision request stands with the owner: keep
`product-owner` (current state, zero churn) or rename to `intake`
(function-exact).

## 10. Acceptance criteria mapping (T-115)

- **AC-1** (design artifact with component contracts — inputs/outputs/error
  behavior named; workflows consistent with DR-1..DR-7): this document —
  §2 contracts in `name(inputs) -> output | errors` form, §4 data structures,
  §5 workflows with their DR citations, §6 error behavior, §7 the explicit
  DR-compliance map.
- **AC-2** (every architecture element resolves to a contract or an explicit
  pass-through; no contradiction with the system doc; Q-1 stays OPEN): §8
  traceability matrix (C-1..C-6, dispatch wiring, guarded switching, model
  resolution, S-1/S-2/S-3 — each resolved or passed through explicitly, with
  the system-doc consistency citations), §9 Q-1 OPEN.
- **AC-3** (artifact recorded on the ticket before in-review; done never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`) written
  before the `in-review` status update; `done` is the engine's verdict.

## 11. Lane and non-goals

This ticket's lane is one new doc file (`docs/intake-seat-design.md`). Not
touched: `docs/intake-seat-architecture.md`, `docs/intake-seat-system.md`,
`docs/design-stage-pipeline.md`, `AGENTS.md`,
`.opencode/agent/product-owner.md`, any code, any skill. No concrete provider
or model ids anywhere (DR-6/D-004). The spot-check queries stay specified, not
scripted; component *construction* (the code that realizes these contracts)
belongs to the implementation stage; the contracts' verification belongs to
`test-software-design`. Downstream hand-off: implementation derives from §2/§4;
`test-design` tests against §2 contracts and §6 error behavior.
