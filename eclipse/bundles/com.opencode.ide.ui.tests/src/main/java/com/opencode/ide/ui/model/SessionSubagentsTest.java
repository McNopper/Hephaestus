package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.ui.model.SessionSubagents.Row;

/**
 * Unit tests for {@link SessionSubagents}: the subagent nesting of the
 * Session Details view (U-041) — children are the sessions whose parentID is
 * the observed session, each with live status and token actuals. SWT-free.
 */
public class SessionSubagentsTest {

    private static final Session.Tokens TOKENS =
            new Session.Tokens(100, 20, 5, new Session.Cache(1, 2));

    private static Session session(String id, String parent, String title, String agent) {
        return new Session(id, null, title, agent, parent, null, null, null, TOKENS, null, null);
    }

    @Test
    public void childrenAreTheSessionsWhoseParentIsTheObservedOne() {
        List<Session> sessions = List.of(
                session("ses_parent", null, "Parent", "build"),
                session("ses_child_a", "ses_parent", "Explore", "explore"),
                session("ses_child_b", "ses_parent", null, null),
                session("ses_other", "ses_someone_else", "Not ours", "build"));

        List<Row> rows = SessionSubagents.rows("ses_parent", sessions, Map.of());

        assertEquals(2, rows.size());
        assertEquals("sorted by id (stable section order)", "ses_child_a", rows.get(0).sessionId());
        assertEquals("ses_child_b", rows.get(1).sessionId());
        assertEquals("Explore", rows.get(0).title());
        assertEquals("a title-less child falls back to its id", "ses_child_b", rows.get(1).title());
    }

    @Test
    public void statusComesFromTheActiveMapAndDefaultsToIdle() {
        List<Session> sessions = List.of(
                session("ses_a", "ses_parent", "A", "explore"),
                session("ses_b", "ses_parent", "B", "explore"));

        List<Row> rows = SessionSubagents.rows("ses_parent", sessions,
                Map.of("ses_b", new SessionStatus("busy")));

        assertEquals("absent from the active map = idle (v2 lists busy sessions only)",
                "idle", rows.get(0).status());
        assertEquals("busy", rows.get(1).status());
    }

    @Test
    public void tokenActualsAreTheSumOfInputOutputReasoning() {
        List<Row> rows = SessionSubagents.rows("ses_parent",
                List.of(session("ses_child", "ses_parent", "Child", "explore")), Map.of());

        assertEquals(Long.valueOf(125), rows.get(0).tokens());
        assertTrue(rows.get(0).detailLabel().contains("125"));
    }

    @Test
    public void nullTokensCostAndStatusesAreTolerated() {
        Session child = new Session("ses_child", null, null, null, "ses_parent", null, null,
                null, null, null, null);

        List<Row> rows = SessionSubagents.rows("ses_parent", List.of(child), null);

        assertEquals(1, rows.size());
        assertEquals("idle", rows.get(0).status());
        assertEquals("", rows.get(0).tokensLabel());
        assertEquals("", rows.get(0).costLabel());
        assertTrue("detail drops blank parts: " + rows.get(0).detailLabel(),
                rows.get(0).detailLabel().isBlank());
    }

    @Test
    public void blankParentOrNullListYieldsNoRows() {
        assertTrue(SessionSubagents.rows(null, List.of(session("x", null, "x", "a")), Map.of()).isEmpty());
        assertTrue(SessionSubagents.rows("  ", List.of(session("x", null, "x", "a")), Map.of()).isEmpty());
        assertTrue(SessionSubagents.rows("ses_parent", null, Map.of()).isEmpty());
        assertTrue(SessionSubagents.rows("ses_parent",
                List.of(new Session(null, null, null, null, "ses_parent", null, null, null, null,
                        null, null)), Map.of()).isEmpty());
    }
}
