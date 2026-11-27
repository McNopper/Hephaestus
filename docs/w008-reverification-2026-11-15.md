# Re-verification of W-008 — FAILED (implementation subject absent)

## About this document

- **Kind:** verification report (test-implementation leg) — evidence record, not a definition.
- **Read by:** the reviewer/gate for ticket T-051 (project `hephaestus`), and whoever reworks basis W-008.
- **Dated:** 2026-11-15 — this run's probes (evidence record of one day). Superseded the same day by `docs/w008-reverification-pass-2026-11-15.md` after T-054's re-delivery; this file stays the FAILED-run evidence.
- **Related:** T-051 (this re-verification — blocked on FAIL at the time; the ticket later closed **done** on the PASS record above), W-008 (basis, the implementation under test), W-005 (design the code must match), W-006 (STALE predecessor verdict — **not used as evidence here**).
- **Verdict:** FAILED VALIDATION. `scratch/fleet-regression/editor/EditorCore.java` does not exist in the working tree, in git history, or anywhere on this machine — AC 1 fails outright and ACs 2–5 have no subject to verify. Basis rework (re-delivery of the W-008 implementation) is required.

## Context

T-051 was minted by the ticker-v resume because upstream W-008 changed after W-006 ran, making
W-006's verdict STALE. The mandate: fresh verification of the **current implementation state**
against W-008's five acceptance criteria, with observed evidence (command + exit code + output)
per criterion. W-006's verdict is deliberately not cited as evidence.

The W-series (W-001..W-008, sprint S-06) was a fleet-regression exercise whose artifacts lived in
an **uncommitted scratch tree** (`scratch/fleet-regression/editor/`). The project's own v0.1.1
review recorded on W-001 and W-004: *"the artifact under scratch/fleet-regression/editor/ was
never committed ... so the ref does not resolve in the repository. Kept as the historical record;
no replacement exists."* The searches below establish that the scratch tree — in particular
`EditorCore.java` — is now gone from the machine entirely.

## Toolchain sanity (the failure is not environmental)

A working JDK 21 is present, so AC 1 does not fail for lack of a compiler:

```text
$ javac -version
javac 21.0.12.1
$ java -version
java version "21.0.12.1" 2026-08-18 LTS
(exit code of the probe command: 0)
```

## Per-criterion evidence

| # | Acceptance criterion (W-008) | Verdict | Observed evidence |
|---|---|---|---|
| 1 | EditorCore.java still compiles (javac, JDK 21) | **FAIL** | The file does not exist, so the compile cannot run. `Test-Path C:\Development\GitHub\Hephaestus\scratch` → `False` (no `scratch/` directory in the checkout at all). Glob `**/EditorCore*.java` over the repo → `No files found`. Disk-wide search across **all fixed drives (C:\, G:\, U:\, Z:\)** (`Get-ChildItem -Recurse -Filter EditorCore.java -Force -Depth 10` from each drive root) → zero results, probe exit code 0. javac 21.0.12.1 available (see above). |
| 2 | apply() handles printable chars in INSERT, Enter, Backspace (join at col 0), Delete (join at EOL), x, d, u | **UNVERIFIABLE — FAIL** | No `EditorCore.java` exists to read or exercise (see AC 1 evidence). There is no `apply` to inspect and no smoke path to run. |
| 3 | Undo: full-state snapshots pushed before each MUTATING transition, stack max 100 (oldest dropped) | **UNVERIFIABLE — FAIL** | Same missing subject — no implementation to read; no runnable behavior to observe. |
| 4 | Every edit respects the mode gates (INSERT keys no-op in NORMAL except i/Insert; NORMAL keys no-op in INSERT except Escape) | **UNVERIFIABLE — FAIL** | Same missing subject — no implementation to read; no runnable behavior to observe. |
| 5 | main() smoke extended: type 'hello', Enter, 'world', Backspace, x, u — each state printed as JSON | **UNVERIFIABLE — FAIL** | Same missing subject — no `main()` exists; the smoke sequence cannot be executed. |

## Search evidence appendix (how "absent" was established)

Each probe was run 2026-11-15 in/against `C:\Development\GitHub\Hephaestus` unless noted.
Cmdlet-based probes report observed output; process-based ones report exit codes.

1. **Working tree:** glob tool, pattern `**/EditorCore.java` and `**/EditorCore*` → `No files found`. `Test-Path scratch` → `False`. `Get-ChildItem scratch -Recurse -Filter *.java` → nothing (dir absent).
2. **Git history:** `git log --all --oneline --name-only -- "*EditorCore*"` → empty; `git log --all --diff-filter=A -- "scratch/*"` → empty. The file was **never committed**.
3. **Branches / artifact refs:** `git branch -a` → only `main` and `remotes/origin/main`. W-005's recorded git artifact `opencode/W-005` does not resolve (no such branch locally or on the single remote `git@github.com:McNopper/Hephaestus.git`).
4. **Worktrees:** `git worktree list` → only the main checkout (plus `opencode.worktree_list` → same single entry). No fleet worktree retains the file.
5. **Local repos:** all 22 git repositories under `C:\Development\GitHub` checked — none contains a `scratch/` directory.
6. **Disk-wide:** `Get-ChildItem <root> -Recurse -Filter "EditorCore.java" -Force -Depth 10` from the root of every fixed drive (C:\, G:\, U:\, Z:\) → zero results. A directory search for `fleet-regression` on C:\ (depth 6, `-Force`) → zero results. (Depth-limited search; combined with items 2, 5 and 7 the conclusion is robust.)
7. **Store documentation:** W-001/W-004 v0.1.1 review comments (2026-10-02): artifacts under `scratch/fleet-regression/editor/` "never committed ... no replacement exists". W-007 artifact note: "external repo, code not stored here". W-006 STALE-review comment: "(The W-series artifacts lived in an uncommitted scratch tree.)"

## Routing (per the V-model)

This is a **failed validation of the verification leg**: the pair `test-software-implementation`
↔ `software-implementation` routes the failure back to the **implementation** — i.e. basis
**W-008** needs rework (re-delivery of `EditorCore.java` implementing the W-005 design, at its
declared path `scratch/fleet-regression/editor/EditorCore.java`, ideally committed this time so
future verification has a resolvable subject). T-051 is left **blocked** with this reason; no
code was created, modified or "restored" by the verifier — recreation is implementation's job,
not verification's.

Re-run conditions: once a resolvable `EditorCore.java` exists at the declared path, a fresh
verification child can execute the compile check, read the transitions against the W-005 design,
and run the extended smoke — none of which was possible here.
