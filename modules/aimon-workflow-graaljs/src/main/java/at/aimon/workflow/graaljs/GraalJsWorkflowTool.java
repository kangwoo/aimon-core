package at.aimon.workflow.graaljs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.CallerAllowedTools;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.HookRegistryAccess;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * LLM-facing consumer that runs a caller-supplied <b>JavaScript</b> workflow script on the shared runner
 * machinery — the scripted mirror of {@code WorkflowTool} (whose workflow is a fixed Java script).
 *
 * <p>
 * Foreground runs build a per-call, budget-bearing runner from the invoking execution's agent runtime
 * (agentRuntimeId, principal, execution attributes, parent LLM metadata, cancellation signal) and block for the
 * result. Background runs reuse the injected app-scoped runner and its own base launch context (they do <b>not</b>
 * inherit the invoking execution's context or signal), returning a run id trackable via {@code /runs}. The tool
 * never throws: guest errors and cancellation ({@link JsScriptException}) and any other failure become
 * {@link ToolResult#error}.
 */
public final class GraalJsWorkflowTool extends AbstractTool {

    /** The tool name exposed to the LLM. */
    public static final String TOOL_NAME = "WorkflowJs";

    private static final Logger log = LoggerFactory.getLogger(GraalJsWorkflowTool.class);

    private static final String MODE_FOREGROUND = "foreground";
    private static final String MODE_BACKGROUND = "background";
    private static final String RUN_SCRIPT_NAME = "graaljs";

    private final LlmModel defaultModel;
    private final SubagentRegistry subagentRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final SubagentExecutionManager subagentExecutionManager;
    private final List<ToolContextEnricher> toolContextEnrichers;

    private final GraalJsEngineHolder engines;
    private final JsSandboxConfig sandbox;
    private final SubagentResolver subagentResolver;
    private final WorkflowRunner backgroundRunner;

    private GraalJsWorkflowTool(Builder builder) {
        super(TOOL_NAME,
                "Run a JavaScript workflow script that fans work out to sub-agents on the same verified "
                        + "primitives (agent/parallel/pipeline/phase/log). The script author encodes the control flow "
                        + "in JS; the LLM only runs inside each sub-agent. Use for multi-step, fan-out, or "
                        + "pipeline workflows expressed as a literal script.",
                createInputSchema());
        this.defaultModel = Objects.requireNonNull(builder.defaultModel, "defaultModel must not be null");
        this.subagentRegistry = Objects.requireNonNull(builder.subagentRegistry, "subagentRegistry must not be null");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry must not be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry must not be null");
        this.subagentExecutionManager = Objects.requireNonNull(builder.subagentExecutionManager,
                "subagentExecutionManager must not be null");
        this.toolContextEnrichers = builder.toolContextEnrichers != null
                ? List.copyOf(builder.toolContextEnrichers)
                : List.of();
        this.engines = Objects.requireNonNull(builder.engines, "engines must not be null");
        this.sandbox = builder.sandbox != null ? builder.sandbox : JsSandboxConfig.defaults();
        if (builder.subagentResolver != null && !builder.scriptAttributeKeys.isEmpty()) {
            throw new IllegalStateException("scriptAttributeKeys configures the default subagentResolver and has no "
                    + "effect on a custom one; set one or the other");
        }
        this.subagentResolver = builder.subagentResolver != null
                ? builder.subagentResolver
                : SubagentResolver.inline(subagentRegistry, builder.scriptAttributeKeys);
        this.backgroundRunner = builder.backgroundRunner; // nullable
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("script",
                Map.of("type", "string", "description",
                        "The JavaScript workflow source. Call agent({agentType, goal, schema?}), "
                                + "parallel([descriptors]), pipeline(items, ...stages), phase(title), log(msg); "
                                + "top-level return/await are legal. Read inputs from the read-only 'args' object."),
                "args",
                Map.of("type", "object", "description", "Optional read-only inputs exposed to the script as 'args'."),
                "max_agents",
                Map.of("type", "number", "description",
                        "Optional agent-count budget ceiling for the run (foreground only)."),
                "mode",
                Map.of("type", "string", "enum", List.of(MODE_FOREGROUND, MODE_BACKGROUND), "description",
                        "Run mode: 'foreground' (default) blocks and returns the result; 'background' returns "
                                + "immediately with a run id trackable via the /runs command.")),
                "required", List.of("script"));
    }

    /**
     * Declares {@link InterruptBehavior#COOPERATIVE}: a foreground run observes the invoking execution's
     * {@link CancellationSignal} through the per-run coordinator cascade — the watchdog force-closes the JS context
     * and the core signal-polling join frees the fan-out — and returns a {@link ToolResult} instead of relying on
     * thread interrupts.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final String source = input.getRequiredString("script");
            if (source.isBlank()) {
                return ToolResult.error("script cannot be blank");
            }

            final String mode = input.getString("mode", MODE_FOREGROUND).trim();
            if (!mode.isEmpty() && !MODE_FOREGROUND.equalsIgnoreCase(mode) && !MODE_BACKGROUND.equalsIgnoreCase(mode)) {
                return ToolResult.error("Invalid mode '" + mode + "'. Use 'foreground' (default) or 'background'.");
            }

            final Map<String, Object> args = readArgs(input);

            if (MODE_BACKGROUND.equalsIgnoreCase(mode)) {
                return runBackground(source, args, context);
            }
            return runForeground(source, args, input, context);

        } catch (JsScriptException e) {
            // Guest error / cancellation / marshalling failure — never propagate org.graalvm types across the boundary.
            log.warn("GraalJS workflow script failed: {}", e.getMessage());
            return ToolResult.error("JS workflow failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("GraalJS workflow failed: {}", e.getMessage(), e);
            return ToolResult.error("JS workflow failed: " + e.getMessage());
        }
    }

    private ToolResult runBackground(String source, Map<String, Object> args, ToolContext context) {
        if (backgroundRunner == null) {
            return ToolResult
                    .error("Background mode is not available: no workflow runner is configured for this agent.");
        }
        // The run below dispatches against the runtime's registry, so the hooks of a skill this call runs inside do
        // not follow it. A guard that would be silently off is refused; a hook that only observes is not (mirrors
        // WorkflowTool).
        final List<String> guardSkills = HookRegistryAccess.activeSkillGuards(context);
        if (!guardSkills.isEmpty()) {
            return ToolResult.error(HookRegistryAccess.backgroundRefusal(guardSkills));
        }
        final List<String> hookSkills = HookRegistryAccess.activeSkillHooks(context);
        if (!hookSkills.isEmpty()) {
            log.warn("WorkflowJs (background): the hooks of skill(s) {} do not fire for this run's subagents",
                    hookSkills);
        }
        // Fire-and-forget on the shared runner's own base launch context and bootstrap budget: a background run does
        // NOT inherit the invoking execution's context id, principal, or cancellation signal (mirrors WorkflowTool).
        // The run id is derived from the full request so an identical in-flight request is joined idempotently.
        final RunId runId = RunId.from(RUN_SCRIPT_NAME, discriminator(source, args));
        final GraalJsWorkflowScript script = new GraalJsWorkflowScript(source, args, sandbox, engines, subagentResolver,
                NoopCancellationSignal.INSTANCE);
        backgroundRunner.runInBackground(script, runId);
        log.debug("WorkflowJs (background): runId='{}'", runId.value());
        return ToolResult.success("Started background JS workflow run '" + runId.value()
                + "'. Track it with the /runs command (or /runs status " + runId.value() + ").");
    }

    private ToolResult runForeground(String source, Map<String, Object> args, ToolInput input, ToolContext context) {
        final AgentRuntimeId agentRuntimeId = context.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null);
        if (agentRuntimeId == null) {
            return ToolResult.error(
                    "Agent runtime ID not found in tool context; " + TOOL_NAME + " must run within an agent runtime.");
        }

        final CancellationSignal parentSignal = context.get(InterruptToolKeys.CANCELLATION_SIGNAL)
                .orElse(NoopCancellationSignal.INSTANCE);
        // Per-run coordinator (RunControl pattern): the invoking execution's signal cascades in, and the wall-clock
        // deadline trips it too — so a JS deadline cancels this run's in-flight leaves cooperatively without
        // aborting the whole invoking execution.
        final InterruptCoordinator runCoordinator = new DefaultInterruptCoordinator();
        final CancellationSignal runSignal = runCoordinator.getSignal();
        final CancellationSignal.Registration cascade = parentSignal
                .onCancel(() -> runCoordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED));
        try {
            final SubagentLaunchContext launchContext = buildLaunchContext(agentRuntimeId, context, runSignal);
            final GraalJsWorkflowScript script = new GraalJsWorkflowScript(source, args, sandbox, engines,
                    subagentResolver, runSignal,
                    () -> runCoordinator.requestInterrupt(InterruptReason.BUDGET_EXCEEDED));

            final WorkflowRunnerOptions options = buildOptions(input);
            final RunId runId = RunId.from(RUN_SCRIPT_NAME, discriminator(source, args));
            log.debug("WorkflowJs (foreground): runId='{}'", runId.value());
            // The per-call foreground runner owns a lazily-created fan-out pool; close it once run() returns so a
            // long-lived process does not leak a worker pool per invocation.
            try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, launchContext, options)) {
                return ToolResult.success(runner.run(script, runId));
            }
        } finally {
            cascade.remove();
            runCoordinator.close();
        }
    }

    private WorkflowRunnerOptions buildOptions(ToolInput input) {
        final WorkflowRunnerOptions.Builder options = WorkflowRunnerOptions.builder();
        if (input.has("max_agents")) {
            final int maxAgents = input.getInteger("max_agents", 0);
            if (maxAgents > 0) {
                options.budget(WorkflowBudget.ofAgents(maxAgents));
            }
        }
        return options.build();
    }

    private SubagentLaunchContext buildLaunchContext(AgentRuntimeId agentRuntimeId, ToolContext context,
            CancellationSignal parentSignal) {
        final Map<String, Object> executionAttributes = context.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)
                .orElse(Map.of());
        final LlmCallMetadata parentMetadata = context.get(ToolContextKeys.LLM_CALL_METADATA_KEY)
                .orElse(LlmCallMetadata.empty());
        final Principal principal = context.get(ToolContextKeys.PRINCIPAL).orElse(null);

        return SubagentLaunchContext.builder().agentRuntimeId(agentRuntimeId).subagentRegistry(subagentRegistry)
                .toolRegistry(toolRegistry)
                // The caller's registry first: inside a forked skill it carries the skill's hooks, and the workflow's
                // subagents must stay under them.
                .hookRegistry(HookRegistryAccess.of(context).orElse(hookRegistry)).defaultModel(defaultModel)
                .executionAttributes(executionAttributes).parentLlmCallMetadata(parentMetadata)
                .cancellationSignal(parentSignal).principal(principal).toolContextEnrichers(toolContextEnrichers)
                .callerAllowedTools(CallerAllowedTools.of(context))
                .invokingSessionId(InvokingSessionAccess.idToPropagate(context).orElse(null))
                // The fork resolves its own environment from the spawning runtime's provider, with this execution's
                // environment as its parent (execution-environment design §5.2).
                .executionEnvironment(ExecutionEnvironmentAccess.of(context).orElse(null))
                .executionEnvironmentProvider(ExecutionEnvironmentAccess.providerOf(context).orElse(null)).build();
    }

    private static Map<String, Object> readArgs(ToolInput input) {
        final Map<String, Object> args = new LinkedHashMap<>();
        if (input.get("args") instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                args.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return args;
    }

    /** 64-bit SHA-256 prefix of (source + args) so distinct requests get distinct run ids, identical ones coalesce. */
    private static String discriminator(String source, Map<String, Object> args) {
        final String canonical = source + '\n' + args;
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.hashCode()).toLowerCase(Locale.ROOT);
        }
    }

    /** Builder for the tool's launch-context-building and execution dependencies. */
    public static final class Builder {

        private LlmModel defaultModel;
        private SubagentRegistry subagentRegistry;
        private ToolRegistry toolRegistry;
        private HookRegistry hookRegistry;
        private SubagentExecutionManager subagentExecutionManager;
        private List<ToolContextEnricher> toolContextEnrichers;
        private GraalJsEngineHolder engines;
        private JsSandboxConfig sandbox;
        private SubagentResolver subagentResolver;
        private List<String> scriptAttributeKeys = List.of();
        private WorkflowRunner backgroundRunner;

        private Builder() {
        }

        public Builder defaultModel(LlmModel defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder subagentRegistry(SubagentRegistry subagentRegistry) {
            this.subagentRegistry = subagentRegistry;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        public Builder subagentExecutionManager(SubagentExecutionManager subagentExecutionManager) {
            this.subagentExecutionManager = subagentExecutionManager;
            return this;
        }

        public Builder toolContextEnrichers(List<ToolContextEnricher> toolContextEnrichers) {
            this.toolContextEnrichers = toolContextEnrichers;
            return this;
        }

        public Builder engines(GraalJsEngineHolder engines) {
            this.engines = engines;
            return this;
        }

        /** Optional sandbox config; defaults to {@link JsSandboxConfig#defaults()}. */
        public Builder sandbox(JsSandboxConfig sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /**
         * Optional resolver; defaults to {@link SubagentResolver#inline(SubagentRegistry, Collection)} over
         * this tool's {@code subagentRegistry} and {@link #scriptAttributeKeys(Collection)}, so a step whose
         * {@code agentType} names a registered subagent carries that subagent's attributes. A custom resolver decides
         * for itself what a script may set; combining it with {@code scriptAttributeKeys} fails {@link #build()}.
         */
        public Builder subagentResolver(SubagentResolver subagentResolver) {
            this.subagentResolver = subagentResolver;
            return this;
        }

        /**
         * The attribute keys a script may set on a step, as flattened dotted keys ({@code sandbox.profile}). Empty by
         * default: a script that gives a step {@code attributes} fails with a script error naming the key.
         *
         * <p>
         * The script is written by the model, and attributes are what an execution environment provider reads to place
         * a step, so which keys a script may set is the operator's decision. A registered definition's own keys stay
         * pinned whether or not they are listed here — the list only admits keys no registered definition sets for
         * that step, which includes every key of a step whose {@code agentType} is unregistered or absent. List only
         * keys whose every value is safe for a model to choose; a key such as {@code sandbox.slot} belongs in a
         * registered definition instead.
         *
         * @param scriptAttributeKeys
         *            the allowed keys (must not be null, nor contain a null or blank key)
         * @return this builder
         * @throws IllegalArgumentException
         *             if a key is null or blank
         */
        public Builder scriptAttributeKeys(Collection<String> scriptAttributeKeys) {
            Objects.requireNonNull(scriptAttributeKeys, "scriptAttributeKeys must not be null");
            for (final String key : scriptAttributeKeys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("scriptAttributeKeys must not contain a null or blank key");
                }
            }
            this.scriptAttributeKeys = List.copyOf(scriptAttributeKeys);
            return this;
        }

        /** Optional app-scoped runner enabling background mode; when null, background mode returns an error. */
        public Builder backgroundRunner(WorkflowRunner backgroundRunner) {
            this.backgroundRunner = backgroundRunner;
            return this;
        }

        /**
         * Builds the tool.
         *
         * @return the tool
         * @throws IllegalStateException
         *             if both a custom {@code subagentResolver} and {@code scriptAttributeKeys} were set
         */
        public GraalJsWorkflowTool build() {
            return new GraalJsWorkflowTool(this);
        }
    }
}
