package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Unit checks of the V-model ladder itself: the canonical stage order, the next/previous boundaries
 * at both tips, the per-stage role and skill maps, the definition/verification split, and the
 * display-only role fallback for legacy tickets.
 */
public class VStagesTest {

  @Test
  public void canonicalOrderIsTheV() {
    assertEquals(
        List.of(
            "requirements",
            "system",
            "architecture",
            "design",
            "implementation",
            "test-implementation",
            "test-design",
            "test-architecture",
            "test-system",
            "test-requirements"),
        VStages.STAGES);
    assertEquals("requirements", VStages.first());
    assertEquals("test-requirements", VStages.last());
  }

  @Test
  public void nextWalksDownAndUpAndStopsAtTheTip() {
    assertEquals("system", VStages.next("requirements"));
    assertEquals("architecture", VStages.next("system"));
    assertEquals("implementation", VStages.next("design"));
    assertEquals("test-implementation", VStages.next("implementation"));
    assertEquals("test-requirements", VStages.next("test-system"));
    assertNull(VStages.next("test-requirements"));
    assertNull(VStages.next(null));
    assertNull(VStages.next("bogus"));
  }

  @Test
  public void previousWalksBackAndStopsAtRequirements() {
    assertNull(VStages.previous("requirements"));
    assertEquals("requirements", VStages.previous("system"));
    assertEquals("implementation", VStages.previous("test-implementation"));
    assertEquals("test-system", VStages.previous("test-requirements"));
    assertNull(VStages.previous(null));
    assertNull(VStages.previous("bogus"));
  }

  @Test
  public void everyStageHasAnOwningRole() {
    assertEquals("pm", VStages.roleOf("requirements"));
    assertEquals("architect", VStages.roleOf("system"));
    assertEquals("architect", VStages.roleOf("architecture"));
    assertEquals("developer", VStages.roleOf("design"));
    assertEquals("developer", VStages.roleOf("implementation"));
    for (String s :
        List.of(
            "test-implementation",
            "test-design",
            "test-architecture",
            "test-system",
            "test-requirements")) {
      assertEquals("tester", VStages.roleOf(s));
    }
    assertNull(VStages.roleOf(null));
    assertNull(VStages.roleOf("nope"));
  }

  @Test
  public void everyStageMapsToItsSkillFamily() {
    assertEquals("software-requirements", VStages.skillOf("requirements"));
    assertEquals("software-system", VStages.skillOf("system"));
    assertEquals("software-architecture", VStages.skillOf("architecture"));
    assertEquals("software-design", VStages.skillOf("design"));
    assertEquals("software-implementation", VStages.skillOf("implementation"));
    assertEquals("test-software-implementation", VStages.skillOf("test-implementation"));
    assertEquals("test-software-design", VStages.skillOf("test-design"));
    assertEquals("test-software-architecture", VStages.skillOf("test-architecture"));
    assertEquals("test-software-system", VStages.skillOf("test-system"));
    assertEquals("test-software-requirements", VStages.skillOf("test-requirements"));
    assertNull(VStages.skillOf(null));
    assertNull(VStages.skillOf("nope"));
  }

  @Test
  public void verificationSplitHappensAtTestImplementation() {
    for (String s : VStages.STAGES.subList(0, 5)) {
      assertFalse("definition leg: " + s, VStages.isVerification(s));
    }
    for (String s : VStages.STAGES.subList(5, 10)) {
      assertTrue("verification leg: " + s, VStages.isVerification(s));
    }
    assertFalse(VStages.isVerification(null));
  }

  @Test
  public void isValidAcceptsOnlyTheCanonicalStages() {
    for (String s : VStages.STAGES) {
      assertTrue(s, VStages.isValid(s));
    }
    assertFalse(VStages.isValid(null));
    assertFalse(VStages.isValid("test"));
    assertFalse(VStages.isValid("Requirements"));
    assertFalse(VStages.isValid(""));
  }

  @Test
  public void deriveFromRoleIsTheLegacyDisplayFallback() {
    assertEquals("requirements", VStages.deriveFromRole("pm"));
    assertEquals("architecture", VStages.deriveFromRole("architect"));
    assertEquals("implementation", VStages.deriveFromRole("developer"));
    assertEquals("test-implementation", VStages.deriveFromRole("tester"));
    assertNull(VStages.deriveFromRole(null));
    assertNull("unknown roles have no provisional stage", VStages.deriveFromRole("cpp-tools"));
  }
}
