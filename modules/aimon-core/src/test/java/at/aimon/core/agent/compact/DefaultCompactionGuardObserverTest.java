package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * {@link DefaultCompactionGuard} and the request's {@link CompactionObserver}: one attempt around each engine call,
 * none for a pass the ladder ends without calling the engine.
 *
 * <p>
 * Limits: effective window 9000, auto 7200, warning 6200, blocking 8500.
 */
@DisplayName("DefaultCompactionGuard: the compaction observer")
class DefaultCompactionGuardObserverTest {

    private static final LlmModel MODEL = LlmModel.builder().name("m").build();
    private static final LlmCallMetadata CALLER = LlmCallMetadata.builder().traceId("caller-session").build();

    private final InMemoryCompactionFailureStore failures = new InMemoryCompactionFailureStore();
    private RecordingEngine engine;
    private RecordingCompactionObserver observer;
    private TranscriptBuffer buffer;

    @BeforeEach
    void setUp() {
        engine = new RecordingEngine();
        observer = new RecordingCompactionObserver();
        buffer = new TranscriptBuffer(SessionId.generate());
        buffer.addUserMessage("hello");
        buffer.addAssistantMessage("hi");
    }

    private DefaultCompactionGuard guard(int estimate) {
        return new DefaultCompactionGuard(engine,
                InMemoryModelContextWindowRegistry.builder()
                        .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                                .autoCompactRatio(0.8).warningBuffer(1_000).blockingBuffer(500).build())
                        .build(),
                new FixedEstimator(estimate), 3, 16, failures);
    }

    private CompactionGuardRequest request(boolean budgetForced) {
        return CompactionGuardRequest.builder().transcriptBuffer(buffer).model(MODEL)
                .hookRegistry(new DefaultHookRegistry()).budgetForced(budgetForced).callMetadata(CALLER)
                .compactionObserver(observer).build();
    }

    @Test
    @DisplayName("an AUTO compaction is one attempt around the engine call, and the engine sees its metadata")
    void oneAttemptAroundTheEngineCall() {
        final CompactionDecision decision = guard(7_500).maybeCompact(request(false));

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        final RecordingCompactionObserver.RecordedAttempt attempt = observer.single();
        assertThat(attempt.start().getTrigger()).isEqualTo(CompactionTrigger.AUTO);
        assertThat(attempt.start().getStrategy()).isEqualTo(CompactionStart.IN_PLACE);
        assertThat(attempt.start().isBlockingForced()).isFalse();
        assertThat(attempt.start().getMessagesBefore()).isEqualTo(2);
        assertThat(attempt.enriched()).containsExactly(CALLER);
        assertThat(engine.requests).singleElement().satisfies(r -> assertThat(r.getCallMetadata()).hasValueSatisfying(
                m -> assertThat(m.getTags()).containsEntry(RecordingCompactionObserver.ATTEMPT_TAG, attempt.number())));
        assertThat(attempt.recorded()).containsExactly(decision.getCompactionResult().orElseThrow());
        assertThat(attempt.closes()).isEqualTo(1);
    }

    @Test
    @DisplayName("at the blocking limit the attempt says it was forced")
    void blockingForced() {
        guard(9_000).maybeCompact(request(false));

        assertThat(observer.single().start().isBlockingForced()).isTrue();
    }

    @Test
    @DisplayName("a budget-forced compaction from the warning band is one attempt")
    void budgetForced() {
        guard(6_500).maybeCompact(request(true));

        assertThat(observer.single().start().isBlockingForced()).isFalse();
    }

    @Test
    @DisplayName("an engine that throws is recorded as the failure the guard synthesizes")
    void engineThrows() {
        engine.throwing = true;

        final CompactionDecision decision = guard(7_500).maybeCompact(request(false));

        final CompactionResult recorded = observer.single().result();
        assertThat(recorded.isFailure()).isTrue();
        assertThat(recorded.getError()).containsInstanceOf(IllegalStateException.class);
        assertThat(decision.getCompactionResult()).containsSame(recorded);
        assertThat(observer.single().closes()).isEqualTo(1);
    }

    @Test
    @DisplayName("no attempt below the warning band, in the warning band, or with the breaker open")
    void noAttemptWithoutAnEngineCall() {
        assertThat(guard(100).maybeCompact(request(false)).getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(guard(6_500).maybeCompact(request(false)).getAction()).isEqualTo(CompactionDecision.Action.WARN);
        for (int i = 0; i < 3; i++) {
            failures.recordFailure(buffer.getSessionId());
        }
        assertThat(guard(7_500).maybeCompact(request(false)).getReason()).contains("circuit breaker");

        assertThat(observer.attempts()).isEmpty();
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("no attempt when the precondition is unmet at the blocking limit")
    void noAttemptWhenThePreconditionIsUnmet() {
        buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));

        final CompactionDecision decision = guard(9_000).maybeCompact(request(false));

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.WARN);
        assertThat(observer.attempts()).isEmpty();
    }

    @Test
    @DisplayName("no attempt for a pass that finds another compaction of the session in progress")
    void noAttemptUnderContention() throws Exception {
        final DefaultCompactionGuard guard = guard(7_500);
        final RecordingCompactionObserver concurrentObserver = new RecordingCompactionObserver();
        final AtomicReference<CompactionDecision> concurrent = new AtomicReference<>();
        engine.during = () -> {
            try {
                concurrent.set(CompletableFuture.supplyAsync(() -> guard.maybeCompact(CompactionGuardRequest.builder()
                        .transcriptBuffer(buffer).model(MODEL).hookRegistry(new DefaultHookRegistry())
                        .compactionObserver(concurrentObserver).build())).get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        guard.maybeCompact(request(false));

        assertThat(concurrent.get().getReason()).contains("concurrent");
        assertThat(concurrentObserver.attempts()).isEmpty();
        assertThat(observer.attempts()).hasSize(1);
    }

    @Test
    @DisplayName("a positional entry point observes nothing and hands the engine no metadata")
    void positionalEntryPoints() {
        guard(7_500).maybeCompact(buffer, MODEL, new DefaultHookRegistry());

        assertThat(engine.requests).singleElement().satisfies(r -> assertThat(r.getCallMetadata()).isEmpty());
        assertThat(observer.attempts()).isEmpty();
    }

    @Test
    @DisplayName("without an observer the engine's request carries the caller's metadata instance itself")
    void noObserverLeavesTheMetadataAsItWas() {
        guard(7_500).maybeCompact(CompactionGuardRequest.builder().transcriptBuffer(buffer).model(MODEL)
                .hookRegistry(new DefaultHookRegistry()).callMetadata(CALLER).build());

        assertThat(engine.requests).singleElement()
                .satisfies(r -> assertThat(r.getCallMetadata()).containsSame(CALLER));
    }

    private static final class FixedEstimator implements TokenEstimator {
        private final int estimate;

        FixedEstimator(int estimate) {
            this.estimate = estimate;
        }

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            return estimate;
        }

        @Override
        public int estimateMessage(Message message) {
            return 0;
        }

        @Override
        public int estimateText(String text) {
            return 0;
        }
    }

    private static final class RecordingEngine implements CompactionEngine {
        private final List<CompactionRequest> requests = new CopyOnWriteArrayList<>();
        private boolean throwing;
        private Runnable during = () -> {
        };

        @Override
        public CompactionResult compact(CompactionRequest request) {
            requests.add(request);
            during.run();
            if (throwing) {
                throw new IllegalStateException("engine blew up");
            }
            final Instant now = Instant.now();
            return CompactionResult.success("summary",
                    CompactionMetadata.builder().trigger(request.getTrigger()).startedAt(now).completedAt(now).build());
        }
    }
}
