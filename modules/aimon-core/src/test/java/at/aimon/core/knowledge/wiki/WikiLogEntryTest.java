package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiLogEntry")
class WikiLogEntryTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder creates valid entry with all fields")
        void builderCreatesValidEntry() {
            Instant now = Instant.now();
            WikiLogEntry entry = WikiLogEntry.builder().timestamp(now).operation(WikiLogEntry.Operation.PAGE_CREATED)
                    .pagePath("/wiki/my-page.md").summary("Created page for Kubernetes pods").build();

            assertThat(entry.getTimestamp()).isEqualTo(now);
            assertThat(entry.getOperation()).isEqualTo(WikiLogEntry.Operation.PAGE_CREATED);
            assertThat(entry.getPagePath()).isEqualTo("/wiki/my-page.md");
            assertThat(entry.getSummary()).isEqualTo("Created page for Kubernetes pods");
        }

        @Test
        @DisplayName("pagePath is null when not set")
        void pagePathNullByDefault() {
            WikiLogEntry entry = WikiLogEntry.builder().timestamp(Instant.now())
                    .operation(WikiLogEntry.Operation.LINT_PERFORMED).build();

            assertThat(entry.getPagePath()).isNull();
        }

        @Test
        @DisplayName("summary is null when not set")
        void summaryNullByDefault() {
            WikiLogEntry entry = WikiLogEntry.builder().timestamp(Instant.now())
                    .operation(WikiLogEntry.Operation.SOURCE_INGESTED).build();

            assertThat(entry.getSummary()).isNull();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null timestamp throws NullPointerException")
        void nullTimestampThrowsNpe() {
            assertThatThrownBy(
                    () -> WikiLogEntry.builder().timestamp(null).operation(WikiLogEntry.Operation.PAGE_CREATED).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null operation throws NullPointerException")
        void nullOperationThrowsNpe() {
            assertThatThrownBy(() -> WikiLogEntry.builder().timestamp(Instant.now()).operation(null).build())
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
