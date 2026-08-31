package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemListTest {

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
    void testListDirectChildren() {
        fs.write("a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("b.txt", new ByteArrayInputStream("b".getBytes()), 1);

        List<String> files = fs.list(".");

        assertThat(files).containsExactlyInAnyOrder("a.txt", "b.txt");
    }

    @Test
    void testListDoesNotIncludeNestedFiles() {
        fs.write("root.txt", new ByteArrayInputStream("r".getBytes()), 1);
        fs.write("dir/nested.txt", new ByteArrayInputStream("n".getBytes()), 1);

        List<String> files = fs.list(".");

        // The nested file itself stays out; the directory holding it comes back as one entry, the way a local
        // filesystem reports a subdirectory rather than pretending it is not there.
        assertThat(files).containsExactlyInAnyOrder("root.txt", "dir");
        assertThat(files).doesNotContain("dir/nested.txt");
    }

    @Test
    void testListSubdirectory() {
        fs.write("dir/a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir/sub/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.list("dir");

        assertThat(files).containsExactlyInAnyOrder("dir/a.txt", "dir/b.txt", "dir/sub");
    }

    @Test
    void testListReportsEmptyDirectoryCreatedExplicitly() {
        fs.createDirectory("empty");

        assertThat(fs.list(".")).containsExactly("empty");
        assertThat(fs.list("empty")).isEmpty();
    }

    @Test
    void testListRejectsMissingDirectory() {
        assertThatThrownBy(() -> fs.list("nope"))
                .isInstanceOf(at.aimon.core.filesystem.exception.FileNotFoundException.class);
    }

    @Test
    void testListRejectsFile() {
        fs.write("a.txt", new ByteArrayInputStream("a".getBytes()), 1);

        assertThatThrownBy(() -> fs.list("a.txt"))
                .isInstanceOf(at.aimon.core.filesystem.exception.InvalidPathException.class);
    }

    @Test
    void testListRejectsNull() {
        assertThatThrownBy(() -> fs.list(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");
    }
}
