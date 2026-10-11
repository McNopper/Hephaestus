package com.opencode.ide.mojo.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Validates (and with {@code -Dopencode.tasks.fix=true} normalizes) every project of the opencode
 * task store: frontmatter schema, {@code _meta.json} counter consistency, LF line endings, unique
 * ids, sprint references. Has no default phase — invoke it directly as {@code mvn
 * opencode-tasks:sync}.
 */
@Mojo(name = "sync")
public class SyncMojo extends AbstractMojo {

  /** The store root (the tasks directory itself). Default: nearest .opencode/tasks walking up. */
  @Parameter(property = "opencode.tasks.root")
  private File tasksRoot;

  /** Apply the safe fixes (CRLF to LF re-encode, counter bumps). */
  @Parameter(property = "opencode.tasks.fix", defaultValue = "false")
  private boolean fix;

  /** Fail on fixable findings even when fix is off. */
  @Parameter(property = "opencode.tasks.strict", defaultValue = "false")
  private boolean strict;

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
                  + " and opencode.tasks.root is unset; nothing to sync.");
      return;
    }
    if (!Files.isDirectory(root)) {
      throw new MojoExecutionException("opencode.tasks.root is not a directory: " + root);
    }
    getLog()
        .info(
            "Syncing opencode task store at "
                + root
                + (fix ? " (fix on)" : "")
                + (strict ? " (strict)" : ""));
    StoreSync.Result result;
    try {
      result = new StoreSync(root).run(fix);
    } catch (IOException e) {
      throw new MojoExecutionException("cannot read task store at " + root, e);
    }
    int errors = 0;
    int fixables = 0;
    int warnings = 0;
    for (StoreSync.ProjectReport report : result.projects()) {
      getLog()
          .info(
              "Project '"
                  + report.project()
                  + "': "
                  + report.filesChecked()
                  + " task file(s) checked");
      for (String applied : report.appliedFixes()) {
        getLog().info("  [fixed] " + report.project() + "/" + applied);
      }
      for (StoreSync.Finding f : report.findings()) {
        String line = report.project() + "/" + f.file() + ": " + f.message();
        if (f.severity() == StoreSync.Severity.WARNING) {
          warnings++;
          getLog().warn("  " + line);
        } else if (f.fixable()) {
          fixables++;
          if (fix) {
            getLog().info("  [fixed] " + line);
          } else if (strict) {
            getLog().error("  " + line + " (fixable; run with -Dopencode.tasks.fix=true)");
          } else {
            warnings++;
            getLog().warn("  " + line + " (fixable with -Dopencode.tasks.fix=true)");
          }
        } else {
          errors++;
          getLog().error("  " + line);
        }
      }
    }
    if (result.projects().isEmpty()) {
      getLog().info("No project directories with task files found under " + root);
      return;
    }
    if (errors > 0) {
      throw new MojoExecutionException(
          "opencode-tasks:sync found "
              + errors
              + " unfixable problem(s) in "
              + root
              + "; see the log above.");
    }
    if (strict && !fix && fixables > 0) {
      throw new MojoExecutionException(
          "opencode-tasks:sync (strict) found "
              + fixables
              + " fixable problem(s); run with -Dopencode.tasks.fix=true.");
    }
    getLog()
        .info(
            "Store at "
                + root
                + " is consistent ("
                + fixables
                + " fixable finding(s), "
                + warnings
                + " warning(s)).");
  }
}
