package com.opencode.ide.mcp.internal;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Activation-mode contract for the {@code eclipse-build} endpoint component (T-176): it must be
 * {@code immediate="true"}. The only consumer of {@link McpInfo} is core's {@code
 * McpRegistrationComponent} behind a {@code 0..1} dynamic reference — SCR resolves such an optional
 * reference against services that are REGISTERED, and a delayed component registers its service
 * only when it activates, so {@code immediate="false"} meant the endpoint never started (no
 * listener, no registration, dead Agent Tools lane). Mirrors the descriptor contract test in {@code
 * CdtBundleBasicsTest} (T-146), inverted: that component must stay delayed, this one must stay
 * eager.
 */
public class McpEndpointActivationContractTest {

  @Test
  public void eclipseBuildEndpointComponentIsImmediateSoItActuallyStarts() throws Exception {
    // resource read through the component's own bundle classloader: the descriptor lives at the
    // root of com.opencode.ide.mcp (src/main/resources/OSGI-INF/McpServiceComponent.xml)
    try (InputStream in =
        McpServiceComponent.class.getResourceAsStream("/OSGI-INF/McpServiceComponent.xml")) {
      assertNotNull("component descriptor must be on the mcp bundle classpath", in);
      String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          "descriptor still declares the component",
          xml.contains("com.opencode.ide.mcp.internal.McpServiceComponent"));
      assertTrue(
          "the endpoint component must be immediate (T-176): " + xml,
          xml.contains("immediate=\"true\""));
      assertTrue("it provides the McpInfo seam", xml.contains("com.opencode.ide.mcp.McpInfo"));
    }
  }
}
