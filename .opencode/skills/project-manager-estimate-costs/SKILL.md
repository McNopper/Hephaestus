---
name: project-manager-estimate-costs
description: >
  Use this skill on demand to estimate the cost of executing a plan or execution
  manifest before a fleet runs: estimate per-task tokens, price them against the current
  per-tier model rate card, account for very-high double-runs, rubberduck overhead and
  retries, and surface de-escalation opportunities. Rule-based method with a swappable
  rate card; model prices are examples that change over time. opencode workflow utility.
---

# Cost Estimation Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the project-manager-* domain set; standalone (no lifecycle pair).

You are a pragmatic cost estimator for agentic execution plans.

Your job is to price an execution plan/manifest **before** the fleet runs so tier choices
are budget-aware, and to suggest where cost can be safely reduced.

## Position

This is a **standalone, on-demand** workflow utility. Invoke it whenever a plan or execution
manifest needs a cost estimate or a budget check — typically alongside
`project-manager-orchestrate-execution`.

## Scope

This skill **owns**:

- Estimating input/output tokens per task from its scope.
- Pricing tasks against the current per-model **rate card**.
- Accounting for tier multipliers: `very-high` double-run + reconciler, rubberduck
  overhead on `very-high` (the critic only reviews `very-high` tasks), and the
  retry budget.
- Producing per-task, per-tier, and total costs with a cheap/expected/worst-case range.
- Suggesting **de-escalation** opportunities (tasks that could safely drop a tier).
- **Budget-cap fitting:** given a spend cap (e.g. "~$X today"), propose how to fit the run
  within it (de-escalate / defer / trim) and report projected spend vs cap.

This skill **does not** author, order, or execute tasks; it prices them. Task ordering,
tiering, and execution belong to `project-manager-orchestrate-execution`.

## Core Principles
1. **Method is durable; numbers are swappable.** The estimation *formula* is stable;
   token sizes and prices are inputs that change over time.
2. **Rate card in ONE place.** Keep model prices in the single table below, labelled
   "as of today"; when prices/models change, edit only this table. Reference *tiers*,
   never hard-coded prices, elsewhere.
3. **Estimate conservatively.** Prefer ranges over false precision; state assumptions.
4. **Tie to the tier-selection rule.** Recommend the lowest adequate tier per task.

## Estimation method

For each task in the execution manifest:

1. **Token estimate.**
   - `input_tokens ≈ context (touched_files + inputs + prompt/skill body)`
   - `output_tokens ≈ expected_outputs size × verbosity factor`
2. **Base cost** `= input_tokens × rate_in(tier) + output_tokens × rate_out(tier)`.
3. **Tier multipliers.**
   - `very-high`: **× 2** (two independent passes of the very-high model) **+ reconciler** pass.
   - `very-high`: **+ rubberduck overhead** (cross-vendor critic cross-check of the very-high model).
   - **Retry budget:** add expected retries per the task's `retry_policy`
     (e.g. `+1 same-tier retry` for transient-failure allowance).
4. **Aggregate:** sum per tier and overall; derive a range:
   - **cheap** = no retries, no reopened iterations,
   - **expected** = planned retries + 1 rubberduck pass where required,
    - **worst-case** = max retries + max agile rework iterations (from the manifest's
      iteration guard).

## Rate card (illustrative rates — verify before relying on them)

The numbers below are **illustrative per-1M-token prices**, not a commitment.
They exist so a fresh template can produce a non-zero estimate out of the box.
**When prices or models change, edit only this table.** This skill keys costs
**by tier**; the concrete model each tier resolves to is configured in
`opencode.json` and per-agent overrides (it is intentionally not enumerated in
any skill). Reference *tiers*, never hard-coded prices, elsewhere in the run.

| Tier | Rate in (per 1M) | Rate out (per 1M) | Notes |
|---|---|---|---|
| `very-low` | $0.15 | $0.60 | cheapest/fastest |
| `low` | $0.50 | $1.50 | default executor |
| `mid` | $1.50 | $6.00 | standard impl/tests |
| `high` | $2.00 | $8.00 | planning + review |
| `very-high` | $5.00 | $25.00 | **× 2** (two passes) + reconciler |
| rubberduck | $1.50 | $6.00 | cross-vendor critic; cross-checks the very-high pass |

> Treat these as placeholders pending current provider pricing. Before a real
> budget run, confirm each rate against the provider's published price for
> whatever concrete model you have mapped to that tier, and overwrite the row.
> The estimation *method* is what is durable; the numbers are inputs.

## Budget-cap fitting

When a spend cap is given (e.g. "~$X today", `run.budget_cap_usd`):

1. Estimate the expected total; compare to the cap.
2. If over cap, propose a **fit plan** in priority order:
   - **de-escalate** tasks to the lowest adequate tier,
   - **defer** low-priority tasks (mark `status: deferred`),
   - **trim** optional rubberduck passes on borderline tasks.
3. Report projected spend vs cap after fitting, and what was deferred.
4. Feed the fit plan back to the `orchestrator`, which tracks `run.spent_usd` and halts at
   the cap.

## Default Output
```md
# Cost Estimation

## Assumptions
- Rate card date: <date>; token model: <how tokens were estimated>

## Per-task
| ID | Skill | Tier | Est. in tok | Est. out tok | Base | Multipliers | Task cost |
|----|-------|------|------------:|-------------:|-----:|-------------|----------:|
| T1 | software-implementation | low | ... | ... | ... | — | ... |
| T3 | software-design | very-high | ... | ... | ... | ×2 + reconcile + duck | ... |

## Per-tier subtotal
| Tier | Tasks | Subtotal |
|------|------:|---------:|

## Total
- Cheap: $...  Expected: $...  Worst-case: $...
- Cost drivers: <what dominates>

## De-escalation suggestions
- <task> could drop `high` → `mid` (saves $...) if <condition holds>

## Budget fit (when a cap is given)
- Cap: $<X>   Projected: $<total>   Verdict: within / over by $...
- Fit plan: de-escalate <tasks>; defer <tasks>; trim ducks on <tasks>
- Projected after fit: $...   Deferred to a later run: <tasks>
```

## Notes / Hand Off
- Task tiering/ordering/execution → hand off to `project-manager-orchestrate-execution`.
- Ambiguous lifecycle routing → hand off to `project-manager-route-request`.
- Ticket / wave budgeting in the ticket workflow (`sprint` field = wave) → hand off to the `project-manager` agent (tickets carry `story_points`; a `cost` field can be added later to feed actuals).
