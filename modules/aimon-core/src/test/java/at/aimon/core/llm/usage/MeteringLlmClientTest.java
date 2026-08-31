package at.aimon.core.llm.usage;

import static org.assertj.core.api.Assertions.assertThat;

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

class MeteringLlmClientTest {

    private RecordingRecorder recorder;
    private StubLlmClient delegate;
    private MeteringLlmClient metering;

    @BeforeEach
    void setUp() {
        recorder = new RecordingRecorder();
        delegate = new StubLlmClient();
        metering = new MeteringLlmClient(delegate, recorder);
    }

    @Test
    @DisplayName("metadata 를 전달받으면 recorder 에 attribution 이 기록되어야 한다")
    void recordsAttributedUsage() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("orca-agent").feature("react-loop")
                .build();

        metering.sendMessage("sys", List.of(), List.of(), LlmModel.builder().name("gpt-4").build(), metadata);

        assertThat(recorder.events).hasSize(1);
        final RecordedEvent event = recorder.events.get(0);
        assertThat(event.provider).isEqualTo("StubProvider");
        assertThat(event.model).isEqualTo("gpt-4");
        assertThat(event.usage).isEqualTo(TokenUsage.of(10, 5, 15));
        assertThat(event.metadata.getComponent()).contains("orca-agent");
        assertThat(event.metadata.getFeature()).contains("react-loop");
    }

    @Test
    @DisplayName("metadata 없이 호출해도 empty metadata 로 기록되어야 한다")
    void recordsEmptyMetadataWhenNotProvided() {
        metering.sendMessage("sys", List.of(), List.of(), LlmModel.builder().name("gpt-4").build());

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).metadata.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("provider 이름은 delegate 에 위임되어야 한다")
    void delegatesProviderMetadata() {
        assertThat(metering.getProviderName()).isEqualTo("StubProvider");
    }

    @Test
    @DisplayName("streaming 호출은 delegate 의 native streaming 으로 그대로 전달되어야 한다")
    void streamingPassesThroughDelegateNativeStreaming() {
        final List<LlmStreamChunk> received = new ArrayList<>();
        final LlmStreamSink sink = received::add;
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("orca-agent").build();

        final LlmResponse response = metering.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(),
                LlmModel.builder().name("gpt-4").build(), metadata, LlmStreamingOptions.defaults(), sink);

        assertThat(delegate.streamingCalls).isEqualTo(1);
        assertThat(delegate.nonStreamingCalls).isEqualTo(0);
        assertThat(received).hasSize(3);
        assertThat(received.get(0).getKind()).isEqualTo(LlmStreamChunk.Kind.TEXT_DELTA);
        assertThat(received.get(0).getTextDelta()).contains("Hel");
        assertThat(received.get(1).getKind()).isEqualTo(LlmStreamChunk.Kind.TEXT_DELTA);
        assertThat(received.get(1).getTextDelta()).contains("lo");
        assertThat(received.get(2).getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
        assertThat(response.getTextContent()).isEqualTo("Hello");

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).provider).isEqualTo("StubProvider");
        assertThat(recorder.events.get(0).model).isEqualTo("gpt-4");
        assertThat(recorder.events.get(0).metadata.getComponent()).contains("orca-agent");
    }

    private static final class StubLlmClient implements LlmClient {
        int streamingCalls;
        int nonStreamingCalls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            nonStreamingCalls++;
            return LlmResponse.of("ok", List.of(), TokenUsage.of(10, 5, 15));
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

    private static final class RecordingRecorder implements LlmUsageRecorder {
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        public void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata) {
            events.add(new RecordedEvent(provider, model, usage, metadata));
        }
    }

    private static final class RecordedEvent {
        final String provider;
        final String model;
        final TokenUsage usage;
        final LlmCallMetadata metadata;

        RecordedEvent(String provider, String model, TokenUsage usage, LlmCallMetadata metadata) {
            this.provider = provider;
            this.model = model;
            this.usage = usage;
            this.metadata = metadata;
        }
    }
}
