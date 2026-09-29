package at.aimon.core.agent.impl.orca.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.shell.VirtualShell;

@DisplayName("WorktreeMerge — promote branch environments to their parent (design §6.3)")
class WorktreeMergeTest {

    @TempDir
    Path tempDir;

    private LocalExecutionEnvironmentProvider provider;
    private ExecutionEnvironment parent;

    private void setUp() {
        provider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(tempDir).contentSearch(false).build();
        parent = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("merge")).build());
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private ExecutionEnvironment branch(String key) {
        return parent.isolate(key).orElseThrow();
    }

    @Test
    @DisplayName("promotes disjoint branch files to canonical paths and deletes the branch sources")
    void promotesDisjointBranches() {
        setUp();
        branch("a").fileSystem().write("file1.txt", "one");
        branch("a").fileSystem().write("dir/file2.txt", "two");
        branch("b").fileSystem().write("file3.txt", "three");

        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch("a"), branch("b")),
                WorktreeMerge.Policy.FIRST_WINS);

        final VirtualFileSystem vfs = parent.fileSystem();
        assertThat(report.hasConflicts()).isFalse();
        assertThat(report.promoted()).containsExactlyInAnyOrder("file1.txt", "dir/file2.txt", "file3.txt");
        assertThat(vfs.exists("file1.txt")).isTrue();
        assertThat(vfs.exists("dir/file2.txt")).isTrue();
        assertThat(vfs.exists("file3.txt")).isTrue();
        // Branch sources removed after promotion.
        assertThat(vfs.exists(".worktrees/a/file1.txt")).isFalse();
        assertThat(vfs.exists(".worktrees/b/file3.txt")).isFalse();
    }

    @Test
    @DisplayName("FAIL policy promotes nothing and reports the cross-branch collision")
    void conflictUnderFailPolicy() {
        setUp();
        branch("a").fileSystem().write("same.txt", "from-a");
        branch("b").fileSystem().write("same.txt", "from-b");

        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch("a"), branch("b")),
                WorktreeMerge.Policy.FAIL);

        assertThat(report.hasConflicts()).isTrue();
        assertThat(report.conflicts()).containsExactly("same.txt");
        assertThat(report.promoted()).isEmpty();
        assertThat(parent.fileSystem().exists("same.txt")).isFalse(); // nothing promoted
    }

    @Test
    @DisplayName("FIRST_WINS resolves a collision to the first branch in the list")
    void conflictFirstWins() {
        setUp();
        branch("a").fileSystem().write("same.txt", "from-a");
        branch("b").fileSystem().write("same.txt", "from-b");

        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch("a"), branch("b")),
                WorktreeMerge.Policy.FIRST_WINS);

        assertThat(report.conflicts()).containsExactly("same.txt");
        assertThat(report.promoted()).containsExactly("same.txt");
        assertThat(readAll("same.txt")).isEqualTo("from-a");
    }

    @Test
    @DisplayName("LAST_WINS resolves a collision to the last branch")
    void conflictLastWins() {
        setUp();
        branch("a").fileSystem().write("same.txt", "from-a");
        branch("b").fileSystem().write("same.txt", "from-b");

        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch("a"), branch("b")),
                WorktreeMerge.Policy.LAST_WINS);

        assertThat(report.conflicts()).containsExactly("same.txt");
        assertThat(report.promoted()).containsExactly("same.txt");
        assertThat(readAll("same.txt")).isEqualTo("from-b");
    }

    @Test
    @DisplayName("an assembler that only knows branch keys rebuilds the same branches with isolate(key)")
    void branchesRebuiltFromKeysPromote() {
        setUp();
        branch("p0_0_a0").fileSystem().write("out.txt", "payload");

        // A fresh isolate() of the same key is the same view the run used: isolation is deterministic on the key.
        final MergeReport report = WorktreeMerge.promote(parent, List.of(parent.isolate("p0_0_a0").orElseThrow()),
                WorktreeMerge.Policy.FIRST_WINS);

        assertThat(report.hasConflicts()).isFalse();
        assertThat(report.promoted()).containsExactly("out.txt");
        assertThat(readAll("out.txt")).isEqualTo("payload");
        assertThat(parent.fileSystem().exists(".worktrees/p0_0_a0/out.txt")).isFalse();
    }

    @Test
    @DisplayName("the branch-key shape check now lives in isolate(): malformed keys are rejected there")
    void rejectsMalformedBranchKeys() {
        setUp();
        for (final String bad : List.of("../docs", "", "a/b", "a.b")) {
            assertThatThrownBy(() -> parent.isolate(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[A-Za-z0-9_]+").hasMessageContaining("got: " + bad);
        }
        assertThatThrownBy(() -> parent.isolate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- mid-merge failure semantics (fail-fast, no rollback, partial progress observable) ----------------------

    @Test
    @DisplayName("mid-merge failure aborts fail-fast, keeps promoted files in place, and reports partial progress")
    void midMergeFailureReportsPartialProgress() {
        setUp();
        branch("a").fileSystem().write("first.txt", "one");
        branch("b").fileSystem().write("second.txt", "two");
        // Branch 'a' promotes cleanly; reading branch 'b''s file simulates a backend failure. The fault sits on the
        // branch side: a parent wrapped in a fake would (rightly) fail the ownership check instead.
        final ExecutionEnvironment failing = new ForeignEnvironment(branch("b"),
                new FailingFileSystem(branch("b").fileSystem(), "second.txt"));

        assertThatThrownBy(
                () -> WorktreeMerge.promote(parent, List.of(branch("a"), failing), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("second.txt")
                .hasMessageContaining("branch #1").hasMessageContaining("after 1 promoted file(s)")
                .hasMessageContaining("0 conflict(s)").hasMessageContaining("no rollback")
                .hasRootCauseMessage("simulated backend failure: second.txt");

        final VirtualFileSystem vfs = parent.fileSystem();
        // The first file was fully promoted (copied + branch source deleted) before the abort — no rollback.
        assertThat(readAll("first.txt")).isEqualTo("one");
        assertThat(vfs.exists(".worktrees/a/first.txt")).isFalse();
        // The failed file is untouched, so re-running the merge after the failure is resolved is safe.
        assertThat(vfs.exists("second.txt")).isFalse();
        assertThat(vfs.exists(".worktrees/b/second.txt")).isTrue();
    }

    @Test
    @DisplayName("a branch file that cannot be read as a file (a shell-made symlink) aborts before anything is promoted")
    @DisabledOnOs(OS.WINDOWS)
    void unreadableBranchFileAbortsBeforePromoting() throws IOException {
        setUp();
        branch("a").fileSystem().write("a.txt", "one");
        Files.writeString(tempDir.resolve("target.txt"), "outside the branch");
        Files.createSymbolicLink(tempDir.resolve(".worktrees/a/link.txt"), tempDir.resolve("target.txt"));

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(branch("a")), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("before promoting anything")
                .hasMessageContaining("link.txt").hasMessageContaining("branch #0");

        assertThat(parent.fileSystem().exists("a.txt")).isFalse();
        assertThat(parent.fileSystem().exists(".worktrees/a/a.txt")).isTrue();
    }

    @Test
    @DisplayName("a destination the parent's path rules make read-only aborts before anything is promoted")
    void readOnlyDestinationAbortsBeforePromoting() throws IOException {
        provider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(tempDir).pathRules(
                List.of(PathRule.deny(".aimon"), PathRule.readOnly(".aimon-staged"), PathRule.readOnly("vendor")))
                .contentSearch(false).build();
        parent = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("merge")).build());
        branch("a").fileSystem().write("a.txt", "one");
        // The branch's file tools cannot write vendor/ (the rule is re-anchored at the branch root); a shell can.
        Files.createDirectories(tempDir.resolve(".worktrees/a/vendor"));
        Files.writeString(tempDir.resolve(".worktrees/a/vendor/lib.txt"), "shell-written");
        branch("a").fileSystem().write("z.txt", "two");

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(branch("a")), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("before promoting anything")
                .hasMessageContaining("vendor/lib.txt").hasMessageContaining("READ_ONLY");

        assertThat(parent.fileSystem().exists("a.txt")).isFalse();
        assertThat(parent.fileSystem().exists("z.txt")).isFalse();
        assertThat(parent.fileSystem().exists(".worktrees/a/a.txt")).isTrue();
        assertThat(tempDir.resolve("vendor")).doesNotExist();
    }

    // --- ownership: every branch must be a distinct branch of this parent (EE-28) --------------------------------

    @Test
    @DisplayName("the parent itself is refused as a branch, before any file is read or written")
    void parentAsItsOwnBranchIsRefused() {
        setUp();
        parent.fileSystem().write("keep.txt", "canonical");

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(parent), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("branches[0]")
                .hasMessageContaining("parent environment itself");

        assertThat(readAll("keep.txt")).isEqualTo("canonical");
    }

    @Test
    @DisplayName("a branch that shares the parent's filesystem is refused (it would copy onto itself, then delete)")
    void branchSharingTheParentFileSystemIsRefused() {
        setUp();
        parent.fileSystem().write("keep.txt", "canonical");
        final ExecutionEnvironment impostor = new ForeignEnvironment(parent, parent.fileSystem());

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(impostor), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shares the parent's filesystem");

        assertThat(readAll("keep.txt")).isEqualTo("canonical");
    }

    @Test
    @DisplayName("a branch isolated from another environment is refused")
    void foreignBranchIsRefused(@TempDir Path otherRoot) {
        setUp();
        try (LocalExecutionEnvironmentProvider other = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(otherRoot).contentSearch(false).build()) {
            final ExecutionEnvironment foreign = other
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("other")).build())
                    .isolate("k").orElseThrow();
            foreign.fileSystem().write("x.txt", "foreign");

            assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(foreign), WorktreeMerge.Policy.FIRST_WINS))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("was isolated from");

            assertThat(parent.fileSystem().exists("x.txt")).isFalse();
            assertThat(foreign.fileSystem().exists("x.txt")).isTrue();
        }
    }

    @Test
    @DisplayName("the same branch listed twice is refused (it would report false conflicts)")
    void duplicateBranchIsRefused() {
        setUp();
        final ExecutionEnvironment a = branch("a");
        a.fileSystem().write("x.txt", "one");

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(a, a), WorktreeMerge.Policy.FAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branches[0] and branches[1] are the same environment");

        assertThat(a.fileSystem().exists("x.txt")).isTrue();
    }

    @Test
    @DisplayName("a branch that declares no lineage but has its own filesystem is still promoted")
    void branchWithoutLineageIsAccepted() {
        setUp();
        branch("a").fileSystem().write("x.txt", "one");
        final ExecutionEnvironment undeclared = new ForeignEnvironment(branch("a"), branch("a").fileSystem());

        final MergeReport report = WorktreeMerge.promote(parent, List.of(undeclared), WorktreeMerge.Policy.FAIL);

        assertThat(report.promoted()).containsExactly("x.txt");
        assertThat(readAll("x.txt")).isEqualTo("one");
    }

    private String readAll(String path) {
        try (var in = parent.fileSystem().read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An environment that declares no lineage (as a third-party branch may not) and uses the given filesystem —
     * everything else is the delegate's.
     */
    private static final class ForeignEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final VirtualFileSystem fileSystem;

        ForeignEnvironment(ExecutionEnvironment delegate, VirtualFileSystem fileSystem) {
            this.delegate = delegate;
            this.fileSystem = fileSystem;
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return fileSystem;
        }

        @Override
        public VirtualShell shell() {
            return delegate.shell();
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }
    }

    /**
     * Forwarding {@link VirtualFileSystem} decorator that simulates a mid-merge backend failure:
     * {@link #openInputStream} throws {@link VirtualFileSystemException} for the given path; every other operation
     * delegates unchanged, so the pre-flight (metadata only) passes. Lets a test drive the fail-fast abort on the
     * second file of a promotion.
     */
    private static final class FailingFileSystem implements VirtualFileSystem {

        private final VirtualFileSystem delegate;
        private final String failingPath;

        FailingFileSystem(VirtualFileSystem delegate, String failingPath) {
            this.delegate = delegate;
            this.failingPath = failingPath;
        }

        @Override
        public void copy(String sourcePath, String destinationPath, boolean overwrite) {
            delegate.copy(sourcePath, destinationPath, overwrite);
        }

        @Override
        public void write(String path, InputStream content, long contentLength) {
            delegate.write(path, content, contentLength);
        }

        @Override
        public InputStream read(String path) {
            return delegate.read(path);
        }

        @Override
        public void delete(String path) {
            delegate.delete(path);
        }

        @Override
        public boolean exists(String path) {
            return delegate.exists(path);
        }

        @Override
        public boolean isDirectory(String path) {
            return delegate.isDirectory(path);
        }

        @Override
        public FileMetadata getMetadata(String path) {
            return delegate.getMetadata(path);
        }

        @Override
        public List<String> list(String directory) {
            return delegate.list(directory);
        }

        @Override
        public List<String> listRecursive(String directory) {
            return delegate.listRecursive(directory);
        }

        @Override
        public void move(String sourcePath, String destinationPath, boolean overwrite) {
            delegate.move(sourcePath, destinationPath, overwrite);
        }

        @Override
        public OutputStream openOutputStream(String path) {
            return delegate.openOutputStream(path);
        }

        @Override
        public InputStream openInputStream(String path) {
            if (path.equals(failingPath)) {
                throw new VirtualFileSystemException("simulated backend failure: " + path);
            }
            return delegate.openInputStream(path);
        }

        @Override
        public String getWorkingDirectory() {
            return delegate.getWorkingDirectory();
        }

        @Override
        public void initialize() {
            delegate.initialize();
        }

        @Override
        public BackendStatus getStatus() {
            return delegate.getStatus();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
