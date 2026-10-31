package com.opencode.ide.core.context;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Integration seam between the opencode core/UI layers and an IDE-specific layer.
 *
 * <p>Defined in {@code core} so that the core and UI bundles never depend on IDE-specific APIs
 * (e.g. CDT). The {@code com.opencode.ide.cdt} bundle registers an implementation of this interface
 * as an OSGi Declarative Service.
 *
 * <p>This is a stub seam: for now it only exposes the working directory that a spawned {@code
 * opencode serve} should use. It will grow as the CDT integration grows (current project,
 * translation unit, build config, ...).
 */
public interface ProjectContext {

  /**
   * @return the working directory the opencode server should run in for the active project, or
   *     empty if none can be determined.
   */
  Optional<Path> getWorkingDirectory();
}
