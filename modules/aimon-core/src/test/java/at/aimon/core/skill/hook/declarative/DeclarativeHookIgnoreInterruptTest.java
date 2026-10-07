package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * A report hook that declared {@code ignoreInterrupt} keeps its command running when the execution is interrupted
 * (EE-97), and a gate hook cannot declare it.
 *
 * <p>
 * Same shell as {@code DeclarativeHookCancellationTest}: its blocking call does not answer a thread interrupt and the
 * only stop it honours is the command's own cancellation. Whether a command was stopped or left alone is read from
 * what that shell saw — its cancellation tripped or not — never from how soon the hook returned.
 */
@DisplayName("ignoreInterrupt: a report hook's command is left running by an interrupt (EE-97)")
class DeclarativeHookIgnoreInterruptTest {

    /** A hang guard only; nothing is asserted on elapsed time. */
    private static final Duration HANG_GUARD = Duration.ofSeconds(10);
    private static final ShellAction ACTION = new ShellAction("cleanup.sh", Duration.ofSeconds(30));
    private static final DeclarativeHookOptions IGNORE_INTERRUPT = DeclarativeHookOptions.builder()
            .ignoreInterrupt(true).build();

    private final ExecutorService hookThread = Executors.newSingleThreadExecutor();
    private final CountDownLatch commandStarted = new CountDownLatch(1);
    private final CountDownLatch commandMayFinish = new CountDownLatch(1);
    private final AtomicReference<ExecutionOptions> seenOptions = new AtomicReference<>();
    private final AtomicBoolean stoppedByCancellation = new AtomicBoolean();
    private final AtomicBoolean ranToCompletion = new AtomicBoolean();

    @AfterEach
    void stopHookThread() {
        commandMayFinish.countDown();
        hookThread.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_withIgnoreInterrupt_commandOutlivesTheInterrupt(ReportEvent event) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final ShellActionExecutor shell = new HostShellActionExecutor(cancellationOnlyShell());

            final Future<HookResult> running = hookThread
                    .submit(() -> event.fire(shell, coordinator.getSignal(), IGNORE_INTERRUPT));
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            // The command was never tied to the execution's signal, so the interrupt did not reach it.
            assertThat(seenOptions.get().getCancellation().isCancelled()).isFalse();
            assertThat(running).isNotDone();
            commandMayFinish.countDown();
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(ranToCompletion).as("the command finished on its own").isTrue();
            assertThat(stoppedByCancellation).isFalse();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest
    @EnumSource(ReportEvent.class)
    void reportEvent_withoutTheOption_isStoppedByTheSameInterrupt(ReportEvent event) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final ShellActionExecutor shell = new HostShellActionExecutor(cancellationOnlyShell());

            final Future<HookResult> running = hookThread
                    .submit(() -> event.fire(shell, coordinator.getSignal(), DeclarativeHookOptions.none()));
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
            assertThat(ranToCompletion).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}, failOpen={1}")
    @MethodSource("gateEventsWithAndWithoutFailOpen")
    void gateEvent_ignoresTheOption_andACancelledGuardStillBlocks(GateEvent event, boolean failOpen) throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final ShellActionExecutor shell = new HostShellActionExecutor(cancellationOnlyShell());
            final DeclarativeHookOptions options = DeclarativeHookOptions.builder().ignoreInterrupt(true)
                    .failOpen(failOpen).build();

            final Future<HookResult> running = hookThread
                    .submit(() -> event.fire(shell, coordinator.getSignal(), options));
            assertThat(commandStarted.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final HookResult result = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(stoppedByCancellation).as("the guard's command was stopped").isTrue();
            // Not "allow and continue", whatever failOpen and ignoreInterrupt say: the execution is ending.
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.getFeedback().orElseThrow()).contains("execution cancelled");
        }
    }

    static Stream<Arguments> gateEventsWithAndWithoutFailOpen() {
        return Stream.of(GateEvent.values())
                .flatMap(event -> Stream.of(Arguments.of(event, false), Arguments.of(event, true)));
    }

    @Test
    void onlyAReportHookThatDeclaredTheOptionAsksToBeWaitedFor() {
        final ShellActionExecutor shell = new HostShellActionExecutor(mock(VirtualShell.class));

        assertThat(new DeclarativeOnStopHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt()).isTrue();
        assertThat(new DeclarativeOnStopHook("ops", ACTION, shell).ignoresInterrupt()).isFalse();
        assertThat(new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell, null, null, Map.of(),
                IGNORE_INTERRUPT).ignoresInterrupt()).isTrue();
        // An http call is not tied to the execution's signal, so there is nothing for the option to detach.
        assertThat(new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY,
                HttpAction.builder().url(URI.create("https://hooks.example/audit")).build(), shell, null, null,
                Map.of(), IGNORE_INTERRUPT).ignoresInterrupt()).isFalse();
        // The gates never do, whatever the options say.
        assertThat(new DeclarativeOnStartHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt()).isFalse();
        assertThat(new DeclarativePreCompactHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt()).isFalse();
        assertThat(new DeclarativePermissionRequestHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt())
                .isFalse();
        assertThat(((ExecutionHook<?>) new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell, null,
                null, Map.of(), IGNORE_INTERRUPT)).ignoresInterrupt()).isFalse();
    }

    @Test
    void aHookBuiltByHandForAnEventOutsideAnyExecutionDoesNotAskToBeWaitedFor() {
        // Neither front-end lets the key through on these three, but the classes are public and cannot veto, so
        // "not a guard" alone would honour it. The option is for the report events.
        final ShellActionExecutor shell = new HostShellActionExecutor(mock(VirtualShell.class));

        assertThat(new DeclarativeOnSessionStartHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt())
                .isFalse();
        assertThat(new DeclarativeOnSessionEndHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt())
                .isFalse();
        assertThat(new DeclarativeOnConfigReloadHook("ops", ACTION, shell, IGNORE_INTERRUPT).ignoresInterrupt())
                .isFalse();
    }

    @Test
    void theOptionIsPartOfTheOptionsValue() {
        final DeclarativeHookOptions plain = DeclarativeHookOptions.builder().hookIdDiscriminator("onStop[0][0]")
                .build();
        final DeclarativeHookOptions mustFinish = DeclarativeHookOptions.builder().hookIdDiscriminator("onStop[0][0]")
                .ignoreInterrupt(true).build();

        assertThat(DeclarativeHookOptions.none().isIgnoreInterrupt()).isFalse();
        assertThat(mustFinish.isIgnoreInterrupt()).isTrue();
        assertThat(mustFinish).isNotEqualTo(plain).doesNotHaveSameHashCodeAs(plain);
        assertThat(mustFinish).isEqualTo(
                DeclarativeHookOptions.builder().hookIdDiscriminator("onStop[0][0]").ignoreInterrupt(true).build());
        assertThat(mustFinish.toString()).contains("ignoreInterrupt=true");
    }

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

    /** The six events that report what already happened inside an execution. */
    private enum ReportEvent {
        ON_STOP {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativeOnStopHook("ops", ACTION, shell, options)
                        .execute(onStop(new DefaultHookRegistry(), signal, Instant.now()));
            }
        },
        POST_COMPACT {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                final Instant now = Instant.now();
                return new DeclarativePostCompactHook("ops", ACTION, shell, options)
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
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativeSubagentStartHook("ops", ACTION, shell, options)
                        .execute(SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").goal("map the module graph").build());
            }
        },
        SUBAGENT_STOP {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativeSubagentStopHook("ops", ACTION, shell, options)
                        .execute(SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .subagentName("Explore").taskId("t-1").success(true).build());
            }
        },
        PERMISSION_DENIED {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativePermissionDeniedHook("ops", ACTION, shell, options).execute(
                        PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).toolName("Bash")
                                .toolInput(ToolInput.of(Map.of("command", "rm -rf /"))).denyReason("policy").build());
            }
        },
        POST_TOOL {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell, null, null, Map.of(),
                        options)
                        .execute(PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                                .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build());
            }
        };

        abstract HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options);
    }

    /** The four events that are asked before something proceeds. */
    private enum GateEvent {
        PRE_TOOL {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, ACTION, shell, null, null, Map.of(),
                        options)
                        .execute(PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build());
            }
        },
        ON_START {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativeOnStartHook("ops", ACTION, shell, options)
                        .execute(OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .userMessage("deploy please").build());
            }
        },
        PRE_COMPACT {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativePreCompactHook("ops", ACTION, shell, options)
                        .execute(PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal)
                                .trigger(CompactionTrigger.AUTO).sessionIdValue("conv-1").messageCount(42)
                                .estimatedTokens(120_000).build());
            }
        },
        PERMISSION_REQUEST {
            @Override
            HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options) {
                return new DeclarativePermissionRequestHook("ops", ACTION, shell, options).execute(
                        PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                                .hookRegistry(new DefaultHookRegistry()).executionCancellation(signal).toolName("Bash")
                                .toolInput(ToolInput.of(Map.of("command", "ls"))).build());
            }
        };

        abstract HookResult fire(ShellActionExecutor shell, CancellationSignal signal, DeclarativeHookOptions options);
    }

    private static OnStopContext onStop(HookRegistry registry, CancellationSignal signal, Instant now) {
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .executionCancellation(signal).success(true).finalAnswer("done").timestamp(now)
                .metadata(ExecutionMetadata.builder().iterationCount(1).duration(Duration.ofMillis(50))
                        .startTime(now.minusMillis(50)).endTime(now).build())
                .build();
    }

    /**
     * A shell that ignores thread interrupts. Its command runs until the test lets it finish or the command's own
     * cancellation trips, and records which of the two ended it.
     */
    private VirtualShell cancellationOnlyShell() throws Exception {
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
            awaitIgnoringInterrupts(ended);
            if (stoppedByCancellation.get()) {
                throw new ShellCancelledException("Process cancelled: cleanup.sh");
            }
            ranToCompletion.set(true);
            return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
        });
        return shell;
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        final long deadline = System.nanoTime() + HANG_GUARD.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    return;
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
}
