package com.opencode.ide.git.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opencode.ide.git.StoreGitStatus;
import com.opencode.ide.git.StoreSync.Outcome;

/**
 * The task store's git plumbing behind {@link StoreGitStatus} and
 * {@link StoreSync}. All invocations go through {@code git -C <dir> ...} with
 * UTF-8 output capture and a timeout. Unlike {@link GitWorktreeManager} this
 * layer never throws: failures become {@link StoreGitStatus#NONE} /
 * {@link Outcome#FAILED} plus a stderr tail in the log.
 */
public final class GitStore {

    private static final Logger LOG = Logger.getLogger(GitStore.class.getName());

    private static final Duration TIMEOUT = com.opencode.ide.git.GitTuning.SYNC_TIMEOUT;
    private static final String GIT = com.opencode.ide.git.GitLocator.resolve().command().toString();
    private static final String DEFAULT_MESSAGE = "sync task store";
    private static final int LOG_TAIL = com.opencode.ide.git.GitTuning.WARN_TAIL;

    private static final Pattern AHEAD = Pattern.compile("\\bahead (\\d+)");
    private static final Pattern BEHIND = Pattern.compile("\\bbehind (\\d+)");

    private GitStore() {
    }

    record GitOutput(int exitCode, String stdout, String stderr) {
    }

    public static StoreGitStatus status(Path root) {
        Path repo = repo(root);
        if (repo == null) {
            return StoreGitStatus.NONE;
        }
        // scoped to the store subtree with the transient lock file excluded:
        // an in-flight TaskStore transaction must never show as store dirt
        GitOutput out = run(repo, "status", "--porcelain=v1", "-b", "--", ".", ":(exclude)*.lock");
        if (out.exitCode() != 0) {
            return StoreGitStatus.NONE;
        }
        String branch = null;
        int ahead = 0;
        int behind = 0;
        int changed = 0;
        boolean detached = false;
        boolean headerSeen = false;
        for (String line : out.stdout().split("\\R")) {
            if (!headerSeen && line.startsWith("## ")) {
                headerSeen = true;
                String header = line.substring(3).trim();
                if ("No branch".equals(header) || header.startsWith("HEAD (no branch")) {
                    detached = true;
                } else if (header.startsWith("No commits yet on ")) {
                    branch = header.substring("No commits yet on ".length()).trim();
                } else {
                    int tracking = header.indexOf("...");
                    int bracket = header.indexOf('[');
                    int end = tracking >= 0 ? tracking : header.length();
                    if (bracket >= 0 && bracket < end) {
                        end = bracket;
                    }
                    branch = header.substring(0, end).trim();
                    ahead = match(AHEAD, header);
                    behind = match(BEHIND, header);
                }
            } else if (!line.isBlank()) {
                changed++;
            }
        }
        return new StoreGitStatus(branch, ahead, behind, changed, detached);
    }

    public static Outcome sync(Path root, String message) {
        Path repo = repo(root);
        if (repo == null) {
            return Outcome.NOT_A_REPO;
        }
        if (!isWorkTree(repo)) {
            return gitUnusable() ? Outcome.FAILED : Outcome.NOT_A_REPO;
        }
        // R2: sync mutates the shared repo (commit/pull/push) - ride the repo
        // gate keyed on the TRUE git toplevel (the store dir and the repo
        // root are different keys; a per-dir key would not serialize against
        // commitAll/mergeBack on the same repository)
        Path gateKey = toplevelOf(repo);
        return com.opencode.ide.git.RepoGate.with(gateKey != null ? gateKey : repo,
                () -> syncGuarded(repo, message));
    }

    /** The git worktree top-level of {@code dir}, or null when git cannot say. */
    private static Path toplevelOf(Path dir) {
        GitOutput out = run(dir, "rev-parse", "--show-toplevel");
        if (out.exitCode() == 0 && !out.stdout().isBlank()) {
            return Path.of(out.stdout().trim()).toAbsolutePath().normalize();
        }
        return null;
    }

    private static Outcome syncGuarded(Path repo, String message) {
        // scoped to the store subtree: a pathspec-less `add -A` stages the
        // ENTIRE repository no matter the cwd (git >= 2.0) - from the store
        // dir that would sweep the host repo's unrelated WIP into a store
        // commit and push it. The EXCLUDE keeps the TaskStore's transient
        // per-project OS lock file out of the index: once staged it stays
        // dirty forever (staged-add survives worktree deletion), which hung
        // a fleet test's clean-check for its whole deadline (2026-09-13).
        GitOutput add = runLocked(repo, "add", "-A", "--", ".", ":(exclude)*.lock");
        if (add.exitCode() != 0) {
            warn("git add -A", add);
            return Outcome.FAILED;
        }
        GitOutput staged = runLocked(repo, "diff", "--cached", "--name-only", "--", ".", ":(exclude)*.lock");
        if (staged.exitCode() != 0) {
            warn("git diff --cached --name-only", staged);
            return Outcome.FAILED;
        }
        boolean committed = false;
        if (!staged.stdout().isBlank()) {
            GitOutput commit = runLocked(repo, "commit", "--only", "-m",
                    message == null || message.isBlank() ? DEFAULT_MESSAGE : message,
                    "--", ".", ":(exclude)*.lock");
            if (commit.exitCode() != 0) {
                warn("git commit", commit);
                return Outcome.FAILED;
            }
            committed = true;
        }
        GitOutput pull = runLocked(repo, "pull", "--rebase");
        if (pull.exitCode() != 0) {
            warn("git pull --rebase", pull);
            return rebaseInProgress(repo) ? Outcome.PULL_CONFLICT : Outcome.FAILED;
        }
        boolean unpushed = unpushedCommits(repo) != 0;
        GitOutput push = runLocked(repo, "push");
        if (push.exitCode() != 0) {
            warn("git push", push);
            return rejected(push) ? Outcome.PUSH_REJECTED : Outcome.FAILED;
        }
        return committed || unpushed ? Outcome.PUSHED : Outcome.UP_TO_DATE;
    }

    public static Outcome recover(Path root) {
        Path repo = repo(root);
        if (repo == null) {
            return Outcome.NOT_A_REPO;
        }
        if (!isWorkTree(repo)) {
            return gitUnusable() ? Outcome.FAILED : Outcome.NOT_A_REPO;
        }
        Path gateKey = toplevelOf(repo);
        return com.opencode.ide.git.RepoGate.with(gateKey != null ? gateKey : repo, () -> {
            GitOutput abort = run(repo, "rebase", "--abort");
            if (abort.exitCode() != 0) {
                warn("git rebase --abort", abort);
            }
            return rebaseInProgress(repo) ? Outcome.PULL_CONFLICT : Outcome.FAILED;
        });
    }

    private static int match(Pattern pattern, String header) {
        Matcher matcher = pattern.matcher(header);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * Runs a mutating git step with bounded retries on transient lock
     * contention ({@code index.lock: File exists} / "Another git process").
     * Any concurrent git - a poller's status refresh, an IDE, a peer engine,
     * the CLI - briefly takes the index lock; a one-shot step then fails
     * forever after (2026-09-25: the store sync reported FAILED while a
     * test's git-status poll refreshed the index). Transient by nature, so
     * retry with a growing sleep between attempts.
     */
    private static GitOutput runLocked(Path repo, String... command) {
        GitOutput last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            last = run(repo, command);
            if (last.exitCode() == 0 || !lockContended(last)) {
                return last;
            }
            try {
                Thread.sleep(50L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** @return whether the failure is transient index-lock contention. */
    private static boolean lockContended(GitOutput out) {
        String text = out.stdout() + out.stderr();
        return text.contains("index.lock") || text.contains("Another git process");
    }

    private static boolean isWorkTree(Path repo) {
        GitOutput probe = run(repo, "rev-parse", "--is-inside-work-tree");
        boolean inside = probe.exitCode() == 0 && "true".equals(probe.stdout().trim());
        if (!inside) {
            // loud at the decision point (2026-09-25: NOT_A_REPO was decided
            // here with the probe's evidence dropped - debug by telemetry)
            warn("git rev-parse --is-inside-work-tree (probe of " + repo + ")", probe);
        }
        return inside;
    }

    private static boolean gitUnusable() {
        GitOutput version = run(Path.of("."), "--version");
        if (version.exitCode() != 0) {
            warn("git --version (usability probe)", version);
        }
        return version.exitCode() != 0;
    }

    private static boolean rejected(GitOutput push) {
        String output = (push.stdout() + push.stderr()).toLowerCase(Locale.ROOT);
        return output.contains("rejected") || output.contains("non-fast-forward")
                || output.contains("fetch first");
    }

    private static int unpushedCommits(Path repo) {
        GitOutput count = run(repo, "rev-list", "--count", "@{upstream}..HEAD");
        if (count.exitCode() != 0) {
            return -1;
        }
        try {
            return Integer.parseInt(count.stdout().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean rebaseInProgress(Path repo) {
        if (run(repo, "rev-parse", "-q", "--verify", "REBASE_HEAD").exitCode() == 0) {
            return true;
        }
        return rebaseDirExists(repo, "rebase-merge") || rebaseDirExists(repo, "rebase-apply");
    }

    private static boolean rebaseDirExists(Path repo, String name) {
        GitOutput path = run(repo, "rev-parse", "--git-path", name);
        if (path.exitCode() != 0 || path.stdout().isBlank()) {
            return false;
        }
        return Files.isDirectory(repo.resolve(path.stdout().trim()));
    }

    private static Path repo(Path root) {
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    private static void warn(String action, GitOutput out) {
        String stderr = out.stderr().trim();
        String tail = stderr.length() <= LOG_TAIL ? stderr : stderr.substring(stderr.length() - LOG_TAIL);
        LOG.log(Level.WARNING, action + " failed (exit " + out.exitCode() + "): " + tail);
    }

    private static GitOutput run(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add(GIT);
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return new GitOutput(-1, "", "failed to start git (" + GIT + "): " + e.getMessage());
        }
        // the shared run pipeline (GitProcesses.await) - failures stay values here
        GitProcesses.Result result = GitProcesses.await(process, args, TIMEOUT);
        if (result.failed()) {
            return new GitOutput(-1, "", result.failure());
        }
        return new GitOutput(result.exitCode(), result.stdout(), result.stderr());
    }

}
