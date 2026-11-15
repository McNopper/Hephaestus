# W-008 Re-verification PASS evidence (T-051, 2026-11-15)

**About this document:** the PASS-side verification evidence for ticket T-051
(hephaestus, stage test-implementation, role tester) — a fresh re-verification of
W-008 ("I3: Navi editing + undo transitions, EditorCore.apply part 2") against the
re-delivered implementation. It is the successor of
`docs/w008-reverification-2026-11-15.md`, which recorded the same ticket's FAILED
run (subject absent). Read by the ticker-v engine, the reviewer of T-051/T-054, and
the PO. The verdict recorded on the ticket mirrors this file; this file is the
durable per-command evidence.

- **Verdict: PASS (overall). AC1–AC5 all PASS.** 55/55 harness checks green,
  exit 0; AC5 smoke matches an independently hand-computed trace line by line.
- Subject under verification: commit `57c264305d175297dbf9732c622934b31a84879a`
  (T-054 re-delivery; exactly one file `scratch/fleet-regression/editor/EditorCore.java`,
  686 lines; working tree == HEAD at verification time).
- Toolchain: javac 21.0.12.1 / java 21.0.12.1.
- Method: compile + run in a throwaway temp dir
  (`C:\Users\norbe\AppData\Local\Temp\opencode\w008-reverify`); a throwaway
  harness `EditorCoreVerify.java` (55 checks) was written there — never in the
  repo, never committed. The verifier changed no implementation file.

## AC1 — compiles under JDK 21: PASS

```text
javac -version              -> javac 21.0.12.1
javac EditorCore.java       -> (no output) exit 0
```

`.class` files created only in the temp dir (`EditorCore.class`,
`EditorCore$CoreState.class`, `EditorCore$Parser.class`); nothing dropped into the
repo. Working tree confirmed clean for the subject file (`git status --porcelain`
does not list it; `git show --stat 57c2643` confirms exactly one file, 686
insertions).

## AC2 — editing transitions per the W-008 table: PASS (21 checks)

Harness checks (exact expected JSON from the state grammar, or field assertions):

| Transition | Check(s) | Result |
|---|---|---|
| printable (INSERT) | `AC2.printable.insert` (full JSON incl. snapshot), `.midline` | PASS — insert at cursor, col+1 |
| Enter split | `AC2.enter.split` (W-006 v4 shape: `"hello"` @2 → `["he","llo"]` 1:0), `.emptyline` | PASS — cursor to new line, col 0 |
| Backspace col>0 | `AC2.backspace.col>0` (`"hello"` @2 → `"hllo"` col 1) | PASS |
| Backspace col==0 join | `AC2.backspace.join.col0` (W-006 v5: `["hel","lo"]` 1:0 → `["hello"]` cursor at join point 0:3) | PASS |
| Backspace at (0,0) | `AC2.backspace.0,0.noop` | PASS — nothing left to delete |
| Delete col<len | `AC2.delete.col<len` (`"hello"` @2 → `"helo"` col 2) | PASS |
| Delete EOL join | `AC2.delete.join.EOL` (W-006 v13: `["hel","lo"]` @3 → `["hello"]` col 3) | PASS |
| Delete EOL last line | `AC2.delete.EOLlast.noop` | PASS |
| x | `AC2.x.vector10` (W-006 v10: `"hello"` @0 → `["ello"]` col 0), `.midline` (`@2` → `"helo"` col 2), `.EOL.choice4` (col clamps to len-1, deletes last char), `.emptyline.noop` | PASS |
| d | `AC2.d.vector11` (2-line → 1 line, cursor kept), `.lineclamp`, `.colclamp`, `.singleline.noop` (buffer keeps ≥1 line) | PASS |
| u | `AC2.u.emptystack.noop` | PASS |
| purity | `AC2.apply.inputNotMutated`, `AC2.apply.newState` | PASS — immutable states (header choice 7) |

## AC3 — undo protocol: PASS (25 checks)

- **Push before every MUTATING transition, none otherwise** — chain
  `i, h,e,y, Enter, w, Escape, Home, i, Delete, Escape, Left/Right/Up/Down, Up, x, d, u`
  tracked depth: mode keys and all cursor moves push nothing (`AC3.noSnap.*`),
  each of printable/Enter/Backspace/Delete/x/d pushes exactly one (`AC3.snap.*`),
  u pops one and pushes none (`AC3.u.popsNoPush`).
- **No-op paths push nothing**: Backspace at (0,0), Delete at EOL of last line,
  x on empty line, d on single line, u on empty stack (`AC3.noSnap.*` PASS).
- **Full-state snapshots incl. mode**: `AC3.restore.preX` + `AC3.restore.modeIncluded`
  restore buffer, cursor AND mode exactly (snapshot taken in INSERT restores as
  INSERT), matching the W-006 vector-12 pin (snapshot carries its own undoStack).
- **Cap 100, oldest dropped** (field oracle; see Observation 1):
  `AC3.cap.depth100` (101 mutations → depth exactly 100),
  `AC3.cap.oldestDropped` (one undo restores the pre-state of the 101st mutation =
  state after the 100th — proves the OLDEST entry was dropped, not the newest refused),
  `AC3.cap.cascadeToOldestKept` (exactly 100 undos land on the content after the 1st
  mutation, NOT the initial state), `AC3.cap.beyondEmpty.noop` (u past empty stack is a no-op).

## AC4 — mode gates: PASS (6 checks)

- INSERT keys no-op in NORMAL: `Backspace, Delete, Enter, a, q, 1, " "` all leave the
  state byte-identical (`AC4.gate.insertKeysNoopInNORMAL`). Exceptions work:
  `i` and `Insert` switch to INSERT (`AC4.exception.*`).
- NORMAL keys no-op in INSERT: `Left, Right, Up, Down, Home, End, PageUp, PageDown,
  Ctrl+Home, Ctrl+End, Insert` all leave the state byte-identical
  (`AC4.gate.normalKeysNoopInINSERT`). Exception works: `Escape` switches to NORMAL.
- `u`/`x`/`d` in INSERT are PRINTABLE text (W-008 table row 1, standard vi semantics):
  `feed(INSERT "" @0, "u","x","d")` → `["uxd"]` col 3, three snapshots
  (`AC4.uxd.printableInINSERT`, + exact-JSON variant). The same `u` is undo in NORMAL
  and text in INSERT — the gate distinction is real and pinned.

## AC5 — extended smoke (type 'hello', Enter, 'world', Backspace, x, u): PASS

`java EditorCore` runs the exact sequence (mode glue `i`…`Escape` required by the
gates and documented in the file header); every state printed as JSON. The visible
trace matches an independent hand computation line by line:

```text
initial [lines=[] cursor=0:0 mode=NORMAL undoDepth=0]
i       -> INSERT, no snapshot (depth 0)
h,e,l,l,o -> ["hello"] 0:5, depth 1..5 (one snapshot per key)
Enter   -> ["hello",""] 1:0, depth 6
w,o,r,l,d -> ["hello","world"] 1:5, depth 7..11
Backspace -> col>0 case: ["hello","worl"] 1:4, depth 12
Escape  -> NORMAL, no snapshot (depth 12)
x       -> EOL clamp (choice 4): ["hello","wor"] 1:3, depth 13
u       -> exact pre-x restore: ["hello","worl"] 1:4 NORMAL, depth 12
FINAL [lines=[hello, worl] cursor=1:4 mode=NORMAL undoDepth=12]
codec round-trip stable: true
gate 'Home' in INSERT no-op: true ; gate 'Backspace' in NORMAL no-op: true ; exit 0
```

`FINAL` is byte-identical to the smoke recorded on T-054 by the implementing worker.

## Underspecification cross-check (8 header choices vs W-008 table + W-006)

| # | Choice | Cross-check | Result |
|---|---|---|---|
| 1 | compact codec, fixed key order, lenient `fromJson` | matches W-006 grammar; round-trip stable | CONSISTENT |
| 2 | Left/Right clamp in-line, no wrap | W-006 v1/v15 pin only the clamped ends | CONSISTENT |
| 3 | PageUp/Down ±10, clamped | W-006 v8 pins only the clamp; page size free | CONSISTENT |
| 4 | x clamps col to len-1 → deletes last char at EOL | W-008 "col clamps"; W-006 v10 (cursor 0) unaffected | CONSISTENT (recorded, defensible reading) |
| 5 | d on single-line buffer = no-op | W-008 "(if it leaves >= 1 line)"; W-006 v11 unaffected | CONSISTENT |
| 6 | u on empty stack = no-op | unspecified; no-op + no push | CONSISTENT (recorded) |
| 7 | immutable CoreState, structural sharing | `AC2.apply.inputNotMutated`; observably deep-copy-equal | CONSISTENT |
| 8 | mode changes not undoable; `Insert` in INSERT no-op | W-008 undo protocol + AC4 exception wording | CONSISTENT |

**No choice contradicts a spec line.**

## Observations (non-blocking, for the W-006 vector re-run)

1. **Deep-stack serialization is exponential by the pinned grammar** — W-006 v12
   pins snapshots to carry their own undoStack, so serialized size doubles per
   mutation (depth 12 ≈ 305,276 chars, exactly the smoke's `FINAL json length`).
   Serializing a state whose stack is ~31+ deep exceeds StringBuilder's array limit
   (the harness hit OOM twice while using toJson as the cap oracle before switching
   to field comparison). No W-008 AC requires serializing deep stacks (AC5 stops at
   depth 12; W-006 vectors are shallow) and apply/undo themselves handle 101
   mutations fine — but if W-006's re-run ever chains many mutating vectors into one
   state, its expected JSON must stay shallow or use field oracles.
2. **Strict-gate consequences worth pinning deliberately in vectors**: `Delete` is a
   strict no-op in NORMAL; cursor keys are strict no-ops in INSERT; a restored
   snapshot returns in its snapshot-time mode (so a cascade of undos needs `Escape`
   between steps when the snapshots were taken in INSERT).

## Harness provenance

`EditorCoreVerify.java` (rev 5, 55 checks) lives only in the temp dir and is NOT
committed. Revision history is honest verification hygiene: rev 1–4 had five
harness-side bugs (wrong x expectation; Delete/cursor keys fed in the wrong mode —
each gate correctly no-oped them; bare `u` fed in INSERT mode, which correctly
inserted text; a duplicated loop; a mode-flip comparison artifact). Every interim
failure output was itself spec-correct implementation behavior; the final run is
55/55 PASS, exit 0.
