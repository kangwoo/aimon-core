package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TokenUsage}'s fourth field.
 *
 * <p>
 * The type had no test class of its own before this one — its only coverage was indirect, through
 * {@code ModelPriceTest}
 * and {@code MeteringLlmClientTest}. A field that a provider fills and a cost table deliberately ignores needs its own.
 */
@DisplayName("TokenUsage - reasoning tokens")
class TokenUsageTest {

    @Test
    @DisplayName("the three-argument factory leaves reasoning tokens at zero")
    void threeArgFactoryDefaultsToZero() {
        assertThat(TokenUsage.of(100, 50, 150).getReasoningTokens()).isZero();
        assertThat(TokenUsage.empty().getReasoningTokens()).isZero();
    }

    @Test
    @DisplayName("the four-argument factory round-trips every counter")
    void fourArgFactoryRoundTrips() {
        final TokenUsage usage = TokenUsage.of(100, 50, 150, 30);

        assertThat(usage.getPromptTokens()).isEqualTo(100);
        assertThat(usage.getCompletionTokens()).isEqualTo(50);
        assertThat(usage.getTotalTokens()).isEqualTo(150);
        assertThat(usage.getReasoningTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("add sums the fourth counter like the other three")
    void addSumsReasoningTokens() {
        final TokenUsage sum = TokenUsage.of(100, 50, 150, 30).add(TokenUsage.of(80, 40, 120, 10));

        assertThat(sum).isEqualTo(TokenUsage.of(180, 90, 270, 40));
    }

    @Test
    @DisplayName("a negative reasoning count is rejected")
    void negativeReasoningTokensRejected() {
        assertThatThrownBy(() -> TokenUsage.of(100, 50, 150, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reasoning tokens cannot be negative");
    }

    @Test
    @DisplayName("reasoning tokens are NOT constrained to be at most the completion tokens")
    void containmentIsDocumentedNotEnforced() {
        // Every provider integrated so far reports containment, and this deliberately does not enforce it: the value
        // is filled by the *server*, and a new throwing cross-field check on it would turn an accounting surprise
        // into a failed LLM call.
        assertThat(TokenUsage.of(100, 50, 150, 999).getReasoningTokens()).isEqualTo(999);
    }

    @Test
    @DisplayName("a usage carrying reasoning tokens is not equal to one without")
    void reasoningTokensParticipateInEquality() {
        assertThat(TokenUsage.of(100, 50, 150, 30)).isNotEqualTo(TokenUsage.of(100, 50, 150));
        assertThat(TokenUsage.of(100, 50, 150, 30)).isEqualTo(TokenUsage.of(100, 50, 150, 30));
        assertThat(TokenUsage.of(100, 50, 150, 30)).hasSameHashCodeAs(TokenUsage.of(100, 50, 150, 30));
        assertThat(TokenUsage.of(100, 50, 150, 30).toString()).contains("reasoning=30");
    }
}
