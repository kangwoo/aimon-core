package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.mcp.exception.McpInitializeException;
import at.aimon.core.mcp.transport.McpTransport;

class DefaultMcpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTransport transport;
    private DefaultMcpClient client;

    @BeforeEach
    void setUp() {
        transport = mock(McpTransport.class);
        lenient().when(transport.isConnected()).thenReturn(true);
        client = new DefaultMcpClient(transport, "github");
    }

    private JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Constructor rejects null arguments")
    void constructorRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultMcpClient(null, "github"));
        assertThatNullPointerException().isThrownBy(() -> new DefaultMcpClient(transport, null));
    }

    @Test
    @DisplayName("getServerName returns configured name")
    void getServerNameReturnsConfigured() {
        assertThat(client.getServerName()).isEqualTo("github");
    }

    @Test
    @DisplayName("isConnected is false before initialize()")
    void notConnectedBeforeInitialize() {
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("initialize() parses serverInfo and capabilities")
    void initializeParsesResponse() {
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(parse("""
                {
                  "serverInfo": {"name": "GitHub MCP", "version": "2.1.0"},
                  "capabilities": {"tools": {}, "resources": {}}
                }
                """));

        McpServerCapabilities caps = client.initialize();

        assertThat(caps.getServerName()).isEqualTo("GitHub MCP");
        assertThat(caps.getServerVersion()).isEqualTo("2.1.0");
        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsResources()).isTrue();
        assertThat(caps.supportsPrompts()).isFalse();
        verify(transport).sendNotification(eq("notifications/initialized"), any());
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    @DisplayName("initialize() falls back to configured server name when serverInfo missing")
    void initializeFallsBackToConfigName() {
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(parse("{}"));

        McpServerCapabilities caps = client.initialize();

        assertThat(caps.getServerName()).isEqualTo("github");
        assertThat(caps.getServerVersion()).isNull();
        assertThat(caps.supportsTools()).isFalse();
    }

    @Test
    @DisplayName("initialize() twice throws IllegalStateException")
    void initializeTwiceFails() {
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(parse("{\"serverInfo\":{\"name\":\"x\"}}"));
        client.initialize();

        assertThatIllegalStateException().isThrownBy(() -> client.initialize())
                .withMessageContaining("already initialized");
    }

    @Test
    @DisplayName("initialize() wraps transport failure in McpInitializeException")
    void initializeWrapsTransportFailure() {
        when(transport.sendRequest(eq("initialize"), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> client.initialize()).isInstanceOf(McpInitializeException.class)
                .hasMessageContaining("github");
    }

    @Test
    @DisplayName("listTools requires initialization")
    void listToolsRequiresInit() {
        assertThatIllegalStateException().isThrownBy(() -> client.listTools()).withMessageContaining("not initialized");
    }

    @Test
    @DisplayName("listTools parses tools array")
    void listToolsParses() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(parse("""
                {
                  "tools": [
                    {"name": "create_issue", "description": "Create an issue",
                     "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}}}},
                    {"name": "list_repos"}
                  ]
                }
                """));

        List<McpToolSchema> tools = client.listTools();

        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).getName()).isEqualTo("create_issue");
        assertThat(tools.get(0).getDescription()).isEqualTo("Create an issue");
        assertThat(tools.get(0).getInputSchema()).containsKey("type").containsKey("properties");
        // tool with neither description nor inputSchema falls back to defaults
        assertThat(tools.get(1).getName()).isEqualTo("list_repos");
        assertThat(tools.get(1).getDescription()).isEmpty();
        assertThat(tools.get(1).getInputSchema()).containsEntry("type", "object");
    }

    @Test
    @DisplayName("listTools parses tool annotations, and treats an absent block as no claims at all")
    void listToolsParsesAnnotations() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(parse("""
                {
                  "tools": [
                    {"name": "search", "annotations": {"readOnlyHint": true, "openWorldHint": false}},
                    {"name": "append", "annotations": {"destructiveHint": false, "idempotentHint": true}},
                    {"name": "unannotated"}
                  ]
                }
                """));

        List<McpToolSchema> tools = client.listTools();

        assertThat(tools.get(0).getAnnotations().getReadOnlyHint()).contains(true);
        assertThat(tools.get(0).getAnnotations().getOpenWorldHint()).contains(false);
        assertThat(tools.get(0).getAnnotations().getDestructiveHint()).as("a hint the server omitted stays absent")
                .isEmpty();
        assertThat(tools.get(1).getAnnotations().getDestructiveHint()).contains(false);
        assertThat(tools.get(1).getAnnotations().getIdempotentHint()).contains(true);
        assertThat(tools.get(2).getAnnotations()).isEqualTo(McpToolAnnotations.empty());
    }

    @Test
    @DisplayName("A malformed annotations block or hint leaves the claim absent rather than reading it as false")
    void listToolsToleratesMalformedAnnotations() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(parse("""
                {
                  "tools": [
                    {"name": "bogus_block", "annotations": "readOnly"},
                    {"name": "bogus_hint", "annotations": {"readOnlyHint": "yes", "destructiveHint": null}}
                  ]
                }
                """));

        List<McpToolSchema> tools = client.listTools();

        assertThat(tools.get(0).getAnnotations()).isEqualTo(McpToolAnnotations.empty());
        assertThat(tools.get(1).getAnnotations().getReadOnlyHint()).isEmpty();
        assertThat(tools.get(1).getAnnotations().getDestructiveHint()).isEmpty();
        assertThat(tools.get(1).getAnnotations().isDestructive()).as("the absent hint still defaults to destructive")
                .isTrue();
    }

    @Test
    @DisplayName("listTools returns empty when tools field missing")
    void listToolsEmpty() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(parse("{}"));

        assertThat(client.listTools()).isEmpty();
    }

    @Test
    @DisplayName("callTool returns success with concatenated text content")
    void callToolSuccess() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/call"), any())).thenReturn(parse("""
                {
                  "content": [
                    {"type": "text", "text": "line 1"},
                    {"type": "text", "text": "line 2"},
                    {"type": "image", "data": "base64-data"}
                  ]
                }
                """));

        McpCallResult result = client.callTool("create_issue", Map.of("title", "bug"));

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("line 1\nline 2");
    }

    @Test
    @DisplayName("callTool returns error when isError true")
    void callToolError() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/call"), any())).thenReturn(parse("""
                {"isError": true, "content": [{"type": "text", "text": "permission denied"}]}
                """));

        McpCallResult result = client.callTool("create_issue", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isEqualTo("permission denied");
    }

    @Test
    @DisplayName("callTool returns empty content when no content field")
    void callToolEmptyContent() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/call"), any())).thenReturn(parse("{}"));

        McpCallResult result = client.callTool("noop", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("callTool serializes arguments into params")
    void callToolSerializesArgs() {
        initSuccessfully();
        when(transport.sendRequest(eq("tools/call"), any())).thenReturn(parse("{\"content\":[]}"));

        client.callTool("create_issue", Map.of("title", "bug", "priority", 3));

        verify(transport).sendRequest(eq("tools/call"), any(ObjectNode.class));
    }

    @Test
    @DisplayName("callTool requires non-null toolName/arguments")
    void callToolRejectsNulls() {
        initSuccessfully();
        assertThatNullPointerException().isThrownBy(() -> client.callTool(null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> client.callTool("x", null));
    }

    @Test
    @DisplayName("callTool requires initialization")
    void callToolRequiresInit() {
        assertThatIllegalStateException().isThrownBy(() -> client.callTool("x", Map.of()))
                .withMessageContaining("not initialized");
    }

    @Test
    @DisplayName("close() makes isConnected return false and closes transport")
    void closeBehavior() throws Exception {
        initSuccessfully();
        assertThat(client.isConnected()).isTrue();

        client.close();

        assertThat(client.isConnected()).isFalse();
        verify(transport, atLeastOnce()).close();
    }

    @Test
    @DisplayName("Operations after close throw IllegalStateException")
    void operationsAfterCloseFail() throws Exception {
        initSuccessfully();
        client.close();

        assertThatIllegalStateException().isThrownBy(() -> client.callTool("x", Map.of()))
                .withMessageContaining("already closed");
    }

    @Test
    @DisplayName("close() propagates transport close exception")
    void closePropagatesTransportFailure() throws Exception {
        initSuccessfully();
        doThrow(new RuntimeException("close failed")).when(transport).close();

        assertThatThrownBy(() -> client.close()).hasMessageContaining("close failed");
    }

    private void initSuccessfully() {
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(parse("{\"serverInfo\":{\"name\":\"x\"}}"));
        client.initialize();
    }
}
