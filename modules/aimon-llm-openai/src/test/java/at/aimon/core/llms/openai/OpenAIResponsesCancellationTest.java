package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Preserved behaviours 1 and 2, on the second endpoint.
 *
 * <p>
 * The point of these six is that the Responses path <em>inherits</em> the cancellation machinery rather than
 * re-implementing it. Everything they assert lives in {@code OpenAILlmClient} and is shared: the fast path before
 * anything opens, the abort lever registered <em>before</em> the stream opens (the call's transport abort, never the
 * stream's close), the three-way catch cascade, and the decision to reroute a cancellable non-streaming call through
 * streaming. If one of these fails while its Chat sibling passes, the refactor put a copy of that machinery somewhere
 * it should not be.
 *
 * <p>
 * The Chat siblings in {@code OpenAILlmClientCancellationTest} assert the same things in the same way, which is part
 * of the acceptance: a difference between the two would hide exactly the regression they guard.
 */
@DisplayName("OpenAI Responses - streaming cancellation")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesCancellationTest {
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

    private static LlmCancellation cancellation(boolean cancelled, java.util.function.Consumer<Runnable> onCancel) {
        return new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public void onCancel(Runnable abort) {
                onCancel.accept(abort);
            }
        };
    }

    private LlmResponse stream(LlmCancellation cancellation) {
        return createClientWithMock().sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation);
    }

    @Test
    @DisplayName("already cancelled before the connection opens: nothing is sent")
    void throwsBeforeStartWhenAlreadyCancelled() {
        final OpenAILlmClient client = createClientWithMock();
        final LlmCancellation cancelled = cancellation(true, abort -> {
        });

        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancelled)).isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before start");

        // The parameter type is named because ResponseService declares four createStreaming(...) overloads.
        verify(mockResponseService, never()).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("the abort lever is registered before the stream opens, and it is not the stream's close()")
    void abortLeverIsRegisteredBeforeTheStreamOpensAndNeverClosesIt() {
        final ResponsesFixtures.RecordingStreamResponse streamResponse = ResponsesFixtures.recordingStreamOf();
        final AtomicBoolean streamOpened = new AtomicBoolean();
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class))).thenAnswer(invocation -> {
            streamOpened.set(true);
            return streamResponse;
        });

        // A token whose isCancelled() guard passes at entry but whose onCancel() fires synchronously -- the
        // documented "already cancelled at registration time" contract. What the registration sees is the point:
        // the stream has not been opened yet, so a trip during the header wait is covered.
        final AtomicBoolean openedAtRegistration = new AtomicBoolean(true);
        final LlmResponse response = stream(cancellation(false, abort -> {
            openedAtRegistration.set(streamOpened.get());
            abort.run();
        }));

        assertThat(response).isNotNull();
        assertThat(openedAtRegistration).as("the lever is registered before the stream opens").isFalse();
        // Exactly once: the lever did not close the stream; the consumer thread's own close on the way out did.
        assertThat(streamResponse.closeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mid-stream failure while the token is cancelled is reported as a cancellation")
    void midStreamFailureDuringCancellationIsACancellation() {
        final AtomicBoolean cancelledFlag = new AtomicBoolean(false);
        final RuntimeException streamClosed = new RuntimeException("stream closed");
        @SuppressWarnings("unchecked")
        final com.openai.core.http.StreamResponse<com.openai.models.responses.ResponseStreamEvent> streamResponse = mock(
                com.openai.core.http.StreamResponse.class);
        when(streamResponse.stream()).thenAnswer(invocation -> {
            cancelledFlag.set(true);
            throw streamClosed;
        });
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(streamResponse);

        final OpenAILlmClient client = createClientWithMock();
        final LlmCancellation cancellation = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return cancelledFlag.get();
            }

            @Override
            public void onCancel(Runnable abort) {
                // Not exercised: the failure is simulated on stream() directly.
            }
        };

        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation)).isExactlyInstanceOf(LlmCallCancelledException.class)
                .hasMessageContaining("aborted by cancellation").hasCause(streamClosed);
    }

    @Test
    @DisplayName("a cancellable non-streaming call is rerouted through the streaming path, usage included")
    void cancellableNonStreamingReroutesThroughStreaming() {
        // Preserved behaviour 2, and the half that is easy to lose: the reroute discards the chunks but must still
        // get token accounting, or a cancellable turn under-reports its cost against a blocking one.
        final ResponsesFixtures.RecordingStreamResponse streamResponse = ResponsesFixtures.recordingStreamOf(
                ResponsesFixtures.event("{\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{"
                        + "\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                        + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                        + "\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":10,"
                        + "\"output_tokens\":5,\"total_tokens\":15,\"output_tokens_details\":"
                        + "{\"reasoning_tokens\":2}}}}"));
        when(mockResponseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(streamResponse);

        final LlmResponse response = createClientWithMock().sendMessage(SystemPromptParts.empty(),
                List.of(Message.user("hi")), Collections.emptyList(), LlmModel.builder().build(),
                LlmCallMetadata.empty(), cancellation(false, abort -> {
                }));

        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(15);
        assertThat(response.getTokenUsage().getReasoningTokens()).isEqualTo(2);
        verify(mockResponseService).createStreaming(any(ResponseCreateParams.class));
        verify(mockResponseService, never()).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("the inert none() token keeps the cheaper blocking call")
    void nonCancellableKeepsTheBlockingPath() {
        final RuntimeException sentinel = new RuntimeException("blocking-create-was-invoked");
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenThrow(sentinel);

        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(), LlmCancellation.none()))
                .isInstanceOf(LlmClientException.class);

        verify(mockResponseService).create(any(ResponseCreateParams.class));
        verify(mockResponseService, never()).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("a cancellable non-streaming call that is already cancelled sends nothing at all")
    void cancellableNonStreamingThrowsBeforeStart() {
        final OpenAILlmClient client = createClientWithMock();

        assertThatThrownBy(() -> client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                cancellation(true, abort -> {
                }))).isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before start");

        verify(mockResponseService, never()).createStreaming(any(ResponseCreateParams.class));
        verify(mockResponseService, never()).create(any(ResponseCreateParams.class));
    }
}
