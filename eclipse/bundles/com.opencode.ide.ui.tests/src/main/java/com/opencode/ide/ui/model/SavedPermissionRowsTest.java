package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.SavedPermission;

/**
 * Unit tests for {@link SavedPermissionRows} (the SWT-free row building
 * behind the Saved permissions dialog, U-046 slice 2): no SWT, no HTTP.
 */
public class SavedPermissionRowsTest {

    @Test
    public void idsSortCaseInsensitivelyAndSkipUnusableEntries() {
        List<String> ids = SavedPermissionRows.ids(Arrays.asList(
                null,
                new SavedPermission(null),
                new SavedPermission("   "),
                new SavedPermission("perm_saved_9"),
                new SavedPermission("Perm_saved_1")));

        assertEquals(List.of("Perm_saved_1", "perm_saved_9"), ids);
    }

    @Test
    public void nullListYieldsEmptyIds() {
        assertTrue(SavedPermissionRows.ids(null).isEmpty());
    }

    @Test
    public void emptyListIsPlainEmptyNotTheEmptyStateSentence() {
        // the empty-state TEXT is the dialog's concern; rows stay plain data
        assertTrue(SavedPermissionRows.ids(List.of()).isEmpty());
    }

    @Test
    public void emptyStateSentenceExplainsWhereRulesComeFrom() {
        assertTrue(SavedPermissionRows.EMPTY_TEXT.contains("always"));
    }
}
