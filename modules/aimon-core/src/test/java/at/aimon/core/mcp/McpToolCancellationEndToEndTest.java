package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.mcp.transport.StdioMcpTransport;

/**
 * Cancellation through the three real layers at once: {@link McpTool}, {@link DefaultMcpClient} and
 * {@link StdioMcpTransport} over a process that takes a tool call and never answers it.
 *
 * <p>
 * {@code McpToolInterruptTest} holds the tool to a client that throws on an interrupt, and the transport's tests
 * interrupt it directly. Neither shows that the interrupt the tool sends is one the transport under the client hears.
 */
@DisabledOnOs(OS.WINDOWS)
class McpToolCancellationEndToEndTest {

    private static final long HANG_GUARD_SECONDS = 4;

    /** Answers the handshake, swallows the first tool call, and answers the second. */
    private static final String SERVER = "read -r l; "
            + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{\"tools\":{}}}}'; "
            + "read -r l; read -r l; read -r l; "
            + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"second\"}]}}'; "
            + "exec sleep 30";

    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "mcp-e2e-test-caller");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void stopCallers() {
        callers.shutdownNow();
    }

    @Test
    void cancellationEndsACallTheServerNeverAnswers_andTheNextCallStillWorks() throws Exception {
        // Twenty seconds of requestTimeout: only the cancellation can bring the first call back inside the guard.
        final StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", SERVER), Map.of(),
                Duration.ofSeconds(20));
        try (DefaultMcpClient client = new DefaultMcpClient(transport, "stalls");
                DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            client.initialize();
            final McpTool tool = new McpTool("stalls", McpToolSchema.of("slow", "Never answers", Map.of()), client);
            final ToolContext cancellable = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final Future<Object[]> pending = callers.submit(() -> {
                final ToolResult result = tool.execute(ToolInput.of(Map.of()), cancellable);
                return new Object[]{result, Thread.currentThread().isInterrupted()};
            });
            // Long enough for the request to be written and the wait for its answer to begin.
            Thread.sleep(400);
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final Object[] returned = pending.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            final ToolResult first = (ToolResult) returned[0];
            assertThat(first.isError()).isTrue();
            assertThat(first.getContent()).isEqualTo("MCP tool interrupted: USER_SIGINT");
            assertThat(returned[1]).isEqualTo(false);

            // The transport was given back, not left held or broken: another execution's call goes through.
            final Future<ToolResult> next = callers
                    .submit(() -> tool.execute(ToolInput.of(Map.of()), ToolContext.empty()));
            final ToolResult second = next.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            assertThat(second.isSuccess()).isTrue();
            assertThat(second.getContent()).isEqualTo("second");
        }
    }
}
