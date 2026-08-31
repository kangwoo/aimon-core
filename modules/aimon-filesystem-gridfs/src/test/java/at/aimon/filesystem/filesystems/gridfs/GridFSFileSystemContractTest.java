package at.aimon.filesystem.filesystems.gridfs;

import org.junit.jupiter.api.Tag;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.filesystem.testkit.AbstractVirtualFileSystemContractTest;

/**
 * {@link at.aimon.filesystem.core.gridfs.GridFSFileSystem} against the shared
 * {@link AbstractVirtualFileSystemContractTest}.
 *
 * <p>
 * The reason this class exists is the reason the contract test exists. GridFS stores flat filenames with no notion of a
 * directory, so every directory case here — an empty directory that survives, a subdirectory appearing in
 * {@code list}, a listing that refuses a path naming nothing — is behaviour the backend has to synthesize and could
 * silently stop synthesizing. Its own tests would keep passing; this one would not.
 */
@Tag("docker")
class GridFSFileSystemContractTest extends AbstractVirtualFileSystemContractTest {

    @Override
    protected VirtualFileSystem newFileSystem() {
        // One container is shared by every GridFS test class, so "fresh filesystem" means an emptied bucket.
        GridFSTestSupport.cleanDatabase();
        return GridFSTestSupport.createAndInitialize();
    }

    @Override
    protected VirtualFileSystem newFileSystem(long maxFileSize) {
        // No cleanup here: the bucket was emptied moments ago by the factory above, and dropping the collections out
        // from under the filesystem that is already open on them would be cleaning the wrong thing.
        return GridFSTestSupport.createAndInitialize(maxFileSize);
    }
}
