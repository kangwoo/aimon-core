package at.aimon.core.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionBlockedByHookException;
import at.aimon.core.agent.compact.CompactionContendedException;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionFailureStore;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionReentrancyException;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.compact.NoOpPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.SummaryRequest;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.LegalCuts;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * A {@link ContextEngine} for long conversations: it keeps the start and the recent end of the view verbatim and
 * summarizes the middle, widening one summary span a little at a time (context-engine §5).
 *
 * <pre>
 * log:   s0 s1 s2 ............................................ sN
 * view: [ head ][ B  S ][ verbatim ............ ][ tail ...........]
 * </pre>
 *
 * <ul>
 * <li><b>Head</b> &mdash; from {@code floorSeq} up to and including the first {@link LogOrigin#CONVERSATION} user
 * message: what the session is for. Synthetic entries before it belong to the head without ending it. A head whose
 * conversation part exceeds {@code headTokenRatio} of the effective window is empty, and so is one a previous
 * compaction already absorbed or a log migrated from version 1 that starts with a boundary marker.
 * <li><b>Span</b> &mdash; one summary, recorded in the view state. A new compaction widens it, and the summary is
 * <em>updated</em>: the previous summary plus what the span newly absorbs go to
 * {@link CompactionEngine#summarize} as a rolling summary.
 * <li><b>Tail</b> &mdash; the most recent {@code tailTokenRatio} of the window, cut at a legal cut and snapped to a
 * user message within &plusmn;10% of the budget.
 * </ul>
 *
 * <p>
 * <b>When.</b> The decision ladder is the default engine's (blocking &rarr; circuit breaker &rarr; auto &rarr;
 * warning), with an earlier trigger: {@code rollingAuto = min(autoCompactRatio × effective, autoCompactThreshold)},
 * {@code warning = rollingAuto − warningBuffer}, and a budget-forced call compacts at {@code warning}. Every size
 * counts the system prompt. The cut is chosen from estimates <em>before</em> the summary is asked for, retreating from
 * the tail budget to half of it to the last legal cut; if even that would not end below the threshold the engine warns
 * instead of compacting ({@link CompactionKind#FALLBACK}), except at the blocking limit, where the head is absorbed
 * too. Before any summary, eliding large tool results in the part about to be absorbed is tried first
 * ({@link CompactionKind#PRUNE}); when that alone is enough nothing is summarized.
 *
 * <p>
 * <b>Falling back.</b> Rolling only pays when a compacted view ends below the warning band:
 * {@code system + head + summaryTokenRatio × effective + minTailRatio × effective < warning}. Where it does not — a
 * small window, or a large system prompt, known only at call time — this engine behaves as {@link DefaultContextEngine}
 * for that call, and warns once per model. A version-1 transcript, which cannot keep a view state, falls back the
 * same way. Prompt-too-long recovery is always the default engine's: drop what the recovery strategy leaves out.
 *
 * <p>
 * The engine never registers the post-compaction restore hooks: the tail is already verbatim, and re-attached files
 * would pile up in the tail and bring the next compaction forward (context-engine §5.7).
 *
 * <p>
 * Thread-safe. Work on one session is serialized by a node-local {@code tryLock}; the circuit breaker lives in the
 * supplied {@link CompactionFailureStore}.
 */
public final class RollingContextEngine implements ContextEngine {

    public static final double DEFAULT_AUTO_COMPACT_RATIO = 0.6;
    public static final double DEFAULT_HEAD_TOKEN_RATIO = 0.05;
    public static final double DEFAULT_TAIL_TOKEN_RATIO = 0.20;
    public static final double DEFAULT_SUMMARY_TOKEN_RATIO = 0.08;
    public static final double DEFAULT_MIN_TAIL_RATIO = 0.05;
    public static final int DEFAULT_PRUNE_MIN_TOKENS = 500;

    /** Stands before an absorbed range that does not start with a user message, so the summary call's does. */
    static final String CONTINUATION_NOTE = "[The earlier part of this conversation is covered by the previous"
            + " summary.]";

    private static final Logger log = LoggerFactory.getLogger(RollingContextEngine.class);

    private static final int MAX_TRACKED_SESSIONS = DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS;
    private static final double SNAP_BAND = 0.10;

    private final CompactionEngine compactionEngine;
    private final ModelContextWindowRegistry modelContextWindowRegistry;
    private final TokenEstimator tokenEstimator;
    private final CompactionFailureStore failureStore;
    private final int maxConsecutiveFailures;
    private final LlmModel summaryModel;
    private final double autoCompactRatio;
    private final double headTokenRatio;
    private final double tailTokenRatio;
    private final double summaryTokenRatio;
    private final double minTailRatio;
    private final int pruneMinTokens;
    private final DefaultContextEngine fallback;
    private final Map<SessionId, ReentrantLock> sessionLocks;
    private final Set<String> unsustainableWarned = ConcurrentHashMap.newKeySet();
    private final Set<SessionId> versionOneWarned = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("deprecation") // the fallback wraps the guard on purpose — it is the default engine's rules
    private RollingContextEngine(Builder builder) {
        this.compactionEngine = Objects.requireNonNull(builder.compactionEngine, "compactionEngine cannot be null");
        this.modelContextWindowRegistry = Objects.requireNonNull(builder.modelContextWindowRegistry,
                "modelContextWindowRegistry cannot be null");
        this.tokenEstimator = Objects.requireNonNull(builder.tokenEstimator, "tokenEstimator cannot be null");
        if (builder.writeFormat == SessionLogFormat.V1) {
            throw new IllegalStateException("RollingContextEngine keeps its summary span in the view state, which the"
                    + " version-1 write mode cannot store; switch the session log write format to version 2, or use"
                    + " the default context engine");
        }
        if (!compactionEngine.supportsSummarize()) {
            throw new IllegalStateException("RollingContextEngine needs a CompactionEngine that supports summarize(): "
                    + compactionEngine.getClass().getName());
        }
        if (builder.maxConsecutiveFailures < 1) {
            throw new IllegalArgumentException(
                    "maxConsecutiveFailures must be >= 1, got: " + builder.maxConsecutiveFailures);
        }
        this.failureStore = builder.failureStore != null ? builder.failureStore : new InMemoryCompactionFailureStore();
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
        this.summaryModel = builder.summaryModel;
        this.autoCompactRatio = requireRatio(builder.autoCompactRatio, "autoCompactRatio");
        this.headTokenRatio = requireRatio(builder.headTokenRatio, "headTokenRatio");
        this.tailTokenRatio = requireRatio(builder.tailTokenRatio, "tailTokenRatio");
        this.summaryTokenRatio = requireRatio(builder.summaryTokenRatio, "summaryTokenRatio");
        this.minTailRatio = requireRatio(builder.minTailRatio, "minTailRatio");
        if (builder.pruneMinTokens < 1) {
            throw new IllegalArgumentException("pruneMinTokens must be >= 1, got: " + builder.pruneMinTokens);
        }
        this.pruneMinTokens = builder.pruneMinTokens;
        final PromptSizeRecoveryStrategy recovery = builder.recoveryStrategy != null
                ? builder.recoveryStrategy
                : NoOpPromptSizeRecoveryStrategy.instance();
        // The default engine's rules over the same failure store, so a breaker tripped in one mode holds in the other.
        this.fallback = DefaultContextEngine.builder()
                .compactionGuard(new DefaultCompactionGuard(compactionEngine, modelContextWindowRegistry,
                        tokenEstimator, maxConsecutiveFailures, MAX_TRACKED_SESSIONS, failureStore))
                .recoveryStrategy(recovery).compactionEngine(compactionEngine).tokenEstimator(tokenEstimator)
                .writeFormat(SessionLogFormat.V2).build();
        this.sessionLocks = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<SessionId, ReentrantLock> eldest) {
                return size() > MAX_TRACKED_SESSIONS;
            }
        });
    }

    private static double requireRatio(double value, String name) {
        if (!(value > 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be in (0, 1], got: " + value);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The default engine this one falls back to, and whose recovery it uses. Never null. */
    public DefaultContextEngine getFallback() {
        return fallback;
    }

    @Override
    public ContextDecision prepare(ContextRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        final Call call = rollingCall(request);
        if (call == null) {
            return fallback.prepare(request);
        }
        final ReentrantLock lock = lockOf(call.buffer.getSessionId());
        if (!lock.tryLock()) {
            return ContextDecision.from(CompactionDecision.none("concurrent compaction in progress"),
                    call.viewOfCurrent(), call.view.size());
        }
        try {
            return decide(call);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
        return fallback.recover(request, error);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Skips the threshold decision and follows the cut selection: the tail budget, then half of it, then the last
     * legal cut — without the {@code WARN} row, since the user asked (context-engine §5.6). A concurrent compaction of
     * the same session makes this fail with {@link CompactionContendedException} rather than wait or do nothing. On
     * success the circuit breaker is reset.
     */
    @Override
    public CompactionResult compactNow(ContextRequest request, String instructions) {
        Objects.requireNonNull(request, "request cannot be null");
        final Call call = rollingCall(request);
        if (call == null) {
            return fallback.compactNow(request, instructions);
        }
        final ReentrantLock lock = lockOf(call.buffer.getSessionId());
        if (!lock.tryLock()) {
            return failure(CompactionTrigger.MANUAL,
                    new CompactionContendedException("another compaction of this session is in progress"));
        }
        try {
            final Plan plan = call.spanPlan(false, Double.MAX_VALUE);
            if (plan == null) {
                return failure(CompactionTrigger.MANUAL,
                        new IllegalStateException("nothing to compact: no part of the view can be summarized"));
            }
            final CompactionResult result = summarizeSpan(call, plan, CompactionTrigger.MANUAL, instructions);
            if (result != null && result.isSuccess()) {
                failureStore.reset(call.buffer.getSessionId());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the call's layout when this engine serves it by rolling, or null when it falls back to the default
     * engine — a version-1 transcript, or a model and system prompt that cannot sustain rolling.
     */
    private Call rollingCall(ContextRequest request) {
        requireHooks(request);
        final TranscriptBuffer buffer = request.getTranscriptBuffer();
        if (buffer.getFormat() != SessionLogFormat.V2) {
            if (versionOneWarned.size() < MAX_TRACKED_SESSIONS && versionOneWarned.add(buffer.getSessionId())) {
                log.warn("Session {} has a version-1 log, which cannot keep a summary span; the rolling context engine"
                        + " compacts it as the default engine does", buffer.getSessionId());
            }
            return null;
        }
        final Call call = new Call(request, thresholds(request.getModel()));
        final int minAfter = call.systemTokens + call.headTokens + call.summaryBudget
                + (int) (minTailRatio * call.effective);
        if (minAfter >= call.warning) {
            final String modelName = call.modelName;
            if (unsustainableWarned.add(modelName)) {
                log.warn(
                        "Model '{}' cannot sustain rolling compaction with this system prompt: the smallest compacted"
                                + " view ({} tokens: system {}, head {}, summary {}, minimum tail {}) would not end"
                                + " below the warning band ({} tokens). Compacting as the default engine does.",
                        modelName, minAfter, call.systemTokens, call.headTokens, call.summaryBudget,
                        (int) (minTailRatio * call.effective), call.warning);
            }
            return null;
        }
        return call;
    }

    private Thresholds thresholds(LlmModel model) {
        final String modelName = model.getName().orElse("");
        final ModelContextLimits limits = modelContextWindowRegistry.resolve(modelName);
        final int effective = limits.getEffectiveContextWindow();
        final int rollingAuto = Math.min((int) (autoCompactRatio * effective), limits.getAutoCompactThreshold());
        final int warning = Math.max(0, rollingAuto - limits.getWarningBuffer());
        return new Thresholds(modelName, effective, rollingAuto, warning, limits.getBlockingLimit());
    }

    /** The decision ladder: blocking → circuit breaker → auto → warning (context-engine §5.6). */
    private ContextDecision decide(Call call) {
        final SessionId sessionId = call.buffer.getSessionId();
        final int estimated = call.estimated;
        final int threshold = call.request.isBudgetForced() ? call.warning : call.rollingAuto;
        final boolean preconditionMet = LegalCuts.isLegal(call.view.getMessages(), call.view.size());

        // 1) blocking: compact regardless of the breaker, absorbing the head when nothing smaller will do
        if (estimated >= call.blocking) {
            if (!preconditionMet) {
                return call.decision(CompactionDecision.warn("blocking-limit reached but precondition unmet; deferring",
                        estimated, call.blocking), null);
            }
            final CompactionResult result = compact(call, threshold, true);
            if (result.isSuccess()) {
                failureStore.reset(sessionId);
                return call.decision(CompactionDecision.compact(result, "blocking-limit forced compaction", estimated,
                        call.blocking), result.getMetadata());
            }
            recordFailureIfTransient(sessionId, result);
            return call.decision(CompactionDecision.block(
                    "compaction failed at blocking limit: "
                            + result.getError().map(Throwable::getMessage).orElse("unknown error"),
                    estimated, call.blocking), result.getMetadata());
        }

        // 2) circuit breaker — blocks AUTO only
        if (failureStore.get(sessionId) >= maxConsecutiveFailures) {
            return call.decision(CompactionDecision.none("circuit breaker open"), null);
        }

        // 3) auto
        if (estimated >= threshold && preconditionMet) {
            final Plan prune = call.prunePlan(threshold, pruneMinTokens);
            if (prune != null) {
                return call.decision(applyPrune(call, prune, estimated), null);
            }
            final Plan plan = call.spanPlan(false, threshold);
            if (plan == null) {
                final CompactionMetadata fallbackMetadata = decisionMetadata(CompactionKind.FALLBACK,
                        CompactionTrigger.AUTO, estimated);
                return call.decision(
                        CompactionDecision.warn("rolling compaction cannot bring the view below " + threshold
                                + " tokens (estimated=" + estimated + "); not compacting", estimated, call.blocking),
                        fallbackMetadata);
            }
            final CompactionResult result = summarizeSpan(call, plan, CompactionTrigger.AUTO, null);
            if (result.isSuccess()) {
                failureStore.reset(sessionId);
                final String reason = call.request.isBudgetForced()
                        ? "budget-forced rolling compaction (warning band)"
                        : "rolling compaction threshold reached";
                return call.decision(CompactionDecision.compact(result, reason, estimated, call.blocking),
                        result.getMetadata());
            }
            recordFailureIfTransient(sessionId, result);
            return call.decision(CompactionDecision.compact(result, "rolling compaction attempted but failed",
                    estimated, call.blocking), result.getMetadata());
        }

        // 4) warning band
        if (estimated >= call.warning) {
            return call
                    .decision(
                            CompactionDecision.warn("warning threshold reached: estimated=" + estimated + ", warningAt="
                                    + call.warning + ", rollingAutoAt=" + call.rollingAuto, estimated, call.blocking),
                            null);
        }
        return call.decision(CompactionDecision.none(), null);
    }

    /** At the blocking limit: prune, the three retreating cuts, then the last cut with the head absorbed. */
    private CompactionResult compact(Call call, int threshold, boolean blocking) {
        final Plan prune = call.prunePlan(threshold, pruneMinTokens);
        if (prune != null) {
            return applyPruneResult(call, prune, call.estimated);
        }
        Plan plan = call.spanPlan(false, threshold);
        if (plan == null && blocking) {
            plan = call.spanPlan(true, Double.MAX_VALUE);
        }
        if (plan == null) {
            return failure(CompactionTrigger.AUTO,
                    new IllegalStateException("no legal cut leaves anything for the summary to absorb"));
        }
        return summarizeSpan(call, plan, CompactionTrigger.AUTO, null);
    }

    private CompactionDecision applyPrune(Call call, Plan plan, int estimated) {
        final CompactionResult result = applyPruneResult(call, plan, estimated);
        return CompactionDecision.compact(result, "tool results elided (no summary needed)", estimated, call.blocking);
    }

    private CompactionResult applyPruneResult(Call call, Plan plan, int estimated) {
        final Instant startedAt = Instant.now();
        for (long seq : plan.elisions) {
            call.buffer.elideInView(seq, placeholder(seq));
        }
        final ViewProjection after = ViewProjection.of(call.buffer.getLogState());
        final int postTokens = tokenEstimator.estimate(call.request.getSystemPrompt(), after.getMessages());
        log.info("Rolling compaction pruned {} tool results from the view of session {} ({} -> {} tokens)",
                plan.elisions.size(), call.buffer.getSessionId(), estimated, postTokens);
        return CompactionResult.success("",
                CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).kind(CompactionKind.PRUNE)
                        .preCompactTokenCount(estimated).postCompactTokenCount(postTokens).startedAt(startedAt)
                        .completedAt(Instant.now()).build());
    }

    /**
     * The placeholder an elided tool result shows: deterministic, and naming the seq {@code SessionHistory} reads the
     * original by.
     *
     * @param seq
     *            the seq of the elided entry
     * @return the placeholder (never null)
     */
    public static String placeholder(long seq) {
        return "[tool result elided: seq=" + seq + "]";
    }

    /**
     * Summarizes what {@code plan} absorbs — updating the held summary — and records the widened span. The log is not
     * touched; PostCompact hooks fire through {@link CompactionEngine#summaryInstalled} once the span is in place.
     */
    private CompactionResult summarizeSpan(Call call, Plan plan, CompactionTrigger trigger, String instructions) {
        final TranscriptBuffer buffer = call.buffer;
        final SummarySpan held = call.held;
        final List<Message> absorbed = new ArrayList<>();
        long absorbedFrom = -1;
        for (int p = 0; p < call.view.size(); p++) {
            final long seq = call.view.sourceSeq(p);
            if (seq == ViewProjection.MADE_BY_VIEW || seq < plan.fromSeq || seq >= plan.toSeq) {
                continue;
            }
            absorbed.add(call.view.getMessages().get(p));
            if (absorbedFrom < 0) {
                absorbedFrom = seq;
            }
        }
        if (absorbed.isEmpty()) {
            return failure(trigger, new IllegalStateException("nothing to compact: the cut absorbs no message"));
        }
        final List<Message> input = new ArrayList<>(absorbed.size() + 1);
        if (absorbed.get(0).getRole() != Role.USER) {
            input.add(Message.user(CONTINUATION_NOTE));
        }
        input.addAll(absorbed);
        final SummaryRequest summaryRequest = SummaryRequest.builder().messages(input)
                .systemPrompt(call.request.getSystemPrompt()).sessionId(buffer.getSessionId())
                .executionId(call.request.getCaller().getExecutionId().orElse(null)).trigger(trigger)
                .model(summaryModel != null ? summaryModel : call.request.getModel())
                .hookRegistry(call.request.getHookRegistry().orElseThrow())
                .environment(call.request.getEnvironment().orElseThrow()).customInstructions(instructions)
                .callMetadata(call.request.getCallMetadata().orElse(null)).rolling(true)
                .previousSummary(held != null ? held.getSummaryText() : null).targetSummaryTokens(call.summaryBudget)
                .build();
        final CompactionResult summarized = compactionEngine.summarize(summaryRequest);
        if (summarized == null || summarized.isFailure()) {
            return summarized != null
                    ? summarized
                    : failure(trigger, new IllegalStateException("summarize() returned null"));
        }
        final CompactionMetadata produced = summarized.getMetadata();
        final String summaryText = summarized.getSummaryText().orElse("");
        final Set<String> tools = new LinkedHashSet<>();
        if (held != null) {
            tools.addAll(held.getDiscoveredToolNames());
        }
        tools.addAll(produced.getDiscoveredToolNames());
        final SummarySpan span = SummarySpan.builder().fromSeq(plan.fromSeq).toSeq(plan.toSeq).summaryText(summaryText)
                .boundaryId(UUID.randomUUID().toString()).trigger(trigger.name()).preTokenCount(call.estimated)
                .messagesSummarized((held != null ? held.getMessagesSummarized() : 0) + absorbed.size())
                .discoveredToolNames(List.copyOf(tools)).build();
        try {
            buffer.summarizeView(span);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Rolling summary could not be recorded for session {}: {}", buffer.getSessionId(), e.getMessage());
            return CompactionResult.failure(e, produced);
        }
        final ViewProjection after = ViewProjection.of(buffer.getLogState());
        final Shape shape = shapeOf(after, call.headEndSeq, plan.toSeq);
        final int postTokens = tokenEstimator.estimate(call.request.getSystemPrompt(), after.getMessages());
        final CompactionResult installed = CompactionResult.success(summaryText, CompactionMetadata.builder()
                .trigger(trigger).kind(CompactionKind.ROLLING).preCompactTokenCount(call.estimated)
                .postCompactTokenCount(postTokens).messagesSummarized(absorbed.size())
                .startedAt(produced.getStartedAt()).completedAt(Instant.now()).discoveredToolNames(List.copyOf(tools))
                .viewShape(shape.head, shape.span, shape.tail).summaryTokens(tokenEstimator.estimateText(summaryText))
                .absorbedRange(absorbedFrom, plan.toSeq).build());
        compactionEngine.summaryInstalled(summaryRequest, installed, buffer);
        log.info("Rolling compaction of session {}: span now {} (stage {}), absorbed {} messages, {} -> {} tokens",
                buffer.getSessionId(), span.getRange(), plan.stage, absorbed.size(), call.estimated, postTokens);
        return installed;
    }

    private Shape shapeOf(ViewProjection view, long headEndSeq, long spanEndSeq) {
        int head = 0;
        int span = 0;
        int tail = 0;
        for (int p = 0; p < view.size(); p++) {
            final long seq = view.sourceSeq(p);
            final int tokens = tokenEstimator.estimateMessage(view.getMessages().get(p));
            if (seq == ViewProjection.MADE_BY_VIEW) {
                span += tokens;
            } else if (seq < headEndSeq) {
                head += tokens;
            } else if (seq >= spanEndSeq) {
                tail += tokens;
            }
        }
        return new Shape(head, span, tail);
    }

    private CompactionMetadata decisionMetadata(CompactionKind kind, CompactionTrigger trigger, int estimated) {
        final Instant now = Instant.now();
        return CompactionMetadata.builder().trigger(trigger).kind(kind).preCompactTokenCount(estimated).startedAt(now)
                .completedAt(now).build();
    }

    private static CompactionResult failure(CompactionTrigger trigger, Exception error) {
        final Instant now = Instant.now();
        return CompactionResult.failure(error, CompactionMetadata.builder().trigger(trigger)
                .kind(CompactionKind.ROLLING).startedAt(now).completedAt(now).build());
    }

    private void recordFailureIfTransient(SessionId sessionId, CompactionResult result) {
        final Exception error = result.getError().orElse(null);
        if (error instanceof CompactionBlockedByHookException || error instanceof CompactionReentrancyException) {
            log.debug("Compaction of session {} was not a transient failure; not counted toward the circuit breaker",
                    sessionId);
            return;
        }
        failureStore.recordFailure(sessionId);
    }

    private ReentrantLock lockOf(SessionId sessionId) {
        synchronized (sessionLocks) {
            return sessionLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        }
    }

    private static void requireHooks(ContextRequest request) {
        request.getHookRegistry()
                .orElseThrow(() -> new IllegalArgumentException("RollingContextEngine requires a HookRegistry"));
        request.getEnvironment()
                .orElseThrow(() -> new IllegalArgumentException("RollingContextEngine requires an Environment"));
    }

    @Override
    public String toString() {
        return "RollingContextEngine{autoCompactRatio=" + autoCompactRatio + ", headTokenRatio=" + headTokenRatio
                + ", tailTokenRatio=" + tailTokenRatio + ", summaryTokenRatio=" + summaryTokenRatio + ", engine="
                + compactionEngine.getClass().getSimpleName() + '}';
    }

    /** The thresholds of one model. */
    private static final class Thresholds {
        private final String modelName;
        private final int effective;
        private final int rollingAuto;
        private final int warning;
        private final int blocking;

        private Thresholds(String modelName, int effective, int rollingAuto, int warning, int blocking) {
            this.modelName = modelName;
            this.effective = effective;
            this.rollingAuto = rollingAuto;
            this.warning = warning;
            this.blocking = blocking;
        }
    }

    /** A chosen compaction: a span {@code [fromSeq, toSeq)} to summarize, or tool results to elide. */
    private static final class Plan {
        private final long fromSeq;
        private final long toSeq;
        private final int stage;
        private final List<Long> elisions;

        private Plan(long fromSeq, long toSeq, int stage, List<Long> elisions) {
            this.fromSeq = fromSeq;
            this.toSeq = toSeq;
            this.stage = stage;
            this.elisions = elisions;
        }
    }

    /** Estimated tokens of the view's three parts. */
    private static final class Shape {
        private final int head;
        private final int span;
        private final int tail;

        private Shape(int head, int span, int tail) {
            this.head = head;
            this.span = span;
            this.tail = tail;
        }
    }

    /**
     * One call's view and everything measured about it: the head, the held span, and per view position its seq and
     * its tokens. Every choice below is made from these numbers, before any summary is asked for.
     */
    private final class Call {
        private final ContextRequest request;
        private final TranscriptBuffer buffer;
        private final SessionLogState state;
        private final ViewProjection view;
        private final SummarySpan held;
        private final String modelName;
        private final int effective;
        private final int rollingAuto;
        private final int warning;
        private final int blocking;
        private final int summaryBudget;
        private final int systemTokens;
        private final int estimated;
        private final int[] tokens;
        private final long headEndSeq;
        private final int headTokens;

        private Call(ContextRequest request, Thresholds thresholds) {
            this.request = request;
            this.buffer = request.getTranscriptBuffer();
            this.state = buffer.getLogState();
            this.view = ViewProjection.of(state);
            this.held = state.getViewState().getSummarySpan().orElse(null);
            this.modelName = thresholds.modelName;
            this.effective = thresholds.effective;
            this.rollingAuto = thresholds.rollingAuto;
            this.warning = thresholds.warning;
            this.blocking = thresholds.blocking;
            this.summaryBudget = (int) (summaryTokenRatio * effective);
            this.systemTokens = tokenEstimator.estimate(request.getSystemPrompt(), List.of());
            this.estimated = tokenEstimator.estimate(request.getSystemPrompt(), view.getMessages());
            this.tokens = new int[view.size()];
            for (int p = 0; p < tokens.length; p++) {
                tokens[p] = tokenEstimator.estimateMessage(view.getMessages().get(p));
            }
            this.headEndSeq = headEnd();
            int head = 0;
            for (int p = 0; p < view.size(); p++) {
                final long seq = view.sourceSeq(p);
                if (seq != ViewProjection.MADE_BY_VIEW && seq < headEndSeq) {
                    head += tokens[p];
                }
            }
            this.headTokens = head;
        }

        /**
         * Returns the first seq after the head, or {@code floorSeq} when the head is empty (context-engine §5.2).
         */
        private long headEnd() {
            final long floor = state.getFloorSeq();
            if (held != null && held.getFromSeq() == floor) {
                return floor;
            }
            final List<SessionLogEntry> entries = state.getEntries();
            if (entries.isEmpty() || isVersionOneMarker(entries.get(0))) {
                return floor;
            }
            long end = -1;
            long conversationTokens = 0;
            for (SessionLogEntry entry : entries) {
                final Message message = entry.getMessage();
                if (message == null) {
                    continue;
                }
                if (entry.getOrigin() == LogOrigin.CONVERSATION) {
                    conversationTokens += tokenEstimator.estimateMessage(message);
                }
                if (entry.getOrigin() == LogOrigin.CONVERSATION && message.getRole() == Role.USER) {
                    end = entry.getSeq() + 1;
                    break;
                }
            }
            if (end < 0 || (held != null && held.getFromSeq() < end)
                    || conversationTokens > (long) (headTokenRatio * effective)) {
                return floor;
            }
            return end;
        }

        private boolean isVersionOneMarker(SessionLogEntry entry) {
            final Message message = entry.getMessage();
            return message != null && message.getContent() != null
                    && message.getContent().startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        }

        /** The first seq a span may start at: the held span's start, or the end of the head. */
        private long spanFrom(boolean absorbHead) {
            if (absorbHead) {
                return state.getFloorSeq();
            }
            return held != null ? held.getFromSeq() : headEndSeq;
        }

        /**
         * Chooses the span end, retreating from the tail budget to half of it to the last legal cut, and adopting the
         * first whose expected size is under {@code threshold}. With {@code absorbHead} the head joins the span and
         * the last legal cut is taken outright — the blocking limit's last resort.
         *
         * @return the plan, or null when no cut both absorbs something and ends under the threshold
         */
        private Plan spanPlan(boolean absorbHead, double threshold) {
            final long from = spanFrom(absorbHead);
            final List<Cut> cuts = cuts(from);
            if (cuts.isEmpty()) {
                return null;
            }
            if (absorbHead) {
                return new Plan(from, cuts.get(cuts.size() - 1).seq, 3, List.of());
            }
            final int tailBudget = (int) (tailTokenRatio * effective);
            final Cut[] stages = {byTailBudget(cuts, tailBudget), byTailBudget(cuts, tailBudget / 2),
                    cuts.get(cuts.size() - 1)};
            for (int stage = 0; stage < stages.length; stage++) {
                final Cut cut = stages[stage];
                if (cut != null && systemTokens + headTokens + summaryBudget + cut.tailTokens < threshold) {
                    return new Plan(from, cut.seq, stage, List.of());
                }
            }
            return null;
        }

        /**
         * Tries eliding the large tool results in what the tail-budget cut would absorb (context-engine §5.5), and
         * returns the elisions when they alone bring the view under {@code threshold}.
         */
        private Plan prunePlan(int threshold, int minTokens) {
            final List<Cut> cuts = cuts(spanFrom(false));
            final Cut cut = cuts.isEmpty() ? null : byTailBudget(cuts, (int) (tailTokenRatio * effective));
            if (cut == null) {
                return null;
            }
            final long from = held != null ? held.getToSeq() : headEndSeq;
            final List<Long> elisions = new ArrayList<>();
            int saved = 0;
            for (int p = 0; p < view.size(); p++) {
                final long seq = view.sourceSeq(p);
                if (seq == ViewProjection.MADE_BY_VIEW || seq < from || seq >= cut.seq || !view.isVerbatim(p)) {
                    continue;
                }
                final Message message = view.getMessages().get(p);
                if (message.getRole() != Role.TOOL || tokens[p] < minTokens) {
                    continue;
                }
                elisions.add(seq);
                saved += tokens[p] - tokenEstimator.estimateMessage(elided(message, placeholder(seq)));
            }
            if (elisions.isEmpty() || estimated - saved >= threshold) {
                return null;
            }
            return new Plan(from, cut.seq, -1, List.copyOf(elisions));
        }

        /**
         * Every legal cut at or after the held span's end whose span, starting at {@code from}, would absorb at least
         * one view message it does not already hide — with the tokens left verbatim after it. In seq order.
         */
        private List<Cut> cuts(long from) {
            final long minimum = held != null ? Math.max(from, held.getToSeq()) : from;
            long firstAbsorbable = Long.MAX_VALUE;
            for (int p = 0; p < view.size(); p++) {
                final long seq = view.sourceSeq(p);
                if (seq != ViewProjection.MADE_BY_VIEW && seq >= from
                        && (held == null || !held.getRange().contains(seq))) {
                    firstAbsorbable = seq;
                    break;
                }
            }
            if (firstAbsorbable == Long.MAX_VALUE) {
                return List.of();
            }
            final List<Cut> cuts = new ArrayList<>();
            final long end = state.getNextSeq();
            if (end > firstAbsorbable && end >= minimum && state.isLegalCut(end)) {
                cuts.add(new Cut(end, 0, false));
            }
            // Back to front, so each cut's verbatim tail is a running sum.
            int tail = 0;
            for (int p = view.size() - 1; p >= 0; p--) {
                final long seq = view.sourceSeq(p);
                if (seq == ViewProjection.MADE_BY_VIEW) {
                    continue;
                }
                if (seq < minimum || seq <= firstAbsorbable) {
                    break;
                }
                tail += tokens[p];
                if (state.isLegalCut(seq)) {
                    cuts.add(new Cut(seq, tail, view.getMessages().get(p).getRole() == Role.USER));
                }
            }
            Collections.reverse(cuts);
            return cuts;
        }

        /**
         * The earliest cut whose verbatim tail fits {@code budget} — the largest tail that does — snapped to a user
         * message whose tail is within ±10% of the budget when there is one.
         */
        private Cut byTailBudget(List<Cut> cuts, int budget) {
            Cut chosen = null;
            for (Cut cut : cuts) {
                if (cut.tailTokens <= budget) {
                    chosen = cut;
                    break;
                }
            }
            Cut snapped = null;
            final double band = SNAP_BAND * budget;
            for (Cut cut : cuts) {
                if (cut.startsUserMessage && Math.abs(cut.tailTokens - budget) <= band && (snapped == null
                        || Math.abs(cut.tailTokens - budget) < Math.abs(snapped.tailTokens - budget))) {
                    snapped = cut;
                }
            }
            return snapped != null ? snapped : chosen;
        }

        private ContextView viewOfCurrent() {
            return ContextView.of(view.getMessages(), estimated);
        }

        private ContextDecision decision(CompactionDecision decision, CompactionMetadata metadata) {
            final ViewProjection after = decision.getAction() == CompactionDecision.Action.COMPACT
                    ? ViewProjection.of(buffer.getLogState())
                    : view;
            final ContextView sent = after == view
                    ? viewOfCurrent()
                    : ContextView.of(after.getMessages(),
                            tokenEstimator.estimate(request.getSystemPrompt(), after.getMessages()));
            return ContextDecision.builder().view(sent).action(decision.getAction()).reason(decision.getReason())
                    .compactionMetadata(metadata != null
                            ? metadata
                            : decision.getCompactionResult().map(CompactionResult::getMetadata).orElse(null))
                    .estimatedTokens(decision.getEstimatedTokens()).blockingLimit(decision.getBlockingLimit())
                    .viewSizeBefore(view.size()).build();
        }
    }

    /** A candidate span end: its seq, the tokens left verbatim after it, and whether a user message starts there. */
    private static final class Cut {
        private final long seq;
        private final int tailTokens;
        private final boolean startsUserMessage;

        private Cut(long seq, int tailTokens, boolean startsUserMessage) {
            this.seq = seq;
            this.tailTokens = tailTokens;
            this.startsUserMessage = startsUserMessage;
        }
    }

    private static Message elided(Message original, String placeholder) {
        final List<ToolUseResult> results = new ArrayList<>();
        for (ToolUseResult result : original.getToolUseResults()) {
            results.add(result.isError()
                    ? ToolUseResult.error(result.getToolUseId(), placeholder)
                    : ToolUseResult.success(result.getToolUseId(), placeholder));
        }
        return results.isEmpty() ? original : Message.toolUseResults(results);
    }

    /** Builder for {@link RollingContextEngine}. */
    public static final class Builder {
        private CompactionEngine compactionEngine;
        private ModelContextWindowRegistry modelContextWindowRegistry;
        private TokenEstimator tokenEstimator;
        private CompactionFailureStore failureStore;
        private int maxConsecutiveFailures = DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES;
        private PromptSizeRecoveryStrategy recoveryStrategy;
        private LlmModel summaryModel;
        private double autoCompactRatio = DEFAULT_AUTO_COMPACT_RATIO;
        private double headTokenRatio = DEFAULT_HEAD_TOKEN_RATIO;
        private double tailTokenRatio = DEFAULT_TAIL_TOKEN_RATIO;
        private double summaryTokenRatio = DEFAULT_SUMMARY_TOKEN_RATIO;
        private double minTailRatio = DEFAULT_MIN_TAIL_RATIO;
        private int pruneMinTokens = DEFAULT_PRUNE_MIN_TOKENS;
        private SessionLogFormat writeFormat = SessionLogFormat.V2;

        private Builder() {
        }

        /**
         * @param compactionEngine
         *            the summary part; must {@linkplain CompactionEngine#supportsSummarize() summarize} (required)
         * @return this builder
         */
        public Builder compactionEngine(CompactionEngine compactionEngine) {
            this.compactionEngine = compactionEngine;
            return this;
        }

        /** Resolves a model to its limits (required). */
        public Builder modelContextWindowRegistry(ModelContextWindowRegistry modelContextWindowRegistry) {
            this.modelContextWindowRegistry = modelContextWindowRegistry;
            return this;
        }

        /** Sizes views and messages (required). */
        public Builder tokenEstimator(TokenEstimator tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        /**
         * @param failureStore
         *            where the circuit breaker counts, or {@code null} for an {@link InMemoryCompactionFailureStore}
         * @return this builder
         */
        public Builder failureStore(CompactionFailureStore failureStore) {
            this.failureStore = failureStore;
            return this;
        }

        public Builder maxConsecutiveFailures(int maxConsecutiveFailures) {
            this.maxConsecutiveFailures = maxConsecutiveFailures;
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
         * @param summaryModel
         *            the model summaries are asked of, or {@code null} for the call's own model. The runtime has one
         *            {@code LlmClient}, so it must be a model of the same provider
         * @return this builder
         */
        public Builder summaryModel(LlmModel summaryModel) {
            this.summaryModel = summaryModel;
            return this;
        }

        /** Rolling auto-compact trigger as a fraction of the effective window (default 0.6). */
        public Builder autoCompactRatio(double autoCompactRatio) {
            this.autoCompactRatio = autoCompactRatio;
            return this;
        }

        /** Cap on the head's conversation tokens as a fraction of the effective window (default 0.05). */
        public Builder headTokenRatio(double headTokenRatio) {
            this.headTokenRatio = headTokenRatio;
            return this;
        }

        /** The verbatim tail's budget as a fraction of the effective window (default 0.20). */
        public Builder tailTokenRatio(double tailTokenRatio) {
            this.tailTokenRatio = tailTokenRatio;
            return this;
        }

        /** The summary length asked for, as a fraction of the effective window (default 0.08). */
        public Builder summaryTokenRatio(double summaryTokenRatio) {
            this.summaryTokenRatio = summaryTokenRatio;
            return this;
        }

        /** The smallest tail rolling must be able to keep, as a fraction of the effective window (default 0.05). */
        public Builder minTailRatio(double minTailRatio) {
            this.minTailRatio = minTailRatio;
            return this;
        }

        /** The smallest tool result body worth eliding, in tokens (default 500). */
        public Builder pruneMinTokens(int pruneMinTokens) {
            this.pruneMinTokens = pruneMinTokens;
            return this;
        }

        /**
         * Declares the log format this node writes. {@link SessionLogFormat#V1} makes {@link #build()} fail: rolling
         * keeps its span in the view state, which a version-1 record cannot store (session-log §7.3).
         *
         * @param writeFormat
         *            the format this node writes (must not be null; default {@link SessionLogFormat#V2})
         * @return this builder
         */
        public Builder writeFormat(SessionLogFormat writeFormat) {
            this.writeFormat = Objects.requireNonNull(writeFormat, "writeFormat cannot be null");
            return this;
        }

        /**
         * @return the engine (never null)
         * @throws IllegalStateException
         *             if the write format is version 1, or the compaction engine cannot summarize
         */
        public RollingContextEngine build() {
            return new RollingContextEngine(this);
        }
    }
}
