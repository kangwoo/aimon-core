package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * The fourth token counter, read untyped because this SDK version does not model it.
 *
 * <p>
 * These tests prove the parser, not the field name: {@code output_tokens_details.thinking_tokens} comes from the
 * vendor's documentation and has not been seen on a live response in this run. If the name is wrong the counter reads
 * zero and nothing else changes, which is the property the degradation cases below pin.
 */
@DisplayName("AnthropicUsages - the thinking token counter")
@ExtendWith(MockitoExtension.class)
class AnthropicUsageTest {

    @Mock
    private AnthropicClient mockAnthropicClient;

    @Mock
    private MessageService mockMessageService;

    private static Usage usage(String json) {
        return read(json, Usage.class);
    }

    private static MessageDeltaUsage deltaUsage(String json) {
        return read(json, MessageDeltaUsage.class);
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(json, e);
        }
    }

    @Test
    @DisplayName("the counter is read from a blocking usage document")
    void blockingUsageCarriesTheCounter() {
        assertThat(AnthropicUsages.thinkingTokens(usage("""
                {"input_tokens":10,"output_tokens":100,"output_tokens_details":{"thinking_tokens":60}}""")))
                .isEqualTo(60);
    }

    @Test
    @DisplayName("the counter is read from a streamed message_delta usage")
    void streamingUsageCarriesTheCounter() {
        assertThat(AnthropicUsages.thinkingTokens(deltaUsage("""
                {"output_tokens":100,"output_tokens_details":{"thinking_tokens":60}}"""))).isEqualTo(60);
    }

    @Test
    @DisplayName("every unreadable shape yields zero rather than throwing")
    void unreadableShapesDegradeToZero() {
        // A counter that could not be read must not fail a turn that otherwise succeeded.
        assertThatCode(() -> {
            assertThat(AnthropicUsages.thinkingTokens(usage("""
                    {"input_tokens":10,"output_tokens":100}"""))).isZero();
            assertThat(AnthropicUsages.thinkingTokens(usage("""
                    {"input_tokens":10,"output_tokens":100,"output_tokens_details":"nope"}"""))).isZero();
            assertThat(AnthropicUsages.thinkingTokens(usage("""
                    {"input_tokens":10,"output_tokens":100,"output_tokens_details":{"thinking_tokens":"lots"}}""")))
                    .isZero();
            assertThat(AnthropicUsages.thinkingTokens(usage("""
                    {"input_tokens":10,"output_tokens":100,"output_tokens_details":{"other":1}}"""))).isZero();
            assertThat(AnthropicUsages.thinkingTokens(
                    usage("""
                            {"input_tokens":10,"output_tokens":100,"output_tokens_details":{"thinking_tokens":99999999999}}""")))
                    .isZero();
            assertThat(AnthropicUsages.thinkingTokens((Usage) null)).isZero();
            assertThat(AnthropicUsages.thinkingTokens((MessageDeltaUsage) null)).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on a blocking response the counter is reported and the total is still input + output")
    void blockingResponseReportsWithoutInflatingTheTotal() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(AnthropicFixtures.message("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                 "stop_reason":"end_turn","content":[{"type":"text","text":"hi"}],
                 "usage":{"input_tokens":10,"output_tokens":100,
                          "output_tokens_details":{"thinking_tokens":60}}}"""));

        final LlmResponse response = new AnthropicLlmClient(AnthropicConfig.builder().apiKey("k").build(),
                mockAnthropicClient)
                .sendMessage("s", List.of(Message.user("hi")), List.of(), LlmModel.builder().build());

        final TokenUsage usage = response.getTokenUsage();
        assertThat(usage.getReasoningTokens()).isEqualTo(60);
        // Thinking tokens are billed as output tokens, so they are contained in the completion count rather than
        // added to the total. Adding them would double-count.
        assertThat(usage.getTotalTokens()).isEqualTo(110);
        assertThat(usage.getCompletionTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("on a stream the counter comes off the final message_delta, and the total is unchanged")
    void streamedResponseReportsWithoutInflatingTheTotal() {
        final ChunkAggregator aggregator = new ChunkAggregator();
        new AnthropicStreamingMapper(LlmStreamSink.discarding(), aggregator, "Anthropic", (s, m, a) -> {
        }, false).consume(List.of(event("""
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
                 "model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10,"output_tokens":0}}}"""), event("""
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                 "usage":{"output_tokens":100,"output_tokens_details":{"thinking_tokens":60}}}"""), event("""
                {"type":"message_stop"}""")).stream());

        final TokenUsage usage = aggregator.toLlmResponse().getTokenUsage();
        assertThat(usage.getReasoningTokens()).isEqualTo(60);
        assertThat(usage.getTotalTokens()).isEqualTo(110);
    }

    @Test
    @DisplayName("a usage document without the breakdown reports zero reasoning tokens and nothing else changes")
    void missingBreakdownIsSilent() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(AnthropicFixtures.message("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                 "stop_reason":"end_turn","content":[{"type":"text","text":"hi"}],
                 "usage":{"input_tokens":10,"output_tokens":100}}"""));

        final TokenUsage usage = new AnthropicLlmClient(AnthropicConfig.builder().apiKey("k").build(),
                mockAnthropicClient)
                .sendMessage("s", List.of(Message.user("hi")), List.of(), LlmModel.builder().build()).getTokenUsage();

        assertThat(usage.getReasoningTokens()).isZero();
        assertThat(usage.getTotalTokens()).isEqualTo(110);
    }

    private static RawMessageStreamEvent event(String json) {
        return AnthropicFixtures.event(json);
    }
}
