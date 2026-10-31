package com.opencode.ide.mojo.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Renders the sprint board of every project in the opencode task store into {@code board.md} and
 * {@code board.html} under {@code opencode.tasks.out} (default {@code target/opencode-tasks/}). Has
 * no default phase — invoke it directly as {@code mvn opencode-tasks:plan}. Read-only against the
 * store.
 */
@Mojo(name = "plan")
public class PlanMojo extends AbstractMojo {

  /** The store root (the tasks directory itself). Default: nearest .opencode/tasks walking up. */
  @Parameter(property = "opencode.tasks.root")
  private File tasksRoot;

  /** Selects a sprint id explicitly; default is the most recently created active sprint. */
  @Parameter(property = "opencode.tasks.sprint")
  private String sprint;

  /** Output directory for board.md and board.html. */
  @Parameter(
      property = "opencode.tasks.out",
      defaultValue = "${project.build.directory}/opencode-tasks")
  private File outDir;

  @Parameter(defaultValue = "${project.basedir}", readonly = true)
  private File basedir;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = StoreIo.resolveRoot(tasksRoot, basedir.toPath());
    if (root == null) {
      getLog()
          .info(
              "No .opencode/tasks store found walking up from "
                  + basedir
                  + " and opencode.tasks.root is unset; nothing to render.");
      return;
    }
    if (!Files.isDirectory(root)) {
      throw new MojoExecutionException("opencode.tasks.root is not a directory: " + root);
    }
    List<BoardRenderer.ProjectBoard> boards;
    try {
      boards = StoreBoards.load(root, sprint, getLog()::warn);
    } catch (IOException e) {
      throw new MojoExecutionException("cannot read task store at " + root, e);
    }
    if (boards.isEmpty()) {
      getLog()
          .info(
              "No project directories with task files found under "
                  + root
                  + "; nothing to render.");
      return;
    }
    try {
      Path out = outDir.toPath();
      Files.createDirectories(out);
      Path md = out.resolve("board.md");
      Path html = out.resolve("board.html");
      StoreIo.writeAtomic(md, BoardRenderer.markdown(boards));
      StoreIo.writeAtomic(html, BoardRenderer.html(boards));
      getLog().info("Rendered " + boards.size() + " project board(s) to " + md + " and " + html);
    } catch (IOException e) {
      throw new MojoExecutionException("cannot write board output to " + outDir, e);
    }
  }
}
