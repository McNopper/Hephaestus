package com.opencode.ide.core;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;

/**
 * The OpenCode repo nature (O-001): a marker on an Eclipse project whose
 * root is an opencode repository (it contains {@code .opencode/} or an
 * {@code opencode.json}). It deliberately configures nothing - the nature's
 * only job is making the repo a first-class workspace citizen so the
 * repo-driven derivation (spawn working directory, tasksRoot adoption) has
 * a stable anchor and the project shows in the Project Explorer. Created by
 * the Board's "Adopt repo..." action via {@link RepoProjectSetup}; never
 * set on nested bundle projects of a multi-project dev layout (Eclipse
 * forbids overlapping projects - auto-discovery covers those).
 */
public final class OpenCodeRepoNature implements IProjectNature {

    /** The nature id as registered in the core bundle's plugin.xml. */
    public static final String ID = "com.opencode.ide.core.repoNature";

    private IProject project;

    @Override
    public IProject getProject() {
        return project;
    }

    @Override
    public void setProject(IProject project) {
        this.project = project;
    }

    @Override
    public void configure() {
        // marker only: no builders, no builders to remove
    }

    @Override
    public void deconfigure() {
        // marker only
    }
}
