package at.aimon.core.toolinvocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * The {@code TOOL} span both ReAct executors open around one tool call (TRACE-01).
 *
 * <p>
 * The span's parent is read from the tool context's {@link ToolContextKeys#LLM_CALL_METADATA_KEY} metadata — the
 * iteration the call belongs to. {@link #contextUnder} then rotates that same key to point at the TOOL span, so a
 * sub-execution the tool starts ({@code Task}, {@code Workflow}, a fork-mode skill) nests under the call that started
 * it rather than beside it. No new context key is involved: the metadata stays the one propagation spine.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class ToolCallTracing {

    private ToolCallTracing() {
    }

    /**
     * Opens a {@code TOOL} span as a child of the span the tool context's metadata points at, or returns {@code null}
     * when it points at none (tracing off, or the call was not enriched) so no orphan span is produced.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param toolContext
     *            the context the tool is about to receive (must not be null)
     * @param toolUse
     *            the tool call (must not be null)
     * @param iterationCount
     *            the iteration the call belongs to
     * @param invokerType
     *            who is calling the tool (must not be null)
     * @return the open span, or {@code null} when there is no parent to nest under
     */
    public static Tracer.Span open(Tracer tracer, ToolContext toolContext, ToolUse toolUse, int iterationCount,
            InvokerType invokerType) {
        Objects.requireNonNull(tracer, "tracer cannot be null");
        Objects.requireNonNull(toolContext, "toolContext cannot be null");
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        Objects.requireNonNull(invokerType, "invokerType cannot be null");
        final Optional<SpanContext> parent = toolContext.get(ToolContextKeys.LLM_CALL_METADATA_KEY)
                .flatMap(SpanContext::readFrom);
        if (parent.isEmpty()) {
            return null;
        }
        // ToolUse guarantees a non-null id and input, so Map.of cannot throw here.
        final Tracer.Span span = Spans.startChildSafely(tracer, parent.get(), SpanType.TOOL, toolUse.getName(),
                Map.of("toolUseId", toolUse.getId(), "input", toolUse.getInput()));
        span.setAttribute("iteration", String.valueOf(iterationCount));
        span.setAttribute("invokerType", invokerType.name());
        return span;
    }

    /**
     * Returns {@code toolContext} with its {@link ToolContextKeys#LLM_CALL_METADATA_KEY} metadata rotated to point at
     * {@code toolSpan}. When the rotation changes nothing — a no-op span, or no metadata at all — the same instance
     * is returned, so tracing-off allocates nothing.
     *
     * @param toolSpan
     *            the tool call's span (must not be null)
     * @param toolContext
     *            the context to rotate (must not be null)
     * @return the rotated context, or {@code toolContext} itself when nothing changed (never null)
     */
    public static ToolContext contextUnder(Tracer.Span toolSpan, ToolContext toolContext) {
        Objects.requireNonNull(toolSpan, "toolSpan cannot be null");
        final Optional<LlmCallMetadata> metadata = toolContext.get(ToolContextKeys.LLM_CALL_METADATA_KEY);
        if (metadata.isEmpty()) {
            return toolContext;
        }
        final LlmCallMetadata rotated = Spans.enrichSafely(toolSpan, metadata.get());
        return rotated == metadata.get()
                ? toolContext
                : ToolContext.builder().putAll(toolContext.getContext())
                        .put(ToolContextKeys.LLM_CALL_METADATA_KEY, rotated).build();
    }

    /**
     * Records the tool span outcome. Always captures the {@code isError}/{@code contentChars} summary; TRACE-02: when
     * {@code policy} {@link TracePayloadPolicy#capturesContent() captures content}, the (truncated) result content is
     * additionally attached under {@code content}. An error result marks the span as failed.
     *
     * @param toolSpan
     *            the tool call's span (must not be null)
     * @param result
     *            the tool result (must not be null)
     * @param policy
     *            the payload capture policy (must not be null)
     */
    public static void recordOutcome(Tracer.Span toolSpan, ToolUseResult result, TracePayloadPolicy policy) {
        final String content = result.getContent();
        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("isError", result.isError());
        outputs.put("contentChars", content == null ? 0 : content.length());
        if (policy.capturesContent() && content != null) {
            outputs.put("content", policy.truncate(content));
        }
        toolSpan.setOutputs(outputs);
        if (result.isError()) {
            toolSpan.error(content);
        }
    }

    /**
     * Records a tool call that threw instead of returning a result. A cancellation — a
     * {@link CancelledExecutionException}, or any throwable while the context's
     * {@link InterruptToolKeys#CANCELLATION_SIGNAL} is tripped — ends the span interrupted, matching the ITERATION it
     * belongs to; anything else, an {@link Error} included, ends it in error.
     *
     * @param toolSpan
     *            the tool call's span (must not be null)
     * @param failure
     *            what the call threw (must not be null)
     * @param toolContext
     *            the context the tool received (must not be null)
     */
    public static void recordFailure(Tracer.Span toolSpan, Throwable failure, ToolContext toolContext) {
        final boolean cancelled = failure instanceof CancelledExecutionException || toolContext
                .get(InterruptToolKeys.CANCELLATION_SIGNAL).map(CancellationSignal::isCancelled).orElse(false);
        if (cancelled) {
            toolSpan.interrupted();
        } else {
            toolSpan.error(failure);
        }
    }
}
