package com.opencode.ide.ui.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.opencode.ide.client.model.Model;

/**
 * Unit tests for the SWT-free {@link ModelFilter} behind the Providers view:
 * case-insensitive substring over the visible fields; empty passes all.
 */
public class ModelFilterTest {

    private static ModelRow row(String providerName, String providerId, String modelId,
            String modelName, String status) {
        Model model = new Model(modelId, null, null, null, modelName, null, null, null, null,
                status, null, null, null, null);
        return new ModelRow(providerName, providerId, model, false);
    }

    private final ModelFilter filter = new ModelFilter();

    @Test
    public void emptyFilterAcceptsEverything() {
        ModelRow row = row("Z.AI", "zai", "glm-5.3", "GLM 5.3", "active");

        assertTrue(filter.accepts(row));
        filter.setFilter("   ");
        assertTrue(filter.accepts(row));
        filter.setFilter(null);
        assertTrue(filter.accepts(row));
        assertTrue(filter.accepts(row(null, null, null, null, null)));
    }

    @Test
    public void filterMatchesProviderNameAndIdCaseInsensitively() {
        ModelRow row = row("Z.AI GLM", "zai", "glm-5.3", "GLM 5.3", "active");

        filter.setFilter("z.ai"); // substring of the provider name, lower-cased
        assertTrue(filter.accepts(row));
        filter.setFilter("ZAI"); // case-insensitive on the provider id
        assertTrue(filter.accepts(row));
        filter.setFilter("anthropic");
        assertFalse(filter.accepts(row));
    }

    @Test
    public void filterMatchesModelIdNameAndStatus() {
        ModelRow row = row("Z.AI", "zai", "glm-5.3-air", "GLM 5.3 Air", "beta");

        filter.setFilter("air");
        assertTrue(filter.accepts(row)); // matches both id and name
        filter.setFilter("BETA");
        assertTrue(filter.accepts(row)); // status match
        filter.setFilter("glm-5.3-air");
        assertTrue(filter.accepts(row)); // exact id substring
        filter.setFilter("sonnet");
        assertFalse(filter.accepts(row));
    }

    @Test
    public void filterOnNullModelOnlyConsidersProviderFields() {
        ModelRow row = new ModelRow("GitHub Copilot", "github-copilot", null, false);

        filter.setFilter("copilot");
        assertTrue(filter.accepts(row));
        filter.setFilter("gpt-5"); // model fields are absent -> no match
        assertFalse(filter.accepts(row));
    }

    @Test
    public void filterTrimsSurroundingWhitespace() {
        ModelRow row = row("Z.AI", "zai", "glm-5.3", "GLM 5.3", "active");

        filter.setFilter("  glm  ");
        assertTrue(filter.accepts(row));
    }
}
