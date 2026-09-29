package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.IntegrationInfo;
import com.opencode.ide.ui.model.IntegrationRows.Row;

/**
 * Unit tests for {@link IntegrationRows} (the SWT-free row building behind
 * the read-only Integrations surface of the Server view, U-046 slice 2):
 * no SWT, no HTTP.
 */
public class IntegrationRowsTest {

    private static IntegrationInfo integration(String id, String name, int connections,
            IntegrationInfo.IntegrationMethod... methods) {
        return new IntegrationInfo(id, name, List.of(methods), connections);
    }

    @Test
    public void rowsSortCaseInsensitivelyAndSkipEntriesWithoutId() {
        // Arrays.asList (not List.of): the list deliberately contains a null entry
        List<Row> rows = IntegrationRows.rows(java.util.Arrays.asList(
                null,
                integration(null, "No id", 0),
                integration("  ", "Blank id", 0),
                integration("zai", "Z.AI", 1,
                        new IntegrationInfo.IntegrationMethod("key", List.of("ZAI_API_KEY"))),
                integration("Anthropic", "Anthropic", 0)));

        assertEquals(2, rows.size());
        assertEquals("Anthropic", rows.get(0).id());
        assertEquals("zai", rows.get(1).id());
    }

    @Test
    public void nullListYieldsEmptyRows() {
        assertTrue(IntegrationRows.rows(null).isEmpty());
    }

    @Test
    public void badgesRenderTypeAndNames() {
        Row row = IntegrationRows.rows(List.of(integration("anthropic", "Anthropic", 0,
                new IntegrationInfo.IntegrationMethod("key", List.of("ANTHROPIC_API_KEY")),
                new IntegrationInfo.IntegrationMethod("env", List.of("ANTHROPIC_KEY", "ANTHROPIC_TOKEN")),
                new IntegrationInfo.IntegrationMethod("oauth", List.of())))).get(0);

        assertEquals("key: ANTHROPIC_API_KEY", row.badges().get(0));
        assertEquals("env: ANTHROPIC_KEY, ANTHROPIC_TOKEN", row.badges().get(1));
        // an oauth method with no explicit names still badges as a method
        assertEquals("oauth: (any)", row.badges().get(2));
        assertEquals("key: ANTHROPIC_API_KEY  \u2022  env: ANTHROPIC_KEY, ANTHROPIC_TOKEN"
                + "  \u2022  oauth: (any)", row.badgesLabel());
    }

    @Test
    public void missingMethodsAndCountsReadGracefully() {
        Row row = IntegrationRows.rows(List.of(integration("x", null, 0))).get(0);

        assertEquals("no auth methods", row.badgesLabel());
        assertEquals("0 connections", row.connectionsLabel());
        assertNull("a nameless integration renders under its id only", row.nameLabel());

        Row one = IntegrationRows.rows(List.of(integration("y", "Y", 1))).get(0);
        assertEquals("1 connection", one.connectionsLabel());
    }

    @Test
    public void dialogTextListsIntegrationsWithBadgesAndCounts() {
        String text = IntegrationRows.dialogText("primary", List.of(
                integration("zai", "Z.AI", 1, new IntegrationInfo.IntegrationMethod("key", List.of("ZAI_KEY"))),
                integration("anthropic", "Anthropic", 0)));

        assertTrue(text.startsWith("2 integrations are available on primary:"));
        assertTrue(text.contains("anthropic (Anthropic)  \u2022  no auth methods  \u2022  0 connections"));
        assertTrue(text.contains("zai (Z.AI)  \u2022  key: ZAI_KEY  \u2022  1 connection"));
        // sorted: anthropic row above zai
        assertTrue(text.indexOf("anthropic") < text.indexOf("zai (Z.AI)"));
    }

    @Test
    public void dialogTextUsesSingularForOneIntegration() {
        String text = IntegrationRows.dialogText("primary",
                List.of(integration("zai", "Z.AI", 0)));

        assertTrue(text.startsWith("1 integration is available on primary:"));
    }

    @Test
    public void dialogTextForEmptyListAndMissingLabel() {
        assertEquals("No integrations are available on this server.",
                IntegrationRows.dialogText(null, List.of()));
        assertEquals("No integrations are available on remote.",
                IntegrationRows.dialogText("remote", null));
    }

    // ---------- per-method badge (shared with the connect dialog, U-048) ----------

    @Test
    public void methodBadgeRendersTypeAndNamesOfOneMethod() {
        assertEquals("key: A, B", IntegrationRows.methodBadge("key", List.of("A", "B")));
        assertEquals("oauth: (any)", IntegrationRows.methodBadge("oauth", List.of()));
        assertEquals("env: (any)", IntegrationRows.methodBadge("env", null));
    }

    @Test
    public void methodBadgeToleratesAMissingType() {
        assertEquals("(no type): (any)", IntegrationRows.methodBadge(null, null));
        assertEquals("(no type): (any)", IntegrationRows.methodBadge("   ", List.of()));
    }

    @Test
    public void usableSortsByIdAndSkipsEntriesWithoutOne() {
        List<IntegrationInfo> usable = IntegrationRows.usable(java.util.Arrays.asList(
                null,
                integration(null, "No id", 0),
                integration("zai", "Z.AI", 1),
                integration("Anthropic", "Anthropic", 0)));

        assertEquals(List.of("Anthropic", "zai"),
                usable.stream().map(IntegrationInfo::id).toList());
        assertTrue(IntegrationRows.usable(null).isEmpty());
    }
}
