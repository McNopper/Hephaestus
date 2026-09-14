package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.CostOverview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Unit tests for the SWT-free {@link CostOverview}: the parser against the
 * EXACT producer strings of {@code FleetTelemetry.actualsComment} (copied
 * from the fleet's own golden tests), null/format tolerance, aggregation
 * across tickets and sprints (runs, per-ticket sort, backlog pseudo-sprint),
 * the one-line summary formats, the empty board, and the
 * {@link BoardModel#costOverview()} store access.
 */
public class CostOverviewTest {

    /** Golden producer strings — byte-identical to FleetTelemetryTest / TaskFleetTelemetryTest. */
    private static final String FULL =
            "fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22),"
                    + " agent executor, model zai-coding-plan/glm-5.2";
    private static final String COST_ONLY = "fleet actuals: cost 0.0123 USD";
    private static final String TOKENS_ONLY = "fleet actuals: tokens 3 (in 0 / out 3 / reasoning 0)";
    private static final String AGENT_MODEL_ONLY =
            "fleet actuals: agent executor, model zai-coding-plan/glm-5.2";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
    }

    private static Task task(String id, String sprint, String... comments) {
        Task t = new Task();
        t.id = id;
        t.title = "title " + id;
        t.sprint = sprint;
        for (String comment : comments) {
            t.comments.add(new Task.Comment(Instant.EPOCH, "fleet", comment));
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Parser (golden producer format)
    // ------------------------------------------------------------------

    @Test
    public void parseReadsTheExactProducerLine() {
        assertEquals(new CostOverview.CostRecord(0.0123, 6736L, 3L, 22L),
                CostOverview.CostRecord.parse(FULL));
    }

    @Test
    public void parseHandlesEveryProducerOmissionShape() {
        assertEquals("only cost known", new CostOverview.CostRecord(0.0123, null, null, null),
                CostOverview.CostRecord.parse(COST_ONLY));
        assertEquals("only tokens known", new CostOverview.CostRecord(null, 0L, 3L, 0L),
                CostOverview.CostRecord.parse(TOKENS_ONLY));
        assertEquals("agent/model-only is still a run, with unknown cost/tokens",
                new CostOverview.CostRecord(null, null, null, null),
                CostOverview.CostRecord.parse(AGENT_MODEL_ONLY));
    }

    @Test
    public void parseRejectsNonActualsComments() {
        assertNull("null text", CostOverview.CostRecord.parse(null));
        assertNull("empty", CostOverview.CostRecord.parse(""));
        assertNull("blank", CostOverview.CostRecord.parse("   "));
        assertNull("regular note", CostOverview.CostRecord.parse("looks good, ship it"));
        assertNull("cost without the prefix is not an actuals comment",
                CostOverview.CostRecord.parse("cost 1.0 USD, tokens 5 (in 1 / out 2 / reasoning 2)"));
        assertNull("prefix is case-sensitive",
                CostOverview.CostRecord.parse("Fleet actuals: cost 1.0 USD"));
    }

    @Test
    public void parseToleratesWhitespaceAndMalformedFields() {
        assertEquals("stray surrounding whitespace", 1.5,
                CostOverview.CostRecord.parse("  fleet actuals: cost 1.5 USD  ").costUsd(), 1e-12);
        assertEquals("missing USD suffix still parses", 2.0,
                CostOverview.CostRecord.parse("fleet actuals: cost 2").costUsd(), 1e-12);
        assertNull("garbage cost reads as unknown",
                CostOverview.CostRecord.parse("fleet actuals: cost oops USD").costUsd());
        assertNull("malformed tokens segment reads as unknown",
                CostOverview.CostRecord.parse("fleet actuals: tokens lots (in ? / out ? / reasoning ?)")
                        .tokensIn());
        assertEquals("prefix alone is still an actuals (empty) record",
                new CostOverview.CostRecord(null, null, null, null),
                CostOverview.CostRecord.parse("fleet actuals:"));
    }

    // ------------------------------------------------------------------
    // Aggregation
    // ------------------------------------------------------------------

    @Test
    public void aggregatesPerTicketPerSprintAndProject() {
        CostOverview overview = CostOverview.of(List.of(
                task("T1", "S-01", FULL, FULL),
                task("T2", "S-01",
                        "fleet actuals: cost 1.2 USD, tokens 100 (in 60 / out 40 / reasoning 0),"
                                + " agent executor, model a/b"),
                task("T3", "S-02", COST_ONLY),
                task("T4", null, AGENT_MODEL_ONLY)));

        assertEquals("every actuals comment is one run", 5, overview.project().runs());
        assertEquals(0.0246 + 1.2 + 0.0123, overview.project().costUsd(), 1e-9);
        assertEquals(13472 + 60, overview.project().tokensIn().longValue());
        assertEquals(6 + 40, overview.project().tokensOut().longValue());
        assertEquals(44, overview.project().tokensReasoning().longValue());

        assertEquals(3, overview.sprint("S-01").runs());
        assertEquals(0.0246 + 1.2, overview.sprint("S-01").costUsd(), 1e-9);
        assertEquals(1, overview.sprint("S-02").runs());
        assertEquals(0.0123, overview.sprint("S-02").costUsd(), 1e-9);
        assertNull("cost-less runs keep cost unknown",
                overview.sprint(BoardModel.BACKLOG).costUsd());
        assertEquals(1, overview.sprint(BoardModel.BACKLOG).runs());
        assertEquals("null sprintId reads as the backlog pseudo-sprint",
                overview.sprint(BoardModel.BACKLOG), overview.sprint(null));
    }

    @Test
    public void ticketsAreSortedByCostDescendingWithUnknownLast() {
        CostOverview overview = CostOverview.of(List.of(
                task("T1", "S-01", FULL, FULL),          // 0.0246
                task("T2", "S-01", "fleet actuals: cost 1.2 USD"),
                task("T3", "S-02", COST_ONLY),           // 0.0123
                task("T4", null, AGENT_MODEL_ONLY)));    // unknown

        assertEquals(List.of("T2", "T1", "T3", "T4"),
                overview.tickets().stream().map(CostOverview.TicketCost::id).toList());
        assertEquals(2, overview.tickets().get(1).runs());
        assertNull("tokens-less ticket keeps tokens unknown",
                overview.tickets().get(0).tokensIn());
        assertNull("cost-less ticket keeps cost unknown",
                overview.tickets().get(3).costUsd());
        assertEquals(BoardModel.BACKLOG, overview.tickets().get(3).sprint());
    }

    @Test
    public void sprintOrderIsNaturalWithBacklogLast() {
        CostOverview overview = CostOverview.of(List.of(
                task("T1", "S-02", COST_ONLY),
                task("T2", null, AGENT_MODEL_ONLY),
                task("T3", "S-01", COST_ONLY)));

        assertEquals(List.of("S-01", "S-02", BoardModel.BACKLOG),
                List.copyOf(overview.sprints().keySet()));
    }

    @Test
    public void nonActualsCommentsAndNullsNeverCount() {
        Task quiet = task("T1", "S-01", "regular note", "another note");
        quiet.comments.add(null);
        Task nullComments = task("T2", "S-01");
        nullComments.comments = null;

        CostOverview overview = CostOverview.of(
                java.util.Arrays.asList(quiet, nullComments, null));

        assertEquals(0, overview.project().runs());
        assertTrue(overview.tickets().isEmpty());
        assertEquals(0, CostOverview.of(null).project().runs());
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    @Test
    public void formatsSprintAndProjectSummaryLines() {
        CostOverview overview = CostOverview.of(List.of(
                task("T1", "S-01",
                        "fleet actuals: cost 1.2 USD, tokens 40000 (in 30000 / out 10000 / reasoning 0),"
                                + " agent executor, model a/b"),
                task("T2", "S-01", "fleet actuals: cost 0.03 USD"),
                task("T3", "S-02", "fleet actuals: tokens 12000 (in 9000 / out 3000 / reasoning 0)")));

        assertEquals("Sprint S-01: $1.23 \u00b7 30k in / 10k out tok \u00b7 2 runs",
                overview.formatSprintSummary("S-01"));
        assertEquals("cost-less sprint omits the $ segment",
                "Sprint S-02: 9k in / 3k out tok \u00b7 1 run",
                overview.formatSprintSummary("S-02"));
        assertEquals("Project: $1.23 \u00b7 39k in / 13k out tok \u00b7 3 runs",
                overview.formatSummary());
        assertEquals("Backlog: $1.23 \u00b7 1 run",
                CostOverview.of(List.of(task("T9", null, "fleet actuals: cost 1.23 USD")))
                        .formatSprintSummary(null));
    }

    @Test
    public void formattingKeepsTinyCostsPreciseAndCompactsTokens() {
        CostOverview tiny = CostOverview.of(List.of(task("T1", "S-03", COST_ONLY)));
        assertEquals("Sprint S-03: $0.0123 \u00b7 1 run", tiny.formatSprintSummary("S-03"));

        assertEquals("$1.23", CostOverview.usd(1.23));
        assertEquals("$0.0123", CostOverview.usd(0.0123));
        assertEquals("$0.012", CostOverview.usd(0.012));
        assertEquals("$0.50", CostOverview.usd(0.5));
        assertEquals("$0.00", CostOverview.usd(0));
        assertEquals("999", CostOverview.compact(999));
        assertEquals("1k", CostOverview.compact(1000));
        assertEquals("45k", CostOverview.compact(45000));
        assertEquals("6.8k", CostOverview.compact(6761));
        assertEquals("1.2M", CostOverview.compact(1_200_000));
    }

    @Test
    public void zeroCostBoardYieldsEmptySummary() {
        CostOverview overview = CostOverview.of(List.of(task("T1", "S-01", "no actuals here")));

        assertEquals("", overview.formatSummary());
        assertEquals("", overview.formatSprintSummary("S-01"));
        assertEquals("", overview.project().body());
        assertEquals("", overview.spentSuffix("S-01"));
        assertTrue(overview.tickets().isEmpty());
        assertTrue(overview.sprints().isEmpty());
    }

    @Test
    public void spentSuffixOnlyAppearsForPositiveKnownCost() {
        CostOverview overview = CostOverview.of(List.of(
                task("T1", "S-01", "fleet actuals: cost 1.23 USD"),
                task("T2", "S-02", AGENT_MODEL_ONLY),
                task("T3", "S-03", "fleet actuals: cost 0 USD")));

        assertEquals("$1.23 spent", overview.spentSuffix("S-01"));
        assertEquals("unknown cost -> no suffix", "", overview.spentSuffix("S-02"));
        assertEquals("zero cost -> no suffix", "", overview.spentSuffix("S-03"));
        assertEquals("unknown sprint -> no suffix", "", overview.spentSuffix("S-99"));
    }

    // ------------------------------------------------------------------
    // BoardModel access
    // ------------------------------------------------------------------

    @Test
    public void boardModelAggregatesCostActualsFromTheStore() {
        TaskStore store = new TaskStore(root);
        Task a = store.create("p", TaskStore.CreateSpec.of("ticket a"));
        Task b = store.create("p", TaskStore.CreateSpec.of("ticket b"));
        store.planSprint("p", "S-01", List.of(a.id, b.id), "goal");
        store.addComment("p", a.id, FULL, "fleet");
        store.addComment("p", a.id, FULL, "fleet");
        store.addComment("p", b.id, "fleet actuals: cost 1.2 USD", "fleet");
        store.addComment("p", b.id, "not an actuals comment", "human");

        CostOverview overview = new BoardModel(root, "p").costOverview();

        assertEquals(3, overview.project().runs());
        assertEquals(0.0246 + 1.2, overview.sprint("S-01").costUsd(), 1e-9);
        assertEquals("two runs on a, one on b", 3, overview.sprint("S-01").runs());
        assertEquals(2, overview.tickets().size());
        assertEquals("T-b sorts first at $1.20", b.id, overview.tickets().get(0).id());
    }

    @Test
    public void missingStoreYieldsEmptyCostOverviewWithoutThrowing() {
        CostOverview overview = new BoardModel(tmp.getRoot().toPath().resolve("nope"), "p")
                .costOverview();

        assertEquals(0, overview.project().runs());
        assertEquals("", overview.formatSummary());
        assertTrue(overview.tickets().isEmpty());
    }
}
