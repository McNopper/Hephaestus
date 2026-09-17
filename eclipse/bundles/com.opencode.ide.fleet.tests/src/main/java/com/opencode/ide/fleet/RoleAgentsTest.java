package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.tasks.VStages;

/**
 * Unit tests for the {@link RoleAgents} dispatch table: every default
 * mapping, constructor overrides (replace, add, remove), the null
 * fallback for unknown roles, and the V-pipeline dispatch chain
 * (stage -> role -> agent) staying consistent for all stages.
 */
public class RoleAgentsTest {

    @Test
    public void everyDefaultMapping() {
        RoleAgents agents = new RoleAgents();
        assertEquals("executor", agents.agentFor("developer"));
        assertEquals("executor", agents.agentFor("tester"));
        assertEquals("manifest-author", agents.agentFor("architect"));
        assertEquals("the pm ROLE dispatches to the readable project-manager AGENT",
                "project-manager", agents.agentFor("pm"));
        assertEquals("the reviewer ROLE (U-021 autonomous acceptance) dispatches to the reviewer agent",
                "reviewer", agents.agentFor("reviewer"));
        assertEquals("cpp-tools", agents.agentFor("cpp-engineer"));
        assertEquals("graphics-expert", agents.agentFor("graphics-engineer"));
    }

    @Test
    public void unknownOrNullRoleFallsBackToServerDefault() {
        RoleAgents agents = new RoleAgents();
        assertNull(agents.agentFor("nosuchrole"));
        assertNull(agents.agentFor(""));
        assertNull(agents.agentFor(null));
    }

    @Test
    public void overridesReplaceAddAndRemoveWithoutTouchingDefaults() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("developer", "reviewer");
        overrides.put("auditor", "custom-agent");
        overrides.put("tester", null);

        RoleAgents agents = new RoleAgents(overrides);

        assertEquals("override replaces the default", "reviewer", agents.agentFor("developer"));
        assertEquals("override adds a new mapping", "custom-agent", agents.agentFor("auditor"));
        assertNull("null override removes the mapping (server default)", agents.agentFor("tester"));
        assertEquals("untouched defaults survive", "manifest-author", agents.agentFor("architect"));
        assertEquals("defaults are not shared with overridden instances",
                "executor", new RoleAgents().agentFor("developer"));
    }

    @Test
    public void everyVStageDispatchesThroughARoleToAnAgent() {
        RoleAgents agents = new RoleAgents();
        for (String stage : VStages.STAGES) {
            String role = VStages.roleOf(stage);
            assertNotNull("stage " + stage + " must resolve to a role", role);
            assertNotNull("role " + role + " (stage " + stage + ") must map to an agent",
                    agents.agentFor(role));
        }
    }

    @Test
    public void testImplementationStageDispatchesViaTester() {
        assertEquals("the stage's role comes from VStages (task_advance re-derives it)",
                "tester", VStages.roleOf("test-implementation"));
        assertEquals("stage -> roleOf -> agentFor gives the tester path",
                "executor", new RoleAgents().agentFor(VStages.roleOf("test-implementation")));
    }
}
