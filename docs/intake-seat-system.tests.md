# Product-owner seat — system integration tests (T-084)

## About this document

- **Kind:** `doc` / test-system-stage artifact of ticket T-084 (epic U-112,
  basis T-036, stage `test-system`): the paired verification of T-083's system
  artifact `docs/intake-seat-system.md`. Integration criteria 1:1 with T-083's
  acceptance criteria, each with an observable oracle and this run's observed
  instance, plus the coherence judgment over the harness as ONE system.
- **Read by:** the reviewer weighing T-083's verdict (this decides its
  done/not-done), the maintainer (owns Q-1 and the notes), the PM and tickers.
- **Related:** the verified system doc `docs/intake-seat-system.md` (D-1/D-2/D-3
  routes, S-1/S-2/S-3 queries, §5 Q-1); governing definitions
  `docs/intake-agent-evaluation.md`, `AGENTS.md` §Chat-first control plane, the
  seat `.opencode/agent/product-owner.md`. Verification ran 2026-11-15 against
  the tree at `0e7d2e8` (main). Read-only: no verified file, no code, no store
  write outside ticket T-084 was touched.

## 1. Criteria (1:1 with T-083's acceptance criteria)

| ID | T-083 AC | Criterion | Oracle (observable) | Verdict |
|---|---|---|---|---|
| IC-1 | AC-1 | Dispatch routes D-1/D-2/D-3 cite real wiring, not plausible fiction | Chat-bundle sources + agent frontmatter (read-only) | **PASS** |
| IC-2 | AC-1 | Spot-check mechanization S-1/S-2/S-3 is concretely runnable over store+git evidence and discriminates | Dry-run each query; record what it returns | **PASS** |
| IC-3 | AC-2 | Q-1 surfaced as an OPEN owner decision, not resolved by the architect | `docs/intake-seat-system.md` §5 text | **PASS** |
| IC-4 | AC-1 | The integration model holds with the harness as ONE system (denied tools vs machine-keeping routes, the two dials, the intake boundary) | Cross-read of the four governing definitions + the system doc | **PASS** (with notes) |
| IC-5 | AC-3 | Artifact recorded on T-083 before its in-review status | T-083 history event order | **PASS** |

## 2. IC-1 — dispatch routes vs the actual code

**D-1 (TUI `/agents`).** `.opencode/agent/product-owner.md` frontmatter
declares `mode: primary` (line 8); the definition's own header says "switch via
`/agents` → product-owner". AGENTS.md documents `/agents` as the selection
mechanism. Git confirms the switch pointer is live-maintained (`7f77b52`
"product-owner agent: /agents switch pointer fixed"). The TUI selector itself is
opencode-external code — the repo-verifiable claim (frontmatter mode +
documented route) holds. **PASS.**

**D-2 (Eclipse guarded agent combo).** `ChatView.java` line 139 declares
`agentCombo`; lines 422–425 create it `SWT.DROP_DOWN | SWT.READ_ONLY` and wire
`agentGuard = wireGuardedCombo(agentCombo, text -> selectors.selectAgent(text))`
— the agent combo is guarded by the SAME `wireGuardedCombo` as model/variant
(lines 891–925): commit only on armed pick (mouse-down opened dropdown) or Enter
(`SWT.DefaultSelection`); un-armed drift reverts via
`combo.setText(guard.committed())`. The semantics live in SWT-free
`SelectorGuard.java` (javadoc: "silently flipped agent/model/variant … A change
commits only when armed"), unit-tested per its header. This is exactly the
"opened-dropdown pick or Enter commits; wheel/pointer drift reverts" semantics
AGENTS.md documents for the model selector. **PASS.**

**D-3 (`openChat` `agentId` parameter).** `plugin.xml` lines 15–36 define
command `com.opencode.ide.chat.openChat` with optional parameter
`com.opencode.ide.chat.openChat.agentId`; handler bound lines 51–54.
`OpenChatHandler.java` reads the parameter (line 23) and calls
`chat.preselectAgent(agentId)` on a NEW chat (line 31–33); `ChatView.preselectAgent`
(lines 1000–1007) calls `selectors.selectAgent(agentId)` and selects the combo
index. Caller side: `ServerView.java` line 528 passes
`openChatParameter("agentId", agent.id())` ("v2 wants the id, not the display
name") resolving the command via the core constant
`SessionViewIds.CHAT_OPEN_COMMAND_ID` (line 1121) — grep finds NO
`import com.opencode.ide.chat.` in `ServerView.java`, so the no-compile-
dependency claim holds (ui → core constant → Eclipse command mechanism → chat
handler). `ChatSelectorState.selectAgent` (line 85) stores `requestedAgent`
unconditionally and commits `agent` on list membership — the selection request
survives an async agents-list load. **PASS.**

Note (non-blocking): the combo-index selection in `preselectAgent` is
best-effort — `agentCombo.indexOf(agentId)` selects only if the id is present in
the already-loaded list; the session-level selection (line 1001) is
unconditional. Cosmetic timing nuance, not a dispatch failure.

## 3. IC-2 — spot-check dry runs (S-1/S-2/S-3)

**S-1 (registration coverage / derivation chains).** Ran
`task_traceability` on project `hephaestus` (returned 297 rows before the tasks
namespace dropped): epic U-112 carries 39 child links including T-036 (pm, done),
T-083 (system, in-review) and T-084 (test-system, in-progress); T-084 reports
`verifies: U-112` via `epic:U-112`. The chain T-036 → T-083/T-084 flows through
the V as claimed. Query is runnable and discriminating. Note: the matrix lists
T-083 in `orphan_definitions` — pairing is epic-scoped (role+epic), so the
definition-pair edge (T-083 ↔ sibling T-084 by shared basis T-036) is not
visible as `verified_by` until the review verdict lands. Semantic note, not a
query failure.

**S-2 (by-field provenance).** Grepped `"by":"` over
`.opencode/tasks/hephaestus/*.md` (the doc's own file-form fallback). Sampled
values: `fleet`, `ticker-v`, `ticker-v:review`, `system-ticker`, `system-worker`,
`architecture-worker`, `test-system-worker:A-002`, `executor (board half)`,
`review`, `store-hygiene`, `chat-session`, `agent` — all within/near the §4
whitelist (fleet identities, recorded coordination sessions, engine/null),
EXCEPT `chat-session` provenance on legacy implementation-lane tickets
(B-004/B-005/B-006/B-009/B-010/B-011, fleet-era 2026-09; one URL artifact on
A-001, 2026-11-14). The query DISCRIMINATES: it returns exactly the
candidate class the whitelist exists to flag, and the verdict on each is the
reviewer's — as the doc specifies. Caveat (known store-wide, see the A-002
escalation): status-transition events are actor-less (`by: null`); S-2's power
is on stamped comment/artifact events, which is where implementation provenance
lives. **PASS** (runnable, discriminating).

**S-3 (merge-back shape).** `git worktree list` → main only (fleet worktrees
are ephemeral — removed after merge, so the live-worktree whitelist is empty at
run time). `git log --oneline -25` on main: every sampled commit carries a
ticket reference (`\b[TUA]-\d+\b` — wave commits cite T-034..T-038 etc.; the
intake-agent commits `7aab59b`/`55f2823`/`470274f`/`7f77b52` cite the owner
directive/owner naming). Under S-3's rule (candidate = main-line-only commit
with NO ticket reference), zero candidates in the sample. Fleet merge-backs are
evidenced in the store (`artifact:git:opencode/B-004` "fleet branch merged back
by TaskFleet") rather than in live branches. **PASS** — with a wording-level
note: §S-3's "whitelist of merge sources" assumes live worktree branches; after
merge-back cleanup the whitelist is empty and the query leans entirely on the
ticket-reference test. The query still functions; the doc could state this.

## 4. IC-3 — Q-1 stays OPEN

`docs/intake-seat-system.md` §5 states "**Status: OPEN. Not resolved by this
document.**", records both naming options with their blast radius, and ends with
an explicit decision request to the owner ("keep `product-owner` … or rename to
`intake`"). Nothing is resolved silently; the evaluation's open-question note is
carried forward faithfully. **PASS.**

## 5. IC-4 — coherence as ONE system

- **Denied tools vs machine-keeping routes.** The seat denies
  `write`/`edit`/`patch`/`bash` at role level (frontmatter lines 9–13). §2 of
  the system doc keeps the tickers armed via the Board's schedules switch and
  reconnects the tasks server via the MCP connect endpoint — both non-shell —
  and routes the one unavoidable shell duty (boot-time `ticker-setup.ps1`) to a
  steward session: "the duty is the outcome, not the tool". Coherent with the
  definition's governance-duties list and with invariant 1 (violation requires
  *leaving* the seat).
  *Note (the one real gap found):* the definition's duties list a third
  machine-keeping op — "deploy jars the gate produced" — for which §2 documents
  no seat-compatible (non-shell) route; a bash/write-denied session cannot copy
  jars to `ECLIPSE_HOME`. Plausibly an owner/Build-mode action (override
  intact), but the integration model is silent on it. Non-blocking; worth one
  sentence in the system doc or a route in a follow-up.
- **The two dials.** The definition's two-dials paragraph (goal horizon,
  review cadence; recorded on the ticket, not as ACs) matches AGENTS.md's
  autonomy-spectrum operating model, and §4 attaches the spot-check cadence to
  "every human review that accepts a release (the T-041 standing rule — the
  natural attachment point on the review-cadence dial)": the dial and the check
  land on the same event. Coherent.
- **The intake boundary.** Invariant 6 (honest residual gap: role-level denial
  binds only seat sessions; AGENTS.md doctrine is the second layer, the
  spot-check the detective control over both) is exactly the evaluation's
  residual-gap paragraph and AGENTS.md's role+doctrine design. Invariant 4
  (nothing dispatches TO the seat; Auto ▶ launches sprint tickets per the
  stage→role→skill mapping) matches AGENTS.md's auto-dispatcher description.
  Invariant 5 (owner override intact) matches AGENTS.md's "the rule binds the
  machine and its agents, not the PO" and the T-036 owner-directive record.
  Invariant 2 (never self-declare done; verdicts are the engine's) matches the
  definition's boundaries and the store's observed discipline (workers stop at
  in-review). **PASS with the deploy-jars note.**
- **Model/cost position (checked for D-004).** The committed definition carries
  no `model:` frontmatter (verified by reading the file) and no concrete
  provider/model id appears in the system doc — placeholders only, matching
  AGENTS.md's "model selection is a cost lever on the ticket". Coherent.

## 6. IC-5 — artifact-before-in-review (T-083 store discipline)

T-083 history order: claim 08:56:20.468Z → `artifact:file:docs/intake-seat-system.md`
08:56:20.521Z (by `ticker-v:T-083`) → `updated:status` (in-review)
08:56:20.575Z. The artifact event strictly precedes the in-review event.
**PASS.**

## 7. Verdict

**OVERALL: APPROVE-WITH-NOTES.** All five integration criteria PASS; T-083's
three acceptance criteria are each fulfilled by the system doc as verified
above. The reviewer may weigh these non-blocking notes:

1. **Deploy-jars route gap** (IC-4): the seat's "deploy gate jars" duty has no
   documented non-shell route in §2 — either scope it to the owner/Build
   explicitly in the system doc, or add the route.
2. **S-3 whitelist wording** (IC-2): state that after merge-back cleanup the
   worktree whitelist is empty and the query leans on the ticket-reference test.
3. **Traceability pairing scope** (IC-2/S-1): definition↔verification pairing
   is epic-scoped in `task_traceability`; the T-083↔T-084 pair is by shared
   basis — fine, but the matrix shows T-083 as an orphan definition until the
   verdict lands.
4. **Status transitions are actor-less** store-wide (known, escalated from
   A-002): S-2 discriminates on stamped comment/artifact events; "done by the
   engine" remains undecidable from status events alone until the store change
   lands.

None of the notes fails a T-083 acceptance criterion; they are improvement
surface for the doc's own honesty, to route through the store as the reviewer
sees fit. This ticket claims no done on T-083 — the verdict is the engine's.
