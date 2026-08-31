package at.aimon.core.llm.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

class LoggingLlmClientTest {

    private StubLlmClient delegate;
    private LoggingLlmClient logging;

    @BeforeEach
    void setUp() {
        delegate = new StubLlmClient();
        logging = new LoggingLlmClient(delegate);
    }

    @Test
    @DisplayName("sendMessage 호출은 delegate 로 전달되고 응답이 그대로 반환되어야 한다")
    void sendMessageDelegates() {
        final LlmResponse response = logging.sendMessage("sys", List.of(), List.of(),
                LlmModel.builder().name("gpt-4").build());

        assertThat(delegate.nonStreamingCalls).isEqualTo(1);
        assertThat(response.getTextContent()).isEqualTo("ok");
        assertThat(response.getTokenUsage()).isEqualTo(TokenUsage.of(10, 5, 15));
    }

    @Test
    @DisplayName("metadata variant 도 delegate 로 전달되어야 한다")
    void sendMessageWithMetadataDelegates() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("orca-agent").build();

        logging.sendMessage("sys", List.of(), List.of(), LlmModel.builder().name("gpt-4").build(), metadata);

        assertThat(delegate.nonStreamingCalls).isEqualTo(1);
        assertThat(delegate.lastMetadata.getComponent()).contains("orca-agent");
    }

    @Test
    @DisplayName("streaming 호출은 delegate 의 native streaming 으로 전달되어 토큰 청크가 보존되어야 한다")
    void streamingPassesThroughDelegateNativeStreaming() {
        final List<LlmStreamChunk> received = new ArrayList<>();
        final LlmStreamSink sink = received::add;

        final LlmResponse response = logging.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(),
                LlmModel.builder().name("gpt-4").build(), LlmCallMetadata.empty(), LlmStreamingOptions.defaults(),
                sink);

        assertThat(delegate.streamingCalls).isEqualTo(1);
        assertThat(delegate.nonStreamingCalls).isEqualTo(0);
        assertThat(received).hasSize(3);
        assertThat(received.get(0).getKind()).isEqualTo(LlmStreamChunk.Kind.TEXT_DELTA);
        assertThat(received.get(2).getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
        assertThat(response.getTextContent()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("delegate 가 던진 예외는 로깅 후 그대로 전파되어야 한다")
    void exceptionsArePropagated() {
        delegate.failWith = new RuntimeException("boom");

        assertThatThrownBy(() -> logging.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build()))
                .isInstanceOf(RuntimeException.class).hasMessage("boom");
    }

    @Test
    @DisplayName("provider 이름은 delegate 에 위임되어야 한다")
    void delegatesProviderMetadata() {
        assertThat(logging.getProviderName()).isEqualTo("StubProvider");
    }

    @Test
    @DisplayName("LlmLoggingOptions.defaults 는 body logging 비활성, 200자 preview 여야 한다")
    void defaultsAreSafe() {
        final LlmLoggingOptions defaults = LlmLoggingOptions.defaults();

        assertThat(defaults.isLogBodies()).isFalse();
        assertThat(defaults.getMaxPreviewChars()).isEqualTo(200);
    }

    @Test
    @DisplayName("maxPreviewChars 가 음수이면 IllegalArgumentException 이 발생해야 한다")
    void rejectsNegativePreviewLength() {
        assertThatThrownBy(() -> LlmLoggingOptions.builder().maxPreviewChars(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class StubLlmClient implements LlmClient {
        int streamingCalls;
        int nonStreamingCalls;
        LlmCallMetadata lastMetadata = LlmCallMetadata.empty();
        RuntimeException failWith;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            nonStreamingCalls++;
            if (failWith != null) {
                throw failWith;
            }
            return LlmResponse.of("ok", List.of(), TokenUsage.of(10, 5, 15));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            this.lastMetadata = metadata;
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            streamingCalls++;
            sink.accept(LlmStreamChunk.textDelta(0, "Hel"));
            sink.accept(LlmStreamChunk.textDelta(1, "lo"));
            sink.accept(LlmStreamChunk.streamEnd(2, TokenUsage.of(10, 5, 15), Optional.of("stop")));
            return LlmResponse.of("Hello", List.of(), TokenUsage.of(10, 5, 15));
        }

        @Override
        public String getProviderName() {
            return "StubProvider";
        }

    }
}
