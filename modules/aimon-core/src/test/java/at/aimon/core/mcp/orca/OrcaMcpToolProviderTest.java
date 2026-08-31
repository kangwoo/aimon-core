package at.aimon.core.mcp.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.McpServerConfig;
import at.aimon.core.mcp.McpServerConfig.McpTransportType;
import at.aimon.core.mcp.McpServerConfigProvider;

class OrcaMcpToolProviderTest {

    private McpServerConfigProvider configProvider;
    private McpClientManager mcpClientManager;
    private OrcaMcpToolProvider provider;
    private ToolRegistry registry;
    private OrcaToolProviderContext context;

    @BeforeEach
    void setUp() {
        configProvider = mock(McpServerConfigProvider.class);
        mcpClientManager = mock(McpClientManager.class);
        provider = new OrcaMcpToolProvider(configProvider, mcpClientManager);
        registry = new DefaultToolRegistry();
        context = mock(OrcaToolProviderContext.class);
    }

    private McpServerConfig stdioConfig(String name) {
        return McpServerConfig.builder().name(name).transportType(McpTransportType.STDIO).command("/bin/cat").build();
    }

    @Test
    @DisplayName("Constructor rejects null arguments")
    void constructorRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new OrcaMcpToolProvider(null, mcpClientManager));
        assertThatNullPointerException().isThrownBy(() -> new OrcaMcpToolProvider(configProvider, null));
    }

    @Test
    @DisplayName("registerTools skips when no configs provided")
    void registerSkipsEmptyConfigs() {
        when(configProvider.getConfigs()).thenReturn(List.of());

        provider.registerTools(registry, context);

        verify(mcpClientManager, never()).createClients(any());
        verify(mcpClientManager, never()).registerAllTools(any());
    }

    @Test
    @DisplayName("registerTools delegates to manager when configs present")
    void registerDelegatesToManager() {
        List<McpServerConfig> configs = List.of(stdioConfig("a"), stdioConfig("b"));
        when(configProvider.getConfigs()).thenReturn(configs);
        when(mcpClientManager.createClients(configs)).thenReturn(List.of());
        when(mcpClientManager.getServerNames()).thenReturn(List.of("a", "b"));

        provider.registerTools(registry, context);

        verify(mcpClientManager, times(1)).createClients(configs);
        verify(mcpClientManager, times(1)).registerAllTools(registry);
    }

    @Test
    @DisplayName("registerTools logs failed servers but still registers tools")
    void registerHandlesPartialFailure() {
        List<McpServerConfig> configs = List.of(stdioConfig("a"), stdioConfig("b"));
        when(configProvider.getConfigs()).thenReturn(configs);
        when(mcpClientManager.createClients(configs)).thenReturn(List.of("b"));
        when(mcpClientManager.getServerNames()).thenReturn(List.of("a"));

        provider.registerTools(registry, context);

        verify(mcpClientManager).registerAllTools(registry);
    }

    @Test
    @DisplayName("Duplicate server names rejected upfront before manager is touched")
    void duplicateServerNamesRejected() {
        when(configProvider.getConfigs()).thenReturn(List.of(stdioConfig("dup"), stdioConfig("dup")));

        assertThatIllegalArgumentException().isThrownBy(() -> provider.registerTools(registry, context))
                .withMessageContaining("Duplicate MCP server name").withMessageContaining("dup");

        verify(mcpClientManager, never()).createClients(any());
    }

    @Test
    @DisplayName("Single config with unique name passes validation")
    void singleConfigPasses() {
        when(configProvider.getConfigs()).thenReturn(List.of(stdioConfig("solo")));
        when(mcpClientManager.createClients(any())).thenReturn(List.of());
        when(mcpClientManager.getServerNames()).thenReturn(List.of("solo"));

        provider.registerTools(registry, context);

        assertThat(mcpClientManager).isNotNull();
        verify(mcpClientManager).createClients(any());
    }
}
