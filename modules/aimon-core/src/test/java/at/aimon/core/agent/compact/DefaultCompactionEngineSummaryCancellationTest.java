package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * The summary LLM call of a compaction follows the compacting execution's cancellation signal (EE-95).
 *
 * <p>
 * The client here is the kind a provider is: its cancellation-aware call blocks until its abort lever is pulled. That
 * the call was stopped is read from the client having seen its abort run, not from how soon the engine returned.
 */
@DisplayName("DefaultCompactionEngine: the summary call and the execution's cancellation signal (EE-95)")
class DefaultCompactionEngineSummaryCancellationTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();
    private static final long HANG_GUARD_SECONDS = 10;

    private final ExecutorService compactionThread = Executors.newSingleThreadExecutor();
    private final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    private final CountingSignal signal = new CountingSignal(coordinator.getSignal());

    @AfterEach
    void tearDown() {
        compactionThread.shutdownNow();
        coordinator.close();
    }

    @Test
    void anInterruptAbortsTheSummaryCallInFlight() throws Exception {
        final AbortableClient client = new AbortableClient();
        final DefaultCompactionEngine engine = engine(client);

        final Future<CompactionResult> running = compactionThread
                .submit(() -> engine.summarize(request().executionCancellation(signal).build()));
        assertThat(client.callStarted.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(signal.liveListeners).as("the call's one listener is on the signal while it runs").hasValue(1);
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        final CompactionResult result = running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

        assertThat(client.abortRan).as("the client saw its abort lever pulled").isTrue();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).containsInstanceOf(LlmCallCancelledException.class);
        assertThat(signal.liveListeners).as("nothing is left on the execution's signal").hasValue(0);
    }

    @Test
    void theInPlaceCompactionSharesTheSameCall() throws Exception {
        final AbortableClient client = new AbortableClient();
        final DefaultCompactionEngine engine = engine(client);
        final TranscriptBuffer buffer = new TranscriptBuffer(SessionId.generate());
        buffer.addUserMessage("first");
        buffer.addAssistantMessage("one");
        buffer.addUserMessage("second");
        buffer.addAssistantMessage("two");

        @SuppressWarnings("deprecation")
        final Future<CompactionResult> running = compactionThread.submit(() -> engine
                .compact(CompactionRequest.builder().transcriptBuffer(buffer).trigger(CompactionTrigger.AUTO)
                        .model(MODEL).hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).build()));
        assertThat(client.callStarted.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        final CompactionResult result = running.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

        assertThat(client.abortRan).isTrue();
        assertThat(result.getError()).containsInstanceOf(LlmCallCancelledException.class);
        assertThat(buffer.getMessages()).as("the transcript is as it was").hasSize(4);
        assertThat(signal.liveListeners).hasValue(0);
    }

    @Test
    void aSignalThatHasAlreadyTrippedMakesNoCall() {
        final AbortableClient client = new AbortableClient();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        final CompactionResult result = engine(client).summarize(request().executionCancellation(signal).build());

        assertThat(client.cancellationAwareCalls).hasValue(0);
        assertThat(client.plainCalls).hasValue(0);
        assertThat(result.getError()).containsInstanceOf(LlmCallCancelledException.class);
        assertThat(signal.liveListeners).hasValue(0);
    }

    @Test
    void withoutASignalTheCallIsMadeAsBefore() {
        final AbortableClient client = new AbortableClient();

        final CompactionResult result = engine(client).summarize(request().build());

        // /compact, or a caller that forwarded no signal: the same overload and so the same transport as before.
        assertThat(client.plainCalls).hasValue(1);
        assertThat(client.cancellationAwareCalls).hasValue(0);
        assertThat(result.getSummaryText()).hasValue("the summary");
    }

    @Test
    void aSummaryTheClientReturnsAfterTheSignalTrippedIsKept() {
        // A client that does not honour the token finishes its call. The work is done, so it is not thrown away.
        final AbortableClient client = new AbortableClient();
        client.beforeAnswering = () -> coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        client.ignoreTheToken = true;

        final CompactionResult result = engine(client).summarize(request().executionCancellation(signal).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummaryText()).hasValue("the summary");
        assertThat(signal.liveListeners).hasValue(0);
    }

    @Test
    void aCancellationTheExecutionDidNotRequestIsAnOrdinaryFailure() {
        // The breakers do not count a cancelled summary. A client that says "cancelled" while the signal is live has
        // simply failed, and must not ride that exemption into being retried on every iteration.
        final AbortableClient client = new AbortableClient();
        client.throwCancelledUnasked = true;

        final CompactionResult result = engine(client).summarize(request().executionCancellation(signal).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError().orElseThrow()).isNotInstanceOf(LlmCallCancelledException.class)
                .hasCauseInstanceOf(LlmCallCancelledException.class);
        assertThat(signal.liveListeners).hasValue(0);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static DefaultCompactionEngine engine(LlmClient client) {
        return DefaultCompactionEngine.withDefaults(client, new HeuristicTokenEstimator(),
                new DefaultHookExecutionManager());
    }

    private static SummaryRequest.Builder request() {
        return SummaryRequest.builder().messages(List.of(Message.user("first"), Message.assistant("one", List.of())))
                .systemPrompt("system").sessionId(SessionId.of("s-1")).trigger(CompactionTrigger.AUTO).model(MODEL)
                .hookRegistry(new DefaultHookRegistry());
    }

    /** Counts the listeners that are on the wrapped signal right now. */
    private static final class CountingSignal implements CancellationSignal {
        final AtomicInteger liveListeners = new AtomicInteger();
        private final CancellationSignal delegate;

        CountingSignal(CancellationSignal delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public Optional<InterruptReason> getReason() {
            return delegate.getReason();
        }

        @Override
        public void checkpoint() {
            delegate.checkpoint();
        }

        @Override
        public Registration onCancel(Runnable listener) {
            final Registration registration = delegate.onCancel(listener);
            liveListeners.incrementAndGet();
            final AtomicBoolean removed = new AtomicBoolean();
            return () -> {
                if (removed.compareAndSet(false, true)) {
                    liveListeners.decrementAndGet();
                }
                registration.remove();
            };
        }
    }

    /**
     * A client whose cancellation-aware call blocks until its abort lever is pulled, and whose plain call answers at
     * once. It records which overload was used.
     */
    private static final class AbortableClient implements LlmClient {
        final CountDownLatch callStarted = new CountDownLatch(1);
        final AtomicBoolean abortRan = new AtomicBoolean();
        final AtomicInteger plainCalls = new AtomicInteger();
        final AtomicInteger cancellationAwareCalls = new AtomicInteger();
        Runnable beforeAnswering = () -> {
        };
        boolean ignoreTheToken;
        boolean throwCancelledUnasked;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            plainCalls.incrementAndGet();
            return LlmResponse.text("the summary");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            cancellationAwareCalls.incrementAndGet();
            if (throwCancelledUnasked) {
                throw new LlmCallCancelledException("stream closed");
            }
            beforeAnswering.run();
            if (ignoreTheToken) {
                return LlmResponse.text("the summary");
            }
            final CountDownLatch aborted = new CountDownLatch(1);
            cancellation.onCancel(() -> {
                abortRan.set(true);
                aborted.countDown();
            });
            callStarted.countDown();
            try {
                if (!aborted.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the abort lever was never pulled");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted, not aborted", e);
            }
            throw new LlmCallCancelledException("summary call aborted by cancellation");
        }

        @Override
        public String getProviderName() {
            return "Abortable";
        }
    }
}
