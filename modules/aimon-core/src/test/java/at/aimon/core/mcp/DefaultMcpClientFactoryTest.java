package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.mcp.McpServerConfig.McpTransportType;
import at.aimon.core.mcp.exception.McpTransportException;

class DefaultMcpClientFactoryTest {

    private final DefaultMcpClientFactory factory = new DefaultMcpClientFactory();

    @Test
    @DisplayName("create rejects null config")
    void rejectsNullConfig() {
        assertThatNullPointerException().isThrownBy(() -> factory.create(null));
    }

    @Test
    @DisplayName("STDIO transport with non-existent command fails to start")
    @DisabledOnOs(OS.WINDOWS)
    void stdioWithBadCommandFails() {
        McpServerConfig config = McpServerConfig.builder().name("ghost").transportType(McpTransportType.STDIO)
                .command("/this/binary/definitely/does/not/exist/aimon-mcp-test").build();

        assertThatThrownBy(() -> factory.create(config)).isInstanceOf(McpTransportException.class)
                .hasMessageContaining("Failed to start MCP process");
    }
}
