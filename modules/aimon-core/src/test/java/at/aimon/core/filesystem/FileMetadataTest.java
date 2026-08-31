package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FileMetadataTest {

    @Test
    void testBuilderCreatesValidMetadata() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("test/file.txt").size(1024).createdAt(now).modifiedAt(now)
                .mimeType("text/plain").customMetadata("key", "value").build();

        assertThat(metadata.getPath()).isEqualTo("test/file.txt");
        assertThat(metadata.getSize()).isEqualTo(1024);
        assertThat(metadata.getCreatedAt()).isEqualTo(now);
        assertThat(metadata.getModifiedAt()).isEqualTo(now);
        assertThat(metadata.getMimeType()).hasValue("text/plain");
        assertThat(metadata.getCustomMetadata()).containsEntry("key", "value");
    }

    @Test
    void testBuilderWithOptionalMimeType() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("test/file.bin").size(512).createdAt(now).modifiedAt(now)
                .build();

        assertThat(metadata.getMimeType()).isEmpty();
    }

    @Test
    void testBuilderRejectsNullPath() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> FileMetadata.builder().path(null).size(100).createdAt(now).modifiedAt(now).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Path cannot be null");
    }

    @Test
    void testBuilderRejectsNegativeSize() {
        Instant now = Instant.now();
        assertThatThrownBy(
                () -> FileMetadata.builder().path("test.txt").size(-1).createdAt(now).modifiedAt(now).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Size must be non-negative");
    }

    @Test
    void testBuilderRejectsModifiedBeforeCreated() {
        Instant now = Instant.now();
        Instant past = now.minusSeconds(60);
        assertThatThrownBy(
                () -> FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(past).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModifiedAt cannot be before createdAt");
    }

    @Test
    void testCustomMetadataIsImmutable() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .customMetadata("key1", "value1").build();

        Map<String, String> retrieved = metadata.getCustomMetadata();
        assertThatThrownBy(() -> retrieved.put("key2", "value2")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testBuilderWithBulkCustomMetadata() {
        Instant now = Instant.now();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");

        FileMetadata fileMetadata = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .customMetadata(metadata).build();

        assertThat(fileMetadata.getCustomMetadata()).containsEntry("key1", "value1").containsEntry("key2", "value2");
    }

    @Test
    void testEqualsAndHashCode() {
        Instant now = Instant.now();
        FileMetadata metadata1 = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .build();

        FileMetadata metadata2 = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .build();

        assertThat(metadata1).isEqualTo(metadata2);
        assertThat(metadata1.hashCode()).isEqualTo(metadata2.hashCode());
    }

    @Test
    void testToString() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .mimeType("text/plain").build();

        String toString = metadata.toString();
        assertThat(toString).contains("FileMetadata").contains("test.txt").contains("100").contains("text/plain");
    }

    @Test
    void testBuilderDirectoryDefaultsFalse() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("test.txt").size(100).createdAt(now).modifiedAt(now)
                .build();

        assertThat(metadata.isDirectory()).isFalse();
    }

    @Test
    void testBuilderDirectoryTrue() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("mydir").size(0).directory(true).createdAt(now)
                .modifiedAt(now).build();

        assertThat(metadata.isDirectory()).isTrue();
        assertThat(metadata.getSize()).isZero();
    }

    @Test
    void testEqualsIncludesDirectory() {
        Instant now = Instant.now();
        FileMetadata file = FileMetadata.builder().path("entry").size(0).createdAt(now).modifiedAt(now).build();
        FileMetadata dir = FileMetadata.builder().path("entry").size(0).directory(true).createdAt(now).modifiedAt(now)
                .build();

        assertThat(file).isNotEqualTo(dir);
    }

    @Test
    void testToStringIncludesDirectory() {
        Instant now = Instant.now();
        FileMetadata metadata = FileMetadata.builder().path("mydir").size(0).directory(true).createdAt(now)
                .modifiedAt(now).build();

        assertThat(metadata.toString()).contains("directory=true");
    }
}
