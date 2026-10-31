package com.opencode.ide.cdt.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.cdt.markers.DiagnosticsMarkers;
import com.opencode.ide.cdt.markers.DiagnosticsMarkers.Diagnostic;
import com.opencode.ide.cdt.markers.DiagnosticsMarkers.MappedDiagnostics;
import com.opencode.ide.cdt.markers.MarkerAttributes;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for the pure {@link DiagnosticsMarkers} mapping. No OSGi, no workspace, no SWT - the
 * mapper must be loadable and deterministic headlessly.
 */
public class DiagnosticsMarkersTest {

  private static Diagnostic diagnostic(
      String severity, Integer line, String message, Integer charStart, Integer charEnd) {
    return new Diagnostic("src/main.cpp", severity, line, message, charStart, charEnd);
  }

  @Test
  public void severityTable() {
    assertEquals(2, severityOf(diagnostic("error", null, "m", null, null)));
    assertEquals(1, severityOf(diagnostic("warning", null, "m", null, null)));
    assertEquals(0, severityOf(diagnostic("info", null, "m", null, null)));
    // case-insensitive
    assertEquals(2, severityOf(diagnostic("ERROR", null, "m", null, null)));
    assertEquals(1, severityOf(diagnostic("Warning", null, "m", null, null)));
    assertEquals(0, severityOf(diagnostic("Info", null, "m", null, null)));
    // unknown and null map to warning
    assertEquals(1, severityOf(diagnostic("fatal", null, "m", null, null)));
    assertEquals(1, severityOf(diagnostic(null, null, "m", null, null)));
    assertEquals(1, severityOf(diagnostic("", null, "m", null, null)));
  }

  private int severityOf(Diagnostic diagnostic) {
    MappedDiagnostics result = DiagnosticsMarkers.map(List.of(diagnostic));
    assertEquals(1, result.markers().size());
    return (Integer) result.markers().get(0).get(MarkerAttributes.SEVERITY);
  }

  @Test
  public void lineIsClampedToOne() {
    assertEquals(1, lineOf(diagnostic("error", null, "m", null, null)));
    assertEquals(1, lineOf(diagnostic("error", 0, "m", null, null)));
    assertEquals(1, lineOf(diagnostic("error", -17, "m", null, null)));
    assertEquals(1, lineOf(diagnostic("error", 1, "m", null, null)));
    assertEquals(42, lineOf(diagnostic("error", 42, "m", null, null)));
  }

  private int lineOf(Diagnostic diagnostic) {
    MappedDiagnostics result = DiagnosticsMarkers.map(List.of(diagnostic));
    return (Integer) result.markers().get(0).get(MarkerAttributes.LINE_NUMBER);
  }

  @Test
  public void charOffsetsOnlyWhenValid() {
    // both present, start < end -> included
    Map<String, Object> attrs = mapOne(diagnostic("error", 3, "m", 10, 20));
    assertEquals(10, attrs.get(MarkerAttributes.CHAR_START));
    assertEquals(20, attrs.get(MarkerAttributes.CHAR_END));

    // start == end is valid (empty range)
    attrs = mapOne(diagnostic("error", 3, "m", 10, 10));
    assertEquals(10, attrs.get(MarkerAttributes.CHAR_START));
    assertEquals(10, attrs.get(MarkerAttributes.CHAR_END));

    // start > end -> dropped
    attrs = mapOne(diagnostic("error", 3, "m", 20, 10));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_START));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_END));

    // one or both missing -> dropped
    attrs = mapOne(diagnostic("error", 3, "m", null, 20));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_START));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_END));
    attrs = mapOne(diagnostic("error", 3, "m", 10, null));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_START));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_END));
    attrs = mapOne(diagnostic("error", 3, "m", null, null));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_START));
    assertFalse(attrs.containsKey(MarkerAttributes.CHAR_END));
  }

  @Test
  public void invalidMessageOrPathIsDropped() {
    // Arrays.asList (not List.of): the input legitimately contains nulls
    MappedDiagnostics result =
        DiagnosticsMarkers.map(
            java.util.Arrays.asList(
                new Diagnostic("src/a.cpp", "error", 1, null, null, null),
                new Diagnostic("src/a.cpp", "error", 1, "  ", null, null),
                new Diagnostic(null, "error", 1, "no path", null, null),
                new Diagnostic("", "error", 1, "blank path", null, null),
                null,
                new Diagnostic("src/a.cpp", "error", 1, "good", null, null)));
    assertEquals("one survivor expected", 1, result.markers().size());
    assertEquals("five dropped expected", 5, result.dropped());
    assertEquals("good", result.markers().get(0).get(MarkerAttributes.MESSAGE));
  }

  @Test
  public void attributeKeysAndValueTypes() {
    Map<String, Object> attrs = mapOne(diagnostic("error", 7, "boom", null, null));
    assertEquals(3, attrs.size());
    assertEquals("boom", attrs.get(MarkerAttributes.MESSAGE));
    assertEquals(2, attrs.get(MarkerAttributes.SEVERITY));
    assertEquals(7, attrs.get(MarkerAttributes.LINE_NUMBER));
    assertTrue(attrs.get(MarkerAttributes.SEVERITY) instanceof Integer);
    assertTrue(attrs.get(MarkerAttributes.LINE_NUMBER) instanceof Integer);

    attrs = mapOne(diagnostic("error", 7, "boom", 1, 2));
    assertEquals(5, attrs.size());
    assertTrue(attrs.get(MarkerAttributes.CHAR_START) instanceof Integer);
    assertTrue(attrs.get(MarkerAttributes.CHAR_END) instanceof Integer);
  }

  @Test
  public void nullListMapsToEmptyResult() {
    MappedDiagnostics result = DiagnosticsMarkers.map(null);
    assertTrue(result.markers().isEmpty());
    assertEquals(0, result.dropped());
  }

  @Test
  public void byPathGroupsSurvivorsInFirstSeenOrder() {
    Map<String, List<Map<String, Object>>> grouped =
        DiagnosticsMarkers.byPath(
            List.of(
                new Diagnostic("src/b.cpp", "error", 2, "one", null, null),
                new Diagnostic("src/a.cpp", "warning", 3, "two", null, null),
                new Diagnostic("src/b.cpp", "info", 4, "three", null, null),
                new Diagnostic(null, "error", 1, "dropped: no path", null, null),
                new Diagnostic("src/a.cpp", "error", 1, null, null, null)));
    assertEquals(2, grouped.size());
    assertEquals(List.of("src/b.cpp", "src/a.cpp"), List.copyOf(grouped.keySet()));
    assertEquals(2, grouped.get("src/b.cpp").size());
    assertEquals(1, grouped.get("src/a.cpp").size());
    assertEquals("three", grouped.get("src/b.cpp").get(1).get(MarkerAttributes.MESSAGE));
  }

  @Test
  public void markerConstantsMatchImarkerContract() {
    // Documents the IMarker bridge: keys and severity ints are the
    // org.eclipse.core.resources marker contract (inlined constants).
    assertEquals("message", MarkerAttributes.MESSAGE);
    assertEquals("severity", MarkerAttributes.SEVERITY);
    assertEquals("lineNumber", MarkerAttributes.LINE_NUMBER);
    assertEquals("charStart", MarkerAttributes.CHAR_START);
    assertEquals("charEnd", MarkerAttributes.CHAR_END);
    assertEquals(2, MarkerAttributes.SEVERITY_ERROR);
    assertEquals(1, MarkerAttributes.SEVERITY_WARNING);
    assertEquals(0, MarkerAttributes.SEVERITY_INFO);
  }

  @Test
  public void mapIsDeterministicAcrossRepeatedCalls() {
    List<Diagnostic> input =
        List.of(
            new Diagnostic("src/a.cpp", "error", 2, "first", 0, 5),
            new Diagnostic("src/a.cpp", "info", 9, "second", null, null));
    MappedDiagnostics first = DiagnosticsMarkers.map(input);
    MappedDiagnostics second = DiagnosticsMarkers.map(input);
    assertEquals(first, second);
    assertEquals(0, first.dropped());
  }

  private static Map<String, Object> mapOne(Diagnostic diagnostic) {
    MappedDiagnostics result = DiagnosticsMarkers.map(List.of(diagnostic));
    assertEquals(1, result.markers().size());
    return result.markers().get(0);
  }
}
