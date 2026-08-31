package at.aimon.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TracePayloadPolicy (TRACE-02)")
class TracePayloadPolicyTest {

    @Test
    @DisplayName("summaryOnly captures no content")
    void summaryOnlyCapturesNoContent() {
        final TracePayloadPolicy policy = TracePayloadPolicy.summaryOnly();
        assertThat(policy.capturesContent()).isFalse();
        assertThat(policy.getMaxChars()).isEqualTo(TracePayloadPolicy.DEFAULT_MAX_CHARS);
    }

    @Test
    @DisplayName("full() captures content with the default cap")
    void fullUsesDefaultCap() {
        final TracePayloadPolicy policy = TracePayloadPolicy.full();
        assertThat(policy.capturesContent()).isTrue();
        assertThat(policy.getMaxChars()).isEqualTo(TracePayloadPolicy.DEFAULT_MAX_CHARS);
    }

    @Test
    @DisplayName("full(maxChars) captures content with a custom cap")
    void fullUsesCustomCap() {
        final TracePayloadPolicy policy = TracePayloadPolicy.full(16);
        assertThat(policy.capturesContent()).isTrue();
        assertThat(policy.getMaxChars()).isEqualTo(16);
    }

    @Test
    @DisplayName("full(maxChars) rejects a non-positive cap")
    void fullRejectsNonPositiveCap() {
        assertThatThrownBy(() -> TracePayloadPolicy.full(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TracePayloadPolicy.full(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("truncate leaves content at or below the cap unchanged")
    void truncateLeavesShortContentUnchanged() {
        final TracePayloadPolicy policy = TracePayloadPolicy.full(8);
        assertThat(policy.truncate("12345678")).isEqualTo("12345678"); // exactly at cap
        assertThat(policy.truncate("hi")).isEqualTo("hi");
    }

    @Test
    @DisplayName("truncate cuts over-cap content and appends a marker")
    void truncateCutsLongContent() {
        final TracePayloadPolicy policy = TracePayloadPolicy.full(5);
        final String result = policy.truncate("abcdefghij"); // 10 chars, cap 5
        assertThat(result).isEqualTo("abcde…(truncated 5 chars)");
    }

    @Test
    @DisplayName("truncate is null-safe")
    void truncateIsNullSafe() {
        assertThat(TracePayloadPolicy.full(5).truncate(null)).isNull();
    }

    @Test
    @DisplayName("summaryOnly is a shared singleton; value semantics hold for full policies")
    void equalitySemantics() {
        assertThat(TracePayloadPolicy.summaryOnly()).isSameAs(TracePayloadPolicy.summaryOnly());
        assertThat(TracePayloadPolicy.full(100)).isEqualTo(TracePayloadPolicy.full(100));
        assertThat(TracePayloadPolicy.full(100)).isNotEqualTo(TracePayloadPolicy.full(200));
        assertThat(TracePayloadPolicy.full(100)).isNotEqualTo(TracePayloadPolicy.summaryOnly());
    }
}
