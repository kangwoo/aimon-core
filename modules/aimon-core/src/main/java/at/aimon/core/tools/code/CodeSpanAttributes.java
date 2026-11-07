package at.aimon.core.tools.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tracing.SpanAttributes;
import at.aimon.core.tracing.Tracer;

/**
 * The trace of programmatic tool calling ({@code docs/design/tool/programmatic-tool-calling.md} §12.2): the attribute
 * names, the iteration's {@code ptc.unavailable_reason}, and the {@code TOOL} span of each call a program makes.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class CodeSpanAttributes {

    /** On an iteration span: why {@code ExecuteCode} was not offered to an agent that opens tools to code. */
    public static final String UNAVAILABLE_REASON = "ptc.unavailable_reason";
    /** On an {@code ExecuteCode} span whose engine is bound by it: the effective {@code shell-engine-tool-scope}. */
    public static final String TOOL_SCOPE = "ptc.tool_scope";
    /** On an {@code ExecuteCode} span and on each nested call's span: the program's language. */
    public static final String LANGUAGE = "ptc.language";
    /** The path a call took, {@code direct} or {@code code} ({@link ToolSpans#PATH}). */
    public static final String PATH = ToolSpans.PATH;
    /** On a nested call's span: the tool-use id of the {@code ExecuteCode} call whose program made it. */
    public static final String PARENT_TOOL_USE_ID = "ptc.parent_tool_use_id";
    /** On a nested call's span: why the bridge refused it before it reached the dispatcher. */
    public static final String REFUSED = "ptc.refused";

    private CodeSpanAttributes() {
    }

    /**
     * Sets {@link #UNAVAILABLE_REASON} on an iteration's span when {@code offer} recorded why {@code ExecuteCode} was
     * left out; does nothing otherwise — the agent does not open tools to code, or {@code ExecuteCode} was offered.
     *
     * @param iterationSpan
     *            the iteration's span (must not be null)
     * @param offer
     *            the iteration's offer context, after its {@link ToolOfferContext#offer()} (must not be null)
     */
    public static void annotateIteration(SpanAttributes iterationSpan, ToolOfferContext offer) {
        Objects.requireNonNull(iterationSpan, "iterationSpan cannot be null");
        final String reason = Objects.requireNonNull(offer, "offer cannot be null").getUnavailableReasons()
                .get(ExecuteCodeTool.TOOL_NAME);
        if (reason != null) {
            iterationSpan.setAttribute(UNAVAILABLE_REASON, reason);
        }
    }

    /**
     * Returns the span helper of one {@code ExecuteCode} call's bridge.
     *
     * @param parentContext
     *            the {@code ExecuteCode} call's tool context, whose metadata names its span (must not be null)
     * @param parentToolUseId
     *            the {@code ExecuteCode} call's id (must not be null)
     * @param language
     *            the program's language (must not be null)
     * @return the helper (never null)
     */
    static Nested nested(ToolContext parentContext, String parentToolUseId, String language) {
        return new Nested(parentContext, parentToolUseId, language);
    }

    /** The {@code TOOL} spans of the calls one program makes, all under its {@code ExecuteCode} call's span. */
    static final class Nested {
        private final ToolContext parentContext;
        private final String parentToolUseId;
        private final String language;

        private Nested(ToolContext parentContext, String parentToolUseId, String language) {
            this.parentContext = Objects.requireNonNull(parentContext, "parentContext cannot be null");
            this.parentToolUseId = Objects.requireNonNull(parentToolUseId, "parentToolUseId cannot be null");
            this.language = Objects.requireNonNull(language, "language cannot be null");
        }

        /**
         * Opens the {@code TOOL} span of one nested call, or returns {@code null} when the run is not traced.
         *
         * @param toolUseId
         *            the nested call's id, {@code <parent>#<n>} (must not be null)
         * @param toolName
         *            the tool the program called (must not be null)
         * @param input
         *            the arguments it passed (must not be null)
         * @return the open span, or {@code null}
         */
        Tracer.Span open(String toolUseId, String toolName, Map<String, Object> input) {
            final Optional<Tracer> tracer = ToolSpans.tracerOf(parentContext);
            if (tracer.isEmpty()) {
                return null;
            }
            final Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("toolUseId", toolUseId);
            inputs.put("input", input);
            final Tracer.Span span = ToolSpans.openTool(tracer.get(), parentContext, toolName, inputs);
            if (span == null) {
                return null;
            }
            span.setAttribute(PATH, ToolSpans.PATH_CODE);
            span.setAttribute(PARENT_TOOL_USE_ID, parentToolUseId);
            span.setAttribute(LANGUAGE, language);
            parentContext.get(ToolContextKeys.ITERATION_COUNT)
                    .ifPresent(iteration -> span.setAttribute("iteration", String.valueOf(iteration)));
            return span;
        }

        /**
         * Records a call the bridge refused before it reached the dispatcher as a zero-length {@code TOOL} span, with
         * {@link #REFUSED} naming the refusal, ended in error — so the trace lists every call the program asked for, as
         * the observation does.
         *
         * @param toolUseId
         *            the nested call's id (must not be null)
         * @param toolName
         *            the tool the program called (must not be null)
         * @param input
         *            the arguments it passed (must not be null)
         * @param kind
         *            the refusal ({@code closed}, {@code call limit}, {@code not callable}, ...) (must not be null)
         * @param message
         *            what the program was told (must not be null)
         */
        void refused(String toolUseId, String toolName, Map<String, Object> input, String kind, String message) {
            final Tracer.Span span = open(toolUseId, toolName, input);
            if (span == null) {
                return;
            }
            try {
                span.setAttribute(REFUSED, kind);
                span.error(message);
            } finally {
                span.close();
            }
        }
    }
}
