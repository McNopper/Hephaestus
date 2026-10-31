package com.opencode.ide.cdt.markers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, deterministic mapping from tool diagnostics to Eclipse marker attribute maps ({@code
 * Map<String, Object>}) as consumed by {@link MarkerApplier#apply(org.eclipse.core.resources.IFile,
 * List)}.
 *
 * <p>SWT-free, workspace-free and OSGi-free on purpose: it only references compile-time constants
 * from {@link MarkerAttributes} (inlined by the compiler), so it is trivially unit-testable
 * headlessly. Mapping rules:
 *
 * <ul>
 *   <li>severity: {@code error} -> 2, {@code warning} -> 1, {@code info} -> 0 (case-insensitive);
 *       anything else, including {@code null}, -> 1.
 *   <li>line: 1-based; {@code null} or &lt; 1 is clamped to 1.
 *   <li>{@code charStart}/{@code charEnd}: only included when both are present and {@code start <=
 *       end}.
 *   <li>{@code message} and {@code path} are required (non-blank); a diagnostic missing either is
 *       dropped and counted in the result.
 * </ul>
 */
public final class DiagnosticsMarkers implements MarkerAttributes {

  private DiagnosticsMarkers() {}

  /**
   * One diagnostic as produced by a tool (e.g. clang-tidy/cppcheck output).
   *
   * @param path workspace/project-relative path of the affected file (required; used by consumers
   *     to route the marker to a file)
   * @param severity {@code error} | {@code warning} | {@code info} (case-insensitive)
   * @param line 1-based line number, may be {@code null}
   * @param message human-readable message (required)
   * @param charStart inclusive start character offset, may be {@code null}
   * @param charEnd inclusive end character offset, may be {@code null}
   */
  public record Diagnostic(
      String path,
      String severity,
      Integer line,
      String message,
      Integer charStart,
      Integer charEnd) {}

  /**
   * Mapping result.
   *
   * @param markers marker attribute maps, in input order
   * @param dropped number of input diagnostics dropped (invalid message/path)
   */
  public record MappedDiagnostics(List<Map<String, Object>> markers, int dropped) {
    public MappedDiagnostics {
      markers = List.copyOf(markers);
    }
  }

  /**
   * Maps diagnostics to marker attribute maps; never throws, never returns {@code null}. A {@code
   * null} input list maps to an empty result.
   */
  public static MappedDiagnostics map(List<Diagnostic> diagnostics) {
    List<Map<String, Object>> markers = new ArrayList<>();
    if (diagnostics == null) {
      return new MappedDiagnostics(markers, 0);
    }
    int dropped = 0;
    for (Diagnostic diagnostic : diagnostics) {
      Map<String, Object> attributes = mapOne(diagnostic);
      if (attributes == null) {
        dropped++;
      } else {
        markers.add(attributes);
      }
    }
    return new MappedDiagnostics(markers, dropped);
  }

  /**
   * Maps and groups diagnostics by file path, preserving first-seen path order (deterministic).
   * Dropped diagnostics are excluded; the dropped count of {@link #map(List)} equals the total
   * excluded here. Keys are the literal {@link Diagnostic#path()} strings.
   */
  public static Map<String, List<Map<String, Object>>> byPath(List<Diagnostic> diagnostics) {
    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    if (diagnostics == null) {
      return grouped;
    }
    for (Diagnostic diagnostic : diagnostics) {
      Map<String, Object> attributes = mapOne(diagnostic);
      if (attributes == null) {
        continue;
      }
      grouped.computeIfAbsent(diagnostic.path(), p -> new ArrayList<>()).add(attributes);
    }
    return grouped;
  }

  /**
   * @return the attribute map, or {@code null} if the diagnostic must be dropped.
   */
  private static Map<String, Object> mapOne(Diagnostic diagnostic) {
    if (diagnostic == null || isBlank(diagnostic.message()) || isBlank(diagnostic.path())) {
      return null;
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(MESSAGE, diagnostic.message());
    attributes.put(SEVERITY, severityOf(diagnostic.severity()));
    attributes.put(LINE_NUMBER, clampLine(diagnostic.line()));
    Integer charStart = diagnostic.charStart();
    Integer charEnd = diagnostic.charEnd();
    if (charStart != null && charEnd != null && charStart <= charEnd) {
      attributes.put(CHAR_START, charStart);
      attributes.put(CHAR_END, charEnd);
    }
    return attributes;
  }

  private static int severityOf(String severity) {
    if (severity != null) {
      switch (severity.trim().toLowerCase(Locale.ROOT)) {
        case "error":
          return SEVERITY_ERROR;
        case "warning":
          return SEVERITY_WARNING;
        case "info":
          return SEVERITY_INFO;
        default:
          return SEVERITY_WARNING;
      }
    }
    return SEVERITY_WARNING;
  }

  private static int clampLine(Integer line) {
    return line == null || line < 1 ? 1 : line;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
