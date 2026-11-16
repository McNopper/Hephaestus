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

  /**
   * T-146: the component must stay DELAYED. The implementation class-loads {@code
   * org.eclipse.cdt.core.model.*}, so {@code immediate} would lazy-start {@code
   * org.eclipse.cdt.core} at framework start - before IDEApplication sets the instance area - and
   * every launch would log {@code IllegalStateException: The instance data location has not been
   * specified yet} from {@code CCorePlugin.start()}. Delayed activation waits for core's lazy
   * ProjectContext tracker (first {@code getService}), long after the instance area exists.
   */
  @Test
  public void projectContextComponentIsDelayedSoCdtNeverStartsBeforeTheInstanceArea()
      throws Exception {
    // read the descriptor from the CDT BUNDLE itself (a bundle classloader's
    // getResource only searches its own bundle - the entry lives in com.opencode.ide.cdt)
    java.net.URL entry =
        org.eclipse.core.runtime.Platform.getBundle("com.opencode.ide.cdt")
            .getEntry("OSGI-INF/CdtProjectContext.xml");
    assertNotNull("component descriptor must be an entry of the CDT bundle", entry);
    String xml;
    try (java.io.InputStream in = entry.openStream()) {
      xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(
        "descriptor still declares the component",
        xml.contains("com.opencode.ide.cdt.internal.CdtProjectContext"));
    assertTrue(
        "the component must NOT be immediate (T-146): " + xml, !xml.contains("immediate=\"true\""));
  }
}
