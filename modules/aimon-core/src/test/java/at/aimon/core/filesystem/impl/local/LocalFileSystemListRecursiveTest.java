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

class LocalFileSystemListRecursiveTest {

    @TempDir
    Path tempDir;

    @Test
    void testListRecursiveIncludesAllNestedFiles() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create nested structure
        fs.write("file1.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir1/file2.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir1/dir2/file3.txt", new ByteArrayInputStream("c".getBytes()), 1);
        fs.write("dir1/dir2/dir3/file4.txt", new ByteArrayInputStream("d".getBytes()), 1);

        List<String> files = fs.listRecursive(".");

        assertThat(files).hasSize(4);
        assertThat(files).containsExactlyInAnyOrder("file1.txt", "dir1/file2.txt", "dir1/dir2/file3.txt",
                "dir1/dir2/dir3/file4.txt");

        fs.close();
    }

    @Test
    void testListRecursiveFromSubdirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create nested structure
        fs.write("root.txt", new ByteArrayInputStream("root".getBytes()), 4);
        fs.write("dir1/file1.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir1/sub1/file2.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir1/sub2/file3.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.listRecursive("dir1");

        // Should only include files under dir1
        assertThat(files).hasSize(3);
        assertThat(files).containsExactlyInAnyOrder("dir1/file1.txt", "dir1/sub1/file2.txt", "dir1/sub2/file3.txt");
        assertThat(files).doesNotContain("root.txt");

        fs.close();
    }

    @Test
    void testListRecursiveReturnsEmptyForEmptyDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create empty subdirectory
        java.nio.file.Files.createDirectory(tempDir.resolve("emptydir"));

        List<String> files = fs.listRecursive("emptydir");

        assertThat(files).isEmpty();

        fs.close();
    }

    @Test
    void testListRecursiveWithMultipleBranches() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create tree structure with multiple branches
        fs.write("branch1/file1.txt", new ByteArrayInputStream("1".getBytes()), 1);
        fs.write("branch1/sub/file2.txt", new ByteArrayInputStream("2".getBytes()), 1);
        fs.write("branch2/file3.txt", new ByteArrayInputStream("3".getBytes()), 1);
        fs.write("branch2/sub/file4.txt", new ByteArrayInputStream("4".getBytes()), 1);
        fs.write("branch3/file5.txt", new ByteArrayInputStream("5".getBytes()), 1);

        List<String> files = fs.listRecursive(".");

        assertThat(files).hasSize(5);
        assertThat(files).contains("branch1/file1.txt", "branch1/sub/file2.txt", "branch2/file3.txt",
                "branch2/sub/file4.txt", "branch3/file5.txt");

        fs.close();
    }

    @Test
    void testListRecursiveWithDeepNesting() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create very deep nesting (10 levels)
        String deepPath = "l1/l2/l3/l4/l5/l6/l7/l8/l9/l10/deep.txt";
        fs.write(deepPath, new ByteArrayInputStream("deep".getBytes()), 4);

        List<String> files = fs.listRecursive(".");

        assertThat(files).hasSize(1);
        assertThat(files).containsExactly(deepPath);

        fs.close();
    }

    @Test
    void testListRecursiveIgnoresEmptySubdirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create files and empty directories
        fs.write("file1.txt", new ByteArrayInputStream("a".getBytes()), 1);
        java.nio.file.Files.createDirectories(tempDir.resolve("empty1/empty2"));
        fs.write("dir1/file2.txt", new ByteArrayInputStream("b".getBytes()), 1);

        List<String> files = fs.listRecursive(".");

        // Should only return files, not directories
        assertThat(files).hasSize(2);
        assertThat(files).containsExactlyInAnyOrder("file1.txt", "dir1/file2.txt");

        fs.close();
    }

    @Test
    void testListRecursiveThrowsForNonExistentDirectory() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.listRecursive("nonexistent")).isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("nonexistent");

        fs.close();
    }

    @Test
    void testListRecursiveThrowsForFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("file.txt", new ByteArrayInputStream("content".getBytes()), 7);

        assertThatThrownBy(() -> fs.listRecursive("file.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Not a directory");

        fs.close();
    }

    @Test
    void testListRecursiveRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.listRecursive(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");

        fs.close();
    }

    @Test
    void testListRecursiveRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.listRecursive("../../../etc")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testListRecursiveHandlesLargeNumberOfFiles() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 100 files across multiple directories
        for (int i = 0; i < 100; i++) {
            String path = "dir" + (i / 10) + "/file" + i + ".txt";
            fs.write(path, new ByteArrayInputStream(("content" + i).getBytes()), ("content" + i).length());
        }

        List<String> files = fs.listRecursive(".");

        assertThat(files).hasSize(100);

        fs.close();
    }

    @Test
    void testListRecursiveUsesForwardSlashes() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("dir1/dir2/file.txt", new ByteArrayInputStream("test".getBytes()), 4);

        List<String> files = fs.listRecursive(".");

        assertThat(files).hasSize(1);
        // Paths should be normalized with forward slashes
        assertThat(files.get(0)).contains("/");
        assertThat(files.get(0)).doesNotContain("\\");

        fs.close();
    }
}
