package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSConfig;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemIntegrationTest {

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
    void testFullWriteReadDeleteCycle() throws Exception {
        String content = "Integration test content";

        // Write
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());
        assertThat(fs.exists("test.txt")).isTrue();

        // Read
        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }

        // Delete
        fs.delete("test.txt");
        assertThat(fs.exists("test.txt")).isFalse();

        // Verify read after delete throws
        assertThatThrownBy(() -> fs.read("test.txt")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testMultipleFilesInDifferentDirectories() {
        fs.write("root.txt", new ByteArrayInputStream("r".getBytes()), 1);
        fs.write("docs/readme.md", new ByteArrayInputStream("d".getBytes()), 1);
        fs.write("src/main/App.java", new ByteArrayInputStream("s".getBytes()), 1);

        assertThat(fs.exists("root.txt")).isTrue();
        assertThat(fs.exists("docs/readme.md")).isTrue();
        assertThat(fs.exists("src/main/App.java")).isTrue();

        assertThat(fs.listRecursive(".")).hasSize(3);
    }

    @Test
    void testOverwriteAndReadCycle() throws Exception {
        fs.write("test.txt", new ByteArrayInputStream("version1".getBytes()), 8);

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("version1");
        }

        fs.write("test.txt", new ByteArrayInputStream("version2".getBytes()), 8);

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("version2");
        }
    }

    @Test
    void testMultipleInitializeCallsAreIdempotent() {
        GridFSConfig config = GridFSTestSupport.createConfig();
        GridFSFileSystem testFs = new GridFSFileSystem(config);

        testFs.initialize();
        testFs.initialize();
        testFs.initialize();

        assertThat(testFs.getStatus().isAvailable()).isTrue();

        testFs.close();
    }

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        GridFSFileSystem testFs = GridFSTestSupport.createAndInitialize();

        testFs.close();
        testFs.close();
        testFs.close();

        assertThat(testFs.getStatus().isAvailable()).isFalse();
    }

    @Test
    void testStatusBeforeInitialization() {
        GridFSConfig config = GridFSTestSupport.createConfig();
        GridFSFileSystem testFs = new GridFSFileSystem(config);

        BackendStatus status = testFs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.UNKNOWN);
        assertThat(status.getType()).isEqualTo(BackendType.GRIDFS);

        testFs.close();
    }

    @Test
    void testStatusAfterInitialization() {
        BackendStatus status = fs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.CONNECTED);
        assertThat(status.getType()).isEqualTo(BackendType.GRIDFS);
        assertThat(status.isAvailable()).isTrue();
    }

    @Test
    void testGetWorkingDirectory() {
        String wd = fs.getWorkingDirectory();

        assertThat(wd).startsWith("gridfs://");
        assertThat(wd).contains(GridFSTestSupport.DATABASE_NAME);
        assertThat(wd).isEqualTo("gridfs://" + GridFSTestSupport.DATABASE_NAME + "/fs");
    }

    @Test
    void testStatusAfterClose() {
        GridFSFileSystem testFs = GridFSTestSupport.createAndInitialize();
        testFs.close();

        BackendStatus status = testFs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.DISCONNECTED);
        assertThat(status.isAvailable()).isFalse();
    }
}
