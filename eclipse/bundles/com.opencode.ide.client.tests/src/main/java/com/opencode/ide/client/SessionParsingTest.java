package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.Gson;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Unit tests for the {@link Session} / {@link SessionStatus} Gson mappings
 * (v2 shape: projectID + parentID for nesting, the model triple, time with an
 * idle stamp, location, tokens — the v1 {@code slug} and {@code share} are gone).
 */
public class SessionParsingTest {

    private static final Gson GSON = new Gson();

    private static final String PARENT_SESSION = """
            {
              "id": "ses_parent",
              "projectID": "f02fb234",
              "title": "Refactor the parser",
              "agent": "build",
              "model": { "id": "glm-5.3", "providerID": "zai-coding-plan", "variant": "high" },
              "time": { "created": 1786511177910, "updated": 1786511199000, "idle": 1786511200000 },
              "cost": 0.12,
              "tokens": { "input": 1000, "output": 500, "reasoning": 0,
                          "cache": { "read": 10, "write": 5 } },
              "outcome": "succeeded",
              "location": { "directory": "C:\\\\Development\\\\GitHub\\\\Hephaestus" }
            }
            """;

    private static final String CHILD_SESSION = """
            {
              "id": "ses_child",
              "title": "explore task",
              "agent": "explore",
              "parentID": "ses_parent",
              "time": { "created": 1786511177000, "updated": 1786511180000 }
            }
            """;

    @Test
    public void parentSessionMaps() {
        Session s = GSON.fromJson(PARENT_SESSION, Session.class);
        assertEquals("ses_parent", s.id());
        assertEquals("f02fb234", s.projectID());
        assertEquals("Refactor the parser", s.title());
        assertEquals("build", s.agent());
        assertNull("top-level session has no parentID", s.parentID());
        assertEquals(1786511199000L, s.time().updated());
        assertEquals(0.12, s.cost(), 0.0001);
        assertEquals(1000L, s.tokens().input());
        assertEquals(5L, s.tokens().cache().write());
        assertEquals("succeeded", s.outcome());
    }

    @Test
    public void modelTripleResolvesThroughConvenienceAccessors() {
        Session s = GSON.fromJson(PARENT_SESSION, Session.class);
        assertEquals("glm-5.3", s.modelId());
        assertEquals("zai-coding-plan", s.providerId());
        assertEquals("high", s.model().variant());
    }

    @Test
    public void locationYieldsTheWorkingDirectory() {
        Session s = GSON.fromJson(PARENT_SESSION, Session.class);
        assertEquals("C:\\Development\\GitHub\\Hephaestus", s.directory());
    }

    @Test
    public void idleStampMarksTheSessionIdle() {
        assertTrue("idle stamp present", GSON.fromJson(PARENT_SESSION, Session.class).isIdle());
        assertFalse("no idle stamp while running", GSON.fromJson(CHILD_SESSION, Session.class).isIdle());
    }

    @Test
    public void childSessionKeepsParentID() {
        Session s = GSON.fromJson(CHILD_SESSION, Session.class);
        assertEquals("ses_child", s.id());
        assertEquals("ses_parent", s.parentID());
        assertEquals("explore", s.agent());
    }

    @Test
    public void sessionStatusMaps() {
        SessionStatus status = GSON.fromJson("{\"type\":\"busy\"}", SessionStatus.class);
        assertEquals("busy", status.type());
    }

    @Test
    public void absentOptionalFieldsAreNull() {
        Session s = GSON.fromJson("{\"id\":\"ses_x\"}", Session.class);
        assertEquals("ses_x", s.id());
        assertNull(s.agent());
        assertNull(s.parentID());
        assertNull(s.title());
        assertNull(s.time());
        assertNull(s.tokens());
        assertNull(s.model());
        assertNull(s.location());
        assertNull(s.outcome());
        assertNull(s.modelId());
        assertNull(s.directory());
    }
}
