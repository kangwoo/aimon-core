package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Usage;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ServiceTier;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * The prompt-cache counters and the applied service tier, read off Anthropic's usage document.
 *
 * <p>
 * Anthropic's {@code input_tokens} excludes the cache, so the adapter has to add the two cache counters back for
 * {@link TokenUsage#getPromptTokens()} to be the whole input context — the number a context-length price band is
 * selected by.
 */
@DisplayName("AnthropicUsages - prompt cache and service tier")
class AnthropicCacheAndTierUsageTest {

    private static Usage usage(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, Usage.class);
        } catch (Exception e) {
            throw new IllegalStateException(json, e);
        }
    }

    @Test
    @DisplayName("cache reads and writes are added back to input_tokens, and reported as subsets of the prompt")
    void promptIsTheWholeInputContext() {
        final TokenUsage usage = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":1000,"cache_read_input_tokens":270000,"cache_creation_input_tokens":1001,
                 "output_tokens":200}"""));

        assertThat(usage.getPromptTokens()).isEqualTo(272001);
        assertThat(usage.getCacheReadTokens()).hasValue(270000);
        assertThat(usage.getCacheWriteTokens()).hasValue(1001);
        assertThat(usage.getTotalTokens()).isEqualTo(272201);
    }

    @Test
    @DisplayName("reported zeros are measured zeros")
    void zeroCountersAreMeasured() {
        final TokenUsage usage = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":5}"""));

        assertThat(usage.getPromptTokens()).isEqualTo(10);
        assertThat(usage.getCacheReadTokens()).hasValue(0);
        assertThat(usage.getCacheWriteTokens()).hasValue(0);
    }

    @Test
    @DisplayName("absent or null cache counters stay unmeasured, not zero")
    void absentCountersAreUnmeasured() {
        final TokenUsage absent = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"output_tokens":5}"""));
        final TokenUsage nulls = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"cache_read_input_tokens":null,"cache_creation_input_tokens":null,
                 "output_tokens":5}"""));

        assertThat(absent.getCacheReadTokens()).isEmpty();
        assertThat(absent.getCacheWriteTokens()).isEmpty();
        assertThat(nulls.getCacheReadTokens()).isEmpty();
        assertThat(nulls.getCacheWriteTokens()).isEmpty();
    }

    @Test
    @DisplayName("one-hour TTL writes withhold the cache-write count but still count toward the prompt")
    void longTtlWritesCannotBePricedWithOneRate() {
        final TokenUsage usage = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"cache_read_input_tokens":0,"cache_creation_input_tokens":300,
                 "cache_creation":{"ephemeral_5m_input_tokens":100,"ephemeral_1h_input_tokens":200},
                 "output_tokens":5}"""));

        assertThat(usage.getPromptTokens()).isEqualTo(310);
        assertThat(usage.getCacheReadTokens()).hasValue(0);
        assertThat(usage.getCacheWriteTokens()).isEmpty();
    }

    @Test
    @DisplayName("five-minute-only writes keep the cache-write count")
    void shortTtlWritesKeepTheCount() {
        final TokenUsage usage = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"cache_creation_input_tokens":100,
                 "cache_creation":{"ephemeral_5m_input_tokens":100,"ephemeral_1h_input_tokens":0},
                 "output_tokens":5}"""));

        assertThat(usage.getCacheWriteTokens()).hasValue(100);
    }

    @Test
    @DisplayName("thinking tokens remain a subset of the output")
    void thinkingStaysInsideTheOutput() {
        final TokenUsage usage = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":10,"output_tokens":100,"output_tokens_details":{"thinking_tokens":60}}"""));

        assertThat(usage.getCompletionTokens()).isEqualTo(100);
        assertThat(usage.getReasoningTokens()).isEqualTo(60);
        assertThat(usage.getTotalTokens()).isEqualTo(110);
    }

    @Test
    @DisplayName("standard and batch are normalized; priority is preserved, not mapped to fast")
    void serviceTierMapping() {
        assertThat(AnthropicUsages.serviceTier(usage("""
                {"input_tokens":1,"output_tokens":1,"service_tier":"standard"}"""))).isEqualTo(ServiceTier.STANDARD);
        assertThat(AnthropicUsages.serviceTier(usage("""
                {"input_tokens":1,"output_tokens":1,"service_tier":"batch"}"""))).isEqualTo(ServiceTier.BATCH);
        final ServiceTier priority = AnthropicUsages.serviceTier(usage("""
                {"input_tokens":1,"output_tokens":1,"service_tier":"priority"}"""));
        assertThat(priority.getValue()).isEqualTo("priority");
        assertThat(priority.isNormalized()).isFalse();
        assertThat(AnthropicUsages.serviceTier(usage("""
                {"input_tokens":1,"output_tokens":1}"""))).isNull();
    }

    @Test
    @DisplayName("streaming reports the same usage, model and tier as the blocking document")
    void streamingMatchesBlocking() {
        final ChunkAggregator aggregator = new ChunkAggregator();
        new AnthropicStreamingMapper(LlmStreamSink.discarding(), aggregator, "Anthropic", (s, m, a) -> {
        }, false).consume(Stream.of(AnthropicFixtures.event("""
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
                 "model":"claude-x-20990101","content":[],"stop_reason":null,"stop_sequence":null,
                 "usage":{"input_tokens":1000,"cache_read_input_tokens":270000,"cache_creation_input_tokens":1001,
                          "output_tokens":1,"service_tier":"batch"}}}"""), AnthropicFixtures.event("""
                {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},
                 "usage":{"output_tokens":200}}"""), AnthropicFixtures.event("""
                {"type":"message_stop"}""")));

        final LlmResponse response = aggregator.toLlmResponse();
        final TokenUsage blocking = AnthropicUsages.toTokenUsage(usage("""
                {"input_tokens":1000,"cache_read_input_tokens":270000,"cache_creation_input_tokens":1001,
                 "output_tokens":200}"""));
        assertThat(response.getTokenUsage()).isEqualTo(blocking);
        assertThat(response.getServedModel()).contains("claude-x-20990101");
        assertThat(response.getServiceTier()).contains(ServiceTier.BATCH);
    }
}
