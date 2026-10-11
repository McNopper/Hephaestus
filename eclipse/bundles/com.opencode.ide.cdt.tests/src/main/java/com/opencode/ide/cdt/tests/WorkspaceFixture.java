package com.opencode.ide.cdt.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * Headless-workspace fixture for the Tycho surefire OSGi test runtime: the {@code
 * org.eclipse.core.resources} bundle is on the test runtime (required by {@code
 * com.opencode.ide.cdt}), so {@link ResourcesPlugin} serves a real workspace under {@code
 * target/work/data} - no Display, no natures, no builders needed for markers.
 *
 * <p>Project names are unique per run (label + {@link System#nanoTime()}) so leftover projects from
 * crashed runs never collide; {@link #dispose()} in an {@code @After} deletes everything ({@code
 * delete(true, true, null)}).
 */
final class WorkspaceFixture {

  private final List<IProject> projects = new ArrayList<>();

  /** Creates and opens a nature-less project; no-op natures are fine for markers. */
  IProject createProject(String label) throws CoreException {
    IProject project =
        ResourcesPlugin.getWorkspace()
            .getRoot()
            .getProject("cdt-marker-tests-" + label + "-" + Long.toHexString(System.nanoTime()));
    project.create(null);
    project.open(null);
    projects.add(project);
    return project;
  }

  /** Creates a file (and any missing parent folders) with the given content. */
  static IFile createFile(IProject project, String projectRelativePath, String content)
      throws CoreException {
    IFile file = project.getFile(projectRelativePath);
    createParents(file.getParent());
    file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
    return file;
  }

  private static void createParents(IContainer container) throws CoreException {
    if (container == null || container.exists()) {
      return;
    }
    createParents(container.getParent());
    ((IFolder) container).create(true, true, null);
  }

  /** Deletes every created project (with content, forcing). Never throws on its own. */
  void dispose() throws CoreException {
    for (IProject project : projects) {
      if (project.exists()) {
        project.delete(true, true, null);
      }
    }
    projects.clear();
  }
}
