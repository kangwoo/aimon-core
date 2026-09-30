package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileMetadata;

@DisplayName("FileStamp")
class FileStampTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static FileStamp stamp(long size, Instant modified, String etag) {
        return FileStamp
                .of(FileMetadata.builder().path("a").size(size).createdAt(T).modifiedAt(modified).etag(etag).build());
    }

    @Test
    @DisplayName("without etags, size and modification time decide")
    void sizeAndTime() {
        assertThat(stamp(3, T, null)).isEqualTo(stamp(3, T, null));
        assertThat(stamp(3, T, null)).isNotEqualTo(stamp(4, T, null));
        assertThat(stamp(3, T, null)).isNotEqualTo(stamp(3, T.plusNanos(1), null));
    }

    @Test
    @DisplayName("when both sides have an etag it decides, whatever size and time say")
    void etagPreferred() {
        assertThat(stamp(3, T, "e1")).isNotEqualTo(stamp(3, T, "e2"));
        assertThat(stamp(3, T, "e1")).isEqualTo(stamp(9, T.plusSeconds(5), "e1"));
    }

    @Test
    @DisplayName("an etag on one side only falls back to size and time")
    void oneSidedEtag() {
        assertThat(stamp(3, T, "e1")).isEqualTo(stamp(3, T, null));
        assertThat(stamp(3, T, "e1")).isNotEqualTo(stamp(4, T, null));
    }

    @Test
    @DisplayName("exposes the metadata fields")
    void accessors() {
        final FileStamp stamp = stamp(7, T, "e");
        assertThat(stamp.getSize()).isEqualTo(7);
        assertThat(stamp.getModifiedAt()).isEqualTo(T);
        assertThat(stamp.getEtag()).hasValue("e");
    }
}
