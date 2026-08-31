package at.aimon.core.filesystem.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.filesystem.testkit.AbstractVirtualFileSystemContractTest;

/**
 * {@link ScopedVirtualFileSystem} against the shared {@link AbstractVirtualFileSystemContractTest}.
 *
 * <p>
 * The decorator is a {@code VirtualFileSystem} to everyone above it, so it owes callers the same contract as the
 * backend it wraps — a caller handed a branch cannot tell it is not looking at a whole filesystem. That is exactly what
 * this run checks: every case is written against the unscoped paths a caller uses, and passes only if the prefix is
 * added and stripped consistently on both the request and the answer.
 */
class ScopedVirtualFileSystemContractTest extends AbstractVirtualFileSystemContractTest {

    private static final String BRANCH = "branch/work";

    @TempDir
    private Path baseDir;

    private LocalFileSystem delegate;

    private LocalFileSystem cappedDelegate;

    @Override
    protected VirtualFileSystem newFileSystem() {
        delegate = branchedDelegate(LocalFileSystemConfig.builder(baseDir.toString()).build());
        return new ScopedVirtualFileSystem(delegate, BRANCH);
    }

    @Override
    protected VirtualFileSystem newFileSystem(long maxFileSize) {
        // The cap belongs to the delegate; the decorator neither knows nor needs to know about it. Running the size
        // cases through here is what proves that — a scoped caller has to hit the same limit as an unscoped one.
        cappedDelegate = branchedDelegate(
                LocalFileSystemConfig.builder(baseDir.toString()).maxFileSize(maxFileSize).build());
        return new ScopedVirtualFileSystem(cappedDelegate, BRANCH);
    }

    private static LocalFileSystem branchedDelegate(LocalFileSystemConfig config) {
        final LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        // The branch root is the scoped filesystem's own root, and a root has to exist before anyone can list it.
        // Creating it is the wrapping assembly's job, not the decorator's — it borrows its delegate rather than
        // provisioning it.
        fs.createDirectory(BRANCH);
        return fs;
    }

    @AfterEach
    void closeDelegate() {
        // ScopedVirtualFileSystem.close() is deliberately a no-op — it borrows the delegate — so every delegate this
        // test created is this test's to close, including the capped one the size cases asked for.
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
        if (cappedDelegate != null) {
            cappedDelegate.close();
            cappedDelegate = null;
        }
    }

    @Test
    @DisplayName("everything the branch writes lands under the prefix, and nothing outside it is visible")
    void branchIsIsolatedFromTheRestOfTheDelegate() {
        final byte[] outside = "outside".getBytes(StandardCharsets.UTF_8);
        delegate.write("elsewhere.txt", new ByteArrayInputStream(outside), outside.length);

        write("inside.txt", "inside");

        assertThat(fs().list(ROOT)).containsExactly("inside.txt");
        assertThat(fs().exists("elsewhere.txt")).isFalse();
        assertThat(delegate.exists(BRANCH + "/inside.txt")).isTrue();
        assertThat(fs().getUsageSummary().getFileCount()).isEqualTo(1);
    }
}
