package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The point of this type is that "absent" and "present and false" are different, and that each hint has its own default
 * — three of the four differ from what a naive {@code boolean} field would give. Those defaults are the tests worth
 * having: getting {@code destructiveHint} backwards would make every annotation-less tool look harmless.
 */
@DisplayName("McpToolAnnotations")
class McpToolAnnotationsTest {

    @Nested
    @DisplayName("spec defaults when a hint is absent")
    class Defaults {

        @Test
        @DisplayName("empty() answers every hint with the MCP default, which is the conservative reading")
        void emptyUsesSpecDefaults() {
            final McpToolAnnotations annotations = McpToolAnnotations.empty();

            assertThat(annotations.isEmpty()).isTrue();
            assertThat(annotations.isReadOnly()).as("readOnlyHint defaults to false").isFalse();
            assertThat(annotations.isDestructive()).as("destructiveHint defaults to true").isTrue();
            assertThat(annotations.isIdempotent()).as("idempotentHint defaults to false").isFalse();
            assertThat(annotations.isOpenWorld()).as("openWorldHint defaults to true").isTrue();
        }

        @Test
        @DisplayName("an absent hint stays distinguishable from one the server explicitly set to its default")
        void absentIsNotTheSameAsSet() {
            final McpToolAnnotations explicit = McpToolAnnotations.builder().destructiveHint(true).build();

            assertThat(McpToolAnnotations.empty().getDestructiveHint()).isEmpty();
            assertThat(explicit.getDestructiveHint()).contains(true);
            assertThat(explicit.isEmpty()).isFalse();
            assertThat(explicit.isDestructive()).isEqualTo(McpToolAnnotations.empty().isDestructive());
        }
    }

    @Test
    @DisplayName("a hint the server sent is reported as sent")
    void hintsRoundTrip() {
        final McpToolAnnotations annotations = McpToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                .idempotentHint(true).openWorldHint(false).build();

        assertThat(annotations.getReadOnlyHint()).contains(true);
        assertThat(annotations.getDestructiveHint()).contains(false);
        assertThat(annotations.getIdempotentHint()).contains(true);
        assertThat(annotations.getOpenWorldHint()).contains(false);

        assertThat(annotations.isReadOnly()).isTrue();
        assertThat(annotations.isDestructive()).isFalse();
        assertThat(annotations.isIdempotent()).isTrue();
        assertThat(annotations.isOpenWorld()).isFalse();
    }

    @Test
    @DisplayName("passing null to a builder setter leaves the hint absent rather than storing a value")
    void nullSetterLeavesHintAbsent() {
        final McpToolAnnotations annotations = McpToolAnnotations.builder().readOnlyHint(null).build();

        assertThat(annotations.getReadOnlyHint()).isEmpty();
        assertThat(annotations.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("equality is by hint values, so absent never equals present")
    void equality() {
        assertThat(McpToolAnnotations.builder().readOnlyHint(true).build())
                .isEqualTo(McpToolAnnotations.builder().readOnlyHint(true).build())
                .hasSameHashCodeAs(McpToolAnnotations.builder().readOnlyHint(true).build())
                .isNotEqualTo(McpToolAnnotations.builder().readOnlyHint(false).build())
                .isNotEqualTo(McpToolAnnotations.empty());
    }
}
