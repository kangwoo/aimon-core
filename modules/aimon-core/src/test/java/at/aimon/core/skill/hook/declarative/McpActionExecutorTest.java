package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.mcp.McpCallResult;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.exception.McpTransportException;
import at.aimon.core.skill.hook.action.McpToolAction;

@DisplayName("McpActionExecutor")
class McpActionExecutorTest {

    private McpClientManager manager;
    private McpClient client;
    private McpActionExecutor executor;

    @BeforeEach
    void setUp() {
        manager = mock(McpClientManager.class);
        client = mock(McpClient.class);
        when(client.isConnected()).thenReturn(true);
        when(manager.getClient("github")).thenReturn(Optional.of(client));
        when(manager.getClient("missing")).thenReturn(Optional.empty());
        executor = new McpActionExecutor(manager, new ObjectMapper());
    }

    @Test
    @DisplayName("renders args template before invoking callTool")
    void rendersArgsTemplate() {
        when(client.callTool(eq("create_issue"), any())).thenReturn(McpCallResult.success(""));

        final McpToolAction action = McpToolAction.builder().serverName("github").toolName("create_issue")
                .argsTemplate(Map.of("title", "${tool_input.title}", "body", "fixed in ${tool_input.commit}")).build();

        executor.run(action, ToolInput.of("title", "T1", "commit", "abc"), Map.of());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(client).callTool(eq("create_issue"), captor.capture());
        assertThat(captor.getValue()).containsEntry("title", "T1").containsEntry("body", "fixed in abc");
    }

    @Test
    @DisplayName("missing server degrades to success (fail-soft)")
    void missingServerIsSoftFail() {
        final McpToolAction action = McpToolAction.builder().serverName("missing").toolName("x").build();

        final HookResult result = executor.run(action, ToolInput.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @DisplayName("decision=deny in JSON content maps to block")
    void denyInJson() {
        when(client.callTool(any(), any()))
                .thenReturn(McpCallResult.success("{\"decision\":\"deny\",\"reason\":\"forbidden\"}"));

        final HookResult result = executor.run(simpleAction(), ToolInput.of(), Map.of());

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFeedback()).contains("forbidden");
    }

    @Test
    @DisplayName("plain-text content is treated as side-effect only")
    void plainTextIsSideEffect() {
        when(client.callTool(any(), any())).thenReturn(McpCallResult.success("posted"));

        final HookResult result = executor.run(simpleAction(), ToolInput.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @DisplayName("isError result degrades to success")
    void isErrorIsSoftFail() {
        when(client.callTool(any(), any())).thenReturn(McpCallResult.error("server-side failure"));

        final HookResult result = executor.run(simpleAction(), ToolInput.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @DisplayName("transport exception degrades to success")
    void transportExceptionIsSoftFail() {
        when(client.callTool(any(), any())).thenThrow(new McpTransportException("disconnected"));

        final HookResult result = executor.run(simpleAction(), ToolInput.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    private static McpToolAction simpleAction() {
        return McpToolAction.builder().serverName("github").toolName("ping").build();
    }
}
