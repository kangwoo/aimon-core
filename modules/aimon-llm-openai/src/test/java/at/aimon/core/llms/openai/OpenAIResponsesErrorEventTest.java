package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Preserved behaviour 3 on this endpoint — and the reason it needed a different test from the Chat one.
 *
 * <p>
 * <strong>Every input here is an event or a status, not a pre-thrown exception.</strong> That distinction is the whole
 * finding. The SDK's SSE decoder throws only when the decoded payload has a <em>top-level</em> {@code "error"} key;
 * Chat Completions' mid-stream failure has that shape, and none of the three Responses shapes does — a
 * {@code response.error} event carries {@code code}/{@code message}/{@code param}/{@code sequence_number}, a
 * {@code response.failed} event nests its error inside {@code response}, and a blocking 200 carrying
 * {@code status: "failed"} is an ordinary return value. So a test that feeds the client an already-thrown exception
 * and asserts the mapper classified it is true, irrelevant, and green while the path is broken.
 *
 * <p>
 * The two ways of getting this wrong are each pinned below: leaving the aggregator unclosed escapes as
 * {@code IllegalStateException} from <em>outside</em> the client's try, and closing it without throwing turns the
 * failure into a silent empty success the executor accepts as the assistant's final answer.
 */
@DisplayName("OpenAI Responses - provider errors that are not SDK exceptions")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesErrorEventTest {

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private OpenAILlmClient createClientWithMock() {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build(),
                mockOpenAIClient);
    }

    /** {@code ResponseErrorEvent.build()} requires code, message, param AND sequence_number. */
    private static String errorEvent(String code, String message) {
        return "{\"type\":\"error\",\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"param\":null,"
                + "\"sequence_number\":1}";
    }

    /**
     * A terminal event carrying a nested failure status — the shape a conforming provider never sends, because it
     * sends {@code response.failed} instead.
     */
    private static String completedEventWithStatus(String status, String code, String message) {
        return "{\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"id\":\"r\","
                + "\"created_at\":1,\"model\":\"m\",\"object\":\"response\",\"parallel_tool_calls\":true,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"" + status + "\",\"output\":[],"
                + "\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}}";
    }

    private static String failedEvent(String code, String message) {
        return "{\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"id\":\"r\",\"created_at\":1,"
                + "\"model\":\"m\",\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\","
                + "\"tools\":[],\"status\":\"failed\",\"output\":[],\"error\":{\"code\":\"" + code + "\",\"message\":\""
                + message + "\"}}}";
    }

    private Throwable streamThrowing(String... eventJson) {
        return streamThrowing(LlmCancellation.none(), eventJson);
    }

    private Throwable streamThrowing(LlmCancellation cancellation, String... eventJson) {
        final com.openai.models.responses.ResponseStreamEvent[] events = new com.openai.models.responses.ResponseStreamEvent[eventJson.length];
        for (int i = 0; i < eventJson.length; i++) {
            events[i] = ResponsesFixtures.event(eventJson[i]);
        }
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.streamOf(events));

        final OpenAILlmClient client = createClientWithMock();
        try {
            client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")), Collections.emptyList(),
                    LlmModel.builder().build(), LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                    }, cancellation);
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    @Test
    @DisplayName("a response.error event becomes the same exception the blocking path would produce")
    void errorEventBecomesTheSameExceptionAsTheBlockingPath() {
        final Throwable thrown = streamThrowing(errorEvent("server_error", "upstream failure"));

        assertThat(thrown).isInstanceOf(LlmOverloadedException.class).hasMessageContaining("upstream failure");
    }

    @Test
    @DisplayName("a response.error event does NOT produce a successful empty response")
    void errorEventDoesNotProduceASuccessfulEmptyResponse() {
        // The "just call emitStreamEnd()" mis-fix: it closes the aggregator and returns empty text,
        // TokenUsage.empty() and StopReason.UNKNOWN -- which OrcaAgentExecutor accepts as the assistant's answer.
        assertThat(streamThrowing(errorEvent("server_error", "upstream failure")))
                .as("the call must not return normally").isNotNull();
    }

    @Test
    @DisplayName("a response.error event does not escape as aggregator state")
    void errorEventDoesNotEscapeAsAggregatorState() {
        // The other mis-fix: doing nothing leaves the aggregator unclosed, and toLlmResponse() -- which the client
        // calls OUTSIDE its try -- throws IllegalStateException("Aggregator has not received STREAM_END yet"),
        // unmapped and naming AIMON's internals instead of the provider's error.
        final Throwable thrown = streamThrowing(errorEvent("server_error", "upstream failure"));

        assertThat(thrown).isInstanceOf(LlmClientException.class).isNotInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).doesNotContain("STREAM_END");
    }

    @Test
    @DisplayName("a response.failed event is an exception, not a stop reason")
    void failedEventBecomesAnException() {
        final Throwable thrown = streamThrowing(failedEvent("rate_limit_exceeded", "slow down"));

        assertThat(thrown).isInstanceOf(LlmRateLimitedException.class).hasMessageContaining("slow down");
    }

    @Test
    @DisplayName("a blocking 200 carrying status:failed is an exception, with no SSE decoder involved")
    void blockingFailedStatusBecomesAnException() {
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\","
                        + "\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\","
                        + "\"tools\":[],\"status\":\"failed\",\"output\":[],"
                        + "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}"));

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage("sys", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build())).isInstanceOf(LlmOverloadedException.class).hasMessageContaining("boom");
    }

    @Test
    @DisplayName("a failed status with no error object still fails loudly, naming the status")
    void failedStatusWithoutAnErrorObjectStillThrows() {
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\","
                        + "\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\","
                        + "\"tools\":[],\"status\":\"cancelled\",\"output\":[]}"));

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage("sys", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build())).isInstanceOf(LlmClientException.class)
                        .hasMessageContaining("cancelled");
    }

    @Test
    @DisplayName("a terminal event whose nested status is failed is an exception, not an empty success")
    void terminalEventCarryingAFailureStatusIsAnException() {
        // The streaming half of what the blocking path checks in convertResponse. Without the same isFailureStatus
        // guard in captureTerminal, this stream closes the aggregator normally and hands the executor a successful
        // LlmResponse with empty text and StopReason.UNKNOWN -- the silent success this whole area exists to prevent,
        // and the one shape where "the provider failed" and "the assistant had nothing to say" are indistinguishable.
        final Throwable thrown = streamThrowing(completedEventWithStatus("failed", "server_error", "boom"));

        assertThat(thrown).isInstanceOf(LlmOverloadedException.class).hasMessageContaining("boom");
    }

    @Test
    @DisplayName("a terminal event whose nested status is cancelled fails the same way")
    void terminalEventCarryingACancelledStatusIsAnException() {
        // isFailureStatus covers four statuses, not one. Asserting a second of them keeps a fix narrowed to
        // "failed" from passing.
        final Throwable thrown = streamThrowing(completedEventWithStatus("cancelled", "server_error", "stopped"));

        assertThat(thrown).isInstanceOf(LlmClientException.class).hasMessageContaining("stopped");
    }

    @Test
    @DisplayName("a terminal event whose nested status is completed is still an ordinary success")
    void terminalEventCarryingACompletedStatusStillSucceeds() {
        // The guard must not turn every terminal event into a failure. This is the arm that keeps the two above
        // honest.
        assertThat(streamThrowing("{\"type\":\"response.completed\",\"sequence_number\":1,\"response\":"
                + "{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                + "\"status\":\"completed\",\"output\":[]}}")).as("a healthy terminal event must not throw").isNull();
    }

    @Test
    @DisplayName("the retry budget matches the Chat path, asserted through the policy rather than by class name")
    void retryBudgetMatchesTheChatPath() {
        // The actual content of the preserved behaviour: a classification that is the right shape but the wrong
        // retryability is still a divergence. Asked of LlmRetryPolicy rather than of a class name.
        final Throwable thrown = streamThrowing(errorEvent("server_error", "upstream failure"));

        assertThat(thrown).isInstanceOf(LlmClientException.class);
        assertThat(LlmRetryPolicy.defaultPolicy().isRetryable((LlmClientException) thrown)).isTrue();
    }

    @Test
    @DisplayName("a request-content failure code is NOT retryable")
    void contentFailureCodeIsNotRetryable() {
        // The one deliberate divergence from mid-stream parity: invalid_prompt and its family are what a blocking
        // call would have rejected as a 400. Sending them through the mid-stream mapper would make a permanently
        // broken request retry until the policy gives up.
        final Throwable thrown = streamThrowing(errorEvent("invalid_prompt", "prompt rejected"));

        assertThat(thrown).isInstanceOf(LlmInvalidRequestException.class);
        assertThat(LlmRetryPolicy.defaultPolicy().isRetryable((LlmClientException) thrown)).isFalse();
    }

    @Test
    @DisplayName("a code this SDK version does not model keeps today's retryable behaviour")
    void anUnrecognisedCodeKeepsParity() {
        // The default arm is stated as a transient SET with "unrecognised keeps parity", not as a list of terminal
        // codes, so a code OpenAI adds later inherits today's behaviour instead of silently becoming non-retryable.
        final Throwable thrown = streamThrowing(errorEvent("some_future_code", "who knows"));

        assertThat(thrown).isInstanceOf(LlmOverloadedException.class);
        assertThat(LlmRetryPolicy.defaultPolicy().isRetryable((LlmClientException) thrown)).isTrue();
    }

    @Test
    @DisplayName("a provider error during a local cancellation is reported as a cancellation")
    void errorEventDuringLocalCancellationIsReportedAsCancellation() {
        // This is what binds "thrown from INSIDE the try" rather than merely "an exception is thrown". A throw placed
        // outside the cascade would skip the second catch's isCancelled() check and report a server fault instead.
        //
        // The token must report FALSE at entry and true afterwards. A token that is cancelled from the start takes
        // the fast path, never opens a stream, and yields LlmCallCancelledException for a reason that has nothing to
        // do with the cascade -- so the test would be green against the very bug it is here to catch.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Throwable thrown = streamThrowing(new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onCancel(Runnable abort) {
                // Registered after the stream opens; trips the flag so the cascade sees a cancelled token when the
                // provider's error unwinds through it.
                cancelled.set(true);
            }
        }, errorEvent("server_error", "upstream failure"));

        assertThat(thrown).isInstanceOf(LlmCallCancelledException.class);
        assertThat(thrown).hasCauseInstanceOf(LlmOverloadedException.class);
    }
}
