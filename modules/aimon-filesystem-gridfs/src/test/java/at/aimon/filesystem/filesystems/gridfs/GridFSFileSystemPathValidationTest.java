package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemPathValidationTest {

    private GridFSFileSystem fs;

    @BeforeEach
    void setUp() {
        GridFSTestSupport.cleanDatabase();
        fs = GridFSTestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    void testPathTraversalForwardSlash() {
        assertThatThrownBy(() -> fs.read("../etc/passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testPathTraversalBackslash() {
        assertThatThrownBy(() -> fs.read("..\\etc\\passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testNullByte() {
        assertThatThrownBy(() -> fs.read("test\0.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void testEmptyPath() {
        assertThatThrownBy(() -> fs.read("")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("empty or blank");
    }

    @Test
    void testBlankPath() {
        assertThatThrownBy(() -> fs.read("   ")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("empty or blank");
    }

    @Test
    void testPathTraversalOnWrite() {
        assertThatThrownBy(() -> fs.write("../evil.txt", new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("path traversal");
    }

    @Test
    void testPathTraversalOnDelete() {
        assertThatThrownBy(() -> fs.delete("../evil.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testPathTraversalOnExists() {
        assertThatThrownBy(() -> fs.exists("../evil.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testPathTraversalOnCopy() {
        assertThatThrownBy(() -> fs.copy("../evil.txt", "dest.txt", false)).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testPathTraversalOnMove() {
        assertThatThrownBy(() -> fs.move("../evil.txt", "dest.txt", false)).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void testNullByteOnList() {
        assertThatThrownBy(() -> fs.list("dir\0name")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void testPathTraversalOnSearch() {
        assertThatThrownBy(() -> fs.search("../secret", "*.txt", 10)).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("path traversal");
    }
}
