package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.ui.model.SkillRows.Row;

/**
 * Unit tests for {@link SkillRows} (the SWT-free row building behind the
 * session's Attach-skill picker, U-046 slice 2): no SWT, no HTTP.
 */
public class SkillRowsTest {

    @Test
    public void rowsSortByNameAndSkipEntriesWithoutUsableAttachId() {
        List<Row> rows = SkillRows.rows(Arrays.asList(
                null,
                new SkillInfo(null, null, "no id, no name"),
                new SkillInfo("cpp-tools", "cpp-tools", "C++ execution utility"),
                new SkillInfo("agent-ops", "Agent Ops", "drives agents")));

        assertEquals(2, rows.size());
        assertEquals("Agent Ops", rows.get(0).name());
        assertEquals("cpp-tools", rows.get(1).name());
    }

    @Test
    public void nullListYieldsEmptyRows() {
        assertTrue(SkillRows.rows(null).isEmpty());
    }

    /** The attach payload: the wire id when present, the name as the fallback (older payloads). */
    @Test
    public void attachIdPrefersTheIdAndFallsBackToTheName() {
        List<Row> rows = SkillRows.rows(List.of(
                new SkillInfo("git-release", "Git Release", null),
                new SkillInfo(null, "name-only", "older payload")));

        assertEquals("git-release", rows.get(0).attachId());
        assertEquals("name-only", rows.get(1).attachId());
        // a named payload renders under its name; attachId still carries the wire id
        assertEquals("Git Release", rows.get(0).name());
    }

    @Test
    public void descriptionLabelIsNullForBlankDescriptions() {
        Row row = SkillRows.rows(List.of(new SkillInfo("a", "a", "  "))).get(0);
        assertNull(row.descriptionLabel());
        assertEquals("does things", SkillRows.rows(List.of(new SkillInfo("b", "b", "does things")))
                .get(0).descriptionLabel());
    }
}
