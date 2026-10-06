package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.mcp.exception.McpTransportException;

/**
 * A cancelled execution ends an MCP tool call that is still waiting for its server, instead of waiting out the
 * server's {@code requestTimeout}.
 *
 * <p>
 * The client here blocks the way the stdio transport does: until it is interrupted, at which point it sets the flag
 * again and throws. Every call runs on a worker thread and is awaited with a bound of its own.
 */
class McpToolInterruptTest {

    private static final long HANG_GUARD_SECONDS = 4;

    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "mcp-tool-interrupt-test-caller");
        thread.setDaemon(true);
        return thread;
    });

    private McpClient mcpClient;
    private McpTool tool;

    @BeforeEach
    void setUp() {
        mcpClient = mock(McpClient.class);
        when(mcpClient.getServerName()).thenReturn("server");
        when(mcpClient.isConnected()).thenReturn(true);
        tool = new McpTool("server", McpToolSchema.of("slow", "A tool whose server may stall", Map.of()), mcpClient);
    }

    @AfterEach
    void stopCallers() {
        callers.shutdownNow();
    }

    @Test
    void declaresThatItListensToTheSignalItself() {
        // Not THREAD_INTERRUPT: the executor registers no terminator for this tool. The tool sends its own interrupt.
        assertThat(tool.getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
    }

    @Test
    void aCallOfAnExecutionAlreadyCancelledIsNotMade() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            final ToolResult result = tool.execute(ToolInput.of(Map.of()), contextOf(coordinator));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("interrupted").contains("USER_SIGINT");
            verify(mcpClient, never()).callTool(anyString(), any());
        }
    }

    @Test
    void cancellationEndsACallThatIsWaitingForItsServer() throws Exception {
        final CountDownLatch inCall = stallUntilInterrupted();
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final Future<Returned> pending = callers.submit(() -> call(contextOf(coordinator)));
            assertThat(inCall.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();

            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final Returned returned = pending.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            assertThat(returned.result.isError()).isTrue();
            assertThat(returned.result.getContent()).contains("interrupted").contains("USER_SIGINT");
            // The interrupt was this call's own way of ending its wait, on a thread that may be a shared worker: it
            // must not be left for whatever that thread runs next.
            assertThat(returned.interruptedOnReturn).isFalse();
        }
    }

    @Test
    void cancellationAfterTheCallReturnedDoesNotInterruptItsThread() throws Exception {
        when(mcpClient.callTool(anyString(), any())).thenReturn(McpCallResult.success("done"));
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final CountDownLatch returnedLatch = new CountDownLatch(1);
            final CountDownLatch tripped = new CountDownLatch(1);
            final Future<Boolean> interruptedLater = callers.submit(() -> {
                final ToolResult result = tool.execute(ToolInput.of(Map.of()), contextOf(coordinator));
                assertThat(result.isSuccess()).isTrue();
                returnedLatch.countDown();
                tripped.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                return Thread.currentThread().isInterrupted();
            });
            assertThat(returnedLatch.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();

            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            tripped.countDown();

            assertThat(interruptedLater.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void anInterruptThatIsNotTheCancellationIsLeftSet() throws Exception {
        final CountDownLatch inCall = stallUntilInterrupted();
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final Thread[] caller = new Thread[1];
            final Future<Returned> pending = callers.submit(() -> {
                caller[0] = Thread.currentThread();
                return call(contextOf(coordinator));
            });
            assertThat(inCall.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();

            caller[0].interrupt();
            final Returned returned = pending.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);

            // Whoever interrupted the thread is waiting to see it, and the signal never tripped.
            assertThat(returned.result.isError()).isTrue();
            assertThat(returned.result.getContent()).contains("MCP tool execution failed");
            assertThat(returned.interruptedOnReturn).isTrue();
        }
    }

    @Test
    void aCallWithoutASignalIsMadeAsBefore() {
        when(mcpClient.callTool(anyString(), any())).thenReturn(McpCallResult.success("done"));

        final ToolResult result = tool.execute(ToolInput.of(Map.of()), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("done");
    }

    /** Makes {@code callTool} wait the way the stdio transport waits for a response: until interrupted. */
    private CountDownLatch stallUntilInterrupted() {
        final CountDownLatch inCall = new CountDownLatch(1);
        when(mcpClient.callTool(anyString(), any())).thenAnswer(invocation -> {
            inCall.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpTransportException("Request interrupted for method 'tools/call'", e);
            }
            throw new AssertionError("unreachable");
        });
        return inCall;
    }

    private Returned call(ToolContext context) {
        final ToolResult result = tool.execute(ToolInput.of(Map.of()), context);
        return new Returned(result, Thread.currentThread().isInterrupted());
    }

    private static ToolContext contextOf(DefaultInterruptCoordinator coordinator) {
        return ToolContext.builder().put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();
    }

    private static final class Returned {

        private final ToolResult result;
        private final boolean interruptedOnReturn;

        private Returned(ToolResult result, boolean interruptedOnReturn) {
            this.result = result;
            this.interruptedOnReturn = interruptedOnReturn;
        }
    }
}
