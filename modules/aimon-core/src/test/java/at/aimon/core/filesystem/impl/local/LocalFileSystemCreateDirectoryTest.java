package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemCreateDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateSimpleDirectory() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.createDirectory("newdir");

        assertThat(tempDir.resolve("newdir")).exists().isDirectory();

        fs.close();
    }

    @Test
    void testCreateNestedDirectories() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.createDirectory("a/b/c");

        assertThat(tempDir.resolve("a/b/c")).exists().isDirectory();
        assertThat(tempDir.resolve("a/b")).exists().isDirectory();
        assertThat(tempDir.resolve("a")).exists().isDirectory();

        fs.close();
    }

    @Test
    void testCreateDirectoryIdempotent() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.createDirectory("existing");
        fs.createDirectory("existing");

        assertThat(tempDir.resolve("existing")).exists().isDirectory();

        fs.close();
    }

    @Test
    void testCreateDirectoryRootIsNoOp() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // "." normalizes to basePath — should be no-op
        fs.createDirectory(".");

        assertThat(tempDir).exists().isDirectory();

        fs.close();
    }

    @Test
    void testCreateDirectoryFailsWhenFileExists() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create a regular file
        Files.writeString(tempDir.resolve("conflict"), "I am a file");

        assertThatThrownBy(() -> fs.createDirectory("conflict")).isInstanceOf(FileAlreadyExistsException.class);

        fs.close();
    }

    @Test
    void testCreateDirectoryRejectsNull() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.createDirectory(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testCreateDirectoryRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.createDirectory("../../../escape")).isInstanceOf(InvalidPathException.class);

        fs.close();
    }

    @Test
    void testCreateDirectoryFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testCreateDirectoryFailsWhenClosed() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        fs.close();

        assertThatThrownBy(() -> fs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
