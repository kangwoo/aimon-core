package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MigrationResult")
class MigrationResultTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with valid values stores all fields")
        void builderWithValidValues() {
            MigrationResult result = MigrationResult.builder().migratedCount(3).skippedCount(2).durationMs(1500L)
                    .errors(List.of("error1")).build();

            assertThat(result.getMigratedCount()).isEqualTo(3);
            assertThat(result.getSkippedCount()).isEqualTo(2);
            assertThat(result.getDurationMs()).isEqualTo(1500L);
            assertThat(result.getErrors()).containsExactly("error1");
        }

        @Test
        @DisplayName("empty() factory yields all zeros")
        void emptyFactory() {
            MigrationResult result = MigrationResult.empty();

            assertThat(result.getMigratedCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getDurationMs()).isZero();
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative migratedCount throws")
        void negativeMigrated() {
            assertThatThrownBy(() -> MigrationResult.builder().migratedCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative skippedCount throws")
        void negativeSkipped() {
            assertThatThrownBy(() -> MigrationResult.builder().skippedCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative durationMs throws")
        void negativeDuration() {
            assertThatThrownBy(() -> MigrationResult.builder().durationMs(-1L).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("errors list is unmodifiable")
        void errorsImmutable() {
            MigrationResult result = MigrationResult.builder().errors(new ArrayList<>(List.of("e"))).build();

            assertThatThrownBy(() -> result.getErrors().add("e2")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
