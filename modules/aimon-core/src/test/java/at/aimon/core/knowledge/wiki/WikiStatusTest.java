package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiStatus")
class WikiStatusTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with all fields set stores all values")
        void builderWithAllFields() {
            Instant now = Instant.now();
            WikiStatus status = WikiStatus.builder().pageCount(42).sourceCount(10).lastIngestedAt(now)
                    .wikiDirectory("/wiki/ops").state(WikiStatus.State.READY).build();

            assertThat(status.getPageCount()).isEqualTo(42);
            assertThat(status.getSourceCount()).isEqualTo(10);
            assertThat(status.getLastIngestedAt()).isEqualTo(now);
            assertThat(status.getWikiDirectory()).isEqualTo("/wiki/ops");
            assertThat(status.getState()).isEqualTo(WikiStatus.State.READY);
        }

        @Test
        @DisplayName("default state is EMPTY when not set")
        void defaultStateIsEmpty() {
            WikiStatus status = WikiStatus.builder().build();

            assertThat(status.getState()).isEqualTo(WikiStatus.State.EMPTY);
        }

        @Test
        @DisplayName("lastIngestedAt is null when not set")
        void lastIngestedAtNullByDefault() {
            WikiStatus status = WikiStatus.builder().build();

            assertThat(status.getLastIngestedAt()).isNull();
        }

        @Test
        @DisplayName("wikiDirectory is null when not set")
        void wikiDirectoryNullByDefault() {
            WikiStatus status = WikiStatus.builder().build();

            assertThat(status.getWikiDirectory()).isNull();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative pageCount throws IllegalArgumentException")
        void negativePageCountThrowsIae() {
            assertThatThrownBy(() -> WikiStatus.builder().pageCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative sourceCount throws IllegalArgumentException")
        void negativeSourceCountThrowsIae() {
            assertThatThrownBy(() -> WikiStatus.builder().sourceCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null state throws NullPointerException")
        void nullStateThrowsNpe() {
            assertThatThrownBy(() -> WikiStatus.builder().state(null).build()).isInstanceOf(NullPointerException.class);
        }
    }
}
