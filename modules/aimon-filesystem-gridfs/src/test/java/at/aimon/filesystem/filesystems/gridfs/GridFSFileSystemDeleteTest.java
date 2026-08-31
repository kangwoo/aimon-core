package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemDeleteTest {

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
    void testDeleteSimpleFile() {
        fs.write("test.txt", new ByteArrayInputStream("content".getBytes()), 7);
        assertThat(fs.exists("test.txt")).isTrue();

        fs.delete("test.txt");

        assertThat(fs.exists("test.txt")).isFalse();
    }

    @Test
    void testDeleteNestedFile() {
        fs.write("a/b/deep.txt", new ByteArrayInputStream("deep".getBytes()), 4);

        fs.delete("a/b/deep.txt");

        assertThat(fs.exists("a/b/deep.txt")).isFalse();
    }

    @Test
    void testDeleteNonExistentFile() {
        assertThatThrownBy(() -> fs.delete("nonexistent.txt")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testDeleteRejectsNullPath() {
        assertThatThrownBy(() -> fs.delete(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testDeleteFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.delete("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testDeleteFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.delete("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
