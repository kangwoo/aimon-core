package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.Terminator;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * Verifies the cooperative interrupt contract on {@link BashTool}:
 * <ul>
 * <li>{@link BashTool#getInterruptBehavior()} returns {@link InterruptBehavior#THREAD_INTERRUPT}, which is what makes
 * the framework pre-register a {@code Thread.interrupt()} terminator on the tool thread before dispatch.
 * <li>That terminator is the only one: the interrupt lands while the tool blocks inside
 * {@link VirtualShell#execute(ShellCommand, ExecutionOptions)}, and a shell that reacts to it answers by killing the
 * process and throwing. Waking the waiter and killing the command are the same event.
 * <li>The stop also travels on the command's own cancellation signal ({@code ExecutionOptions.getCancellation()}),
 * tripped by a listener the tool keeps on the execution's signal for the length of the shell call (EE-54). That is
 * what stops a shell whose blocking call does not answer a thread interrupt, and either road ends in the same
 * result.
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
        bashTool = new BashTool(null);
    }

    @AfterEach
    void tearDown() {
        if (bashTool != null) {
            bashTool.shutdown();
        }
    }

    /** A context builder carrying the execution environment whose shell is the recording shell. */
    private ToolContext.Builder shellContextBuilder() {
        return TestExecutionEnvironments.contextBuilder(TestExecutionEnvironments.ofShell(shell));
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
            final ToolContext context = shellContextBuilder()
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
            final ToolContext context = shellContextBuilder()
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
        final ToolContext context = shellContextBuilder().build();
        shell.setNextOutput("hello");

        final ToolResult result = bashTool.execute(ToolInput.of(Map.of("command", "echo hello")), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("hello");
    }

    // ============================================================
    // EE-54 — the stop also travels on the shell's cancellation signal
    // ============================================================

    @Test
    @DisplayName("a shell that ignores thread interrupts is stopped through the command's cancellation signal")
    void interruptReachesAShellThatIgnoresThreadInterrupts() throws Exception {
        final InterruptDeafShell deaf = new InterruptDeafShell();
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar()) {
            final ToolContext context = TestExecutionEnvironments
                    .contextBuilder(TestExecutionEnvironments.ofShell(deaf))
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal())
                    .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar).build();

            final CompletableFuture<ToolResult> toolResult = CompletableFuture.supplyAsync(() -> {
                // Same stand-in for SingleToolInvoker as above: the thread interrupt is still sent, and this shell
                // does not react to it.
                final Thread toolThread = Thread.currentThread();
                registrar.register(toolThread::interrupt);
                return bashTool.execute(ToolInput.of(Map.of("command", "sleep 60", "timeout", 30000)), context);
            });
            try {
                assertThat(deaf.awaitStarted(2, TimeUnit.SECONDS)).isTrue();

                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

                final ToolResult result = toolResult.get(3, TimeUnit.SECONDS);
                assertThat(deaf.sawCancellation()).as("the shell was told to stop through its own signal").isTrue();
                assertThat(result.isError()).isTrue();
                // The same words an interrupted LocalShell command is reported with: which of the two stops got
                // there first is not something the model should be able to tell.
                assertThat(result.getContent()).isEqualTo("Bash command interrupted: USER_SIGINT");
            } finally {
                // Without the signal nothing ever stops this shell; let the parked thread go.
                deaf.abandon();
            }
        }
    }

    @Test
    @DisplayName("a signal tripped before the call reaches the shell already cancelled, so the command is not started")
    void alreadyTrippedSignalArrivesCancelled() {
        final InterruptDeafShell deaf = new InterruptDeafShell();
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final ToolContext context = TestExecutionEnvironments
                    .contextBuilder(TestExecutionEnvironments.ofShell(deaf))
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();
            // Nothing would end the command if the signal did not arrive; do not let that hang the test.
            deaf.abandon();

            final ToolResult result = bashTool.execute(ToolInput.of(Map.of("command", "sleep 60")), context);

            assertThat(deaf.cancelledOnEntry()).isTrue();
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Bash command interrupted: USER_SIGINT");
        }
    }

    @Test
    @DisplayName("the listener on the execution's signal is removed when the command ends — normally or by failing")
    void listenerDoesNotOutliveTheCommand() {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final CountingSignal signal = new CountingSignal(coordinator.getSignal());
            final ToolContext context = shellContextBuilder().put(InterruptToolKeys.CANCELLATION_SIGNAL, signal)
                    .build();

            shell.setNextOutput("ok");
            assertThat(bashTool.execute(ToolInput.of(Map.of("command", "echo ok")), context).isSuccess()).isTrue();
            assertThat(signal.registered.get()).isEqualTo(1);
            assertThat(signal.live.get()).as("nothing of a finished command stays on the execution's signal").isZero();

            shell.failNext();
            assertThat(bashTool.execute(ToolInput.of(Map.of("command", "boom")), context).isError()).isTrue();
            assertThat(signal.registered.get()).isEqualTo(2);
            assertThat(signal.live.get()).isZero();

            // An interrupt after both commands ended has nothing left to cancel.
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            assertThat(shell.cancellationsSeen()).isZero();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("LocalShell: an interrupted foreground command is killed and reported in the same words as before")
    void localShellInterruptOutcomeIsUnchanged(@TempDir Path dir) throws Exception {
        final Path pidFile = dir.resolve("pid");
        try (LocalShell local = new LocalShell(dir);
                DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar()) {
            final ToolContext context = TestExecutionEnvironments
                    .contextBuilder(TestExecutionEnvironments.ofShell(local))
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal())
                    .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar).build();

            final CompletableFuture<ToolResult> toolResult = CompletableFuture.supplyAsync(() -> {
                final Thread toolThread = Thread.currentThread();
                registrar.register(toolThread::interrupt);
                return bashTool.execute(ToolInput
                        .of(Map.of("command", "echo partial; echo $$ > " + pidFile + "; sleep 60", "timeout", 30000)),
                        context);
            });
            final long pid = awaitPid(pidFile);

            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            final ToolResult result = toolResult.get(5, TimeUnit.SECONDS);
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Bash command interrupted: USER_SIGINT");
            awaitDead(pid);
        }
    }

    private static long awaitPid(Path pidFile) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the command never wrote its pid");
    }

    private static void awaitDead(long pid) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("process " + pid + " is still alive");
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
        private final AtomicInteger cancellationsSeen = new AtomicInteger();
        private boolean failNext;

        void failNext() {
            this.failNext = true;
        }

        int cancellationsSeen() {
            return cancellationsSeen.get();
        }

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
            // Deliberately never removed, unlike a real shell: a signal that still reaches this listener after the
            // command ended is one the tool left connected to the execution's signal.
            options.getCancellation().onCancel(cancellationsSeen::incrementAndGet);
            if (failNext) {
                failNext = false;
                throw new ShellExecutionException("no such shell");
            }
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

    /**
     * The shell EE-54 is about: its blocking call does not react to {@link Thread#interrupt()} — a remote call that
     * swallows the interrupt, or one whose remote command would keep running anyway — and it stops only when the
     * command's own signal is tripped, answering the way the contract says with a {@link ShellCancelledException}.
     */
    private static final class InterruptDeafShell implements VirtualShell {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final CountDownLatch abandoned = new CountDownLatch(1);
        private final AtomicBoolean sawCancellation = new AtomicBoolean();
        private final AtomicBoolean cancelledOnEntry = new AtomicBoolean();

        boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return started.await(timeout, unit);
        }

        boolean sawCancellation() {
            return sawCancellation.get();
        }

        boolean cancelledOnEntry() {
            return cancelledOnEntry.get();
        }

        void abandon() {
            abandoned.countDown();
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                throws ShellExecutionException {
            if (options.getCancellation().isCancelled()) {
                cancelledOnEntry.set(true);
                throw new ShellCancelledException("Cancelled before the process started: " + command.asString());
            }
            final var registration = options.getCancellation().onCancel(() -> {
                sawCancellation.set(true);
                cancelled.countDown();
            });
            started.countDown();
            boolean interrupted = false;
            try {
                while (cancelled.getCount() > 0 && abandoned.getCount() > 0) {
                    try {
                        if (cancelled.await(20, TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        // Deaf to it: note it, keep waiting.
                        interrupted = true;
                    }
                }
            } finally {
                registration.remove();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (cancelled.getCount() == 0) {
                throw new ShellCancelledException("Process cancelled: " + command.asString(), "partial", "", false);
            }
            return new ShellCommandResult(0, "abandoned", "", Duration.ofMillis(1));
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return feature == ShellFeature.CANCELLATION;
        }

        @Override
        public void close() {
            /* Nothing to release. */
        }
    }

    /** Counts the listeners that are on the wrapped signal right now. */
    private static final class CountingSignal implements CancellationSignal {
        private final CancellationSignal delegate;
        private final AtomicInteger registered = new AtomicInteger();
        private final AtomicInteger live = new AtomicInteger();

        CountingSignal(CancellationSignal delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public Optional<InterruptReason> getReason() {
            return delegate.getReason();
        }

        @Override
        public void checkpoint() {
            delegate.checkpoint();
        }

        @Override
        public Registration onCancel(Runnable listener) {
            registered.incrementAndGet();
            live.incrementAndGet();
            final Registration registration = delegate.onCancel(listener);
            final AtomicBoolean removed = new AtomicBoolean();
            return () -> {
                registration.remove();
                if (removed.compareAndSet(false, true)) {
                    live.decrementAndGet();
                }
            };
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
