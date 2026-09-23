package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.ProjectSummary;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Unit tests for the SWT-free {@link ProjectVcs} project/VCS header model of
 * the Server view: cwd→project resolution with the first-project fallback,
 * the lenient merge with {@code GET /vcs}, dirtiness from
 * {@code GET /file/status}, graceful degradation to
 * {@link ProjectVcs#UNKNOWN}, and the {@code summary()}/{@code tooltip()}
 * renderings.
 */
public class ProjectVcsTest {

    // ---------- fixtures ----------

    private static ProjectSummary project(String worktree, String branch, String repository) {
        return new ProjectSummary(worktree, branch, repository);
    }

    /** Only the project/VCS/status endpoints work; everything else is never touched. */
    private static final class FakeClient extends com.opencode.ide.ui.testsupport.StubClient {
        List<ProjectSummary> projects = List.of();
        VcsInfo vcs = new VcsInfo(null, null);
        List<FileStatus> status = List.of();
        boolean throwOnProjects;
        boolean throwOnVcs;
        boolean throwOnStatus;

        @Override
        public List<ProjectSummary> getProjects() throws OpencodeException {
            if (throwOnProjects) {
                throw new OpencodeException("project endpoint gone");
            }
            return projects;
        }

        @Override
        public VcsInfo getVcsInfo() throws OpencodeException {
            if (throwOnVcs) {
                throw new OpencodeException("vcs endpoint gone");
            }
            return vcs;
        }

        @Override
        public List<FileStatus> getFileStatus() throws OpencodeException {
            if (throwOnStatus) {
                throw new OpencodeException("status endpoint gone");
            }
            return status;
        }

    }

    // ---------- project resolution ----------

    @Test
    public void matchedCwdProjectRendersCleanSummary() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/other", "dev", null), project("/w/hephaestus", "main", null));

        ProjectVcs vcs = ProjectVcs.load(client, "/w/hephaestus");

        assertEquals("hephaestus · branch main", vcs.summary());
        assertEquals("Project: /w/hephaestus\nBranch: main", vcs.tooltip());
    }

    @Test
    public void cwdMatchIgnoresTrailingSeparators() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("C:\\w\\foo", "main", null));

        assertEquals("foo · branch main", ProjectVcs.load(client, "C:\\w\\foo\\").summary());
        assertEquals("foo · branch main", ProjectVcs.load(client, "C:\\w\\foo/").summary());
    }

    @Test
    public void cwdMissFallsBackToFirstProject() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/first", "main", null), project("/w/second", "dev", null));

        ProjectVcs vcs = ProjectVcs.load(client, "/nowhere");

        assertEquals("first · branch main", vcs.summary());
        assertEquals("Project: /w/first\nBranch: main", vcs.tooltip());
    }

    @Test
    public void nullCwdUsesFirstProject() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/first", "main", null));

        assertEquals("first · branch main", ProjectVcs.load(client, null).summary());
    }

    @Test
    public void projectWithoutVcsFieldsFallsBackToVcsEndpoint() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", " ", null));   // blank branch
        client.vcs = new VcsInfo("dev", "git@github.com:x/y.git");

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch dev", vcs.summary());
        assertEquals("Project: /w/foo\nBranch: dev\nRepository: git@github.com:x/y.git", vcs.tooltip());
    }

    @Test
    public void noProjectsWithCwdUseVcsAndCwdName() {
        FakeClient client = new FakeClient();
        client.vcs = new VcsInfo("main", null);

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch main", vcs.summary());
        assertEquals("Project: /w/foo\nBranch: main", vcs.tooltip());
    }

    // ---------- graceful degradation ----------

    @Test
    public void noProjectsNoCwdDegradesToUnknown() {
        FakeClient client = new FakeClient();

        ProjectVcs vcs = ProjectVcs.load(client, null);

        assertEquals("", vcs.summary());
        assertEquals("", vcs.tooltip());
    }

    @Test
    public void failingVcsEndpointKeepsProjectBranch() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.throwOnVcs = true;

        assertEquals("foo · branch main", ProjectVcs.load(client, "/w/foo").summary());
    }

    @Test
    public void failingProjectEndpointDegradesGracefully() {
        FakeClient client = new FakeClient();
        client.throwOnProjects = true;

        assertEquals("", ProjectVcs.load(client, null).summary());      // no cwd: nothing derivable
        assertEquals("Project: /w/foo", ProjectVcs.load(client, "/w/foo").tooltip());   // cwd keeps the path
    }

    @Test
    public void projectWithoutAnyVcsRendersEmptySummary() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", null, null));

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("", vcs.summary());
        assertEquals("Project: /w/foo", vcs.tooltip());
    }

    @Test
    public void unknownInstanceRendersEmpty() {
        assertEquals("", ProjectVcs.UNKNOWN.summary());
        assertEquals("", ProjectVcs.UNKNOWN.tooltip());
    }

    // ---------- dirty via GET /file/status ----------

    @Test
    public void nonEmptyFileStatusRendersDirty() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.status = List.of(new FileStatus("a.txt", "modified", 1, 1));

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch main · dirty", vcs.summary());
    }

    @Test
    public void emptyFileStatusStaysCleanWithoutChangesLine() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.status = List.of();

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch main", vcs.summary());
        assertEquals("Project: /w/foo\nBranch: main", vcs.tooltip());
    }

    @Test
    public void nullFileStatusListStaysClean() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.status = null;

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch main", vcs.summary());
    }

    @Test
    public void failingStatusEndpointKeepsProjectBranchAndNoDirty() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.throwOnStatus = true;

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("foo · branch main", vcs.summary());
        assertEquals("Project: /w/foo\nBranch: main", vcs.tooltip());
    }

    @Test
    public void tooltipCountsChangedFilesFromStatusSize() {
        FakeClient client = new FakeClient();
        client.projects = List.of(project("/w/foo", "main", null));
        client.status = List.of(
                new FileStatus("a.txt", "modified", 1, 1),
                new FileStatus("b.txt", "added", 2, 0),
                new FileStatus("c.txt", "deleted", 0, 3));

        ProjectVcs vcs = ProjectVcs.load(client, "/w/foo");

        assertEquals("Project: /w/foo\nBranch: main\nChanges: 3", vcs.tooltip());
        assertEquals("foo · branch main · dirty", vcs.summary());
    }

    // ---------- renderings via the pure factory ----------

    @Test
    public void dirtyRendersSuffix() {
        ProjectVcs vcs = ProjectVcs.of("/w/foo", null, "main", null, true, -1);

        assertEquals("foo · branch main · dirty", vcs.summary());
    }

    @Test
    public void repositoryOnlyRendersRepository() {
        ProjectVcs vcs = ProjectVcs.of("/w/foo", null, null, "git@github.com:x/y.git", false, -1);

        assertEquals("foo · git@github.com:x/y.git", vcs.summary());
    }

    @Test
    public void tooltipListsWorktreeAndChangesWhenKnown() {
        ProjectVcs vcs = ProjectVcs.of("/w/foo", "/w/root", "main", "git@github.com:x/y.git", true, 3);

        assertEquals("Project: /w/foo\nWorktree: /w/root\nBranch: main\n"
                + "Repository: git@github.com:x/y.git\nChanges: 3", vcs.tooltip());
    }
}
