package at.aimon.core.agent.impl.orca.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("WorktreeMerge — promote branch subtrees to canonical (design §6.3)")
class WorktreeMergeTest {

    private LocalFileSystem vfs;

    private void setUp(Path tempDir) {
        vfs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        vfs.initialize();
    }

    @Test
    @DisplayName("promotes disjoint branch files to canonical paths and deletes the branch sources")
    void promotesDisjointBranches(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/a/file1.txt", "one");
        vfs.write(".worktrees/a/dir/file2.txt", "two");
        vfs.write(".worktrees/b/file3.txt", "three");

        final MergeReport report = WorktreeMerge.promote(vfs, List.of("a", "b"), WorktreeMerge.Policy.FIRST_WINS);

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
    void conflictUnderFailPolicy(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/a/same.txt", "from-a");
        vfs.write(".worktrees/b/same.txt", "from-b");

        final MergeReport report = WorktreeMerge.promote(vfs, List.of("a", "b"), WorktreeMerge.Policy.FAIL);

        assertThat(report.hasConflicts()).isTrue();
        assertThat(report.conflicts()).containsExactly("same.txt");
        assertThat(report.promoted()).isEmpty();
        assertThat(vfs.exists("same.txt")).isFalse(); // nothing promoted
    }

    @Test
    @DisplayName("LAST_WINS resolves a collision to the last branch")
    void conflictLastWins(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/a/same.txt", "from-a");
        vfs.write(".worktrees/b/same.txt", "from-b");

        final MergeReport report = WorktreeMerge.promote(vfs, List.of("a", "b"), WorktreeMerge.Policy.LAST_WINS);

        assertThat(report.conflicts()).containsExactly("same.txt");
        assertThat(report.promoted()).containsExactly("same.txt");
        assertThat(readAll("same.txt")).isEqualTo("from-b");
    }

    // --- branch-key shape validation (defensive re-check before any filesystem access) -------------------------

    @Test
    @DisplayName("rejects malformed branch keys with IllegalArgumentException before touching the filesystem")
    void rejectsMalformedBranchKeys(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write("canonical.txt", "untouched");
        vfs.write(".worktrees/ok/file1.txt", "one");

        // Malformed key comes AFTER a valid one: validation is up-front, so not even the valid branch may promote.
        for (final String bad : List.of("../docs", "", "a/b", "a.b")) {
            assertThatThrownBy(() -> WorktreeMerge.promote(vfs, List.of("ok", bad), WorktreeMerge.Policy.FIRST_WINS))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[A-Za-z0-9_]+")
                    .hasMessageContaining("got: " + bad);
        }

        // Canonical tree completely untouched: nothing promoted, branch source intact, existing file unchanged.
        assertThat(vfs.exists("file1.txt")).isFalse();
        assertThat(vfs.exists(".worktrees/ok/file1.txt")).isTrue();
        assertThat(readAll("canonical.txt")).isEqualTo("untouched");
    }

    @Test
    @DisplayName("rejects a null branch key element with IllegalArgumentException")
    void rejectsNullBranchKeyElement(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/ok/file1.txt", "one");

        assertThatThrownBy(() -> WorktreeMerge.promote(vfs, Arrays.asList("ok", null), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[A-Za-z0-9_]+");

        assertThat(vfs.exists("file1.txt")).isFalse();
        assertThat(vfs.exists(".worktrees/ok/file1.txt")).isTrue();
    }

    @Test
    @DisplayName("a framework-shaped branch key (letters, digits, underscores) still promotes")
    void frameworkShapedKeyPromotes(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/p0_0_a0/out.txt", "payload");

        final MergeReport report = WorktreeMerge.promote(vfs, List.of("p0_0_a0"), WorktreeMerge.Policy.FIRST_WINS);

        assertThat(report.hasConflicts()).isFalse();
        assertThat(report.promoted()).containsExactly("out.txt");
        assertThat(readAll("out.txt")).isEqualTo("payload");
        assertThat(vfs.exists(".worktrees/p0_0_a0/out.txt")).isFalse();
    }

    // --- mid-merge failure semantics (fail-fast, no rollback, partial progress observable) ----------------------

    @Test
    @DisplayName("mid-merge failure aborts fail-fast, keeps promoted files in place, and reports partial progress")
    void midMergeFailureReportsPartialProgress(@TempDir Path tempDir) {
        setUp(tempDir);
        vfs.write(".worktrees/a/first.txt", "one");
        vfs.write(".worktrees/b/second.txt", "two");
        // Branch 'a' promotes cleanly; the copy of branch 'b''s file simulates a backend failure.
        final VirtualFileSystem failing = new FailingCopyFileSystem(vfs, ".worktrees/b/");

        assertThatThrownBy(() -> WorktreeMerge.promote(failing, List.of("a", "b"), WorktreeMerge.Policy.FIRST_WINS))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("second.txt")
                .hasMessageContaining("branch 'b'").hasMessageContaining("after 1 promoted file(s)")
                .hasMessageContaining("0 conflict(s)").hasMessageContaining("no rollback")
                .hasRootCauseMessage("simulated backend failure: .worktrees/b/second.txt");

        // The first file was fully promoted (copied + branch source deleted) before the abort — no rollback.
        assertThat(vfs.exists("first.txt")).isTrue();
        assertThat(readAll("first.txt")).isEqualTo("one");
        assertThat(vfs.exists(".worktrees/a/first.txt")).isFalse();
        // The failed file is untouched, so re-running the merge after the failure is resolved is safe.
        assertThat(vfs.exists("second.txt")).isFalse();
        assertThat(vfs.exists(".worktrees/b/second.txt")).isTrue();
    }

    private String readAll(String path) {
        try (var in = vfs.read(path)) {
            return new String(in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Forwarding {@link VirtualFileSystem} decorator that simulates a mid-merge backend failure: {@link #copy}
     * throws {@link VirtualFileSystemException} for any source path under the given prefix; every other operation
     * delegates unchanged. Lets a test drive the fail-fast abort on the second file of a promotion.
     */
    private static final class FailingCopyFileSystem implements VirtualFileSystem {

        private final VirtualFileSystem delegate;
        private final String failingSourcePrefix;

        FailingCopyFileSystem(VirtualFileSystem delegate, String failingSourcePrefix) {
            this.delegate = delegate;
            this.failingSourcePrefix = failingSourcePrefix;
        }

        @Override
        public void copy(String sourcePath, String destinationPath, boolean overwrite) {
            if (sourcePath.startsWith(failingSourcePrefix)) {
                throw new VirtualFileSystemException("simulated backend failure: " + sourcePath);
            }
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
