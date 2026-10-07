package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

/**
 * The U-072 per-stage model contract: the installed resolver answers stage
 * lookups (null when uninstalled/unmapped/blank) and the launch precedence
 * puts it LAST - per-run override &gt; ticket model &gt; stage policy &gt;
 * server default - so a per-stage pick can never override an explicit
 * per-ticket or per-run decision.
 */
public class StageModelsTest {

    @After
    public void clearResolver() {
        StageModels.clear();
    }

    @Test
    public void uninstalledResolverYieldsNull() {
        assertNull(StageModels.forStage("implementation"));
    }

    @Test
    public void installedResolverAnswersKnownStagesAndIgnoresBlankOnes() {
        StageModels.install(stage -> "implementation".equals(stage) ? "z.ai/glm-4.7#high" : null);
        assertEquals("z.ai/glm-4.7#high", StageModels.forStage("implementation"));
        assertNull(StageModels.forStage("design"));
        assertNull(StageModels.forStage(null));
        assertNull(StageModels.forStage(" "));
    }

    @Test
    public void blankResolverResultsReadAsNull() {
        StageModels.install(stage -> "  ");
        assertNull(StageModels.forStage("implementation"));
    }

    @Test
    public void clearRestoresTheUninstalledBehavior() {
        StageModels.install(stage -> "provider/model");
        assertEquals("provider/model", StageModels.forStage("test-system"));
        StageModels.clear();
        assertNull(StageModels.forStage("test-system"));
    }

    @Test
    public void precedencePutsRunOverrideFirstThenTicketThenStage() {
        StageModels.install(stage -> "design".equals(stage) ? "stage/model" : null);
        assertEquals("run/model", TaskFleet.effectiveModel("run/model", "ticket/model", "design"));
        assertEquals("ticket/model", TaskFleet.effectiveModel(null, "ticket/model", "design"));
        assertEquals("ticket/model", TaskFleet.effectiveModel(" ", "ticket/model", "design"));
        assertEquals("stage/model", TaskFleet.effectiveModel(null, null, "design"));
        assertNull(TaskFleet.effectiveModel(null, null, "implementation"));
        StageModels.clear();
        assertNull(TaskFleet.effectiveModel(null, null, "design"));
    }
}
