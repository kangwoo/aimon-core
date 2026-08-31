package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.filesystem.core.s3.S3FileSystem;

/**
 * Tests for path validation in S3FileSystem. Verifies that blank paths, null byte injection, and path traversal
 * attempts are properly rejected.
 */
@Tag("docker")
class S3FileSystemPathValidationTest {

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

    // --- Blank path ---

    @Test
    void testWriteRejectsBlankPath() {
        assertThatThrownBy(() -> fs.write("", new ByteArrayInputStream("x".getBytes()), 1))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.write("   ", new ByteArrayInputStream("x".getBytes()), 1))
                .isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testReadRejectsBlankPath() {
        assertThatThrownBy(() -> fs.read("")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.read("   ")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testDeleteRejectsBlankPath() {
        assertThatThrownBy(() -> fs.delete("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testExistsRejectsBlankPath() {
        assertThatThrownBy(() -> fs.exists("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testIsDirectoryRejectsBlankPath() {
        assertThatThrownBy(() -> fs.isDirectory("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testGetMetadataRejectsBlankPath() {
        assertThatThrownBy(() -> fs.getMetadata("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testCopyRejectsBlankPath() {
        assertThatThrownBy(() -> fs.copy("", "dest.txt", false)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.copy("src.txt", "", false)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testMoveRejectsBlankPath() {
        assertThatThrownBy(() -> fs.move("", "dest.txt", false)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.move("src.txt", "", false)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testCreateDirectoryRejectsBlankPath() {
        assertThatThrownBy(() -> fs.createDirectory("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testDeleteRecursiveRejectsBlankPath() {
        assertThatThrownBy(() -> fs.deleteRecursive("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testOpenOutputStreamRejectsBlankPath() {
        assertThatThrownBy(() -> fs.openOutputStream("")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testOpenInputStreamRejectsBlankPath() {
        assertThatThrownBy(() -> fs.openInputStream("")).isInstanceOf(InvalidPathException.class);
    }

    // --- Null byte injection ---

    @Test
    void testWriteRejectsNullByte() {
        assertThatThrownBy(() -> fs.write("file\0.txt", new ByteArrayInputStream("x".getBytes()), 1))
                .isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testReadRejectsNullByte() {
        assertThatThrownBy(() -> fs.read("file\0.txt")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testExistsRejectsNullByte() {
        assertThatThrownBy(() -> fs.exists("file\0.txt")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testListRejectsNullByte() {
        assertThatThrownBy(() -> fs.list("dir\0name")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testListRecursiveRejectsNullByte() {
        assertThatThrownBy(() -> fs.listRecursive("dir\0name")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testSearchRejectsNullByteInDirectory() {
        assertThatThrownBy(() -> fs.search("dir\0name", "*.txt", 10)).isInstanceOf(InvalidPathException.class);
    }

    // --- Path traversal ---

    @Test
    void testWriteRejectsTraversal() {
        assertThatThrownBy(() -> fs.write("../etc/passwd", new ByteArrayInputStream("x".getBytes()), 1))
                .isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testReadRejectsTraversal() {
        assertThatThrownBy(() -> fs.read("../etc/passwd")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testDeleteRejectsTraversal() {
        assertThatThrownBy(() -> fs.delete("foo/../../../etc/passwd")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testExistsRejectsTraversal() {
        assertThatThrownBy(() -> fs.exists("../secret")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testCopyRejectsTraversal() {
        assertThatThrownBy(() -> fs.copy("../src", "dest.txt", false)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.copy("src.txt", "../dest", false)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testListRejectsTraversal() {
        assertThatThrownBy(() -> fs.list("../secret")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testSearchRejectsTraversal() {
        assertThatThrownBy(() -> fs.search("../secret", "*.txt", 10)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void testBackslashTraversalRejected() {
        assertThatThrownBy(() -> fs.read("..\\etc\\passwd")).isInstanceOf(InvalidPathException.class);
    }
}
