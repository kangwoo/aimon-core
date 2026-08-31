package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemListTest {

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

        assertThat(files).containsExactly("root.txt");
        assertThat(files).doesNotContain("dir/nested.txt");
    }

    @Test
    void testListSubdirectory() {
        fs.write("dir/a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir/sub/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.list("dir");

        assertThat(files).containsExactlyInAnyOrder("dir/a.txt", "dir/b.txt");
    }

    @Test
    void testListIgnoresDirectoryMarkers() {
        fs.createDirectory("mydir");
        fs.write("mydir/file.txt", new ByteArrayInputStream("f".getBytes()), 1);

        List<String> files = fs.list("mydir");

        assertThat(files).containsExactly("mydir/file.txt");
        assertThat(files).noneMatch(f -> f.endsWith("/"));
    }

    @Test
    void testListRejectsNull() {
        assertThatThrownBy(() -> fs.list(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");
    }
}
