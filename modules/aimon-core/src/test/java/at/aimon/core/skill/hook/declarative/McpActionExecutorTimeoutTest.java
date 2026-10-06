package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.mcp.DefaultMcpClient;
import at.aimon.core.mcp.McpCallResult;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.exception.McpTransportException;
import at.aimon.core.mcp.transport.StdioMcpTransport;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * The {@code timeout} of an {@code mcp} hook action bounds the call.
 *
 * <p>
 * {@code McpClient.callTool} takes no timeout, so the executor used to wait for as long as the client did — the
 * server's {@code requestTimeout} (30s by default), or forever for a client that never returns — and the declared
 * timeout only widened the hook executor's outer net.
 */
@DisplayName("McpActionExecutor enforces the action's timeout")
class McpActionExecutorTimeoutTest {

    private static final Duration ACTION_TIMEOUT = Duration.ofMillis(200);

    private final CountDownLatch never = new CountDownLatch(1);
    private final AtomicBoolean callInterrupted = new AtomicBoolean();
    private final McpClientManager servers = mock(McpClientManager.class);
    private final McpClient client = mock(McpClient.class);
    private final McpActionExecutor executor = new McpActionExecutor(servers, new ObjectMapper());

    McpActionExecutorTimeoutTest() {
        when(client.isConnected()).thenReturn(true);
        when(servers.getClient("policy")).thenReturn(Optional.of(client));
    }

    @AfterEach
    void clearInterruptFlag() {
        never.countDown();
        // A test that leaves the flag set would fail the next one on this thread for the wrong reason.
        Thread.interrupted();
    }

    /** A call that never returns by itself; like the stdio transport it ends when its thread is interrupted. */
    private void callNeverReturns() {
        when(client.callTool(any(), any())).thenAnswer(invocation -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                callInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw new McpTransportException("Request interrupted for method 'tools/call'", e);
            }
            return McpCallResult.success("");
        });
    }

    private static McpToolAction action(Duration timeout) {
        return McpToolAction.builder().serverName("policy").toolName("evaluate").timeout(timeout).build();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void callThatNeverReturns_endsAtTheActionTimeout_asATimeout() {
        callNeverReturns();
        final long start = System.nanoTime();

        final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(outcome.getVerdict()).isEmpty();
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
        assertThat(outcome.getUnrun().orElseThrow().unrunReason()).contains("200ms");
        // The request itself was ended, not left waiting behind the caller's back.
        assertThat(callInterrupted).isTrue();
        // The interrupt was the executor's own: it must not leak to the caller, who goes on to run other hooks.
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void advisoryReading_ofACallThatNeverReturns_isSuccessAfterTheTimeout() {
        callNeverReturns();

        final HookResult result = executor.run(action(ACTION_TIMEOUT), ToolInput.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void callThatAnswersInTime_isAVerdict_andLeavesNoInterruptBehind() throws Exception {
        when(client.callTool(any(), any()))
                .thenReturn(McpCallResult.success("{\"decision\":\"deny\",\"reason\":\"no\"}"));

        final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of());
        // Well past the deadline: a timer that was not disarmed would interrupt this thread now.
        Thread.sleep(ACTION_TIMEOUT.toMillis() * 3);

        assertThat(outcome.getVerdict().orElseThrow().isBlocked()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void callInterruptedFromOutside_isCancelled_notATimeout_andKeepsTheFlag() {
        callNeverReturns();
        final Thread caller = Thread.currentThread();
        final Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
            caller.interrupt();
        });
        interrupter.start();

        final ActionCallOutcome outcome = executor.attempt(action(Duration.ofSeconds(30)), ToolInput.of(), Map.of());

        // The execution is being cancelled (the hook executor's net, or the turn): that is not "the server was slow",
        // and failOpen does not open it.
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.CANCELLED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    // --- the deadline ends with the call on every exit path -------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void callThatThrowsAnError_leavesNoDeadlineBehind_toInterruptTheThreadLater() throws Exception {
        // An Error is not a RuntimeException: it leaves attempt() through no catch. The thread goes back to the hook
        // pool and runs something else; a deadline still armed would interrupt that.
        for (Error thrown : new Error[]{new NoClassDefFoundError("at/aimon/gone/Type"), new StackOverflowError()}) {
            org.mockito.Mockito.doThrow(thrown).when(client).callTool(any(), any());

            assertThatThrownBy(() -> executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of()))
                    .isSameAs(thrown);

            assertThat(interruptedWithin(ACTION_TIMEOUT.multipliedBy(3))).as(thrown.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void callThatThrowsAnErrorAfterTheDeadlineFired_doesNotLeaveTheExecutorsInterruptSet() {
        // The deadline's interrupt is the executor's own. It is taken back on this exit path too.
        when(client.callTool(any(), any())).thenAnswer(invocation -> {
            spinPast(ACTION_TIMEOUT.multipliedBy(2));
            throw new NoClassDefFoundError("at/aimon/gone/Type");
        });

        assertThatThrownBy(() -> executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of()))
                .isInstanceOf(NoClassDefFoundError.class);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void interruptThatWasSetBeforeTheCall_isStillSetAfterIt_evenWhenTheDeadlineFired() {
        // The flag is left as it was found: taking back the deadline's interrupt must not take the caller's with it.
        when(client.callTool(any(), any())).thenAnswer(invocation -> {
            spinPast(ACTION_TIMEOUT.multipliedBy(2));
            return McpCallResult.success("");
        });
        Thread.currentThread().interrupt();

        executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of());

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /** Waits without answering an interrupt, like a client that ignores one. */
    private static void spinPast(Duration duration) {
        final long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /** Whether an interrupt arrives on this thread within the window; the flag is consumed. */
    private static boolean interruptedWithin(Duration window) {
        try {
            Thread.sleep(window.toMillis());
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void preToolGuard_blocksAtTheActionTimeout_withTheGuardsOwnReason_andFailOpenPasses() throws Exception {
        callNeverReturns();
        final HookRegistry registry = new DefaultHookRegistry();
        final DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder().build();
        try {
            registry.register(HookEventType.PRE_TOOL,
                    new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, action(ACTION_TIMEOUT),
                            NoOpShellActionExecutor.INSTANCE, null, executor, Map.of(), DeclarativeHookOptions.none()));
            final long start = System.nanoTime();

            final HookResult blocked = HookResult.merge(manager.executePreTool(preToolContext(registry)));

            // Well inside the 30s outer net of the shipped policy: the action's own deadline answered.
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
            assertThat(blocked.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(blocked.getFeedback().orElseThrow()).contains("could not get a verdict from its mcp call")
                    .contains("timed out");

            final HookRegistry open = new DefaultHookRegistry();
            open.register(HookEventType.PRE_TOOL,
                    new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, action(ACTION_TIMEOUT),
                            NoOpShellActionExecutor.INSTANCE, null, executor, Map.of(),
                            DeclarativeHookOptions.builder().failOpen(true).build()));

            assertThat(HookResult.merge(manager.executePreTool(preToolContext(open))).getStatus())
                    .isEqualTo(HookStatus.SUCCESS);
        } finally {
            manager.close();
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void realStdioServerThatNeverAnswers_isCutAtTheActionTimeout_notAtItsRequestTimeout() throws Exception {
        // Answers initialize, then swallows every request without replying. requestTimeout is the server's own bound
        // (30s); the action asks for 200ms, and the smaller one wins.
        final String script = "read -r line; printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                + "\"serverInfo\":{\"name\":\"silent\",\"version\":\"1\"}}}'; cat > /dev/null";
        try (StdioMcpTransport transport = new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(),
                Duration.ofSeconds(30)); DefaultMcpClient real = new DefaultMcpClient(transport, "policy")) {
            real.initialize();
            when(servers.getClient("policy")).thenReturn(Optional.of(real));
            final long start = System.nanoTime();

            final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of());

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            // The transport is not stuck behind the abandoned request: the next call gets its own turn (and its own
            // timeout) at once.
            final long second = System.nanoTime();
            assertThat(executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of()).getUnrun().orElseThrow()
                    .getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - second)).isLessThan(Duration.ofSeconds(5));
        }
    }

    private static PreToolContext preToolContext(HookRegistry registry) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build();
    }
}
