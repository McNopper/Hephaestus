import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * EditorCore - self-contained editor core for the fleet-regression lane
 * (scratch/fleet-regression/editor/EditorCore.java, default package).
 *
 * RE-DELIVERY of W-008 via T-054 (2026-11-15). The original implementation
 * lived in an uncommitted scratch tree that was lost before verification;
 * problem statement: docs/w008-reverification-2026-11-15.md (recorded on
 * ticket T-051; re-delivery order T-054, basis W-008). This file
 * is rebuilt from the store specs alone: W-006's state grammar + codec
 * contract (chunk 1: JSON codec), W-008's mode gates (chunk 2: cursor+mode)
 * and editing transitions + undo protocol (chunk 3).
 *
 * State grammar (exact toJson output; compact, keys always in this order):
 *   {"lines":["..."],"cursor":{"line":L,"col":C},"mode":"NORMAL|INSERT","undoStack":[...]}
 * Every undoStack entry is a full CoreState in the same grammar. A snapshot
 * is taken before every MUTATING transition and carries the complete pre-edit
 * state INCLUDING its own undoStack (pinned by W-006 vector 12).
 *
 * UNDERSPECIFIED-DETAIL CHOICES (T-054: choose the simplest behavior
 * consistent with W-006's golden vectors and record it):
 *   1. JSON codec: compact output, fixed key order above, minimal standard
 *      JSON string escaping. fromJson is lenient about key order and
 *      whitespace, strict about the grammar's shape.
 *   2. Left/Right step within the current line only and clamp at col 0 /
 *      end of line; they do NOT wrap to the previous/next line (the vectors
 *      pin only the clamped ends: vectors 1 and 15).
 *   3. PageUp/PageDown move the cursor line by 10 lines (page size chosen;
 *      the vectors pin only the clamp, vector 8), clamped to the first/last
 *      line, col clamped to the new line's length.
 *   4. x on a non-empty line first clamps col to length-1, so x at end of
 *      line deletes the LAST character; afterwards col clamps to the
 *      shortened line (W-008: "delete char at cursor (if line non-empty);
 *      col clamps" - the empty line is the stated no-op case).
 *   5. d on a single-line buffer is a NO-OP (W-008: delete only "if it
 *      leaves >= 1 line"; a buffer always keeps at least one line). After a
 *      real delete, line and col clamp to the remaining buffer.
 *   6. u with an empty undoStack is a no-op.
 *   7. CoreState is immutable; apply never mutates its input. Snapshots are
 *      therefore structurally shared copies that can never diverge -
 *      observably equal to a full deep copy.
 *   8. Mode transitions (i/Insert -> INSERT, Escape -> NORMAL) are NOT
 *      undoable and push no snapshot (W-008 undo protocol). "Insert" in
 *      INSERT mode is a no-op: strict gates allow only Escape to leave
 *      INSERT.
 */
public final class EditorCore {

    public static final String MODE_NORMAL = "NORMAL";
    public static final String MODE_INSERT = "INSERT";

    /** Undo stack cap (W-008): at most 100 snapshots, oldest dropped. */
    public static final int UNDO_LIMIT = 100;

    /** Page size for PageUp/PageDown (header choice 3). */
    static final int PAGE_SIZE = 10;

    private EditorCore() {
    }

    /** Immutable editor state: buffer lines, cursor, mode, undo history. */
    public static final class CoreState {
        public final List<String> lines;
        public final int cursorLine;
        public final int cursorCol;
        public final String mode;
        public final List<CoreState> undoStack;

        CoreState(List<String> lines, int cursorLine, int cursorCol, String mode, List<CoreState> undoStack) {
            this.lines = lines;
            this.cursorLine = cursorLine;
            this.cursorCol = cursorCol;
            this.mode = mode;
            this.undoStack = undoStack;
        }
    }

    /** Fresh buffer: one empty line, cursor at 0:0, NORMAL mode, empty undo. */
    public static CoreState initial() {
        List<String> lines = new ArrayList<>();
        lines.add("");
        return new CoreState(lines, 0, 0, MODE_NORMAL, new ArrayList<>());
    }

    /**
     * Apply one key to a state and return the resulting state; the input
     * state is never mutated. Keys not handled in the current mode are strict
     * no-ops (W-008 mode gates): INSERT keys do nothing in NORMAL except
     * i/Insert; NORMAL keys do nothing in INSERT except Escape.
     */
    public static CoreState apply(CoreState state, String key) {
        if (state == null || key == null) {
            return state;
        }
        if (MODE_INSERT.equals(state.mode)) {
            return applyInsert(state, key);
        }
        return applyNormal(state, key);
    }

    // ------------------------------------------------------------------
    // INSERT mode: printable chars, Enter, Backspace, Delete, Escape
    // ------------------------------------------------------------------

    private static CoreState applyInsert(CoreState s, String key) {
        switch (key) {
            case "Enter":
                return snapshotAndEdit(s, EditorCore::splitLine);
            case "Backspace":
                if (s.cursorCol > 0 || s.cursorLine > 0) {
                    return snapshotAndEdit(s, EditorCore::backspace);
                }
                return s; // col 0 of line 0: nothing left to delete
            case "Delete":
                String line = s.lines.get(s.cursorLine);
                if (s.cursorCol < line.length()
                        || (s.cursorCol == line.length() && s.cursorLine < s.lines.size() - 1)) {
                    return snapshotAndEdit(s, EditorCore::deleteForward);
                }
                return s; // nothing right of the cursor and no next line to join
            case "Escape":
                return withMode(s, MODE_NORMAL);
            default:
                if (isPrintable(key)) {
                    return snapshotAndEdit(s, st -> insertText(st, key));
                }
                return s; // strict gate: every other key is a no-op in INSERT
        }
    }

    // ------------------------------------------------------------------
    // NORMAL mode: cursor table, x, d, u, i/Insert
    // ------------------------------------------------------------------

    private static CoreState applyNormal(CoreState s, String key) {
        switch (key) {
            case "Left":
                return s.cursorCol > 0 ? withCursor(s, s.cursorLine, s.cursorCol - 1) : s;
            case "Right":
                return s.cursorCol < s.lines.get(s.cursorLine).length()
                        ? withCursor(s, s.cursorLine, s.cursorCol + 1) : s;
            case "Up":
                return s.cursorLine > 0 ? clampIntoLine(s, s.cursorLine - 1) : s;
            case "Down":
                return s.cursorLine < s.lines.size() - 1 ? clampIntoLine(s, s.cursorLine + 1) : s;
            case "Home":
                return withCursor(s, s.cursorLine, 0);
            case "End":
                return withCursor(s, s.cursorLine, s.lines.get(s.cursorLine).length());
            case "PageUp":
                return clampIntoLine(s, Math.max(0, s.cursorLine - PAGE_SIZE));
            case "PageDown":
                return clampIntoLine(s, Math.min(s.lines.size() - 1, s.cursorLine + PAGE_SIZE));
            case "Ctrl+Home":
                return withCursor(s, 0, 0);
            case "Ctrl+End":
                return withCursor(s, s.lines.size() - 1, s.lines.get(s.lines.size() - 1).length());
            case "x":
                if (!s.lines.get(s.cursorLine).isEmpty()) {
                    return snapshotAndEdit(s, EditorCore::deleteAtCursor);
                }
                return s; // nothing to delete on an empty line
            case "d":
                if (s.lines.size() > 1) {
                    return snapshotAndEdit(s, EditorCore::deleteLine);
                }
                return s; // a buffer always keeps at least one line (header choice 5)
            case "u":
                return undo(s);
            case "i":
            case "Insert":
                return withMode(s, MODE_INSERT);
            default:
                return s; // strict gate: any other key (incl. printable) is a no-op in NORMAL
        }
    }

    // ------------------------------------------------------------------
    // Buffer transitions (each one is MUTATING and runs behind a snapshot)
    // ------------------------------------------------------------------

    /** INSERT Enter: split the line at the cursor; cursor to the new line, col 0. */
    private static CoreState splitLine(CoreState s) {
        String text = s.lines.get(s.cursorLine);
        List<String> lines = copy(s.lines);
        lines.set(s.cursorLine, text.substring(0, s.cursorCol));
        lines.add(s.cursorLine + 1, text.substring(s.cursorCol));
        return new CoreState(lines, s.cursorLine + 1, 0, s.mode, s.undoStack);
    }

    /** INSERT Backspace: delete left of the cursor; at col 0 join the previous line. */
    private static CoreState backspace(CoreState s) {
        List<String> lines = copy(s.lines);
        int line = s.cursorLine;
        int col = s.cursorCol;
        if (col > 0) {
            String text = lines.get(line);
            lines.set(line, text.substring(0, col - 1) + text.substring(col));
            return new CoreState(lines, line, col - 1, s.mode, s.undoStack);
        }
        // col == 0 && line > 0: join with the previous line, cursor at the join point
        String prev = lines.get(line - 1);
        lines.set(line - 1, prev + lines.get(line));
        lines.remove(line);
        return new CoreState(lines, line - 1, prev.length(), s.mode, s.undoStack);
    }

    /** INSERT Delete: delete right of the cursor; at end of line join the next line. */
    private static CoreState deleteForward(CoreState s) {
        List<String> lines = copy(s.lines);
        int line = s.cursorLine;
        int col = s.cursorCol;
        String text = lines.get(line);
        if (col < text.length()) {
            lines.set(line, text.substring(0, col) + text.substring(col + 1));
            return new CoreState(lines, line, col, s.mode, s.undoStack);
        }
        // col == length && line < last: join the next line onto this one
        lines.set(line, text + lines.get(line + 1));
        lines.remove(line + 1);
        return new CoreState(lines, line, col, s.mode, s.undoStack);
    }

    /** NORMAL x: delete the char at the cursor; col clamped (header choice 4). */
    private static CoreState deleteAtCursor(CoreState s) {
        List<String> lines = copy(s.lines);
        String text = lines.get(s.cursorLine);
        int col = Math.min(s.cursorCol, text.length() - 1);
        lines.set(s.cursorLine, text.substring(0, col) + text.substring(col + 1));
        return new CoreState(lines, s.cursorLine, Math.min(col, lines.get(s.cursorLine).length()),
                s.mode, s.undoStack);
    }

    /** NORMAL d: delete the whole current line; cursor clamps (header choice 5). */
    private static CoreState deleteLine(CoreState s) {
        List<String> lines = copy(s.lines);
        lines.remove(s.cursorLine);
        int line = Math.min(s.cursorLine, lines.size() - 1);
        return new CoreState(lines, line, Math.min(s.cursorCol, lines.get(line).length()),
                s.mode, s.undoStack);
    }

    /** INSERT printable char: insert at the cursor, col+1. */
    private static CoreState insertText(CoreState s, String text) {
        List<String> lines = copy(s.lines);
        String line = lines.get(s.cursorLine);
        lines.set(s.cursorLine, line.substring(0, s.cursorCol) + text + line.substring(s.cursorCol));
        return new CoreState(lines, s.cursorLine, s.cursorCol + text.length(), s.mode, s.undoStack);
    }

    // ------------------------------------------------------------------
    // Undo (W-008 protocol)
    // ------------------------------------------------------------------

    /** NORMAL u: pop the newest snapshot and restore it; the restore pushes nothing. */
    private static CoreState undo(CoreState s) {
        if (s.undoStack.isEmpty()) {
            return s; // header choice 6
        }
        List<CoreState> rest = new ArrayList<>(s.undoStack);
        CoreState restored = rest.remove(rest.size() - 1);
        return new CoreState(restored.lines, restored.cursorLine, restored.cursorCol, restored.mode, rest);
    }

    /**
     * Undo protocol (W-008): before every MUTATING transition push the full
     * pre-edit state; cap the stack at UNDO_LIMIT entries, dropping the
     * OLDEST. Mode changes and cursor moves are not mutating and never push.
     */
    private static CoreState snapshotAndEdit(CoreState s, UnaryOperator<CoreState> edit) {
        List<CoreState> stack = new ArrayList<>(s.undoStack);
        stack.add(s); // immutable states: the pushed snapshot IS the pre-edit state
        if (stack.size() > UNDO_LIMIT) {
            stack.remove(0);
        }
        return edit.apply(new CoreState(s.lines, s.cursorLine, s.cursorCol, s.mode, stack));
    }

    // ------------------------------------------------------------------
    // Small immutable-state helpers
    // ------------------------------------------------------------------

    private static CoreState withCursor(CoreState s, int line, int col) {
        return new CoreState(s.lines, line, col, s.mode, s.undoStack);
    }

    private static CoreState withMode(CoreState s, String mode) {
        return new CoreState(s.lines, s.cursorLine, s.cursorCol, mode, s.undoStack);
    }

    private static CoreState clampIntoLine(CoreState s, int targetLine) {
        int col = Math.min(s.cursorCol, s.lines.get(targetLine).length());
        return new CoreState(s.lines, targetLine, col, s.mode, s.undoStack);
    }

    private static List<String> copy(List<String> lines) {
        return new ArrayList<>(lines);
    }

    /** A printable character key: length 1, not a control character. */
    private static boolean isPrintable(String key) {
        return key.length() == 1 && key.charAt(0) >= 0x20 && key.charAt(0) != 0x7F;
    }

    // ------------------------------------------------------------------
    // JSON codec (W-005 chunk 1 semantics; grammar pinned by W-006)
    // ------------------------------------------------------------------

    public static String toJson(CoreState s) {
        StringBuilder sb = new StringBuilder();
        writeState(sb, s);
        return sb.toString();
    }

    public static CoreState fromJson(String json) {
        Parser p = new Parser(json);
        CoreState s = p.parseState();
        p.skipWs();
        if (!p.atEnd()) {
            throw p.error("trailing content");
        }
        return s;
    }

    private static void writeState(StringBuilder sb, CoreState s) {
        sb.append("{\"lines\":[");
        for (int i = 0; i < s.lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeString(sb, s.lines.get(i));
        }
        sb.append("],\"cursor\":{\"line\":").append(s.cursorLine)
                .append(",\"col\":").append(s.cursorCol)
                .append("},\"mode\":");
        writeString(sb, s.mode);
        sb.append(",\"undoStack\":[");
        for (int i = 0; i < s.undoStack.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeState(sb, s.undoStack.get(i));
        }
        sb.append("]}");
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Minimal recursive-descent parser for the CoreState JSON grammar. */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char c) {
            skipWs();
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        CoreState parseState() {
            expect('{');
            List<String> lines = new ArrayList<>();
            int cursorLine = 0;
            int cursorCol = 0;
            String mode = MODE_NORMAL;
            List<CoreState> undoStack = new ArrayList<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return new CoreState(lines, cursorLine, cursorCol, mode, undoStack);
            }
            while (true) {
                skipWs();
                String key = parseString();
                expect(':');
                switch (key) {
                    case "lines":
                        expect('[');
                        skipWs();
                        if (peek() == ']') {
                            pos++;
                            break;
                        }
                        while (true) {
                            lines.add(parseString());
                            skipWs();
                            char c = peek();
                            if (c == ',') {
                                pos++;
                                continue;
                            }
                            if (c == ']') {
                                pos++;
                                break;
                            }
                            throw error("expected ',' or ']' in lines");
                        }
                        break;
                    case "cursor":
                        expect('{');
                        skipWs();
                        if (peek() == '}') {
                            pos++;
                            break;
                        }
                        while (true) {
                            skipWs();
                            String ck = parseString();
                            expect(':');
                            if ("line".equals(ck)) {
                                cursorLine = parseNumber();
                            } else if ("col".equals(ck)) {
                                cursorCol = parseNumber();
                            } else {
                                skipValue();
                            }
                            skipWs();
                            char c = peek();
                            if (c == ',') {
                                pos++;
                                continue;
                            }
                            if (c == '}') {
                                pos++;
                                break;
                            }
                            throw error("expected ',' or '}' in cursor");
                        }
                        break;
                    case "mode":
                        mode = parseString();
                        break;
                    case "undoStack":
                        expect('[');
                        skipWs();
                        if (peek() == ']') {
                            pos++;
                            break;
                        }
                        while (true) {
                            undoStack.add(parseState());
                            skipWs();
                            char c = peek();
                            if (c == ',') {
                                pos++;
                                continue;
                            }
                            if (c == ']') {
                                pos++;
                                break;
                            }
                            throw error("expected ',' or ']' in undoStack");
                        }
                        break;
                    default:
                        skipValue();
                }
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
                throw error("expected ',' or '}' in state");
            }
            return new CoreState(lines, cursorLine, cursorCol, mode, undoStack);
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw error("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        int parseNumber() {
            skipWs();
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("expected a number");
            }
            return Integer.parseInt(text.substring(start, pos));
        }

        /** Skips any JSON value (used for unknown keys). */
        void skipValue() {
            skipWs();
            char c = peek();
            if (c == '"') {
                parseString();
                return;
            }
            if (c == '{' || c == '[') {
                int depth = 0;
                while (true) {
                    char d = text.charAt(pos++);
                    if (d == '"') {
                        pos--;
                        parseString();
                    } else if (d == '{' || d == '[') {
                        depth++;
                    } else if (d == '}' || d == ']') {
                        depth--;
                        if (depth == 0) {
                            return;
                        }
                    }
                }
            }
            if (c == '-' || Character.isDigit(c)) {
                parseNumber();
                return;
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return;
            }
            throw error("unexpected value");
        }
    }

    // ------------------------------------------------------------------
    // Smoke (W-008 AC5): type 'hello', Enter, 'world', Backspace, x, u -
    // each state printed as JSON. i/Escape are the mode switches the
    // sequence needs (typing is INSERT-only; x/u are NORMAL-only). The
    // trailing gate demos exercise AC4's strict mode gates explicitly.
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        String[] keys = {"i", "h", "e", "l", "l", "o", "Enter",
                         "w", "o", "r", "l", "d", "Backspace",
                         "Escape", "x", "u"};
        CoreState s = initial();
        System.out.println(String.format("%-9s", "initial") + toJson(s) + "  " + summarize(s));
        for (String key : keys) {
            s = apply(s, key);
            System.out.println(String.format("%-9s", key) + toJson(s) + "  " + summarize(s));
        }
        String finalJson = toJson(s);
        System.out.println("FINAL " + summarize(s));
        System.out.println("FINAL json length: " + finalJson.length());
        System.out.println("codec round-trip stable: " + toJson(fromJson(finalJson)).equals(finalJson));

        // AC4 gate demonstrations (strict mode gates), outside the AC5 sequence.
        // 'Home' is a NORMAL-mode cursor key; printable chars like 'u' or 'x'
        // are INSERT-mode input per W-008's table, not gate cases.
        CoreState g1 = apply(initial(), "i");   // -> INSERT
        CoreState g1Home = apply(g1, "Home");   // NORMAL cursor key in INSERT: no-op
        System.out.println("gate 'Home' in INSERT no-op: " + toJson(g1Home).equals(toJson(g1))
                + "  " + summarize(g1Home));
        CoreState g2 = apply(initial(), "Backspace"); // INSERT key in NORMAL: no-op
        System.out.println("gate 'Backspace' in NORMAL no-op: " + toJson(g2).equals(toJson(initial()))
                + "  " + summarize(g2));
    }

    /** Compact human-readable view (the full JSON is printed next to it). */
    private static String summarize(CoreState s) {
        return "[lines=" + s.lines + " cursor=" + s.cursorLine + ":" + s.cursorCol
                + " mode=" + s.mode + " undoDepth=" + s.undoStack.size() + "]";
    }
}
