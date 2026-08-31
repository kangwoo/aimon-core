package at.aimon.core.llms.anthropic;

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

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.blocking.MessageService;

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
 * {@link AnthropicLlmClient#sendMessageStreaming(SystemPromptParts, List, List, LlmModel, LlmCallMetadata,
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
 * {@link AnthropicLlmClient#sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmCancellation)}:
 * a blocking {@code create()} has no abort handle, so when the token is cancellable the request is re-routed through
 * {@code createStreaming()} to borrow the streaming abort lever, while the inert {@link LlmCancellation#none()} token
 * keeps the cheaper blocking {@code create()} path (zero behaviour change).
 */
@DisplayName("AnthropicLlmClient - Streaming Cancellation Tests")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientCancellationTest {

    @Mock
    private AnthropicClient mockAnthropicClient;

    @Mock
    private MessageService mockMessageService;

    private AnthropicLlmClient createClientWithMock() {
        // lenient(): the fast-cancellation-path test never reaches client.messages() (the guard throws first), so
        // this stub is unused there. The other tests rely on it.
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514").build();
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    @Test
    @DisplayName("Should throw LlmCallCancelledException before opening the stream when already cancelled")
    void shouldThrowBeforeStart_WhenAlreadyCancelled() {
        // Given: a token that reports cancelled from the start
        AnthropicLlmClient client = createClientWithMock();
        LlmCancellation cancellation = mock(LlmCancellation.class);
        when(cancellation.isCancelled()).thenReturn(true);

        // When/Then: the fast-path guard throws before the SDK is ever invoked
        assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), chunk -> {
                }, cancellation)).isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before start");

        verify(mockMessageService, never()).createStreaming(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("Should invoke the registered abort callback (StreamResponse.close()) when cancellation fires")
    void shouldInvokeAbortCallback_WhenCancellationFiresDuringRegistration() {
        // Given: the SDK call succeeds and returns an empty stream so mapper.consume() unwinds cleanly
        @SuppressWarnings("unchecked")
        StreamResponse<RawMessageStreamEvent> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockMessageService.createStreaming(any(MessageCreateParams.class))).thenReturn(streamResponse);

        AnthropicLlmClient client = createClientWithMock();

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
        StreamResponse<RawMessageStreamEvent> streamResponse = mock(StreamResponse.class);
        RuntimeException streamClosed = new RuntimeException("stream closed");
        when(streamResponse.stream()).thenAnswer(invocation -> {
            // Simulate the cancellation tripping concurrently with the stream unwinding, so by the time the general
            // catch block runs, isCancelled() already reports true.
            cancelledFlag.set(true);
            throw streamClosed;
        });
        when(mockMessageService.createStreaming(any(MessageCreateParams.class))).thenReturn(streamResponse);

        AnthropicLlmClient client = createClientWithMock();

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
        StreamResponse<RawMessageStreamEvent> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockMessageService.createStreaming(any(MessageCreateParams.class))).thenReturn(streamResponse);

        AnthropicLlmClient client = createClientWithMock();

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
        verify(mockMessageService).createStreaming(any(MessageCreateParams.class));
        verify(mockMessageService, never()).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("Non-streaming sendMessage keeps the blocking create() path for the none() token")
    void nonStreamingKeepsBlockingPath_ForNoneToken() {
        // Given: create() is stubbed to throw a sentinel so we can assert it was the invoked path without having to
        // construct a fully valid SDK Message for convertResponse().
        RuntimeException sentinel = new RuntimeException("blocking-create-was-invoked");
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(sentinel);

        AnthropicLlmClient client = createClientWithMock();

        // When/Then: the inert none() token (isSupported() == false) must NOT be re-routed through streaming
        assertThatThrownBy(() -> client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(), LlmCancellation.none()))
                .isInstanceOf(LlmClientException.class);

        verify(mockMessageService).create(any(MessageCreateParams.class));
        verify(mockMessageService, never()).createStreaming(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("Non-streaming sendMessage throws before start for an already-cancelled cancellable token")
    void nonStreamingThrowsBeforeStart_WhenAlreadyCancelledAndCancellable() {
        AnthropicLlmClient client = createClientWithMock();

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

        verify(mockMessageService, never()).create(any(MessageCreateParams.class));
        verify(mockMessageService, never()).createStreaming(any(MessageCreateParams.class));
    }
}
