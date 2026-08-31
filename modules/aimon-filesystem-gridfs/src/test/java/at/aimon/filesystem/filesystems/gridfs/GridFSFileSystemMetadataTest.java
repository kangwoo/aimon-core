package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemMetadataTest {

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
        fs.write("doc.pdf", new ByteArrayInputStream("pdf".getBytes()), 3);
        fs.write("doc.json", new ByteArrayInputStream("json".getBytes()), 4);

        assertThat(fs.getMetadata("doc.txt").getMimeType()).hasValue("text/plain");
        assertThat(fs.getMetadata("doc.pdf").getMimeType()).hasValue("application/pdf");
        assertThat(fs.getMetadata("doc.json").getMimeType()).hasValue("application/json");
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
