package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeHookOptions;
import at.aimon.core.skill.hook.declarative.DeclarativeOnStartHook;
import at.aimon.core.skill.hook.declarative.DeclarativeOnStopHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreCompactHook;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * The main turn's hooks are tied to the turn's cancellation signal (EE-80): the signal exists before {@code onStart}
 * fires, an interrupt that arrives while an {@code onStart} command runs ends the turn as interrupted, and
 * {@code onStop} and the compaction gate are handed the same signal. A slash-command turn gets the same {@code onStart}
 * and nothing after it.
 *
 * <p>
 * The shell is the kind the item is about — its blocking call ignores thread interrupts and honours only the
 * command's own {@link ExecutionOptions#getCancellation() cancellation signal}.
 */
@DisplayName("OrcaAgentExecutor: the turn's hooks and the turn's cancellation signal (EE-80)")
class OrcaAgentExecutorHookCancellationTest {

    /**
     * How long the fake command "runs" when nothing stops it. A hang guard only: that a command was stopped is read
     * from {@link #stoppedByCancellation}, not from how soon the turn returned.
     */
    private static final Duration COMMAND_RUNTIME = Duration.ofSeconds(10);
    private static final ShellAction GUARD = new ShellAction("guard.sh", Duration.ofSeconds(30));

    @TempDir
    Path tempDir;

    private final ExecutorService turnThread = Executors.newSingleThreadExecutor();
    private final CountDownLatch commandStarted = new CountDownLatch(1);
    private final AtomicBoolean stoppedByCancellation = new AtomicBoolean();
    private final CountDownLatch commandMayFinish = new CountDownLatch(1);
    private final AtomicBoolean ranToCompletion = new AtomicBoolean();
    private final AtomicReference<InterruptCoordinator> published = new AtomicReference<>();
    private final AtomicInteger publications = new AtomicInteger();
    private final AtomicReference<OnStopContext> onStop = new AtomicReference<>();
    private final AtomicReference<Optional<CancellationSignal>> onStopSignal = new AtomicReference<>();
    private final CountingLlmClient llmClient = new CountingLlmClient();
    private final PingCommand ping = new PingCommand();
    private final InMemorySessionRecordStore store = new InMemorySessionRecordStore();
    private final DefaultTranscriptManager transcriptManager = new DefaultTranscriptManager(store);
    private DefaultHookRegistry hookRegistry;
    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStop.set(context);
            // Read while the hook runs: a report context answers for the moment the work starts.
            onStopSignal.set(context.getExecutionCancellation());
            return HookResult.success();
        });
    }

    @AfterEach
    void stopTurnThread() {
        commandMayFinish.countDown();
        turnThread.shutdownNow();
    }

    @Test
    @DisplayName("onStart and onStop of a ReAct turn are handed the signal of the coordinator published to the observer")
    void reactTurn_onStartAndOnStop_carryTheTurnsSignal() {
        final AtomicReference<Optional<CancellationSignal>> onStartSignal = new AtomicReference<>();
        final AtomicReference<InterruptCoordinator> publishedAtOnStart = new AtomicReference<>();
        hookRegistry.register(HookEventType.ON_START, (OnStartHook) context -> {
            onStartSignal.set(context.getExecutionCancellation());
            publishedAtOnStart.set(published.get());
            return HookResult.success();
        });

        final SessionId sessionId = SessionId.generate();

        final OrcaAgentExecutionResult result = executor().execute(runtime(null), request("hi", sessionId));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(transcriptManager.initialize(sessionId, "You are a test agent").getRewindPoint())
                .as("a completed turn leaves nothing to retry").isEmpty();
        assertThat(publishedAtOnStart.get()).as("the coordinator is published before onStart fires").isNotNull();
        assertThat(publications).hasValue(1);
        assertThat(onStartSignal.get().orElseThrow()).isSameAs(published.get().getSignal());
        assertThat(onStopSignal.get().orElseThrow()).isSameAs(published.get().getSignal());
    }

    @Test
    @DisplayName("an interrupt during an onStart command stops it and ends the turn INTERRUPTED, with no LLM call")
    void reactTurn_interruptDuringOnStart_endsTheTurnInterrupted() throws Exception {
        hookRegistry.register(HookEventType.ON_START,
                new DeclarativeOnStartHook("ops", GUARD, new HostShellActionExecutor(cancellationOnlyShell())));
        final SessionId sessionId = SessionId.generate();

        final Future<OrcaAgentExecutionResult> turn = turnThread
                .submit(() -> executor().execute(runtime(null), request("hi", sessionId)));
        assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
        published.get().requestInterrupt(InterruptReason.USER_SIGINT);
        final OrcaAgentExecutionResult result = turn.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

        // The command was stopped, not waited out.
        assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
        // The user stopped the turn; it is not reported as a guard's veto, though the cancelled guard blocked.
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getMetadata().getIterationCount()).isZero();
        assertThat(llmClient.calls).hasValue(0);
        assertThat(onStop.get()).as("onStop fired").isNotNull();
        assertThat(onStop.get().isSuccess()).isFalse();
        // A cancelled turn's onStop starts unbound: the tripped signal must not keep its command from running.
        assertThat(onStopSignal.get()).isEmpty();
        // It took the interrupted turn's road out, so the turn can be retried.
        assertThat(transcriptManager.initialize(sessionId, "You are a test agent").getRewindPoint()).isPresent();
    }

    @Test
    @DisplayName("an interrupt during a slash-command turn's onStart ends it INTERRUPTED and the command does not run")
    void commandTurn_interruptDuringOnStart_endsTheTurnInterruptedBeforeTheCommandRuns() throws Exception {
        hookRegistry.register(HookEventType.ON_START,
                new DeclarativeOnStartHook("ops", GUARD, new HostShellActionExecutor(cancellationOnlyShell())));

        final Future<OrcaAgentExecutionResult> turn = turnThread
                .submit(() -> executor().execute(runtime(null), request("/ping")));
        assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
        published.get().requestInterrupt(InterruptReason.USER_SIGINT);
        final OrcaAgentExecutionResult result = turn.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

        assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(ping.executions).hasValue(0);
        assertThat(onStop.get().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("EE-97: an interrupt that arrives while a finished turn's onStop command runs stops the command")
    void reactTurn_interruptDuringOnStop_stopsTheCommandOfAHookWithoutTheOption() throws Exception {
        hookRegistry.register(HookEventType.ON_STOP,
                new DeclarativeOnStopHook("ops", GUARD, new HostShellActionExecutor(holdingShell())));

        final Future<OrcaAgentExecutionResult> turn = turnThread
                .submit(() -> executor().execute(runtime(null), request("hi")));
        assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // The next input preempting the finished turn: the interrupt lands on the turn's still-open coordinator.
        published.get().requestInterrupt(InterruptReason.USER_SIGINT);
        final OrcaAgentExecutionResult result = turn.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

        assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
        assertThat(ranToCompletion).isFalse();
        // The turn had already finished; only its cleanup was cut short.
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("EE-97: with ignoreInterrupt the same interrupt leaves the onStop command running to its end")
    void reactTurn_interruptDuringOnStop_leavesTheCommandOfAnIgnoreInterruptHookRunning() throws Exception {
        hookRegistry.register(HookEventType.ON_STOP,
                new DeclarativeOnStopHook("ops", GUARD, new HostShellActionExecutor(holdingShell()),
                        DeclarativeHookOptions.builder().ignoreInterrupt(true).build()));

        final Future<OrcaAgentExecutionResult> turn = turnThread
                .submit(() -> executor().execute(runtime(null), request("hi")));
        assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Listeners run inside requestInterrupt, so a command tied to the signal would have been stopped by now.
        published.get().requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(stoppedByCancellation).isFalse();
        assertThat(turn).as("the turn is still waiting for its cleanup").isNotDone();
        commandMayFinish.countDown();
        final OrcaAgentExecutionResult result = turn.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

        assertThat(ranToCompletion).as("the cleanup command finished on its own").isTrue();
        assertThat(stoppedByCancellation).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("a slash-command turn's onStart is handed the published signal; its onStop is handed none")
    void commandTurn_onStartCarriesTheSignal_onStopCarriesNone() {
        final AtomicReference<Optional<CancellationSignal>> onStartSignal = new AtomicReference<>();
        hookRegistry.register(HookEventType.ON_START, (OnStartHook) context -> {
            onStartSignal.set(context.getExecutionCancellation());
            return HookResult.success();
        });

        final OrcaAgentExecutionResult result = executor().execute(runtime(null), request("/ping"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(ping.executions).hasValue(1);
        assertThat(publications).as("published once per turn, for a command turn too").hasValue(1);
        assertThat(onStartSignal.get().orElseThrow()).isSameAs(published.get().getSignal());
        // Nothing the command flow runs reads a signal, so "none can trip" is the honest answer for its onStop.
        assertThat(onStop.get()).isNotNull();
        assertThat(onStopSignal.get()).isEmpty();
    }

    @Test
    @DisplayName("the compaction gate is handed the turn's signal, and a BLOCK after an interrupt is the interrupt")
    void reactTurn_compactionBlockedAfterAnInterrupt_endsTheTurnInterrupted() {
        final AtomicReference<Optional<CancellationSignal>> gateSignal = new AtomicReference<>();
        // Stands in for an engine whose preCompact guard was cancelled by the interrupt: the compaction is skipped
        // and the view is over the blocking limit.
        final ContextEngine blockingAfterInterrupt = new StubContextEngine(request -> {
            gateSignal.set(request.getExecutionCancellation());
            published.get().requestInterrupt(InterruptReason.USER_SIGINT);
            return ContextDecision.from(CompactionDecision.block("over the blocking limit", 9_900, 9_500),
                    ContextView.of(request.getTranscriptBuffer().getMessages()));
        });

        final OrcaAgentExecutionResult result = executor().execute(runtime(blockingAfterInterrupt), request("hi"));

        assertThat(gateSignal.get().orElseThrow()).isSameAs(published.get().getSignal());
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.calls).hasValue(0);
    }

    @Test
    @DisplayName("an interrupt during an AUTO preCompact command stops it and ends the turn INTERRUPTED")
    void reactTurn_interruptDuringPreCompact_endsTheTurnInterrupted() throws Exception {
        // The chain end to end, with the real engine: the turn's signal reaches the guard's command, the stopped
        // guard blocks, the compaction is skipped, the view is over the blocking limit, and the BLOCK is the interrupt.
        hookRegistry.register(HookEventType.PRE_COMPACT,
                new DeclarativePreCompactHook("ops", GUARD, new HostShellActionExecutor(cancellationOnlyShell())));

        final Future<OrcaAgentExecutionResult> turn = turnThread
                .submit(() -> executor().execute(runtime(engineOverTheBlockingLimit()), request("hi")));
        assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
        published.get().requestInterrupt(InterruptReason.USER_SIGINT);
        final OrcaAgentExecutionResult result = turn.get(COMMAND_RUNTIME.toMillis() * 2, TimeUnit.MILLISECONDS);

        assertThat(stoppedByCancellation).as("the shell saw the command's cancellation trip").isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.calls).as("neither a summary call nor a loop call").hasValue(0);
    }

    @Test
    @DisplayName("a BLOCK with no interrupt behind it is still a context-window failure")
    void reactTurn_compactionBlockedWithoutAnInterrupt_isStillAnError() {
        final ContextEngine blocking = new StubContextEngine(
                request -> ContextDecision.from(CompactionDecision.block("over the blocking limit", 9_900, 9_500),
                        ContextView.of(request.getTranscriptBuffer().getMessages())));

        final OrcaAgentExecutionResult result = executor().execute(runtime(blocking), request("hi"));

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private OrcaAgentExecutionRequest request(String input) {
        return request(input, SessionId.generate());
    }

    private OrcaAgentExecutionRequest request(String input, SessionId sessionId) {
        return OrcaAgentExecutionRequest.builder().userInput(input).sessionId(sessionId)
                .interruptObserver(coordinator -> {
                    publications.incrementAndGet();
                    published.set(coordinator);
                }).build();
    }

    private OrcaAgentRuntime runtime(ContextEngine contextEngine) {
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(fileSystem, ".aimon/commands");
        commandRegistry.registerSystemCommand(ping);
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .contextEngine(contextEngine)
                .executionEnvironmentProvider(TestExecutionEnvironments.provider(fileSystem)).build();
    }

    /** The default engine with every estimate in the blocking band, so only a compaction can let the turn go on. */
    private ContextEngine engineOverTheBlockingLimit() {
        final TokenEstimator overTheLimit = new FixedTokenEstimator(9_000);
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(llmClient, overTheLimit,
                new DefaultHookExecutionManager());
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder()
                        .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                                .autoCompactBuffer(2_000).warningBuffer(1_000).blockingBuffer(500).build())
                        .build(),
                overTheLimit);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(overTheLimit).build();
    }

    private OrcaAgentExecutor executor() {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        return new OrcaAgentExecutor(llmClient, transcriptManager, toolManager, hookManager,
                new DefaultCommandExecutionManager(llmClient),
                new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager));
    }

    /**
     * A shell that ignores thread interrupts and stops only when the command's cancellation signal trips, as a
     * shell whose blocking call is a remote request does.
     */
    private VirtualShell cancellationOnlyShell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            final ExecutionOptions options = invocation.getArgument(1);
            if (options.getCancellation().isCancelled()) {
                throw new ShellCancelledException("Cancelled before the process started: guard.sh");
            }
            final CountDownLatch stopped = new CountDownLatch(1);
            options.getCancellation().onCancel(stopped::countDown);
            commandStarted.countDown();
            if (awaitIgnoringInterrupts(stopped)) {
                stoppedByCancellation.set(true);
                throw new ShellCancelledException("Process cancelled: guard.sh");
            }
            return new ShellCommandResult(0, "", "", COMMAND_RUNTIME);
        });
        return shell;
    }

    /**
     * The same kind of shell, whose command runs until the test lets it finish or its cancellation trips, and
     * records which of the two ended it.
     */
    private VirtualShell holdingShell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            final ExecutionOptions options = invocation.getArgument(1);
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
                throw new ShellCancelledException("Process cancelled: guard.sh");
            }
            ranToCompletion.set(true);
            return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
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

    /** A slash command that counts its executions. */
    private static final class PingCommand extends SystemCommand implements DirectExecutable {
        final AtomicInteger executions = new AtomicInteger();

        PingCommand() {
            super("ping", "ping");
        }

        @Override
        public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
            executions.incrementAndGet();
            return CommandExecutionResult.success("pong");
        }
    }

    /** A context engine whose gate is the test's to script. */
    private static final class StubContextEngine implements ContextEngine {
        private final java.util.function.Function<ContextRequest, ContextDecision> gate;

        StubContextEngine(java.util.function.Function<ContextRequest, ContextDecision> gate) {
            this.gate = gate;
        }

        @Override
        public ContextDecision prepare(ContextRequest request) {
            return gate.apply(request);
        }

        @Override
        public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
            return Optional.empty();
        }

        @Override
        public CompactionResult compactNow(ContextRequest request, String instructions) {
            throw new UnsupportedOperationException("not under test");
        }
    }

    /** Returns a fixed estimate regardless of content, so a threshold band can be targeted precisely. */
    private static final class FixedTokenEstimator implements TokenEstimator {
        private final int fixedEstimate;

        FixedTokenEstimator(int fixedEstimate) {
            this.fixedEstimate = fixedEstimate;
        }

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            return fixedEstimate;
        }

        @Override
        public int estimateMessage(Message message) {
            return 0;
        }

        @Override
        public int estimateText(String text) {
            return 0;
        }
    }

    /** Answers every call with a final text and counts the calls. */
    private static final class CountingLlmClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            calls.incrementAndGet();
            return LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "Counting";
        }
    }
}
