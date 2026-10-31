package com.opencode.ide.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * U-083 - the QUALITY.md layer graph and the namespace convention as executable tests
 * (code-quality-matrix: Java / Arch rules = ArchUnit). Where {@code releng/check-architecture.ps1}
 * verifies the BUNDLE graph via the manifests, this suite reads the actual reactor BYTECODE - which
 * also catches edges the manifest scan can never see, e.g. packages that arrive through the parent
 * pom's {@code pomDependencies=consider} instead of a Require-Bundle entry.
 *
 * <p>The suite imports every production {@code target/classes} directory of the reactor (tests
 * modules excluded by path; reactor root resolved by climbing up at runtime - never a fixed machine
 * path). It therefore must build AFTER all production bundles: it is the last module before the
 * feature/repository.
 *
 * <p>Two seeded-proof tests keep the rules honest: a fixture outside the namespace and a
 * tools->board fixture each MUST fail their rule.
 */
public class LayerArchitectureTest {

  /**
   * Platform and third-party namespaces every bundle may reference (the +{...} side of the QUALITY
   * graph).
   */
  private static final List<String> PLATFORM =
      List.of(
          "java..",
          "javax..",
          "jakarta..",
          "org.osgi..",
          "org.eclipse..",
          "com.google.gson..",
          "org.apache..",
          "org.slf4j..",
          // JDK-shipped modules (e.g. jdk.httpserver's com.sun.net.httpserver,
          // used by the chat web server): runtime platform, not a dependency -
          // manifests never list them, so the bytecode rule must allow them here
          "com.sun..");

  /**
   * The QUALITY.md layer graph: bundle -> the bundles it may depend on (self is added per rule).
   */
  private static final Map<String, List<String>> GRAPH = new LinkedHashMap<>();

  static {
    GRAPH.put("board", List.of("chat", "fleet", "core", "tasks", "git", "client"));
    GRAPH.put("chat", List.of("core", "client"));
    GRAPH.put("ui", List.of("core", "client", "tools"));
    GRAPH.put("cdt", List.of("core"));
    GRAPH.put("core", List.of("client", "mcp"));
    GRAPH.put("fleet", List.of("client", "git", "tasks", "tools"));
    GRAPH.put("mcp", List.of("client", "tasks", "tools"));
    GRAPH.put("tasks", List.of("tools"));
    GRAPH.put("git", List.of("client"));
    GRAPH.put("client", List.of());
    GRAPH.put("tools", List.of());
    GRAPH.put("mojo", List.of("tasks"));
  }

  private static JavaClasses production;

  @BeforeClass
  public static void importReactorBytecode() {
    List<Path> dirs = productionClassDirs();
    if (dirs.isEmpty()) {
      throw new IllegalStateException(
          "no target/classes found under the reactor - run this suite inside the Maven build");
    }
    production = new ClassFileImporter().importPaths(dirs.toArray(new Path[0]));
  }

  /** Every production class lives in the flat com.opencode.ide namespace (AGENTS.md convention). */
  @Test
  public void everyProductionClassLivesInTheHephaestusNamespace() {
    classes()
        .should()
        .haveNameMatching("com\\.opencode\\.ide\\..+")
        .because(
            "AGENTS.md: the namespace is com.opencode.ide.* (mojo under com.opencode.ide.mojo)")
        .check(production);
  }

  /** The bundle layer graph from QUALITY.md, checked on real dependency edges. */
  @Test
  public void bundleDependenciesStayInsideTheQualityLayerGraph() {
    for (Map.Entry<String, List<String>> entry : GRAPH.entrySet()) {
      noClasses()
          .that()
          .resideInAPackage("com.opencode.ide." + entry.getKey() + "..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages(allowedFor(entry.getKey(), entry.getValue()))
          .because("QUALITY.md layer graph: " + entry.getKey() + " -> " + entry.getValue())
          .check(production);
    }
  }

  /** Slices at bundle granularity must be acyclic (the graph is acyclic by design). */
  @Test
  public void bundlesFormNoDependencyCycles() {
    SlicesRuleDefinition.slices()
        .matching("com.opencode.ide.(*)..")
        .should()
        .beFreeOfCycles()
        .because("the QUALITY.md layer graph is acyclic by design")
        .check(production);
  }

  /** The Eclipse-free layer never sees SWT/JFace/workbench types at the bytecode level. */
  @Test
  public void eclipseFreeLayerNeverTouchesSwtJfaceOrWorkbench() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.opencode.ide.client..",
            "com.opencode.ide.tools..",
            "com.opencode.ide.tasks..",
            "com.opencode.ide.git..",
            "com.opencode.ide.fleet..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.eclipse.swt..",
            "org.eclipse.jface..",
            "org.eclipse.ui..",
            "org.eclipse.e4..",
            "org.eclipse.workbench..")
        .because("QUALITY.md: the Eclipse-free layer never sees SWT/JFace/workbench types")
        .check(production);
  }

  /** The import scope is production-only: no tests module ever enters the scan. */
  @Test
  public void onlyProductionClassDirsAreImported() {
    for (Path dir : productionClassDirs()) {
      assertFalse(
          "tests modules are out of the scan scope: " + dir, dir.toString().contains(".tests"));
    }
  }

  /** Seeded proof 1: a class outside com.opencode.ide MUST fail the namespace rule. */
  @Test
  public void theNamespaceRuleWouldRejectAClassOutsideTheNamespace() {
    JavaClasses fixture =
        new ClassFileImporter().importClasses(outside.namespace.OutsideNamespace.class);

    AssertionError violation =
        catchViolation(
            () -> classes().should().haveNameMatching("com\\.opencode\\.ide\\..+").check(fixture));

    assertNotNull("the seeded namespace violation must fail the rule", violation);
  }

  /** Seeded proof 2: a tools->board dependency MUST fail the layer-graph rule. */
  @Test
  public void theLayerGraphWouldRejectToolsDependingOnBoard() {
    JavaClasses fixture =
        new ClassFileImporter().importClasses(com.opencode.ide.tools.probe.BoardImportProbe.class);

    AssertionError violation =
        catchViolation(
            () ->
                noClasses()
                    .that()
                    .resideInAPackage("com.opencode.ide.tools..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages(allowedFor("tools", List.of()))
                    .check(fixture));

    assertNotNull("the seeded layer violation must fail the rule", violation);
  }

  private static String[] allowedFor(String bundle, List<String> allowedBundles) {
    List<String> allowed = new ArrayList<>();
    allowed.add("com.opencode.ide." + bundle + "..");
    for (String other : allowedBundles) {
      allowed.add("com.opencode.ide." + other + "..");
    }
    allowed.addAll(PLATFORM);
    return allowed.toArray(new String[0]);
  }

  private static AssertionError catchViolation(Runnable rule) {
    try {
      rule.run();
      return null;
    } catch (AssertionError expected) {
      return expected;
    }
  }

  /**
   * The production class directories of the reactor: every non-tests module under {@code bundles/}
   * plus the plain {@code mojo/opencode-tasks} module. The reactor root is found by climbing up
   * from the module directory until pom.xml + bundles/ + releng/ mark the spot (runtime resolution,
   * never a fixed path - the rule the arch check itself enforces).
   */
  private static List<Path> productionClassDirs() {
    Path start = Path.of("").toAbsolutePath();
    Path root = null;
    for (Path p = start; p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("pom.xml"))
          && Files.isDirectory(p.resolve("bundles"))
          && Files.isDirectory(p.resolve("releng"))) {
        root = p;
        break;
      }
    }
    if (root == null) {
      throw new IllegalStateException("reactor root not found above " + start);
    }
    List<Path> out = new ArrayList<>();
    try (Stream<Path> bundles = Files.list(root.resolve("bundles"))) {
      for (Path bundle : bundles.filter(Files::isDirectory).sorted().toList()) {
        if (bundle.getFileName().toString().endsWith(".tests")) {
          continue;
        }
        Path classes = bundle.resolve("target").resolve("classes");
        if (Files.isDirectory(classes)) {
          out.add(classes);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot list the reactor bundles", e);
    }
    Path mojo = root.resolve("mojo").resolve("opencode-tasks").resolve("target").resolve("classes");
    if (Files.isDirectory(mojo)) {
      out.add(mojo);
    }
    return out;
  }
}
