package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

/**
 * Codec round-trip checks: the format rules that keep hand-edited files
 * loadable (CRLF/BOM, colons, unicode, unknown keys/sections) and machine
 * rewrites lossless (round-trip stability), plus the strict-failure modes.
 */
public class TaskFileCodecTest {

    private static Task fullTask() {
        Task t = new Task();
        t.id = "T-042";
        t.title = "Fix: the \u00e4 title with: colons";
        t.description = "Line one.\n\nLine two with ### noise and\n- not a todo";
        t.type = "bug";
        t.status = "in-progress";
        t.priority = "high";
        t.role = "developer";
        t.storyPoints = 8;
        t.sprint = "S-01";
        t.epic = "T-001";
        t.assignee = "agent-7";
        t.blocked = true;
        t.blocker = "waiting on API";
        t.labels = List.of("ui", "p1");
        t.acceptanceCriteria = List.of("GIVEN a user", "WHEN they click");
        t.createdAt = Instant.parse("2026-08-17T10:00:00.001Z");
        t.updatedAt = Instant.parse("2026-08-17T11:02:03.004Z");
        t.todos.add(new Task.Todo("write spec", false));
        t.todos.add(new Task.Todo("interview user", true));
        t.artifacts.add(new Task.Artifact("file", "src/x.java", "the fix", "dev",
                Instant.parse("2026-08-17T11:00:00.000Z")));
        t.comments.add(new Task.Comment(Instant.parse("2026-08-17T11:01:00.000Z"), "pm", "looks good"));
        t.history.add(new Task.HistoryEvent(Instant.parse("2026-08-17T10:00:00.001Z"), "created", null));
        return t;
    }

    @Test
    public void roundTripFullTask() {
        Task t = fullTask();
        String written = TaskFileCodec.write(t);
        Task back = TaskFileCodec.read(written);
        assertEquals(t.id, back.id);
        assertEquals(t.title, back.title);
        assertEquals(t.description, back.description);
        assertEquals(t.type, back.type);
        assertEquals(t.status, back.status);
        assertEquals(t.priority, back.priority);
        assertEquals(t.role, back.role);
        assertEquals(t.storyPoints, back.storyPoints);
        assertEquals(t.sprint, back.sprint);
        assertEquals(t.epic, back.epic);
        assertEquals(t.assignee, back.assignee);
        assertEquals(t.blocked, back.blocked);
        assertEquals(t.blocker, back.blocker);
        assertEquals(t.labels, back.labels);
        assertEquals(t.acceptanceCriteria, back.acceptanceCriteria);
        assertEquals(t.createdAt, back.createdAt);
        assertEquals(t.updatedAt, back.updatedAt);
        assertEquals(2, back.todos.size());
        assertEquals("write spec", back.todos.get(0).text());
        assertTrue(back.todos.get(1).done());
        assertEquals(1, back.artifacts.size());
        assertEquals("file", back.artifacts.get(0).kind());
        assertEquals(1, back.comments.size());
        assertEquals(1, back.history.size());
        // stability: write(parse(write)) is identical
        assertEquals(written, TaskFileCodec.write(back));
    }

    @Test
    public void toleratesCrlfAndBom() {
        String lf = TaskFileCodec.write(fullTask());
        String crlf = "\uFEFF" + lf.replace("\n", "\r\n");
        Task back = TaskFileCodec.read(crlf);
        assertEquals("T-042", back.id);
        assertEquals("Fix: the \u00e4 title with: colons", back.title);
    }

    @Test
    public void jsonLookingStringsAreQuotedAndSurvive() {
        Task t = new Task();
        t.id = "T-001";
        t.title = "123";                 // parses as a number -> must be quoted on write
        String written = TaskFileCodec.write(t);
        assertTrue(written.contains("title: \"123\""));
        assertEquals("123", TaskFileCodec.read(written).title);

        t.title = "true";
        written = TaskFileCodec.write(t);
        assertTrue(written.contains("title: \"true\""));
        assertEquals("true", TaskFileCodec.read(written).title);

        t.title = " spaced ";
        written = TaskFileCodec.write(t);
        assertEquals(" spaced ", TaskFileCodec.read(written).title);
    }

    @Test
    public void rawStringsStayRawWhenUnambiguous() {
        Task t = new Task();
        t.id = "T-001";
        t.title = "plain title with: colon";
        String written = TaskFileCodec.write(t);
        assertTrue(written.contains("title: plain title with: colon"));
        assertEquals(t.title, TaskFileCodec.read(written).title);
    }

    @Test
    public void unknownFrontmatterKeysAndSectionsPreserved() {
        String content = """
                ---
                id: T-009
                title: custom
                custom_key: keep me
                ---

                Description here.

                ## Todos
                - [ ] only todo

                ## Notes
                some hand-written notes
                over two lines
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals("keep me", t.extraFrontmatter.get("custom_key"));
        assertEquals(1, t.extraSections.size());
        assertTrue(t.extraSections.get(0).startsWith("## Notes"));
        assertTrue(t.extraSections.get(0).contains("over two lines"));
        String rewritten = TaskFileCodec.write(t);
        assertTrue(rewritten.contains("custom_key: keep me"));
        assertTrue(rewritten.contains("## Notes"));
        assertTrue(rewritten.contains("over two lines"));
        // and the second round-trip is stable
        assertEquals(rewritten, TaskFileCodec.write(TaskFileCodec.read(rewritten)));
    }

    @Test
    public void emptySectionsOmittedAndMinimalTaskRoundTrips() {
        Task t = new Task();
        t.id = "FR-017";
        t.title = "minimal";
        t.createdAt = t.updatedAt = Instant.now();
        String written = TaskFileCodec.write(t);
        assertTrue(!written.contains("## Todos"));
        assertTrue(!written.contains("## History"));
        Task back = TaskFileCodec.read(written);
        assertEquals("FR-017", back.id);
        assertEquals("minimal", back.title);
        assertTrue(back.todos.isEmpty());
    }

    @Test
    public void pythonOffsetTimestampsToleratedAndTruncatedToMillis() {
        String content = """
                ---
                id: T-001
                title: legacy
                created_at: 2026-07-16T04:24:36.093683+00:00
                updated_at: 2026-07-16T04:56:07.651404+00:00
                ---
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals(Instant.parse("2026-07-16T04:24:36.093Z"), t.createdAt);
        assertEquals(Instant.parse("2026-07-16T04:56:07.651Z"), t.updatedAt);
    }

    @Test
    public void missingFrontmatterFenceFails() {
        try {
            TaskFileCodec.read("no fence here");
            fail("expected FormatException");
        } catch (TaskFileCodec.FormatException expected) {
            assertTrue(expected.getMessage().contains("frontmatter"));
        }
    }

    @Test
    public void missingIdFails() {
        try {
            TaskFileCodec.read("---\ntitle: x\n---\n");
            fail("expected FormatException");
        } catch (TaskFileCodec.FormatException expected) {
            assertTrue(expected.getMessage().contains("id"));
        }
    }

    @Test
    public void malformedRecordLineIsQuarantinedNotFatal() {
        String content = """
                ---
                id: T-001
                title: x
                ---

                ## Artifacts
                not-json-at-all
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals("T-001", t.id);
        assertTrue(t.artifacts.isEmpty());
        assertEquals(1, t.quarantinedLines.size());
        assertTrue(t.quarantinedLines.get(0).startsWith("Artifacts: "));
        String rewritten = TaskFileCodec.write(t);
        assertFalse("the quarantined line is dropped on rewrite", rewritten.contains("not-json-at-all"));
        assertTrue("repair is stable", TaskFileCodec.read(rewritten).quarantinedLines.isEmpty());
    }

    @Test
    public void wrongTypedRecordFieldIsQuarantinedNotFatal() {
        // Review S1 regression: a JSON object where a string belongs throws
        // UnsupportedOperationException from Gson's getAsString - the narrow
        // catch let that escape and hide the WHOLE ticket. (Single-element
        // arrays are Gson-lenient and coerce; the object shape is the fatal
        // one, verified empirically.)
        String content = """
                ---
                id: T-009
                title: typed
                ---

                ## Comments
                {"ts":"2026-09-16T10:00:00.000Z","by":{"agent":true},"text":"by is an object"}
                {"ts":"2026-09-16T12:00:00.000Z","by":"pm","text":"healthy line"}
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals("T-009", t.id);
        assertEquals(1, t.comments.size());
        assertEquals("healthy line", t.comments.get(0).text());
        assertEquals(1, t.quarantinedLines.size());
        assertTrue(t.quarantinedLines.get(0).startsWith("Comments: "));
        String rewritten = TaskFileCodec.write(t);
        assertEquals(1, TaskFileCodec.read(rewritten).comments.size());
    }

    @Test
    public void backtickNPrefixedHistoryLineIsQuarantined() {
        // The exact live corruption (W-007): a PowerShell write accident
        // emitted a literal `n - the PS newline escape - before the JSON object.
        String content = """
                ---
                id: W-007
                title: drifted
                ---

                ## History
                {"ts":"2026-09-14T10:00:00.000Z","action":"created"}
                `n{"ts":"2026-09-14T11:00:00.000Z","action":"updated:status","by":"pm"}
                {"ts":"2026-09-14T12:00:00.000Z","action":"blocked:waiting","by":"pm"}
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals("W-007", t.id);
        assertEquals(2, t.history.size());
        assertEquals("created", t.history.get(0).action());
        assertEquals("blocked:waiting", t.history.get(1).action());
        assertEquals(1, t.quarantinedLines.size());
        assertTrue(t.quarantinedLines.get(0).startsWith("History: `n{"));
        String rewritten = TaskFileCodec.write(t);
        assertFalse(rewritten.contains("`n{"));
        assertEquals(2, TaskFileCodec.read(rewritten).history.size());
    }

    @Test
    public void nullAndDefaults() {
        String content = "---\nid: T-001\ntitle: t\nassignee: null\nblocked: false\n---\n";
        Task t = TaskFileCodec.read(content);
        assertNull(t.assignee);
        assertNotNull(t.createdAt);
        assertEquals("task", t.type);
        assertEquals("product-backlog", t.status);
        assertEquals("medium", t.priority);
        assertEquals("developer", t.role);
    }

    @Test
    public void descriptionWithH2HeadingBehaviorPinned() {
        // A description containing '## ' at line start is split: the head stays the
        // description, the tail becomes a preserved extra section. Pinned so a future
        // fix is a conscious decision, not an accident.
        Task t = new Task();
        t.id = "T-001";
        t.title = "x";
        t.description = "intro\n## Overview\nbody";
        t.createdAt = t.updatedAt = Instant.parse("2026-08-17T10:00:00.000Z");
        String written = TaskFileCodec.write(t);
        Task back = TaskFileCodec.read(written);
        assertEquals("intro", back.description);
        assertEquals(1, back.extraSections.size());
        assertTrue(back.extraSections.get(0).contains("## Overview"));
        assertTrue(back.extraSections.get(0).contains("body"));
        // content is not lost, and from the parsed state on the round-trip is stable
        String second = TaskFileCodec.write(back);
        assertEquals(second, TaskFileCodec.write(TaskFileCodec.read(second)));
    }

    @Test
    public void adversarialScalarsRoundTrip() {
        Task t = new Task();
        t.id = "T-001";
        t.title = "\u00e4\u00f6\u00fc \u4e2d\u6587 \ud83d\udd25 emoji";
        t.description = "--- looks like a fence\nbut is body text";
        t.blocker = "# not a comment";
        t.createdAt = t.updatedAt = Instant.parse("2026-08-17T10:00:00.000Z");
        String written = TaskFileCodec.write(t);
        Task back = TaskFileCodec.read(written);
        assertEquals(t.title, back.title);
        assertEquals(t.description, back.description);
        assertEquals(t.blocker, back.blocker);
        assertEquals(written, TaskFileCodec.write(back));

        // leading/trailing whitespace values survive via JSON quoting
        t.title = " padded ";
        t.blocker = "\ttabbed";
        back = TaskFileCodec.read(TaskFileCodec.write(t));
        assertEquals(" padded ", back.title);
        assertEquals("\ttabbed", back.blocker);
    }

    @Test
    public void sectionOrderAndDuplicatesTolerated() {
        String content = """
                ---
                id: T-001
                title: x
                ---

                ## History
                {"ts":"2026-08-17T10:00:00.000Z","action":"created"}

                ## Todos
                - [ ] first

                ## Todos
                - [x] second
                """;
        Task t = TaskFileCodec.read(content);
        assertEquals(1, t.history.size());
        assertEquals("sections may appear in any order; duplicates merge in arrival order",
                2, t.todos.size());
        assertTrue(t.todos.get(1).done());
        // write always emits canonical order: Todos before History
        String written = TaskFileCodec.write(t);
        assertTrue(written.indexOf("## Todos") < written.indexOf("## History"));
    }
}
