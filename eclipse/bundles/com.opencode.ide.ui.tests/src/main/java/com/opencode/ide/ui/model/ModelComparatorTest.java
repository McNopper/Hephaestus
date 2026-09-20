package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Model.Limit;

/**
 * Unit tests for the SWT-free {@link ModelComparator} behind the Providers
 * view: per-column sorting, numeric context ordering and deterministic
 * tie-breaks.
 */
public class ModelComparatorTest {

    private static final int PROVIDER = 0;
    private static final int MODEL = 1;
    private static final int ID = 2;
    private static final int STATUS = 3;
    private static final int CONTEXT = 5;
    private static final int DEFAULT = 6;

    // ---------- fixtures ----------

    private static ModelRow row(String providerName, String modelId, String modelName,
            String status, long context, boolean defaultModel) {
        Model model = new Model(modelId, null, null, null, modelName, null, null, null,
                new Limit(context, 0L), status, null, null, null, null);
        return new ModelRow(providerName, providerName.toLowerCase(), model, defaultModel);
    }

    private final ModelComparator comparator = new ModelComparator();

    private List<ModelRow> sort(ModelRow... rows) {
        List<ModelRow> list = new ArrayList<>(Arrays.asList(rows));
        list.sort(comparator);
        return list;
    }

    private static List<String> ids(List<ModelRow> rows) {
        return rows.stream().map(r -> r.model().id()).toList();
    }

    // ---------- direction ----------

    @Test
    public void initialSortIsProviderAscendingAndSameColumnFlipsDirection() {
        ModelRow b = row("Beta", "b", null, null, 0L, false);
        ModelRow a = row("alpha", "a", null, null, 0L, false);
        ModelRow c = row("Gamma", "c", null, null, 0L, false);

        assertTrue(comparator.isAscending());
        assertEquals(List.of("a", "b", "c"), ids(sort(a, b, c))); // case-insensitive

        comparator.applySort(PROVIDER);
        assertFalse(comparator.isAscending());
        assertEquals(List.of("c", "b", "a"), ids(sort(a, b, c))); // reversed
    }

    @Test
    public void newColumnResetsToAscending() {
        comparator.applySort(PROVIDER); // provider now descending
        assertFalse(comparator.isAscending());

        comparator.applySort(ID);
        assertTrue(comparator.isAscending());
    }

    // ---------- columns ----------

    @Test
    public void modelNameColumnSortsWithIdFallback() {
        ModelRow named = row("p", "id-1", "Zeta", null, 0L, false);
        ModelRow unnamed = row("p", "id-0", null, null, 0L, false); // falls back to id

        comparator.applySort(MODEL);
        assertEquals(List.of("id-0", "id-1"), ids(sort(named, unnamed))); // "id-0" < "Zeta"
    }

    @Test
    public void contextColumnSortsNumerically() {
        ModelRow mega = row("p", "m", null, null, 1_000_000L, false);   // "1000k"
        ModelRow kilo = row("p", "k", null, null, 999_000L, false);     // "999k"
        ModelRow small = row("p", "s", null, null, 100L, false);        // "0k"
        ModelRow none = row("p", "n", null, null, 0L, false);           // no limit value

        comparator.applySort(CONTEXT);
        assertEquals(List.of("n", "s", "k", "m"), ids(sort(mega, kilo, small, none))); // 0 < 100 < 999K < 1M

        comparator.applySort(CONTEXT); // flip
        assertEquals(List.of("m", "k", "s", "n"), ids(sort(mega, kilo, small, none)));
    }

    @Test
    public void defaultColumnSortsFalseBeforeTrueAscending() {
        ModelRow isDefault = row("p", "d", null, null, 0L, true);
        ModelRow normal = row("p", "n", null, null, 0L, false);

        comparator.applySort(DEFAULT);
        assertEquals(List.of("n", "d"), ids(sort(isDefault, normal)));

        comparator.applySort(DEFAULT);
        assertEquals(List.of("d", "n"), ids(sort(isDefault, normal)));
    }

    // ---------- tie-breaks ----------

    @Test
    public void tiesBreakByProviderThenModelNameDeterministically() {
        ModelRow pB = row("Beta", "x", "A", null, 0L, false);
        ModelRow pA_z = row("Alpha", "y", "Z", null, 0L, false);
        ModelRow pA_a = row("Alpha", "z", "A", null, 0L, false);

        comparator.applySort(STATUS); // all statuses equal -> tie
        assertEquals(List.of("z", "y", "x"), ids(sort(pB, pA_z, pA_a))); // Alpha/A, Alpha/Z, Beta/A

        comparator.applySort(STATUS); // flip: tie-breaks flip with the direction
        assertEquals(List.of("x", "y", "z"), ids(sort(pB, pA_z, pA_a)));
    }

    @Test
    public void textColumnsSortNullsFirst() {
        ModelRow named = row("p", "named", "Model", null, 0L, false);
        ModelRow nullProvider = new ModelRow(null, "p", named.model(), false);

        // fresh comparator already sorts by the provider column ascending
        List<ModelRow> sorted = sort(named, nullProvider);

        assertEquals(nullProvider, sorted.get(0));
        assertEquals(named, sorted.get(1));
    }
}
