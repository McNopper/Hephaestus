package com.opencode.ide.cdt.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.cdt.markers.DiagnosticsMarkers;
import com.opencode.ide.cdt.markers.DiagnosticsMarkers.Diagnostic;
import com.opencode.ide.cdt.markers.MarkerApplier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.junit.After;
import org.junit.Test;

/**
 * Workspace-path tests for {@link MarkerApplier}: real markers on real {@code IFile}s in the
 * headless surefire workspace (the test runtime boots {@code org.eclipse.core.resources} under
 * {@code target/work}; no Display is needed for IProject/IFile/IMarker). {@link MarkerApplier} is
 * instantiated directly - it is a plain class, the DS registration is irrelevant here. Attribute
 * maps are built with the tested pure mapper {@link DiagnosticsMarkers#map(List)}.
 */
public class MarkerApplierWorkspaceTest {

  private final WorkspaceFixture fixture = new WorkspaceFixture();
  private final MarkerApplier applier = new MarkerApplier();

  @After
  public void tearDown() throws CoreException {
    fixture.dispose();
  }

  /** Attribute maps for "src/main.cpp" with the given messages (severity error, lines 1..n). */
  private static List<Map<String, Object>> mapped(String... messages) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    int line = 1;
    for (String message : messages) {
      diagnostics.add(new Diagnostic("src/main.cpp", "error", line++, message, null, null));
    }
    return DiagnosticsMarkers.map(diagnostics).markers();
  }

  private static int markerCount(IResource resource) throws CoreException {
    return resource.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO).length;
  }

  @Test
  public void applyCreatesMarkersOnAFile() throws Exception {
    IProject project = fixture.createProject("create");
    IFile file = WorkspaceFixture.createFile(project, "src/main.cpp", "int main() {}\n");
    List<Map<String, Object>> attributes =
        DiagnosticsMarkers.map(
                List.of(
                    new Diagnostic("src/main.cpp", "error", 7, "undeclared identifier", 3, 9),
                    new Diagnostic("src/main.cpp", "warning", 12, "unused variable", null, null)))
            .markers();

    assertEquals(2, applier.apply(file, attributes));

    IMarker[] markers = file.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO);
    assertEquals(2, markers.length);
    Set<String> messages = new HashSet<>();
    for (IMarker marker : markers) {
      messages.add((String) marker.getAttribute(IMarker.MESSAGE));
    }
    assertEquals(Set.of("undeclared identifier", "unused variable"), messages);
    for (IMarker marker : markers) {
      if ("undeclared identifier".equals(marker.getAttribute(IMarker.MESSAGE))) {
        assertEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY));
        assertEquals(7, marker.getAttribute(IMarker.LINE_NUMBER));
        assertEquals(3, marker.getAttribute(IMarker.CHAR_START));
        assertEquals(9, marker.getAttribute(IMarker.CHAR_END));
      } else {
        assertEquals(IMarker.SEVERITY_WARNING, marker.getAttribute(IMarker.SEVERITY));
        assertEquals(12, marker.getAttribute(IMarker.LINE_NUMBER));
      }
    }
  }

  @Test
  public void applyReplacesPreviousMarkers() throws Exception {
    IProject project = fixture.createProject("replace");
    IFile file = WorkspaceFixture.createFile(project, "src/main.cpp", "x\n");

    assertEquals(2, applier.apply(file, mapped("old one", "old two")));
    assertEquals(1, applier.apply(file, mapped("fresh finding")));

    IMarker[] markers = file.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO);
    assertEquals(1, markers.length);
    assertEquals("fresh finding", markers[0].getAttribute(IMarker.MESSAGE));
  }

  @Test
  public void applyOnMissingFileReturnsMinusOne() throws Exception {
    IProject project = fixture.createProject("missing");
    IFile ghost = project.getFile("nope.txt");
    assertFalse(ghost.exists());

    assertEquals(-1, applier.apply(ghost, mapped("ghost")));
    assertEquals(
        0, project.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_INFINITE).length);
  }

  @Test
  public void applyWithNullOrEmptyAttributeListClearsMarkersAndReturnsZero() throws Exception {
    IProject project = fixture.createProject("clear");
    IFile file = WorkspaceFixture.createFile(project, "src/main.cpp", "x\n");

    assertEquals(2, applier.apply(file, mapped("one", "two")));
    assertEquals("null list must clear and return 0", 0, applier.apply(file, null));
    assertEquals(0, markerCount(file));

    assertEquals(2, applier.apply(file, mapped("three", "four")));
    assertEquals("empty list must clear and return 0", 0, applier.apply(file, List.of()));
    assertEquals(0, markerCount(file));
  }

  @Test
  public void applySkipsNullAndEmptyAttributeMaps() throws Exception {
    IProject project = fixture.createProject("emptyMaps");
    IFile file = WorkspaceFixture.createFile(project, "src/main.cpp", "x\n");

    List<Map<String, Object>> mixed = new ArrayList<>();
    mixed.add(null);
    mixed.add(new LinkedHashMap<>());
    mixed.add(mapped("only valid one").get(0));

    assertEquals(1, applier.apply(file, mixed));
    assertEquals(1, markerCount(file));
    IMarker[] markers = file.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO);
    assertEquals("only valid one", markers[0].getAttribute(IMarker.MESSAGE));
  }

  @Test
  public void applyOnlyTouchesTheTargetFile() throws Exception {
    IProject project = fixture.createProject("sibling");
    IFile a = WorkspaceFixture.createFile(project, "src/a.cpp", "a\n");
    IFile b = WorkspaceFixture.createFile(project, "src/b.cpp", "b\n");

    assertEquals(2, applier.apply(a, mapped("a1", "a2")));
    assertEquals(2, markerCount(a));
    assertEquals("sibling must stay clean", 0, markerCount(b));
    assertEquals(
        "project DEPTH_INFINITE sees exactly the two markers",
        2,
        project.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_INFINITE).length);
  }

  @Test
  public void applyAllResolvesRelativeAndWorkspaceAbsolutePaths() throws Exception {
    IProject project = fixture.createProject("paths");
    IFile main = WorkspaceFixture.createFile(project, "src/main.cpp", "m\n");
    IFile util = WorkspaceFixture.createFile(project, "include/util.h", "u\n");

    Map<IPath, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
    byFile.put(Path.fromOSString("src/main.cpp"), mapped("rel one", "rel two"));
    byFile.put(util.getFullPath(), mapped("abs one"));

    assertEquals(3, applier.applyAll(project, byFile));
    assertEquals("project-relative path resolves against the project", 2, markerCount(main));
    assertEquals(
        "workspace-absolute path resolves against the workspace root", 1, markerCount(util));
  }

  @Test
  public void applyAllSkipsUnresolvablePathsWithoutBreakingTheRest() throws Exception {
    IProject project = fixture.createProject("skip");
    IFile main = WorkspaceFixture.createFile(project, "src/main.cpp", "m\n");

    Map<IPath, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
    byFile.put(Path.fromOSString("does/not/exist.cpp"), mapped("dropped: missing relative"));
    byFile.put(Path.fromOSString("/does-not-exist/none.cpp"), mapped("dropped: missing absolute"));
    byFile.put(main.getLocation(), mapped("dropped: OS-absolute path is not workspace-absolute"));
    byFile.put(Path.fromOSString("src/main.cpp"), mapped("kept"));

    assertEquals("only the resolvable entry counts", 1, applier.applyAll(project, byFile));
    assertEquals(1, markerCount(main));
    IMarker[] markers = main.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO);
    assertEquals("kept", markers[0].getAttribute(IMarker.MESSAGE));
  }

  @Test
  public void applyAllAppliesForeignProjectFileViaWorkspaceRootResolution() throws Exception {
    // Documented behavior (not a bug): an absolute workspace path is resolved
    // against the workspace root, which FINDS files of other projects -
    // applyAll does not confine absolute paths to the given project.
    IProject projectA = fixture.createProject("foreign-a");
    IProject projectB = fixture.createProject("foreign-b");
    IFile own = WorkspaceFixture.createFile(projectA, "src/own.cpp", "o\n");
    IFile foreign = WorkspaceFixture.createFile(projectB, "src/foreign.cpp", "f\n");

    Map<IPath, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
    byFile.put(Path.fromOSString("src/own.cpp"), mapped("own finding"));
    byFile.put(foreign.getFullPath(), mapped("foreign finding"));

    assertEquals(2, applier.applyAll(projectA, byFile));
    assertEquals(1, markerCount(own));
    assertEquals("markers land on the other project's file", 1, markerCount(foreign));
    IMarker[] markers = foreign.findMarkers(MarkerApplier.MARKER_TYPE, false, IResource.DEPTH_ZERO);
    assertEquals("foreign finding", markers[0].getAttribute(IMarker.MESSAGE));
  }

  @Test
  public void applyAllReturnsMinusOneForNullProjectOrNullMap() throws Exception {
    IProject project = fixture.createProject("nullargs");
    assertEquals(-1, applier.applyAll(null, Map.of()));
    assertEquals(-1, applier.applyAll(project, null));
    assertTrue(project.exists());
  }
}
