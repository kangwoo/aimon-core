package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.logging.LoggingLlmClient;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.tagging.BoundMetadataLlmClient;
import at.aimon.core.llm.tagging.TaggingLlmClient;
import at.aimon.core.llm.usage.LlmUsageRecorder;
import at.aimon.core.llm.usage.MeteringLlmClient;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.TracingLlmClient;

/**
 * Verifies that the five {@link LlmClient} decorators forward {@link LlmClient#getDefaultModelName()} to their
 * delegate rather than inheriting the interface's empty default.
 *
 * <p>
 * A decorator that forgets this is the defect of issue #45 reintroduced invisibly: the outermost client answers
 * {@code Optional.empty()}, everything outside it goes back to reporting no model for any request that did not name
 * one, and nothing fails — the empty answer is a legal answer for a client that genuinely has no default. Modelled on
 * {@link LlmClientCancellationForwardingTest}, which binds the same five decorators for the same reason.
 */
@DisplayName("LlmClient decorators - getDefaultModelName forwarding")
class LlmClientDefaultModelNameForwardingTest {

    private static final String MODEL = "gpt-4o";

    @Test
    @DisplayName("every decorator forwards the delegate's default model name")
    void everyDecoratorForwardsTheDefaultModelName() {
        final StubLlmClient delegate = new StubLlmClient();

        assertThat(new TaggingLlmClient(delegate).getDefaultModelName()).contains(MODEL);
        assertThat(new BoundMetadataLlmClient(delegate, LlmCallMetadata.empty()).getDefaultModelName()).contains(MODEL);
        assertThat(new LoggingLlmClient(delegate).getDefaultModelName()).contains(MODEL);
        assertThat(new MeteringLlmClient(delegate, LlmUsageRecorder.NOOP).getDefaultModelName()).contains(MODEL);
        assertThat(new TracingLlmClient(delegate, Tracer.noop()).getDefaultModelName()).contains(MODEL);
    }

    @Test
    @DisplayName("a client that declares no default answers empty rather than guessing")
    void aClientWithNoDefaultAnswersEmpty() {
        // The interface default, taken by a client that overrides nothing. A router or a recorded fixture has no
        // client-wide model, and saying so is the correct answer -- which is exactly why a decorator's failure to
        // forward is invisible without the test above.
        final LlmClient bare = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                return LlmResponse.text("ok");
            }

            @Override
            public String getProviderName() {
                return "Bare";
            }
        };

        assertThat(bare.getDefaultModelName()).isEmpty();
    }

    /** A delegate whose only interesting property is that it names a default model. */
    private static class StubLlmClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("ok");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            sink.accept(LlmStreamChunk.streamEnd(0, TokenUsage.of(0, 0, 0), Optional.of("stop")));
            return LlmResponse.text("ok");
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }

        @Override
        public Optional<String> getDefaultModelName() {
            return Optional.of(MODEL);
        }
    }
}
