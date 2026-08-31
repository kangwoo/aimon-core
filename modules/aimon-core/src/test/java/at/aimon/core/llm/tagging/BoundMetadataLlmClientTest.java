package at.aimon.core.llm.tagging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

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

class BoundMetadataLlmClientTest {

    private final StubLlmClient delegate = new StubLlmClient();

    @Test
    @DisplayName("4-arg 호출은 bound metadata 가 그대로 부착되어야 한다")
    void fourArgUsesBoundMetadata() {
        final LlmCallMetadata bound = LlmCallMetadata.builder().component("memory").feature("summarize").build();
        final BoundMetadataLlmClient client = new BoundMetadataLlmClient(delegate, bound);

        client.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build());

        assertThat(delegate.lastMetadata.getComponent()).contains("memory");
        assertThat(delegate.lastMetadata.getFeature()).contains("summarize");
    }

    @Test
    @DisplayName("caller metadata 가 bound 보다 우선되고 미설정 필드는 bound 가 채워야 한다")
    void callerWinsBoundFillsUnset() {
        final LlmCallMetadata bound = LlmCallMetadata.builder().component("memory").feature("summarize")
                .tag("layer", "memory").build();
        final LlmCallMetadata caller = LlmCallMetadata.builder().component("memory-rebuild").tag("op", "compact")
                .build();
        final BoundMetadataLlmClient client = new BoundMetadataLlmClient(delegate, bound);

        client.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build(), caller);

        assertThat(delegate.lastMetadata.getComponent()).contains("memory-rebuild");
        assertThat(delegate.lastMetadata.getFeature()).contains("summarize");
        assertThat(delegate.lastMetadata.getTags()).containsEntry("op", "compact").containsEntry("layer", "memory");
    }

    @Test
    @DisplayName("streaming 호출도 bound metadata 가 부착되어야 한다")
    void streamingUsesBoundMetadata() {
        final LlmCallMetadata bound = LlmCallMetadata.builder().component("memory").build();
        final BoundMetadataLlmClient client = new BoundMetadataLlmClient(delegate, bound);

        client.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                });

        assertThat(delegate.lastMetadata.getComponent()).contains("memory");
    }

    @Test
    @DisplayName("provider 이름은 delegate 에 위임되어야 한다")
    void delegatesProviderMetadata() {
        final BoundMetadataLlmClient client = new BoundMetadataLlmClient(delegate, LlmCallMetadata.empty());

        assertThat(client.getProviderName()).isEqualTo("StubProvider");
    }

    @Test
    @DisplayName("delegate 또는 bound 가 null 이면 NullPointerException 이 발생해야 한다")
    void rejectsNulls() {
        assertThatThrownBy(() -> new BoundMetadataLlmClient(null, LlmCallMetadata.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BoundMetadataLlmClient(delegate, null)).isInstanceOf(NullPointerException.class);
    }

    private static final class StubLlmClient implements LlmClient {
        LlmCallMetadata lastMetadata = LlmCallMetadata.empty();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.of("ok", List.of(), TokenUsage.of(1, 1, 2));
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
            this.lastMetadata = metadata;
            sink.accept(LlmStreamChunk.streamEnd(0, TokenUsage.of(1, 1, 2), Optional.empty()));
            return LlmResponse.of("ok", List.of(), TokenUsage.of(1, 1, 2));
        }

        @Override
        public String getProviderName() {
            return "StubProvider";
        }

    }
}
