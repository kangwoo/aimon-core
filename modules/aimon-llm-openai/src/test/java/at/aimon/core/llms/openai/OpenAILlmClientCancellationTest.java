package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;

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
 * Tests for the LLM-cancellation ({@link LlmCancellation}) contract of
 * {@link OpenAILlmClient#sendMessageStreaming(SystemPromptParts, List, List, LlmModel, LlmCallMetadata,
 * LlmStreamingOptions, at.aimon.core.llm.streaming.LlmStreamSink, LlmCancellation)}.
 *
 * <p>
 * Covers the three observable contract points documented on the streaming method:
 * <ul>
 * <li>the fast path that rejects an already-cancelled call before opening the HTTP stream;
 * <li>the abort lever registered via {@code cancellation.onCancel(streamResponse::close)};
 * <li>mapping a mid-stream failure to {@link LlmCallCancelledException} when the token is cancelled.
 * </ul>
 *
 * <p>
 * Also covers the non-streaming in-flight abort on
 * {@link OpenAILlmClient#sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmCancellation)}:
 * a blocking {@code create()} has no abort handle, so when the token is cancellable the request is re-routed through
 * {@code createStreaming()} to borrow the streaming abort lever, while the inert {@link LlmCancellation#none()} token
 * keeps the cheaper blocking {@code create()} path (zero behaviour change).
 */
@DisplayName("OpenAILlmClient - Streaming Cancellation Tests")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientCancellationTest {

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    private OpenAILlmClient createClientWithMock() {
        // lenient(): the fast-cancellation-path test never reaches client.chat().completions() (the guard throws
        // first), so these stubs are unused there. The other tests rely on them.
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-4").build();
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    @Test
    @DisplayName("Should throw LlmCallCancelledException before opening the stream when already cancelled")
    void shouldThrowBeforeStart_WhenAlreadyCancelled() {
        // Given: a token that reports cancelled from the start
        OpenAILlmClient client = createClientWithMock();
        LlmCancellation cancellation = mock(LlmCancellation.class);
        when(cancellation.isCancelled()).thenReturn(true);

        // When/Then: the fast-path guard throws before the SDK is ever invoked
        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation)).isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before start");

        verify(mockChatCompletionService, never()).createStreaming(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("Should invoke the registered abort callback (StreamResponse.close()) when cancellation fires")
    void shouldInvokeAbortCallback_WhenCancellationFiresDuringRegistration() {
        // Given: the SDK call succeeds and returns an empty stream so mapper.consume() unwinds cleanly
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(streamResponse);

        OpenAILlmClient client = createClientWithMock();

        // A cancellation whose isCancelled() guard passes at entry (so the call proceeds) but whose onCancel()
        // registration fires the abort callback synchronously -- mirroring the documented "already cancelled at
        // registration time" contract of LlmCancellation.onCancel().
        LlmCancellation cancellation = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable abort) {
                abort.run();
            }
        };

        // When
        LlmResponse response = client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation);

        // Then: the registered abort lever (streamResponse::close) was actually invoked. It fires once from the
        // onCancel() registration and once more from the try-with-resources auto-close on the way out -- close() is
        // documented as idempotent, so at-least-once is the correct assertion here rather than an exact count.
        assertThat(response).isNotNull();
        verify(streamResponse, atLeastOnce()).close();
    }

    @Test
    @DisplayName("Should map a mid-stream failure to LlmCallCancelledException when the token is cancelled")
    void shouldMapMidStreamFailureToCancelledException_WhenTokenCancelled() {
        // Given: a token that is not cancelled at entry but flips to cancelled concurrently with the stream unwind
        AtomicBoolean cancelledFlag = new AtomicBoolean(false);
        LlmCancellation cancellation = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return cancelledFlag.get();
            }

            @Override
            public void onCancel(Runnable abort) {
                // Not exercised in this scenario -- the failure below is simulated directly on stream().
            }
        };

        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        RuntimeException streamClosed = new RuntimeException("stream closed");
        when(streamResponse.stream()).thenAnswer(invocation -> {
            // Simulate the cancellation tripping concurrently with the stream unwinding, so by the time the general
            // catch block runs, isCancelled() already reports true.
            cancelledFlag.set(true);
            throw streamClosed;
        });
        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(streamResponse);

        OpenAILlmClient client = createClientWithMock();

        // When/Then: the general catch block classifies this as a cancellation, not a generic LlmClientException
        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation)).isExactlyInstanceOf(LlmCallCancelledException.class)
                .hasMessageContaining("aborted by cancellation").hasCause(streamClosed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Non-streaming in-flight abort — the blocking sendMessage(..., LlmCancellation) overload re-routes through
    // createStreaming() when (and only when) the token is cancellable.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Non-streaming sendMessage routes through createStreaming when the token is cancellable")
    void nonStreamingRoutesThroughStreaming_WhenCancellable() {
        // Given: the SDK streaming call returns an empty stream so the aggregator unwinds to an empty response
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(streamResponse);

        OpenAILlmClient client = createClientWithMock();

        // A real, trippable token: inherits the default isSupported() == true, so the provider borrows the streaming
        // abort lever instead of issuing a blocking create().
        LlmCancellation cancellable = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable abort) {
                // real token; the abort lever is registered but never fired in this scenario
            }
        };

        // When
        LlmResponse response = client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(), cancellable);

        // Then: the request went through the abortable streaming path, never the blocking create()
        assertThat(response).isNotNull();
        verify(mockChatCompletionService).createStreaming(any(ChatCompletionCreateParams.class));
        verify(mockChatCompletionService, never()).create(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("Non-streaming sendMessage keeps the blocking create() path for the none() token")
    void nonStreamingKeepsBlockingPath_ForNoneToken() {
        // Given: create() is stubbed to throw a sentinel so we can assert it was the invoked path without having to
        // construct a fully valid SDK ChatCompletion for convertResponse().
        RuntimeException sentinel = new RuntimeException("blocking-create-was-invoked");
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(sentinel);

        OpenAILlmClient client = createClientWithMock();

        // When/Then: the inert none() token (isSupported() == false) must NOT be re-routed through streaming
        assertThatThrownBy(() -> client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(), LlmCancellation.none()))
                .isInstanceOf(LlmClientException.class);

        verify(mockChatCompletionService).create(any(ChatCompletionCreateParams.class));
        verify(mockChatCompletionService, never()).createStreaming(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("Non-streaming sendMessage throws before start for an already-cancelled cancellable token")
    void nonStreamingThrowsBeforeStart_WhenAlreadyCancelledAndCancellable() {
        OpenAILlmClient client = createClientWithMock();

        // Cancellable (default isSupported() == true) and already cancelled: re-routes to streaming, whose fast-path
        // guard rejects the call before any SDK request is issued.
        LlmCancellation cancellation = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onCancel(Runnable abort) {
                // not exercised — the guard throws first
            }
        };

        assertThatThrownBy(() -> client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(), cancellation))
                .isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before start");

        verify(mockChatCompletionService, never()).create(any(ChatCompletionCreateParams.class));
        verify(mockChatCompletionService, never()).createStreaming(any(ChatCompletionCreateParams.class));
    }
}
