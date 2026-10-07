package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * A hook's shell command stops when the execution it fired in is interrupted (EE-80).
 *
 * <p>
 * The shell here is the kind the item is about: its blocking call does not answer a thread interrupt, and the only
 * stop it honours is the command's own {@link ExecutionOptions#getCancellation() cancellation signal}. Before the
 * hook passed the execution's signal on, such a command ran on to its timeout after the user had interrupted.
 */
@DisplayName("declarative hook shell commands and the execution's cancellation signal (EE-80)")
class DeclarativeHookCancellationTest {

    /**
     * How long the fake command "runs" when nothing stops it. A hang guard only: that a command was stopped is read
     * from {@link #stoppedByCancellation}, not from how soon the hook returned.
     */
    private static final Duration COMMAND_RUNTIME = Duration.ofSeconds(10);
    private static final ShellAction ACTION = new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(30));

    private final ExecutorService hookThread = Executors.newSingleThreadExecutor();
    private final CountDownLatch commandStarted = new CountDownLatch(1);
    private final AtomicReference<ExecutionOptions> seenOptions = new AtomicReference<>();
    private final AtomicBoolean stoppedByCancellation = new AtomicBoolean();

    @AfterEach
    void stopHookThread() {
        hookThread.shutdownNow();
    }

    @ParameterizedTest(name = "failOpen={0}")
    @ValueSource(booleans = {false, true})
    void preTool_interruptedExecution_stopsTheCommandAndBlocks(boolean failOpen) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final DeclarativePreToolHook hook = new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()), null, null, Map.of(),
                    DeclarativeHookOptions.builder().failOpen(failOpen).build());

            final Future<HookResult> running = hookThread.submit(() -> hook.execute(preTool(coordinator.getSignal())));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

            // The command was stopped, not waited out.
            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            // And a cancelled guard is not "allow and continue", whatever failOpen says: the execution is ending.
            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow()).contains("execution cancelled").doesNotContain("s3cret")
                    .doesNotContain("failOpen");
        }
    }

    @Test
    void preTool_executionAlreadyCancelled_neverRunsTheCommand() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final DeclarativePreToolHook hook = new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()));

            final HookResult result = hookThread.submit(() -> hook.execute(preTool(coordinator.getSignal()))).get(3,
                    TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(seenOptions.get().getCancellation().isCancelled()).isTrue();
        }
    }

    @Test
    void advisoryEvent_interruptedExecution_stopsTheCommandAndProceeds() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final DeclarativePostToolHook hook = new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()));
            final PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("agent").hookRegistry(new DefaultHookRegistry())
                    .executionCancellation(coordinator.getSignal()).toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                    .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build();

            final Future<HookResult> running = hookThread.submit(() -> hook.execute(context));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            // Nothing to decide on an advisory event.
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_interruptedExecution_stopsTheCommandAndProceeds(ReportEvent event) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final VirtualShell shell = cancellationOnlyShell();

            final Future<HookResult> running = hookThread
                    .submit(() -> event.fire(new HostShellActionExecutor(shell), coordinator.getSignal()));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_executionAlreadyCancelled_stillRunsTheCommandUnbound(ReportEvent event) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            final HookResult result = event.fire(new HostShellActionExecutor(recordingShell()),
                    coordinator.getSignal());

            // The audit / cleanup command of a cancelled execution starts, and nothing can stop it but its timeout.
            assertThat(seenOptions.get()).as("the command was handed to the shell").isNotNull();
            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @Test
    void postTool_secondHookOfTheChain_stillRunsAfterAnInterruptDuringTheFirst() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            // One context for the whole chain, as DefaultHookExecutor runs it: the rule has to hold per hook.
            final PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("agent").hookRegistry(new DefaultHookRegistry())
                    .executionCancellation(coordinator.getSignal()).toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                    .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build();
            final DeclarativePostToolHook first = new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()));
            final DeclarativePostToolHook second = new DeclarativePostToolHook("audit", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(recordingShell()));

            final Future<HookResult> running = hookThread.submit(() -> first.execute(context));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            running.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);
            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            seenOptions.set(null);
            final HookResult audited = second.execute(context);

            assertThat(seenOptions.get()).as("the second hook's command was handed to the shell").isNotNull();
            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
            assertThat(audited.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest(name = "failOpen={0}")
    @ValueSource(booleans = {false, true})
    void preCompact_interruptedExecution_stopsTheCommandAndBlocks(boolean failOpen) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final DeclarativePreCompactHook hook = new DeclarativePreCompactHook("ops", ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()),
                    DeclarativeHookOptions.builder().failOpen(failOpen).build());

            final Future<HookResult> running = hookThread
                    .submit(() -> hook.execute(preCompact(coordinator.getSignal())));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow()).contains("execution cancelled");
        }
    }

    @Test
    void preCompact_executionAlreadyCancelled_neverRunsTheCommand() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final DeclarativePreCompactHook hook = new DeclarativePreCompactHook("ops", ACTION,
                    new HostShellActionExecutor(cancellationOnlyShell()));

            final HookResult result = hookThread.submit(() -> hook.execute(preCompact(coordinator.getSignal()))).get(3,
                    TimeUnit.SECONDS);

            // A gate, unlike the reports above: the context keeps handing out the tripped signal.
            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(seenOptions.get().getCancellation().isCancelled()).isTrue();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void realLocalShell_interruptedExecution_stopsTheCommandAndBlocksTheSameWay(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final java.nio.file.Path marker = tmp.resolve("started");
            final DeclarativePreToolHook hook = new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY,
                    new ShellAction("touch started; sleep 30", Duration.ofSeconds(60)),
                    new HostShellActionExecutor(new at.aimon.core.shell.impl.local.LocalShell(tmp)));

            final Future<HookResult> running = hookThread.submit(() -> hook.execute(preTool(coordinator.getSignal())));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!java.nio.file.Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(marker).exists();
            // No thread interrupt here: the only stop that reaches the shell is the execution's signal.
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(10, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow()).contains("execution cancelled");
        }
    }

    @Test
    void finishedCommand_leavesNoListenerOnTheExecutionsSignal() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final VirtualShell shell = mock(VirtualShell.class);
            when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
                seenOptions.set(invocation.getArgument(1));
                return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
            });
            final DeclarativePreToolHook hook = new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(shell));

            assertThat(hook.execute(preTool(coordinator.getSignal())).getStatus()).isEqualTo(HookStatus.SUCCESS);
            // The execution's signal outlives the command; an interrupt that arrives later must find nothing of it.
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
        }
    }

    @Test
    void eventOutsideAnyExecution_carriesNoCancellation() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            seenOptions.set(invocation.getArgument(1));
            return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
        });
        final OnSessionStartContext context = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("agent").hookRegistry(new DefaultHookRegistry()).build();

        new DeclarativeOnSessionStartHook("ops", ACTION, new HostShellActionExecutor(shell)).execute(context);

        assertThat(context.getExecutionCancellation()).isEmpty();
        assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /** The four events that report what already happened and were not tied to the execution's signal before. */
    private enum ReportEvent {
        ON_STOP {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal) {
                final Instant now = Instant.now();
                return new DeclarativeOnStopHook("ops", ACTION, shell).execute(OnStopContext.builder()
                        .executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                        .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).success(false)
                        .finalAnswer("Execution interrupted").metadata(ExecutionMetadata.builder().iterationCount(1)
                                .duration(Duration.ofMillis(50)).startTime(now.minusMillis(50)).endTime(now).build())
                        .build());
            }
        },
        POST_COMPACT {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal) {
                final Instant now = Instant.now();
                return new DeclarativePostCompactHook("ops", ACTION, shell)
                        .execute(PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .trigger(CompactionTrigger.AUTO)
                                .compactionMetadata(CompactionMetadata.builder().trigger(CompactionTrigger.AUTO)
                                        .startedAt(now).completedAt(now).build())
                                .compactSummary("summary").transcriptBuffer(new TranscriptBuffer(SessionId.generate()))
                                .build());
            }
        },
        SUBAGENT_START {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal) {
                return new DeclarativeSubagentStartHook("ops", ACTION, shell)
                        .execute(SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").goal("map the module graph").build());
            }
        },
        SUBAGENT_STOP {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal) {
                return new DeclarativeSubagentStopHook("ops", ACTION, shell)
                        .execute(SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").success(false).build());
            }
        };

        abstract HookResult fire(ShellActionExecutor shell, CancellationSignal signal);
    }

    /** A shell that finishes at once and records the options it was called with. */
    private VirtualShell recordingShell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            seenOptions.set(invocation.getArgument(1));
            return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
        });
        return shell;
    }

    private static PreCompactContext preCompact(CancellationSignal signal) {
        return PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).trigger(CompactionTrigger.AUTO)
                .sessionIdValue("conv-1").messageCount(42).estimatedTokens(120_000).build();
    }

    /**
     * A shell that ignores thread interrupts and stops only when the command's cancellation signal trips, as a
     * shell whose blocking call is a remote request does.
     */
    private VirtualShell cancellationOnlyShell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            final ExecutionOptions options = invocation.getArgument(1);
            seenOptions.set(options);
            if (options.getCancellation().isCancelled()) {
                throw new ShellCancelledException("Cancelled before the process started: guard.sh --token s3cret");
            }
            final CountDownLatch stopped = new CountDownLatch(1);
            options.getCancellation().onCancel(stopped::countDown);
            commandStarted.countDown();
            if (awaitIgnoringInterrupts(stopped)) {
                stoppedByCancellation.set(true);
                throw new ShellCancelledException("Process cancelled: guard.sh --token s3cret");
            }
            return new ShellCommandResult(0, "", "", COMMAND_RUNTIME);
        });
        return shell;
    }

    private static boolean awaitIgnoringInterrupts(CountDownLatch latch) {
        final long deadline = System.nanoTime() + COMMAND_RUNTIME.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static PreToolContext preTool(CancellationSignal signal) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build();
    }
}
