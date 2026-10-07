package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
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
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * EE-93, end to end with the real executor: an interrupt that arrives while a turn is still being set up — the
 * executor has not reached its {@code onStart} hooks and has published no coordinator — is kept by the session and
 * ends the turn as interrupted. Before, it was dropped and the turn ran.
 *
 * <p>
 * The turn is held in that stretch by its execution-environment provider, which the executor asks before it creates
 * the turn's coordinator.
 */
@DisplayName("DefaultLiveSession + OrcaAgentExecutor: an interrupt before the turn's onStart hooks (EE-93)")
class DefaultLiveSessionInterruptBeforeOnStartTest {

    @TempDir
    Path tempDir;

    private final CountDownLatch settingUp = new CountDownLatch(1);
    private final CountDownLatch goOn = new CountDownLatch(1);
    private final CountingLlmClient llm = new CountingLlmClient();
    private final PingCommand ping = new PingCommand();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    private final AtomicInteger onStartBodiesRun = new AtomicInteger();
    private final List<Boolean> onStops = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Test
    @DisplayName("session.interrupt during set-up ends a ReAct turn INTERRUPTED: no LLM call, onStop(success=false)")
    void interruptDuringSetUp_endsAReActTurnInterrupted() throws Exception {
        try (DefaultLiveSession session = session(null)) {
            final CompletionStage<AgentExecutionResult> turn = session.submitAsync("hi", e -> {
            });
            assertThat(settingUp.await(10, TimeUnit.SECONDS)).isTrue();

            session.interrupt(InterruptReason.USER_SIGINT);
            goOn.countDown();
            final AgentExecutionResult result = turn.toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(llm.calls).hasValue(0);
            // The onStart hook here is a programmatic one, which no signal stops: it is not run at all.
            assertThat(onStartBodiesRun).as("no onStart hook runs for a turn interrupted before it").hasValue(0);
            assertThat(onStops).containsExactly(false);
        }
    }

    @Test
    @DisplayName("the same interrupt ends a slash-command turn INTERRUPTED before the command runs")
    void interruptDuringSetUp_endsACommandTurnBeforeTheCommandRuns() throws Exception {
        try (DefaultLiveSession session = session(null)) {
            final CompletionStage<AgentExecutionResult> turn = session.submitAsync("/ping", e -> {
            });
            assertThat(settingUp.await(10, TimeUnit.SECONDS)).isTrue();

            session.interrupt(InterruptReason.USER_SIGINT);
            goOn.countDown();
            final AgentExecutionResult result = turn.toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(ping.executions).hasValue(0);
            assertThat(onStartBodiesRun).hasValue(0);
            assertThat(onStops).containsExactly(false);
        }
    }

    @Test
    @DisplayName("a NOW-priority input enqueued during set-up preempts the turn and is left in the queue")
    void nowPriorityInputDuringSetUp_preemptsTheTurnAndStaysQueued() throws Exception {
        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        try (DefaultLiveSession session = session(queue)) {
            final CompletionStage<AgentExecutionResult> turn = session.submitAsync("hi", e -> {
            });
            assertThat(settingUp.await(10, TimeUnit.SECONDS)).isTrue();

            queue.enqueue(QueuedInput.builder().inputText("now please").priority(QueuedInputPriority.NOW)
                    .agentRuntimeId(AgentRuntimeId.of("agent:test-ee93")).build());
            goOn.countDown();
            final AgentExecutionResult result = turn.toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Before EE-93 the interrupt was dropped in this stretch, the turn ran, and its loop's mid-turn drain
            // could take the input into the turn it was meant to preempt.
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(llm.calls).hasValue(0);
            assertThat(onStartBodiesRun).hasValue(0);
            assertThat(queue.snapshot()).as("left for the next turn, or for the host's own drain").hasSize(1);
        }
    }

    @Test
    @DisplayName("with no interrupt the same turn runs: the hold alone changes nothing")
    void noInterrupt_theTurnRuns() throws Exception {
        try (DefaultLiveSession session = session(null)) {
            final CompletionStage<AgentExecutionResult> turn = session.submitAsync("hi", e -> {
            });
            assertThat(settingUp.await(10, TimeUnit.SECONDS)).isTrue();
            goOn.countDown();
            final AgentExecutionResult result = turn.toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(llm.calls).hasValue(1);
            assertThat(onStartBodiesRun).hasValue(1);
            assertThat(onStops).containsExactly(true);
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private DefaultLiveSession session(MessageQueueManager queue) {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            onStartBodiesRun.incrementAndGet();
            return HookResult.success();
        });
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.add(context.isSuccess());
            return HookResult.success();
        });
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final ExecutionEnvironment environment = TestExecutionEnvironments.of(fileSystem);
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(fileSystem, ".aimon/commands");
        commandRegistry.registerSystemCommand(ping);
        final OrcaAgentRuntime runtime = OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-ee93"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(hooks).commandRegistry(commandRegistry)
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                // Asked before the turn's coordinator exists: the turn waits here, set up but not yet interruptible.
                .executionEnvironmentProvider(request -> {
                    settingUp.countDown();
                    try {
                        goOn.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return environment;
                }).build();
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final OrcaAgentExecutor executor = new OrcaAgentExecutor(llm,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()), toolManager, hookManager,
                new DefaultCommandExecutionManager(llm),
                new DefaultSubagentExecutionManager(llm, toolManager, hookManager));
        final SessionId sessionId = SessionId.generate();
        return queue == null
                ? new DefaultLiveSession(sessionId, runtime, executor, LiveSessionOptions.defaults())
                : new DefaultLiveSession(sessionId, runtime, executor, LiveSessionOptions.defaults(), queue);
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
