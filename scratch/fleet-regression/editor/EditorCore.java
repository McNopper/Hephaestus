public class EditorCore {
    public enum Mode { NORMAL, INSERT }
    public record Cursor(int line, int col) {}
    public record CoreState(String[] lines, Cursor cursor, Mode mode, CoreState[] undoStack) {}

    /** The startup state: one empty line, cursor 0/0, NORMAL, empty undo. */
    public static CoreState initial() {
        return new CoreState(new String[] {""}, new Cursor(0, 0), Mode.NORMAL, new CoreState[0]);
    }

    /** Serializes any CoreState to the exact JSON grammar below. */
    public static String toJson(CoreState s) {
        StringBuilder sb = new StringBuilder();
        writeState(sb, s);
        return sb.toString();
    }

    /** Parses that JSON back — the exact inverse of toJson. */
    public static CoreState fromJson(String json) {
        Parser parser = new Parser(json);
        CoreState state = parser.parseState();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing characters after CoreState");
        }
        return state;
    }

    /** Smoke: round-trip initial() and print the JSON twice — identical output. */
    public static void main(String[] args) {
        CoreState state = initial();
        String jsonFirst = toJson(state);
        String jsonSecond = toJson(fromJson(jsonFirst));
        System.out.println(jsonFirst);
        System.out.println(jsonSecond);
        System.out.println(jsonFirst.equals(jsonSecond) ? "ROUND-TRIP OK" : "ROUND-TRIP FAILED");
        // cursor/mode smoke
        CoreState s = initial();
        s = apply(s, "i");
        s = apply(s, "h");
        s = apply(s, "e");
        s = apply(s, "l");
        s = apply(s, "l");
        s = apply(s, "o");
        s = apply(s, "Enter");
        s = apply(s, "w");
        s = apply(s, "o");
        s = apply(s, "r");
        s = apply(s, "l");
        s = apply(s, "d");
        s = apply(s, "Escape");
        System.out.println(toJson(s));
    }

    /** Applies one key to a state — the pure core transition. Unrecognized keys are no-ops. */
    public static CoreState apply(CoreState s, String key) {
        if (key == null || key.isEmpty()) {
            return s;
        }
        boolean insert = s.mode() == Mode.INSERT;
        return switch (key) {
            // mode switches (work in their respective modes)
            case "i", "Insert" -> insert ? s : mode(s, Mode.NORMAL == s.mode() ? Mode.INSERT : s.mode());
            case "Escape" -> insert ? mode(s, Mode.NORMAL) : s;
            // cursor movement (any mode)
            case "Up" -> clampCursor(s, s.cursor().line() - 1, s.cursor().col());
            case "Down" -> clampCursor(s, s.cursor().line() + 1, s.cursor().col());
            case "Left" -> left(s);
            case "Right" -> right(s);
            case "Home" -> clampCursor(s, s.cursor().line(), 0);
            case "End" -> clampCursor(s, s.cursor().line(), s.lines()[s.cursor().line()].length());
            case "Ctrl+Home" -> clampCursor(s, 0, 0);
            case "Ctrl+End" -> clampCursor(s, s.lines().length - 1, s.lines()[s.lines().length - 1].length());
            case "PageUp" -> clampCursor(s, s.cursor().line() - 12, s.cursor().col());
            case "PageDown" -> clampCursor(s, s.cursor().line() + 12, s.cursor().col());
            // editing (INSERT mode only)
            case "Enter" -> insert ? pushUndo(s, splitLine(s)) : s;
            case "Backspace" -> insert ? pushUndo(s, backspace(s)) : s;
            case "Delete" -> insert ? pushUndo(s, deleteAt(s)) : s;
            // editing (NORMAL mode only)
            case "x" -> insert ? s : pushUndo(s, deleteCharAtCursor(s));
            case "d" -> insert ? s : pushUndo(s, deleteLine(s));
            case "u" -> insert ? s : undo(s);
            // single printable char in INSERT mode
            default -> insert && key.length() == 1 && !Character.isISOControl(key.charAt(0))
                    ? pushUndo(s, insertChar(s, key.charAt(0)))
                    : s;
        };
    }

    // ---- mode/cursor helpers (no undo push — mode changes and cursor moves are not undoable) ----

    private static CoreState mode(CoreState s, Mode newMode) {
        return new CoreState(s.lines(), s.cursor(), newMode, s.undoStack());
    }

    private static CoreState clampCursor(CoreState s, int line, int col) {
        int l = Math.max(0, Math.min(line, s.lines().length - 1));
        int c = Math.max(0, Math.min(col, s.lines()[l].length()));
        return new CoreState(s.lines(), new Cursor(l, c), s.mode(), s.undoStack());
    }

    private static CoreState left(CoreState s) {
        int line = s.cursor().line();
        int col = s.cursor().col();
        if (col > 0) {
            return clampCursor(s, line, col - 1);
        }
        if (line > 0) {
            return clampCursor(s, line - 1, s.lines()[line - 1].length());
        }
        return s;
    }

    private static CoreState right(CoreState s) {
        int line = s.cursor().line();
        int col = s.cursor().col();
        if (col < s.lines()[line].length()) {
            return clampCursor(s, line, col + 1);
        }
        if (line < s.lines().length - 1) {
            return clampCursor(s, line + 1, 0);
        }
        return s;
    }

    // ---- editing helpers (callers push undo before calling) ----

    private static CoreState insertChar(CoreState s, char c) {
        String[] lines = s.lines().clone();
        int line = s.cursor().line();
        int col = s.cursor().col();
        lines[line] = lines[line].substring(0, col) + c + lines[line].substring(col);
        return new CoreState(lines, new Cursor(line, col + 1), s.mode(), s.undoStack());
    }

    private static CoreState splitLine(CoreState s) {
        String[] old = s.lines();
        String[] lines = new String[old.length + 1];
        int line = s.cursor().line();
        int col = s.cursor().col();
        System.arraycopy(old, 0, lines, 0, line);
        lines[line] = old[line].substring(0, col);
        lines[line + 1] = old[line].substring(col);
        System.arraycopy(old, line + 1, lines, line + 2, old.length - line - 1);
        return new CoreState(lines, new Cursor(line + 1, 0), s.mode(), s.undoStack());
    }

    private static CoreState backspace(CoreState s) {
        int line = s.cursor().line();
        int col = s.cursor().col();
        if (col > 0) {
            String[] lines = s.lines().clone();
            lines[line] = lines[line].substring(0, col - 1) + lines[line].substring(col);
            return new CoreState(lines, new Cursor(line, col - 1), s.mode(), s.undoStack());
        }
        if (line > 0) {
            String[] old = s.lines();
            String[] lines = new String[old.length - 1];
            System.arraycopy(old, 0, lines, 0, line - 1);
            lines[line - 1] = old[line - 1] + old[line];
            System.arraycopy(old, line + 1, lines, line, old.length - line - 1);
            return new CoreState(lines, new Cursor(line - 1, old[line - 1].length()), s.mode(), s.undoStack());
        }
        return s;
    }

    private static CoreState deleteAt(CoreState s) {
        int line = s.cursor().line();
        int col = s.cursor().col();
        String current = s.lines()[line];
        if (col < current.length()) {
            String[] lines = s.lines().clone();
            lines[line] = current.substring(0, col) + current.substring(col + 1);
            return new CoreState(lines, s.cursor(), s.mode(), s.undoStack());
        }
        if (line < s.lines().length - 1) {
            String[] old = s.lines();
            String[] lines = new String[old.length - 1];
            System.arraycopy(old, 0, lines, 0, line);
            lines[line] = current + old[line + 1];
            System.arraycopy(old, line + 2, lines, line + 1, old.length - line - 2);
            return new CoreState(lines, s.cursor(), s.mode(), s.undoStack());
        }
        return s;
    }

    private static CoreState deleteCharAtCursor(CoreState s) {
        int line = s.cursor().line();
        int col = s.cursor().col();
        String current = s.lines()[line];
        if (col < current.length()) {
            String[] lines = s.lines().clone();
            lines[line] = current.substring(0, col) + current.substring(col + 1);
            return new CoreState(lines, clampCursor(s, line, Math.min(col, lines[line].length())).cursor(),
                    s.mode(), s.undoStack());
        }
        return s;
    }

    private static CoreState deleteLine(CoreState s) {
        if (s.lines().length <= 1) {
            return new CoreState(new String[] {""}, new Cursor(0, 0), s.mode(), s.undoStack());
        }
        String[] old = s.lines();
        String[] lines = new String[old.length - 1];
        int line = s.cursor().line();
        System.arraycopy(old, 0, lines, 0, line);
        System.arraycopy(old, line + 1, lines, line, old.length - line - 1);
        int newLine = Math.min(line, lines.length - 1);
        return new CoreState(lines, new Cursor(newLine, Math.min(s.cursor().col(), lines[newLine].length())),
                s.mode(), s.undoStack());
    }

    // ---- undo ----

    private static final int UNDO_CAP = 100;

    private static CoreState pushUndo(CoreState s, CoreState edited) {
        CoreState[] old = s.undoStack();
        CoreState[] stack;
        if (old.length >= UNDO_CAP) {
            stack = new CoreState[UNDO_CAP];
            System.arraycopy(old, old.length - UNDO_CAP + 1, stack, 0, UNDO_CAP - 1);
            stack[UNDO_CAP - 1] = s;
        } else {
            stack = new CoreState[old.length + 1];
            System.arraycopy(old, 0, stack, 0, old.length);
            stack[old.length] = s;
        }
        return new CoreState(edited.lines(), edited.cursor(), edited.mode(), stack);
    }

    private static CoreState undo(CoreState s) {
        CoreState[] stack = s.undoStack();
        if (stack.length == 0) {
            return s;
        }
        CoreState restored = stack[stack.length - 1];
        CoreState[] remaining = new CoreState[stack.length - 1];
        System.arraycopy(stack, 0, remaining, 0, stack.length - 1);
        return new CoreState(restored.lines(), restored.cursor(), restored.mode(), remaining);
    }

    private static void writeState(StringBuilder sb, CoreState s) {
        sb.append("{\"lines\":[");
        for (int i = 0; i < s.lines().length; i++) {
            if (i > 0) sb.append(',');
            writeString(sb, s.lines()[i]);
        }
        sb.append("],\"cursor\":{\"line\":").append(s.cursor().line())
          .append(",\"col\":").append(s.cursor().col())
          .append("},\"mode\":\"").append(s.mode().name())
          .append("\",\"undoStack\":[");
        for (int i = 0; i < s.undoStack().length; i++) {
            if (i > 0) sb.append(',');
            writeState(sb, s.undoStack()[i]);
        }
        sb.append("]}");
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(index);
        }

        void expect(char expected) {
            if (atEnd() || text.charAt(index) != expected) {
                throw error("expected '" + expected + "'");
            }
            index++;
        }

        CoreState parseState() {
            skipWhitespace();
            expect('{');
            String[] lines = null;
            Cursor cursor = null;
            Mode mode = null;
            CoreState[] undoStack = new CoreState[0];
            skipWhitespace();
            if (peek() != '}') {
                while (true) {
                    skipWhitespace();
                    String field = parseString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    switch (field) {
                        case "lines" -> lines = parseStringArray();
                        case "cursor" -> cursor = parseCursor();
                        case "mode" -> mode = parseMode();
                        case "undoStack" -> undoStack = parseStateArray();
                        default -> throw error("unknown field '" + field + "'");
                    }
                    skipWhitespace();
                    if (peek() == ',') {
                        index++;
                        continue;
                    }
                    break;
                }
            }
            expect('}');
            if (lines == null) throw error("missing field 'lines'");
            if (cursor == null) throw error("missing field 'cursor'");
            if (mode == null) throw error("missing field 'mode'");
            return new CoreState(lines, cursor, mode, undoStack);
        }

        String[] parseStringArray() {
            return parseRepeated('[', ']', this::parseString, String[]::new);
        }

        CoreState[] parseStateArray() {
            return parseRepeated('[', ']', this::parseState, CoreState[]::new);
        }

        <T> T[] parseRepeated(char open, char close, java.util.function.Supplier<T> element, java.util.function.IntFunction<T[]> maker) {
            skipWhitespace();
            expect(open);
            java.util.List<T> out = new java.util.ArrayList<>();
            skipWhitespace();
            if (peek() != close) {
                while (true) {
                    skipWhitespace();
                    out.add(element.get());
                    skipWhitespace();
                    if (peek() == ',') {
                        index++;
                        continue;
                    }
                    break;
                }
            }
            expect(close);
            return out.toArray(maker.apply(out.size()));
        }

        Cursor parseCursor() {
            skipWhitespace();
            expect('{');
            int line = 0;
            int col = 0;
            boolean hasLine = false;
            boolean hasCol = false;
            skipWhitespace();
            if (peek() != '}') {
                while (true) {
                    skipWhitespace();
                    String field = parseString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    switch (field) {
                        case "line" -> { line = parseInt(); hasLine = true; }
                        case "col" -> { col = parseInt(); hasCol = true; }
                        default -> throw error("unknown field '" + field + "'");
                    }
                    skipWhitespace();
                    if (peek() == ',') {
                        index++;
                        continue;
                    }
                    break;
                }
            }
            expect('}');
            if (!hasLine) throw error("missing field 'line'");
            if (!hasCol) throw error("missing field 'col'");
            return new Cursor(line, col);
        }

        Mode parseMode() {
            Mode mode = Mode.valueOf(parseString());
            return mode;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape sequence");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> throw error("invalid escape '\\" + escaped + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        int parseInt() {
            int start = index;
            if (!atEnd() && (peek() == '-' || peek() == '+')) {
                index++;
            }
            int digitStart = index;
            while (!atEnd() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index == digitStart) {
                throw error("expected an integer");
            }
            try {
                return Integer.parseInt(text.substring(start, index));
            } catch (NumberFormatException e) {
                throw error("invalid integer '" + text.substring(start, index) + "'");
            }
        }
    }
}
