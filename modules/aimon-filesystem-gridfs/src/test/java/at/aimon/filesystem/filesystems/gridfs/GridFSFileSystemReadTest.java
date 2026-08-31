package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemReadTest {

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
    void testReadSimpleFile() throws Exception {
        String content = "read me";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testReadNestedFile() throws Exception {
        String content = "nested read";
        fs.write("a/b/c.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        try (InputStream in = fs.read("a/b/c.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testReadLargeFile() throws Exception {
        byte[] largeContent = new byte[2 * 1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }
        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        try (InputStream in = fs.read("large.bin")) {
            assertThat(in.readAllBytes()).isEqualTo(largeContent);
        }
    }

    @Test
    void testReadNonExistentFile() {
        assertThatThrownBy(() -> fs.read("nonexistent.txt")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testReadRejectsNullPath() {
        assertThatThrownBy(() -> fs.read(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testReadFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.read("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testReadFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.read("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
