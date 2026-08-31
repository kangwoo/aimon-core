package at.aimon.core.mcp.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.mcp.exception.McpTransportException;

@DisabledOnOs(OS.WINDOWS)
class StdioMcpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructorRejectsNullArgs() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StdioMcpTransport(null, List.of(), Map.of(), Duration.ofMillis(100)));
        assertThatNullPointerException()
                .isThrownBy(() -> new StdioMcpTransport("/bin/cat", null, Map.of(), Duration.ofMillis(100)));
        assertThatNullPointerException()
                .isThrownBy(() -> new StdioMcpTransport("/bin/cat", List.of(), null, Duration.ofMillis(100)));
        assertThatNullPointerException().isThrownBy(() -> new StdioMcpTransport("/bin/cat", List.of(), Map.of(), null));
    }

    @Test
    void constructorThrowsWhenProcessFailsToStart() {
        assertThatThrownBy(() -> new StdioMcpTransport("/this/binary/does/not/exist/aimon-mcp-stdio", List.of(),
                Map.of(), Duration.ofMillis(500))).isInstanceOf(McpTransportException.class)
                .hasMessageContaining("Failed to start MCP process");
    }

    @Test
    void echoServerRoundTripsRequestAsResponse() throws Exception {
        // cat echoes each line back. The transport tags the request with id=1 and the echoed line carries
        // the same id, so sendRequest matches and returns the (missing) "result" field as null.
        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/cat", List.of(), Map.of(),
                Duration.ofSeconds(2))) {

            assertThat(transport.isConnected()).isTrue();

            ObjectNode params = MAPPER.createObjectNode();
            params.put("hello", "world");
            JsonNode result = transport.sendRequest("tools/list", params);

            // The echoed request has no "result" field, so JsonNode#get returns null.
            assertThat(result).isNull();
        }
    }

    @Test
    void jsonRpcErrorResponseIsSurfacedAsTransportException() throws Exception {
        // sh script: read one line, ignore it, emit a canned JSON-RPC error response keyed to id=1.
        String script = "read -r line; "
                + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"server boom\"}}'";

        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(),
                Duration.ofSeconds(2))) {

            assertThatThrownBy(() -> transport.sendRequest("tools/list", MAPPER.createObjectNode()))
                    .isInstanceOf(McpTransportException.class).hasMessageContaining("server boom")
                    .hasMessageContaining("tools/list");
        }
    }

    @Test
    void jsonRpcErrorWithoutMessageReportsUnknownError() throws Exception {
        String script = "read -r line; " + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1}}'";

        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(),
                Duration.ofSeconds(2))) {

            assertThatThrownBy(() -> transport.sendRequest("tools/call", MAPPER.createObjectNode()))
                    .isInstanceOf(McpTransportException.class).hasMessageContaining("Unknown error");
        }
    }

    @Test
    void notificationsAreSkippedAndCorrectResponseIsReturned() throws Exception {
        // First emit a notification (no id), then the real response with id=1 carrying a result object.
        String script = "read -r line; " + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/log\"}'; "
                + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}'";

        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(),
                Duration.ofSeconds(2))) {

            JsonNode result = transport.sendRequest("tools/list", MAPPER.createObjectNode());
            assertThat(result).isNotNull();
            assertThat(result.get("ok").asBoolean()).isTrue();
        }
    }

    @Test
    void timeoutIsEnforcedWhenServerNeverResponds() throws Exception {
        // sh that reads then sleeps — nothing ever reaches stdout.
        String script = "read -r line; sleep 5";

        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(),
                Duration.ofMillis(200))) {

            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.sendRequest("tools/list", MAPPER.createObjectNode()))
                    .isInstanceOf(McpTransportException.class).hasMessageContaining("Request timeout");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertThat(elapsedMs).isLessThan(2_000L);
        }
    }

    @Test
    void closingTransportTerminatesProcessAndBlocksFurtherRequests() throws Exception {
        StdioMcpTransport transport = new StdioMcpTransport("/bin/cat", List.of(), Map.of(), Duration.ofSeconds(1));

        assertThat(transport.isConnected()).isTrue();
        transport.close();

        assertThat(transport.isConnected()).isFalse();
        assertThatThrownBy(() -> transport.sendRequest("tools/list", MAPPER.createObjectNode()))
                .isInstanceOf(McpTransportException.class).hasMessageContaining("not running");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        StdioMcpTransport transport = new StdioMcpTransport("/bin/cat", List.of(), Map.of(), Duration.ofSeconds(1));
        transport.close();
        transport.close();
        assertThat(transport.isConnected()).isFalse();
    }

    @Test
    void sendRequestFailsWhenChildProcessExitedFirst() throws Exception {
        // Process exits immediately — stdin write should hit a broken pipe and bubble out as McpTransportException.
        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", "exit 0"), Map.of(),
                Duration.ofMillis(500))) {

            // Give the child a moment to exit so process.isAlive() flips false.
            Thread.sleep(100);

            assertThatThrownBy(() -> transport.sendRequest("tools/list", MAPPER.createObjectNode()))
                    .isInstanceOf(McpTransportException.class);
        }
    }
}
