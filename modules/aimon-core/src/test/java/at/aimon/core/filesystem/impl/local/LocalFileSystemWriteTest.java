package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemWriteTest {

    @TempDir
    Path tempDir;

    /** Creates and initializes a LocalFileSystem with default configuration. */
    private LocalFileSystem createAndInitialize() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        return fs;
    }

    @Test
    void testWriteSimpleFile() throws IOException {
        LocalFileSystem fs = createAndInitialize();

        String content = "Hello, World!";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());

        fs.write("test.txt", inputStream, content.length());

        Path writtenFile = tempDir.resolve("test.txt");
        assertThat(writtenFile).exists();
        assertThat(Files.readString(writtenFile)).isEqualTo(content);

        fs.close();
    }

    @Test
    void testWriteToNestedDirectory() throws IOException {
        LocalFileSystem fs = createAndInitialize();

        String content = "Nested content";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());

        fs.write("dir1/dir2/nested.txt", inputStream, content.length());

        Path writtenFile = tempDir.resolve("dir1/dir2/nested.txt");
        assertThat(writtenFile).exists();
        assertThat(Files.readString(writtenFile)).isEqualTo(content);

        fs.close();
    }

    @Test
    void testWriteOverwritesExistingFile() throws IOException {
        LocalFileSystem fs = createAndInitialize();

        // Write initial content
        String initialContent = "Initial";
        fs.write("test.txt", new ByteArrayInputStream(initialContent.getBytes()), initialContent.length());

        // Overwrite with new content
        String newContent = "Updated content";
        fs.write("test.txt", new ByteArrayInputStream(newContent.getBytes()), newContent.length());

        Path writtenFile = tempDir.resolve("test.txt");
        assertThat(Files.readString(writtenFile)).isEqualTo(newContent);

        fs.close();
    }

    @Test
    void testWriteLargeFile() throws IOException {
        LocalFileSystem fs = createAndInitialize();

        // Create 1MB of data
        byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        Path writtenFile = tempDir.resolve("large.bin");
        assertThat(writtenFile).exists();
        assertThat(Files.size(writtenFile)).isEqualTo(largeContent.length);

        fs.close();
    }

    @Test
    void testWriteRejectsNullPath() {
        LocalFileSystem fs = createAndInitialize();

        assertThatThrownBy(() -> fs.write(null, new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testWriteRejectsNullContent() {
        LocalFileSystem fs = createAndInitialize();

        assertThatThrownBy(() -> fs.write("test.txt", null, 0)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");

        fs.close();
    }

    @Test
    void testWriteRejectsInvalidPath() {
        LocalFileSystem fs = createAndInitialize();

        assertThatThrownBy(() -> fs.write("../../../etc/passwd", new ByteArrayInputStream("hack".getBytes()), 4))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testWriteFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.write("test.txt", new ByteArrayInputStream("test".getBytes()), 4))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testWriteFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.write("test.txt", new ByteArrayInputStream("test".getBytes()), 4))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}
