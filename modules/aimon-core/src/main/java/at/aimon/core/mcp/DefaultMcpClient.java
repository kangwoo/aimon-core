package at.aimon.core.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.mcp.exception.McpInitializeException;
import at.aimon.core.mcp.transport.McpTransport;

/**
 * Default implementation of {@link McpClient}.
 *
 * <p>
 * Communicates with an MCP server via the provided {@link McpTransport}, handling JSON-RPC protocol operations
 * including
 * initialization, tool listing, and tool calling.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. The {@code initialized} and connection state are managed via volatile fields, and the
 * underlying {@link McpTransport} is required to be thread-safe.
 *
 * <h2>Post-close Behavior</h2>
 * <p>
 * After {@link #close()}, {@link #isConnected()} immediately returns {@code false}.
 */
public class DefaultMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpClient.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "aimon";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpTransport transport;
    private final String serverName;
    private final ObjectMapper objectMapper;
    private volatile boolean initialized = false;
    private volatile boolean closed = false;

    /**
     * Creates a DefaultMcpClient.
     *
     * @param transport
     *            the transport layer for server communication (must be thread-safe)
     * @param serverName
     *            the identifying name of the MCP server
     */
    public DefaultMcpClient(McpTransport transport, String serverName) {
        this.transport = Objects.requireNonNull(transport, "transport cannot be null");
        this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public McpServerCapabilities initialize() {
        if (initialized) {
            throw new IllegalStateException("McpClient for server '" + serverName + "' is already initialized");
        }

        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);

            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", CLIENT_NAME);
            clientInfo.put("version", CLIENT_VERSION);
            params.set("clientInfo", clientInfo);

            ObjectNode capabilities = objectMapper.createObjectNode();
            params.set("capabilities", capabilities);

            JsonNode response = transport.sendRequest("initialize", params);

            McpServerCapabilities serverCapabilities = parseCapabilities(response);

            // Send initialized notification as a one-way message (no id, no response expected). Routing it through
            // sendRequest would busy-wait for a correlated reply that a compliant server never sends, stalling
            // initialize() for the full request timeout.
            transport.sendNotification("notifications/initialized", objectMapper.createObjectNode());

            initialized = true;
            log.info("MCP client initialized for server '{}': {}", serverName, serverCapabilities.getServerName());

            return serverCapabilities;

        } catch (Exception e) {
            throw new McpInitializeException(
                    "Failed to initialize MCP client for server '" + serverName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public List<McpToolSchema> listTools() {
        ensureInitialized();

        ObjectNode params = objectMapper.createObjectNode();
        JsonNode response = transport.sendRequest("tools/list", params);

        List<McpToolSchema> tools = new ArrayList<>();
        JsonNode toolsNode = response.get("tools");
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                tools.add(parseToolSchema(toolNode));
            }
        }

        return Collections.unmodifiableList(tools);
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(arguments, "arguments cannot be null");
        ensureInitialized();

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments));

        JsonNode response = transport.sendRequest("tools/call", params);

        return parseCallResult(response);
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    @Override
    public boolean isConnected() {
        return !closed && initialized && transport.isConnected();
    }

    @Override
    public void close() throws Exception {
        closed = true;
        transport.close();
        log.debug("DefaultMcpClient closed for server '{}'", serverName);
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "McpClient for server '" + serverName + "' is not initialized. Call initialize() first.");
        }
        if (closed) {
            throw new IllegalStateException("McpClient for server '" + serverName + "' is already closed");
        }
    }

    private McpServerCapabilities parseCapabilities(JsonNode response) {
        McpServerCapabilities.Builder builder = McpServerCapabilities.builder();

        JsonNode serverInfo = response.get("serverInfo");
        if (serverInfo != null) {
            builder.serverName(getTextOrDefault(serverInfo, "name", serverName));
            builder.serverVersion(getTextOrNull(serverInfo, "version"));
        } else {
            builder.serverName(serverName);
        }

        JsonNode capabilities = response.get("capabilities");
        if (capabilities != null) {
            builder.supportsTools(capabilities.has("tools"));
            builder.supportsResources(capabilities.has("resources"));
            builder.supportsPrompts(capabilities.has("prompts"));
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private McpToolSchema parseToolSchema(JsonNode toolNode) {
        String name = toolNode.get("name").asText();
        String description = getTextOrDefault(toolNode, "description", "");

        JsonNode inputSchemaNode = toolNode.get("inputSchema");
        Map<String, Object> inputSchema;
        if (inputSchemaNode != null) {
            inputSchema = objectMapper.convertValue(inputSchemaNode, Map.class);
        } else {
            inputSchema = Map.of("type", "object", "properties", Map.of());
        }

        return McpToolSchema.of(name, description, inputSchema, parseToolAnnotations(toolNode.get("annotations")));
    }

    /**
     * Reads MCP's {@code ToolAnnotations} object. A hint the server omitted, or sent as something other than a boolean,
     * is left absent rather than coerced — {@link McpToolAnnotations} applies the spec default for it, and a malformed
     * hint should not be able to produce a <em>less</em> conservative reading than saying nothing would.
     */
    private McpToolAnnotations parseToolAnnotations(JsonNode annotationsNode) {
        if (annotationsNode == null || !annotationsNode.isObject()) {
            return McpToolAnnotations.empty();
        }
        return McpToolAnnotations.builder().readOnlyHint(getBooleanOrNull(annotationsNode, "readOnlyHint"))
                .destructiveHint(getBooleanOrNull(annotationsNode, "destructiveHint"))
                .idempotentHint(getBooleanOrNull(annotationsNode, "idempotentHint"))
                .openWorldHint(getBooleanOrNull(annotationsNode, "openWorldHint")).build();
    }

    private static Boolean getBooleanOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private McpCallResult parseCallResult(JsonNode response) {
        boolean isError = response.has("isError") && response.get("isError").asBoolean(false);

        StringBuilder content = new StringBuilder();
        JsonNode contentArray = response.get("content");
        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                String type = getTextOrDefault(item, "type", "text");
                if ("text".equals(type)) {
                    if (content.length() > 0) {
                        content.append("\n");
                    }
                    content.append(getTextOrDefault(item, "text", ""));
                }
            }
        }

        String resultContent = content.length() > 0 ? content.toString() : "";

        if (isError) {
            return McpCallResult.error(resultContent);
        }
        return McpCallResult.success(resultContent);
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return defaultValue;
    }

    private static String getTextOrNull(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return null;
    }

}
