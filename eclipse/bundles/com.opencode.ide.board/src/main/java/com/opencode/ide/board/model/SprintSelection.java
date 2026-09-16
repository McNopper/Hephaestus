package com.opencode.ide.board.model;

import java.util.List;

/**
 * The SWT-free truth table behind the sprint selector's peer-aware behavior
 * (B-002): when a refresh should switch the board to another sprint. The
 * live failure this fixes — a peer plans a sprint and moves the tickets into
 * it while the Board sits on the default {@link BoardModel#BACKLOG}
 * pseudo-sprint, which then renders empty while the real work is one combo
 * entry away, unnoticed.
 *
 * <p>Rule: auto-select only when the user has not explicitly picked a sprint
 * this session ({@code picked == false}), the current selection is the
 * "none" pseudo-sprint ({@link BoardModel#BACKLOG}), that selection shows an
 * empty board, and at least one real sprint exists — then take the newest
 * one (the sprint list is sorted, and sprint ids like
 * {@code sprint-2026-09-15} sort chronologically). Any explicit user
 * selection, non-empty board, or sprint-less store keeps the current
 * selection: the board must never fight the user.</p>
 */
public final class SprintSelection {

    private SprintSelection() {
    }

    /**
     * The sprint the board should switch to after a refresh, or
     * {@code null} to keep the current one.
     *
     * @param current      the currently selected sprint id (never null in
     *                     practice; {@link BoardModel#BACKLOG} means "none
     *                     picked")
     * @param picked       whether the user chose a sprint in the selector
     *                     this session (explicit choice is never overridden)
     * @param sprints      the full selectable sprint list (sorted;
     *                     {@link BoardModel#BACKLOG} rides along, typically
     *                     last)
     * @param currentTotal tickets visible under {@code current} (an empty
     *                     board is the "silently showing nothing" symptom)
     * @return the newest real sprint to auto-select, or {@code null}
     */
    public static String autoSelect(String current, boolean picked, List<String> sprints, int currentTotal) {
        if (picked || sprints == null || sprints.isEmpty()) {
            return null;
        }
        if (!BoardModel.BACKLOG.equals(current)) {
            return null; // a real sprint is already selected — never fight it
        }
        if (currentTotal > 0) {
            return null; // backlog has visible work; switching would hide it
        }
        String newest = null;
        for (String sprint : sprints) {
            if (!BoardModel.BACKLOG.equals(sprint)) {
                newest = sprint; // sorted list: the last real one is the newest
            }
        }
        return newest;
    }
}
