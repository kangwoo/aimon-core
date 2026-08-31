package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemMetadataTest {

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
    void testMetadataAccurateSize() {
        String content = "Test content with known size";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        FileMetadata metadata = fs.getMetadata("test.txt");

        assertThat(metadata.getSize()).isEqualTo(content.getBytes().length);
        assertThat(metadata.getPath()).isEqualTo("test.txt");
    }

    @Test
    void testMetadataTimestamps() {
        Instant before = Instant.now().minusSeconds(2);

        fs.write("test.txt", new ByteArrayInputStream("data".getBytes()), 4);

        Instant after = Instant.now().plusSeconds(2);

        FileMetadata metadata = fs.getMetadata("test.txt");

        assertThat(metadata.getCreatedAt()).isBetween(before, after);
        assertThat(metadata.getModifiedAt()).isBetween(before, after);
    }

    @Test
    void testMetadataMimeType() {
        fs.write("doc.txt", new ByteArrayInputStream("text".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("doc.txt");

        // S3 returns content-type set during upload (may be application/octet-stream by default)
        assertThat(metadata.getMimeType()).isPresent();
    }

    @Test
    void testMetadataNestedFile() {
        String content = "nested";
        fs.write("a/b/file.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        FileMetadata metadata = fs.getMetadata("a/b/file.txt");

        assertThat(metadata.getPath()).isEqualTo("a/b/file.txt");
        assertThat(metadata.getSize()).isEqualTo(content.getBytes().length);
    }

    @Test
    void testMetadataNonExistentFile() {
        assertThatThrownBy(() -> fs.getMetadata("nonexistent.txt")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testMetadataRejectsNull() {
        assertThatThrownBy(() -> fs.getMetadata(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }
}
