package com.opencode.ide.fleet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The machine-readable verdict of an autonomous-acceptance review session
 * (U-021): the reviewer agent judges the ticket's acceptance criteria
 * against the recorded artifacts and the merged commit, and ends its final
 * reply with exactly one verdict line —
 * {@code VERDICT: PASS - <summary>}, {@code VERDICT: FAIL - <reasons>} or
 * {@code VERDICT: UNCLEAR - <doubt>} — which the engine parses and applies
 * through the task store (done&nbsp;+&nbsp;advance / send-back / stays
 * in-review with a comment).
 *
 * <p>{@link #parse} scans the reply's lines from the END (the protocol puts
 * the verdict last; taking the final one tolerates the word appearing in
 * quoted protocol text) and matches each line against {@link #VERDICT_LINE}
 * — case-insensitive, tolerant of leading markdown decoration (bullets,
 * bold, quotes) and of decoration between the word and the colon
 * ({@code **VERDICT**:}). Anything after the decision word, minus leading
 * separators ({@code - — : .} and spaces), is the reason. A reply without a
 * parseable verdict line yields {@code null} — the engine conservatively
 * treats that as an unclear outcome and never auto-advances on a malformed
 * review.</p>
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class ReviewVerdict {

    /** The three review outcomes the engine acts on. */
    public enum Decision { PASS, FAIL, UNCLEAR }

    /**
     * One candidate verdict line: optional decoration, the word, a colon,
     * the decision, the rest. A line that merely CONTAINS the word (prose
     * quoting the protocol) does not match — the decoration class cannot
     * bridge real words.
     */
    private static final Pattern VERDICT_LINE = Pattern.compile(
            "^[*_#>\\s-]*verdict[*_\\s]*:\\s*(pass|fail|unclear)\\b(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_REASON = "reviewer gave no reasons";

    private final Decision decision;
    private final String reason;

    private ReviewVerdict(Decision decision, String reason) {
        this.decision = decision;
        this.reason = reason;
    }

    /**
     * Parses the reviewer reply.
     *
     * @return the verdict of the LAST verdict line, or {@code null} when the
     *         text carries none
     */
    public static ReviewVerdict parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String[] lines = reply.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            ReviewVerdict verdict = parseLine(lines[i]);
            if (verdict != null) {
                return verdict;
            }
        }
        return null;
    }

    /** One candidate line: {@code [decoration]VERDICT[decoration]: <decision>[ - <reason>]}. */
    private static ReviewVerdict parseLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = VERDICT_LINE.matcher(line.strip());
        if (!matcher.matches()) {
            return null;
        }
        Decision decision = Decision.valueOf(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return new ReviewVerdict(decision, stripSeparators(matcher.group(2)));
    }

    /** Drops leading reason separators ({@code - — : .} and whitespace); blank becomes {@code null}. */
    private static String stripSeparators(String text) {
        int i = 0;
        while (i < text.length() && "-\u2014:. \t".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        String stripped = i == 0 ? text : text.substring(i);
        return stripped.isBlank() ? null : stripped;
    }

    /** @return the parsed decision (never {@code null}) */
    public Decision decision() {
        return decision;
    }

    /** @return the reason text after the decision word, or {@code null} when the line carried none */
    public String reason() {
        return reason;
    }

    /** @return the reason text, or {@code fallback} when the verdict line carried none */
    public String reasonOr(String fallback) {
        return reason == null ? fallback : reason;
    }

    /** @return the reason text, or a generic placeholder when the verdict line carried none */
    public String reasonOrDefault() {
        return reasonOr(DEFAULT_REASON);
    }

    @Override
    public String toString() {
        return "ReviewVerdict[" + decision + (reason == null ? "" : ": " + reason) + "]";
    }
}
