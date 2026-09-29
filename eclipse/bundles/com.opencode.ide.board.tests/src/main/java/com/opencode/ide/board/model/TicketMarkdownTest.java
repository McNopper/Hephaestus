package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for {@link TicketMarkdown}: the composed ticket document carries
 * every section, renders checklist items as literal [x]/[ ] text, keeps
 * markdown (e.g. mermaid fences in comments) top-level so diagrams render,
 * and stays stable for empty tickets.
 */
public class TicketMarkdownTest {

    @Test
    public void composesAllSections() {
        Task task = new Task();
        task.description = "Build the **store**";
        task.acceptanceCriteria.add("locks work");
        task.todos.add(new Task.Todo("write codec", true));
        task.todos.add(new Task.Todo("wire tools", false));
        task.artifacts.add(new Task.Artifact("file", "src/Store.java", "implemented", null, null));
        task.comments.add(new Task.Comment(Instant.parse("2026-08-23T10:00:00Z"), "build",
                "done, see diagram"));

        String doc = TicketMarkdown.document(task);

        assertTrue(doc.startsWith("Build the **store**"));
        assertTrue(doc.contains("## Acceptance criteria\n- [ ] locks work"));
        assertTrue(doc.contains("## Todos\n- [x] write codec\n- [ ] wire tools"));
        assertTrue(doc.contains("## Artifacts\n- `file` `src/Store.java` — implemented"));
        assertTrue(doc.contains("**[2026-08-23T10:00:00.000Z] build:**\n\ndone, see diagram"));
    }

    @Test
    public void commentMarkdownStaysTopLevelSoDiagramsRender() {
        Task task = new Task();
        task.comments.add(new Task.Comment(Instant.parse("2026-08-23T10:00:00Z"), "architect",
                "```mermaid\ngraph TD; A-->B;\n```"));

        String doc = TicketMarkdown.document(task);

        // top-level fence (not indented/quoted) -> the page's mermaid pass picks it up
        assertTrue(doc.contains("\n```mermaid\ngraph TD; A-->B;\n```"));
    }

    @Test
    public void emptyTicketRendersPlaceholders() {
        String doc = TicketMarkdown.document(new Task());

        assertTrue(doc.contains("_no description_"));
        assertTrue(doc.contains("## Acceptance criteria\n_(none)_"));
        assertTrue(doc.contains("## Todos\n_(none)_"));
        assertTrue(doc.contains("## Artifacts\n_(none)_"));
        assertTrue(doc.contains("## Comments\n_(none)_"));
    }

    @Test
    public void nullsAreTolerated() {
        Task task = new Task();
        task.description = null;
        task.todos.add(new Task.Todo(null, false));
        task.comments.add(new Task.Comment(Instant.parse("2026-08-23T10:00:00Z"), null, null));

        String doc = TicketMarkdown.document(task);

        assertTrue(doc.contains("_no description_"));
        assertTrue(doc.contains("- [ ] \n"));
        assertTrue(doc.contains("**[2026-08-23T10:00:00.000Z] ?:**"));
    }

    @Test
    public void commentsCapAtTenNewestLast() {
        Task task = new Task();
        for (int i = 1; i <= 12; i++) {
            task.comments.add(new Task.Comment(Instant.parse("2026-08-23T10:00:00Z"), "a", "c" + i));
        }

        String doc = TicketMarkdown.document(task);

        assertTrue(doc.contains("c3"));
        assertEquals(-1, doc.indexOf("c1\n")); // c1, c2 dropped (oldest)
        assertTrue(doc.indexOf("c3") < doc.indexOf("c12")); // chronological, newest last
    }

    /** U-026 FR-002/FR-003/FR-004: the details surface carries the movement trace. */
    @Test
    public void journeySectionListsMovementsWithTimestampsAuthorsAndReasons() {
        Task task = new Task();
        task.stage = "requirements";
        task.role = "pm";
        task.history.add(new Task.HistoryEvent(Instant.parse("2026-09-29T06:33:53.929Z"),
                "created (stage: requirements - entry)", null));
        task.history.add(new Task.HistoryEvent(Instant.parse("2026-09-29T06:33:53.929Z"),
                "advanced to system", "chat-session"));
        task.history.add(new Task.HistoryEvent(Instant.parse("2026-09-29T07:00:00.000Z"),
                "sent back to requirements: missing NFR", "reviewer"));

        String doc = TicketMarkdown.document(task);

        assertTrue(doc.contains("## Stage journey"));
        assertTrue(doc.contains("**2/10** stages visited"));
        assertTrue(doc.contains("[2026-09-29T06:33:53.929Z] chat-session · advanced requirements → system"));
        assertTrue("the send-back is highlighted with its reason (AC-002)",
                doc.contains("**[2026-09-29T07:00:00.000Z] reviewer · "
                        + "⚠ sent back from system: missing NFR — back to requirements**"));
    }

    /** U-026: the progress is duplicated in the details (NFR-REDUND-001); empty journeys say so. */
    @Test
    public void journeySectionShowsProgressAndPlaceholderWhenNothingMoved() {
        Task task = new Task();
        task.stage = "design";
        task.role = "developer";

        String doc = TicketMarkdown.document(task);

        assertTrue(doc.contains("## Stage journey"));
        assertTrue(doc.contains("**1/10** stages visited"));
        assertTrue(doc.contains("_(no movements)_"));
    }
}
