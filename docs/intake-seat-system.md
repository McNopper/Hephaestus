# Product-owner seat — system integration (T-083)

## About this document

- **Kind:** `doc` / system-stage artifact of ticket T-083 (epic U-112, basis
  T-036, stage `system`): the integration model for the product-owner seat —
  dispatch wiring, agent switching, model/cost position, and the mechanized
  spot-check rule.
- **Read by:** the maintainer (owns Q-1 and the override rule), the PM and the
  stage tickers (what they must NOT dispatch to the seat), any agent touching
  the chat-first control plane, and the reviewer running the spot-check.
- **Related:** `AGENTS.md` §Chat-first control plane (the intake-discipline
  paragraph, line ~321, and the autonomy-spectrum paragraph, line ~156); the
  governing evaluation `docs/intake-agent-evaluation.md` (BUILD recommendation,
  spot-check rule, Q-1); the seat itself `.opencode/agent/product-owner.md`;
  `docs/design-stage-pipeline.md` (the machine the seat feeds); T-036's
  owner-directive comments on the store (the seat exists by owner override;
  the owner CAN enter Build by choice).

## 1. System view

The seat sits between the owner and the machine. Four parts, three interfaces:

```text
owner chat ──> [product-owner seat] ──> task store ──> stage tickers ──> workers ──> engine verdicts
                     │  ^                        │
                     │  └── store evidence       └── Board (schedules switch, Auto ▶)
                     └──── machine-keeping surfaces (non-shell)
```

- **The seat** — `.opencode/agent/product-owner.md`, `mode: primary`, tools
  denied at role level: `write`, `edit`, `patch`, `bash`. Its loop:
  classify → distill acceptance criteria → link (`epic`/`basis`) → register
  and cite the ticket id → verify from store evidence.
- **Dispatch surfaces** — the ways a session comes up running the seat (§2).
- **The machine** — the store, the stage tickers, the workers, the engine
  verdicts (`docs/design-stage-pipeline.md`). The seat feeds it; it never
  receives from it work to do.
- **The evidence plane** — store state (`task_board`, `task_readiness`,
  `task_get`, `task_doctor`) and git history. Both the seat (verification
  step) and the spot-check reviewer (§4) read it; neither writes it by hand.

**Invariants (hold across every route):**

1. Tool denials are role-level — no route the seat runs can edit files or run
   shell commands. Violation requires *leaving* the seat (owner's switch).
2. The seat never moves a ticket to `done` that its session did not fulfill —
   verdicts are the engine's.
3. The seat never spawns fulfillment workers — spawn capacity belongs to the
   tickers (global spawn cap).
4. Nothing in the harness dispatches TO the seat automatically. The Board's
   Auto ▶ dispatcher launches *sprint tickets* per the stage → role → skill
   mapping (worker roles: pm/architect/developer/tester); the product-owner
   seat is a chat-side primary agent, not a dispatch target. The only route
   in is the owner switching to it (§2).
5. Owner override intact: the rule binds the machine and its agents, not the
   PO — the owner can enter Build by choice (T-036 owner-directive comment).
6. Honest residual gap (from the evaluation, kept honest here): role-level
   denial binds only sessions actually running the seat. A plain chat session
   is unrestricted — the AGENTS.md doctrine paragraph is the second layer,
   and the spot-check (§4) is the detective control over both.

## 2. Dispatch wiring

The seat is entered by the owner, on purpose, once per session. Routes:

| # | Route | How it launches the seat | Surface |
|---|---|---|---|
| D-1 | TUI `/agents` | `mode: primary` puts `product-owner` in the primary agent selector; picking it starts (or re-aims) the session as the seat | opencode TUI |
| D-2 | Eclipse chat agent combo | the chat view's agent selector is a **guarded combo**: only an opened-dropdown pick or Enter commits the agent; un-armed pointer/wheel drift reverts (same commit semantics AGENTS.md documents for the model selector) | Eclipse Chat view |
| D-3 | Eclipse `openChat` command | the `com.opencode.ide.chat.openChat` command takes an `agentId` parameter and preselects the agent on a new chat — so the Server view's Agents category can launch a seat chat without a compile dependency between bundles | Eclipse Server view → Chat |

**What happens when a requirement or bug arrives while the default agent is
active** (the common miss): the structural enforcement is absent (invariant 6)
— the doctrine layer governs. The chat-side agent registers the input as a
ticket (GOAL/requirements ticket, or `type: bug`), cites the id back, and does
not implement; it may suggest switching to the seat (D-1/D-2) for the rest of
the session. Work performed anyway is a process violation, caught by §4, and
re-enters through the store — it is not shipped as chat edits.

**Machine-keeping dispatch (the bash-denied seat):** the seat's governance
duties include keeping the tickers armed and the tasks server connected, but
`ticker-setup.ps1` needs a shell — which the seat denies itself. The
seat-compatible routes are the non-shell ones: the Board's schedules switch
(Auto/Tickers) flips the tickers, and the tasks-server reconnect goes through
the MCP connect endpoint (`POST /api/experimental/mcp/tasks/connect`). Where a
shell is unavoidable (boot-time `ticker-setup.ps1`), the seat directs a
shell-capable **steward session** — the duty is the outcome (tickers armed),
not the tool.

## 3. Model/cost position (a cost lever, D-004-safe)

- **No committed model.** Per decision D-004 the seat's committed definition
  carries no `model:` frontmatter and no default; it runs on the setup's
  session default. A setup that wants a fixed seat model adds a frontmatter
  override **locally** (outside the committed definition) with a
  `<provider>/<model>[#variant]` placeholder value. No concrete provider or
  model id appears in committed content — ever.
- **Tier position.** The seat's loop (classify, distill criteria, register,
  cite, verify from store evidence) is structured store work, not deep
  reasoning: per the tier-selection rule (lowest tier whose criteria still
  satisfy the task) a `low`–`mid` tier model is the expected seat; escalate
  to `high` when a registration needs real distillation judgment. Never
  de-escalate when uncertain.
- **The lever the seat operates.** The ticket-level `model` field
  (`task_create`/`task_update`, `<provider>/<model>[#variant]`) decides what a
  run costs; the seat sets it at registration — small well-specified intake
  tickets deserve cheap models. This is the seat's main cost influence: it
  prices downstream work at the moment it creates it.
- **Seat cost profile.** One agent definition (already paid), one `/agents`
  switch per owner session, short verify-heavy turns. Session-level cost of
  the seat is observable like any session's (fleet intelligence gathering
  reads per-session tokens/cost), so the tier choice above is auditable after
  the fact.

## 4. Spot-check mechanization

The evaluation's spot-check rule (no direct chat-agent implementations, from
store and git evidence alone), mechanized as three concrete, **read-only**
queries over a review window `[t0, t1]`. All three produce *candidate lists*
for the reviewer — the verdict is the reviewer's, never automated. Reads may
use the `task_*` API (preferred) or grep the store files (`.opencode/tasks/
hephaestus/*.md`) — the write discipline bans hand *writes*, not reads.

**Identity whitelist** (used by all three signals): fleet identities
(`ticker-v:*` ticket-scoped sessions), `owner-directive` comments, engine/null
created-events, and recorded coordination sessions (reviewer, PM). Any other
provenance on an implementation lane is a candidate.

- **S-1 — Registration coverage** *(evaluation signal 1: every owner
  requirement/bug traces to a registered ticket whose derivation flows
  through the V).*
  - Store query: for tickets updated in the window, flag every
    implementation-lane ticket (`stage: implementation` or `stage: test-*`)
    whose `basis:` is empty **and** whose history/artifacts carry no
    `owner-directive` or fleet provenance — implementation with no intake
    parent.
  - Git query: `git log --since=<t0> --format='%h %s'`; flag commits that
    touch implementation lanes (non-doc, non-store paths) whose message cites
    no ticket id (pattern `\b[TU]-\d+\b`). Each flagged id-reference must
    resolve via `task_get`; an unresolvable reference is a candidate.
- **S-2 — Provenance by-fields** *(evaluation signal 2: implementation-lane
  artifacts and history cite worker/ticker sessions, never a chat-side
  session).*
  - Query: per ticket in the window, collect `history[].by` and
    `artifacts[].by` (API: `task_get`; file form: grep `"by":"` over the
    store directory). Flag any value outside the whitelist on an
    implementation-lane ticket — in particular any bare chat-session id.
- **S-3 — Merge-back shape** *(evaluation signal 3: implementation changes
  arrive as fleet/worktree merge-backs, not direct chat-session commits).*
  - Query: `git worktree list` gives the fleet's worktree branches (the
    whitelist of merge sources). For each commit in the window:
    `git branch --contains <sha>` — a commit reachable only on the main
    line, contained by no worktree branch, and carrying no ticket reference,
    is a candidate for a direct in-tree commit.

**Cadence and what a hit means.** Run S-1..S-3 as part of every human review
that accepts a release (the T-041 standing rule — the natural attachment
point on the review-cadence dial) and at retros. A hit means rework through
the store plus a doctrine reminder — never a silent pass (evaluation, §Spot-
check rule).

## 5. Q-1 — intake vs product-owner naming (OPEN owner decision)

**Status: OPEN. Not resolved by this document.** The evaluation carried it as
a recorded note (owner's call, not that ticket's lane); this section surfaces
it as the standing decision it is.

- The seat was created as `.opencode/agent/intake.md` by explicit owner
  override (commit `7aab59b`), then renamed to `product-owner.md` by owner
  direction (commit `55f2823`).
- **"intake"** names the *function*: the single point of entry (FR-2 — the
  intake role registers owner input). Precise about what the seat does.
- **"product-owner"** names the *seat/persona*: the PO's chair, matching the
  coordination-agent vocabulary (`project-manager` the PM voice, `reviewer`
  the verdict voice) and the "product-owner seat" phrasing AGENTS.md and the
  T-036 artifacts already use. Precise about whose chair it is.
- **If the owner renames again**, the blast radius is the dispatch surfaces —
  the name is the dispatch key everywhere: the `/agents` selector (D-1), the
  Eclipse agent combo (D-2), the `openChat` `agentId` parameter (D-3), the
  references in AGENTS.md and the governing docs, and the identity whitelist
  in §4 if the session-label convention changes with it.
- **Decision request to the owner:** keep `product-owner` (current state, zero
  churn) or rename to `intake` (function-exact). Either is one rename plus a
  docs touch; no code depends on the name.

## 6. Acceptance criteria mapping (T-083)

- **AC-1** (system artifact: integration model citing the governing
  definitions): this document — §1 system view and invariants (agent
  definition, AGENTS.md paragraph), §2 dispatch (surfaces verified in the
  chat-bundle sources), §3 model/cost (D-004, ticket-model convention), §4
  spot-check (the evaluation's three signals, mechanized).
- **AC-2** (Q-1 surfaced as an owner decision, not resolved by the
  architect): §5 — status OPEN, decision request stated, blast radius
  recorded, nothing resolved.
- **AC-3** (artifact recorded on the ticket before in-review): store
  discipline followed — `task_add_artifact` (kind `file`, this path) written
  before the `in-review` status update; the ticket's history is the proof.

## 7. Lane and non-goals

This ticket's lane is one new doc file. Not touched: `AGENTS.md`,
`ROADMAP.md`, `.opencode/agent/product-owner.md`, any code, any skill. No
concrete provider/model ids anywhere (D-004). Q-1 not resolved (§5). The
spot-check queries are specified, not scripted — a checking script would be
its own ticket with its own tests.
