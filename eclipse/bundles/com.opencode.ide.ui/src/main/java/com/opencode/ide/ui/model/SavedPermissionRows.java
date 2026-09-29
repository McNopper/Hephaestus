package com.opencode.ide.ui.model;

import java.util.List;

import com.opencode.ide.client.model.SavedPermission;

/**
 * Rows for the "Saved permissions" dialog (U-046 slice 2): converts the
 * client's {@code GET /permission/saved} list into sorted, null-tolerant
 * display rows. SWT-free so the mapping is unit-testable; the dialog only
 * renders. Remembered rules are user-global (no location scoping), and the
 * pinned server items carry little beyond the {@code id} - the id IS the row.
 */
public final class SavedPermissionRows {

    /** Empty-state sentence for the dialog when no rule is remembered. */
    public static final String EMPTY_TEXT = "No remembered permission rules. Rules appear here when a permission decision is saved with \"always\".";

    private SavedPermissionRows() {
    }

    /**
     * @param permissions the client's list (may be {@code null}; entries
     *                    without a usable id are skipped - a rule that cannot
     *                    be addressed for deletion must not be listed)
     * @return rows sorted by id (case-insensitive) - stable for the dialog
     */
    public static List<String> ids(List<SavedPermission> permissions) {
        if (permissions == null) {
            return List.of();
        }
        return permissions.stream()
                .filter(permission -> permission != null
                        && permission.id() != null && !permission.id().isBlank())
                .map(SavedPermission::id)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
