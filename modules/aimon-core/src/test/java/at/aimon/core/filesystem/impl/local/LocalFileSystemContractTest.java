package at.aimon.core.filesystem.impl.local;

import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.filesystem.testkit.AbstractVirtualFileSystemContractTest;

/**
 * {@link LocalFileSystem} against the shared {@link AbstractVirtualFileSystemContractTest}.
 *
 * <p>
 * This backend is the reference the contract was written from, so a failure here means the contract itself moved, not
 * that a backend drifted. Each case gets its own {@code @TempDir}, which JUnit injects before any {@code @BeforeEach}
 * runs — so the factory below has a base directory by the time the superclass calls it.
 */
class LocalFileSystemContractTest extends AbstractVirtualFileSystemContractTest {

    @TempDir
    private Path baseDir;

    @Override
    protected VirtualFileSystem newFileSystem() {
        return initialized(LocalFileSystemConfig.builder(baseDir.toString()).build());
    }

    @Override
    protected VirtualFileSystem newFileSystem(long maxFileSize) {
        return initialized(LocalFileSystemConfig.builder(baseDir.toString()).maxFileSize(maxFileSize).build());
    }

    private static LocalFileSystem initialized(LocalFileSystemConfig config) {
        final LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        return fs;
    }
}
