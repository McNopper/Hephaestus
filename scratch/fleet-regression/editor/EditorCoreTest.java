public class EditorCoreTest {
    static int passed = 0, failed = 0;

    static void check(String name, String startJson, String[] keys, String expectJson) {
        EditorCore.CoreState s = EditorCore.fromJson(startJson);
        for (String k : keys) s = EditorCore.apply(s, k);
        String got = EditorCore.toJson(s);
        if (got.equals(expectJson)) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + "\n  got:    " + got + "\n  expect: " + expectJson); }
    }

    static final String INIT = "{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}";

    public static void main(String[] args) {
        // 1. Right on empty buffer: no-op (col 0 clamps to line length 0)
        check("right-on-empty", INIT, new String[]{"Right"},
                INIT);

        // 2. i then 'a': lines ["a"], col 1, mode INSERT, 1 undo snapshot
        check("insert-a", INIT, new String[]{"i", "a"},
                "{\"lines\":[\"a\"],\"cursor\":{\"line\":0,\"col\":1},\"mode\":\"INSERT\",\"undoStack\":[{\"lines\":[\"\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // 3. Home after typing
        check("home-after-type", "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"Home"},
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 4. End
        check("end", "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"End"},
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":5},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 5. Down on single-line: no-op
        check("down-single", "{\"lines\":[\"hi\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"Down"},
                "{\"lines\":[\"hi\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 6. Down on multi-line
        check("down-multi", "{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"Down"},
                "{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 7. PageDown clamps to last line (col 0 is valid on "c", length 1)
        check("pagedown-clamps", "{\"lines\":[\"a\",\"b\",\"c\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"PageDown"},
                "{\"lines\":[\"a\",\"b\",\"c\"],\"cursor\":{\"line\":2,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 8. Ctrl+End
        check("ctrl-end", "{\"lines\":[\"ab\",\"cde\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"Ctrl+End"},
                "{\"lines\":[\"ab\",\"cde\"],\"cursor\":{\"line\":1,\"col\":3},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 9. x in NORMAL deletes char at cursor
        check("x-deletes", "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"x"},
                "{\"lines\":[\"ello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}]}");

        // 10. d deletes line
        check("d-deletes-line", "{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}",
                new String[]{"d"},
                "{\"lines\":[\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}]}");

        // 11. u after d restores
        check("u-restores", "{\"lines\":[\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}]}",
                new String[]{"u"},
                "{\"lines\":[\"a\",\"b\"],\"cursor\":{\"line\":0,\"col\":0},\"mode\":\"NORMAL\",\"undoStack\":[]}");

        // 12. Enter splits the line
        check("enter-splits", "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}",
                new String[]{"Enter"},
                "{\"lines\":[\"he\",\"llo\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":[{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // 13. Backspace at col 0 joins lines
        check("backspace-joins", "{\"lines\":[\"he\",\"llo\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":[]}",
                new String[]{"Backspace"},
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[{\"lines\":[\"he\",\"llo\"],\"cursor\":{\"line\":1,\"col\":0},\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // 14. Delete at EOL joins next line
        check("delete-joins", "{\"lines\":[\"he\",\"llo\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}",
                new String[]{"Delete"},
                "{\"lines\":[\"hello\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[{\"lines\":[\"he\",\"llo\"],\"cursor\":{\"line\":0,\"col\":2},\"mode\":\"INSERT\",\"undoStack\":[]}]}");

        // 15. Left at 0,0: no-op
        check("left-at-origin", INIT, new String[]{"Left"}, INIT);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
