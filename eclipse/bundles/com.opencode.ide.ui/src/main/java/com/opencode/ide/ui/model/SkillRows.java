package com.opencode.ide.ui.model;

import java.util.Comparator;
import java.util.List;

import com.opencode.ide.client.model.SkillInfo;

/**
 * Rows for the session's "Attach skill" picker (U-046 slice 2): converts the
 * client's {@code GET /skill} list into sorted, null-tolerant picker rows
 * whose {@link Row#attachId()} is exactly what the experimental attach POST
 * sends in its {@code skill} body field. SWT-free so the mapping is
 * unit-testable; the dialog only renders.
 */
public final class SkillRows {

    private SkillRows() {
    }

    /**
     * @param skills the client's list (may be {@code null}); entries without
     *               a usable attach id (neither {@code id} nor name) are
     *               skipped - they could never be attached
     * @return rows sorted by name (case-insensitive) - stable for the picker
     */
    public static List<Row> rows(List<SkillInfo> skills) {
        if (skills == null) {
            return List.of();
        }
        return skills.stream()
                .filter(skill -> skill != null && skill.attachId() != null)
                .map(skill -> new Row(skill.attachId(), labelOf(skill), skill.description()))
                .sorted(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String labelOf(SkillInfo skill) {
        if (skill.name() != null && !skill.name().isBlank()) {
            return skill.name();
        }
        return skill.attachId(); // id-only payloads render under their id
    }

    /** One picker row: the attach payload id, the display name and the description. */
    public record Row(String attachId, String name, String description) {

        /** The description for the picker's second column; {@code null} when absent. */
        public String descriptionLabel() {
            return description == null || description.isBlank() ? null : description;
        }
    }
}
