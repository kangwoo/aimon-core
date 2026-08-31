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
class S3FileSystemListRecursiveTest {

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
    void testListRecursiveAllNested() {
        fs.write("a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir/sub/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.listRecursive(".");

        assertThat(files).containsExactlyInAnyOrder("a.txt", "dir/b.txt", "dir/sub/c.txt");
    }

    @Test
    void testListRecursiveFromSubdirectory() {
        fs.write("dir/a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/sub/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("other/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> files = fs.listRecursive("dir");

        assertThat(files).containsExactlyInAnyOrder("dir/a.txt", "dir/sub/b.txt");
        assertThat(files).doesNotContain("other/c.txt");
    }

    @Test
    void testListRecursiveMultipleBranches() {
        fs.write("a/1.txt", new ByteArrayInputStream("1".getBytes()), 1);
        fs.write("a/2.txt", new ByteArrayInputStream("2".getBytes()), 1);
        fs.write("b/3.txt", new ByteArrayInputStream("3".getBytes()), 1);

        List<String> files = fs.listRecursive(".");

        assertThat(files).containsExactlyInAnyOrder("a/1.txt", "a/2.txt", "b/3.txt");
    }

    @Test
    void testListRecursiveDeepNesting() {
        fs.write("a/b/c/d/e.txt", new ByteArrayInputStream("deep".getBytes()), 4);

        List<String> files = fs.listRecursive(".");

        assertThat(files).containsExactly("a/b/c/d/e.txt");
    }

    @Test
    void testListRecursiveRejectsNull() {
        assertThatThrownBy(() -> fs.listRecursive(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");
    }
}
