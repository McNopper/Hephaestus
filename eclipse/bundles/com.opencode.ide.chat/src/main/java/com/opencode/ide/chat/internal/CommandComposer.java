package com.opencode.ide.chat.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.opencode.ide.client.model.CommandInfo;

/**
 * Composer logic for the project's custom slash commands (SWT-free): loads
 * them through the server connection, decides when the input's completion
 * picker applies, filters matching names, and maps a submission to its send
 * path - a custom command run on the session
 * ({@code POST /session/:id/command}) or a normal message. All parsing is
 * lenient: null/blank input and load failures degrade to "no commands"
 * instead of an error.
 */
public final class CommandComposer {

    /** Proposal limit of the picker (also the cap {@link #matches(String)} returns). */
    private static final int MAX_PROPOSALS = 8;

    /** The send path one submission takes. */
    public enum Kind {
        COMMAND, MESSAGE
    }

    /**
     * One resolved submission: a custom command plus the text after its name
     * as the single argument, or a plain message carrying the original text.
     */
    public record CommandSelection(Kind kind, CommandInfo command, List<String> arguments,
            String message) {

        static CommandSelection message(String text) {
            return new CommandSelection(Kind.MESSAGE, null, List.of(), text);
        }
    }

    private final ChatServerConnection connection;
    private volatile List<CommandInfo> commands = List.of();

    /**
     * @param connection server to load the commands from ({@code getClient()}
     *        may block - never call {@link #loadCommands()} on the UI thread)
     */
    public CommandComposer(ChatServerConnection connection) {
        this.connection = connection;
    }

    // ---------- loading ----------

    /** Loads the project's commands ({@code GET /command}); failures degrade to no commands. */
    public void loadCommands() {
        try {
            // scoped: commands live in the project's .opencode/command/, and
            // v2 resolves the list per location (unscoped = the server's home)
            commands = sanitize(connection.getClient().getCommands(connection.workingDirectory()));
        } catch (Throwable t) {
            commands = List.of();
        }
    }

    private static List<CommandInfo> sanitize(List<CommandInfo> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }
        List<CommandInfo> usable = new ArrayList<>();
        for (CommandInfo command : loaded) {
            if (command != null && command.name() != null && !command.name().isBlank()) {
                usable.add(command);
            }
        }
        usable.sort(Comparator.comparing(CommandInfo::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(usable);
    }

    // ---------- picker ----------

    /**
     * @return true while {@code text} is an unfinished slash command: it starts
     *         with {@code /} and no whitespace has completed the command name
     *         yet (arguments started, text cleared, or no leading slash all
     *         hide the picker)
     */
    public boolean isPickerTrigger(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '/') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Prefix filter over the loaded command names for the slash token being
     * typed (case-insensitive; a bare {@code /} proposes everything), in name
     * order, capped at {@value #MAX_PROPOSALS} entries.
     */
    public List<CommandInfo> matches(String text) {
        if (!isPickerTrigger(text)) {
            return List.of();
        }
        String token = commandToken(text);
        List<CommandInfo> result = new ArrayList<>();
        for (CommandInfo command : commands) {
            if (result.size() >= MAX_PROPOSALS) {
                break;
            }
            if (command.name().regionMatches(true, 0, token, 0, token.length())) {
                result.add(command);
            }
        }
        return result;
    }

    /**
     * Maps an explicitly picked command (picker Enter/Tab commit) to the send
     * path: COMMAND with the text after the command name as the single
     * argument ({@code /cmd rest of it} → {@code ["rest of it"]}); a null or
     * nameless command degrades to a plain MESSAGE of the original text.
     */
    public CommandSelection select(CommandInfo command, String text) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            return CommandSelection.message(text);
        }
        return new CommandSelection(Kind.COMMAND, command,
                argumentsAfter(command.name(), text), text);
    }

    /**
     * Send-path decision for a submitted text without an explicit pick: a
     * leading {@code /name} that exactly names a loaded command runs that
     * command with the remainder as its argument; anything else - plain text,
     * a slash mid-text, an unknown command - is a normal message.
     */
    public CommandSelection resolve(String text) {
        String token = commandToken(text);
        if (!token.isEmpty()) {
            for (CommandInfo command : commands) {
                if (command.name().equalsIgnoreCase(token)) {
                    return select(command, text);
                }
            }
        }
        return CommandSelection.message(text);
    }

    // ---------- helpers ----------

    /** The command name being typed: after the leading {@code /} up to the first whitespace. */
    private static String commandToken(String text) {
        if (text == null || text.length() < 2 || text.charAt(0) != '/') {
            return "";
        }
        int end = 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(1, end);
    }

    /** The text after {@code name} in {@code text} as a one-element argument list (empty when absent). */
    private static List<String> argumentsAfter(String name, String text) {
        String body = text == null ? "" : text;
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        if (body.regionMatches(true, 0, name, 0, name.length())) {
            body = body.substring(name.length());
        } else {
            int ws = indexOfWhitespace(body);
            body = ws < 0 ? "" : body.substring(ws);
        }
        String rest = body.strip();
        return rest.isEmpty() ? List.of() : List.of(rest);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
