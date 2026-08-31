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
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

class LocalFileSystemDeleteRecursiveTest {

    @TempDir
    Path tempDir;

    @Test
    void testDeleteSingleFile() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "delete me";
        fs.write("file.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.deleteRecursive("file.txt");

        assertThat(tempDir.resolve("file.txt")).doesNotExist();

        fs.close();
    }

    @Test
    void testDeleteEmptyDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        Files.createDirectory(tempDir.resolve("emptydir"));

        fs.deleteRecursive("emptydir");

        assertThat(tempDir.resolve("emptydir")).doesNotExist();

        fs.close();
    }

    @Test
    void testDeleteNestedDirectoryRecursively() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "content";
        fs.write("dir/sub1/a.txt", new ByteArrayInputStream(content.getBytes()), content.length());
        fs.write("dir/sub1/b.txt", new ByteArrayInputStream(content.getBytes()), content.length());
        fs.write("dir/sub2/c.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.deleteRecursive("dir");

        assertThat(tempDir.resolve("dir")).doesNotExist();

        fs.close();
    }

    @Test
    void testDeleteRootIsRejected() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.deleteRecursive(".")).isInstanceOf(VirtualFileSystemException.class)
                .hasMessageContaining("root");

        fs.close();
    }

    @Test
    void testDeleteNonExistentPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.deleteRecursive("nonexistent")).isInstanceOf(FileNotFoundException.class);

        fs.close();
    }

    @Test
    void testDeleteSymlink() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create a target file outside normal path
        Path target = tempDir.resolve("target.txt");
        Files.writeString(target, "target content");

        // Create a symlink - PathValidator should reject it
        Path link = tempDir.resolve("link.txt");
        Files.createSymbolicLink(link, target);

        assertThatThrownBy(() -> fs.deleteRecursive("link.txt")).isInstanceOf(InvalidPathException.class);

        // Target should still exist
        assertThat(target).exists();

        fs.close();
    }

    @Test
    void testDeleteRejectsNull() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.deleteRecursive(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testDeleteRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.deleteRecursive("../../../etc/passwd")).isInstanceOf(InvalidPathException.class);

        fs.close();
    }

    @Test
    void testDeleteFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.deleteRecursive("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testDeleteFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.deleteRecursive("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
