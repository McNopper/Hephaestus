package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Model.Capabilities;
import com.opencode.ide.client.model.Model.Limit;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;

/**
 * Unit tests for the SWT-free {@link ModelRows} row derivation and column
 * formatting behind the Providers view: no SWT, no JFace, no Display.
 */
public class ModelRowsTest {

    // ---------- fixtures ----------

    private static Model model(String id, String name, String status, Capabilities caps, Limit limit) {
        return new Model(id, null, null, null, name, null, caps, null, limit, status,
                null, null, null, null);
    }

    private static Capabilities caps(boolean reasoning, boolean attachment, boolean toolcall) {
        return new Capabilities(true, reasoning, attachment, toolcall, null, null);
    }

    private static Provider provider(String id, String name, Model... models) {
        Map<String, Model> map = new LinkedHashMap<>();
        for (Model m : models) {
            map.put(m.id(), m);
        }
        return new Provider(id, name, null, null, null, null, map);
    }

    // ---------- toRows ----------

    @Test
    public void toRowsYieldsOneRowPerModelWithProviderIdentity() {
        ProviderList list = new ProviderList(
                List.of(
                        provider("zai", "Z.AI",
                                model("glm-5.3", "GLM 5.3", "active", null, new Limit(200_000L, 64_000L)),
                                model("glm-5.3-air", "GLM 5.3 Air", null, null, null)),
                        provider("github-copilot", null, // name null -> id used
                                model("gpt-5", "GPT 5", "beta", null, null))),
                null);

        List<ModelRow> rows = ModelRows.toRows(list);

        assertEquals(3, rows.size());
        assertEquals("Z.AI", rows.get(0).providerName());
        assertEquals("zai", rows.get(0).providerId());
        assertEquals("glm-5.3", rows.get(0).model().id());
        assertEquals("github-copilot", rows.get(2).providerName()); // fallback to id
        assertTrue(rows.stream().noneMatch(ModelRow::defaultModel)); // no defaults -> none marked
    }

    @Test
    public void toRowsMarksTheProvidersDefaultModel() {
        ProviderList list = new ProviderList(
                List.of(provider("zai", "Z.AI",
                        model("glm-5.3", "GLM 5.3", null, null, null),
                        model("glm-5.3-air", "GLM 5.3 Air", null, null, null))),
                Map.of("zai", "glm-5.3-air")); // the reserved-word "default" mapping, keyed by provider id

        List<ModelRow> rows = ModelRows.toRows(list);

        assertEquals(2, rows.size());
        assertFalse(rows.get(0).defaultModel());
        assertTrue(rows.get(1).defaultModel());
    }

    @Test
    public void toRowsIsTolerantOfNulls() {
        assertEquals(List.of(), ModelRows.toRows(null));
        assertEquals(List.of(), ModelRows.toRows(new ProviderList(null, null)));

        Provider empty = new Provider("empty", "Empty", null, null, null, null, null); // no models map
        Provider withModels = provider("p", "P", model("m1", "M1", null, null, null));
        List<ModelRow> rows = ModelRows.toRows(
                new ProviderList(java.util.Arrays.asList(null, empty, withModels), null)); // List.of rejects nulls

        assertEquals(List.of("m1"), rows.stream().map(r -> r.model().id()).toList());
    }

    // ---------- capabilities ----------

    @Test
    public void capabilitiesLettersFollowRATOrder() {
        assertEquals("RAT", ModelRows.capabilities(model("m", null, null, caps(true, true, true), null)));
        assertEquals("R", ModelRows.capabilities(model("m", null, null, caps(true, false, false), null)));
        assertEquals("A", ModelRows.capabilities(model("m", null, null, caps(false, true, false), null)));
        assertEquals("T", ModelRows.capabilities(model("m", null, null, caps(false, false, true), null)));
        assertEquals("RT", ModelRows.capabilities(model("m", null, null, caps(true, false, true), null)));
        assertEquals("", ModelRows.capabilities(model("m", null, null, caps(false, false, false), null)));
        assertEquals("", ModelRows.capabilities(model("m", null, null, null, null)));
        assertEquals("", ModelRows.capabilities(null));
    }

    // ---------- context ----------

    @Test
    public void contextFormatsThousandsAsK() {
        assertEquals("2000k", ModelRows.context(model("m", null, null, null, new Limit(2_000_000L, 0L))));
        assertEquals("128k", ModelRows.context(model("m", null, null, null, new Limit(128_000L, 0L))));
        assertEquals("", ModelRows.context(model("m", null, null, null, new Limit(0L, 0L))));
        assertEquals("", ModelRows.context(model("m", null, null, null, new Limit(-5L, 0L))));
        assertEquals("", ModelRows.context(model("m", null, null, null, null)));
        assertEquals("", ModelRows.context(null));
    }

    @Test
    public void contextValueIsTheRawNumericSortKey() {
        assertEquals(1_000_000L, ModelRows.contextValue(model("m", null, null, null, new Limit(1_000_000L, 0L))));
        assertEquals(0L, ModelRows.contextValue(model("m", null, null, null, null)));
        assertEquals(0L, ModelRows.contextValue(null));
    }
}
