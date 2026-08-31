package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemExistsTest {

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
    void testExistsTrue() {
        fs.write("test.txt", new ByteArrayInputStream("data".getBytes()), 4);

        assertThat(fs.exists("test.txt")).isTrue();
    }

    @Test
    void testExistsFalse() {
        assertThat(fs.exists("nonexistent.txt")).isFalse();
    }

    @Test
    void testExistsNestedFile() {
        fs.write("a/b/c.txt", new ByteArrayInputStream("data".getBytes()), 4);

        assertThat(fs.exists("a/b/c.txt")).isTrue();
    }

    @Test
    void testExistsFalseAfterDelete() {
        fs.write("test.txt", new ByteArrayInputStream("data".getBytes()), 4);
        fs.delete("test.txt");

        assertThat(fs.exists("test.txt")).isFalse();
    }

    @Test
    void testExistsRejectsNull() {
        assertThatThrownBy(() -> fs.exists(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testExistsFailsWhenNotInitialized() {
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.exists("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testExistsFailsWhenClosed() {
        S3FileSystem closedFs = S3TestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.exists("test.txt")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
