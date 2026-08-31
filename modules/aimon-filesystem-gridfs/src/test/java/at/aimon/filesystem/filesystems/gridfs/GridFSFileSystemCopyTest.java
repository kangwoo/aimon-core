package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemCopyTest {

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
    void testCopyCreatesExactDuplicate() throws Exception {
        String content = "copy me";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.copy("source.txt", "dest.txt", false);

        assertThat(fs.exists("source.txt")).isTrue();
        assertThat(fs.exists("dest.txt")).isTrue();

        try (InputStream in = fs.read("dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testCopyToNestedDirectory() throws Exception {
        String content = "nested copy";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.copy("source.txt", "a/b/dest.txt", false);

        try (InputStream in = fs.read("a/b/dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testCopyOverwriteFalseThrows() {
        fs.write("source.txt", new ByteArrayInputStream("src".getBytes()), 3);
        fs.write("dest.txt", new ByteArrayInputStream("dst".getBytes()), 3);

        assertThatThrownBy(() -> fs.copy("source.txt", "dest.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void testCopyOverwriteTrueReplaces() throws Exception {
        fs.write("source.txt", new ByteArrayInputStream("new content".getBytes()), 11);
        fs.write("dest.txt", new ByteArrayInputStream("old".getBytes()), 3);

        fs.copy("source.txt", "dest.txt", true);

        try (InputStream in = fs.read("dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("new content");
        }
    }

    @Test
    void testCopyLargeFile() {
        byte[] largeContent = new byte[2 * 1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }
        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        fs.copy("large.bin", "large-copy.bin", false);

        assertThat(fs.getMetadata("large-copy.bin").getSize()).isEqualTo(largeContent.length);
    }

    @Test
    void testCopyNonExistentSource() {
        assertThatThrownBy(() -> fs.copy("nonexistent.txt", "dest.txt", false))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testCopyRejectsNullSource() {
        assertThatThrownBy(() -> fs.copy(null, "dest.txt", false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Source path cannot be null");
    }

    @Test
    void testCopyRejectsNullDestination() {
        assertThatThrownBy(() -> fs.copy("source.txt", null, false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Destination path cannot be null");
    }
}
