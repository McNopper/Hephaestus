package com.opencode.ide.client.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Locks the Gson - record mapping for the opencode v2 OpenAPI types, using
 * recorded JSON samples for {@code /api/info}, {@code /api/agent} and
 * {@code /api/model}. Exercises the tricky bits: the v2 permission rule shape
 * ({@code action/resource/effect}), the model {@code cost} as an ARRAY of price
 * tiers, and capabilities with string-array modalities.
 */
public class OpencodeModelParsingTest {

    private static final Gson GSON = new Gson();

    private static final String INFO_JSON = """
            { "version": "2.0.10", "pid": 21108, "urls": ["http://127.0.0.1:49374"], "paths": { "tmp": "C:\\\\Temp" } }
            """;

    /** Recorded shape of one {@code /api/agent} entry (v2 Agent.Info). */
    private static final String AGENTS_JSON = """
            [
              {
                "id": "build",
                "name": "Build",
                "description": "The default agent. Executes tools based on configured permissions.",
                "mode": "primary",
                "hidden": false,
                "permissions": [
                  { "action": "*", "resource": "*", "effect": "allow" },
                  { "action": "external_directory", "resource": "*", "effect": "ask" },
                  { "action": "read", "resource": "*.env", "effect": "ask" }
                ]
              },
              {
                "id": "explore",
                "name": "Explore",
                "description": "Fast read-only explorer.",
                "mode": "subagent",
                "hidden": false,
                "permissions": [ { "action": "read", "resource": "*", "effect": "allow" } ]
              }
            ]
            """;

    /** Recorded shape of one {@code /api/model} entry (v2 Model.Info). */
    private static final String MODEL_JSON = """
            {
              "id": "claude-opus-5",
              "modelID": "claude-opus-5",
              "providerID": "opencode",
              "name": "Claude Opus 5",
              "family": "claude-opus",
              "capabilities": {
                "temperature": true, "reasoning": true, "attachment": true, "tools": true,
                "input": ["text", "image", "pdf"],
                "output": ["text"]
              },
              "variants": ["none", "high", "max"],
              "cost": [
                { "input": 15.0, "output": 75.0, "cache": { "read": 1.5, "write": 18.75 } },
                { "tier": { "type": "context", "size": 200000 }, "input": 30.0, "output": 150.0,
                  "cache": { "read": 3.0, "write": 37.5 } }
              ],
              "limit": { "context": 200000, "output": 32000 },
              "status": "active",
              "enabled": true
            }
            """;

    @Test
    public void infoMapsToHealthStatus() {
        // getHealth() parses /api/info by hand; this locks the raw shape it reads
        com.google.gson.JsonObject info = com.google.gson.JsonParser.parseString(INFO_JSON).getAsJsonObject();
        assertEquals("2.0.10", info.get("version").getAsString());
    }

    @Test
    public void agentsMapV2Shape() {
        List<Agent> agents = GSON.fromJson(AGENTS_JSON,
                TypeToken.getParameterized(List.class, Agent.class).getType());

        assertEquals(2, agents.size());

        Agent build = agents.get(0);
        assertEquals("build", build.id());
        assertEquals("Build", build.name());
        assertEquals(Agent.MODE_PRIMARY, build.mode());
        assertFalse("v2 has no built-in marker; isNative is always false", build.isNative());
        assertFalse(build.isHidden());
        assertTrue(build.isPrimary());

        List<Agent.PermissionRule> rules = build.permissions();
        assertNotNull(rules);
        assertEquals(3, rules.size());
        assertEquals("*", rules.get(0).action());
        assertEquals("*", rules.get(0).resource());
        assertEquals("allow", rules.get(0).effect());
        // snake_case category names must round-trip verbatim
        assertEquals("external_directory", rules.get(1).action());
        assertEquals("ask", rules.get(1).effect());
        assertEquals("*.env", rules.get(2).resource());

        assertNull("optional model should be null when absent", build.model());

        Agent explore = agents.get(1);
        assertEquals(Agent.MODE_SUBAGENT, explore.mode());
        assertFalse(explore.isPrimary());
    }

    @Test
    public void modelMapsCostArrayAndStringModalities() {
        Model model = GSON.fromJson(MODEL_JSON, Model.class);

        assertEquals("claude-opus-5", model.id());
        assertEquals("opencode", model.providerID());
        assertTrue(model.capabilities().reasoning());
        assertTrue(model.capabilities().attachment());
        assertTrue(model.capabilities().toolcall());
        assertTrue(model.capabilities().inputText());
        assertTrue(model.capabilities().inputImage());
        assertTrue(model.capabilities().outputText());
        assertEquals(Model.STATUS_ACTIVE, model.status());
        assertEquals(200000L, model.limit().context());

        // cost is an ARRAY of price tiers: the base row first, context tiers after
        assertNotNull(model.cost());
        assertEquals(2, model.cost().size());
        assertEquals(75.0, model.cost().get(0).output(), 0.0001);
        assertEquals(18.75, model.cost().get(0).cache().write(), 0.0001);
        assertEquals(200000L, model.cost().get(1).tier().size());
        assertEquals(30.0, model.cost().get(1).input(), 0.0001);

        Model.Cost base = model.baseCost();
        assertNotNull(base);
        assertNull(base.tier());
        assertEquals(15.0, base.input(), 0.0001);

        assertEquals(List.of("none", "high", "max"), model.variantNames());
        assertTrue(model.hasVariant("high"));
        assertFalse(model.hasVariant("xhigh"));
    }
}
