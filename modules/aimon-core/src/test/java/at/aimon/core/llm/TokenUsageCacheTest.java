package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The prompt-cache counters on {@link TokenUsage}: unmeasured is not zero, and sums stay honest about it.
 */
class TokenUsageCacheTest {

    @Test
    @DisplayName("the existing factories leave both cache counters unmeasured")
    void factoriesLeaveCacheUnmeasured() {
        assertThat(TokenUsage.of(10, 5, 15).getCacheReadTokens()).isEmpty();
        assertThat(TokenUsage.of(10, 5, 15, 2).getCacheWriteTokens()).isEmpty();
        assertThat(TokenUsage.empty().getCacheReadTokens()).isEmpty();
    }

    @Test
    @DisplayName("withCacheTokens keeps every other counter and distinguishes 0 from unmeasured")
    void withCacheTokens() {
        final TokenUsage usage = TokenUsage.of(100, 50, 150, 20).withCacheTokens(0, null);

        assertThat(usage.getPromptTokens()).isEqualTo(100);
        assertThat(usage.getReasoningTokens()).isEqualTo(20);
        assertThat(usage.getCacheReadTokens()).hasValue(0);
        assertThat(usage.getCacheWriteTokens()).isEmpty();
        assertThat(usage).isNotEqualTo(TokenUsage.of(100, 50, 150, 20));
    }

    @Test
    @DisplayName("negative cache counters are rejected")
    void negativeRejected() {
        assertThatThrownBy(() -> TokenUsage.of(10, 5, 15).withCacheTokens(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenUsage.of(10, 5, 15).withCacheTokens(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("add sums measured counters, and an unmeasured side makes the sum unmeasured")
    void addPropagatesUnknown() {
        final TokenUsage measured = TokenUsage.of(100, 10, 110).withCacheTokens(40, 5);

        assertThat(measured.add(measured).getCacheReadTokens()).hasValue(80);
        assertThat(measured.add(measured).getCacheWriteTokens()).hasValue(10);
        assertThat(measured.add(TokenUsage.of(100, 10, 110)).getCacheReadTokens()).isEmpty();
    }

    @Test
    @DisplayName("empty() + empty() is still empty(), and isZero ignores measured-zero cache counters")
    void emptyPlusEmptyStaysEmpty() {
        assertThat(TokenUsage.empty().add(TokenUsage.empty())).isEqualTo(TokenUsage.empty());
        assertThat(TokenUsage.empty().withCacheTokens(0, 0).isZero()).isTrue();
        assertThat(TokenUsage.of(1, 0, 1).isZero()).isFalse();
    }

    @Test
    @DisplayName("an overflowing cache sum becomes unmeasured instead of failing add()")
    void overflowingCacheSumIsUnmeasured() {
        final TokenUsage big = TokenUsage.of(Integer.MAX_VALUE / 2, 0, Integer.MAX_VALUE / 2)
                .withCacheTokens(Integer.MAX_VALUE, 0);

        assertThat(big.add(big).getCacheReadTokens()).isEmpty();
        assertThat(big.add(big).getCacheWriteTokens()).hasValue(0);
    }

    @Test
    @DisplayName("a side with no prompt tokens (the empty() seed) has trivially zero cache tokens")
    void emptySeedDoesNotPoisonTheSum() {
        final TokenUsage measured = TokenUsage.of(100, 10, 110).withCacheTokens(40, 5);

        assertThat(TokenUsage.empty().add(measured).getCacheReadTokens()).hasValue(40);
        assertThat(TokenUsage.empty().add(measured).getCacheWriteTokens()).hasValue(5);
    }
}
