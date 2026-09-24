package at.aimon.core.agent.context;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
import at.aimon.core.agent.compact.NoOpPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * The default {@link ContextEngine}: today's compaction behaviour behind the engine seam.
 *
 * <p>
 * It does not decide anything itself. It routes each of the three entries to the collaborator that already made that
 * decision before the seam existed:
 *
 * <ul>
 * <li>{@link #prepare} to the {@link CompactionGuard} &mdash; {@code forceCompact} when the request is budget-forced,
 * {@code maybeCompact} otherwise, with the {@link ExecutionId}-carrying overloads for a session-less caller;
 * <li>{@link #recover} to the {@link PromptSizeRecoveryStrategy};
 * <li>{@link #compactNow} to the {@link CompactionEngine}, resetting the guard's circuit breaker on success the way
 * {@code /compact} always has.
 * </ul>
 *
 * <p>
 * <b>The transcript is rewritten in place.</b> A compaction or a recovery replaces the buffer's messages through
 * {@link TranscriptBuffer#replaceWith(List)}, exactly as before, and the view is the buffer's messages read after the
 * decision took effect. This is the v1 write mode of the session-log design; the view-state mode, where the log stays
 * append-only and the view is projected from it, replaces it once the record can persist a view state.
 *
 * <p>
 * Every collaborator defaults to its no-op, so an engine built with nothing configured behaves as
 * {@link ContextEngine#passthrough()} for {@code prepare} and {@code recover}. Thread-safe as long as its collaborators
 * are; the guard serializes compaction per session.
 */
public final class DefaultContextEngine implements ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextEngine.class);

    private final CompactionGuard compactionGuard;
    private final PromptSizeRecoveryStrategy recoveryStrategy;
    private final CompactionEngine compactionEngine;
    private final TokenEstimator tokenEstimator;

    private DefaultContextEngine(Builder builder) {
        this.compactionGuard = builder.compactionGuard != null
                ? builder.compactionGuard
                : NoOpCompactionGuard.instance();
        this.recoveryStrategy = builder.recoveryStrategy != null
                ? builder.recoveryStrategy
                : NoOpPromptSizeRecoveryStrategy.instance();
        this.compactionEngine = builder.compactionEngine;
        this.tokenEstimator = builder.tokenEstimator;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The guard {@link #prepare} delegates to. Never null. */
    public CompactionGuard getCompactionGuard() {
        return compactionGuard;
    }

    /** The strategy {@link #recover} delegates to. Never null. */
    public PromptSizeRecoveryStrategy getRecoveryStrategy() {
        return recoveryStrategy;
    }

    /** The engine {@link #compactNow} delegates to, if one is configured. */
    public Optional<CompactionEngine> getCompactionEngine() {
        return Optional.ofNullable(compactionEngine);
    }

    @Override
    public ContextDecision prepare(ContextRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final HookRegistry hookRegistry = requireHookRegistry(request);
        final Environment environment = requireEnvironment(request);
        final ExecutionId executionId = request.getCaller().getExecutionId().orElse(null);

        final CompactionDecision decision;
        if (executionId == null) {
            decision = request.isBudgetForced()
                    ? compactionGuard.forceCompact(buffer, request.getModel(), hookRegistry, environment)
                    : compactionGuard.maybeCompact(buffer, request.getModel(), hookRegistry, environment);
        } else {
            decision = request.isBudgetForced()
                    ? compactionGuard.forceCompact(buffer, request.getModel(), hookRegistry, environment, executionId)
                    : compactionGuard.maybeCompact(buffer, request.getModel(), hookRegistry, environment, executionId);
        }
        // Read after the guard: a compaction rewrote the buffer in place, and the view is what it left behind.
        return ContextDecision.from(decision, viewOf(request));
    }

    @Override
    public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(error, "error cannot be null");
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final PromptSizeRecoveryDecision decision = recoveryStrategy.recover(buffer.getMessages(), error);
        if (decision.getAction() != PromptSizeRecoveryDecision.Action.RETRY) {
            log.warn("Prompt-too-long recovery declined ({})", decision.getReason());
            return Optional.empty();
        }
        final List<Message> recovered = decision.getRecoveredMessages()
                .orElseThrow(() -> new IllegalStateException("RETRY decision must carry recoveredMessages"));
        log.warn("Prompt-too-long recovery applied: {}", decision.getReason());
        buffer.replaceWith(recovered);
        return Optional.of(viewOf(request));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A MANUAL compaction through the configured {@link CompactionEngine}. An exception the engine throws propagates to
     * the caller, which is where {@code /compact} has always reported it. On success the guard's circuit breaker is
     * reset for the buffer's session, so AUTO compaction resumes after the user compacted by hand.
     */
    @Override
    public CompactionResult compactNow(ContextRequest request, String instructions) {
        Objects.requireNonNull(request, "request cannot be null");
        if (compactionEngine == null) {
            final Instant now = Instant.now();
            return CompactionResult.failure(new IllegalStateException("no CompactionEngine configured"),
                    CompactionMetadata.builder().trigger(CompactionTrigger.MANUAL).startedAt(now).completedAt(now)
                            .build());
        }
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final CompactionRequest compactionRequest = CompactionRequest.builder().transcriptBuffer(buffer)
                .trigger(CompactionTrigger.MANUAL).model(request.getModel()).hookRegistry(requireHookRegistry(request))
                .environment(requireEnvironment(request)).customInstructions(instructions)
                .callMetadata(request.getCallMetadata().orElse(null))
                .executionId(request.getCaller().getExecutionId().orElse(null)).build();
        final CompactionResult result = compactionEngine.compact(compactionRequest);
        if (result != null && result.isSuccess()) {
            compactionGuard.recordExternalSuccess(buffer.getSessionId());
        }
        return result;
    }

    private ContextView viewOf(ContextRequest request) {
        final List<Message> messages = request.getTranscriptBuffer().getMessages();
        if (tokenEstimator == null) {
            return ContextView.of(messages);
        }
        return ContextView.of(messages, tokenEstimator.estimate(request.getSystemPrompt(), messages));
    }

    private static HookRegistry requireHookRegistry(ContextRequest request) {
        return request.getHookRegistry()
                .orElseThrow(() -> new IllegalArgumentException("DefaultContextEngine requires a HookRegistry"));
    }

    private static Environment requireEnvironment(ContextRequest request) {
        return request.getEnvironment()
                .orElseThrow(() -> new IllegalArgumentException("DefaultContextEngine requires an Environment"));
    }

    @Override
    public String toString() {
        return "DefaultContextEngine{guard=" + compactionGuard.getClass().getSimpleName() + ", recovery="
                + recoveryStrategy.getClass().getSimpleName() + ", engine="
                + (compactionEngine != null ? compactionEngine.getClass().getSimpleName() : "none") + '}';
    }

    /** Builder for {@link DefaultContextEngine}. Every collaborator is optional. */
    public static final class Builder {
        private CompactionGuard compactionGuard;
        private PromptSizeRecoveryStrategy recoveryStrategy;
        private CompactionEngine compactionEngine;
        private TokenEstimator tokenEstimator;

        private Builder() {
        }

        /**
         * @param compactionGuard
         *            the guard deciding AUTO compaction, or {@code null} for {@link NoOpCompactionGuard}
         * @return this builder
         */
        public Builder compactionGuard(CompactionGuard compactionGuard) {
            this.compactionGuard = compactionGuard;
            return this;
        }

        /**
         * @param recoveryStrategy
         *            the prompt-too-long fallback, or {@code null} for {@link NoOpPromptSizeRecoveryStrategy}
         * @return this builder
         */
        public Builder recoveryStrategy(PromptSizeRecoveryStrategy recoveryStrategy) {
            this.recoveryStrategy = recoveryStrategy;
            return this;
        }

        /**
         * @param compactionEngine
         *            the engine performing {@link #compactNow} compactions, or {@code null} to make {@code compactNow}
         *            fail
         * @return this builder
         */
        public Builder compactionEngine(CompactionEngine compactionEngine) {
            this.compactionEngine = compactionEngine;
            return this;
        }

        /**
         * @param tokenEstimator
         *            estimates {@link ContextView#getEstimatedTokens()}, or {@code null} to leave views unestimated
         * @return this builder
         */
        public Builder tokenEstimator(TokenEstimator tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        public DefaultContextEngine build() {
            return new DefaultContextEngine(this);
        }
    }
}
