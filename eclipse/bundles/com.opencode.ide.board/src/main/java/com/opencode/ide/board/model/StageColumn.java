package com.opencode.ide.board.model;

import java.util.List;

/**
 * One column of the V-pipeline board: a V-model stage (or the trailing {@link
 * PipelineSnapshot#UNTRACKED} group), its tickets in board order, and the column's blocked count
 * and story-point sum. A pure, SWT-free projection of {@link PipelineSnapshot} data for display.
 */
public record StageColumn(String stage, List<TicketRow> rows, int blockedCount, int points) {}
