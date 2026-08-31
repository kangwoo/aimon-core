package at.aimon.core.skill.hook.declarative;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PreToolHook} that fires the configured {@link HookAction} when a tool invocation matches the supplied
 * {@link ToolInputPredicate}.
 *
 * <p>
 * Action semantics:
 * <ul>
 * <li>{@link DenyAction} → {@link HookResult#block(String)} with the configured reason.
 * <li>{@link ShellAction} → executed via the supplied {@link ShellActionExecutor}, with the firing context as a JSON
 * document on standard input (see {@code ShellHookPayload}). Exit code
 * {@link ShellHookOutcome#DENY_EXIT_CODE} vetoes the tool and feeds stderr back to the model as the reason; any other
 * exit code allows it (Claude Code parity).
 * <li>{@link HttpAction} → request issued via {@link HttpActionExecutor}; the JSON response can carry an
 * {@code allow}/{@code deny}/{@code defer} decision and an optional {@code updatedInput}.
 * <li>{@link McpToolAction} → MCP tool call via {@link McpActionExecutor}; result content can carry the same
 * decision contract.
 * </ul>
 *
 * <p>
 * If an action type is supplied without the matching executor (e.g. {@link HttpAction} but {@code httpExecutor} is
 * {@code null}) the hook logs at WARN and returns {@link HookResult#success()} — declarative hooks remain fail-soft.
 *
 * <p>
 * Non-matching tool invocations short-circuit to {@link HookResult#success()} without invoking the action.
 *
 * <p>
 * Immutable; thread-safe as long as the underlying executors are.
 */
public final class DeclarativePreToolHook implements PreToolHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "preTool";

    private static final Logger log = LoggerFactory.getLogger(DeclarativePreToolHook.class);

    private final String skillName;
    private final String hookId;
    private final RewakeSpec rewakeSpec;
    private final ToolInputPredicate predicate;
    private final HookAction action;
    private final ShellActionExecutor shellExecutor;
    private final HttpActionExecutor httpExecutor;
    private final McpActionExecutor mcpExecutor;
    private final Map<String, String> processEnvSnapshot;

    /**
     * Creates a hook with shell-only action support (legacy SK-13 wiring).
     *
     * <p>
     * The HTTP and MCP executors default to {@code null} (HTTP/MCP actions degrade to success) and the env snapshot
     * defaults to {@code Map.of()}. Callers that need {@code ${env.X}} substitution in HTTP/MCP actions must use the
     * 7-arg constructor and pass an explicit env snapshot (typically {@code System.getenv()} at bootstrap time).
     *
     * @param skillName
     *            the skill name (must not be null)
     * @param predicate
     *            the tool predicate (must not be null)
     * @param action
     *            the action to fire (must not be null; HTTP / MCP actions degrade to success without their executors)
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     */
    public DeclarativePreToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
            ShellActionExecutor shellExecutor) {
        this(skillName, predicate, action, shellExecutor, null, null, Map.of());
    }

    /**
     * Creates a hook with full action support.
     *
     * @param skillName
     *            the skill name (must not be null)
     * @param predicate
     *            the tool predicate (must not be null)
     * @param action
     *            the action to fire (must not be null)
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     * @param httpExecutor
     *            the executor for HTTP actions (may be null; absence makes HTTP actions degrade to success)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; absence makes MCP actions degrade to success)
     * @param processEnv
     *            process env snapshot used to populate the env whitelist for HTTP / MCP actions (must not be null)
     */
    public DeclarativePreToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
            ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor, McpActionExecutor mcpExecutor,
            Map<String, String> processEnv) {
        this(skillName, predicate, action, shellExecutor, httpExecutor, mcpExecutor, processEnv,
                DeclarativeHookOptions.none());
    }

    /**
     * Creates a hook with an explicit {@linkplain #getHookId() hook id} discriminator.
     *
     * <p>
     * Callers that register several hooks of this class — {@code hooks.json} entries and multi-entry skill frontmatter
     * — must pass a discriminator, otherwise all of them share one id and async-rewake routing / reload cancellation
     * cannot tell them apart. See {@link DeclarativeHookId}.
     *
     * @param skillName
     *            the skill name (must not be null)
     * @param predicate
     *            the tool predicate (must not be null)
     * @param action
     *            the action to fire (must not be null)
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     * @param httpExecutor
     *            the executor for HTTP actions (may be null; absence makes HTTP actions degrade to success)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; absence makes MCP actions degrade to success)
     * @param processEnv
     *            process env snapshot used to populate the env whitelist for HTTP / MCP actions (must not be null)
     * @param options
     *            config-derived options: hook-id discriminator and {@code asyncRewake} spec (must not be null)
     */
    // Declarative hooks bind one constructor parameter per config field, so they cannot be grouped.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeclarativePreToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
            ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor, McpActionExecutor mcpExecutor,
            Map<String, String> processEnv, DeclarativeHookOptions options) {
        this.skillName = Objects.requireNonNull(skillName, "Skill name cannot be null");
        Objects.requireNonNull(options, "Options cannot be null");
        this.hookId = DeclarativeHookId.of(DeclarativePreToolHook.class, this.skillName,
                options.getHookIdDiscriminator());
        this.rewakeSpec = options.getRewakeSpec().orElse(null);
        this.predicate = Objects.requireNonNull(predicate, "Predicate cannot be null");
        this.action = Objects.requireNonNull(action, "Action cannot be null");
        this.shellExecutor = Objects.requireNonNull(shellExecutor, "Shell executor cannot be null");
        this.httpExecutor = httpExecutor;
        this.mcpExecutor = mcpExecutor;
        this.processEnvSnapshot = Map.copyOf(Objects.requireNonNull(processEnv, "processEnv cannot be null"));
    }

    @Override
    public String getHookId() {
        return hookId;
    }

    @Override
    public Optional<Duration> getExecutionBudget() {
        return action.getExecutionBudget();
    }

    @Override
    public HookResult execute(PreToolContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final String toolName = context.getCurrentToolUse().getName();
        final ToolInput toolInput = context.currentInput();
        if (!predicate.test(toolName, toolInput)) {
            return HookResult.success();
        }

        if (action instanceof DenyAction deny) {
            log.info("Skill '{}' preTool hook denied tool '{}': {}", skillName, toolName, deny.getReason());
            return withRewake(HookResult.block(deny.getReason()));
        }
        if (action instanceof ShellAction shell) {
            final Map<String, String> env = buildShellEnv(context, toolName);
            final ShellHookOutcome outcome = shellExecutor.run(shell, env,
                    ShellHookPayload.render(env, toolInput.toMap()));
            if (outcome.isDenied()) {
                log.info("Skill '{}' preTool shell hook vetoed tool '{}' (exit {}): {}", skillName, toolName,
                        outcome.getExitCode(), outcome.denyReason());
                return withRewake(HookResult.block(outcome.denyReason()));
            }
            if (outcome.isObserved() && outcome.getExitCode() != 0) {
                // Neither success nor the deny code: the script is broken. Treated as allow so a malfunctioning
                // audit hook cannot silently start blocking every tool call.
                log.warn(
                        "Skill '{}' preTool shell hook for tool '{}' exited with {} — not {} (deny), so the tool is"
                                + " allowed to run",
                        skillName, toolName, outcome.getExitCode(), ShellHookOutcome.DENY_EXIT_CODE);
            }
            return withRewake(HookResult.success());
        }
        if (action instanceof HttpAction http) {
            if (httpExecutor == null) {
                log.warn("Skill '{}' preTool hook for tool '{}' carries an HttpAction but no HttpActionExecutor is"
                        + " wired; degrading to success", skillName, toolName);
                return withRewake(HookResult.success());
            }
            return withRewake(
                    httpExecutor.run(http, toolInput, buildContextAttributes(context, toolName), processEnvSnapshot));
        }
        if (action instanceof McpToolAction mcp) {
            if (mcpExecutor == null) {
                log.warn("Skill '{}' preTool hook for tool '{}' carries an McpToolAction but no McpActionExecutor is"
                        + " wired; degrading to success", skillName, toolName);
                return withRewake(HookResult.success());
            }
            return withRewake(mcpExecutor.run(mcp, toolInput, buildContextAttributes(context, toolName)));
        }
        // Unreachable: HookAction is sealed and exhaustively handled above.
        throw new IllegalStateException("Unknown HookAction subtype: " + action.getClass());
    }

    /**
     * Re-attaches the configured {@code asyncRewake} spec to a result produced on a firing path.
     *
     * <p>
     * Results produced on the non-matching short-circuit never pass through here, so a hook that did not fire
     * schedules nothing. See {@link DeclarativeRewake} for why re-attaching does not loop forever.
     */
    private HookResult withRewake(HookResult result) {
        return DeclarativeRewake.attach(result, rewakeSpec);
    }

    private Map<String, String> buildShellEnv(PreToolContext context, String toolName) {
        final Map<String, String> env = new LinkedHashMap<>();
        env.put(SkillHookEnv.AIMON_HOOK_EVENT, "preTool");
        env.put(SkillHookEnv.AIMON_SKILL_NAME, skillName);
        env.put(SkillHookEnv.AIMON_INVOKER_NAME, context.getInvokerName());
        env.put(SkillHookEnv.AIMON_INVOKER_TYPE, context.getInvokerType().name());
        env.put(SkillHookEnv.AIMON_TOOL_NAME, toolName);
        env.put(SkillHookEnv.AIMON_ITERATION, Integer.toString(context.getIterationCount()));
        return env;
    }

    private Map<String, String> buildContextAttributes(PreToolContext context, String toolName) {
        final Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("event", "preTool");
        attrs.put("skill_name", skillName);
        attrs.put("invoker_name", context.getInvokerName());
        attrs.put("invoker_type", context.getInvokerType().name());
        attrs.put("tool_name", toolName);
        attrs.put("iteration", Integer.toString(context.getIterationCount()));
        return attrs;
    }
}
