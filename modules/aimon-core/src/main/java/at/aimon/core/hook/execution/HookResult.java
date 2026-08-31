package at.aimon.core.hook.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.rewake.RewakeSpec;

/**
 * Represents the result of a hook execution.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Hook results carry two independent axes:
 * <ul>
 * <li><b>{@link Decision}</b> &mdash; pre-tool semantic outcome ({@code ALLOW / ASK / DENY}). Post-tool / lifecycle
 * hooks always emit {@link Decision#ALLOW}.
 * <li><b>{@link FlowControl}</b> &mdash; chain-level signal ({@code CONTINUE / BLOCK}). Independent of decision so that
 * a fail-closed timeout can short-circuit the chain without claiming to be a deny.
 * </ul>
 *
 * <p>
 * Hook results can additionally:
 *
 * <ul>
 * <li>Provide advisory feedback for the model (see {@link #withFeedback(String)} for where it surfaces)
 * <li>Carry an updated {@link ToolInput} that should replace the input passed to subsequent PreTool hooks and to the
 * actual tool dispatcher
 * <li>Carry an updated {@link ToolResult} that should replace the output passed to subsequent PostTool hooks and to
 * the LLM
 * </ul>
 *
 * <p>
 * Backward-compat mapping &mdash; the {@link HookStatus} surface is derived from {@link Decision}:
 * {@link Decision#ALLOW} &rarr; {@link HookStatus#SUCCESS}, {@link Decision#ASK} &rarr; {@link HookStatus#ASK},
 * {@link Decision#DENY} &rarr; {@link HookStatus#BLOCKED}. Existing callers using {@link #success()} /
 * {@link #block(String)} / {@link #isBlocked()} continue to work unchanged.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Allow without feedback (legacy: success())
 *     HookResult result = HookResult.allow();
 *
 *     // Allow with feedback (legacy: withFeedback("..."))
 *     HookResult result = HookResult.withFeedback("Tool executed successfully");
 *
 *     // Deny with reason (legacy: block("..."))
 *     HookResult result = HookResult.deny("Dangerous operation detected");
 *
 *     // Combine multiple hook results with deny &gt; ask &gt; allow precedence
 *     HookResult merged = HookResult.merge(r1, r2, r3);
 * }
 * </pre>
 *
 * <p>
 * <b>Async rewake.</b> A hook may additionally return one or more {@link RewakeSpec rewake specs} via
 * {@link #asyncRewake(RewakeSpec)} or {@link Builder#rewakeSpec(RewakeSpec)}. Rewake specs are <i>orthogonal</i> to
 * {@link Decision} / {@link FlowControl}: emitting a rewake does not deny the live turn — the result is still
 * observably {@link Decision#ALLOW} for the current dispatch. The framework collects every spec across the chain
 * (merge concatenates them in argument order) and hands them to the rewake service for scheduling.
 */
public final class HookResult {
    /**
     * Creates a success result without feedback. Equivalent to {@link #allow()}.
     *
     * @return A success result (never null)
     */
    public static HookResult success() {
        return allow();
    }

    /**
     * Creates an {@link Decision#ALLOW} result without feedback (alias of {@link #success()}).
     *
     * @return An allow result (never null)
     */
    public static HookResult allow() {
        return new HookResult(Decision.ALLOW, FlowControl.CONTINUE, null, null, null, List.of());
    }

    /**
     * Creates a success result with feedback to be sent to the LLM.
     *
     * <p>
     * Where the feedback surfaces depends on which chain emitted it:
     * <ul>
     * <li><b>Tool-scoped chains</b> ({@code PermissionRequest} / {@code PreTool} / {@code PostTool}) — appended to
     * that tool's result inside a {@code <system-reminder key="hook-feedback">} block, so the note stays attributable
     * to the dispatch it describes even when a batch runs tools in parallel.
     * <li><b>Lifecycle chains</b> ({@code OnStart}, {@code PreCompact}, ...) — added as a user message to the
     * conversation context at the firing site.
     * </ul>
     *
     * <p>
     * Feedback on a {@link #deny(String) deny} result is the deny reason and is rendered as the block/error message
     * instead; it is not additionally surfaced as advisory feedback.
     *
     * @param feedback
     *            The feedback message (must not be null)
     * @return A success result with feedback (never null)
     * @throws NullPointerException
     *             if feedback is null
     */
    public static HookResult withFeedback(String feedback) {
        Objects.requireNonNull(feedback, "Feedback cannot be null");
        return new HookResult(Decision.ALLOW, FlowControl.CONTINUE, feedback, null, null, List.of());
    }

    /**
     * Creates a blocked result that prevents execution. Equivalent to {@link #deny(String)}.
     *
     * <p>
     * Only applicable for PreToolHook. The tools execution will be skipped and the block reason will be sent to the LLM
     * as a tools error.
     *
     * @param reason
     *            The reason for blocking (must not be null)
     * @return A blocked result (never null)
     * @throws NullPointerException
     *             if reason is null
     */
    public static HookResult block(String reason) {
        return deny(reason);
    }

    /**
     * Creates a {@link Decision#DENY} + {@link FlowControl#BLOCK} result (alias of {@link #block(String)}).
     *
     * @param reason
     *            the deny reason carried as feedback (must not be null)
     * @return a deny result (never null)
     * @throws NullPointerException
     *             if reason is null
     */
    public static HookResult deny(String reason) {
        Objects.requireNonNull(reason, "Deny reason cannot be null");
        return new HookResult(Decision.DENY, FlowControl.BLOCK, reason, null, null, List.of());
    }

    /**
     * Creates a {@link Decision#ASK} + {@link FlowControl#CONTINUE} result that defers to the user.
     *
     * <p>
     * The dispatcher promotes ASK to {@code allow} or {@code deny} via the configured ask-prompt handler. When no
     * interactive surface is available the dispatcher falls back to {@code AIMON_HOOK_ASK_DEFAULT} (default
     * {@code deny}).
     *
     * @param reason
     *            the prompt shown to the user (must not be null)
     * @return an ask result (never null)
     * @throws NullPointerException
     *             if reason is null
     */
    public static HookResult ask(String reason) {
        Objects.requireNonNull(reason, "Ask reason cannot be null");
        return new HookResult(Decision.ASK, FlowControl.CONTINUE, reason, null, null, List.of());
    }

    /**
     * Creates a success result that replaces the tool input passed to subsequent PreTool hooks and the dispatcher.
     *
     * @param updatedInput
     *            The replacement tool input (must not be null)
     * @return A success result carrying the updated input (never null)
     * @throws NullPointerException
     *             if updatedInput is null
     */
    public static HookResult withUpdatedInput(ToolInput updatedInput) {
        Objects.requireNonNull(updatedInput, "Updated input cannot be null");
        return new HookResult(Decision.ALLOW, FlowControl.CONTINUE, null, updatedInput, null, List.of());
    }

    /**
     * Creates a success result that replaces the tool output passed to subsequent PostTool hooks and the LLM.
     *
     * @param updatedOutput
     *            The replacement tool output (must not be null)
     * @return A success result carrying the updated output (never null)
     * @throws NullPointerException
     *             if updatedOutput is null
     */
    public static HookResult withUpdatedOutput(ToolResult updatedOutput) {
        Objects.requireNonNull(updatedOutput, "Updated output cannot be null");
        return new HookResult(Decision.ALLOW, FlowControl.CONTINUE, null, null, updatedOutput, List.of());
    }

    /**
     * Creates an {@link Decision#ALLOW} result that additionally requests an asynchronous re-fire of the originating
     * hook.
     *
     * <p>
     * Rewake is orthogonal to {@link Decision} / {@link FlowControl} — the live turn proceeds as if the hook returned
     * {@link #allow()}; the spec is queued by the rewake service and re-delivered to the same hook on a follow-up
     * firing.
     *
     * @param spec
     *            the rewake spec (must not be null)
     * @return an allow result carrying a single rewake spec (never null)
     * @throws NullPointerException
     *             if spec is null
     */
    public static HookResult asyncRewake(RewakeSpec spec) {
        Objects.requireNonNull(spec, "RewakeSpec cannot be null");
        return new HookResult(Decision.ALLOW, FlowControl.CONTINUE, null, null, null, List.of(spec));
    }

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merges multiple hook results into a single combined result using this precedence:
     * <ul>
     * <li><b>decision</b>: {@code DENY > ASK > ALLOW} (most restrictive wins).
     * <li><b>flowControl</b>: {@code BLOCK > CONTINUE} (most restrictive wins).
     * <li><b>feedback</b>: non-null feedback strings are joined with {@code "\n"} in argument order.
     * <li><b>updatedInput</b> / <b>updatedOutput</b>: first non-null wins. When more than one hook supplies a
     * replacement, only the first is kept &mdash; callers must not rely on order for conflicting mutations.
     * <li><b>rewakeSpecs</b>: concatenated in argument order. Every spec emitted across the chain survives the merge
     * &mdash; the rewake service schedules them all.
     * </ul>
     *
     * <p>
     * An empty / null vararg returns {@link #allow()}. Null elements inside the vararg are skipped silently
     * (treated as {@link #allow()}).
     *
     * @param results
     *            results to merge (may be empty)
     * @return merged result (never null)
     */
    public static HookResult merge(HookResult... results) {
        if (results == null || results.length == 0) {
            return allow();
        }
        return mergeAll(Arrays.asList(results));
    }

    /**
     * Iterable variant of {@link #merge(HookResult...)} for use with collection-typed result lists.
     *
     * @param results
     *            results to merge (must not be null; may be empty)
     * @return merged result (never null)
     * @throws NullPointerException
     *             if {@code results} is null
     */
    public static HookResult merge(Iterable<HookResult> results) {
        Objects.requireNonNull(results, "results cannot be null");
        return mergeAll(results);
    }

    private static HookResult mergeAll(Iterable<HookResult> results) {
        Decision decision = Decision.ALLOW;
        FlowControl flow = FlowControl.CONTINUE;
        final List<String> feedbacks = new ArrayList<>();
        ToolInput updatedInput = null;
        ToolResult updatedOutput = null;
        final List<RewakeSpec> rewakes = new ArrayList<>();

        for (HookResult r : results) {
            if (r == null) {
                continue;
            }
            decision = Decision.max(decision, r.decision);
            flow = FlowControl.max(flow, r.flowControl);
            if (r.feedback != null) {
                feedbacks.add(r.feedback);
            }
            if (updatedInput == null && r.updatedInput != null) {
                updatedInput = r.updatedInput;
            }
            if (updatedOutput == null && r.updatedOutput != null) {
                updatedOutput = r.updatedOutput;
            }
            rewakes.addAll(r.rewakeSpecs);
        }

        final String feedback = feedbacks.isEmpty() ? null : String.join("\n", feedbacks);
        return new HookResult(decision, flow, feedback, updatedInput, updatedOutput, rewakes);
    }

    private final Decision decision;
    private final FlowControl flowControl;
    private final String feedback;
    private final ToolInput updatedInput;
    private final ToolResult updatedOutput;
    private final List<RewakeSpec> rewakeSpecs;

    private HookResult(Decision decision, FlowControl flowControl, String feedback, ToolInput updatedInput,
            ToolResult updatedOutput, List<RewakeSpec> rewakeSpecs) {
        this.decision = Objects.requireNonNull(decision, "Decision cannot be null");
        this.flowControl = Objects.requireNonNull(flowControl, "FlowControl cannot be null");
        this.feedback = feedback;
        this.updatedInput = updatedInput;
        this.updatedOutput = updatedOutput;
        Objects.requireNonNull(rewakeSpecs, "RewakeSpecs cannot be null");
        for (RewakeSpec spec : rewakeSpecs) {
            Objects.requireNonNull(spec, "RewakeSpec entries cannot be null");
        }
        this.rewakeSpecs = List.copyOf(rewakeSpecs);
    }

    /**
     * Gets the hook execution status.
     *
     * <p>
     * Derived from {@link #getDecision()}: {@link Decision#DENY} &rarr; {@link HookStatus#BLOCKED},
     * {@link Decision#ASK} &rarr; {@link HookStatus#ASK}, {@link Decision#ALLOW} &rarr; {@link HookStatus#SUCCESS}.
     *
     * @return The status (never null)
     */
    public HookStatus getStatus() {
        switch (decision) {
            case DENY :
                return HookStatus.BLOCKED;
            case ASK :
                return HookStatus.ASK;
            case ALLOW :
            default :
                return HookStatus.SUCCESS;
        }
    }

    /**
     * Gets the decision (deny / ask / allow).
     *
     * @return the decision (never null)
     */
    public Decision getDecision() {
        return decision;
    }

    /**
     * Gets the flow-control flag.
     *
     * @return the flow control (never null)
     */
    public FlowControl getFlowControl() {
        return flowControl;
    }

    /**
     * Checks if the hook blocked the operation.
     *
     * <p>
     * Equivalent to {@code getDecision() == Decision.DENY}.
     *
     * @return true if the decision is DENY, false otherwise
     */
    public boolean isBlocked() {
        return decision == Decision.DENY;
    }

    /**
     * Gets the optional feedback message.
     *
     * @return An optional containing the feedback, or empty if no feedback
     */
    public Optional<String> getFeedback() {
        return Optional.ofNullable(feedback);
    }

    /**
     * Gets the optional updated tool input that should replace the dispatcher's input.
     *
     * @return An optional containing the replacement input, or empty if the hook left the input untouched
     */
    public Optional<ToolInput> getUpdatedInput() {
        return Optional.ofNullable(updatedInput);
    }

    /**
     * Gets the optional updated tool output that should replace the LLM-facing output.
     *
     * @return An optional containing the replacement output, or empty if the hook left the output untouched
     */
    public Optional<ToolResult> getUpdatedOutput() {
        return Optional.ofNullable(updatedOutput);
    }

    /**
     * Gets the rewake specs emitted by this result.
     *
     * <p>
     * The list is never null but may be empty. Each spec describes one queued re-fire of the originating hook.
     *
     * @return immutable list of rewake specs (never null; possibly empty)
     */
    public List<RewakeSpec> getRewakeSpecs() {
        return rewakeSpecs;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("HookResult{decision=").append(decision).append(", flowControl=")
                .append(flowControl);
        if (feedback != null) {
            sb.append(", feedback='").append(feedback).append('\'');
        }
        if (updatedInput != null) {
            sb.append(", updatedInput=").append(updatedInput);
        }
        if (updatedOutput != null) {
            sb.append(", updatedOutput=").append(updatedOutput);
        }
        if (!rewakeSpecs.isEmpty()) {
            sb.append(", rewakeSpecs=").append(rewakeSpecs);
        }
        return sb.append('}').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final HookResult that = (HookResult) o;
        return decision == that.decision && flowControl == that.flowControl && Objects.equals(feedback, that.feedback)
                && Objects.equals(updatedInput, that.updatedInput) && Objects.equals(updatedOutput, that.updatedOutput)
                && rewakeSpecs.equals(that.rewakeSpecs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, flowControl, feedback, updatedInput, updatedOutput, rewakeSpecs);
    }

    /** Builder for HookResult. */
    public static final class Builder {
        private Decision decision = Decision.ALLOW;
        private FlowControl flowControl = FlowControl.CONTINUE;
        private String feedback;
        private ToolInput updatedInput;
        private ToolResult updatedOutput;
        private final List<RewakeSpec> rewakeSpecs = new ArrayList<>();

        private Builder() {
        }

        /**
         * Legacy setter that maps {@link HookStatus} onto {@link Decision} / {@link FlowControl} for backward
         * compatibility. {@link HookStatus#BLOCKED} sets decision DENY + flow BLOCK; {@link HookStatus#SUCCESS}
         * sets decision ALLOW + flow CONTINUE. Prefer {@link #decision(Decision)} for new code.
         *
         * @param status
         *            the status (must not be null)
         * @return This builder
         */
        public Builder status(HookStatus status) {
            Objects.requireNonNull(status, "Status cannot be null");
            switch (status) {
                case BLOCKED :
                    this.decision = Decision.DENY;
                    this.flowControl = FlowControl.BLOCK;
                    break;
                case ASK :
                    this.decision = Decision.ASK;
                    this.flowControl = FlowControl.CONTINUE;
                    break;
                case SUCCESS :
                default :
                    this.decision = Decision.ALLOW;
                    this.flowControl = FlowControl.CONTINUE;
                    break;
            }
            return this;
        }

        /**
         * Sets the {@link Decision} (default {@link Decision#ALLOW}).
         *
         * @param decision
         *            the decision (must not be null)
         * @return This builder
         */
        public Builder decision(Decision decision) {
            this.decision = Objects.requireNonNull(decision, "Decision cannot be null");
            return this;
        }

        /**
         * Sets the {@link FlowControl} (default {@link FlowControl#CONTINUE}).
         *
         * @param flowControl
         *            the flow-control flag (must not be null)
         * @return This builder
         */
        public Builder flowControl(FlowControl flowControl) {
            this.flowControl = Objects.requireNonNull(flowControl, "FlowControl cannot be null");
            return this;
        }

        /**
         * Sets the feedback message; pass {@code null} to clear it.
         *
         * @param feedback
         *            The feedback message (may be null)
         * @return This builder
         */
        public Builder feedback(String feedback) {
            this.feedback = feedback;
            return this;
        }

        /**
         * Sets the replacement tool input; pass {@code null} to clear it.
         *
         * @param updatedInput
         *            The replacement input (may be null)
         * @return This builder
         */
        public Builder updatedInput(ToolInput updatedInput) {
            this.updatedInput = updatedInput;
            return this;
        }

        /**
         * Sets the replacement tool output; pass {@code null} to clear it.
         *
         * @param updatedOutput
         *            The replacement output (may be null)
         * @return This builder
         */
        public Builder updatedOutput(ToolResult updatedOutput) {
            this.updatedOutput = updatedOutput;
            return this;
        }

        /**
         * Appends a rewake spec to this result. Multiple calls accumulate; merge concatenates rewake specs
         * across the chain in argument order.
         *
         * @param spec
         *            the rewake spec (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if spec is null
         */
        public Builder rewakeSpec(RewakeSpec spec) {
            Objects.requireNonNull(spec, "RewakeSpec cannot be null");
            this.rewakeSpecs.add(spec);
            return this;
        }

        /**
         * Builds the {@link HookResult}.
         *
         * @return A new HookResult (never null)
         */
        public HookResult build() {
            return new HookResult(decision, flowControl, feedback, updatedInput, updatedOutput, rewakeSpecs);
        }
    }
}
