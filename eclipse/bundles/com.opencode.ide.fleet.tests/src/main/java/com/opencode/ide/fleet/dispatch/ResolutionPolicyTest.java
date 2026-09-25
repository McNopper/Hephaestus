package com.opencode.ide.fleet.dispatch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * The pure U-031 route policy (paired unit tests of
 * {@code ResolutionPolicy.route}): definition-leg blocks go vertical
 * (previous stage), verification-leg blocks go horizontal (the V-level
 * pair), and the round-trip limit ends the agent routes with NEEDS-HUMAN.
 */
public class ResolutionPolicyTest {

    private static Task at(String stage) {
        Task task = new Task();
        task.id = "T-1";
        task.stage = stage;
        task.blocked = true;
        task.blocker = "stalled: the worker hung";
        return task;
    }

    @Test
    public void definitionLegBlocksGoVertical() {
        assertEquals(ResolutionPolicy.Route.VERTICAL,
                ResolutionPolicy.route(at("design"), 0));
        assertEquals(ResolutionPolicy.Route.VERTICAL,
                ResolutionPolicy.route(at("requirements"), 0));
    }

    @Test
    public void verificationLegBlocksGoHorizontal() {
        assertEquals("a test-design failure reports to design",
                ResolutionPolicy.Route.HORIZONTAL, ResolutionPolicy.route(at("test-design"), 0));
        assertEquals(ResolutionPolicy.Route.HORIZONTAL,
                ResolutionPolicy.route(at("test-implementation"), 1));
    }

    @Test
    public void theRoundTripLimitEndsTheAgentRoutes() {
        assertEquals(ResolutionPolicy.Route.NEEDS_HUMAN,
                ResolutionPolicy.route(at("design"), ResolutionPolicy.ATTEMPT_LIMIT));
        assertEquals("the attempt just below the limit still routes",
                ResolutionPolicy.Route.VERTICAL,
                ResolutionPolicy.route(at("design"), ResolutionPolicy.ATTEMPT_LIMIT - 1));
    }

    @Test
    public void unstagedOrUnknownStagesNeedTheHuman() {
        assertEquals(ResolutionPolicy.Route.NEEDS_HUMAN, ResolutionPolicy.route(new Task(), 0));
        assertEquals(ResolutionPolicy.Route.NEEDS_HUMAN, ResolutionPolicy.route(at("nope"), 0));
        assertEquals(ResolutionPolicy.Route.NEEDS_HUMAN, ResolutionPolicy.route(null, 0));
    }

    @Test
    public void everyStageHasARoute() {
        for (String stage : VStages.STAGES) {
            ResolutionPolicy.Route route = ResolutionPolicy.route(at(stage), 0);
            assertEquals("verification leg routes horizontally: " + stage,
                    VStages.isVerification(stage), route == ResolutionPolicy.Route.HORIZONTAL);
        }
    }
}
