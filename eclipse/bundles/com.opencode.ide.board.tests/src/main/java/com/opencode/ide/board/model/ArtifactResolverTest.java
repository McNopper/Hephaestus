package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Containment tests for {@link ArtifactResolver}: agent-written artifact refs are untrusted —
 * repo-subtree confinement, link-escape refusal, executable type refusal, directories always
 * openable.
 */
public class ArtifactResolverTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path repo() throws IOException {
    Path repo = tmp.newFolder("repo").toPath();
    Files.writeString(repo.resolve("notes.md"), "safe");
    Files.writeString(repo.resolve("tool.exe"), "binary");
    Files.writeString(repo.resolve("script.cmd"), "script");
    Files.createDirectories(repo.resolve("src"));
    Files.createDirectories(repo.resolve("outside"));
    return repo;
  }

  @Test
  public void relativeAndAbsoluteRefsInsideRepoOpen() throws IOException {
    Path repo = repo();
    assertTrue(ArtifactResolver.resolve(repo, "notes.md").openable());
    // expected in CANONICAL form: the resolver real-paths its results and
    // on some machines (8.3 short names on CI runners) the lexical temp
    // path differs from the real one (same lesson as B-001)
    assertEquals(
        repo.resolve("notes.md").toRealPath().normalize(),
        ArtifactResolver.resolve(repo, "notes.md").path());
    assertTrue(ArtifactResolver.resolve(repo, repo.resolve("src").toString()).openable());
  }

  @Test
  public void traversalOutsideRepoIsRefused() throws IOException {
    Path repo = repo();
    ArtifactResolver.Result up = ArtifactResolver.resolve(repo, "../outside");
    assertFalse(up.openable());
    assertTrue(up.refusal(), up.refusal().contains("outside the repository"));
  }

  @Test
  public void absolutePathOutsideRepoIsRefused() throws IOException {
    Path repo = repo();
    Path outside = repo.getParent().resolve("definitely-not-in-repo.md");
    assertFalse(ArtifactResolver.resolve(repo, outside.toString()).openable());
  }

  @Test
  public void executableTypesAreRefused() throws IOException {
    Path repo = repo();
    assertTrue(ArtifactResolver.resolve(repo, "tool.exe").refusal().contains("executable"));
    assertTrue(ArtifactResolver.resolve(repo, "script.cmd").refusal().contains("executable"));
  }

  @Test
  public void directoriesAreAlwaysOpenable() throws IOException {
    assertTrue(ArtifactResolver.resolve(repo(), "src").openable());
  }

  @Test
  public void missingFileIsANotFoundRefusal() throws IOException {
    assertNotNull(ArtifactResolver.resolve(repo(), "nope.md").refusal());
  }

  @Test
  public void nullRepoRootAndBlankRefsAreRefused() throws IOException {
    assertNotNull(ArtifactResolver.resolve(null, "x").refusal());
    assertNotNull(ArtifactResolver.resolve(repo(), "  ").refusal());
  }
}
