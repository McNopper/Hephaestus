package com.opencode.ide.tools.probe;

import com.opencode.ide.board.model.BoardModel;

/**
 * U-083 seeded-proof fixture: a class in the Eclipse-free {@code tools}
 * package that depends on {@code board} - an edge the QUALITY.md layer graph
 * forbids. {@code LayerArchitectureTest} imports this class and asserts that
 * the boundary rule rejects it - the permanent proof that the rule can
 * actually fail.
 */
public final class BoardImportProbe {

    private BoardImportProbe() {
    }

    /**
     * @return the board class name, forcing a real bytecode dependency
     */
    public String boardClass() {
        return BoardModel.class.getName();
    }
}
