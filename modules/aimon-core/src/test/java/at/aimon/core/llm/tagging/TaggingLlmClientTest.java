package at.aimon.core.llm.tagging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
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

class TaggingLlmClientTest {

    private final StubLlmClient delegate = new StubLlmClient();
    private final TaggingLlmClient tagging = new TaggingLlmClient(delegate);

    @AfterEach
    void tearDown() {
        LlmCallMetadataHolder.clear();
    }

    @Test
    @DisplayName("scope 가 없으면 4-arg 호출은 empty metadata 로 위임되어야 한다")
    void noScopePassesEmptyMetadata() {
        tagging.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build());

        assertThat(delegate.lastMetadata.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("scope 안에서 4-arg 호출은 ambient metadata 가 자동 부착되어야 한다")
    void ambientIsAttachedToFourArgOverload() {
        final LlmCallMetadata ambient = LlmCallMetadata.builder().component("memory").tag("tenant", "acme").build();

        try (LlmCallMetadataHolder.Scope ignored = LlmCallMetadataHolder.push(ambient)) {
            tagging.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build());
        }

        assertThat(delegate.lastMetadata.getComponent()).contains("memory");
        assertThat(delegate.lastMetadata.getTags()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("caller-supplied metadata 가 ambient 보다 우선되어야 한다")
    void callerWinsOverAmbient() {
        final LlmCallMetadata ambient = LlmCallMetadata.builder().component("ambient-component").feature("ambient-feat")
                .tag("tenant", "ambient").build();
        final LlmCallMetadata caller = LlmCallMetadata.builder().component("caller-component").tag("tenant", "caller")
                .build();

        try (LlmCallMetadataHolder.Scope ignored = LlmCallMetadataHolder.push(ambient)) {
            tagging.sendMessage("sys", List.of(), List.of(), LlmModel.builder().build(), caller);
        }

        // caller's component wins, caller's tag wins, ambient's feature fills in (caller didn't set it)
        assertThat(delegate.lastMetadata.getComponent()).contains("caller-component");
        assertThat(delegate.lastMetadata.getTags()).containsEntry("tenant", "caller");
        assertThat(delegate.lastMetadata.getFeature()).contains("ambient-feat");
    }

    @Test
    @DisplayName("streaming 호출도 ambient 가 부착되어야 한다")
    void streamingAttachesAmbient() {
        final LlmCallMetadata ambient = LlmCallMetadata.builder().component("memory").build();

        try (LlmCallMetadataHolder.Scope ignored = LlmCallMetadataHolder.push(ambient)) {
            tagging.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    });
        }

        assertThat(delegate.lastMetadata.getComponent()).contains("memory");
    }

    @Test
    @DisplayName("provider 이름은 delegate 에 위임되어야 한다")
    void delegatesProviderMetadata() {
        assertThat(tagging.getProviderName()).isEqualTo("StubProvider");
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
