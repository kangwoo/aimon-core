package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * with a {@code decision} field, decisions {@code allow}/{@code deny}/{@code defer} are honored; otherwise the call is
 * treated as side-effect only and {@link HookResult#success()} is returned.
 *
 * <p>
 * <b>Verdict or no verdict.</b> {@link #attempt} tells the two apart, because a guard has to (see
 * {@link ActionCallOutcome}). A result that came back without {@link McpCallResult#isError()} is a verdict: content
 * that is blank, plain text or JSON that is not an object is a side-effect call (allow), and a JSON object is read
 * as a decision document. No verdict is: a server that is not registered or not connected, a transport failure or
 * any other exception from the client, a result flagged {@code isError} (all {@code CALL_FAILED}), and a decision
 * document that cannot be read ({@code INVALID_RESPONSE} &mdash; a {@code decision} that is not text or not one of
 * {@code allow} / {@code deny} / {@code defer}, an {@code updatedInput} that is not an object).
 *
 * <p>
 * {@link #run} is the advisory reading of the same call: a missing verdict is logged at WARN and returned as
 * {@link HookResult#success()}, so a {@code postTool} call stays fail-soft.
 *
 * <p>
 * The action's {@code timeout} is not enforced here &mdash; {@link McpClient#callTool} takes none. What bounds a call
 * that hangs is the hook executor's outer net, which the action's declared budget widens.
 *
 * <p>
 * Thread-safe; {@code McpClientManager} is documented as thread-safe.
 */
public final class McpActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpActionExecutor.class);

    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new executor.
     *
     * @param mcpClientManager
     *            MCP manager that owns the registered server clients (must not be null)
     * @param objectMapper
     *            JSON mapper used to interpret the call result content (must not be null)
     */
    public McpActionExecutor(McpClientManager mcpClientManager, ObjectMapper objectMapper) {
        this.mcpClientManager = Objects.requireNonNull(mcpClientManager, "mcpClientManager cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
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
            final Optional<McpClient> clientOpt = mcpClientManager.getClient(action.getServerName());
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

            final McpCallResult result = client.callTool(action.getToolName(), renderedArgs);
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
