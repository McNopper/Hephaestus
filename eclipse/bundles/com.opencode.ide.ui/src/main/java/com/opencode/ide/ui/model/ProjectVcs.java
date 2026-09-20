package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.List;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.ProjectSummary;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Pure (SWT-free) project/VCS header model for the Server view: the project
 * the selected connection currently works on (worktree, branch, repository
 * remote) and its renderings — the one-line {@link #summary()} for the view's
 * description slot and the multi-line {@link #tooltip()} for the view title.
 *
 * <p>{@link #load(OpencodeClient, String)} is lenient by design: it resolves
 * the project via {@code GET /project}, merges it with {@code GET /vcs} and
 * adds dirtiness from {@code GET /file/status} (a non-empty list means dirty,
 * its size the changed-file count); any absent endpoint, empty list, blank
 * field or transport failure degrades — never {@code null}, never an
 * exception — so the header always renders (a failed status call keeps the
 * project/branch and renders clean with an unknown count). The pure {@link #of}
 * factory exists for tests and hand-built instances: dirty state and
 * changed-file counts render as soon as they are passed in.</p>
 */
public final class ProjectVcs {

    /** The graceful-degradation instance: no project, no VCS, empty renderings. */
    public static final ProjectVcs UNKNOWN = new ProjectVcs(null, null, null, null, null, false, -1);

    private final String name;
    private final String projectPath;
    private final String worktree;
    private final String branch;
    private final String repository;
    private final boolean dirty;
    private final int changedFiles;   // -1 = unknown

    private ProjectVcs(String name, String projectPath, String worktree, String branch,
            String repository, boolean dirty, int changedFiles) {
        this.name = name;
        this.projectPath = projectPath;
        this.worktree = worktree;
        this.branch = branch;
        this.repository = repository;
        this.dirty = dirty;
        this.changedFiles = changedFiles;
    }

    /**
     * Resolves the current project of the given client: the project whose
     * worktree equals {@code cwd} (ignoring trailing separators), the first
     * project as the fallback (also for a null/blank {@code cwd}), with the
     * branch/repository of the project record preferred over {@code GET /vcs}.
     * Dirtiness and the changed-file count come from {@code GET /file/status}
     * (non-empty ⇒ dirty, size ⇒ count); its failure or an empty list renders
     * clean with an unknown count. Absent data — no projects, no VCS,
     * transport errors, or no derivable project name — degrades to
     * {@link #UNKNOWN}.
     */
    public static ProjectVcs load(OpencodeClient client, String cwd) {
        if (client == null) {
            return UNKNOWN;
        }
        ProjectSummary project = findProject(projectsOf(client), cwd);
        String worktree = project == null ? null : blankToNull(project.worktree());
        String projectPath = worktree != null ? worktree : blankToNull(cwd);
        // scope the VCS calls to the resolved project: v2 answers /vcs/* per
        // location (unscoped on the shared service = the user's home repo)
        VcsInfo vcs = vcsOf(client, projectPath);
        List<FileStatus> status = fileStatusOf(client, projectPath);
        String name = nameOf(projectPath);
        if (name == null) {
            return UNKNOWN;
        }
        String branch = firstNonBlank(project == null ? null : project.branch(),
                vcs == null ? null : vcs.branch());
        String repository = firstNonBlank(project == null ? null : project.repository(),
                vcs == null ? null : vcs.repository());
        int changedFiles = status == null || status.isEmpty() ? -1 : status.size();
        return new ProjectVcs(name, projectPath, worktree, branch, repository, changedFiles > 0, changedFiles);
    }

    /**
     * The pure factory behind every non-unknown instance (rendering path for
     * tests and hand-built instances): derives the project name from
     * {@code projectPath}; a path with no derivable name degrades to
     * {@link #UNKNOWN}.
     */
    public static ProjectVcs of(String projectPath, String worktree, String branch,
            String repository, boolean dirty, int changedFiles) {
        String path = blankToNull(projectPath);
        String name = nameOf(path);
        if (name == null) {
            return UNKNOWN;
        }
        return new ProjectVcs(name, path, blankToNull(worktree), blankToNull(branch),
                blankToNull(repository), dirty, changedFiles);
    }

    /** The resolved project directory (the project's worktree, else the cwd); {@code null} when unknown. */
    public String projectPath() {
        return projectPath;
    }

    /**
     * The one-line header form, e.g. {@code "Hephaestus · branch main"}, plus
     * {@code " · dirty"} while the VCS reports uncommitted changes (the
     * repository remote replaces the branch when only it is known). Empty
     * when there is no VCS at all.
     */
    public String summary() {
        if (branch == null && repository == null) {
            return "";
        }
        String vcs = branch != null ? "branch " + branch : repository;
        return name + " · " + vcs + (dirty ? " · dirty" : "");
    }

    /**
     * The multi-line detail form: project path, worktree root (only when it
     * differs from the project path), branch, repository remote and
     * changed-file count (when known). Empty when nothing is known.
     */
    public String tooltip() {
        List<String> lines = new ArrayList<>(5);
        if (projectPath != null) {
            lines.add("Project: " + projectPath);
        }
        if (worktree != null && !worktree.equals(projectPath)) {
            lines.add("Worktree: " + worktree);
        }
        if (branch != null) {
            lines.add("Branch: " + branch);
        }
        if (repository != null) {
            lines.add("Repository: " + repository);
        }
        if (changedFiles >= 0) {
            lines.add("Changes: " + changedFiles);
        }
        return String.join("\n", lines);
    }

    private static List<ProjectSummary> projectsOf(OpencodeClient client) {
        try {
            List<ProjectSummary> projects = client.getProjects();
            return projects == null ? List.of() : projects;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static VcsInfo vcsOf(OpencodeClient client, String directory) {
        try {
            return client.getVcsInfo(directory);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<FileStatus> fileStatusOf(OpencodeClient client, String directory) {
        try {
            return client.getFileStatus(directory);
        } catch (Exception e) {
            return null;
        }
    }

    /** The project whose worktree equals {@code cwd} (ignoring trailing separators); the first project as the fallback. */
    private static ProjectSummary findProject(List<ProjectSummary> projects, String cwd) {
        if (projects.isEmpty()) {
            return null;
        }
        String wanted = trimSeparators(cwd);
        if (wanted != null) {
            for (ProjectSummary project : projects) {
                if (wanted.equals(trimSeparators(project.worktree()))) {
                    return project;
                }
            }
        }
        return projects.get(0);
    }

    /** The given path without surrounding whitespace and trailing separators; {@code null} when nothing remains. */
    static String trimSeparators(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? null : p;
    }

    /** The last path segment of the given path (both separators tolerated); {@code null} when not derivable. */
    private static String nameOf(String path) {
        String p = trimSeparators(path);
        if (p == null) {
            return null;
        }
        return p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }
}
