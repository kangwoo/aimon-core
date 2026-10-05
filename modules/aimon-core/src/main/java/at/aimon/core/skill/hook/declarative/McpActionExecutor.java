package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.mcp.McpCallResult;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.exception.McpTransportException;
import at.aimon.core.skill.hook.action.McpToolAction;

/**
 * Executes {@link McpToolAction} declarative hook actions.
 *
 * <p>
 * Resolves the target server via {@link McpClientManager} at call time, renders the args template via
 * {@link TemplateRenderer}, and invokes {@link McpClient#callTool}. The {@link McpCallResult} is mapped to a
 * {@link HookResult} using the same JSON contract as the HTTP executor &mdash; if the result content is a JSON object
 * it is read by {@link DecisionDocument}, the native {@code decision} and the Claude Code spellings alike; otherwise
 * the call is treated as side-effect only and {@link HookResult#success()} is returned.
 *
 * <p>
 * <b>Verdict or no verdict.</b> {@link #attempt} tells the two apart, because a guard has to (see
 * {@link ActionCallOutcome}). A result that came back without {@link McpCallResult#isError()} is a verdict: content
 * that is blank, plain text or JSON that is not an object is a side-effect call (allow), and a JSON object is read
 * as a decision document. No verdict is: a server that is not registered or not connected, a transport failure or
 * any other exception from the client, a result flagged {@code isError} (all {@code CALL_FAILED}), and a decision
 * document that cannot be read ({@code INVALID_RESPONSE} &mdash; a decision field whose value is not one
 * {@link DecisionDocument} knows, an {@code updatedInput} that is not an object).
 *
 * <p>
 * {@link #run} is the advisory reading of the same call: a missing verdict is logged at WARN and returned as
 * {@link HookResult#success()}, so a {@code postTool} call stays fail-soft.
 *
 * <p>
 * <b>Timeout.</b> The action's {@link McpToolAction#getTimeout() timeout} bounds the call. {@link McpClient#callTool}
 * takes none, so the deadline is kept here ({@link CallDeadline}, the mechanism the HTTP executor uses for the same
 * purpose): when it passes, the calling thread is interrupted, which is how a
 * request is told to stop ({@code StdioMcpTransport} polls for its response and gives up on an interrupt), and the
 * outcome is {@code TIMEOUT} &mdash; no verdict. The interrupt is this executor's own and is taken back before
 * returning, so the thread's interrupt flag is left as the call found it. No thread is created per call: the deadline
 * rides on the JDK's shared delay scheduler and is cancelled when the call ends &mdash; by returning, by throwing, or
 * with an {@link Error} that this method lets through.
 *
 * <p>
 * The server's {@code requestTimeout} (per server, 30s by default) still applies underneath, so <em>the smaller of
 * the two ends the call</em>: an action timeout cannot extend a request past the server's bound, and a request the
 * transport gives up on first is reported as {@code CALL_FAILED} (its exception does not say that it was a timeout).
 *
 * <p>
 * What happens to the request afterwards: nothing is left waiting on this side. Over stdio the request line has
 * already been written, so the server may go on working and answer late; the transport discards that answer when
 * the next request reads past it. No {@code notifications/cancelled} is sent. A client that does not answer an
 * interrupt &mdash; one waiting for the transport while another request holds it, a blocking read &mdash; is not ended
 * by this deadline; the hook executor's outer net ends the wait for it, and a guard still blocks.
 *
 * <p>
 * An interrupt that is <em>not</em> this executor's &mdash; the hook executor cutting the hook off, the execution
 * being cancelled &mdash; is reported as {@code CANCELLED} and left set, as the HTTP executor does.
 *
 * <p>
 * Thread-safe; {@code McpClientManager} is documented as thread-safe.
 */
public final class McpActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpActionExecutor.class);

    private final Supplier<Optional<McpClientManager>> mcpClientManagers;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new executor.
     *
     * <p>
     * The manager is <b>borrowed</b>: it is agent-scoped and its runtime closes it. This executor never does.
     *
     * @param mcpClientManager
     *            MCP manager that owns the registered server clients (must not be null)
     * @param objectMapper
     *            JSON mapper used to interpret the call result content (must not be null)
     */
    public McpActionExecutor(McpClientManager mcpClientManager, ObjectMapper objectMapper) {
        this(fixed(Objects.requireNonNull(mcpClientManager, "mcpClientManager cannot be null")), objectMapper);
    }

    private McpActionExecutor(Supplier<Optional<McpClientManager>> mcpClientManagers, ObjectMapper objectMapper) {
        this.mcpClientManagers = mcpClientManagers;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    private static Supplier<Optional<McpClientManager>> fixed(McpClientManager manager) {
        final Optional<McpClientManager> present = Optional.of(manager);
        return () -> present;
    }

    /**
     * Creates an executor that looks its {@link McpClientManager} up on every call.
     *
     * <p>
     * For an assembly that has to hand out the executor before the manager exists. A skill parser is the case: it
     * is built before the agent runtime, and the hooks it parses keep the executor they were given, while the manager
     * is created with the runtime and owned by it. While the supplier answers empty &mdash; before the runtime is up,
     * or for a runtime with no MCP servers &mdash; a call has no verdict ({@code CALL_FAILED}).
     *
     * <p>
     * One executor resolves one manager. A manager is agent-scoped, so this fits an assembly with one runtime; a
     * stack with several would need the manager of the runtime the hook <em>fired in</em>, which a supplier without
     * the firing context cannot give.
     *
     * @param mcpClientManagers
     *            answers the manager to use for a call, or empty when there is none yet; called on every call, on the
     *            hook's thread (must not be null, must not return null)
     * @param objectMapper
     *            JSON mapper used to interpret the call result content (must not be null)
     * @return a new executor (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public static McpActionExecutor lateBound(Supplier<Optional<McpClientManager>> mcpClientManagers,
            ObjectMapper objectMapper) {
        return new McpActionExecutor(Objects.requireNonNull(mcpClientManagers, "mcpClientManagers cannot be null"),
                objectMapper);
    }

    /**
     * Executes the action and returns the resolved {@link HookResult}, reading a call that produced no verdict as
     * success. For events that cannot block; a guard uses {@link #attempt}.
     *
     * @param action
     *            configured action (must not be null)
     * @param toolInput
     *            tool input source for placeholders (may be null)
     * @param contextAttributes
     *            context attributes for {@code ${context.X}} placeholders (must not be null)
     * @return the hook result (never null; success when the call produced no verdict)
     */
    public HookResult run(McpToolAction action, ToolInput toolInput, Map<String, String> contextAttributes) {
        return attempt(action, toolInput, contextAttributes).orSuccess();
    }

    /**
     * Executes the action and reports whether it produced a verdict. Never throws for a failed call.
     *
     * @param action
     *            configured action (must not be null)
     * @param toolInput
     *            tool input source for placeholders (may be null)
     * @param contextAttributes
     *            context attributes for {@code ${context.X}} placeholders (must not be null)
     * @return the verdict, or why there is none (never null)
     * @throws NullPointerException
     *             if action or contextAttributes is null
     */
    public ActionCallOutcome attempt(McpToolAction action, ToolInput toolInput, Map<String, String> contextAttributes) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(contextAttributes, "contextAttributes cannot be null");

        try {
            final Optional<McpClientManager> manager = mcpClientManagers.get();
            if (manager.isEmpty()) {
                log.warn("MCP hook to '{}/{}' skipped: no MCP servers are available", action.getServerName(),
                        action.getToolName());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, "MCP servers not available");
            }
            final Optional<McpClient> clientOpt = manager.get().getClient(action.getServerName());
            if (clientOpt.isEmpty()) {
                log.warn("MCP hook to '{}/{}' skipped: server not registered", action.getServerName(),
                        action.getToolName());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, "MCP server not registered");
            }
            final McpClient client = clientOpt.get();
            if (!client.isConnected()) {
                log.warn("MCP hook to '{}/{}' skipped: server not connected", action.getServerName(),
                        action.getToolName());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, "MCP server not connected");
            }

            final TemplateRenderer renderer = TemplateRenderer.builder().toolInput(toolInput).context(contextAttributes)
                    .build();

            @SuppressWarnings("unchecked")
            final Map<String, Object> renderedArgs = (Map<String, Object>) renderer
                    .renderObject(action.getArgsTemplate());

            final CallDeadline deadline = CallDeadline.arm(action.getTimeout());
            McpCallResult result = null;
            RuntimeException failure = null;
            boolean timedOut = false;
            try {
                result = client.callTool(action.getToolName(), renderedArgs);
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                // On every way out, an Error included: this thread belongs to the hook pool and runs something else
                // next, and a deadline left armed would interrupt that.
                timedOut = deadline.disarm();
            }
            if (failure != null) {
                if (timedOut) {
                    log.warn("MCP hook '{}/{}' timed out after {}", action.getServerName(), action.getToolName(),
                            action.getTimeout());
                    return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT,
                            "no response within " + action.getTimeout().toMillis() + "ms");
                }
                if (Thread.currentThread().isInterrupted()) {
                    // Not our deadline: the thread running the hook was interrupted from outside.
                    log.warn("MCP hook '{}/{}' interrupted", action.getServerName(), action.getToolName());
                    return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CANCELLED, "");
                }
                throw failure;
            }
            if (result == null) {
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE, "no result");
            }
            if (result.isError()) {
                // The content stays in the log: it is the server's text, and the detail becomes a deny reason.
                log.warn("MCP hook '{}/{}' returned isError content: {}", action.getServerName(), action.getToolName(),
                        summarise(result.getContent()));
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, "tool returned an error");
            }
            return mapContent(action, result.getContent());
        } catch (McpTransportException e) {
            log.warn("MCP hook '{}/{}' transport error: {}", action.getServerName(), action.getToolName(),
                    e.getMessage());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        } catch (RuntimeException e) {
            log.warn("MCP hook '{}/{}' threw: {}", action.getServerName(), action.getToolName(), e.getMessage(), e);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        }
    }

    private ActionCallOutcome mapContent(McpToolAction action, String content) {
        if (content == null || content.isBlank()) {
            return ActionCallOutcome.verdict(HookResult.success());
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            // Plain-text content → side-effect only call, no decision intended.
            return ActionCallOutcome.verdict(HookResult.success());
        }
        if (root == null || !root.isObject()) {
            return ActionCallOutcome.verdict(HookResult.success());
        }
        return DecisionDocument.read(root, objectMapper,
                "MCP hook '" + action.getServerName() + "/" + action.getToolName() + "'",
                "Denied by MCP hook " + action.getServerName() + "/" + action.getToolName(), false);
    }

    private static String summarise(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }
        final String trimmed = text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
