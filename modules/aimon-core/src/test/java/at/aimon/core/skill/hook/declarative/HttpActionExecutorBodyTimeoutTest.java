package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * The {@code timeout} of an {@code http} hook action bounds the whole exchange, the response body included.
 *
 * <p>
 * The JDK's {@code HttpRequest.timeout} stops counting when the response headers arrive. An endpoint that sent its
 * headers and then stalled, or dripped its body a byte at a time, held the call until the hook executor's outer net
 * (30s at least, for a 300ms action). The endpoint here is a raw loopback socket, because the point is what happens
 * between the header block and the last body byte.
 */
@DisplayName("HttpActionExecutor enforces the action's timeout over the response body")
class HttpActionExecutorBodyTimeoutTest {

    private static final Duration ACTION_TIMEOUT = Duration.ofMillis(300);
    /** How long the endpoint keeps a connection it was not hung up on: long enough to tell 300ms from "never". */
    private static final Duration SERVER_PATIENCE = Duration.ofSeconds(6);
    private static final String HEADERS = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
            + "Content-Length: 4096\r\nConnection: close\r\n\r\n";

    private ServerSocket endpoint;
    private Thread acceptor;
    /** Whether the body is sent a byte every 50ms (under any size cap, never finishing in time) or not at all. */
    private volatile boolean drip;
    private final CountDownLatch headersSent = new CountDownLatch(1);
    private final CountDownLatch clientHungUp = new CountDownLatch(1);
    private final HttpActionExecutor executor = HttpActionExecutor.createDefault();

    @BeforeEach
    void startEndpoint() throws IOException {
        endpoint = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serveOne, "body-timeout-test-endpoint");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopEndpoint() throws IOException {
        endpoint.close();
        acceptor.interrupt();
        // A test that leaves the flag set would fail the next one on this thread for the wrong reason.
        Thread.interrupted();
    }

    private void serveOne() {
        try (Socket socket = endpoint.accept()) {
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            readRequestHead(in);
            out.write(HEADERS.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            headersSent.countDown();
            final long giveUpAt = System.nanoTime() + SERVER_PATIENCE.toNanos();
            if (drip) {
                while (System.nanoTime() < giveUpAt) {
                    out.write(' ');
                    out.flush();
                    Thread.sleep(50);
                }
            } else {
                socket.setSoTimeout((int) SERVER_PATIENCE.toMillis());
                if (in.read() < 0) {
                    clientHungUp.countDown();
                }
            }
        } catch (java.net.SocketTimeoutException stillConnected) {
            // The client never hung up: the request was left running.
        } catch (IOException writeFailed) {
            // The client closed the connection under a write or a read: the request was cancelled.
            clientHungUp.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return;
            }
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : 0;
        }
    }

    private HttpAction action(Duration timeout) {
        return HttpAction.builder().url("http://127.0.0.1:" + endpoint.getLocalPort() + "/policy").timeout(timeout)
                .build();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void endpointThatSendsHeadersAndStalls_endsAtTheActionTimeout_asATimeout_andTheRequestIsCancelled()
            throws Exception {
        final long start = System.nanoTime();

        final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of(), Map.of());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(headersSent.getCount()).as("the headers had arrived: this is the body's wait").isZero();
        assertThat(outcome.getVerdict()).isEmpty();
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
        assertThat(outcome.getUnrun().orElseThrow().unrunReason()).contains("300ms");
        // The deadline's interrupt is the executor's own.
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        // Cancelled, not abandoned: the connection is closed under the endpoint well before it gives up by itself.
        assertThat(clientHungUp.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void endpointThatDripsItsBody_endsAtTheActionTimeout_asATimeout_andTheRequestIsCancelled() throws Exception {
        drip = true;
        final long start = System.nanoTime();

        final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of(), Map.of());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(clientHungUp.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void stalledBodyInterruptedFromOutside_isCancelled_notATimeout_andKeepsTheFlag() throws Exception {
        final Thread caller = Thread.currentThread();
        final Thread interrupter = new Thread(() -> {
            try {
                headersSent.await();
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
            caller.interrupt();
        });
        interrupter.start();

        final ActionCallOutcome outcome = executor.attempt(action(Duration.ofSeconds(30)), ToolInput.of(), Map.of(),
                Map.of());

        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.CANCELLED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(clientHungUp.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void callThatAnswersInTime_leavesNoDeadlineBehind() throws Exception {
        // Nothing listens on the port any more: the call fails at once, long before its deadline.
        endpoint.close();

        final ActionCallOutcome outcome = executor.attempt(action(ACTION_TIMEOUT), ToolInput.of(), Map.of(), Map.of());
        // Well past the deadline: a timer that was not disarmed would interrupt this thread now.
        Thread.sleep(ACTION_TIMEOUT.toMillis() * 3);

        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.CALL_FAILED);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void preToolGuard_onAStalledBody_blocksAtTheActionTimeout_withTheGuardsOwnReason() throws Exception {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY,
                action(ACTION_TIMEOUT), NoOpShellActionExecutor.INSTANCE, executor, null, Map.of()));
        try (DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder().build()) {
            final long start = System.nanoTime();

            final HookResult blocked = HookResult.merge(manager.executePreTool(PreToolContext.builder()
                    .executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                    .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build()));

            // The action's own deadline answered, not the 30s outer net: the reason is the guard's, with its cause.
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(blocked.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(blocked.getFeedback().orElseThrow()).contains("could not get a verdict from its http call")
                    .contains("timed out");
        }
    }
}
