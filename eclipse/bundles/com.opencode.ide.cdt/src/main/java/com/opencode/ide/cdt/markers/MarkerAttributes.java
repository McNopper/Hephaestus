package com.opencode.ide.cdt.markers;

import org.eclipse.core.resources.IMarker;

/**
 * Thin, documented constants bridge between the pure mapping code ({@link DiagnosticsMarkers}) and
 * the {@code org.eclipse.core.resources} marker API. All fields are compile-time constants (inlined
 * by the compiler), so classes that only reference them - the pure mapper and its unit tests -
 * never load {@code IMarker} or need a running workspace.
 *
 * <p>Attribute keys and severity values are exactly the {@link IMarker} contract: {@code message},
 * {@code severity}, {@code line}, {@code charStart}, {@code charEnd}; severities {@code error=2},
 * {@code warning=1}, {@code info=0}.
 */
public interface MarkerAttributes {

  /** Marker attribute key: the human-readable message ({@link IMarker#MESSAGE}). */
  String MESSAGE = IMarker.MESSAGE;

  /** Marker attribute key: the severity int ({@link IMarker#SEVERITY}). */
  String SEVERITY = IMarker.SEVERITY;

  /** Marker attribute key: the 1-based line number ({@link IMarker#LINE_NUMBER}). */
  String LINE_NUMBER = IMarker.LINE_NUMBER;

  /** Marker attribute key: the inclusive start character offset ({@link IMarker#CHAR_START}). */
  String CHAR_START = IMarker.CHAR_START;

  /** Marker attribute key: the inclusive end character offset ({@link IMarker#CHAR_END}). */
  String CHAR_END = IMarker.CHAR_END;

  /** Severity value for errors (see {@link IMarker#SEVERITY_ERROR}). */
  int SEVERITY_ERROR = IMarker.SEVERITY_ERROR;

  /** Severity value for warnings (see {@link IMarker#SEVERITY_WARNING}). */
  int SEVERITY_WARNING = IMarker.SEVERITY_WARNING;

  /** Severity value for informational messages (see {@link IMarker#SEVERITY_INFO}). */
  int SEVERITY_INFO = IMarker.SEVERITY_INFO;
}
