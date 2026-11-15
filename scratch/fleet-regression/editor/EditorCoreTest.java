import java.util.List;

/**
 * EditorCoreTest - W-006 golden vectors for the fleet-regression editor lane
 * (scratch/fleet-regression/editor/, default package; JDK 21:
 * {@code javac *.java} then {@code java EditorCoreTest}).
 *
 * RE-RUN of W-006 via T-100 (2026-11-15) against the re-delivered EditorCore
 * (commit 57c264305d175297dbf9732c622934b31a84879a; T-054 re-delivery,
 * re-verified PASS by T-051). Predecessor W-006 ran against the original
 * uncommitted scratch tree that was wiped; this re-run commits the lane.
 *
 * Harness: W-006's skeleton, verbatim - check() runs fromJson(start) ->
 * apply(keys) -> toJson and compares the FULL result JSON. Vectors are added
 * one by one, run after each (T-100/W-006 discipline). The state grammar is
 *   {"lines":[...],"cursor":{"line":L,"col":C},"mode":"...","undoStack":[...]}
 *
 * T-051's two observations honored for this re-run:
 *   1. Every undoStack entry carries its own undoStack (the W-006 vector-12
 *      pin), so serialized size doubles per mutation. Chained-undo vectors
 *      therefore use the FIELD oracle (checkFields) instead of full-JSON
 *      equality; full-JSON vectors stay shallow (stack depth <= 2).
 *   2. Strict-gate consequences pinned deliberately: Delete is a no-op in
 *      NORMAL (v16), cursor keys are no-ops in INSERT (v17), and undo
 *      restores carry the snapshot-time mode (v18).
 *
 * Vector provenance: v01-v15 = W-006's golden-vector table (hand-written
 * expected JSON); v16-v18 = the T-051 strict-gate pins; v19-v21 = W-006's
 * coverage clause (Up, PageUp, Ctrl+Home in NORMAL - cursor keys the
 * 15-vector table does not exercise). A RED vector is a genuine
 * implementation-vs-contract finding: EditorCore.java is NOT modified to
 * make a vector pass (it routes back to the implementation leg).
 */
public class EditorCoreTest {
    static int passed = 0, failed = 0;

    /** One vector: from a start state, apply keys, compare the JSON of the result. */
    static void check(String name, String startJson, String[] keys, String expectJson) {
        EditorCore.CoreState s = EditorCore.fromJson(startJson);
        for (String k : keys) s = EditorCore.apply(s, k);
        String got = EditorCore.toJson(s);
        if (got.equals(expectJson)) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + "\n  got:    " + got + "\n  expect: " + expectJson); }
    }

    /**
     * Field oracle (T-051 observation 1): for chained vectors whose snapshot
     * stacks are too deep for full-JSON equality, assert the state fields
     * (lines / cursor / mode / undo depth) instead of the serialized JSON.
     */
    static void checkFields(String name, String startJson, String[] keys,
            String[] expLines, int expLine, int expCol, String expMode, int expDepth) {
        EditorCore.CoreState s = EditorCore.fromJson(startJson);
        for (String k : keys) s = EditorCore.apply(s, k);
        boolean ok = s.lines.equals(List.of(expLines))
                && s.cursorLine == expLine && s.cursorCol == expCol
                && expMode.equals(s.mode) && s.undoStack.size() == expDepth;
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name
                + "\n  got:    lines=" + s.lines + " cursor=" + s.cursorLine + ":" + s.cursorCol
                + " mode=" + s.mode + " undoDepth=" + s.undoStack.size()
                + "\n  expect: lines=" + List.of(expLines) + " cursor=" + expLine + ":" + expCol
                + " mode=" + expMode + " undoDepth=" + expDepth); }
    }

    /** Readability helper: keys("i", "a") -> String[]. */
    static String[] keys(String... k) { return k; }

    public static void main(String[] args) {
        // T-100 discipline: vectors are added one by one below, run after each.

        // Shared start states (canonical grammar; fromJson is lenient, toJson emits this exact form).
        String INITIAL = "{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}";

        // v01 (W-006): Right on an empty buffer - no-op (col 0 clamps to line length 0).
        check("v01.right.empty.noop", INITIAL, keys("Right"), INITIAL);

        // v02 (W-006): i then 'a' - lines ["a"], col 1, mode INSERT, undoStack holds the
        // ONE pre-insert snapshot. Mode changes are not undoable (no push for 'i'), so the
        // snapshot is taken before the 'a' keystroke - i.e. in snapshot-time mode INSERT.
        check("v02.i.a.insert.snapshot", INITIAL, keys("i", "a"),
                "{\"lines\":[\"a\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // v03 (W-006): type "hello" (5 keys after i) - lines ["hello"], col 5. Five mutations
        // in one chain make the serialized snapshot stack exponential (T-051 observation 1),
        // so this vector asserts the fields, not the JSON.
        checkFields("v03.hello.insert.fields", INITIAL, keys("i", "h", "e", "l", "l", "o"),
                new String[]{"hello"}, 0, 5, "INSERT", 5);

        // v04 (W-006): Enter splits the line at the cursor - W-006's golden expected is
        // ["hel","lo"], cursor line 1 col 0, i.e. "hello" split at col 3. The start state
        // is that chain's end result (typed "hello", cursor at col 3, INSERT) with the
        // history emptied to keep the JSON shallow (T-051 observation 1).
        String V04_START =
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"INSERT\",\"undoStack\":[]}";
        check("v04.enter.split", V04_START, keys("Enter"),
                "{\"lines\":[\"hel\",\"lo\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // v05 (W-006, chained from v04): Backspace at col 0 of line 1 joins the lines back
        // to ["hello"], cursor AT the join point (0:3 = length of the consumed "hel").
        // The start state IS v04's expected result (including its snapshot history).
        check("v05.backspace.join.col0",
                "{\"lines\":[\"hel\",\"lo\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}",
                keys("Backspace"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]},{\"lines\":[\"hel\",\"lo\"],"
                        + "\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":["
                        + "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}]}");

        // v06 (W-006): Escape then End - mode NORMAL, col clamped to the line's end.
        // Start = v05's content state, history emptied (shallow JSON, T-051 observation 1).
        String V06_START =
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"INSERT\",\"undoStack\":[]}";
        check("v06.escape.end", V06_START, keys("Escape", "End"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v07 (W-006): Down on a single-line buffer - no-op (the line index clamps).
        // Start = v06's expected result; cursor keys push no snapshot.
        check("v07.down.singleline.noop",
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("Down"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v08 (W-006): PageDown on a 3-line buffer - clamps to the last line. Deliberately
        // clamp-only: from line 0 any page size >= 2 lands on line 2, so the vector pins the
        // clamp, not the page size (header choice 3 / T-051 cross-check).
        String THREE_LINES_AT_0_1 =
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"NORMAL\",\"undoStack\":[]}";
        check("v08.pagedown.clamp.lastline", THREE_LINES_AT_0_1, keys("PageDown"),
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":2,\"col\":1},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v09 (W-006): Ctrl+End - cursor to the last line, col = that line's length.
        check("v09.ctrlend.lastline.eol", THREE_LINES_AT_0_1, keys("Ctrl+End"),
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":2,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v10 (W-006): x in NORMAL on "hello" with the cursor at col 0 - deletes the char at
        // the cursor, lines ["ello"], col stays 0; one snapshot (the NORMAL-mode pre-edit state).
        check("v10.x.normal.deleteAtCursor",
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("x"),
                "{\"lines\":[\"ello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\","
                        + "\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},"
                        + "\"mode\":\"NORMAL\",\"undoStack\":[]}]}");

        // v11 (W-006): d in NORMAL on a 2-line buffer - deletes the current line, 1 line
        // remains; cursor clamps to the remaining buffer (here: line 0 kept, col 1 <= len("two")).
        String V11_EXPECT =
                "{\"lines\":[\"two\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"NORMAL\","
                        + "\"undoStack\":[{\"lines\":[\"one\",\"two\"],\"cursor\":{\"line\":0,\"col\":1},"
                        + "\"mode\":\"NORMAL\",\"undoStack\":[]}]}";
        check("v11.d.deleteLine.clamp",
                "{\"lines\":[\"one\",\"two\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("d"), V11_EXPECT);

        // v12 (W-006, chained from v11): u after the d - restores the pre-delete state
        // exactly: buffer, cursor and mode come from the snapshot; the state's undoStack
        // becomes the stack MINUS the popped entry (the restore itself pushes nothing).
        check("v12.u.restoreAfterD", V11_EXPECT, keys("u"),
                "{\"lines\":[\"one\",\"two\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v13 (W-006): Delete at end of line 0 of 2 lines (INSERT) - joins the next line
        // onto this one, col kept at the join point.
        check("v13.delete.eol.join",
                "{\"lines\":[\"hel\",\"lo\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"INSERT\",\"undoStack\":[]}",
                keys("Delete"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"hel\",\"lo\"],\"cursor\":{\"line\":0,\"col\":3},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // v14 (W-006): Home after End - End parks at the line's end, Home returns to col 0.
        // Start at col 3 so the End key actually moves; neither pushes a snapshot.
        check("v14.home.after.end",
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("End", "Home"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v15 (W-006): Left at 0,0 - no-op (col clamps at 0).
        check("v15.left.origin.noop", INITIAL, keys("Left"), INITIAL);

        // --- v01-v15 = W-006's golden-vector table, complete. Below: the T-051
        // --- observation pins and W-006's coverage clause (see file header).

        // v16 (T-051 observation 2 pin): Delete is a strict no-op in NORMAL - the INSERT
        // delete keys are gated out of NORMAL (W-008 mode gates); byte-identical result.
        check("v16.pin.delete.noop.in.normal",
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("Delete"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v17 (T-051 observation 2 pin): cursor keys are strict no-ops in INSERT - the
        // whole NORMAL cursor table is gated out of INSERT; byte-identical result.
        check("v17.pin.cursorKeys.noop.in.insert",
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}",
                keys("Left", "Right", "Up", "Down", "Home", "End", "PageUp", "PageDown",
                        "Ctrl+Home", "Ctrl+End"),
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}");

        // v18a (T-051 observation 2 pin): undo restores carry the snapshot-time mode.
        // Snapshots taken in INSERT ('a','b'), then Escape -> NORMAL, then u: the restored
        // state comes back as INSERT (the mode it was in when the snapshot was pushed).
        String INSERT_EMPTY =
                "{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":[]}";
        check("v18a.pin.undo.restores.snapshotMode", INSERT_EMPTY, keys("a", "b", "Escape", "u"),
                "{\"lines\":[\"a\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"INSERT\","
                        + "\"undoStack\":[{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},"
                        + "\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // v18b (T-051 observation 2 pin, consequence of v18a): a bare second u after that
        // restore runs in the restored INSERT mode, so it INSERTS the letter "u" instead of
        // undoing (cascade undo needs Escape between steps when snapshots were INSERT-taken).
        // Field oracle: the stack after the restore is depth 1, the insert pushes -> 2.
        checkFields("v18b.pin.cascadeUndo.needsEscape", INSERT_EMPTY,
                keys("a", "b", "Escape", "u", "u"),
                new String[]{"au"}, 0, 2, "INSERT", 2);

        // v19 (W-006 coverage clause): Up moves to the previous line and clamps the col to
        // that line's length (from "hello"@5 up to "hi"@2).
        check("v19.up.prevline.colclamp",
                "{\"lines\":[\"hi\",\"hello\"],\"cursor\":{\"line\":1,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                keys("Up"),
                "{\"lines\":[\"hi\",\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v20 (W-006 coverage clause): PageUp clamps to the first line (clamp-only, like v08;
        // the page size stays unpinned per header choice 3), col clamped to line 0's length.
        String THREE_LINES_AT_2_4 =
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":2,\"col\":4},\"mode\":\"NORMAL\",\"undoStack\":[]}";
        check("v20.pageup.clamp.firstline", THREE_LINES_AT_2_4, keys("PageUp"),
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":0,\"col\":3},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // v21 (W-006 coverage clause): Ctrl+Home - cursor to line 0, col 0.
        check("v21.ctrlhome.origin", THREE_LINES_AT_2_4, keys("Ctrl+Home"),
                "{\"lines\":[\"one\",\"two\",\"three\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
