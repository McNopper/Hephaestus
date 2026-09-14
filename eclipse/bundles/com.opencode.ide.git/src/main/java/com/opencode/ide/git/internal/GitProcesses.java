package com.opencode.ide.git.internal;

/** Termination shared by worktree and store commands. */
final class GitProcesses {
    private GitProcesses() { }

    /** Wait before releasing RepoGate. Git locks carry no owner identity, so
     * never delete them or abort an unknown merge as automatic cleanup. */
    static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
