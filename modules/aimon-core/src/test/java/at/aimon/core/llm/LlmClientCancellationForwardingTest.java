package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Verifies that the five {@link LlmClient} decorators forward the caller-supplied cooperative
 * {@link LlmCancellation} token to their delegate unchanged, instead of dropping it or substituting
 * {@link LlmCancellation#none()}.
 *
 * <p>
 * All five decorators override both cancellation overloads — the streaming
 * ({@link LlmClient#sendMessageStreaming(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmStreamingOptions,
 * at.aimon.core.llm.streaming.LlmStreamSink, LlmCancellation)}) and the non-streaming
 * ({@link LlmClient#sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmCancellation)}) path — so
 * both are exercised for each. (Forwarding on the non-streaming overload matters ahead of Phase-2 non-streaming
 * in-flight abort: without the override the {@code LlmClient} default silently drops the token.)
 *
 * <p>
 * Each test asserts same-instance forwarding ({@code isSameAs}) of the {@link LlmCancellation} argument, not
 * equality — decorators transform {@link LlmCallMetadata} (tag merging, bound defaults, span enrichment) but must
 * never touch the cancellation token.
 */
class LlmClientCancellationForwardingTest {

    @Nested
    @DisplayName("TaggingLlmClient")
    class TaggingLlmClientForwarding {

        private final CapturingLlmClient delegate = new CapturingLlmClient();
        private final TaggingLlmClient client = new TaggingLlmClient(delegate);

        @Test
        @DisplayName("forwards the same cancellation instance on the non-streaming overload")
        void forwardsCancellationNonStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), sentinel);

            assertThat(delegate.lastNonStreamingCancellation).isSameAs(sentinel);
        }

        @Test
        @DisplayName("forwards the same cancellation instance on the streaming overload")
        void forwardsCancellationStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, sentinel);

            assertThat(delegate.lastStreamingCancellation).isSameAs(sentinel);
        }
    }

    @Nested
    @DisplayName("BoundMetadataLlmClient")
    class BoundMetadataLlmClientForwarding {

        private final CapturingLlmClient delegate = new CapturingLlmClient();
        private final BoundMetadataLlmClient client = new BoundMetadataLlmClient(delegate, LlmCallMetadata.empty());

        @Test
        @DisplayName("forwards the same cancellation instance on the non-streaming overload")
        void forwardsCancellationNonStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), sentinel);

            assertThat(delegate.lastNonStreamingCancellation).isSameAs(sentinel);
        }

        @Test
        @DisplayName("forwards the same cancellation instance on the streaming overload")
        void forwardsCancellationStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, sentinel);

            assertThat(delegate.lastStreamingCancellation).isSameAs(sentinel);
        }
    }

    @Nested
    @DisplayName("LoggingLlmClient")
    class LoggingLlmClientForwarding {

        private final CapturingLlmClient delegate = new CapturingLlmClient();
        private final LoggingLlmClient client = new LoggingLlmClient(delegate);

        @Test
        @DisplayName("forwards the same cancellation instance on the non-streaming overload")
        void forwardsCancellationNonStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), sentinel);

            assertThat(delegate.lastNonStreamingCancellation).isSameAs(sentinel);
        }

        @Test
        @DisplayName("forwards the same cancellation instance on the streaming overload")
        void forwardsCancellationStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, sentinel);

            assertThat(delegate.lastStreamingCancellation).isSameAs(sentinel);
        }
    }

    @Nested
    @DisplayName("MeteringLlmClient")
    class MeteringLlmClientForwarding {

        private final CapturingLlmClient delegate = new CapturingLlmClient();
        private final MeteringLlmClient client = new MeteringLlmClient(delegate, LlmUsageRecorder.NOOP);

        @Test
        @DisplayName("forwards the same cancellation instance on the non-streaming overload")
        void forwardsCancellationNonStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), sentinel);

            assertThat(delegate.lastNonStreamingCancellation).isSameAs(sentinel);
        }

        @Test
        @DisplayName("forwards the same cancellation instance on the streaming overload")
        void forwardsCancellationStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, sentinel);

            assertThat(delegate.lastStreamingCancellation).isSameAs(sentinel);
        }
    }

    @Nested
    @DisplayName("TracingLlmClient")
    class TracingLlmClientForwarding {

        private final CapturingLlmClient delegate = new CapturingLlmClient();
        private final TracingLlmClient client = new TracingLlmClient(delegate, Tracer.noop());

        @Test
        @DisplayName("forwards the same cancellation instance on the non-streaming overload")
        void forwardsCancellationNonStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), sentinel);

            assertThat(delegate.lastNonStreamingCancellation).isSameAs(sentinel);
        }

        @Test
        @DisplayName("forwards the same cancellation instance on the streaming overload")
        void forwardsCancellationStreaming() {
            final LlmCancellation sentinel = new SentinelCancellation();

            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, sentinel);

            assertThat(delegate.lastStreamingCancellation).isSameAs(sentinel);
        }
    }

    /**
     * A trivial, distinguishable {@link LlmCancellation} instance used purely as an identity sentinel — never actually
     * cancelled, never actually registered against a real abort lever.
     */
    private static final class SentinelCancellation implements LlmCancellation {

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable abort) {
            // no-op: identity is all that matters for this test
        }
    }

    /**
     * Minimal {@link LlmClient} fake that records the exact {@link LlmCancellation} instance received on each
     * cancellation-aware overload, so tests can assert same-instance forwarding through a decorator chain.
     */
    private static final class CapturingLlmClient implements LlmClient {

        private volatile LlmCancellation lastNonStreamingCancellation;
        private volatile LlmCancellation lastStreamingCancellation;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("ok");
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            this.lastNonStreamingCancellation = cancellation;
            return LlmResponse.text("ok");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            this.lastStreamingCancellation = cancellation;
            sink.accept(LlmStreamChunk.streamEnd(0, TokenUsage.of(1, 1, 2), Optional.empty()));
            return LlmResponse.text("ok");
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }
}
