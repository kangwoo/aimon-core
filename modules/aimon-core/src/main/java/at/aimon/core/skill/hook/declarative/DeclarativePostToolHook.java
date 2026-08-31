package at.aimon.core.skill.hook.declarative;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PostToolHook} (AIMON extension, SK-13) that fires the configured non-blocking {@link HookAction} after a
 * matching tool execution completes.
 *
 * <p>
 * postTool hooks are non-blocking by interface contract. {@link DenyAction} is rejected at construction; HTTP / MCP
 * responses that carry a {@code decision: "deny"} are downgraded to {@link HookResult#success()} with a WARN log
 * &mdash; this class never returns {@link HookResult#block(String)}.
 *
 * <p>
 * Action semantics:
 * <ul>
 * <li>{@link ShellAction} → fire-and-forget execution via the supplied {@link ShellActionExecutor}.
 * <li>{@link HttpAction} → request issued via {@link HttpActionExecutor}; non-deny decisions can carry feedback or an
 * {@code updatedInput} map.
 * <li>{@link McpToolAction} → MCP tool call via {@link McpActionExecutor}; same semantics as HTTP.
 * </ul>
 *
 * <p>
 * If an action type is supplied without the matching executor (e.g. {@link HttpAction} but {@code httpExecutor} is
 * {@code null}) the hook logs at WARN and returns {@link HookResult#success()} &mdash; declarative hooks remain
 * fail-soft.
 *
 * <p>
 * Non-matching tool invocations short-circuit to {@link HookResult#success()} without invoking the action.
 *
 * <p>
 * Immutable; thread-safe as long as the underlying executors are.
 */
public final class DeclarativePostToolHook implements PostToolHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "postTool";

    private static final Logger log = LoggerFactory.getLogger(DeclarativePostToolHook.class);

    private final String skillName;
    private final String hookId;
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
     * The HTTP and MCP executors default to {@code null} and the env snapshot defaults to {@code Map.of()}. Callers
     * that need {@code ${env.X}} substitution in HTTP/MCP actions must use the 7-arg constructor and pass an explicit
     * env snapshot (typically {@code System.getenv()} at bootstrap time).
     *
     * @param skillName
     *            the skill name (must not be null)
     * @param predicate
     *            the tool predicate (must not be null)
     * @param action
     *            the non-blocking action to fire (must not be null; must not be a {@link DenyAction})
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     */
    public DeclarativePostToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
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
     *            the non-blocking action to fire (must not be null; must not be a {@link DenyAction})
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     * @param httpExecutor
     *            the executor for HTTP actions (may be null; absence makes HTTP actions degrade to success)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; absence makes MCP actions degrade to success)
     * @param processEnv
     *            process env snapshot used to populate the env whitelist for HTTP / MCP actions (must not be null)
     */
    public DeclarativePostToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
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
     *            the non-blocking action to fire (must not be null; must not be a {@link DenyAction})
     * @param shellExecutor
     *            the executor for shell actions (must not be null)
     * @param httpExecutor
     *            the executor for HTTP actions (may be null; absence makes HTTP actions degrade to success)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; absence makes MCP actions degrade to success)
     * @param processEnv
     *            process env snapshot used to populate the env whitelist for HTTP / MCP actions (must not be null)
     * @param options
     *            config-derived options: hook-id discriminator (must not be null). {@code postTool} is not a
     *            rewakeable event, so any {@code asyncRewake} spec carried here is ignored — the applier rejects it
     *            at registration time.
     */
    // Declarative hooks bind one constructor parameter per config field, so they cannot be grouped.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeclarativePostToolHook(String skillName, ToolInputPredicate predicate, HookAction action,
            ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor, McpActionExecutor mcpExecutor,
            Map<String, String> processEnv, DeclarativeHookOptions options) {
        this.skillName = Objects.requireNonNull(skillName, "Skill name cannot be null");
        Objects.requireNonNull(options, "Options cannot be null");
        this.hookId = DeclarativeHookId.of(DeclarativePostToolHook.class, this.skillName,
                options.getHookIdDiscriminator());
        this.predicate = Objects.requireNonNull(predicate, "Predicate cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        if (action instanceof DenyAction) {
            throw new IllegalArgumentException("postTool hooks cannot use deny actions; deny is preTool-only");
        }
        this.action = action;
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
    public HookResult execute(PostToolContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final String toolName = context.getToolUse().getName();
        final ToolInput toolInput = ToolInput.of(context.getToolUse().getInput());
        if (!predicate.test(toolName, toolInput)) {
            return HookResult.success();
        }

        if (action instanceof ShellAction shell) {
            final Map<String, String> env = buildShellEnv(context, toolName);
            // The outcome is deliberately ignored: postTool cannot block, so an exit code carries no decision here.
            shellExecutor.run(shell, env, ShellHookPayload.render(env, toolInput.toMap()));
            return HookResult.success();
        }
        if (action instanceof HttpAction http) {
            if (httpExecutor == null) {
                log.warn("Skill '{}' postTool hook for tool '{}' carries an HttpAction but no HttpActionExecutor is"
                        + " wired; degrading to success", skillName, toolName);
                return HookResult.success();
            }
            return downgradeBlock(
                    httpExecutor.run(http, toolInput, buildContextAttributes(context, toolName), processEnvSnapshot),
                    toolName);
        }
        if (action instanceof McpToolAction mcp) {
            if (mcpExecutor == null) {
                log.warn("Skill '{}' postTool hook for tool '{}' carries an McpToolAction but no McpActionExecutor is"
                        + " wired; degrading to success", skillName, toolName);
                return HookResult.success();
            }
            return downgradeBlock(mcpExecutor.run(mcp, toolInput, buildContextAttributes(context, toolName)), toolName);
        }
        // Unreachable: HookAction is sealed; DenyAction is rejected in the constructor.
        throw new IllegalStateException("Unknown HookAction subtype for postTool: " + action.getClass());
    }

    private HookResult downgradeBlock(HookResult result, String toolName) {
        if (result.isBlocked()) {
            log.warn("Skill '{}' postTool hook for tool '{}' returned a deny decision; downgrading to success because"
                    + " postTool cannot block (feedback: {})", skillName, toolName, result.getFeedback());
            return HookResult.success();
        }
        return result;
    }

    private Map<String, String> buildShellEnv(PostToolContext context, String toolName) {
        final Map<String, String> env = new LinkedHashMap<>();
        env.put(SkillHookEnv.AIMON_HOOK_EVENT, "postTool");
        env.put(SkillHookEnv.AIMON_SKILL_NAME, skillName);
        env.put(SkillHookEnv.AIMON_INVOKER_NAME, context.getInvokerName());
        env.put(SkillHookEnv.AIMON_INVOKER_TYPE, context.getInvokerType().name());
        env.put(SkillHookEnv.AIMON_TOOL_NAME, toolName);
        env.put(SkillHookEnv.AIMON_ITERATION, Integer.toString(context.getIterationCount()));
        env.put(SkillHookEnv.AIMON_TOOL_RESULT_STATUS,
                context.getCurrentToolUseResult().isError() ? "error" : "success");
        return env;
    }

    private Map<String, String> buildContextAttributes(PostToolContext context, String toolName) {
        final Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("event", "postTool");
        attrs.put("skill_name", skillName);
        attrs.put("invoker_name", context.getInvokerName());
        attrs.put("invoker_type", context.getInvokerType().name());
        attrs.put("tool_name", toolName);
        attrs.put("iteration", Integer.toString(context.getIterationCount()));
        attrs.put("tool_result_status", context.getCurrentToolUseResult().isError() ? "error" : "success");
        return attrs;
    }
}
