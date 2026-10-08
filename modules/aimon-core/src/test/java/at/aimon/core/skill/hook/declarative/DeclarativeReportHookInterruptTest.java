package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.DefaultHookExecutor;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * The one-sentence rule (EE-98): an interrupt of the execution always stops a gate hook's shell command and never
 * stops a report hook's. It is decided by the event, not by a setting, and it holds on both roads an interrupt
 * travels — the execution's cancellation signal, and an interrupt of the thread that fired the hook.
 *
 * <p>
 * The shell answers both stops, as {@code LocalShell} does: its command ends when its cancellation trips, when its
 * thread is interrupted, or when the test lets it finish, and it records which of the three ended it. Whether a
 * command was stopped or left alone is read from that record, never from how soon the hook returned.
 */
@DisplayName("a report hook's command runs through an interrupt, a gate hook's is stopped by it (EE-98)")
class DeclarativeReportHookInterruptTest {

    /** A hang guard only; nothing is asserted on elapsed time. */
    private static final Duration HANG_GUARD = Duration.ofSeconds(30);
    private static final ShellAction ACTION = new ShellAction("cleanup.sh", Duration.ofSeconds(60));

    private final ExecutorService hookThread = Executors.newSingleThreadExecutor();
    private final CountDownLatch commandStarted = new CountDownLatch(1);
    private final CountDownLatch commandMayFinish = new CountDownLatch(1);
    private final CountDownLatch commandEnded = new CountDownLatch(1);
    private final AtomicReference<ExecutionOptions> seenOptions = new AtomicReference<>();
    private final AtomicBoolean stoppedByCancellation = new AtomicBoolean();
    private final AtomicBoolean stoppedByThreadInterrupt = new AtomicBoolean();
    private final AtomicBoolean ranToCompletion = new AtomicBoolean();

    @AfterEach
    void stopHookThread() {
        commandMayFinish.countDown();
        hookThread.shutdownNow();
    }

    // --- the signal road ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_interruptWhileTheCommandRuns_leavesItRunningToItsEnd(ReportEvent event) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final Firing<?> firing = event.prepare(new HostShellActionExecutor(shell()), coordinator.getSignal());

            final Future<HookResult> running = hookThread.submit(firing::direct);
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            // Listeners run inside requestInterrupt, so a command tied to the signal would have been stopped by now.
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
            assertThat(stoppedByCancellation).isFalse();
            assertThat(running).as("the hook is still waiting for its command").isNotDone();
            commandMayFinish.countDown();
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(stoppedByCancellation).isFalse();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_executionAlreadyCancelled_runsTheCommandTheSameWay(ReportEvent event) throws Exception {
        // The other side of what used to be a race: the interrupt came first. The command starts and finishes too.
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final Firing<?> firing = event.prepare(new HostShellActionExecutor(shell()), coordinator.getSignal());

            final Future<HookResult> running = hookThread.submit(firing::direct);
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the command was handed to the shell").isTrue();
            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
            commandMayFinish.countDown();
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @Test
    void postTool_secondHookOfTheChain_alsoRunsToItsEndAfterAnInterruptDuringTheFirst() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            // One context for the whole chain, as DefaultHookExecutor runs it: the rule has to hold per hook.
            final PostToolContext context = postTool(coordinator.getSignal());
            final AtomicBoolean auditRan = new AtomicBoolean();
            final VirtualShell auditShell = mock(VirtualShell.class);
            when(auditShell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
                auditRan.set(!invocation.<ExecutionOptions>getArgument(1).getCancellation().isCancelled());
                return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
            });
            final DeclarativePostToolHook first = new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(shell()));
            final DeclarativePostToolHook second = new DeclarativePostToolHook("audit", NameOnlyPredicate.ANY, ACTION,
                    new HostShellActionExecutor(auditShell));

            final Future<HookResult> running = hookThread.submit(() -> first.execute(context));
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            commandMayFinish.countDown();
            running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            final HookResult audited = second.execute(context);

            assertThat(ranToCompletion).as("the first hook's command finished on its own").isTrue();
            assertThat(auditRan).as("the second hook's command ran, handed no tripped cancellation").isTrue();
            assertThat(audited.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest(name = "{0}, failOpen={1}")
    @MethodSource("gateEventsWithAndWithoutFailOpen")
    void gateEvent_interruptWhileTheCommandRuns_stopsItAndBlocks(GateEvent event, boolean failOpen) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final Firing<?> firing = event.prepare(new HostShellActionExecutor(shell()), coordinator.getSignal(),
                    DeclarativeHookOptions.builder().failOpen(failOpen).build());

            final Future<HookResult> running = hookThread.submit(firing::direct);
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the guard's command was stopped").isTrue();
            assertThat(ranToCompletion).isFalse();
            // Not "allow and continue", whatever failOpen says: the execution is ending.
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.getFeedback().orElseThrow()).contains("execution cancelled");
        }
    }

    static Stream<Arguments> gateEventsWithAndWithoutFailOpen() {
        return Stream.of(GateEvent.values())
                .flatMap(event -> Stream.of(Arguments.of(event, false), Arguments.of(event, true)));
    }

    // --- the thread road ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_interruptOfTheFiringThread_leavesTheCommandRunningToItsEnd(ReportEvent event) throws Exception {
        // What Task.stop does to a background fork's worker. No signal is in play here, only the thread interrupt,
        // and the shell is one that would answer it.
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final Firing<?> firing = event.prepare(new HostShellActionExecutor(shell()), null);
        final CountDownLatch executorAsked = new CountDownLatch(1);
        final AtomicReference<Thread> firingThread = new AtomicReference<>();
        final AtomicBoolean interruptHandedBack = new AtomicBoolean();
        try {
            final Future<HookResult> running = hookThread.submit(() -> {
                firingThread.set(Thread.currentThread());
                try {
                    return firing.through(executor, executorAsked);
                } finally {
                    interruptHandedBack.set(Thread.interrupted());
                }
            });
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            firingThread.get().interrupt();
            // The executor asks the hook this exactly when it is deciding what to do with the interrupt, so by now
            // the interrupt has been answered.
            assertThat(executorAsked.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the interrupt reached the hook wait").isTrue();
            commandMayFinish.countDown();
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            // The hook's own result, not the BLOCKED an abandoned wait is answered with.
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            assertThat(ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(stoppedByThreadInterrupt).as("the command's thread was never interrupted").isFalse();
            assertThat(interruptHandedBack).as("the firing thread's interrupt is back for the caller to read").isTrue();
        } finally {
            executor.close();
        }
    }

    @ParameterizedTest
    @EnumSource(GateEvent.class)
    void gateEvent_interruptOfTheFiringThread_stopsTheCommandAndBlocks(GateEvent event) throws Exception {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final Firing<?> firing = event.prepare(new HostShellActionExecutor(shell()), null,
                DeclarativeHookOptions.none());
        final AtomicReference<Thread> firingThread = new AtomicReference<>();
        try {
            final Future<HookResult> running = hookThread.submit(() -> {
                firingThread.set(Thread.currentThread());
                try {
                    return firing.through(executor, new CountDownLatch(1));
                } finally {
                    Thread.interrupted();
                }
            });
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            firingThread.get().interrupt();
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(result.isBlocked()).isTrue();
            assertThat(commandEnded.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(stoppedByThreadInterrupt).as("the guard's command was stopped through its thread").isTrue();
            assertThat(ranToCompletion).isFalse();
        } finally {
            executor.close();
        }
    }

    // --- who asks to be waited for ------------------------------------------------------------------------------

    @Test
    void aReportHookWithAShellCommandAsksToBeWaitedFor_andNoOtherDeclarativeHookDoes() {
        final ShellActionExecutor shell = new HostShellActionExecutor(mock(VirtualShell.class));

        for (ReportEvent event : ReportEvent.values()) {
            assertThat(event.prepare(shell, null).hook.ignoresInterrupt()).as(event.name()).isTrue();
        }
        // An http call is not tied to the execution's signal to begin with, and keeps the executor's default.
        assertThat(new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY,
                HttpAction.builder().url(URI.create("https://hooks.example/audit")).build(), shell, null, null,
                Map.of(), DeclarativeHookOptions.none()).ignoresInterrupt()).isFalse();
        // The gates never do, failOpen or not.
        for (GateEvent event : GateEvent.values()) {
            for (boolean failOpen : new boolean[]{false, true}) {
                assertThat(event.prepare(shell, null, DeclarativeHookOptions.builder().failOpen(failOpen).build()).hook
                        .ignoresInterrupt()).as("%s, failOpen=%s", event, failOpen).isFalse();
            }
        }
    }

    @Test
    void aHookOnAnEventOutsideAnyExecutionDoesNotAskToBeWaitedFor() {
        // These three cannot veto either, so "not a guard" alone would have them waited for across a thread
        // interrupt that has nothing to do with an execution being stopped. The rule is for the report events.
        final ShellActionExecutor shell = new HostShellActionExecutor(mock(VirtualShell.class));

        assertThat(new DeclarativeOnSessionStartHook("ops", ACTION, shell).ignoresInterrupt()).isFalse();
        assertThat(new DeclarativeOnSessionEndHook("ops", ACTION, shell).ignoresInterrupt()).isFalse();
        assertThat(new DeclarativeOnConfigReloadHook("ops", ACTION, shell).ignoresInterrupt()).isFalse();
    }

    // --- the view a report command is run with ------------------------------------------------------------------

    @Test
    void detachedView_overridesEveryHookContextMethod() throws Exception {
        // A method HookContext gains later would otherwise fall back to the interface default on the view, and the
        // executor would see a different answer from the context it wraps.
        for (Method method : HookContext.class.getMethods()) {
            assertThat(SignalDetachedHookContext.class.getDeclaredMethod(method.getName(), method.getParameterTypes()))
                    .as("SignalDetachedHookContext overrides %s", method.getName()).isNotNull();
        }
    }

    @Test
    void detachedView_delegatesEverythingButTheSignal() {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final HookRegistry registry = new DefaultHookRegistry();
            final Instant firedAt = Instant.parse("2026-10-07T00:00:00Z");
            final OnStopContext context = onStop(registry, coordinator.getSignal(), firedAt);

            final HookContext view = new SignalDetachedHookContext(context);

            // The context still answers a hook written in code; only the command's executor is handed nothing.
            assertThat(context.getExecutionCancellation()).isPresent();
            assertThat(view.getExecutionCancellation()).isEqualTo(Optional.empty());
            assertThat(view.getInvokerType()).isEqualTo(context.getInvokerType());
            assertThat(view.getInvokerName()).isEqualTo(context.getInvokerName());
            assertThat(view.getHookRegistry()).isSameAs(registry);
            assertThat(view.getExecutionEnvironment()).isEqualTo(context.getExecutionEnvironment());
            assertThat(view.getEnvironmentDescriptor()).isEqualTo(context.getEnvironmentDescriptor());
            assertThat(view.getTimestamp()).isEqualTo(context.getTimestamp());
            assertThat(view.getExecutionAttributes()).isEqualTo(context.getExecutionAttributes());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /** A hook and the context to fire it with, so one pair can be fired directly or through the hook executor. */
    private static final class Firing<C extends HookContext> {

        private final ExecutionHook<C> hook;
        private final C context;

        Firing(ExecutionHook<C> hook, C context) {
            this.hook = hook;
            this.context = context;
        }

        HookResult direct() {
            return hook.execute(context);
        }

        /**
         * Fires the hook as declared through {@code executor}, observed: {@code asked} opens when the executor asks
         * the hook whether to wait for it, which it does exactly when the firing thread has been interrupted.
         */
        HookResult through(DefaultHookExecutor executor, CountDownLatch asked) {
            final ExecutionHook<C> observed = new ExecutionHook<>() {
                @Override
                public HookResult execute(C firedWith) {
                    return hook.execute(firedWith);
                }

                @Override
                public String getHookId() {
                    return hook.getHookId();
                }

                @Override
                public Optional<Duration> getExecutionBudget() {
                    return hook.getExecutionBudget();
                }

                @Override
                public Optional<TimeoutBehavior> getTimeoutBehavior() {
                    return hook.getTimeoutBehavior();
                }

                @Override
                public boolean ignoresInterrupt() {
                    asked.countDown();
                    return hook.ignoresInterrupt();
                }
            };
            return executor
                    .execute(List.of(observed), context, HookExecutionPolicy.continueOnExceptionButStopOnBlocked())
                    .get(0);
        }
    }

    /** The six events that report what already happened inside an execution. */
    private enum ReportEvent {
        ON_STOP {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                return new Firing<>(new DeclarativeOnStopHook("ops", ACTION, shell),
                        onStop(new DefaultHookRegistry(), signal, Instant.now()));
            }
        },
        POST_COMPACT {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                final Instant now = Instant.now();
                return new Firing<>(new DeclarativePostCompactHook("ops", ACTION, shell),
                        PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
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
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                return new Firing<>(new DeclarativeSubagentStartHook("ops", ACTION, shell),
                        SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").goal("map the module graph").build());
            }
        },
        SUBAGENT_STOP {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                return new Firing<>(new DeclarativeSubagentStopHook("ops", ACTION, shell),
                        SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").success(true).build());
            }
        },
        PERMISSION_DENIED {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                return new Firing<>(new DeclarativePermissionDeniedHook("ops", ACTION, shell),
                        PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).toolName("Bash")
                                .toolInput(ToolInput.of(Map.of("command", "rm -rf /"))).denyReason("policy").build());
            }
        },
        POST_TOOL {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal) {
                return new Firing<>(new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell),
                        postTool(signal));
            }
        };

        /**
         * @param signal
         *            the execution's signal, or null for a firing no signal reaches
         */
        abstract Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal);
    }

    /** The four events that are asked before something proceeds. */
    private enum GateEvent {
        PRE_TOOL {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new Firing<>(
                        new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell, null, null, Map.of(),
                                options),
                        PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build());
            }
        },
        ON_START {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new Firing<>(new DeclarativeOnStartHook("ops", ACTION, shell, options),
                        OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .userMessage("deploy please").build());
            }
        },
        PRE_COMPACT {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new Firing<>(new DeclarativePreCompactHook("ops", ACTION, shell, options),
                        PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .trigger(CompactionTrigger.AUTO).sessionIdValue("conv-1").messageCount(42)
                                .estimatedTokens(120_000).build());
            }
        },
        PERMISSION_REQUEST {
            @Override
            Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new Firing<>(new DeclarativePermissionRequestHook("ops", ACTION, shell, options),
                        PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).toolName("Bash")
                                .toolInput(ToolInput.of(Map.of("command", "ls"))).build());
            }
        };

        abstract Firing<?> prepare(ShellActionExecutor shell, CancellationSignal signal,
                DeclarativeHookOptions options);
    }

    private static OnStopContext onStop(HookRegistry registry, CancellationSignal signal, Instant now) {
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .executionCancellation(signal).success(true).finalAnswer("done").timestamp(now)
                .metadata(ExecutionMetadata.builder().iterationCount(1).duration(Duration.ofMillis(50))
                        .startTime(now.minusMillis(50)).endTime(now).build())
                .build();
    }

    private static PostToolContext postTool(CancellationSignal signal) {
        return PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).toolUseResult(ToolUseResult.success("call-1", "ok"))
                .iterationCount(1).build();
    }

    /**
     * A shell that answers both stops. Its command runs until its cancellation trips, its thread is interrupted, or
     * the test lets it finish, and records which of the three ended it.
     */
    private VirtualShell shell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            final ExecutionOptions options = invocation.getArgument(1);
            seenOptions.set(options);
            final CountDownLatch ended = new CountDownLatch(1);
            options.getCancellation().onCancel(() -> {
                stoppedByCancellation.set(true);
                ended.countDown();
            });
            final Thread releaser = new Thread(() -> {
                try {
                    commandMayFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ended.countDown();
            }, "command-release");
            releaser.setDaemon(true);
            releaser.start();
            commandStarted.countDown();
            try {
                if (!ended.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("the test never let the command finish");
                }
                if (stoppedByCancellation.get()) {
                    throw new ShellCancelledException("Process cancelled: cleanup.sh");
                }
                ranToCompletion.set(true);
                return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
            } catch (InterruptedException e) {
                stoppedByThreadInterrupt.set(true);
                Thread.currentThread().interrupt();
                throw new ShellExecutionException("Interrupted: cleanup.sh", e);
            } finally {
                commandEnded.countDown();
            }
        });
        return shell;
    }
}
