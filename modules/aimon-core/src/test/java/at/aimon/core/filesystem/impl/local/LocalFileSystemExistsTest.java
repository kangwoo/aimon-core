package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileSystemExistsTest {

    @TempDir
    Path tempDir;

    @Test
    void testExistsReturnsTrueForExistingFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a file
        String content = "Test content";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        assertThat(fs.exists("test.txt")).isTrue();

        fs.close();
    }

    @Test
    void testExistsReturnsFalseForNonExistentFile() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThat(fs.exists("nonexistent.txt")).isFalse();

        fs.close();
    }

    @Test
    void testExistsForNestedFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a nested file
        String content = "Nested content";
        fs.write("dir1/dir2/nested.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        assertThat(fs.exists("dir1/dir2/nested.txt")).isTrue();
        assertThat(fs.exists("dir1/dir2/nonexistent.txt")).isFalse();

        fs.close();
    }

    @Test
    void testExistsReturnsFalseAfterDelete() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write a file
        String content = "Delete me";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        assertThat(fs.exists("test.txt")).isTrue();

        // Delete it
        fs.delete("test.txt");

        assertThat(fs.exists("test.txt")).isFalse();

        fs.close();
    }

    @Test
    void testExistsReturnsFalseForInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Path traversal should return false instead of throwing
        assertThat(fs.exists("../../../etc/passwd")).isFalse();

        fs.close();
    }

    @Test
    void testExistsRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.exists(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testExistsFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.exists("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testExistsFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.exists("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
