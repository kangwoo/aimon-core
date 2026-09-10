package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Preserved behaviour 4, on the second endpoint: a per-request timeout travels as {@link RequestOptions}, and its
 * absence keeps the <strong>single-argument</strong> SDK overload so the client-wide default is untouched.
 *
 * <p>
 * The {@code never()} assertions are the load-bearing half. Passing {@code RequestOptions.none()} instead of keeping
 * the one-argument call would satisfy every positive assertion here while quietly overriding the client-wide default
 * for every request that never asked for a timeout.
 */
@DisplayName("OpenAI Responses - per-request timeout")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesRequestTimeoutTest {
    /**
     * A gpt-5-family reasoning model, meaning nothing more than that. It was {@code gpt-5.6-terra} until that name
     * got a built-in row of its own for its measured ladder; a name with its own row would keep every assertion here
     * green while quietly testing a different row from the one they are about.
     */
    private static final String A_REASONING_MODEL = "gpt-5-mini";

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private OpenAILlmClient createClientWithMock() {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model(A_REASONING_MODEL).build(),
                mockOpenAIClient);
    }

    @Test
    @DisplayName("blocking create() carries the per-request timeout when set")
    void blockingCreateCarriesRequestTimeout() {
        final RuntimeException sentinel = new RuntimeException("create-with-options-invoked");
        when(mockResponseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(sentinel);

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().requestTimeout(Duration.ofSeconds(30)).build()))
                .isInstanceOf(RuntimeException.class);

        final ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockResponseService).create(any(ResponseCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout()).isNotNull();
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(30));
        verify(mockResponseService, never()).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("createStreaming() carries the per-request timeout when set")
    void streamingCarriesRequestTimeout() {
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(ResponsesFixtures.streamOf());

        createClientWithMock().sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().requestTimeout(Duration.ofSeconds(90)).build(),
                LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none());

        final ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(mockResponseService).createStreaming(any(ResponseCreateParams.class), captor.capture());
        assertThat(captor.getValue().getTimeout().request()).isEqualTo(Duration.ofSeconds(90));
        verify(mockResponseService, never()).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("no per-request timeout keeps the single-argument overload on both paths")
    void noRequestTimeoutKeepsTheSingleArgOverload() {
        final RuntimeException sentinel = new RuntimeException("single-arg-create-invoked");
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenThrow(sentinel);

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build())).isInstanceOf(RuntimeException.class);

        verify(mockResponseService).create(any(ResponseCreateParams.class));
        verify(mockResponseService, never()).create(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("no per-request timeout keeps the single-argument createStreaming() too")
    void noRequestTimeoutKeepsTheSingleArgStreamingOverload() {
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.streamOf());

        createClientWithMock().sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none());

        verify(mockResponseService).createStreaming(any(ResponseCreateParams.class));
        verify(mockResponseService, never()).createStreaming(any(ResponseCreateParams.class),
                any(RequestOptions.class));
    }
}
