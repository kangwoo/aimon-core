package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemListTest {

    @TempDir
    Path tempDir;

    @Test
    void testListReturnsFilesInDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write files to root directory
        fs.write("file1.txt", new ByteArrayInputStream("content1".getBytes()), 8);
        fs.write("file2.txt", new ByteArrayInputStream("content2".getBytes()), 8);
        fs.write("file3.txt", new ByteArrayInputStream("content3".getBytes()), 8);

        List<String> files = fs.list(".");

        assertThat(files).hasSize(3);
        assertThat(files).containsExactlyInAnyOrder("file1.txt", "file2.txt", "file3.txt");

        fs.close();
    }

    @Test
    void testListReturnsEmptyForEmptyDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create empty subdirectory
        java.nio.file.Files.createDirectory(tempDir.resolve("emptydir"));

        List<String> files = fs.list("emptydir");

        assertThat(files).isEmpty();

        fs.close();
    }

    @Test
    void testListReturnsFilesAndDirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write files and create subdirectories
        fs.write("file1.txt", new ByteArrayInputStream("content".getBytes()), 7);
        java.nio.file.Files.createDirectory(tempDir.resolve("subdir1"));
        java.nio.file.Files.createDirectory(tempDir.resolve("subdir2"));
        fs.write("file2.txt", new ByteArrayInputStream("content".getBytes()), 7);

        List<String> entries = fs.list(".");

        assertThat(entries).hasSize(4);
        assertThat(entries).containsExactlyInAnyOrder("file1.txt", "file2.txt", "subdir1", "subdir2");

        fs.close();
    }

    @Test
    void testListDoesNotIncludeNestedFiles() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write files at different levels
        fs.write("root-file.txt", new ByteArrayInputStream("root".getBytes()), 4);
        fs.write("dir1/nested-file.txt", new ByteArrayInputStream("nested".getBytes()), 6);
        fs.write("dir1/dir2/deep-file.txt", new ByteArrayInputStream("deep".getBytes()), 4);

        List<String> entries = fs.list(".");

        // Should return root-level file and directory, but not nested files
        assertThat(entries).hasSize(2);
        assertThat(entries).containsExactlyInAnyOrder("root-file.txt", "dir1");

        fs.close();
    }

    @Test
    void testListSpecificSubdirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create files in subdirectory
        fs.write("dir1/file1.txt", new ByteArrayInputStream("content1".getBytes()), 8);
        fs.write("dir1/file2.txt", new ByteArrayInputStream("content2".getBytes()), 8);
        fs.write("other.txt", new ByteArrayInputStream("other".getBytes()), 5);

        List<String> files = fs.list("dir1");

        assertThat(files).hasSize(2);
        assertThat(files).containsExactlyInAnyOrder("dir1/file1.txt", "dir1/file2.txt");

        fs.close();
    }

    @Test
    void testListNestedSubdirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create files in nested subdirectory
        fs.write("level1/level2/file1.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("level1/level2/file2.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("level1/other.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.list("level1/level2");

        assertThat(files).hasSize(2);
        assertThat(files).containsExactlyInAnyOrder("level1/level2/file1.txt", "level1/level2/file2.txt");

        fs.close();
    }

    @Test
    void testListThrowsForNonExistentDirectory() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.list("nonexistent")).isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("nonexistent");

        fs.close();
    }

    @Test
    void testListThrowsForFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("file.txt", new ByteArrayInputStream("content".getBytes()), 7);

        assertThatThrownBy(() -> fs.list("file.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Not a directory");

        fs.close();
    }

    @Test
    void testListRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.list(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");

        fs.close();
    }

    @Test
    void testListRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.list("../../../etc")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testListHandlesPathsWithForwardSlashes() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("dir1/file.txt", new ByteArrayInputStream("test".getBytes()), 4);

        List<String> files = fs.list("dir1");

        assertThat(files).hasSize(1);
        // Paths should be normalized with forward slashes
        assertThat(files.get(0)).contains("/");
        assertThat(files.get(0)).doesNotContain("\\");

        fs.close();
    }
}
