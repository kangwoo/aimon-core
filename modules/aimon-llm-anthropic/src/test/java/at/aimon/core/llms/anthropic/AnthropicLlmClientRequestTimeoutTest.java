package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.blocking.MessageService;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Tests for the per-request timeout safety net: when {@link LlmModel#getRequestTimeout()} is present, the provider
 * routes the SDK call through the {@code (params, RequestOptions)} overload carrying that worst-case ceiling; when
 * absent, it keeps the single-argument overload so the client-wide default timeout applies unchanged (no regression).
 */
@DisplayName("AnthropicLlmClient - Per-request timeout")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientRequestTimeoutTest {

    @Mock
    private AnthropicClient mockAnthropicClient;

    @Mock
    private MessageService mockMessageService;

    private AnthropicLlmClient createClientWithMock() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514").build();
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    @Test
    @DisplayName("blocking create() carries the per-request timeout as RequestOptions when set")
    void blockingCreateCarriesRequestTimeout() {
        // Stub the two-arg create() to throw a sentinel so we don't have to build a valid SDK Message; the invocation
        // is still recorded for capture/verify.
        RuntimeException sentinel = new RuntimeException("create-with-options-invoked");
        when(mockMessageService.create(any(MessageCreateParams.class), any(RequestOptions.class))).thenThrow(sentinel);

        AnthropicLlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().requestTimeout(Duration.ofSeconds(30)).build();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), model)).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockMessageService).create(any(MessageCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout()).isNotNull();
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(30));
        // Never the single-argument overload when a per-request timeout is set.
        verify(mockMessageService, never()).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("streaming createStreaming() carries the per-request timeout as RequestOptions when set")
    void streamingCarriesRequestTimeout() {
        @SuppressWarnings("unchecked")
        StreamResponse<RawMessageStreamEvent> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockMessageService.createStreaming(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenReturn(streamResponse);

        AnthropicLlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().requestTimeout(Duration.ofSeconds(90)).build();

        client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")), Collections.emptyList(),
                model, LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none());

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockMessageService).createStreaming(any(MessageCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout()).isNotNull();
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(90));
        verify(mockMessageService, never()).createStreaming(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("no per-request timeout keeps the single-argument create() (no regression)")
    void noRequestTimeoutKeepsSingleArgOverload() {
        RuntimeException sentinel = new RuntimeException("single-arg-create-invoked");
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(sentinel);

        AnthropicLlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().build();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), model)).isInstanceOf(RuntimeException.class);

        verify(mockMessageService).create(any(MessageCreateParams.class));
        verify(mockMessageService, never()).create(any(MessageCreateParams.class), any(RequestOptions.class));
    }
}
