package at.aimon.core.mcp.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpExceptionsTest {

    @Test
    void mcpExceptionWithMessage() {
        McpException ex = new McpException("boom");
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mcpExceptionWithMessageAndCause() {
        Throwable cause = new IllegalStateException("inner");
        McpException ex = new McpException("boom", cause);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void mcpTransportExceptionExtendsMcpException() {
        Throwable cause = new RuntimeException("io");
        McpTransportException withMessage = new McpTransportException("transport down");
        McpTransportException withCause = new McpTransportException("transport down", cause);

        assertThat(withMessage).isInstanceOf(McpException.class);
        assertThat(withMessage.getMessage()).isEqualTo("transport down");
        assertThat(withMessage.getCause()).isNull();
        assertThat(withCause.getCause()).isSameAs(cause);
    }

    @Test
    void mcpInitializeExceptionExtendsMcpException() {
        Throwable cause = new McpTransportException("transport");
        McpInitializeException withMessage = new McpInitializeException("init failed");
        McpInitializeException withCause = new McpInitializeException("init failed", cause);

        assertThat(withMessage).isInstanceOf(McpException.class);
        assertThat(withMessage.getMessage()).isEqualTo("init failed");
        assertThat(withCause.getCause()).isSameAs(cause);
    }
}
