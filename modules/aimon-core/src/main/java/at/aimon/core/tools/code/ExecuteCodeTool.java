package at.aimon.core.tools.code;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * {@code ExecuteCode} — runs a program the model wrote, whose function calls are tool calls
 * ({@code docs/design/tool/programmatic-tool-calling.md}).
 *
 * <p>
 * The tool's grade and description depend on its offer context, so it is the one tool that overrides
 * {@link #describe(ToolOfferContext)}: the engine is chosen from the environment's static facts and the agent's
 * {@code programmatic-engine} (§6.2), the callable set is what the agent's {@code programmatic-tools} leaves after the
 * offer's eligibility, the tools' own declarations and the code-path rules (§7.3), and the grade is
 * {@code max(engine's intrinsic grade, callable set)} (decision 10). Without an engine or a callable tool the tool is
 * not offerable. Its constant getters answer with the most conservative grade, for a reader that has no offer.
 *
 * <p>
 * A run freezes the callable set from the call's offer context, hands every nested call to a {@link ToolCallDispatcher}
 * on the {@code CODE} path through a per-call bridge, and returns the program's standard output as the observation,
 * followed by the notices and hook feedback the nested calls produced (§12.1). Never throws.
 *
 * <p>
 * Stateless: the engines are application-scoped and owned by the assembly that built them.
 */
public final class ExecuteCodeTool extends AbstractTool {

    /** The tool name exposed to the LLM. */
    public static final String TOOL_NAME = "ExecuteCode";

    private static final Logger log = LoggerFactory.getLogger(ExecuteCodeTool.class);

    private static final String STATIC_DESCRIPTION = "Run a program whose function calls are tool calls. Offered "
            + "with the language and the functions it can call when the agent opens tools to code.";

    private final List<CodeExecutionEngine> engines;
    private final CodeExecutionLimits limits;
    private final ShellEngineToolScope toolScope;

    /**
     * Creates the tool with the default {@code shell-engine-tool-scope} ({@link ShellEngineToolScope#ENVIRONMENT}).
     *
     * @param engines
     *            the assembled engines, at most one per language (must not be null; may be empty, then the tool is
     *            never offerable)
     * @param limits
     *            the limits every program is held to (must not be null)
     */
    public ExecuteCodeTool(List<CodeExecutionEngine> engines, CodeExecutionLimits limits) {
        this(engines, limits, ShellEngineToolScope.ENVIRONMENT);
    }

    /**
     * Creates the tool.
     *
     * @param engines
     *            the assembled engines, at most one per language (must not be null; may be empty, then the tool is
     *            never offerable)
     * @param limits
     *            the limits every program is held to (must not be null)
     * @param toolScope
     *            the application's {@code shell-engine-tool-scope} — the ceiling an agent can only narrow, applied to
     *            an engine {@linkplain CodeExecutionEngine#isToolScopeBound() bound by it} (must not be null)
     */
    public ExecuteCodeTool(List<CodeExecutionEngine> engines, CodeExecutionLimits limits,
            ShellEngineToolScope toolScope) {
        super(TOOL_NAME, STATIC_DESCRIPTION, createInputSchema(limits));
        this.engines = List.copyOf(Objects.requireNonNull(engines, "engines cannot be null"));
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        this.toolScope = Objects.requireNonNull(toolScope, "toolScope cannot be null");
    }

    private static Map<String, Object> createInputSchema(CodeExecutionLimits limits) {
        Objects.requireNonNull(limits, "limits cannot be null");
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("code", Map.of("type", "string", "description", "The program to run."), "timeout_seconds",
                        Map.of("type", "integer", "description",
                                "Optional wall-clock limit in seconds (default " + limits.getWallClock().toSeconds()
                                        + ", at most " + limits.getMaxWallClock().toSeconds() + ").")),
                "required", List.of("code"));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.MUTATING;
    }

    @Override
    public DestructiveBehavior getDestructiveBehavior() {
        return DestructiveBehavior.DESTRUCTIVE;
    }

    @Override
    public DataReach dataReach() {
        return DataReach.EXTERNAL;
    }

    /** Decision 15: code that calls {@code ExecuteCode} would nest programs outside the script's limits. */
    @Override
    public boolean isCodeCallable() {
        return false;
    }

    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.SEQUENTIAL;
    }

    /**
     * Declares {@link InterruptBehavior#COOPERATIVE}: the engine watches the execution's cancellation signal (through
     * the bridge's child signal) and stops the program, and the tool returns a result rather than being abandoned
     * (§13.2).
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    @Override
    public ToolDescriptor describe(ToolOfferContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        final ExecuteCodePlan plan = ExecuteCodePlan.of(context, engines, TOOL_NAME, toolScope);
        if (!plan.isAvailable()) {
            return ToolDescriptor.from(this).offerable(false).build();
        }
        final CodeExecutionEngine engine = plan.engine().orElseThrow();
        final ToolGrade intrinsic = engine.intrinsicGrade();
        SideEffectLevel level = intrinsic.getSideEffectLevel();
        DataReach reach = intrinsic.getDataReach();
        for (ToolDescriptor tool : plan.callable()) {
            if (tool.getSideEffectLevel() == SideEffectLevel.MUTATING) {
                level = SideEffectLevel.MUTATING;
            }
            reach = reach.max(tool.getDataReach());
        }
        // The callable set holds no MUTATING ∧ DESTRUCTIVE tool (code-path rule 1), so only the engine itself can make
        // ExecuteCode destructive.
        final DestructiveBehavior destructive = intrinsic.getSideEffectLevel() == SideEffectLevel.MUTATING
                && intrinsic.getDestructiveBehavior() == DestructiveBehavior.DESTRUCTIVE
                        ? DestructiveBehavior.DESTRUCTIVE
                        : DestructiveBehavior.NON_DESTRUCTIVE;
        final ToolDefinition definition = ToolDefinition.of(TOOL_NAME,
                ExecuteCodeDescription.render(engine.language(), plan, limits), getDefinition().getInputSchema());
        final ToolDescriptor.Builder builder = ToolDescriptor.from(this).definition(definition).sideEffectLevel(level)
                .destructiveBehavior(destructive).dataReach(reach).approvalDelegated(intrinsic.isApprovalDelegated())
                .codeHostedTools(callableNames(plan));
        intrinsic.getApprovalKey().ifPresent(key -> builder.approvalKey(key)
                .approvalVia(TOOL_NAME + " (" + ExecuteCodeDescription.displayName(engine.language()) + ")"));
        return builder.build();
    }

    private static Set<String> callableNames(ExecuteCodePlan plan) {
        final Set<String> names = new LinkedHashSet<>();
        plan.callable().forEach(descriptor -> names.add(descriptor.getName()));
        return names;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        final String code;
        final Integer timeoutSeconds;
        try {
            code = input.getRequiredString("code");
            timeoutSeconds = input.getIntegerOrNull("timeout_seconds");
        } catch (IllegalArgumentException e) {
            log.warn("ExecuteCode: invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        }

        // The freeze (decision 13): the set computed here, from this call's offer context, is the program's set.
        final ExecuteCodePlan plan = ExecuteCodePlan.of(ToolOfferContext.of(context), engines, TOOL_NAME, toolScope);
        if (!plan.isAvailable()) {
            return ToolResult.error("ExecuteCode is not available here: " + plan.unavailableReason().orElse(""));
        }
        final CodeExecutionEngine engine = plan.engine().orElseThrow();
        final Optional<ToolCallDispatcher> dispatcher = context.get(ToolContextKeys.TOOL_CALL_DISPATCHER);
        if (dispatcher.isEmpty()) {
            return ToolResult.error("ExecuteCode refused: no tool call dispatcher is bound to this execution");
        }
        final ExecutionEnvironment environment = ExecutionEnvironmentAccess.of(context).orElse(null);
        final EngineAvailability probed;
        try {
            probed = engine.probe(environment);
        } catch (RuntimeException e) {
            log.error("ExecuteCode: the {} engine's probe failed", engine.language(), e);
            return ToolResult
                    .error("ExecuteCode: the " + engine.language() + " engine could not be checked: " + e.getMessage());
        }
        if (!probed.isAvailable()) {
            return ToolResult.error("ExecuteCode: the " + engine.language() + " engine cannot run in this "
                    + "environment: " + probed.getReason().orElse("unavailable"));
        }

        final String toolUseId = context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse("execute-code");
        final Duration wallClock = wallClockOf(timeoutSeconds, context);
        final CancellationSignal parentSignal = context.get(InterruptToolKeys.CANCELLATION_SIGNAL).orElse(null);
        final DefaultToolCallBridge bridge = new DefaultToolCallBridge(dispatcher.get(), context, toolUseId,
                engine.language(), plan.callable(), limits, parentSignal);
        final long started = System.nanoTime();
        CodeExecutionOutcome outcome;
        List<String> stillRunning = null;
        // Everything between the bridge's construction (a parent-signal registration, two executors) and its close is
        // guarded, so nothing — not even an Error from the engine — leaks them past this call.
        try {
            final List<ToolDefinition> definitions = new ArrayList<>();
            plan.callable().forEach(descriptor -> definitions.add(descriptor.getDefinition()));
            final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId(toolUseId).code(code)
                    .tools(definitions).limits(limits).wallClock(wallClock).cancellation(bridge.childSignal()).build();
            try {
                outcome = engine.execute(request, bridge, context);
            } catch (RuntimeException e) {
                log.error("ExecuteCode: the {} engine failed", engine.language(), e);
                outcome = CodeExecutionOutcome.builder().status(CodeExecutionStatus.SCRIPT_ERROR)
                        .errorMessage("the engine failed: " + e.getMessage()).build();
            }
            // A limit the bridge stopped the program for wins over the cancellation the engine saw.
            final Optional<String> limit = bridge.limitExceeded();
            if (limit.isPresent() && outcome.getStatus() != CodeExecutionStatus.COMPLETED) {
                outcome = CodeExecutionOutcome.builder().status(CodeExecutionStatus.LIMIT_EXCEEDED)
                        .stdout(outcome.getStdout()).logs(outcome.getLogs()).errorMessage(limit.get()).build();
            }
            final Duration remaining = wallClock.minus(Duration.ofNanos(System.nanoTime() - started));
            stillRunning = bridge.close(remaining.isNegative() ? Duration.ofMillis(1) : remaining);
        } finally {
            if (stillRunning == null) {
                bridge.close(Duration.ofMillis(1));
            }
        }
        final Duration total = Duration.ofNanos(System.nanoTime() - started);

        final List<NestedToolCall> calls = bridge.records();
        if (!outcome.getLogs().isEmpty() && log.isDebugEnabled()) {
            log.debug("ExecuteCode {} logs:\n{}", toolUseId, ModelText.forLog(outcome.getLogs()));
        }
        final ToolResult result;
        if (outcome.getStatus() == CodeExecutionStatus.COMPLETED) {
            result = ToolResult.success(ExecuteCodeObservation.success(outcome, calls, bridge.notices(),
                    bridge.hookFeedback(), stillRunning, total, limits));
        } else {
            if (outcome.getStatus() == CodeExecutionStatus.PROTOCOL_VIOLATION) {
                log.error("ExecuteCode {}: protocol violation: {}", toolUseId,
                        ModelText.forLog(outcome.getErrorMessage().orElse("")));
            } else {
                log.info("ExecuteCode {} ended {}: {}", toolUseId, outcome.getStatus(),
                        ModelText.forLog(outcome.getErrorMessage().orElse("")));
            }
            final ToolResult error = ToolResult.error(ExecuteCodeObservation.failure(outcome, calls, bridge.notices(),
                    bridge.hookFeedback(), stillRunning, total, limits));
            result = outcome.getStatus().isRetryable() ? error : error.notRetryable();
        }
        return result.withRenderPayload(renderPayload(code, engine.language(), outcome.getStatus(), calls));
    }

    private Duration wallClockOf(Integer timeoutSeconds, ToolContext context) {
        Duration wallClock = limits.wallClockFor(timeoutSeconds);
        final Optional<ExecutionDeadline> deadline = context.get(ToolContextKeys.EXECUTION_DEADLINE);
        if (deadline.isPresent()) {
            final Duration remaining = deadline.get().remaining();
            if (remaining.compareTo(wallClock) < 0) {
                wallClock = remaining.isZero() || remaining.isNegative() ? Duration.ofMillis(1) : remaining;
            }
        }
        return wallClock;
    }

    private static Map<String, Object> renderPayload(String code, String language, CodeExecutionStatus status,
            List<NestedToolCall> calls) {
        final List<Map<String, Object>> rendered = new ArrayList<>(calls.size());
        calls.forEach(call -> rendered.add(call.toPayload()));
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", language);
        payload.put("code", code);
        payload.put("status", status.name());
        payload.put("calls", rendered);
        return payload;
    }
}
