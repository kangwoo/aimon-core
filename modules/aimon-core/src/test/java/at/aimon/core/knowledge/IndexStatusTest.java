package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IndexStatus Tests")
class IndexStatusTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create status with all fields")
        void testFullBuild() {
            final Instant now = Instant.now();
            final IndexStatus status = IndexStatus.builder().state(IndexStatus.State.READY).documentCount(10)
                    .chunkCount(50).lastIndexedAt(now).indexedDirectory("/knowledge").build();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.READY);
            assertThat(status.getDocumentCount()).isEqualTo(10);
            assertThat(status.getChunkCount()).isEqualTo(50);
            assertThat(status.getLastIndexedAt()).isEqualTo(now);
            assertThat(status.getIndexedDirectory()).isEqualTo("/knowledge");
        }

        @Test
        @DisplayName("Should default to EMPTY state")
        void testDefaultState() {
            final IndexStatus status = IndexStatus.builder().build();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.EMPTY);
            assertThat(status.getDocumentCount()).isZero();
            assertThat(status.getLastIndexedAt()).isNull();
        }

        @Test
        @DisplayName("Should reject negative counts")
        void testNegativeCounts() {
            assertThatThrownBy(() -> IndexStatus.builder().documentCount(-1).state(IndexStatus.State.EMPTY).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IndexStatus.builder().chunkCount(-1).state(IndexStatus.State.EMPTY).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should have all State enum values")
        void testStateEnum() {
            assertThat(IndexStatus.State.values()).containsExactly(IndexStatus.State.READY, IndexStatus.State.INDEXING,
                    IndexStatus.State.EMPTY, IndexStatus.State.ERROR);
        }
    }
}
