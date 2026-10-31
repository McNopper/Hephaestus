package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.FileNode;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for the SWT-free {@link RepoTree} behind the Repo view: path normalization, parent
 * resolution, page sorting (dirs first, natural name order) and cycle tolerance. No SWT, no JFace,
 * no Display.
 */
public class RepoTreeTest {

  private static FileNode dir(String name, String path) {
    return new FileNode(name, path, "directory");
  }

  private static FileNode file(String name, String path) {
    return new FileNode(name, path, "file");
  }

  // ---------- normalize ----------

  @Test
  public void normalizeMapsNullBlankAndDotToRoot() {
    assertEquals(RepoTree.ROOT, RepoTree.normalize(null));
    assertEquals(RepoTree.ROOT, RepoTree.normalize(""));
    assertEquals(RepoTree.ROOT, RepoTree.normalize("   "));
    assertEquals(RepoTree.ROOT, RepoTree.normalize("."));
    assertEquals(RepoTree.ROOT, RepoTree.normalize(" . "));
  }

  @Test
  public void normalizeStripsDotSlashAndTrailingSlashes() {
    assertEquals("a/b", RepoTree.normalize("./a/b"));
    assertEquals("a/b", RepoTree.normalize("a/b/"));
    assertEquals("a/b", RepoTree.normalize("a/b///"));
    assertEquals("a", RepoTree.normalize("./a/"));
    assertEquals(RepoTree.ROOT, RepoTree.normalize("/")); // slashes only -> root
  }

  // ---------- parentPath ----------

  /**
   * The opencode server answers with Windows separators and a trailing one ({@code
   * "cpp\\build\\"}); those must fold to the canonical form or the cache keys and parent lookups
   * diverge from the forward-slash ones.
   */
  @Test
  public void normalizeFoldsWindowsSeparators() {
    assertEquals("a/b", RepoTree.normalize("a\\b"));
    assertEquals("a/b", RepoTree.normalize("a\\b\\"));
    assertEquals("cpp/build-clang64", RepoTree.normalize("cpp\\build-clang64\\"));
    assertEquals("a/b", RepoTree.normalize(".\\a\\b"));
    assertEquals(RepoTree.ROOT, RepoTree.normalize("\\"));
    assertEquals(RepoTree.ROOT, RepoTree.normalize(".\\"));
    assertEquals("a", RepoTree.parentPath("a\\b"));
    assertEquals("a/b", RepoTree.parentPath("a\\b\\c"));
    assertTrue(RepoTree.isAncestorOrSelf("a", "a\\b\\c"));
  }

  @Test
  public void parentPathOfTopLevelIsRootAndOfNestedIsThePrefix() {
    assertEquals(RepoTree.ROOT, RepoTree.parentPath("a"));
    assertEquals("a", RepoTree.parentPath("a/b"));
    assertEquals("a/b", RepoTree.parentPath("a/b/c"));
    assertEquals(RepoTree.ROOT, RepoTree.parentPath(RepoTree.ROOT));
  }

  // ---------- isAncestorOrSelf ----------

  @Test
  public void isAncestorOrSelfDetectsSelfAncestorsAndUnrelated() {
    assertTrue(RepoTree.isAncestorOrSelf("a", "a"));
    assertTrue(RepoTree.isAncestorOrSelf("a", "a/b"));
    assertTrue(RepoTree.isAncestorOrSelf("a/b", "a/b/c/d"));
    assertTrue(RepoTree.isAncestorOrSelf(RepoTree.ROOT, "a/b")); // root is everyone's ancestor
    assertFalse(RepoTree.isAncestorOrSelf("a/b", "a"));
    assertFalse(RepoTree.isAncestorOrSelf("ab", "a/b/c"));
  }

  // ---------- sorting ----------

  @Test
  public void sortPutsDirectoriesFirstThenNaturalNameOrder() {
    List<FileNode> sorted =
        RepoTree.sort(
            List.of(
                file("a10.txt", "a10.txt"),
                dir("zeta", "zeta"),
                file("a2.txt", "a2.txt"),
                dir("Beta", "Beta"),
                file("A1.txt", "A1.txt")));

    assertEquals(
        List.of("Beta", "zeta", "A1.txt", "a2.txt", "a10.txt"),
        sorted.stream().map(RepoTree::nameOf).toList());
  }

  @Test
  public void naturalOrderComparesDigitRunsNumerically() {
    List<FileNode> sorted =
        RepoTree.sort(List.of(file("v10", "v10"), file("v2", "v2"), file("v007", "v007")));

    assertEquals(List.of("v2", "v007", "v10"), sorted.stream().map(RepoTree::nameOf).toList());
  }

  @Test
  public void sortToleratesNullAndEmpty() {
    assertEquals(List.of(), RepoTree.sort(null));
    assertEquals(List.of(), RepoTree.sort(List.of()));
  }

  // ---------- nameOf ----------

  @Test
  public void nameOfFallsBackToPathSegmentThenUnnamed() {
    assertEquals("readme", RepoTree.nameOf(file(null, "docs/readme")));
    assertEquals("readme", RepoTree.nameOf(file("  ", "readme")));
    assertEquals("(unnamed)", RepoTree.nameOf(file(null, null)));
    assertEquals("(unnamed)", RepoTree.nameOf(null));
  }

  // ---------- pages ----------

  @Test
  public void putStoresSortedPageAndRegistersNodes() {
    RepoTree tree = new RepoTree();

    assertFalse(tree.isLoaded(RepoTree.ROOT));
    tree.put(RepoTree.ROOT, List.of(file("b", "b"), dir("a", "a")));

    assertTrue(tree.isLoaded(RepoTree.ROOT));
    assertTrue(tree.isLoaded(".")); // normalized lookup
    assertEquals(
        List.of("a", "b"), tree.children(RepoTree.ROOT).stream().map(RepoTree::nameOf).toList());
    assertEquals("a", tree.nodeAt("a").name());
    assertNull(tree.nodeAt("missing"));
    assertEquals(1, tree.pageCount());
  }

  @Test
  public void childrenOfUnloadedPathIsEmptyAndPutAcceptsNullChildren() {
    RepoTree tree = new RepoTree();

    assertEquals(List.of(), tree.children("nowhere"));
    assertFalse(tree.isLoaded("nowhere"));

    tree.put("empty", null);
    assertTrue(tree.isLoaded("empty"));
    assertEquals(List.of(), tree.children("empty"));
  }

  @Test
  public void putDropsNullNodesAndNodesWithoutPath() {
    RepoTree tree = new RepoTree();

    // Arrays.asList (not List.of): the fixture intentionally contains nulls
    tree.put(RepoTree.ROOT, java.util.Arrays.asList(null, file("x", null), file("ok", "ok")));

    assertEquals(
        List.of("ok"), tree.children(RepoTree.ROOT).stream().map(RepoTree::nameOf).toList());
    assertNull(tree.nodeAt(""));
  }

  @Test
  public void putDropsChildrenThatWouldCreateACycle() {
    RepoTree tree = new RepoTree();
    tree.put(RepoTree.ROOT, List.of(dir("a", "a"), dir("b", "b")));
    tree.put("a", List.of(dir("b", "a/b"))); // "a/b" under "a"

    // expanding "a/b" returns its own ancestor chain -> all dropped
    tree.put(
        "a/b",
        List.of(
            dir("a", "a"), // grandparent
            dir("a/b", "a/b"), // self
            dir("", ""), // root (ancestor of everything)
            file("leaf", "a/b/leaf")));

    assertEquals(List.of("leaf"), tree.children("a/b").stream().map(RepoTree::nameOf).toList());
  }

  @Test
  public void clearDropsAllPagesAndRegistrations() {
    RepoTree tree = new RepoTree();
    tree.put(RepoTree.ROOT, List.of(dir("a", "a")));

    tree.clear();

    assertFalse(tree.isLoaded(RepoTree.ROOT));
    assertEquals(List.of(), tree.children(RepoTree.ROOT));
    assertNull(tree.nodeAt("a"));
    assertEquals(0, tree.pageCount());
  }
}
