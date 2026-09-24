package at.aimon.core.agent.context;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
import at.aimon.core.agent.compact.NoOpPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.SummaryRequest;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * The default {@link ContextEngine}: today's compaction behaviour behind the engine seam.
 *
 * <p>
 * The decisions are the ones taken before the seam existed, by the same collaborators:
 *
 * <ul>
 * <li>{@link #prepare} &mdash; the {@link CompactionGuard}'s ladder (blocking &rarr; circuit breaker &rarr; auto
 * &rarr; warning), its per-session lock and its precondition; {@code forceCompact} semantics when the request is
 * budget-forced;
 * <li>{@link #recover} &mdash; the {@link PromptSizeRecoveryStrategy};
 * <li>{@link #compactNow} &mdash; a full MANUAL compaction through the {@link CompactionEngine}, resetting the guard's
 * circuit breaker on success the way {@code /compact} always has.
 * </ul>
 *
 * <p>
 * <b>Two modes, chosen per transcript by its log format.</b> What the model sees is the same in both; what differs is
 * whether the record keeps the original.
 *
 * <ul>
 * <li><b>View mode</b> &mdash; a {@linkplain SessionLogFormat#V2 version-2} log. The log stays append-only. A
 * compaction summarizes the whole view through {@link CompactionEngine#summarize} and records the summary as the view
 * state's span ({@link TranscriptBuffer#summarizeView}), so the view is the {@code [boundary, summary]} pair and the
 * log still holds every message. A recovery drops what the strategy left out, as seq ranges
 * ({@link TranscriptBuffer#dropFromView}) mapped back by position (see {@link RecoveryDiff}). The view is
 * {@link ViewProjection projected} from the log and the view state (context-engine §4).
 * <li><b>In place</b> &mdash; a version-1 log, which cannot persist a view state. A compaction or a recovery replaces
 * the buffer's messages through {@link TranscriptBuffer#replaceWith(List)}, and the view is the buffer as the decision
 * left it. It is also where a version-2 transcript falls back to, with a one-time WARN, when this engine lacks what
 * view mode needs: a {@link CompactionGuard} other than {@link DefaultCompactionGuard} or {@link NoOpCompactionGuard}
 * (its contract is to rewrite the buffer, which cannot be translated into view state operations), or a
 * {@link CompactionEngine} that does not {@linkplain CompactionEngine#supportsSummarize() summarize}. That happens only
 * on a node configured to write version 1 meeting a record already upgraded; a node configured to write version 2
 * refuses the combination when it is built (see {@link Builder#writeFormat}).
 * </ul>
 *
 * <p>
 * Every collaborator defaults to its no-op, so an engine built with nothing configured behaves as
 * {@link ContextEngine#passthrough()} for {@code prepare} and {@code recover}. Thread-safe as long as its collaborators
 * are; the guard serializes compaction per session.
 */
@SuppressWarnings("deprecation") // the in-place mode is built on the deprecated guard and compact() on purpose
public final class DefaultContextEngine implements ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextEngine.class);

    private final CompactionGuard compactionGuard;
    private final PromptSizeRecoveryStrategy recoveryStrategy;
    private final CompactionEngine compactionEngine;
    private final TokenEstimator tokenEstimator;
    private final boolean viewCapable;
    private final AtomicBoolean fallbackWarned = new AtomicBoolean();

    private DefaultContextEngine(Builder builder) {
        this.compactionGuard = builder.compactionGuard != null
                ? builder.compactionGuard
                : NoOpCompactionGuard.instance();
        this.recoveryStrategy = builder.recoveryStrategy != null
                ? builder.recoveryStrategy
                : NoOpPromptSizeRecoveryStrategy.instance();
        this.compactionEngine = builder.compactionEngine;
        this.tokenEstimator = builder.tokenEstimator;
        final String missing = viewModeGap(compactionGuard, compactionEngine);
        this.viewCapable = missing == null;
        if (builder.writeFormat == SessionLogFormat.V2 && missing != null) {
            throw new IllegalStateException("The version-2 write mode keeps the log append-only, which this context"
                    + " engine cannot do: " + missing);
        }
    }

    /**
     * Returns why view mode is not possible with these collaborators, or null when it is.
     */
    private static String viewModeGap(CompactionGuard guard, CompactionEngine engine) {
        if (guard instanceof NoOpCompactionGuard) {
            return engine == null || engine.supportsSummarize()
                    ? null
                    : engine.getClass().getName() + " does not support summarize()";
        }
        if (guard.getClass() != DefaultCompactionGuard.class) {
            return "a CompactionGuard (" + guard.getClass().getName() + ") can only be injected in the version-1 write"
                    + " mode; implement ContextEngine to change the decision rules";
        }
        if (engine == null) {
            return "no CompactionEngine is configured to summarize with";
        }
        if (!engine.supportsSummarize()) {
            return engine.getClass().getName() + " does not support summarize()";
        }
        return null;
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
        if (inViewMode(buffer)) {
            return prepareView(request);
        }
        final int sizeBefore = buffer.size();

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
        return ContextDecision.from(decision, viewOf(request), sizeBefore);
    }

    private ContextDecision prepareView(ContextRequest request) {
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final ViewProjection before = ViewProjection.of(buffer.getLogState());
        final CompactionDecision decision;
        if (compactionGuard instanceof DefaultCompactionGuard rules) {
            decision = rules.decide(buffer.getSessionId(), request.getSystemPrompt(), before.getMessages(),
                    request.getModel(), request.isBudgetForced(),
                    forced -> summarizeIntoView(request, CompactionTrigger.AUTO, null, before));
        } else {
            decision = CompactionDecision.none();
        }
        return ContextDecision.from(decision, viewOf(request, ViewProjection.of(buffer.getLogState())), before.size());
    }

    @Override
    public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(error, "error cannot be null");
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        if (inViewMode(buffer)) {
            return recoverView(request, error);
        }
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
     * Recovery in view mode: the strategy is shown the view, and what it left out is dropped from the view as seq
     * ranges — for {@link at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy}, the one user message it
     * removes, as {@code drop(s, s + 1)}. Every range is checked on a copy first, so an answer is applied whole or
     * not at all.
     */
    private Optional<ContextView> recoverView(ContextRequest request, LlmPromptTooLongException error) {
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final SessionLogState state = buffer.getLogState();
        final ViewProjection view = ViewProjection.of(state);
        final PromptSizeRecoveryDecision decision = recoveryStrategy.recover(view.getMessages(), error);
        if (decision.getAction() != PromptSizeRecoveryDecision.Action.RETRY) {
            log.warn("Prompt-too-long recovery declined ({})", decision.getReason());
            return Optional.empty();
        }
        final List<Message> answer = decision.getRecoveredMessages()
                .orElseThrow(() -> new IllegalStateException("RETRY decision must carry recoveredMessages"));
        final RecoveryDiff diff = RecoveryDiff.between(view, answer);
        if (diff.isRefused()) {
            log.warn("Prompt-too-long recovery refused: {} (strategy said: {})", diff.getRefusal(),
                    decision.getReason());
            return Optional.empty();
        }
        SessionLogState trial = state;
        try {
            for (SeqRange range : diff.getRanges()) {
                trial = trial.drop(range.getFromSeq(), range.getToSeq());
            }
        } catch (IllegalArgumentException e) {
            log.warn("Prompt-too-long recovery refused: {} (strategy said: {})", e.getMessage(), decision.getReason());
            return Optional.empty();
        }
        for (SeqRange range : diff.getRanges()) {
            buffer.dropFromView(range.getFromSeq(), range.getToSeq());
        }
        log.warn("Prompt-too-long recovery applied: {} (dropped {} from the view)", decision.getReason(),
                diff.getRanges());
        return Optional.of(viewOf(request, ViewProjection.of(buffer.getLogState())));
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
        if (inViewMode(buffer)) {
            final CompactionResult result = summarizeIntoView(request, CompactionTrigger.MANUAL, instructions,
                    ViewProjection.of(buffer.getLogState()));
            if (result.isSuccess()) {
                compactionGuard.recordExternalSuccess(buffer.getSessionId());
            }
            return result;
        }
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

    /**
     * Whether {@code buffer} is served in view mode: a version-2 log, and collaborators that can keep it append-only.
     * A version-2 log this engine cannot serve that way is compacted in place, with one WARN per engine.
     */
    private boolean inViewMode(TranscriptBuffer buffer) {
        if (buffer.getFormat() != SessionLogFormat.V2) {
            return false;
        }
        if (viewCapable) {
            return true;
        }
        if (fallbackWarned.compareAndSet(false, true)) {
            log.warn(
                    "Session {} has a version-2 log, but this context engine cannot keep it append-only ({}); it is"
                            + " compacted in place instead",
                    buffer.getSessionId(), viewModeGap(compactionGuard, compactionEngine));
        }
        return false;
    }

    /**
     * Summarizes the whole view and records the summary as the view state's span over {@code [floorSeq, nextSeq)} —
     * a span only widens, and this one covers every live seq, so it absorbs any span held. The log is not touched.
     * PostCompact hooks fire through {@link CompactionEngine#summaryInstalled} once the span is in place.
     */
    private CompactionResult summarizeIntoView(ContextRequest request, CompactionTrigger trigger, String instructions,
            ViewProjection before) {
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        final SessionLogState state = buffer.getLogState();
        if (before.size() == 0) {
            final Instant now = Instant.now();
            return CompactionResult.failure(new IllegalStateException("nothing to compact: the view is empty"),
                    CompactionMetadata.builder().trigger(trigger).startedAt(now).completedAt(now).build());
        }
        final SummaryRequest summaryRequest = SummaryRequest.builder().messages(before.getMessages())
                .systemPrompt(request.getSystemPrompt()).sessionId(buffer.getSessionId())
                .executionId(request.getCaller().getExecutionId().orElse(null)).trigger(trigger)
                .model(request.getModel()).hookRegistry(requireHookRegistry(request))
                .environment(requireEnvironment(request)).customInstructions(instructions)
                .callMetadata(request.getCallMetadata().orElse(null)).build();
        final CompactionResult summarized = compactionEngine.summarize(summaryRequest);
        if (summarized == null || summarized.isFailure()) {
            return summarized;
        }
        final CompactionMetadata produced = summarized.getMetadata();
        final String summaryText = summarized.getSummaryText().orElse("");
        final SummarySpan span = SummarySpan.builder().fromSeq(state.getFloorSeq()).toSeq(state.getNextSeq())
                .summaryText(summaryText).boundaryId(UUID.randomUUID().toString()).trigger(trigger.name())
                .preTokenCount(produced.getPreCompactTokenCount()).messagesSummarized(before.size())
                .discoveredToolNames(produced.getDiscoveredToolNames()).build();
        try {
            buffer.summarizeView(span);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Compaction summary could not be recorded for session {}: {}", buffer.getSessionId(),
                    e.getMessage());
            return CompactionResult.failure(e, produced);
        }
        final int postTokenCount = tokenEstimator == null
                ? 0
                : tokenEstimator.estimate(request.getSystemPrompt(),
                        ViewProjection.of(buffer.getLogState()).getMessages());
        final CompactionResult installed = CompactionResult.success(summaryText,
                CompactionMetadata.builder().trigger(produced.getTrigger())
                        .preCompactTokenCount(produced.getPreCompactTokenCount()).postCompactTokenCount(postTokenCount)
                        .messagesSummarized(produced.getMessagesSummarized()).startedAt(produced.getStartedAt())
                        .completedAt(Instant.now()).discoveredToolNames(produced.getDiscoveredToolNames()).build());
        compactionEngine.summaryInstalled(summaryRequest, installed, buffer);
        log.info("Compaction recorded in the view: {} view messages summarized over seqs {} (trigger={})",
                before.size(), span.getRange(), trigger);
        return installed;
    }

    private ContextView viewOf(ContextRequest request) {
        return viewOf(request.getSystemPrompt(), request.getTranscriptBuffer().getMessages());
    }

    private ContextView viewOf(ContextRequest request, ViewProjection projection) {
        return viewOf(request.getSystemPrompt(), projection.getMessages());
    }

    private ContextView viewOf(String systemPrompt, List<Message> messages) {
        if (tokenEstimator == null) {
            return ContextView.of(messages);
        }
        return ContextView.of(messages, tokenEstimator.estimate(systemPrompt, messages));
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
        private SessionLogFormat writeFormat = SessionLogFormat.V1;

        private Builder() {
        }

        /**
         * Declares the log format this node writes, so a configuration that cannot serve it fails when the engine is
         * built rather than on the first compaction.
         *
         * <p>
         * With {@link SessionLogFormat#V2}, {@link #build()} refuses a {@link CompactionGuard} other than
         * {@link DefaultCompactionGuard} or {@link NoOpCompactionGuard}, and a {@link CompactionEngine} that does not
         * {@linkplain CompactionEngine#supportsSummarize() summarize} — neither can keep the log append-only
         * (context-engine §8.2). The mode itself is chosen per transcript by its log format; a version-1 node still
         * serves an upgraded record in view mode when it can.
         *
         * @param writeFormat
         *            the format this node writes (must not be null; default {@link SessionLogFormat#V1})
         * @return this builder
         */
        public Builder writeFormat(SessionLogFormat writeFormat) {
            this.writeFormat = Objects.requireNonNull(writeFormat, "writeFormat cannot be null");
            return this;
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

        /**
         * @return the engine (never null)
         * @throws IllegalStateException
         *             if the write format is {@link SessionLogFormat#V2} and the collaborators cannot keep the log
         *             append-only
         */
        public DefaultContextEngine build() {
            return new DefaultContextEngine(this);
        }
    }
}
