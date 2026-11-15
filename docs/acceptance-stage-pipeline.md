# Requirements: the async stage-pipeline acceptance run (U-110)

- **About this document:** requirements for the live acceptance run of the
  U-110 async stage pipeline (the ex-U-037 acceptance seed; the PO's proxy
  filed this intake as T-031, epic U-111). It defines what the run must
  demonstrate, the evidence to capture, and pass/fail criteria — all
  decidable by reading the task store. **Read by:** the PO's proxy, the
  stage tickers, the engine review. **Related:** `docs/design-stage-pipeline.md`
  (§1 model, §4 tick, §5 fulfillment, §8 verdict), `.harness/tasks/hephaestus/U-110.md`.

## Goal

Prove on one live end-to-end run that the designed machinery works:
stationary tickets, division + linking, goal-driven workers, engine-owned
verdict — the live-parity evidence that gates the FleetRunner retirement
(design §9, chunk 6).

## Demonstration contract

- **D-1 Stationary fulfillment:** a ticket born in its stage is claimed and
  fulfilled by exactly ONE goal-driven subagent ("finish this ticket"),
  which produces the stage product, records it as an artifact, and sets
  `in-review`. Proven by: history shows claim → artifact → `in-review` by
  the worker; `stage:` never rewritten; no `done` set by worker or ticker
  (this run's instance: T-031 itself).
- **D-2 Division + linking:** completing the upstream ticket triggers
  division: a downstream child exists with `basis` = upstream id, the same
  `epic`, and the upstream artifact refs carried over. Proven by: `task_get`
  of the child (this run: T-031's system-stage child) shows `basis`/`epic`
  set; readiness flips the child WAIT_UPSTREAM → READY when the basis
  reaches `done`.
- **D-3 Engine verdict:** only the engine's review pass + quality gate move
  a ticket to `done`. Proven by: history carries `in-review` (worker) then
  `done` (engine review); no worker/ticker `done` event anywhere in the chain.

## Evidence to capture

- Store reads: `task_get` per chain ticket (status, stage, epic, basis,
  artifacts); `task_readiness` before/after the basis done;
  `task_traceability` (basis pairing rows).
- History events: claim, artifact add, each status transition with actor.
- Outputs: the worker's completion report; the ticker tick digest
  (kickoff/work/close); the engine review verdict; the gate run output.

## Pass/fail criteria

- **PASS:** D-1, D-2 and D-3 each hold on store evidence alone — a reviewer
  reading only the store (tickets + history + artifacts) can decide; the
  child reaches READY from division alone and `done` only via the engine
  review; no manual store hand-edits in any history.
- **FAIL:** any D-point not decidable from the store (missing `basis`/`epic`,
  a worker-set `done`, a rewritten `stage:`, no recorded artifact), or the
  child never becomes READY without manual intervention.

## Out of scope

Performance, cost, and multi-fan-out (one basis → several children) are
validated by later runs; this run is the single-chain happy path only.
