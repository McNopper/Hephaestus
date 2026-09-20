package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ProviderList;

public class ChatSelectorStateTest {

    private final List<Agent> agents = List.of(agent("plan", "primary"), agent("build", "all"),
            agent("explore", "subagent"));
    private final ProviderList providers = new Gson().fromJson("""
            {"providers":[{"id":"p","models":{
              "m1":{"id":"m1","variants":{"high":{},"low":{}}},
              "m2":{"id":"m2"}
            }}]}
            """, ProviderList.class);

    @Test
    public void agentsAreListedAndSelectedByIdNotDisplayName() {
        // REGRESSION (live 2026-09-20): the selector listed display NAMES and
        // posted them to /session/:id/agent - v2 rejects "Build" with
        // AgentNotFoundError; the wire value is the id ("build")
        ChatSelectorState state = new ChatSelectorState();
        state.load(agents, providers, null, null);
        assertTrue(state.agents().contains("build"));
        assertFalse("display names are not wire values", state.agents().contains("Build"));
        assertEquals("the default fallback matches on the id", "build", state.agent());
        state.selectAgent("Build");
        assertEquals("a display name selects nothing", "build", state.agent());
        state.selectAgent("plan");
        assertEquals("plan", state.agent());
    }

    @Test
    public void commandSelectionWinsWhetherItArrivesBeforeOrAfterCatalog() {
        for (boolean before : List.of(true, false)) {
            ChatSelectorState state = new ChatSelectorState();
            if (before) {
                selectCommand(state);
            }
            state.load(agents, providers, new String[] { "p", "m1" }, null);
            if (!before) {
                selectCommand(state);
            }
            assertEquals("plan", state.agent());
            assertEquals("p/custom/model", state.model());
            assertTrue(state.models().contains("p/custom/model"));
            assertFalse(state.agents().contains("explore"));
        }
    }

    @Test
    public void latestUserChoiceSurvivesReloadIncludingExplicitDefault() {
        ChatSelectorState state = new ChatSelectorState();
        selectCommand(state);
        state.load(agents, providers, null, null);
        state.selectAgent("build");
        state.selectModel("");
        state.load(agents, providers, new String[] { "p", "m1" }, new String[] { "p", "m2" });
        assertEquals("build", state.agent());
        assertEquals("", state.model());
        assertTrue(state.variants().isEmpty());
    }

    @Test
    public void missingPreferredModelFallsBackAndAvailablePreferenceWins() {
        ChatSelectorState state = new ChatSelectorState();
        state.load(agents, providers, new String[] { "p", "missing" }, new String[] { "p", "m2" });
        assertEquals("p/m2", state.model());
        assertEquals("build", state.agent());
        state.load(agents, providers, new String[] { "p", "m1" }, new String[] { "p", "m2" });
        assertEquals("p/m1", state.model());
        assertEquals(List.of("high", "low"), state.variants());
        state.selectModel("p/m2");
        assertTrue(state.variants().isEmpty());
    }

    @Test
    public void unknownAgentDoesNotInjectSubagentAndEmptyCatalogIsUsable() {
        ChatSelectorState state = new ChatSelectorState();
        state.selectAgent("explore");
        state.load(agents, providers, null, null);
        assertEquals("build", state.agent());
        state.load(null, null, new String[0], null);
        assertNull(state.agent());
        assertEquals(List.of(""), state.models());
        assertEquals("", state.model());
    }

    /**
     * C--001 (live 2026-09-15): the server-derived fallback (first provider
     * in server order) is unstable across catalog fetches - a reload that
     * resolves a DIFFERENT fallback must never flip the effective model of a
     * conversation without user interaction. Only the current model actually
     * vanishing from the catalog legitimizes a switch.
     */
    @Test
    public void unstableFallbackNeverFlipsTheEffectiveModel() {
        ChatSelectorState state = new ChatSelectorState();
        state.load(agents, providers, null, new String[] { "p", "m1" });
        assertEquals("p/m1", state.model());
        // same catalog, fallback now resolves elsewhere: no user action -> no change
        state.load(agents, providers, null, new String[] { "p", "m2" });
        assertEquals("still the effective model", "p/m1", state.model());
        // the current model disappearing from the catalog IS a legitimate switch
        ProviderList withoutM1 = new Gson().fromJson("""
                {"providers":[{"id":"p","models":{"m2":{"id":"m2"}}}]}
                """, ProviderList.class);
        state.load(agents, withoutM1, null, new String[] { "p", "m2" });
        assertEquals("p/m2", state.model());
    }

    private static void selectCommand(ChatSelectorState state) {
        state.selectAgent("plan");
        state.selectModel("p/custom/model");
    }

    private static Agent agent(String id, String mode) {
        // v2 shape: the display NAME differs from the wire ID ("Build" vs
        // "build"); a selector that lists names posts an agent the server
        // rejects with AgentNotFoundError (live, 2026-09-20)
        String displayName = id.substring(0, 1).toUpperCase() + id.substring(1);
        return new Agent(id, displayName, null, mode, null, null, null, null, null, null);
    }
}
