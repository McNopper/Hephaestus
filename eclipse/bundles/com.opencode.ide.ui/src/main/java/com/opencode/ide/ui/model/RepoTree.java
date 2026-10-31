package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.FileNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure (SWT-free) state behind the Repo view's lazy file tree: the loaded {@link FileNode} pages
 * per directory path, with path normalization ({@code null}/{@code blank}/{@code "."} &rarr; root),
 * sorting (directories first, then natural name order) and cycle tolerance (children that would
 * point back at an ancestor of their own parent are dropped, with a depth guard on the ancestor
 * walk).
 *
 * <p>The view loads one page per expansion in a background job and delivers it here on the UI
 * thread; the tree content provider then serves the cached page without IO. Not thread-safe:
 * single-thread (UI-thread) confined by design — see {@code RepoView}.
 */
public final class RepoTree {

  /** Maximum hops when walking a path's ancestor chain (cycle guard). */
  public static final int MAX_ANCESTOR_HOPS = 128;

  /** The canonical root path (the empty string; also what {@code "."} normalizes to). */
  public static final String ROOT = "";

  private final Map<String, List<FileNode>> pages = new HashMap<>();
  private final Map<String, FileNode> nodesByPath = new HashMap<>();

  // ---------- static path/name helpers ----------

  /**
   * @return the canonical form of a workspace-relative path: {@code null}, blank, {@code "."} and
   *     {@code "./"} all map to the root {@code ""}; surrounding whitespace, a leading {@code "./"}
   *     and trailing slashes are stripped. Windows separators are folded to {@code "/"} - the
   *     server answers with backslash paths ({@code "cpp\\build\\"}) and accepts either separator,
   *     so one canonical form keeps the cache keys and {@link #parentPath} consistent.
   */
  public static String normalize(String path) {
    if (path == null) {
      return ROOT;
    }
    String p = path.trim().replace('\\', '/');
    if (p.isEmpty() || ".".equals(p)) {
      return ROOT;
    }
    while (p.startsWith("./")) {
      p = p.substring(2);
    }
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.isEmpty() || ".".equals(p)) {
      return ROOT;
    }
    return p;
  }

  /**
   * @return the normalized parent path of the given (normalized) path: top-level entries have the
   *     root {@code ""} as parent.
   */
  public static String parentPath(String path) {
    String p = normalize(path);
    int idx = p.lastIndexOf('/');
    return idx <= 0 ? ROOT : p.substring(0, idx);
  }

  /**
   * @return the node's name, falling back to the last path segment, then {@code "(unnamed)"}.
   */
  public static String nameOf(FileNode node) {
    if (node == null) {
      return "(unnamed)";
    }
    if (node.name() != null && !node.name().isBlank()) {
      return node.name();
    }
    String p = normalize(node.path());
    if (!p.isEmpty()) {
      int idx = p.lastIndexOf('/');
      String segment = idx >= 0 ? p.substring(idx + 1) : p;
      if (!segment.isBlank()) {
        return segment;
      }
    }
    return "(unnamed)";
  }

  /**
   * @return true when {@code ancestor} is the given path itself or one of its parents.
   */
  public static boolean isAncestorOrSelf(String ancestor, String path) {
    String a = normalize(ancestor);
    String p = normalize(path);
    for (int hop = 0; hop <= MAX_ANCESTOR_HOPS; hop++) {
      if (p.equals(a)) {
        return true;
      }
      if (ROOT.equals(p)) {
        return false;
      }
      p = parentPath(p);
    }
    return false; // depth guard: treat overly deep chains as unrelated
  }

  private static final Comparator<String> NATURAL = RepoTree::compareNatural;

  /**
   * The page ordering: directories first, then files, each group in natural name order
   * (case-insensitive, digit runs compared numerically so {@code a2 < a10}).
   */
  public static final Comparator<FileNode> ORDER =
      (a, b) -> {
        int byKind = Boolean.compare(!a.isDirectory(), !b.isDirectory()); // dirs first
        if (byKind != 0) {
          return byKind;
        }
        return NATURAL.compare(nameOf(a), nameOf(b));
      };

  /** Natural string order: digit runs numerically, everything else case-insensitively. */
  static int compareNatural(String left, String right) {
    int i = 0;
    int j = 0;
    while (i < left.length() && j < right.length()) {
      char ci = left.charAt(i);
      char cj = right.charAt(j);
      if (Character.isDigit(ci) && Character.isDigit(cj)) {
        int iEnd = digitRunEnd(left, i);
        int jEnd = digitRunEnd(right, j);
        String di = stripLeadingZeros(left.substring(i, iEnd));
        String dj = stripLeadingZeros(right.substring(j, jEnd));
        if (di.length() != dj.length()) {
          return di.length() - dj.length();
        }
        int cmp = di.compareTo(dj);
        if (cmp != 0) {
          return cmp;
        }
        i = iEnd;
        j = jEnd;
      } else {
        int cmp = Character.toLowerCase(ci) - Character.toLowerCase(cj);
        if (cmp != 0) {
          return cmp;
        }
        i++;
        j++;
      }
    }
    if (i < left.length()) {
      return 1; // left has more tokens -> sorts after
    }
    if (j < right.length()) {
      return -1;
    }
    return left.compareTo(right); // deterministic tie-break (case differences)
  }

  private static int digitRunEnd(String s, int from) {
    int end = from;
    while (end < s.length() && Character.isDigit(s.charAt(end))) {
      end++;
    }
    return end;
  }

  private static String stripLeadingZeros(String digits) {
    int firstNonZero = 0;
    while (firstNonZero < digits.length() - 1 && digits.charAt(firstNonZero) == '0') {
      firstNonZero++;
    }
    return digits.substring(firstNonZero);
  }

  /**
   * @return a new list sorted with {@link #ORDER} (null-safe: null/empty yields an empty list).
   */
  public static List<FileNode> sort(List<FileNode> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<FileNode> copy = new ArrayList<>(nodes);
    copy.sort(ORDER);
    return copy;
  }

  // ---------- instance state (UI-thread confined) ----------

  /**
   * Stores one loaded page (the children of {@code parentPath}) after normalization and sorting.
   * Children without a path, and children whose path is the parent itself or one of its ancestors
   * (a cycle), are dropped.
   *
   * @return the stored page (what {@link #children(String)} will serve)
   */
  public List<FileNode> put(String parentPath, List<FileNode> children) {
    String parent = normalize(parentPath);
    List<FileNode> page = new ArrayList<>();
    if (children != null) {
      for (FileNode child : children) {
        if (child == null || normalize(child.path()).isEmpty()) {
          continue; // unaddressable node: cannot navigate or copy it
        }
        String childPath = normalize(child.path());
        if (isAncestorOrSelf(childPath, parent)) {
          continue; // cycle: points back at this subtree
        }
        page.add(child);
        nodesByPath.put(childPath, child);
      }
    }
    page.sort(ORDER);
    pages.put(parent, page);
    return page;
  }

  /**
   * @return true when the page for the given path has been loaded.
   */
  public boolean isLoaded(String path) {
    return pages.containsKey(normalize(path));
  }

  /**
   * @return the cached page for the given path (empty when not loaded yet).
   */
  public List<FileNode> children(String path) {
    return pages.getOrDefault(normalize(path), List.of());
  }

  /**
   * @return the node registered for the given path, or {@code null} (the root has no node).
   */
  public FileNode nodeAt(String path) {
    return nodesByPath.get(normalize(path));
  }

  /** Drops all loaded pages and node registrations. */
  public void clear() {
    pages.clear();
    nodesByPath.clear();
  }

  /**
   * @return the number of loaded pages (root included when loaded).
   */
  public int pageCount() {
    return pages.size();
  }
}
