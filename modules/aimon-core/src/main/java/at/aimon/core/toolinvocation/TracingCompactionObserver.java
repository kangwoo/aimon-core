package at.aimon.core.toolinvocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.compact.CompactionBlockedByHookException;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionObserver;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionStart;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * The {@link CompactionObserver} that opens a {@link SpanType#COMPACTION COMPACTION} span around each compaction
 * attempt (ST-5), shared by the main-agent executor, the subagent executor and the {@code /compact} command span.
 *
 * <p>
 * The span is a child of the span the parent carrier points at — the run root (TURN, or SUBAGENT in a fork) for an
 * AUTO compaction, the COMMAND span for {@code /compact} — and {@link CompactionObserver.Attempt#enrich} rotates a
 * summary call's metadata to it, so the summary LLM span nests under the compaction rather than beside it. Only the two
 * reserved span tags change; the call's component, feature, principal and group key are the caller's.
 *
 * <p>
 * Outputs are read from the attempt's {@link CompactionMetadata} when it closes, after the summary was installed, so
 * the post-compaction size is the view's. Token usage is not repeated here: the LLM children carry it.
 *
 * <p>
 * Status, in order: closed without a recorded result — the engine threw — is {@code ERROR}; a failure while the
 * execution's cancellation signal is tripped is {@code INTERRUPTED}; a failure because a PreCompact hook blocked it is
 * {@code OK} with {@code outcome=blocked}; any other failure is {@code ERROR}; a success is {@code OK}, also when the
 * view is still over the blocking limit.
 *
 * <p>
 * Placed beside {@link ToolCallTracing} for the same reason: it depends on {@code tracing}, which no package under
 * {@code at.aimon.core.agent..} may, and it must be reachable from {@code agent.impl.orca} and
 * {@code subagent.execution} alike.
 */
public final class TracingCompactionObserver implements CompactionObserver {

    /**
     * Prefix of the span name; the trigger, lower-cased, follows ({@code compaction:auto}, {@code compaction:manual}).
     */
    public static final String SPAN_NAME_PREFIX = "compaction:";

    /** The {@code outcome} output of a compaction that succeeded. */
    public static final String OUTCOME_COMPACTED = "compacted";

    /** The {@code outcome} output of a compaction a PreCompact hook blocked. */
    public static final String OUTCOME_BLOCKED = "blocked";

    /** The {@code outcome} output of a compaction that failed. */
    public static final String OUTCOME_FAILED = "failed";

    /** The {@code outcome} output of a compaction that failed because its execution was cancelled. */
    public static final String OUTCOME_INTERRUPTED = "interrupted";

    /** The status message of a compaction whose engine threw instead of returning a result. */
    static final String DID_NOT_COMPLETE = "Compaction did not complete";

    private static final Logger log = LoggerFactory.getLogger(TracingCompactionObserver.class);

    private final Tracer tracer;
    private final LlmCallMetadata parent;
    private final TracePayloadPolicy policy;
    private final CancellationSignal cancellation;
    private final Map<String, String> attributes;

    private TracingCompactionObserver(Tracer tracer, LlmCallMetadata parent, TracePayloadPolicy policy,
            CancellationSignal cancellation, Map<String, String> attributes) {
        this.tracer = tracer;
        this.parent = parent;
        this.policy = policy;
        this.cancellation = cancellation;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Returns an observer whose spans nest under the span {@code parent} points at, or empty when it points at none
     * (tracing off, or metadata that was never enriched) — the caller then sets no observer, and the request stays the
     * one it was without tracing.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param parent
     *            the metadata carrying the parent span in its reserved tags; also the summary call's base when a
     *            caller hands {@code enrich} none (must not be null)
     * @param policy
     *            the payload policy; the summary text is captured only when it captures content (must not be null)
     * @param cancellation
     *            the compacting execution's cancellation signal, which turns a failure into {@code INTERRUPTED}, or
     *            {@code null} when nothing can cancel it ({@code /compact})
     * @param attributes
     *            attributes set on every span, e.g. {@code iteration} and {@code invokerType} (must not be null)
     * @return the observer, or empty when there is no parent span
     */
    public static Optional<CompactionObserver> under(Tracer tracer, LlmCallMetadata parent, TracePayloadPolicy policy,
            CancellationSignal cancellation, Map<String, String> attributes) {
        Objects.requireNonNull(tracer, "tracer cannot be null");
        Objects.requireNonNull(parent, "parent cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");
        if (SpanContext.readFrom(parent).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TracingCompactionObserver(tracer, parent, policy, cancellation, attributes));
    }

    @Override
    public Attempt begin(CompactionStart start) {
        Objects.requireNonNull(start, "start cannot be null");
        try {
            final Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("trigger", start.getTrigger().name());
            inputs.put("strategy", start.getStrategy());
            inputs.put("blockingForced", start.isBlockingForced());
            inputs.put("messagesBefore", start.getMessagesBefore());
            final Optional<Tracer.Span> span = Spans.startChildOf(tracer, parent, SpanType.COMPACTION,
                    SPAN_NAME_PREFIX + start.getTrigger().name().toLowerCase(Locale.ROOT), inputs);
            if (span.isEmpty()) {
                return CompactionObserver.none().begin(start);
            }
            attributes.forEach(span.get()::setAttribute);
            return new SpanAttempt(span.get());
        } catch (RuntimeException e) {
            log.warn("Opening the COMPACTION span failed; not tracing this compaction: {}", e.getMessage());
            return CompactionObserver.none().begin(start);
        }
    }

    /** One open COMPACTION span. */
    private final class SpanAttempt implements Attempt {
        private final Tracer.Span span;
        private CompactionResult result;
        private boolean closed;

        private SpanAttempt(Tracer.Span span) {
            this.span = span;
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            return Spans.enrichSafely(span, base != null ? base : parent);
        }

        @Override
        public CompactionResult record(CompactionResult compactionResult) {
            this.result = compactionResult;
            return compactionResult;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                finish();
            } catch (RuntimeException e) {
                log.warn("Recording the COMPACTION span failed: {}", e.getMessage());
            } finally {
                span.close();
            }
        }

        private void finish() {
            if (result == null) {
                span.error(DID_NOT_COMPLETE);
                return;
            }
            final Exception error = result.getError().orElse(null);
            final String outcome;
            if (result.isSuccess()) {
                outcome = OUTCOME_COMPACTED;
            } else if (cancellation != null && cancellation.isCancelled() && causedByCancel(error)) {
                outcome = OUTCOME_INTERRUPTED;
            } else if (error instanceof CompactionBlockedByHookException) {
                outcome = OUTCOME_BLOCKED;
            } else {
                outcome = OUTCOME_FAILED;
            }
            span.setOutputs(outputs(result, outcome, error));
            if (OUTCOME_INTERRUPTED.equals(outcome)) {
                span.interrupted();
            } else if (OUTCOME_FAILED.equals(outcome)) {
                if (error != null) {
                    span.error(error);
                } else {
                    span.error("Compaction failed");
                }
            }
        }
    }

    /**
     * Whether a failure is the cancel's own doing: the summary call was cancelled ({@link LlmCallCancelledException} or
     * {@link CancelledExecutionException} anywhere in the cause chain), or a PreCompact hook the cancel interrupted
     * blocked the compaction. Checked only while the signal is tripped, so a provider error that merely coincides with
     * a
     * cancel stays ERROR — the rule {@code TracingLlmClient} applies to the summary call's own span.
     */
    private static boolean causedByCancel(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LlmCallCancelledException || t instanceof CancelledExecutionException
                    || t instanceof CompactionBlockedByHookException) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> outputs(CompactionResult compactionResult, String outcome, Exception error) {
        final CompactionMetadata metadata = compactionResult.getMetadata();
        final String summary = compactionResult.getSummaryText().orElse(null);
        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", compactionResult.isSuccess());
        outputs.put("outcome", outcome);
        outputs.put("kind", metadata.getKind().name());
        outputs.put("trigger", metadata.getTrigger().name());
        outputs.put("preTokens", metadata.getPreCompactTokenCount());
        outputs.put("postTokens", metadata.getPostCompactTokenCount());
        outputs.put("messagesSummarized", metadata.getMessagesSummarized());
        outputs.put("summaryCalls", metadata.getSummaryCalls());
        outputs.put("summaryChars", summary == null ? 0 : summary.length());
        if (metadata.getBlockingLimit() > 0) {
            outputs.put("blockingLimit", metadata.getBlockingLimit());
            outputs.put("overBlockingLimit", metadata.isOverBlockingLimit());
        }
        if (metadata.getKind() == CompactionKind.ROLLING) {
            outputs.put("summaryTokens", metadata.getSummaryTokens());
            outputs.put("headTokens", metadata.getHeadTokens());
            outputs.put("spanTokens", metadata.getSpanTokens());
            outputs.put("tailTokens", metadata.getTailTokens());
        }
        metadata.getAbsorbedFromSeq().ifPresent(seq -> outputs.put("absorbedFromSeq", seq));
        metadata.getAbsorbedToSeq().ifPresent(seq -> outputs.put("absorbedToSeq", seq));
        outputs.put("discoveredToolCount", metadata.getDiscoveredToolNames().size());
        if (policy.capturesContent() && summary != null) {
            outputs.put("summary", policy.truncate(summary));
        }
        if (error != null) {
            outputs.put("errorType", error.getClass().getSimpleName());
            outputs.put("errorMessage", String.valueOf(error.getMessage()));
        }
        return outputs;
    }
}
