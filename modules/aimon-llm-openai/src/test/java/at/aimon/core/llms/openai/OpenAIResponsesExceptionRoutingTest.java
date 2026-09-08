package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * The <em>other</em> failure shape: an SDK exception genuinely raised by the Responses path.
 *
 * <p>
 * This is <strong>not</strong> the guard for the mid-stream classification behaviour — {@code
 * OpenAIResponsesErrorEventTest} is, because the shape that actually reaches this endpoint is an <em>event</em> the
 * SDK never throws on. What these tests cover is that a real SDK exception still reaches
 * {@link OpenAIExceptionMapper} unchanged, which is true because neither exchange contains a {@code catch}: every
 * failure propagates to the client's existing three-way cascade, the only place the mapper is called on this path.
 */
@DisplayName("OpenAI Responses - SDK exception routing")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesExceptionRoutingTest {

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private OpenAILlmClient createClientWithMock() {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build(),
                mockOpenAIClient);
    }

    @Test
    @DisplayName("a 429 raised by the blocking call is classified by the mapper, Retry-After included")
    void blockingRateLimitIsClassified() {
        final RateLimitException sdkError = RateLimitException.builder()
                .headers(com.openai.core.http.Headers.builder().put("Retry-After", "12").build()).build();
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenThrow(sdkError);

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage("sys", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build())).isInstanceOf(LlmRateLimitedException.class)
                .satisfies(thrown -> assertThat(((LlmRateLimitedException) thrown).getRetryAfter())
                        .contains(java.time.Duration.ofSeconds(12)));
    }

    @Test
    @DisplayName("a 5xx raised mid-stream is classified as retryable, exactly as on the Chat path")
    void streamingServerErrorIsRetryable() {
        final InternalServerException sdkError = InternalServerException.builder().statusCode(503)
                .headers(com.openai.core.http.Headers.builder().build()).build();
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class))).thenThrow(sdkError);

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none())).isInstanceOf(LlmOverloadedException.class)
                .satisfies(thrown -> assertThat(LlmRetryPolicy.defaultPolicy().isRetryable((LlmClientException) thrown))
                        .isTrue());
    }
}
