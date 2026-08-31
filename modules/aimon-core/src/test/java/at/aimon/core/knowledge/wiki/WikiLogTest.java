package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiLog")
class WikiLogTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with valid values stores all fields")
        void builderWithValidValues() {
            WikiLogEntry entry = WikiLogEntry.builder().timestamp(Instant.now())
                    .operation(WikiLogEntry.Operation.PAGE_CREATED).build();

            WikiLog log = WikiLog.builder().entries(List.of(entry)).totalEntryCount(100).build();

            assertThat(log.getEntries()).hasSize(1);
            assertThat(log.getEntries().get(0)).isSameAs(entry);
            assertThat(log.getTotalEntryCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("entries default to empty list when not set")
        void entriesDefaultToEmpty() {
            WikiLog log = WikiLog.builder().build();

            assertThat(log.getEntries()).isEmpty();
        }

        @Test
        @DisplayName("totalEntryCount defaults to zero when not set")
        void totalEntryCountDefaultsToZero() {
            WikiLog log = WikiLog.builder().build();

            assertThat(log.getTotalEntryCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative totalEntryCount throws IllegalArgumentException")
        void negativeTotalEntryCountThrowsIae() {
            assertThatThrownBy(() -> WikiLog.builder().totalEntryCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("entries list is unmodifiable")
        void entriesAreUnmodifiable() {
            WikiLogEntry entry = WikiLogEntry.builder().timestamp(Instant.now())
                    .operation(WikiLogEntry.Operation.PAGE_UPDATED).build();

            WikiLog log = WikiLog.builder().entries(new ArrayList<>(List.of(entry))).totalEntryCount(1).build();

            assertThatThrownBy(() -> log.getEntries().add(entry)).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
