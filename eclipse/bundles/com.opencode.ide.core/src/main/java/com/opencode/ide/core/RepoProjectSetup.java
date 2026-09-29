package com.opencode.ide.core;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * O-001: the pure logic of "adopt this repo as an OpenCode project" — the
 * minimal {@code .project} content (repo nature, no builders) and the
 * adoptability validation, SWT-free and unit-testable. The Board's action
 * is the only caller today; the repo keeps working through marker-based
 * auto-discovery even without adoption (that path must never regress —
 * nested Tycho/PDE dev layouts can never carry a root project).
 */
public final class RepoProjectSetup {

    /** The repo-nature id registered by the core bundle (see plugin.xml). */
    public static final String NATURE_ID = OpenCodeRepoNature.ID;

    /** Directories never descended into when scanning for nested Eclipse projects. */
    private static final List<String> SKIP_DIRS =
            List.of(".git", ".opencode", "node_modules", "target", "build", "out", "bin");

    /** Scan depth for nested .project detection (nested bundle projects live 2-4 deep). */
    private static final int SCAN_DEPTH = 5;

    private RepoProjectSetup() {
    }

    /**
     * The minimal {@code .project} content for an adopted repo root: the
     * name and the OpenCode repo nature, no builders (an adoption marker
     * owns no build spec).
     */
    public static String projectFileXml(String projectName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<projectDescription>\n"
                + "\t<name>" + projectName + "</name>\n"
                + "\t<comment></comment>\n"
                + "\t<projects></projects>\n"
                + "\t<buildSpec></buildSpec>\n"
                + "\t<natures>\n"
                + "\t\t<nature>" + NATURE_ID + "</nature>\n"
                + "\t</natures>\n"
                + "</projectDescription>\n";
    }

    /** @return true when the directory is an opencode repo root (either marker). */
    public static boolean isRepoRoot(Path directory) {
        return directory != null
                && (Files.isDirectory(directory.resolve(".opencode"))
                        || Files.isRegularFile(directory.resolve("opencode.json")));
    }

    /**
     * Why a directory cannot be adopted as a repo-root project, or null when
     * it can. The nested-project refusal is the Eclipse overlap rule, not a
     * preference: a repo whose subdirectories already are Eclipse projects
     * (the Hephaestus dev layout) is covered by marker-based auto-discovery
     * instead, and the message says so.
     */
    public static String adoptionProblem(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return "not a directory";
        }
        if (!isRepoRoot(directory)) {
            return "not an opencode repo (no .opencode/ directory and no opencode.json at the root)";
        }
        if (Files.isRegularFile(directory.resolve(".project"))) {
            return "already an Eclipse project - open it via File > Import > Existing Projects";
        }
        Path nested = nestedProjectFile(directory);
        if (nested != null) {
            return "contains a nested Eclipse project (" + directory.relativize(nested.getParent())
                    + ") - Eclipse forbids overlapping projects; marker-based auto-discovery "
                    + "already covers this repo";
        }
        return null;
    }

    /**
     * The first nested {@code .project} below the root (the root's own is
     * excluded), or null. Pruned and depth-bounded: {@code .git},
     * {@code node_modules}, build outputs and hidden directories are never
     * descended into, so the scan stays cheap on real repos.
     */
    static Path nestedProjectFile(Path root) {
        AtomicReference<Path> found = new AtomicReference<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), SCAN_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (".project".equals(file.getFileName().toString())) {
                                found.set(file);
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            return null; // unreadable trees answer "no nested project found", never throw
        }
        return found.get();
    }
}
