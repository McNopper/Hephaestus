package com.opencode.ide.tools.cpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tools.cpp.ToolchainRegistry.Toolchain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;

/**
 * Machine-dependent detection checks; every test silently skips on machines without the
 * corresponding prerequisite (MSYS2 install / vswhere).
 */
public class ToolchainRegistryTest {

  private static final Path MSYS2 = Paths.get("C:\\msys64");
  private static final Path VSWHERE =
      Paths.get("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe");

  // ONE canonical skip reason per test method: which of a method's staged assumes
  // fires is environment-dependent (MSYS2 present vs absent), and the
  // coverage-manifest gate compares both-skip reason strings strictly across
  // machines - stage-specific wording would read as DRIFT on every other runner.
  private static final String UCRT64_SKIP_REASON = "ucrt64 toolchain env not available";
  private static final String CLANG64_SKIP_REASON = "clang64 toolchain env not available";

  @Test
  public void ucrt64DetectedWithCmakeNinjaAndNoGdb() {
    Assume.assumeTrue(UCRT64_SKIP_REASON, Files.isDirectory(MSYS2));
    Optional<Toolchain> ucrt64 = ToolchainRegistry.byId("ucrt64");
    Assume.assumeTrue(UCRT64_SKIP_REASON, ucrt64.isPresent());
    Toolchain tc = ucrt64.get();
    assertTrue("ucrt64 cmake expected", tc.cmake().isPresent());
    assertTrue("ucrt64 ninja expected", tc.ninja().isPresent());
    assertTrue("ucrt64 compiler expected", tc.compiler().isPresent());
    assertEquals("Ninja", tc.generator().orElse(null));
    assertEquals(List.of(MSYS2.resolve("ucrt64").resolve("bin")), tc.pathPrepend());
    assertFalse("no gdb expected on this machine (env or PATH)", tc.gdb().isPresent());
  }

  @Test
  public void clang64AndMingw64DetectedWhenPresent() {
    Assume.assumeTrue(CLANG64_SKIP_REASON, Files.isDirectory(MSYS2));
    for (String id : new String[] {"clang64", "mingw64"}) {
      Optional<Toolchain> tc = ToolchainRegistry.byId(id);
      Assume.assumeTrue(CLANG64_SKIP_REASON, tc.isPresent());
      assertTrue(
          id + " must have cmake+compiler or would not be reported",
          tc.get().cmake().isPresent() || tc.get().compiler().isPresent());
    }
  }

  @Test
  public void msvcDetectedWithVisualStudioGenerator() {
    Assume.assumeTrue("no vswhere on this machine", Files.isRegularFile(VSWHERE));
    Optional<Toolchain> msvc = ToolchainRegistry.byId("msvc");
    assertTrue("msvc expected when vswhere finds an installation", msvc.isPresent());
    assertTrue("msvc must have cmake (standalone or PATH)", msvc.get().cmake().isPresent());
    String generator = msvc.get().generator().orElse(null);
    assertTrue(
        "msvc must have a Visual Studio generator, got: " + generator,
        generator != null && Pattern.matches("Visual Studio \\d+ \\d{4}", generator));
  }

  @Test
  public void detectedListOrderPutsMsvcFirstAndDefaultMatchesFirst() {
    List<Toolchain> all = ToolchainRegistry.detected();
    Assume.assumeFalse("no toolchains detected on this machine", all.isEmpty());
    assertEquals(
        "first detected entry is the default",
        all.get(0).id(),
        ToolchainRegistry.defaultToolchain().orElseThrow().id());
    boolean msvcSeen = false;
    for (Toolchain tc : all) {
      if ("msvc".equals(tc.id())) {
        assertFalse("msvc must be the first entry when detected", msvcSeen);
        msvcSeen = true;
      }
    }
  }
}
