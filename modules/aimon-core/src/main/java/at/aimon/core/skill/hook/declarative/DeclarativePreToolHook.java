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
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
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
 * <li>{@link DenyAction} → {@link HookResult#block(String)} with the configured reason. {@code failOpen} is not read
 * for it: a deny always has its verdict, so the flag could only open the cases where the hook itself was not run or
 * its matcher threw, and those block.
 * <li>{@link ShellAction} → executed via the supplied {@link ShellActionExecutor}, with the firing context as a JSON
 * document on standard input (see {@code ShellHookPayload}). Exit code
 * {@link ShellHookOutcome#DENY_EXIT_CODE} vetoes the tool and feeds stderr back to the model as the reason; any other
 * exit code allows it (Claude Code parity), except the two the shell reports for a command it could not start. A
 * command that gave <em>no</em> answer — no execution environment, a timeout, a shell failure, a skill directory
 * that could not be staged for {@code AIMON_SKILL_DIR} (see {@link SkillHookDirectory}), or exit
 * {@value ShellHookOutcome#NOT_EXECUTABLE_EXIT_CODE} / {@value ShellHookOutcome#NOT_FOUND_EXIT_CODE} (not
 * executable / not found) — blocks the tool as well (fail-closed), unless the hook declared {@code failOpen}; see
 * {@link ShellHookVerdicts}.
 * <li>{@link HttpAction} → request issued via {@link HttpActionExecutor}; the JSON response can carry an
 * {@code allow}/{@code deny}/{@code defer} decision &mdash; or the Claude Code spelling of one, {@code ask} included,
 * see {@link DecisionDocument} &mdash; and an optional {@code updatedInput}.
 * <li>{@link McpToolAction} → MCP tool call via {@link McpActionExecutor}; result content can carry the same
 * decision contract.
 * </ul>
 *
 * <p>
 * An {@code http} or {@code mcp} call that produced <em>no verdict</em> blocks the tool too, by the rule the shell
 * action follows: the executor is not wired (e.g. {@link HttpAction} but {@code httpExecutor} is {@code null}), the
 * endpoint could not be reached or timed out, it answered with a non-2xx status, the MCP server is not connected or
 * its tool reported an error, or the answer cannot be read as a decision. A hook that only observes declares
 * {@code failOpen} and then lets the call through with a WARN. See {@link ActionCallOutcome} for the line between a
 * verdict and no verdict.
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
    private final boolean failOpen;
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
     * The HTTP and MCP executors default to {@code null} (an HTTP/MCP action then has no verdict and blocks, see the
     * class javadoc) and the env snapshot
     * defaults to {@code Map.of()}. Callers that need {@code ${env.X}} substitution in HTTP/MCP actions must use the
     * 7-arg constructor and pass an explicit env snapshot (typically {@code System.getenv()} at bootstrap time).
     *
     * @param skillName
     *            the skill name (must not be null)
     * @param predicate
     *            the tool predicate (must not be null)
     * @param action
     *            the action to fire (must not be null; HTTP / MCP actions block without their executors unless the hook
     *            is failOpen)
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
     *            the executor for HTTP actions (may be null; an HTTP action then has no verdict and blocks unless the
     *            hook declared failOpen)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; an MCP action then has no verdict and blocks unless the
     *            hook declared failOpen)
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
     *            the executor for HTTP actions (may be null; an HTTP action then has no verdict and blocks unless the
     *            hook declared failOpen)
     * @param mcpExecutor
     *            the executor for MCP actions (may be null; an MCP action then has no verdict and blocks unless the
     *            hook declared failOpen)
     * @param processEnv
     *            process env snapshot used to populate the env whitelist for HTTP / MCP actions (must not be null)
     * @param options
     *            config-derived options: hook-id discriminator, {@code failOpen} (ignored when {@code action} is a
     *            {@link DenyAction}) and {@code asyncRewake} spec (must not be null)
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
        // Decided here as well as by the two config front-ends, so a hook built in code cannot open a deny either.
        this.failOpen = options.isFailOpen() && !(this.action instanceof DenyAction);
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

    /**
     * A guard cut off by the executor's outer net blocks: this hook declares {@link TimeoutBehavior#FAIL_CLOSED}, so
     * an action that does not keep its own deadline — a shell without cancellation, an MCP client that does not
     * answer the interrupt the MCP executor's deadline sends — cannot turn the guard into a pass under the event
     * policy's
     * {@code FAIL_OPEN}. A hook that declared {@code failOpen} declares nothing and takes the policy's answer —
     * except one whose action is a {@link DenyAction}, on which {@code failOpen} is not read.
     */
    @Override
    public Optional<TimeoutBehavior> getTimeoutBehavior() {
        return failOpen ? Optional.empty() : Optional.of(TimeoutBehavior.FAIL_CLOSED);
    }

    /**
     * Runs the shell action. An executor is contracted never to throw, but a guard that cannot judge must block: a
     * custom executor that throws anyway, or a linkage failure from a provider built against another core, is read
     * as {@link ShellHookOutcome.Unrun#EXECUTION_FAILED} so the fail-closed rule (and its {@code failOpen} opt-out)
     * applies, instead of the exception reaching the hook policy, which would let the tool call through.
     */
    private ShellHookOutcome runShell(ShellAction shell, PreToolContext context, String toolName, ToolInput toolInput) {
        try {
            final Map<String, String> env = buildShellEnv(context, toolName);
            final Optional<ShellHookOutcome> unstaged = SkillHookDirectory.export(env, context, this, shellExecutor);
            if (unstaged.isPresent()) {
                return unstaged.get();
            }
            return shellExecutor.run(shell, context, env, ShellHookPayload.render(env, toolInput.toMap()));
        } catch (RuntimeException | LinkageError e) {
            log.warn("Skill '{}' preTool shell hook for tool '{}' threw instead of reporting an outcome", skillName,
                    toolName, e);
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.EXECUTION_FAILED, ShellActionRunner.failureDetail(e));
        }
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
            final ShellHookOutcome outcome = runShell(shell, context, toolName, toolInput);
            if (outcome.isDenied()) {
                log.info("Skill '{}' preTool shell hook vetoed tool '{}' (exit {}): {}", skillName, toolName,
                        outcome.getExitCode(), outcome.denyReason());
                return withRewake(HookResult.block(outcome.denyReason()));
            }
            final Optional<String> unrun = ShellHookVerdicts.guard(outcome, failOpen, skillName, EVENT_NAME);
            if (unrun.isPresent()) {
                return withRewake(HookResult.block(unrun.get()));
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
            return withRewake(judge(callHttp(http, context, toolName, toolInput), ShellHookVerdicts.HTTP_CALL));
        }
        if (action instanceof McpToolAction mcp) {
            return withRewake(judge(callMcp(mcp, context, toolName, toolInput), ShellHookVerdicts.MCP_CALL));
        }
        // Unreachable: HookAction is sealed and exhaustively handled above.
        throw new IllegalStateException("Unknown HookAction subtype: " + action.getClass());
    }

    /**
     * Calls the HTTP endpoint. A hook whose executor is not wired cannot ask at all, and an executor that throws
     * (or fails to link) did not answer: both are "no verdict", so the fail-closed rule and its {@code failOpen}
     * opt-out apply instead of the tool call going through.
     */
    private ActionCallOutcome callHttp(HttpAction http, PreToolContext context, String toolName, ToolInput toolInput) {
        if (httpExecutor == null) {
            log.warn("Skill '{}' preTool hook for tool '{}' carries an HttpAction but no HttpActionExecutor is wired",
                    skillName, toolName);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.EXECUTOR_NOT_WIRED, "http");
        }
        try {
            return httpExecutor.attempt(http, toolInput, buildContextAttributes(context, toolName), processEnvSnapshot);
        } catch (RuntimeException | LinkageError e) {
            log.warn("Skill '{}' preTool http hook for tool '{}' threw instead of reporting an outcome", skillName,
                    toolName, e);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        }
    }

    /** The MCP counterpart of {@link #callHttp}. */
    private ActionCallOutcome callMcp(McpToolAction mcp, PreToolContext context, String toolName, ToolInput toolInput) {
        if (mcpExecutor == null) {
            log.warn("Skill '{}' preTool hook for tool '{}' carries an McpToolAction but no McpActionExecutor is wired",
                    skillName, toolName);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.EXECUTOR_NOT_WIRED, "mcp");
        }
        try {
            return mcpExecutor.attempt(mcp, toolInput, buildContextAttributes(context, toolName));
        } catch (RuntimeException | LinkageError e) {
            log.warn("Skill '{}' preTool mcp hook for tool '{}' threw instead of reporting an outcome", skillName,
                    toolName, e);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        }
    }

    /**
     * Turns what an {@code http} / {@code mcp} call came to into this hook's result: a verdict is returned as it is
     * (a {@code deny} blocks whatever {@code failOpen} says), and a missing one blocks unless the hook declared
     * {@code failOpen} — the rule a shell command with no exit status gets, from the same place.
     */
    private HookResult judge(ActionCallOutcome outcome, String attempted) {
        if (outcome.getVerdict().isPresent()) {
            return outcome.getVerdict().get();
        }
        return ShellHookVerdicts.guard(outcome.getUnrun().orElseThrow(), failOpen, skillName, EVENT_NAME, attempted)
                .map(HookResult::block).orElseGet(HookResult::success);
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
