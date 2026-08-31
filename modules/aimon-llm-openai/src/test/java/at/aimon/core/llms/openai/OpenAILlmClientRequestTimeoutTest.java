package at.aimon.core.llms.openai;

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

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;

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
@DisplayName("OpenAILlmClient - Per-request timeout")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientRequestTimeoutTest {

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    private OpenAILlmClient createClientWithMock() {
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-4").build();
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    @Test
    @DisplayName("blocking create() carries the per-request timeout as RequestOptions when set")
    void blockingCreateCarriesRequestTimeout() {
        // Stub the two-arg create() to throw a sentinel; the invocation is still recorded for capture/verify.
        RuntimeException sentinel = new RuntimeException("create-with-options-invoked");
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenThrow(sentinel);

        OpenAILlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().requestTimeout(Duration.ofSeconds(30)).build();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), model)).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockChatCompletionService).create(any(ChatCompletionCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout()).isNotNull();
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(30));
        verify(mockChatCompletionService, never()).create(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("streaming createStreaming() carries the per-request timeout as RequestOptions when set")
    void streamingCarriesRequestTimeout() {
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class),
                any(RequestOptions.class))).thenReturn(streamResponse);

        OpenAILlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().requestTimeout(Duration.ofSeconds(90)).build();

        client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")), Collections.emptyList(),
                model, LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none());

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockChatCompletionService).createStreaming(any(ChatCompletionCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout()).isNotNull();
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(90));
        verify(mockChatCompletionService, never()).createStreaming(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("no per-request timeout keeps the single-argument create() (no regression)")
    void noRequestTimeoutKeepsSingleArgOverload() {
        RuntimeException sentinel = new RuntimeException("single-arg-create-invoked");
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(sentinel);

        OpenAILlmClient client = createClientWithMock();
        LlmModel model = LlmModel.builder().build();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), model)).isInstanceOf(RuntimeException.class);

        verify(mockChatCompletionService).create(any(ChatCompletionCreateParams.class));
        verify(mockChatCompletionService, never()).create(any(ChatCompletionCreateParams.class),
                any(RequestOptions.class));
    }
}
