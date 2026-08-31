package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemWriteTest {

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
    void testWriteSimpleFile() {
        String content = "hello world";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testWriteToNestedDirectory() {
        String content = "nested content";
        fs.write("dir1/dir2/nested.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        assertThat(fs.exists("dir1/dir2/nested.txt")).isTrue();

        try (InputStream in = fs.read("dir1/dir2/nested.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testWriteOverwritesExistingFile() {
        fs.write("test.txt", new ByteArrayInputStream("initial".getBytes()), 7);

        String updated = "updated content";
        fs.write("test.txt", new ByteArrayInputStream(updated.getBytes()), updated.length());

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(updated);
        } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testWriteLargeFile() {
        byte[] largeContent = new byte[2 * 1024 * 1024]; // 2MB
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        assertThat(fs.getMetadata("large.bin").getSize()).isEqualTo(largeContent.length);
    }

    @Test
    void testWriteRejectsNullPath() {
        assertThatThrownBy(() -> fs.write(null, new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Path cannot be null");
    }

    @Test
    void testWriteRejectsNullContent() {
        assertThatThrownBy(() -> fs.write("test.txt", (InputStream) null, 0)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");
    }

    @Test
    void testWriteFailsWhenNotInitialized() {
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.write("test.txt", new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testWriteFailsWhenClosed() {
        S3FileSystem closedFs = S3TestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.write("test.txt", new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}
