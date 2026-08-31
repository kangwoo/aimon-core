package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemDeleteTest {

    @TempDir
    Path tempDir;

    @Test
    void testDeleteSimpleFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a file
        String content = "Delete me";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        Path filePath = tempDir.resolve("test.txt");
        assertThat(filePath).exists();

        // Delete it
        fs.delete("test.txt");

        assertThat(filePath).doesNotExist();

        fs.close();
    }

    @Test
    void testDeleteNestedFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a nested file
        String content = "Nested delete";
        fs.write("dir1/dir2/nested.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        Path filePath = tempDir.resolve("dir1/dir2/nested.txt");
        assertThat(filePath).exists();

        // Delete it
        fs.delete("dir1/dir2/nested.txt");

        assertThat(filePath).doesNotExist();

        fs.close();
    }

    @Test
    void testDeleteNonExistentFile() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.delete("nonexistent.txt")).isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("nonexistent.txt");

        fs.close();
    }

    @Test
    void testDeleteRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.delete(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testDeleteRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.delete("../../../etc/passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testDeleteFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.delete("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testDeleteFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.delete("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void testDeleteDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create a directory
        Files.createDirectory(tempDir.resolve("testdir"));

        // Should be able to delete empty directory
        fs.delete("testdir");

        assertThat(tempDir.resolve("testdir")).doesNotExist();

        fs.close();
    }
}
