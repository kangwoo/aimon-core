package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemCreateDirectoryTest {

    private GridFSFileSystem fs;

    @BeforeEach
    void setUp() {
        GridFSTestSupport.cleanDatabase();
        fs = GridFSTestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    void testCreateDirectoryRootIsNoOp() {
        // Root directory creation should be a no-op (no exception)
        fs.createDirectory(".");
    }

    @Test
    void testCreateDirectoryIdempotent() {
        // GridFS directories are implicit — repeated calls are no-ops
        fs.createDirectory("mydir");
        fs.createDirectory("mydir");
    }

    @Test
    void testCreateDirectoryFailsWhenFileExists() {
        fs.write("conflict", new ByteArrayInputStream("data".getBytes()), 4);

        assertThatThrownBy(() -> fs.createDirectory("conflict")).isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void testCreateDirectoryRejectsNull() {
        assertThatThrownBy(() -> fs.createDirectory(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testCreateDirectoryFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testCreateDirectoryFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
