package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemIsDirectoryTest {

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
    void testVirtualDirectoryDetection() {
        fs.write("docs/readme.md", new ByteArrayInputStream("data".getBytes()), 4);

        assertThat(fs.isDirectory("docs")).isTrue();
    }

    @Test
    void testFileReturnsFalse() {
        fs.write("test.txt", new ByteArrayInputStream("data".getBytes()), 4);

        assertThat(fs.isDirectory("test.txt")).isFalse();
    }

    @Test
    void testNonExistentReturnsFalse() {
        assertThat(fs.isDirectory("nonexistent")).isFalse();
    }

    @Test
    void testNestedDirectory() {
        fs.write("a/b/c/file.txt", new ByteArrayInputStream("data".getBytes()), 4);

        assertThat(fs.isDirectory("a")).isTrue();
        assertThat(fs.isDirectory("a/b")).isTrue();
        assertThat(fs.isDirectory("a/b/c")).isTrue();
    }

    @Test
    void testRejectsNull() {
        assertThatThrownBy(() -> fs.isDirectory(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.isDirectory("docs")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.isDirectory("docs")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
