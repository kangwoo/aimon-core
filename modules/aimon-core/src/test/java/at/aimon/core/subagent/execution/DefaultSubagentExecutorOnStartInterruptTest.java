package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
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
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeOnStartHook;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;

/**
 * EE-94: a fork cancelled while its {@code onStart} hooks run ends {@code INTERRUPTED}, as a turn does — whichever
 * kind of hook was running and whichever road the cancellation took. It hands back the transcript as it stood before
 * the goal, and its {@code onStop} fires. A plain block is still {@code BLOCKED} with no {@code onStop}
 * ({@code DefaultSubagentExecutorOnStartBlockTest}).
 */
@DisplayName("DefaultSubagentExecutor: a fork cancelled during its onStart hooks ends INTERRUPTED (EE-94)")
class DefaultSubagentExecutorOnStartInterruptTest {

    private static final String GOAL = "UNANSWERED-GOAL-3c18";
    private static final String VETO = "VETO-REASON-88e0";

    private final StubLlmClient llm = new StubLlmClient();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    /** One entry per onStop hook body that actually ran, with the success flag it was handed. */
    private final List<Boolean> onStops = new CopyOnWriteArrayList<>();
    private final DefaultInterruptCoordinator spawner = new DefaultInterruptCoordinator();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    DefaultSubagentExecutorOnStartInterruptTest() {
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.add(context.isSuccess());
            return HookResult.success();
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        spawner.close();
    }

    @Test
    @DisplayName("during a shell guard: the guard's cancelled command blocks, and the fork still ends INTERRUPTED")
    void cancelledDuringAShellGuard() throws Exception {
        // The command is stopped through its own cancellation, and a stopped guard answers with a block. Before
        // EE-94 the fork read that block and ended BLOCKED.
        final AtomicBoolean commandStopped = new AtomicBoolean();
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            final ExecutionOptions options = invocation.getArgument(1);
            options.getCancellation().onCancel(() -> commandStopped.set(true));
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            throw new ShellCancelledException("Process cancelled: guard.sh");
        });
        hooks.register(HookEventType.ON_START, new DeclarativeOnStartHook("ops",
                new ShellAction("guard.sh", Duration.ofSeconds(30)), new HostShellActionExecutor(shell)));

        final SubagentExecutionResult result = execute(null);

        assertThat(commandStopped).as("the shell saw the command's cancellation trip").isTrue();
        assertInterruptedBeforeStart(result);
        assertThat(result.getSnapshot().getConversationHistory()).isEmpty();
    }

    @Test
    @DisplayName("during a programmatic hook that does not block: INTERRUPTED, and the goal is not kept")
    void cancelledDuringAProgrammaticHook() {
        // Before EE-94 this one did end INTERRUPTED — at the loop's first checkpoint, with the goal in the snapshot.
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            return HookResult.withFeedback("ADVICE-91c3");
        });

        final SubagentExecutionResult result = execute(null);

        assertInterruptedBeforeStart(result);
        assertThat(result.getSnapshot().getConversationHistory()).as("no verdict on the goal, so it is not persisted")
                .isEmpty();
    }

    @Test
    @DisplayName("a hook that vetoes while the fork is being cancelled: the cancellation is what is reported")
    void cancelledAndVetoedAtOnce() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            return HookResult.block(VETO);
        });

        final SubagentExecutionResult result = execute(null);

        assertInterruptedBeforeStart(result);
        assertThat(result.getErrorMessage()).doesNotContain(VETO);
    }

    @Test
    @DisplayName("on a resume: the snapshot handed back is the conversation as it was restored, without the goal")
    void cancelledDuringAResume() {
        llm.responses.add(LlmResponse.text("first answer"));
        final SubagentExecutionResult first = execute(null, "first goal");
        onStops.clear();
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            spawner.requestInterrupt(InterruptReason.USER_SIGINT);
            return HookResult.success();
        });

        final SubagentExecutionResult result = execute(first.getSnapshot(), GOAL);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(onStops).containsExactly(false);
        assertThat(llm.calls).as("only the first run called the model").isEqualTo(1);
        assertThat(result.getSnapshot().getConversationHistory()).extracting(Message::getContent)
                .containsExactly("first goal", "first answer");
    }

    @Test
    @DisplayName("through the task handle (signal and thread interrupt): INTERRUPTED, onStop runs, nothing to resume")
    void stoppedThroughTheTaskHandle() throws Exception {
        // Task.stop trips the task's signal and interrupts the worker. The hook executor answers the thread interrupt
        // with a block and re-arms the flag; before EE-94 the fork read that block and ended BLOCKED, with no onStop.
        final CountDownLatch hookRunning = new CountDownLatch(1);
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            hookRunning.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HookResult.success();
        });
        final InMemorySessionSnapshotStore snapshots = new InMemorySessionSnapshotStore();
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(subagent());
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor(), pool,
                new DefaultHookExecutionManager(), new InMemorySubagentBehaviorRegistry());
        final SubagentLaunchContext env = SubagentLaunchContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagentRegistry(subagents)
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(hooks)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).sessionSnapshotStore(snapshots).build();

        final CompletableFuture<SubagentExecutionResult> running = manager.executeInBackground(env, "task-stopped",
                "explorer", GOAL, "");
        assertThat(hookRunning.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.stop("task-stopped")).isTrue();
        final SubagentExecutionResult result = running.get(10, TimeUnit.SECONDS);

        assertInterruptedBeforeStart(result);
        assertThat(result.getSnapshot().getConversationHistory()).isEmpty();
        assertThat(snapshots.load("task-stopped")).as("nothing ran, so there is nothing to resume").isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private void assertInterruptedBeforeStart(SubagentExecutionResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        assertThat(result.getMetadata().getIterationCount()).isZero();
        assertThat(llm.calls).isZero();
        // The hook body ran: the thread interrupt the hook executor re-armed was taken off the thread first, or the
        // wait for this hook would have been cut at once.
        assertThat(onStops).as("onStop fired, and its hook actually ran").containsExactly(false);
    }

    private static Subagent subagent() {
        return Subagent.of("explorer", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of("you are explorer"));
    }

    private DefaultSubagentExecutor executor() {
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
    }

    private SubagentExecutionResult execute(SessionSnapshot previousSnapshot) {
        return execute(previousSnapshot, GOAL);
    }

    private SubagentExecutionResult execute(SessionSnapshot previousSnapshot, String goal) {
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(hooks).parentCancellationSignal(spawner.getSignal()).build();
        return executor().execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal(goal)
                .previousSnapshot(previousSnapshot).build());
    }

    /** Returns queued responses in order and counts the calls. */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> seenMessages = new ArrayList<>();
        private int calls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls++;
            seenMessages.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
