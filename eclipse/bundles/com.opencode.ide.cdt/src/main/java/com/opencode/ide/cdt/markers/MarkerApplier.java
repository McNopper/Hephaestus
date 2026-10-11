package com.opencode.ide.cdt.markers;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

/**
 * Thin best-effort applier that writes marker attribute maps (produced by {@link
 * DiagnosticsMarkers}) as markers of type {@link #MARKER_TYPE} onto workspace files. Registered as
 * an OSGi Declarative Service (see {@code OSGI-INF/MarkerApplier.xml}).
 *
 * <p>Never throws: a missing file, a closed workspace or a {@link CoreException} is logged and
 * reported through the return value ({@code -1} failure, otherwise the number of markers created).
 * Existing markers of {@link #MARKER_TYPE} on the target file are always deleted first, so
 * re-applying is idempotent.
 */
public class MarkerApplier {

  /** Persistent marker type id (declared in {@code plugin.xml}). */
  public static final String MARKER_TYPE = "com.opencode.ide.cdt.diagnostic";

  private static final Logger LOG = Logger.getLogger(MarkerApplier.class.getName());

  /**
   * Deletes existing {@link #MARKER_TYPE} markers on {@code file} and creates one new marker per
   * attribute map.
   *
   * @param file target file; {@code null} or missing -> {@code -1}
   * @param attributes attribute maps; {@code null} or empty clears the existing markers and returns
   *     {@code 0}
   * @return number of markers created, or {@code -1} when the file cannot be touched (missing /
   *     workspace closed / delete failed)
   */
  public int apply(IFile file, List<Map<String, Object>> attributes) {
    if (file == null || !file.exists()) {
      LOG.log(Level.WARNING, "marker target file is missing: {0}", file);
      return -1;
    }
    try {
      file.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO);
    } catch (CoreException e) {
      LOG.log(Level.WARNING, "failed to delete markers on " + file, e);
      return -1;
    }
    int created = 0;
    if (attributes == null) {
      return created;
    }
    for (Map<String, Object> attributeMap : attributes) {
      if (attributeMap == null || attributeMap.isEmpty()) {
        continue;
      }
      try {
        IMarker marker = file.createMarker(MARKER_TYPE);
        marker.setAttributes(attributeMap);
        created++;
      } catch (CoreException e) {
        LOG.log(Level.WARNING, "failed to create marker on " + file, e);
      }
    }
    return created;
  }

  /**
   * Applies markers for many files of one project: for every entry, resolves the {@link IFile}
   * (absolute paths against the workspace root, relative paths against {@code project}) and
   * delegates to {@link #apply(IFile, List)}. Files that cannot be resolved are skipped (logged);
   * the returned count is the total number of markers created ({@code -1} when {@code project} or
   * the map is {@code null}).
   */
  public int applyAll(IProject project, Map<IPath, List<Map<String, Object>>> markersByFile) {
    if (project == null || markersByFile == null) {
      return -1;
    }
    int total = 0;
    try {
      for (Map.Entry<IPath, List<Map<String, Object>>> entry : markersByFile.entrySet()) {
        IFile file = resolveFile(project, entry.getKey());
        if (file == null || !file.exists()) {
          LOG.log(
              Level.WARNING,
              "skipping markers for missing file {0} in project {1}",
              new Object[] {entry.getKey(), project.getName()});
          continue;
        }
        int applied = apply(file, entry.getValue());
        if (applied > 0) {
          total += applied;
        }
      }
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "failed to apply markers for project " + project.getName(), t);
    }
    return total;
  }

  /**
   * @return the file handle, or {@code null} if it cannot be resolved.
   */
  private IFile resolveFile(IProject project, IPath path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    try {
      if (path.isAbsolute()) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getFile(path);
      }
      return project.getFile(path);
    } catch (Throwable t) {
      // e.g. workspace closed (headless) - nothing to resolve
      return null;
    }
  }
}
