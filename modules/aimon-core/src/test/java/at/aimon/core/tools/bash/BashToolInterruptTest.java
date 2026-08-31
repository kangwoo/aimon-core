package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.Terminator;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * Verifies the cooperative interrupt contract on {@link BashTool}:
 * <ul>
 * <li>{@link BashTool#getInterruptBehavior()} returns {@link InterruptBehavior#THREAD_INTERRUPT}, which is what makes
 * the framework pre-register a {@code Thread.interrupt()} terminator on the tool thread before dispatch.
 * <li>That single terminator is the whole mechanism: the interrupt lands while the tool blocks inside
 * {@link VirtualShell#execute(ShellCommand, ExecutionOptions)}, and the shell answers it by killing the process and
 * throwing. Waking the waiter and killing the command are the same event.
 * <li>The tool registers <b>nothing</b> of its own with the {@link TerminatorRegistrar}. It used to register a
 * {@code future.cancel(true)} handle for the foreground future wrapper; the wrapper is gone, so there is nothing to
 * cancel and a registration would only advertise a teardown path that does not exist.
 * <li>When no registrar is present (cooperative / test callers) the tool still executes normally.
 * </ul>
 *
 * <p>
 * Because the terminator is the framework's, not the tool's, the interrupt test below registers it the same way
 * {@code SingleToolInvoker} does — on the thread that is about to call {@code execute}. Skipping that step would leave
 * a coordinator trip with nothing to act on, and the test would simply block until its own timeout.
 */
@DisplayName("BashTool interrupt wiring")
class BashToolInterruptTest {

    private BashTool bashTool;
    private RecordingShell shell;

    @BeforeEach
    void setUp() {
        shell = new RecordingShell();
        bashTool = new BashTool(shell);
    }

    @AfterEach
    void tearDown() {
        if (bashTool != null) {
            bashTool.shutdown();
        }
    }

    @Test
    @DisplayName("getInterruptBehavior() == THREAD_INTERRUPT")
    void declaresThreadInterrupt() {
        assertThat(bashTool.getInterruptBehavior()).isEqualTo(InterruptBehavior.THREAD_INTERRUPT);
    }

    @Test
    @DisplayName("a coordinator trip interrupts the blocked shell call and returns promptly")
    void threadInterruptAbortsTheInFlightCommand() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar()) {
            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal())
                    .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar).build();

            // The stub blocks until we release the latch so we can trip the coordinator mid-execution.
            shell.setDelayUntilLatch();

            final CompletableFuture<ToolResult> toolResult = CompletableFuture.supplyAsync(() -> {
                // Stand in for SingleToolInvoker, which pre-registers this terminator for THREAD_INTERRUPT tools
                // before calling execute(). BashTool registers nothing itself, so without this line the trip below
                // would have no effect at all.
                final Thread toolThread = Thread.currentThread();
                registrar.register(toolThread::interrupt);
                return bashTool.execute(ToolInput.of(Map.of("command", "sleep 60", "timeout", 30000)), context);
            });

            // Wait until the shell call is actually in progress; registration above already happened by then.
            assertThat(shell.awaitStarted(2, TimeUnit.SECONDS)).isTrue();

            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            // Must return well inside the 30s timeout and 60s sleep — the interrupt, not the deadline, ends this.
            final ToolResult result = toolResult.get(3, TimeUnit.SECONDS);
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("interrupted");

            // The delay latch was never released, confirming the shell threw out of its wait rather than running to
            // completion. A real shell would have destroyed the process tree on the same path.
            assertThat(shell.latchReleased()).isFalse();
        }
    }

    @Test
    @DisplayName("registers no terminator of its own — the framework's thread-interrupt handle is the only one")
    void registersNoTerminatorOfItsOwn() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar()) {
            final CountingRegistrar spy = new CountingRegistrar(registrar);
            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal())
                    .put(InterruptToolKeys.TERMINATOR_REGISTRAR, spy).build();

            shell.setNextOutput("ok");
            final ToolResult result = bashTool.execute(ToolInput.of(Map.of("command", "echo ok")), context);

            assertThat(result.isSuccess()).isTrue();
            // Zero, not one. The tool passes the registrar through untouched; the only terminator in play is the
            // thread-interrupt handle the invoker registers out-of-band, which never travels through this context's
            // registrar. Asserting on it here is what would catch a future re-introduction of a tool-side handle
            // that claims to be able to stop a command it cannot reach.
            assertThat(spy.registerCount.get()).isZero();
        }
    }

    @Test
    @DisplayName("executes normally when no registrar is present in context (cooperative/test callers)")
    void noRegistrarStillExecutes() {
        final ToolContext context = ToolContext.empty();
        shell.setNextOutput("hello");

        final ToolResult result = bashTool.execute(ToolInput.of(Map.of("command", "echo hello")), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("hello");
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * A {@link VirtualShell} that can be parked mid-call, mirroring {@code LocalShell}'s behaviour on interruption:
     * restore the interrupt flag, then throw a {@link ShellExecutionException} whose cause is the
     * {@link InterruptedException}. That cause is exactly what {@code BashTool} reads to tell an interrupt apart from
     * an ordinary failure to run the command, so a stub that threw a bare exception would let the tool's discriminator
     * rot untested.
     */
    private static final class RecordingShell implements VirtualShell {
        private String nextOutput = "";
        private CountDownLatch delayLatch;
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final AtomicBoolean latchReleased = new AtomicBoolean();

        void setNextOutput(String output) {
            this.nextOutput = output;
        }

        void setDelayUntilLatch() {
            this.delayLatch = new CountDownLatch(1);
        }

        boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        boolean latchReleased() {
            return latchReleased.get();
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                throws ShellExecutionException {
            startedLatch.countDown();
            if (delayLatch != null) {
                try {
                    delayLatch.await();
                    latchReleased.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ShellExecutionException("Interrupted", e, "", "", false);
                }
            }
            return new ShellCommandResult(0, nextOutput, "", Duration.ofMillis(1));
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
            /* Nothing to release. */
        }
    }

    private static final class CountingRegistrar implements TerminatorRegistrar {
        private final TerminatorRegistrar delegate;
        private final AtomicInteger registerCount = new AtomicInteger();

        CountingRegistrar(TerminatorRegistrar delegate) {
            this.delegate = delegate;
        }

        @Override
        public void register(Terminator terminator) {
            registerCount.incrementAndGet();
            delegate.register(terminator);
        }

        @Override
        public void unregister(Terminator terminator) {
            delegate.unregister(terminator);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
