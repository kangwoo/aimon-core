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
 * Unknown server, transport failure, and {@link McpCallResult#isError()} all degrade to {@code HookResult.success()}
 * with a WARN log &mdash; declarative hooks remain fail-soft.
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
     * Executes the action and returns the resolved {@link HookResult}.
     *
     * @param action
     *            configured action (must not be null)
     * @param toolInput
     *            tool input source for placeholders (may be null)
     * @param contextAttributes
     *            context attributes for {@code ${context.X}} placeholders (must not be null)
     * @return the hook result (never null)
     */
    public HookResult run(McpToolAction action, ToolInput toolInput, Map<String, String> contextAttributes) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(contextAttributes, "contextAttributes cannot be null");

        final Optional<McpClient> clientOpt = mcpClientManager.getClient(action.getServerName());
        if (clientOpt.isEmpty()) {
            log.warn("MCP hook to '{}/{}' skipped: server not registered", action.getServerName(),
                    action.getToolName());
            return HookResult.success();
        }
        final McpClient client = clientOpt.get();
        if (!client.isConnected()) {
            log.warn("MCP hook to '{}/{}' skipped: server not connected", action.getServerName(), action.getToolName());
            return HookResult.success();
        }

        final TemplateRenderer renderer = TemplateRenderer.builder().toolInput(toolInput).context(contextAttributes)
                .build();

        @SuppressWarnings("unchecked")
        final Map<String, Object> renderedArgs = (Map<String, Object>) renderer.renderObject(action.getArgsTemplate());

        try {
            final McpCallResult result = client.callTool(action.getToolName(), renderedArgs);
            if (result.isError()) {
                log.warn("MCP hook '{}/{}' returned isError content: {}", action.getServerName(), action.getToolName(),
                        summarise(result.getContent()));
                return HookResult.success();
            }
            return mapContent(action, result.getContent());
        } catch (McpTransportException e) {
            log.warn("MCP hook '{}/{}' transport error: {}", action.getServerName(), action.getToolName(),
                    e.getMessage());
            return HookResult.success();
        } catch (RuntimeException e) {
            log.warn("MCP hook '{}/{}' threw: {}", action.getServerName(), action.getToolName(), e.getMessage(), e);
            return HookResult.success();
        }
    }

    private HookResult mapContent(McpToolAction action, String content) {
        if (content == null || content.isBlank()) {
            return HookResult.success();
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            // Plain-text content → side-effect only call, no decision intended.
            return HookResult.success();
        }
        if (root == null || !root.isObject()) {
            return HookResult.success();
        }

        final JsonNode decisionNode = root.get("decision");
        final JsonNode reasonNode = root.get("reason");
        if (decisionNode != null && decisionNode.isTextual() && "deny".equalsIgnoreCase(decisionNode.asText())) {
            final String reason = (reasonNode != null && reasonNode.isTextual())
                    ? reasonNode.asText()
                    : "Denied by MCP hook " + action.getServerName() + "/" + action.getToolName();
            return HookResult.block(reason);
        }

        final JsonNode feedbackNode = root.get("feedback");
        final JsonNode updatedInputNode = root.get("updatedInput");
        if ((feedbackNode == null || !feedbackNode.isTextual())
                && (updatedInputNode == null || !updatedInputNode.isObject())) {
            return HookResult.success();
        }

        final HookResult.Builder b = HookResult.builder();
        if (feedbackNode != null && feedbackNode.isTextual()) {
            b.feedback(feedbackNode.asText());
        }
        if (updatedInputNode != null && updatedInputNode.isObject()) {
            try {
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = objectMapper.convertValue(updatedInputNode, Map.class);
                b.updatedInput(ToolInput.of(map));
            } catch (IllegalArgumentException e) {
                log.warn("MCP hook returned malformed updatedInput; ignoring: {}", e.getMessage());
            }
        }
        return b.build();
    }

    private static String summarise(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }
        final String trimmed = text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
