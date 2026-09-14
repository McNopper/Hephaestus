package com.opencode.ide.fleet.dispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opencode.ide.tasks.Task;

/**
 * SWT-free aggregation of the fleet cost actuals recorded as ticket comments
 * (the {@code fleet actuals: ...} lines {@code FleetTelemetry.actualsComment}
 * writes when a fleet run merges). Parses those comments tolerantly and rolls
 * them up per ticket, per sprint, and project-wide; the Board renders the
 * result (dialog + content-description suffix) without touching the store
 * again.
 *
 * <p>Producer format (comma-separated parts, every absent part omitted):
 * {@code fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22), agent build, model provider/model}.
 * The parser keys on the {@link #COMMENT_PREFIX} only — a prefixed comment is
 * always an actuals record (agent/model-only lines count as a run with unknown
 * cost/tokens); everything else is ignored. Missing or malformed fields read
 * as {@code null}; parsing never throws.</p>
 */
public final class CostOverview {
    public static final String BACKLOG = "(backlog)";

    /** The exact prefix every actuals comment starts with (see FleetTelemetry). */
    public static final String COMMENT_PREFIX = "fleet actuals:";

    /** Matches the producer's tokens segment exactly; group 1 is the (derivable) total. */
    private static final Pattern TOKENS = Pattern
            .compile("tokens (\\d+) \\(in (\\d+) / out (\\d+) / reasoning (\\d+)\\)");

    /** Tickets by cost descending; unknown cost last, ties by id for determinism. */
    private static final Comparator<TicketCost> BY_COST_DESC =
            Comparator.comparing(TicketCost::costUsd, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed()
                    .thenComparing(TicketCost::id, Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * One parsed actuals comment. Every field is {@code null} when the comment
     * does not carry it (or carries it malformed); tokens are all-or-nothing
     * because the producer writes them as one segment.
     */
    public record CostRecord(Double costUsd, Long tokensIn, Long tokensOut, Long tokensReasoning) {

        /**
         * Parses one comment text.
         *
         * @return the record, or {@code null} when the text is not an actuals
         *         comment (wrong/no prefix, {@code null}/blank text)
         */
        public static CostRecord parse(String comment) {
            if (comment == null) {
                return null;
            }
            String text = comment.strip();
            if (!text.startsWith(COMMENT_PREFIX)) {
                return null;
            }
            Double cost = null;
            Long in = null;
            Long out = null;
            Long reasoning = null;
            for (String part : text.substring(COMMENT_PREFIX.length()).split(",")) {
                String segment = part.strip();
                if (segment.startsWith("cost ")) {
                    cost = parseCost(segment.substring("cost ".length()));
                } else if (segment.startsWith("tokens ")) {
                    Matcher matcher = TOKENS.matcher(segment);
                    if (matcher.matches()) {
                        try {
                            in = Long.valueOf(matcher.group(2));
                            out = Long.valueOf(matcher.group(3));
                            reasoning = Long.valueOf(matcher.group(4));
                        } catch (NumberFormatException e) {
                            in = out = reasoning = null;
                        }
                    }
                }
                // agent/model segments carry nothing aggregatable — ignored
            }
            return new CostRecord(cost, in, out, reasoning);
        }

        private static Double parseCost(String raw) {
            String value = raw.strip();
            if (value.endsWith("USD")) {
                value = value.substring(0, value.length() - "USD".length()).strip();
            }
            try {
                double cost = Double.parseDouble(value);
                return Double.isFinite(cost) && cost >= 0 ? cost : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * Cost/token totals for one scope (ticket, sprint, project). Nullable
     * fields mean "no record carried it"; {@code runs} counts actuals
     * comments, including cost/token-less ones.
     */
    public record Totals(Double costUsd, Long tokensIn, Long tokensOut, Long tokensReasoning, int runs) {

        /**
         * The label-free one-liner body: {@code $1.23 · 45k in / 12k out tok · 9 runs}
         * — segments omitted when unknown, {@code ""} when there are no runs.
         */
        public String body() {
            if (runs <= 0) {
                return "";
            }
            List<String> segments = new ArrayList<>();
            if (costUsd != null) {
                segments.add(usd(costUsd));
            }
            if (tokensIn != null || tokensOut != null) {
                segments.add(compact(nullToZero(tokensIn)) + " in / "
                        + compact(nullToZero(tokensOut)) + " out tok");
            }
            segments.add(runs + (runs == 1 ? " run" : " runs"));
            return String.join(" \u00b7 ", segments);
        }

        /** {@code <label>: $1.23 · 45k in / 12k out tok · 9 runs}; {@code ""} when there are no runs. */
        public String formatSummary(String label) {
            String b = body();
            return b.isEmpty() ? "" : label + ": " + b;
        }
    }

    /** One ticket's aggregated actuals (only tickets with at least one run appear). */
    public record TicketCost(String id, String title, String sprint, int runs,
            Double costUsd, Long tokensIn, Long tokensOut, Long tokensReasoning) {
    }

    private final List<TicketCost> tickets;
    private final Map<String, Totals> sprints;
    private final Totals project;

    private CostOverview(List<TicketCost> tickets, Map<String, Totals> sprints, Totals project) {
        this.tickets = List.copyOf(tickets);
        this.sprints = Collections.unmodifiableMap(new LinkedHashMap<>(sprints));
        this.project = project;
    }

    /**
     * Aggregates the actuals comments of a snapshot of tickets (never throws;
     * {@code null} lists/tasks/comments tolerated).
     */
    public static CostOverview of(List<Task> tasks) {
        List<TicketCost> tickets = new ArrayList<>();
        Map<String, Accumulator> sprints = new LinkedHashMap<>();
        Accumulator project = new Accumulator();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null) {
                    continue;
                }
                Accumulator ticket = new Accumulator();
                String sprint = sprintKey(task.sprint);
                Accumulator sprintTotals = null;
                if (task.comments != null) {
                    for (Task.Comment comment : task.comments) {
                        CostRecord record = CostRecord.parse(comment == null ? null : comment.text());
                        if (record == null) {
                            continue;
                        }
                        if (sprintTotals == null) {
                            sprintTotals = sprints.computeIfAbsent(sprint, key -> new Accumulator());
                        }
                        ticket.add(record);
                        sprintTotals.add(record);
                        project.add(record);
                    }
                }
                if (ticket.runs > 0) {
                    tickets.add(new TicketCost(task.id, task.title, sprint, ticket.runs,
                            ticket.costKnown ? ticket.cost : null,
                            ticket.tokensKnown ? ticket.tokensIn : null,
                            ticket.tokensKnown ? ticket.tokensOut : null,
                            ticket.tokensKnown ? ticket.tokensReasoning : null));
                }
            }
        }
        tickets.sort(BY_COST_DESC);
        return new CostOverview(tickets, sortedSprints(sprints), project.toTotals());
    }

    /** The overview of a board with no actuals at all (also the error fallback). */
    public static CostOverview empty() {
        return of(List.of());
    }

    /** Tickets with at least one run, sorted by cost descending (unknown cost last). */
    public List<TicketCost> tickets() {
        return tickets;
    }

    /** Per-sprint totals keyed by sprint id ({@link #BACKLOG} for unassigned), real sprints sorted, backlog last. */
    public Map<String, Totals> sprints() {
        return sprints;
    }

    /** Project-wide totals. */
    public Totals project() {
        return project;
    }

    /** One sprint's totals; {@code null} when that sprint has no runs. */
    public Totals sprint(String sprintId) {
        return sprints.get(sprintKey(sprintId));
    }

    /** Project-wide one-liner ({@code Project: $1.23 · ... · 9 runs}); {@code ""} when nothing was recorded. */
    public String formatSummary() {
        return project.formatSummary("Project");
    }

    /** One sprint's one-liner ({@code Sprint S-01: ...} / {@code Backlog: ...}); {@code ""} when it has no runs. */
    public String formatSprintSummary(String sprintId) {
        Totals totals = sprint(sprintId);
        if (totals == null) {
            return "";
        }
        String key = sprintKey(sprintId);
        return totals.formatSummary(BACKLOG.equals(key) ? "Backlog" : "Sprint " + key);
    }

    /**
     * The Board content-description suffix: {@code "$1.23 spent"} when the
     * sprint's accumulated cost is known and positive, {@code ""} otherwise.
     */
    public String spentSuffix(String sprintId) {
        Totals totals = sprint(sprintId);
        if (totals == null || totals.costUsd() == null || totals.costUsd() <= 0) {
            return "";
        }
        return usd(totals.costUsd()) + " spent";
    }

    /** Maps a task's sprint field to the aggregation key (null/blank → backlog pseudo-sprint). */
    private static String sprintKey(String sprint) {
        return sprint == null || sprint.isBlank() ? BACKLOG : sprint;
    }

    /** Real sprint ids in natural order, then the backlog pseudo-sprint last. */
    private static Map<String, Totals> sortedSprints(Map<String, Accumulator> sprints) {
        List<String> ids = new ArrayList<>(sprints.keySet());
        ids.sort(Comparator.naturalOrder());
        ids.sort(Comparator.comparingInt(id -> BACKLOG.equals(id) ? 1 : 0)); // stable: backlog last
        Map<String, Totals> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, sprints.get(id).toTotals());
        }
        return out;
    }

    /**
     * {@code $1.23}; sub-dollar costs keep up to four decimals
     * ({@code $0.0123}, trailing zeros trimmed to at least cents — a single
     * fleet run usually costs a fraction of a cent, which 2 decimals would
     * round away).
     */
    public static String usd(double cost) {
        if (cost > 0 && cost < 1) {
            String four = String.format(Locale.ROOT, "%.4f", cost);
            int end = four.length();
            int keepFrom = four.indexOf('.') + 3; // at least two decimals
            while (end > keepFrom && four.charAt(end - 1) == '0') {
                end--;
            }
            return "$" + four.substring(0, end);
        }
        return String.format(Locale.ROOT, "$%.2f", cost);
    }

    /** Compact token count: {@code 999}, {@code 45k}, {@code 6.8k}, {@code 1.2M}. */
    public static String compact(long value) {
        if (value < 0) {
            return Long.toString(value);
        }
        if (value < 1000) {
            return Long.toString(value);
        }
        if (value < 1_000_000) {
            double k = value / 1000.0;
            return k == Math.rint(k)
                    ? String.format(Locale.ROOT, "%.0fk", k)
                    : String.format(Locale.ROOT, "%.1fk", k);
        }
        double m = value / 1_000_000.0;
        return m == Math.rint(m)
                ? String.format(Locale.ROOT, "%.0fM", m)
                : String.format(Locale.ROOT, "%.1fM", m);
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /** Mutable running sums; {@code known} flags separate "absent" from a real 0. */
    private static final class Accumulator {
        private int runs;
        private double cost;
        private boolean costKnown;
        private long tokensIn;
        private long tokensOut;
        private long tokensReasoning;
        private boolean tokensKnown;

        private void add(CostRecord record) {
            runs++;
            if (record.costUsd() != null) {
                cost += record.costUsd();
                costKnown = true;
            }
            if (record.tokensIn() != null) {
                tokensIn += record.tokensIn();
                tokensKnown = true;
            }
            if (record.tokensOut() != null) {
                tokensOut += record.tokensOut();
            }
            if (record.tokensReasoning() != null) {
                tokensReasoning += record.tokensReasoning();
            }
        }

        private Totals toTotals() {
            return new Totals(costKnown ? cost : null,
                    tokensKnown ? tokensIn : null,
                    tokensKnown ? tokensOut : null,
                    tokensKnown ? tokensReasoning : null,
                    runs);
        }
    }
}
