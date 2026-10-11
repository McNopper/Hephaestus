package com.opencode.ide.cdt.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.cdt.markers.MarkerApplier;
import com.opencode.ide.core.context.ProjectContext;
import java.util.List;
import org.junit.Test;

/**
 * Bundle-wiring sanity for {@code com.opencode.ide.cdt}: proves the bundle is non-empty and its key
 * classes load on the test classpath. Pure {@code Class.forName}/constant checks - no OSGi
 * activation, no workspace.
 */
public class CdtBundleBasicsTest {

  @Test
  public void activatorClassLoads() throws Exception {
    Class<?> activator = Class.forName("com.opencode.ide.cdt.CdtPlugin");
    assertEquals("com.opencode.ide.cdt", activator.getField("PLUGIN_ID").get(null));
  }

  @Test
  public void dsComponentClassLoads() throws Exception {
    // the ProjectContext implementation (registered via OSGI-INF/CdtProjectContext.xml)
    assertNotNull(Class.forName("com.opencode.ide.cdt.internal.CdtProjectContext"));
  }

  @Test
  public void markerTypeMatchesDeclaredExtensionId() {
    assertEquals("com.opencode.ide.cdt.diagnostic", MarkerApplier.MARKER_TYPE);
  }

  @Test
  public void markerApplierIsInstantiableWithoutOsgi() {
    // the DS service class must work as a plain object (headless, no workspace)
    MarkerApplier applier = new MarkerApplier();
    assertEquals(-1, applier.apply(null, List.of()));
  }

  @Test
  public void projectContextIsImplementedByCdtComponent() throws Exception {
    Class<?> component = Class.forName("com.opencode.ide.cdt.internal.CdtProjectContext");
    assertTrue(
        "CdtProjectContext must implement the core seam",
        ProjectContext.class.isAssignableFrom(component));
  }
}
