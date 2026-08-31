package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Unit tests for the {@link CompactionGuard#forceCompact} pathway.
 *
 * <p>
 * {@link DefaultCompactionGuard#forceCompact} lowers the effective auto-compact trigger to the (lower) warning band,
 * so a session sitting in the warning band is left alone by {@link CompactionGuard#maybeCompact} but gets
 * compacted immediately by {@code forceCompact}. Above the model's own auto-compact threshold, both methods behave
 * identically.
 *
 * <p>
 * Threshold arithmetic (see {@link ModelContextLimits}), derived from the fixed limits configured in {@link #setUp()}:
 *
 * <pre>
 * effectiveWindow      = contextWindow - reservedOutputTokens = 10_000 - 1_000 = 9_000
 * autoCompactThreshold = effectiveWindow - autoCompactBuffer  = 9_000  - 2_000 = 7_000
 * warningThreshold     = autoCompactThreshold - warningBuffer = 7_000  - 1_000 = 6_000
 * blockingLimit        = effectiveWindow - blockingBuffer     = 9_000  - 500   = 8_500
 * </pre>
 *
 * <p>
 * A {@link FixedTokenEstimator} pins the estimate at a chosen constant, independent of message content, so the
 * warning band ({@code [6_000, 7_000)}) and the auto-compact band ({@code [7_000, 8_500)}) can be targeted precisely.
 */
class DefaultCompactionGuardForceCompactTest {

    /** In the warning band: {@literal >=} warningThreshold(6_000) and {@literal <} autoCompactThreshold(7_000). */
    private static final int WARNING_BAND_ESTIMATE = 6_500;

    /** In the auto-compact band: {@literal >=} autoCompactThreshold(7_000) and {@literal <} blockingLimit(8_500). */
    private static final int AUTO_BAND_ESTIMATE = 7_500;

    private HookRegistry hookRegistry;
    private Environment environment;
    private ModelContextWindowRegistry modelContextWindowRegistry;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
        modelContextWindowRegistry = InMemoryModelContextWindowRegistry.builder()
                .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                        .autoCompactBuffer(2_000).warningBuffer(1_000).blockingBuffer(500).build())
                .build();
    }

    @Test
    void warningBandEstimateIsOnlyCompactedByForceCompact() {
        StubEngine engine = new StubEngine();
        DefaultCompactionGuard guard = newGuard(engine, WARNING_BAND_ESTIMATE);
        TranscriptBuffer memory = freshMemory();

        // maybeCompact: estimate is in the warning band but below the model's own auto-compact threshold -> WARN,
        // no compaction performed.
        CompactionDecision maybeDecision = guard.maybeCompact(memory, model(), hookRegistry, environment);
        assertThat(maybeDecision.getAction()).isEqualTo(CompactionDecision.Action.WARN);
        assertThat(maybeDecision.getCompactionResult()).isEmpty();
        assertThat(engine.callCount.get()).isZero();

        // forceCompact: same session, same estimate, but the effective trigger is lowered to the warning
        // threshold -> COMPACT is performed. This is the core new behaviour.
        CompactionDecision forceDecision = guard.forceCompact(memory, model(), hookRegistry, environment);
        assertThat(forceDecision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(forceDecision.getCompactionResult()).isPresent();
        assertThat(engine.callCount.get()).isEqualTo(1);
    }

    @Test
    void aboveAutoCompactThresholdBothMaybeCompactAndForceCompactPerformCompaction() {
        StubEngine engine = new StubEngine();
        DefaultCompactionGuard guard = newGuard(engine, AUTO_BAND_ESTIMATE);

        // Separate sessions so neither call is affected by the other's post-compaction state.
        CompactionDecision maybeDecision = guard.maybeCompact(freshMemory(), model(), hookRegistry, environment);
        assertThat(maybeDecision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(maybeDecision.getCompactionResult()).isPresent();

        CompactionDecision forceDecision = guard.forceCompact(freshMemory(), model(), hookRegistry, environment);
        assertThat(forceDecision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(forceDecision.getCompactionResult()).isPresent();

        assertThat(engine.callCount.get()).isEqualTo(2);
    }

    @Test
    void noOpGuardForceCompactDelegatesToMaybeCompactAndNeverCompacts() {
        TranscriptBuffer memory = freshMemory();

        CompactionDecision decision = NoOpCompactionGuard.instance().forceCompact(memory, model(), hookRegistry,
                environment);

        // NoOpCompactionGuard does not override forceCompact, so the CompactionGuard#forceCompact default delegates
        // to maybeCompact -- which NoOpCompactionGuard always answers with NONE("compaction disabled").
        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(decision.getReason()).isEqualTo("compaction disabled");
        assertThat(decision.getCompactionResult()).isEmpty();
    }

    private DefaultCompactionGuard newGuard(CompactionEngine engine, int fixedEstimate) {
        return new DefaultCompactionGuard(engine, modelContextWindowRegistry, new FixedTokenEstimator(fixedEstimate));
    }

    private static LlmModel model() {
        return LlmModel.builder().name("test-model").build();
    }

    private static TranscriptBuffer freshMemory() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("hello");
        memory.addAssistantMessage("hi");
        return memory;
    }

    /**
     * Returns a fixed token estimate regardless of message content, letting tests pin the estimate precisely inside a
     * chosen threshold band.
     */
    private static final class FixedTokenEstimator implements TokenEstimator {
        private final int fixedEstimate;

        FixedTokenEstimator(int fixedEstimate) {
            this.fixedEstimate = fixedEstimate;
        }

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            return fixedEstimate;
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

    /** Records the number of invocations and always returns a successful {@link CompactionResult}. */
    private static final class StubEngine implements CompactionEngine {
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public CompactionResult compact(CompactionRequest request) {
            callCount.incrementAndGet();
            final CompactionMetadata metadata = CompactionMetadata.builder().trigger(request.getTrigger())
                    .startedAt(Instant.now()).completedAt(Instant.now()).build();
            return CompactionResult.success("stub", metadata);
        }
    }
}
