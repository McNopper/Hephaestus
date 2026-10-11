---
name: research-artificial-analysis-models
description: >
  Use when a user needs a current Artificial Analysis model comparison. Fetch the
  model leaderboard, keep models with context windows of at least 1M tokens and
  Intelligence Index values of at least 50, sort by Cost per Task ascending, and
  return the requested Markdown table.
---

# Artificial Analysis Model Leaderboard

## About this document

- **Kind:** `skill` (reusable live leaderboard research capability)
- **Read by:** agents answering model-selection and cost-comparison requests; **written by:** maintainers
- **Related:** standalone research utility; the market side of the cost loop —
  `project-manager-estimate-costs` consumes these rates for a-priori estimates, `project-manager-gather-intelligence`
  measures what runs actually spent.

Use this skill to produce a current, reproducible comparison from the Artificial Analysis model leaderboard.

## Source

Fetch this page at execution time:

`https://artificialanalysis.ai/leaderboards/models`

Do not use remembered values or stale search snippets. If the page cannot be fetched or the required fields are unavailable, report that limitation instead of inventing values.

## Filtering and normalization

Treat each model row as one record. The page currently exposes these relevant fields:

- `Model`
- `Context Window`
- `Creator`
- `Artificial Analysis Intelligence Index`
- `Cost per Task` in USD

Normalize values before applying the filters:

- Convert context suffixes numerically: `k` means 1,000 tokens and `M` means 1,000,000 tokens. Include rows with normalized context `>= 1,000,000`; the boundary is inclusive.
- Parse the leading integer from the Intelligence Index. A trailing `*` is a display marker, not part of the number; preserve it in the output when present. Include rows with numeric intelligence `>= 50`; the boundary is inclusive.
- Parse Cost per Task as a numeric USD amount. Treat `--`, `\--`, blank values, and other non-numeric placeholders as missing and exclude those rows because they cannot be sorted or reported as a cost.
- Preserve the source display form for context, intelligence, and cost in the final table, while using normalized values for comparisons.

## Ordering

Sort qualifying rows by numeric Cost per Task in ascending order, with the cheapest qualifying model as the first data row at the top of the table. Use the model name in ascending alphabetical order as the deterministic tie-breaker.

## Output

Return a Markdown table with exactly these columns and this order:

```md
| Model | Context Window | Creator | Intelligence | Cost per Task |
|---|---:|---|---:|---:|
```

Link the model name to its Artificial Analysis model page when the source provides a model URL. Keep the other four columns as source values. Put the source URL and retrieval timestamp outside the table. If intelligence has a source `*` marker, retain it and briefly explain that it was preserved as a source marker.

Do not add speed, latency, provider, or other leaderboard columns. If no rows qualify, state that clearly and still report the source and retrieval timestamp.

## Extraction guidance

The fetched page may render as flattened text rather than a conventional Markdown table. Use the model link/row boundary and validate the expected field sequence instead of assuming fixed line positions. Keep model links or slugs with their records so duplicate display names remain distinguishable.

Before returning the result, verify that every output row satisfies both inclusive thresholds and has a numeric Cost per Task, that the cheapest model is at the top, and that the displayed costs are in nondecreasing numeric order.
