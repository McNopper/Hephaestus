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
