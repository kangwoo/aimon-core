package at.aimon.core.llm.invoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamTarget;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Cancellation semantics of {@link LlmCallGateway}: a tripped {@link LlmCancellation} short-circuits before an attempt,
 * and an {@link LlmCallCancelledException} raised by the client is <b>terminal</b> — never retried, never fed to
 * fallback — even when the configured retry/fallback policies would otherwise act on it. Covers both the non-streaming
 * and streaming loops.
 */
@DisplayName("LlmCallGateway cancellation")
class LlmCallGatewayCancellationTest {

    private static final List<Message> MESSAGES = List.of(Message.user("hello"));
    private static final List<ToolDefinition> TOOLS = List.of();

    private static LlmModel model(String name) {
        return LlmModel.builder().name(name).build();
    }

    // A retry policy that would eagerly retry a cancelled exception if the gateway did not treat it as terminal — the
    // cancelled type is explicitly listed as retryable to make the "never retried" guarantee unambiguous.
    private static LlmRetryPolicy retryCancelledToo() {
        return LlmRetryPolicy.builder().maxAttempts(3).backoffBase(Duration.ofMillis(10)).jitterFactor(0.0)
                .retryableExceptions(Set.of(LlmCallCancelledException.class)).build();
    }

    // A retry policy that treats any generic LlmClientException as retryable, so a transient failure would normally be
    // retried after a backoff sleep — used to prove the pre-sleep cancellation check short-circuits that retry.
    private static LlmRetryPolicy retryGeneric() {
        return LlmRetryPolicy.builder().maxAttempts(3).backoffBase(Duration.ofMillis(10)).jitterFactor(0.0)
                .retryableExceptions(Set.of(LlmClientException.class)).build();
    }

    private static LlmCallGateway<Void> gateway(ThrowingClient client, RecordingSleeper sleeper) {
        return LlmCallGateway.<Void>builder().client(client).retryPolicy(retryCancelledToo()).sleeper(sleeper)
                .random(new Random(42)).build();
    }

    @Nested
    @DisplayName("non-streaming")
    class NonStreaming {

        @Test
        @DisplayName("pre-cancelled token short-circuits before any client call")
        void preCancelledShortCircuits() {
            final ThrowingClient client = new ThrowingClient(null);
            final RecordingSleeper sleeper = new RecordingSleeper();

            assertThatThrownBy(() -> gateway(client, sleeper).sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS,
                    model("primary"), LlmCallMetadata.empty(), cancelled(true)))
                    .isInstanceOf(LlmCallCancelledException.class).hasMessageContaining("before attempt");
            assertThat(client.callCount).as("client must not be called when already cancelled").isZero();
            assertThat(sleeper.records).isEmpty();
        }

        @Test
        @DisplayName("a client LlmCallCancelledException is rethrown as-is and never retried")
        void clientCancelledIsTerminal() {
            final LlmCallCancelledException cancelled = new LlmCallCancelledException("provider aborted the call");
            final ThrowingClient client = new ThrowingClient(cancelled);
            final RecordingSleeper sleeper = new RecordingSleeper();

            assertThatThrownBy(() -> gateway(client, sleeper).sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS,
                    model("primary"), LlmCallMetadata.empty(), cancelled(false))).isSameAs(cancelled);
            assertThat(client.callCount).as("a cancelled call is terminal — exactly one attempt").isEqualTo(1);
            assertThat(sleeper.records).isEmpty();
        }

        @Test
        @DisplayName("a cancelled exception is not routed to fallback even when the fallback policy activates on it")
        void clientCancelledDoesNotFallOver() {
            final LlmModel primary = model("primary");
            final LlmModel secondary = model("secondary");
            final LlmCallCancelledException cancelled = new LlmCallCancelledException("aborted");
            final ThrowingClient client = new ThrowingClient(cancelled);
            final RecordingSleeper sleeper = new RecordingSleeper();

            final LlmFallbackPolicy fallback = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmCallCancelledException.class)).build();
            final LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client)
                    .retryPolicy(retryCancelledToo()).fallbackPolicy(fallback).sleeper(sleeper).random(new Random(42))
                    .build();

            assertThatThrownBy(() -> gateway.sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, primary,
                    LlmCallMetadata.empty(), cancelled(false))).isSameAs(cancelled);
            assertThat(client.modelsUsed).as("cancellation must not escalate to the secondary model")
                    .containsExactly(primary);
        }

        @Test
        @DisplayName("cancellation during the retry backoff window short-circuits before sleeping or retrying")
        void cancellationDuringRetryBackoffShortCircuits() {
            // A retryable (non-cancel) failure would normally trigger a backoff sleep + retry. Here the cancellation
            // flag flips to true during the failing call, so the gateway's pre-sleep check must abort with a terminal
            // LlmCallCancelledException instead of sleeping and issuing a second attempt.
            final AtomicBoolean flag = new AtomicBoolean(false);
            final LlmClientException transientFailure = new LlmClientException("transient");
            final RetryableThenCancelClient client = new RetryableThenCancelClient(transientFailure, flag);
            final RecordingSleeper sleeper = new RecordingSleeper();
            final LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client)
                    .retryPolicy(retryGeneric()).sleeper(sleeper).random(new Random(42)).build();

            assertThatThrownBy(() -> gateway.sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, model("primary"),
                    LlmCallMetadata.empty(), flag(flag))).isInstanceOf(LlmCallCancelledException.class)
                    .hasMessageContaining("before attempt").satisfies(ex -> assertThat(ex.getSuppressed())
                            .as("the last provider failure is attached for diagnostics").contains(transientFailure));
            assertThat(client.callCount).as("no retry attempt after cancellation").isEqualTo(1);
            assertThat(sleeper.records).as("must short-circuit before the retry backoff sleep").isEmpty();
        }

        @Test
        @DisplayName("a cancellation arriving DURING the backoff sleep wakes it and short-circuits the retry")
        void cancellationDuringSleepShortCircuits() {
            // Distinct from the case above (flag tripped by the failing CALL, caught by the pre-sleep guard): here the
            // flag is tripped by the SLEEP itself, simulating a trip that arrives after the pre-sleep check while the
            // worker is inside the backoff. The gateway must route the backoff through the cancellation-aware sleeper
            // overload and re-check on the next loop iteration, issuing no second attempt.
            final AtomicBoolean flag = new AtomicBoolean(false);
            final LlmClientException transientFailure = new LlmClientException("transient");
            final ThrowingClient client = new ThrowingClient(transientFailure);
            final TripDuringSleepSleeper sleeper = new TripDuringSleepSleeper(flag);
            final LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client)
                    .retryPolicy(retryGeneric()).sleeper(sleeper).random(new Random(42)).build();

            assertThatThrownBy(() -> gateway.sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, model("primary"),
                    LlmCallMetadata.empty(), flag(flag))).isInstanceOf(LlmCallCancelledException.class)
                    .hasMessageContaining("before attempt").satisfies(ex -> assertThat(ex.getSuppressed())
                            .as("the last provider failure is attached for diagnostics").contains(transientFailure));
            assertThat(client.callCount).as("no second attempt once the backoff observes the cancellation")
                    .isEqualTo(1);
            assertThat(sleeper.cancellationAware)
                    .as("gateway must route the backoff through the cancellation-aware sleep overload").isTrue();
            assertThat(sleeper.records).as("exactly one backoff sleep was entered").hasSize(1);
        }
    }

    @Nested
    @DisplayName("streaming")
    class Streaming {

        @Test
        @DisplayName("pre-cancelled token short-circuits before any client call")
        void preCancelledShortCircuits() {
            final ThrowingClient client = new ThrowingClient(null);
            final RecordingSleeper sleeper = new RecordingSleeper();

            assertThatThrownBy(() -> gateway(client, sleeper).sendMessageStreaming(SystemPromptParts.empty(), MESSAGES,
                    TOOLS, model("primary"), LlmCallMetadata.empty(),
                    LlmStreamTarget.builder().options(LlmStreamingOptions.defaults()).sink(chunk -> {
                    }).retryListener((prev, next, reason) -> {
                    }).build(), cancelled(true))).isInstanceOf(LlmCallCancelledException.class)
                    .hasMessageContaining("before attempt");
            assertThat(client.callCount).isZero();
            assertThat(sleeper.records).isEmpty();
        }

        @Test
        @DisplayName("a client LlmCallCancelledException is rethrown as-is and never retried")
        void clientCancelledIsTerminal() {
            final LlmCallCancelledException cancelled = new LlmCallCancelledException("stream aborted");
            final ThrowingClient client = new ThrowingClient(cancelled);
            final RecordingSleeper sleeper = new RecordingSleeper();

            assertThatThrownBy(() -> gateway(client, sleeper).sendMessageStreaming(SystemPromptParts.empty(), MESSAGES,
                    TOOLS, model("primary"), LlmCallMetadata.empty(),
                    LlmStreamTarget.builder().options(LlmStreamingOptions.defaults()).sink(chunk -> {
                    }).retryListener((prev, next, reason) -> {
                    }).build(), cancelled(false))).isSameAs(cancelled);
            assertThat(client.callCount).isEqualTo(1);
            assertThat(sleeper.records).isEmpty();
        }

        @Test
        @DisplayName("buffered partial chunks are discarded — never flushed to the outer sink — when the stream is cancelled")
        void bufferedPartialDiscardedOnCancel() {
            // With bufferUntilFirstSuccess=true the gateway buffers chunks until the attempt succeeds. A cancellation
            // mid-stream must abort the buffer so no partial output leaks to the caller's sink.
            final EmitThenCancelStreamingClient client = new EmitThenCancelStreamingClient();
            final RecordingSleeper sleeper = new RecordingSleeper();
            final List<LlmStreamChunk> flushed = new ArrayList<>();
            final LlmStreamSink outerSink = flushed::add;
            final LlmStreamingOptions buffered = LlmStreamingOptions.builder().bufferUntilFirstSuccess(true).build();
            final LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client)
                    .retryPolicy(retryCancelledToo()).sleeper(sleeper).random(new Random(42)).build();

            assertThatThrownBy(() -> gateway.sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS,
                    model("primary"), LlmCallMetadata.empty(),
                    LlmStreamTarget.builder().options(buffered).sink(outerSink).retryListener((prev, next, reason) -> {
                    }).build(), cancelled(false))).isInstanceOf(LlmCallCancelledException.class);
            assertThat(client.chunksEmitted).as("the client did emit a partial chunk into the buffer").isPositive();
            assertThat(flushed).as("buffered partial must never reach the outer sink on cancel").isEmpty();
        }
    }

    // ---------------- test fakes ----------------

    private static LlmCancellation cancelled(boolean cancelled) {
        return new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public void onCancel(Runnable abort) {
                Objects.requireNonNull(abort, "abort");
            }
        };
    }

    /** A cancellation whose state tracks a shared flag, so a fake client can trip it mid-call. */
    private static LlmCancellation flag(AtomicBoolean flag) {
        return new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return flag.get();
            }

            @Override
            public void onCancel(Runnable abort) {
                Objects.requireNonNull(abort, "abort");
            }
        };
    }

    /** Client that counts calls and, if configured with an exception, throws it on every call. */
    private static final class ThrowingClient implements LlmClient {
        private final LlmClientException toThrow;
        private final List<LlmModel> modelsUsed = new ArrayList<>();
        private int callCount;

        ThrowingClient(LlmClientException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            callCount++;
            modelsUsed.add(modelConfig);
            if (toThrow != null) {
                throw toThrow;
            }
            return LlmResponse.text("ok");
        }

        @Override
        public String getProviderName() {
            return "throwing-fake";
        }

    }

    /**
     * Client that throws a retryable failure on every call and trips a shared cancellation flag on the first call,
     * simulating a cancellation that arrives while the provider call is in flight.
     */
    private static final class RetryableThenCancelClient implements LlmClient {
        private final LlmClientException toThrow;
        private final AtomicBoolean flag;
        private int callCount;

        RetryableThenCancelClient(LlmClientException toThrow, AtomicBoolean flag) {
            this.toThrow = toThrow;
            this.flag = flag;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            callCount++;
            flag.set(true);
            throw toThrow;
        }

        @Override
        public String getProviderName() {
            return "retryable-then-cancel-fake";
        }

    }

    /**
     * Streaming client that emits one partial chunk into the sink, then aborts the stream with a cancelled exception.
     */
    private static final class EmitThenCancelStreamingClient implements LlmClient {
        private int chunksEmitted;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            sink.accept(LlmStreamChunk.textDelta(0, "partial-buffered"));
            chunksEmitted++;
            throw new LlmCallCancelledException("stream aborted mid-buffer");
        }

        @Override
        public String getProviderName() {
            return "emit-then-cancel-fake";
        }

    }

    /** Records requested sleep durations without sleeping. */
    private static final class RecordingSleeper implements Sleeper {
        private final List<Duration> records = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            records.add(duration);
        }
    }

    /**
     * Sleeper that trips the shared cancellation flag while "sleeping" — simulating a cancellation that arrives after
     * the gateway's pre-sleep check, mid-backoff. Records that the cancellation-aware overload was the one invoked.
     */
    private static final class TripDuringSleepSleeper implements Sleeper {
        private final AtomicBoolean flag;
        private final List<Duration> records = new ArrayList<>();
        private boolean cancellationAware;

        TripDuringSleepSleeper(AtomicBoolean flag) {
            this.flag = flag;
        }

        @Override
        public void sleep(Duration duration) {
            records.add(duration);
        }

        @Override
        public void sleep(Duration duration, LlmCancellation cancellation) {
            cancellationAware = true;
            records.add(duration);
            flag.set(true);
        }
    }
}
