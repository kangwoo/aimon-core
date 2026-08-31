package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemDeleteRecursiveTest {

    private S3FileSystem fs;

    @BeforeEach
    void setUp() {
        S3TestSupport.cleanBucket();
        fs = S3TestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    void testDeleteSingleFile() {
        fs.write("test.txt", new ByteArrayInputStream("data".getBytes()), 4);

        fs.deleteRecursive("test.txt");

        assertThat(fs.exists("test.txt")).isFalse();
    }

    @Test
    void testDeleteNestedDirectoryRecursively() {
        fs.write("dir/a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/sub/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir/sub/deep/c.txt", new ByteArrayInputStream("c".getBytes()), 1);
        fs.write("other.txt", new ByteArrayInputStream("o".getBytes()), 1);

        fs.deleteRecursive("dir");

        assertThat(fs.exists("dir/a.txt")).isFalse();
        assertThat(fs.exists("dir/sub/b.txt")).isFalse();
        assertThat(fs.exists("dir/sub/deep/c.txt")).isFalse();
        assertThat(fs.exists("other.txt")).isTrue();
    }

    @Test
    void testDeleteRootIsRejected() {
        assertThatThrownBy(() -> fs.deleteRecursive(".")).isInstanceOf(VirtualFileSystemException.class)
                .hasMessageContaining("Cannot delete VFS root");
    }

    @Test
    void testDeleteNonExistentPath() {
        assertThatThrownBy(() -> fs.deleteRecursive("nonexistent")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testDeleteRejectsNull() {
        assertThatThrownBy(() -> fs.deleteRecursive(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testDeleteFailsWhenNotInitialized() {
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.deleteRecursive("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testDeleteFailsWhenClosed() {
        S3FileSystem closedFs = S3TestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.deleteRecursive("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
