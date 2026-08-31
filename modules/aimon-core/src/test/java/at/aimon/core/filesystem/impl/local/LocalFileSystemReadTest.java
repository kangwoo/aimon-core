package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemReadTest {

    @TempDir
    Path tempDir;

    @Test
    void testReadSimpleFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a file first
        String content = "Hello, World!";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Read it back
        try (InputStream inputStream = fs.read("test.txt")) {
            String readContent = new String(inputStream.readAllBytes());
            assertThat(readContent).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testReadNestedFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a nested file
        String content = "Nested content";
        fs.write("dir1/dir2/nested.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Read it back
        try (InputStream inputStream = fs.read("dir1/dir2/nested.txt")) {
            String readContent = new String(inputStream.readAllBytes());
            assertThat(readContent).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testReadLargeFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 1MB of data
        byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        // Read it back
        try (InputStream inputStream = fs.read("large.bin")) {
            byte[] readContent = inputStream.readAllBytes();
            assertThat(readContent).isEqualTo(largeContent);
        }

        fs.close();
    }

    @Test
    void testReadNonExistentFile() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.read("nonexistent.txt")).isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("nonexistent.txt");

        fs.close();
    }

    @Test
    void testReadDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create a directory
        Files.createDirectory(tempDir.resolve("testdir"));

        assertThatThrownBy(() -> fs.read("testdir")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Not a regular file");

        fs.close();
    }

    @Test
    void testReadRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.read(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testReadRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.read("../../../etc/passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testReadFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.read("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testReadFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.read("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
