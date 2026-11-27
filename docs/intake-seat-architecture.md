# Product-owner seat — architecture (T-105)

## About this document

- **Kind:** `doc` / architecture-stage artifact of ticket T-105 (basis T-083, stage `architecture`): the internal structure of the
  product-owner seat integration — components, per-component responsibilities
  and explicit dependency rules, derived from the governing system definition.
- **Read by:** the design stage (this is its basis — what the design of each
  component must uphold), the reviewer (traceability check against the system
  doc), and any agent touching the seat's wiring or the chat-first control
  plane.
- **Related:** `docs/intake-seat-system.md` (T-083 — the governing system
  definition, read-only basis); `docs/design-stage-pipeline.md` (the machine's
  design — pass-through, not re-derived here); `.opencode/agent/product-owner.md`
  (the seat definition — reference for reality); the Eclipse chat bundle
  (`OpenChatHandler`, `ChatView`, `SelectorGuard`, `ServerView`,
  `SessionViewIds` — dispatch-surface reality); `AGENTS.md` §Chat-first
  control plane.

## 1. Chosen pattern

Evaluated against what the system doc fixes (§1 of
`docs/intake-seat-system.md`: the seat feeds the machine and never receives
work from it; the machine is store-mediated):

- **Layered (N-tier):** fits the *hosting* (Eclipse bundles, opencode) but not
  the *coordination* — the seat is not a layer above the machine; there is no
  call direction between them, only the store.
- **Client-server / SOA / microservices:** wrong shape — one process
  boundary (opencode host + Eclipse host), no services to decompose.
- **Event-driven:** the machine is *polled*, not evented — the pipeline design
  fixes "the tick boundary is the only synchronization point" and "no stage
  calls another".
- **Repository (shared data store):** fits exactly — AGENTS.md: every agent
  is a *peer* that reads and writes the store, no agent owns or messages
  another; the pipeline design: "stages are decoupled through the store —
  never a direct handoff".
- **MVC / CQRS / P2P:** MVC has no view/model split here; CQRS splits
  read/write models the store deliberately does not have; P2P (unstructured)
  is what the store exists to avoid.

**Chosen: shared-repository coordination — the task store as the single
coordination medium — with layered dispatch/hosting surfaces around it.**
Why it fits: every relationship in the system view (seat → store → tickers →
workers → verdicts, and the read-back evidence plane) is a store access, and
the store already carries the invariants (history events, claims, readiness,
done-time guards). What it costs: no push — consumers observe on demand. For
the seat that is free: it is human-paced, so polling on demand is the natural
access pattern.

## 2. Components

The system doc fixes the parts (§1: four parts, three interfaces; §2 adds the
machine-keeping surfaces). This section records the mapping — where structure
was already fixed, the rationale is pass-through, not new design. One
deliberate refinement: the system doc lumps the store into "the machine";
the architecture splits it out as its own component because **every**
dependency rule below (DR-1, DR-4) targets the store's interface, not the
machine's internals. That is a naming move for the rules' sake, not new
structure.

| # | Component | Reality anchor | Responsibility | Depends on |
|---|---|---|---|---|
| C-1 | **Seat definition** | `.opencode/agent/product-owner.md` (`mode: primary`; `write`/`edit`/`patch`/`bash` denied at role level) | the owner-side loop: classify → distill acceptance criteria → link (`basis`) → register + cite the ticket id → verify from store evidence; records the two autonomy dials on GOAL tickets; states NEEDS-HUMAN honestly | C-3 only |
| C-2 | **Dispatch surfaces** | D-1: opencode TUI `/agents` (primary-agent selector). D-2: Eclipse chat agent combo (`wireGuardedCombo` + `SelectorGuard` in `ChatView`). D-3: Eclipse `openChat` command (`OpenChatHandler`, `agentId` parameter → `ChatView.preselectAgent`), launched from `ServerView` | bring an owner-chosen session up running the seat — deliberately, once per session; never a machine-initiated route | C-1, by name only (the dispatch key) |
| C-3 | **Task store** | `.harness/tasks/hephaestus/` + the `task_*` API | the single coordination blackboard: tickets, sprints, claims, readiness, history events, engine invariants (done-time artifact guard, blocked state machine, write discipline) | — (substrate) |
| C-4 | **Stage-pipeline machine** | `docs/design-stage-pipeline.md`: per-stage tickers (`stage-pipeline` skill), goal-driven workers, the engine's review pass + quality gate | consume intake tickets from C-3, divide + derive down the V, produce engine verdicts back into C-3; spawn capacity and done authority live here | C-3 only |
| C-5 | **Evidence plane** | `task_board` / `task_readiness` / `task_get` / `task_doctor` + git history (`git log`, `git worktree list`, `git branch --contains`) | read-only observation of machine state for two consumers: the seat's verify step (C-1 loop step 5) and the spot-check reviewer (§5) | C-3 + git history, read-only |
| C-6 | **Machine-keeping surfaces** | the `ticker-v` schedule armed from chat via `.opencode/skills/stage-pipeline/ticker-setup.ps1` (the planned Board schedules switch, "Auto/Tickers", was never built — chunk-5 scope; the Board mirrors readiness read-only); the MCP connect endpoint (`POST /api/experimental/mcp/tasks/connect`); a shell-capable steward session for shell-bound duties | the non-shell routes that keep the pipeline alive (tickers armed, tasks server connected) — governance duties of the seat that need no shell | C-4's lifecycle via host endpoints, not via the store |

## 3. Dependency rules (explicit)

- **DR-1 — The seat touches the machine through the store interface only.**
  C-1's machine contact is exclusively the `task_*` API of C-3. It holds no
  dependency on C-4's internals — ticker schedules, spawn caps and worker
  sessions are invisible to the seat (and the seat is invisible to them).
- **DR-2 — No machine → seat control edge.** Nothing dispatches TO the seat
  (system doc invariant 4): the opt-in launcher — the `ticker-v` schedule,
  armed from chat via `.opencode/skills/stage-pipeline/ticker-setup.ps1` —
  launches sprint tickets per the
  stage → role → skill mapping (worker roles only); the only inbound edges to
  C-1 are owner-initiated through C-2. The overall direction is therefore
  acyclic: owner → seat → store → machine → verdicts → store → evidence →
  read-back — and read-back (C-5) is observation, never command.
- **DR-3 — Dispatch by name.** C-2 surfaces depend on the seat's agent *name*
  (the dispatch key), never on each other or on C-1's content. D-2 and D-3
  share the Eclipse host, but the D-3 path crosses bundles as a command-id
  *string* (`SessionViewIds.CHAT_OPEN_COMMAND_ID` lives in the core bundle;
  B-017 "one seam") — no compile dependency between bundles.
- **DR-4 — The evidence plane is read-only for its consumers.** Seat
  verification and spot-check queries read C-3 and git history; store writes
  go through the `task_*` tools only (the write discipline bans hand
  *writes*, not reads — grepping the store files is a legal read).
- **DR-5 — The seat holds no shell.** Where a duty is unavoidably shell-bound
  (boot-time `ticker-setup.ps1`), the seat directs a shell-capable steward
  session (C-6) — the duty is the outcome (tickers armed), not the tool.
- **DR-6 — No concrete provider or model id in committed content (D-004).**
  Model identity enters the system only through runtime surfaces: the setup's
  session default, a *local* frontmatter override outside the committed
  definition, or the ticket-level `model` field (§4).
- **DR-7 — Verdicts are engine-owned.** No component here may set `done` on a
  ticket its session did not fulfill (invariant 2), may spawn fulfillment
  workers (invariant 3 — spawn capacity belongs to C-4's tickers), and the
  spot-check (§5) produces candidate lists — the verdict stays with the
  reviewer.

## 4. Model resolution (a cost lever, D-004-safe)

Pass-through of system doc §3, recorded here as the resolution chain each
seat session walks:

1. **Committed definition: no model.** `.opencode/agent/product-owner.md`
   carries no `model:` frontmatter — the seat runs on the setup's session
   default (verified: the frontmatter names only `description`, `mode`,
   `tools`).
2. **Local override (optional, uncommitted).** A setup that wants a fixed
   seat model adds a frontmatter override *locally*, with a
   `<provider>/<model>[#variant]` value — outside the repository (DR-6).
3. **Ticket-level lever (the seat's real cost influence).** At registration
   the seat may set the ticket's `model` field (`task_create`/`task_update`)
   — small well-specified intake tickets deserve cheap models; the seat
   prices downstream work at the moment it creates it.
4. **Tier position.** The seat's loop is structured store work, not deep
   reasoning: `low`–`mid` tier expected per the tier-selection rule (lowest
   tier whose criteria satisfy the task); escalate to `high` when a
   registration needs real distillation judgment; never de-escalate when
   uncertain. Session-level cost is observable after the fact (fleet
   intelligence gathering), so the tier choice is auditable.

Eclipse-side reality (verified in the chat bundle): agent and model selection
are separate guarded combos on the selector row, and the `openChat` command
carries `providerId` / `modelId` / `sessionId` / `agentId` parameters —
so a dispatch surface can preselect an agent without touching model
resolution at all.

## 5. Spot-check mechanization (S-1/S-2/S-3 → C-5)

The evaluation's spot-check rule passes through mechanized as three
concrete, **read-only** queries over a review window `[t0, t1]`, all landing
on the evidence plane (C-5). Queries are specified, not scripted — a checking
script is its own ticket with its own tests (system doc §7).

- **Identity whitelist** (all three signals; pass-through, recorded
  unchanged): fleet identities (`ticker-v:*` ticket-scoped sessions),
  `owner-directive` comments, engine/null created-events, recorded
  coordination sessions (reviewer, PM). Anything else on an implementation
  lane is a candidate.
- **S-1 — Registration coverage** → C-5 store query: implementation-lane
  tickets (`stage: implementation` or `test-*`) updated in the window with
  empty `basis:` and no owner-directive/fleet provenance; plus C-5 git query:
  window commits touching implementation lanes whose message cites no ticket
  id (`\b[TU]-\d+\b`), every cited id resolved via `task_get`.
- **S-2 — Provenance by-fields** → C-5 store query: per window ticket,
  `history[].by` and `artifacts[].by` — flag any value outside the whitelist
  on an implementation lane, in particular any bare chat-session id.
- **S-3 — Merge-back shape** → C-5 git topology query: `git worktree list`
  gives the whitelist of merge sources; a window commit reachable only on the
  main line, contained by no worktree branch, carrying no ticket reference,
  is a direct-commit candidate.
- **Cadence and meaning** (pass-through): run at every human review that
  accepts a release (the T-041 standing rule) and at retros; a hit means
  rework through the store plus a doctrine reminder — never a silent pass.
  All three produce candidate lists only (DR-7).

## 6. Key quality attributes

- **QA-001 — Enforced intake boundary** (why: the 2026-11-14 violations are
  the failure class this structure exists to prevent). Preventive layers:
  role-level tool denials in C-1 (structural — invariant 1), deliberate-pick
  dispatch in C-2 (no accidental seat entry), AGENTS.md doctrine as the
  second layer for unrestricted plain sessions (invariant 6, the honest
  residual gap). Detective layer: S-1/S-2/S-3 over immutable evidence (§5).
- **QA-002 — Auditability.** Every store change leaves a history event with
  provenance (`by` fields); verdicts are engine-owned (DR-7); the spot-check
  reads only append-immutable surfaces (store history, git history). The
  structure supports it by making the store the *only* coordination medium —
  nothing happens off the record by construction.
- **QA-003 — Cost observability.** The ticket-level `model` field prices
  work at creation (§4); session-level cost is readable per session; no
  committed ids (DR-6) keep the lever D-004-safe.

## 7. Architecture decisions

- **AD-001: Shared-store coordination over direct agent messaging.** The
  store is the single coordination blackboard; every peer relationship is a
  store access. Trade-off: no push — consumers observe on demand; accepted
  because all consumers are either tick-bound (C-4) or human-paced (C-1).
- **AD-002: The seat stands outside the pipeline.** No `stage:`, never a
  dispatch target, no spawn rights (invariants 3–4). Trade-off: the seat's
  machine-keeping duties need the non-shell routes or a steward session
  (C-6, DR-5).
- **AD-003: The seat's name is the dispatch key everywhere.** D-1 selector,
  D-2 combo, D-3 `agentId`, AGENTS.md references, and the S-2 identity
  whitelist all key on the name. Trade-off: a rename (Q-1, §8) touches every
  dispatch surface — but "no code depends on the name": one rename plus a
  docs touch.
- **AD-004: Guarded commit semantics for agent switching.** The agent combo
  reuses the model-selector guard (`SelectorGuard`): a pick commits only when
  the dropdown was explicitly opened (mouse-down) or Enter was pressed;
  un-armed pointer/wheel drift reverts to the committed value. Trade-off: one
  deliberate gesture to switch; prevents wheel-drift flipping the seat in or
  out (user report 2026-09-16).
- **AD-005: `openChat` as a string command-id seam.** The Server view
  launches a seat chat (`agentId` parameter) through
  `com.opencode.ide.chat.openChat` without a compile dependency between
  bundles (B-017's "one seam"). Trade-off: the parameter names are an untyped
  contract — pinned by the core-bundle test that asserts the constant.
- **AD-006: Model resolution deferred to runtime surfaces.** Nothing about
  *which* model serves the seat is committed (DR-6, §4). Trade-off: the
  effective seat model is per-setup and auditable only after the fact; the
  alternative (a committed default) violates D-004.
- **AD-007: The spot-check stays a candidate-list machine.** Mechanization
  produces read-only candidate lists; the verdict is the reviewer's (DR-7).
  Trade-off: reviewer time per release; automated verdicts would break the
  engine-owned-verdict principle the whole harness rests on.

## 8. Open questions

- **Q-1 — intake vs product-owner naming: OPEN, owner decision.** Pass-through
  of system doc §5, unchanged by this architecture: the seat was created as
  `intake` (owner override, commit `7aab59b`), renamed to `product-owner`
  (commit `55f2823`). "intake" names the function (FR-2), "product-owner"
  names the seat/persona. Both are one rename plus a docs touch; no code
  depends on the name (AD-003's blast radius is the dispatch surfaces, the
  governing docs, and the identity whitelist if the session-label convention
  moves with it). This architecture does not resolve Q-1; the decision
  request stands with the owner.

## 9. Known-defect disposition (store-path cite)

The dispatch brief for this ticket reported a non-blocking defect: system doc
§4 would cite the store path `.opencode/tasks/hephaestus` where ground truth
is `.harness/tasks/hephaestus`. **Ground truth at HEAD: already fixed
upstream.** The committed system doc reads `.harness/tasks/hephaestus/*.md`
(§4, the spot-check read instructions); the correction landed with T-084's
review rework — commit `97ea96e`, "T-084 review rework: the store-path
references corrected (.harness/tasks) + the path defect recorded as note
five" — before this ticket was created. Verified by `git log` on the file and
a text search of the current content (evidence, not intent). No coherence-pass
work remains for the path cite itself; this document uses the ground-truth
path throughout (C-3's reality anchor).

## 10. Traceability matrix (T-105 AC-2)

Every integration element of `docs/intake-seat-system.md` → its component(s)
here → the evidence it rests on:

| System element (intake-seat-system.md) | Component(s) | Evidence |
|---|---|---|
| §1 system view + invariants 1–6 | C-1..C-6; DR-1..DR-7 | this doc §2–§3; seat frontmatter (`mode: primary`, tool denials); AGENTS.md intake-discipline paragraph |
| §2 D-1 — TUI `/agents` route | C-2 | `mode: primary` in the seat definition (primary-agent selector) |
| §2 D-2 — guarded agent combo | C-2; AD-004 | `ChatView.wireGuardedCombo` + `SelectorGuard` (arm on mouse-down/Enter, drift reverts) |
| §2 D-3 — `openChat` `agentId` path | C-2; AD-005 | `OpenChatHandler` (reads `agentId`, calls `preselectAgent`); `ChatView.preselectAgent` → `selectors.selectAgent`; `ServerView` passes `agentId` = the v2 id; `SessionViewIds.CHAT_OPEN_COMMAND_ID` (core bundle, one seam) |
| §2 guarded switching semantics | C-2; AD-004 | `SelectorGuard` commit rule: opened-dropdown pick or Enter commits, un-armed drift reverts |
| §2 default-agent miss → doctrine layer | C-1 + invariant 6 | system doc §2; AGENTS.md doctrine paragraph; §5 here as the detective control |
| §2 machine-keeping dispatch (bash-denied seat) | C-6; DR-5 | `ticker-v` armed from chat via `ticker-setup.ps1` (no Board switch — chunk-5, never built); `POST /api/experimental/mcp/tasks/connect`; steward-session rule |
| §3 no committed model (D-004) | C-1; DR-6; AD-006 | seat frontmatter carries no `model:`; D-004 |
| §3 tier position | C-1 | tier-selection rule (lowest sufficient tier; never de-escalate uncertain) |
| §3 ticket-level model lever | C-1 + C-3 | `task_create`/`task_update` `model` field; fleet intelligence reads session cost |
| §4 identity whitelist | C-5 | pass-through, recorded unchanged (§5) |
| §4 S-1 — registration coverage | C-5 | store query (basis-empty implementation-lane tickets) + git query (`\b[TU]-\d+\b`, ids resolved via `task_get`) |
| §4 S-2 — provenance by-fields | C-5 | `history[].by` / `artifacts[].by` reads vs the whitelist |
| §4 S-3 — merge-back shape | C-5 | `git worktree list` (merge-source whitelist) + `git branch --contains` |
| §4 cadence (T-041 reviews, retros) | C-5 | T-041 standing rule; hits → rework + doctrine reminder |
| §5 Q-1 — naming decision | **OPEN** | §8 here: status, decision request, blast radius — nothing resolved |

## 11. Acceptance criteria mapping (T-105)

- **AC-1** (architecture doc with component structure, per-component
  responsibilities and explicit dependency rules derived from the system
  doc): this document — §1 pattern, §2 components, §3 dependency rules; the
  one refinement (store split out of the machine) is recorded with its
  rationale in §2.
- **AC-2** (every integration element traces through components to evidence;
  Q-1 stays OPEN; the store-path defect noted): §10 traceability matrix, §8
  Q-1, §9 defect disposition (reported; found already fixed upstream —
  recorded, not re-fixed, and the system doc was not touched).
- **AC-3** (artifact recorded on the ticket before in-review; done never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`) before
  the `in-review` status update; `done` is the engine's verdict.

## 12. Lane and non-goals

This ticket's lane is one new doc file (`docs/intake-seat-architecture.md`).
Not touched: `docs/intake-seat-system.md` (including the already-fixed cite —
§9), `docs/design-stage-pipeline.md`, `AGENTS.md`,
`.opencode/agent/product-owner.md`, any code, any skill. No concrete
provider or model ids anywhere (DR-6/D-004). The spot-check queries pass
through as specified — scripting them is not this lane. Component *design*
(interfaces, data structures, workflows inside each component) belongs to the
design stage; this document fixes only boundaries, responsibilities and
dependency direction.
