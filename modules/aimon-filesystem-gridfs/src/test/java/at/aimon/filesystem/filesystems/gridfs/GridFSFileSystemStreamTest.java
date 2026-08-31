package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemStreamTest {

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

    @Nested
    class OpenOutputStreamTests {

        @Test
        void testWriteAndReadBack() throws Exception {
            String content = "stream output content";

            try (OutputStream out = fs.openOutputStream("stream-test.txt")) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }

            try (InputStream in = fs.read("stream-test.txt")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(content);
            }
        }

        @Test
        void testOverwriteExisting() throws Exception {
            fs.write("stream-test.txt", new ByteArrayInputStream("initial".getBytes()), 7);

            String updated = "updated via stream";
            try (OutputStream out = fs.openOutputStream("stream-test.txt")) {
                out.write(updated.getBytes(StandardCharsets.UTF_8));
            }

            try (InputStream in = fs.read("stream-test.txt")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(updated);
            }
        }

        @Test
        void testRejectsNullPath() {
            assertThatThrownBy(() -> fs.openOutputStream(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Path cannot be null");
        }

        @Test
        void testFailsWhenNotInitialized() {
            GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

            assertThatThrownBy(() -> uninitFs.openOutputStream("test.txt")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not initialized");

            uninitFs.close();
        }

        @Test
        void testFailsWhenClosed() {
            GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
            closedFs.close();

            assertThatThrownBy(() -> closedFs.openOutputStream("test.txt")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }

    @Nested
    class OpenInputStreamTests {

        @Test
        void testReadCorrectData() throws Exception {
            String content = "input stream content";
            fs.write("input-test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

            try (InputStream in = fs.openInputStream("input-test.txt")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(content);
            }
        }

        @Test
        void testNonExistentFile() {
            assertThatThrownBy(() -> fs.openInputStream("nonexistent.txt")).isInstanceOf(FileNotFoundException.class);
        }

        @Test
        void testRejectsNullPath() {
            assertThatThrownBy(() -> fs.openInputStream(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Path cannot be null");
        }

        @Test
        void testFailsWhenNotInitialized() {
            GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

            assertThatThrownBy(() -> uninitFs.openInputStream("test.txt")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not initialized");

            uninitFs.close();
        }

        @Test
        void testFailsWhenClosed() {
            GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
            closedFs.close();

            assertThatThrownBy(() -> closedFs.openInputStream("test.txt")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }
}
