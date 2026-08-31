package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;
import at.aimon.core.mcp.exception.McpTransportException;

class McpToolTest {

    private McpClient mcpClient;
    private McpToolSchema schema;
    private McpTool tool;

    @BeforeEach
    void setUp() {
        mcpClient = mock(McpClient.class);
        schema = McpToolSchema.of("create_issue", "Create an issue", Map.of("type", "object"));
        when(mcpClient.getServerName()).thenReturn("github");
        tool = new McpTool("github", schema, mcpClient);
    }

    @Test
    @DisplayName("Tool name follows mcp__<server>__<tool> format")
    void toolNameFormat() {
        assertThat(tool.getDefinition().getName()).isEqualTo("mcp__github__create_issue");
    }

    @Test
    @DisplayName("Description and input schema come from McpToolSchema")
    void definitionMetadataFromSchema() {
        assertThat(tool.getDefinition().getDescription()).isEqualTo("Create an issue");
    }

    @Test
    @DisplayName("Null mcpClient rejected")
    void nullClientRejected() {
        assertThatNullPointerException().isThrownBy(() -> new McpTool("github", schema, null));
    }

    @Test
    @DisplayName("Without traits the tool declares what an unaudited tool declares")
    void defaultDeclarationsAreConservative() {
        assertThat(tool.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(tool.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(tool.isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("Resolved traits become the tool's own declarations, indistinguishable from a local tool's")
    void traitsBecomeDeclarations() {
        McpToolSchema readOnly = McpToolSchema.of("search", "Search", Map.of("type", "object"),
                McpToolAnnotations.builder().readOnlyHint(true).build());

        McpTool trusted = new McpTool("github", readOnly, mcpClient,
                McpToolTraits.resolve(readOnly.getAnnotations(), AnnotationTrust.TRUST));

        assertThat(trusted.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(trusted.isReadOnly()).isTrue();
        assertThat(trusted.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
    }

    @Test
    @DisplayName("Null traits rejected — untrusted() is the way to say 'none'")
    void nullTraitsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new McpTool("github", schema, mcpClient, null));
    }

    @Test
    @DisplayName("execute() returns success when client returns success")
    void executeSuccess() {
        when(mcpClient.isConnected()).thenReturn(true);
        when(mcpClient.callTool(eq("create_issue"), any())).thenReturn(McpCallResult.success("issue #42 created"));

        ToolResult result = tool.execute(ToolInput.of("owner", "kangwoo"), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("issue #42 created");
    }

    @Test
    @DisplayName("execute() returns error when client returns error result")
    void executeErrorResult() {
        when(mcpClient.isConnected()).thenReturn(true);
        when(mcpClient.callTool(eq("create_issue"), any())).thenReturn(McpCallResult.error("invalid params"));

        ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isEqualTo("invalid params");
    }

    @Test
    @DisplayName("execute() returns error when client is not connected")
    void executeWhenDisconnected() {
        when(mcpClient.isConnected()).thenReturn(false);

        ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("'github'").contains("not connected");
        verify(mcpClient, never()).callTool(any(), any());
    }

    @Test
    @DisplayName("execute() wraps transport exception into ToolResult.error")
    void executeWrapsTransportException() {
        when(mcpClient.isConnected()).thenReturn(true);
        when(mcpClient.callTool(any(), any())).thenThrow(new McpTransportException("network down"));

        ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("MCP tool execution failed").contains("network down");
    }

    @Test
    @DisplayName("execute() rejects null input/context")
    void executeRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> tool.execute(null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(ToolInput.of(), null));
    }
}
