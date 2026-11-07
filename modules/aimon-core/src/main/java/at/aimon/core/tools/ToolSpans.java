package at.aimon.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.tracing.SpanAttributes;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * The span half of a {@code TOOL} span, shared by the loops ({@code ToolCallTracing}) and by a tool that makes calls of
 * its own ({@code ExecuteCode}'s bridge, {@code docs/design/tool/programmatic-tool-calling.md} §12.2).
 *
 * <p>
 * It lives here, beside {@link ToolContextKeys}, rather than in {@code at.aimon.core.tracing}: it reads the tool
 * context, and {@code tracing} depending on {@code tools} would close a package cycle ({@code tools} names
 * {@link Tracer} in {@link ToolContextKeys#TRACER}). {@code ExecuteCode}'s package may not depend on
 * {@code toolinvocation}, so the helpers cannot stay in {@code ToolCallTracing} either.
 *
 * <p>
 * The parent of a span is read from the tool context's {@link ToolContextKeys#LLM_CALL_METADATA_KEY} metadata; there is
 * no thread-local. Every start goes through {@link Spans#startChildSafely}, so a throwing tracer never fails a call.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class ToolSpans {

    /** The attribute naming which path a call took: {@link #PATH_DIRECT} or {@link #PATH_CODE}. */
    public static final String PATH = "tool.path";
    /** A call the model made itself, in its own tool-use block. */
    public static final String PATH_DIRECT = "direct";
    /** A call a program made through {@code ExecuteCode}. */
    public static final String PATH_CODE = "code";

    private ToolSpans() {
    }

    /**
     * Returns the tracer bound into {@code toolContext}, or empty when the run is not traced.
     *
     * @param toolContext
     *            the context (must not be null)
     * @return the tracer, or empty
     */
    public static Optional<Tracer> tracerOf(ToolContext toolContext) {
        return Objects.requireNonNull(toolContext, "toolContext cannot be null").get(ToolContextKeys.TRACER);
    }

    /**
     * Returns the span of the call {@code toolContext} was handed to, as a view that can only add attributes, or empty
     * when the call is not traced.
     *
     * @param toolContext
     *            the context (must not be null)
     * @return the span's attribute view, or empty
     */
    public static Optional<SpanAttributes> currentSpan(ToolContext toolContext) {
        return Objects.requireNonNull(toolContext, "toolContext cannot be null").get(ToolContextKeys.TOOL_SPAN);
    }

    /**
     * Opens a {@code TOOL} span as a child of the span {@code toolContext}'s metadata points at, or returns
     * {@code null} when it points at none (tracing off, or the call was not enriched), so no orphan span is produced.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param toolContext
     *            the context whose metadata names the parent (must not be null)
     * @param name
     *            the span name, the tool's name (must not be null)
     * @param inputs
     *            the input snapshot (may be null)
     * @return the open span, or {@code null} when there is no parent to nest under
     */
    public static Tracer.Span openTool(Tracer tracer, ToolContext toolContext, String name,
            Map<String, Object> inputs) {
        Objects.requireNonNull(tracer, "tracer cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        final Optional<SpanContext> parent = Objects.requireNonNull(toolContext, "toolContext cannot be null")
                .get(ToolContextKeys.LLM_CALL_METADATA_KEY).flatMap(SpanContext::readFrom);
        if (parent.isEmpty()) {
            return null;
        }
        return Spans.startChildSafely(tracer, parent.get(), SpanType.TOOL, name, inputs);
    }

    /**
     * Returns {@code toolContext} with its {@link ToolContextKeys#LLM_CALL_METADATA_KEY} metadata rotated to point at
     * {@code toolSpan}, and {@link ToolContextKeys#TOOL_SPAN} set to it, so what the tool starts nests under the call
     * and the tool can annotate its own span. When the rotation changes nothing — a no-op span, or no metadata at all —
     * the same instance is returned, so tracing-off allocates nothing.
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
                        .put(ToolContextKeys.LLM_CALL_METADATA_KEY, rotated).put(ToolContextKeys.TOOL_SPAN, toolSpan)
                        .build();
    }

    /**
     * Records a call's outcome on its span: always the {@code isError}/{@code contentChars} summary, and the
     * (truncated) content when {@code policy} {@link TracePayloadPolicy#capturesContent() captures content}. An error
     * marks the span as failed.
     *
     * @param toolSpan
     *            the tool call's span (must not be null)
     * @param isError
     *            whether the call failed
     * @param content
     *            the result's text (may be null)
     * @param policy
     *            the payload capture policy (must not be null)
     */
    public static void recordOutcome(Tracer.Span toolSpan, boolean isError, String content, TracePayloadPolicy policy) {
        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("isError", isError);
        outputs.put("contentChars", content == null ? 0 : content.length());
        if (policy.capturesContent() && content != null) {
            outputs.put("content", policy.truncate(content));
        }
        toolSpan.setOutputs(outputs);
        if (isError) {
            toolSpan.error(content);
        }
    }

    /**
     * Records a call that threw instead of returning a result. A cancellation — a
     * {@link CancelledExecutionException}, or any throwable while the context's
     * {@link InterruptToolKeys#CANCELLATION_SIGNAL} is tripped — ends the span interrupted; anything else, an
     * {@link Error} included, ends it in error.
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
