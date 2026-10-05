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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.config.hook.HookConfigMerger;
import at.aimon.core.config.hook.HookConfigSource;
import at.aimon.core.config.hook.HookRegistryApplier;
import at.aimon.core.config.hook.JacksonHookConfigParser;
import at.aimon.core.config.hook.LayeredHookConfig;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
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
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;

/**
 * EE-70: an {@code onStart} hook that blocks stops the fork before its first LLM call, the way it stops a main
 * execution's turn. The fork ends as a failed result whose message carries the hooks' reasons; {@code onStop} does not
 * fire, because nothing started.
 */
@DisplayName("DefaultSubagentExecutor: an onStart hook that blocks stops the fork")
class DefaultSubagentExecutorOnStartBlockTest {

    private static final String REASON = "FORK-REFUSED-5d20";
    private static final String REFUSED_GOAL = "REFUSED-GOAL-7a41";

    private final VirtualShell forkShell = mock(VirtualShell.class);
    private final ExecutionEnvironment forkEnvironment = TestExecutionEnvironments.builder().shell(forkShell)
            .workingDirectory("/fork").build();
    private final StubLlmClient llm = new StubLlmClient();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    private final List<Boolean> onStops = new ArrayList<>();
    private final StringBuilder streamed = new StringBuilder();

    DefaultSubagentExecutorOnStartBlockTest() {
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.add(context.isSuccess());
            return HookResult.success();
        });
    }

    @Test
    @DisplayName("the fork makes no LLM call, ends as a failure carrying the reason, and fires no onStop")
    void aBlockEndsTheForkBeforeItsFirstLlmCall() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));

        final SubagentExecutionResult result = execute(null);

        assertThat(llm.calls).isZero();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.BLOCKED);
        assertThat(result.getMetadata().getIterationCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isZero();
        assertThat(result.getErrorMessage()).contains("OnStart", "SUBAGENT", "explorer", REASON);
        assertThat(onStops).as("a fork that never started has nothing to stop").isEmpty();
        assertThat(streamed.toString()).contains("[ended: ", REASON).doesNotContain("[iteration");
    }

    @Test
    @DisplayName("one blocking hook among several stops the fork, and only its reason is reported")
    void oneBlockAmongSeveralHooksStopsTheFork() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.withFeedback("ADVICE-91c3"));
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.success());

        final SubagentExecutionResult result = execute(null);

        assertThat(llm.calls).isZero();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains(REASON).doesNotContain("ADVICE-91c3");
    }

    @Test
    @DisplayName("feedback from a hook that does not block still reaches the model as a system reminder")
    void advisoryFeedbackIsStillFedBack() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.withFeedback("ADVICE-91c3"));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = execute(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.seenMessages.get(0)).extracting(Message::getContent)
                .anyMatch(content -> content.contains("<system-reminder") && content.contains("ADVICE-91c3"));
        assertThat(onStops).containsExactly(true);
    }

    @Test
    @DisplayName("a fork's onStart carries the fork's cancellation signal, so a hook command stops when it is cancelled")
    void onStartCarriesTheForksCancellationSignal() {
        final List<java.util.Optional<at.aimon.core.agent.interrupt.CancellationSignal>> seen = new ArrayList<>();
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            seen.add(context.getExecutionCancellation());
            return HookResult.success();
        });
        llm.responses.add(LlmResponse.text("done"));

        assertThat(execute(null).isSuccess()).isTrue();

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isPresent();
        assertThat(seen.get(0).get().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a blocked resume keeps the refused goal out of its snapshot, so a later resume never replays it")
    void aBlockedResumeDoesNotBreakALaterOne() {
        llm.responses.add(LlmResponse.text("first answer"));
        final SubagentExecutionResult first = execute(null, "first goal");
        final OnStartHook gate = context -> HookResult.block(REASON);
        hooks.register(HookEventType.ON_START, gate);

        final SubagentExecutionResult blocked = execute(first.getSnapshot(), REFUSED_GOAL);

        assertThat(blocked.isSuccess()).isFalse();
        assertThat(blocked.getErrorMessage()).contains(REASON);
        assertThat(llm.calls).isEqualTo(1);
        assertThat(blocked.getSnapshot().getConversationHistory()).extracting(Message::getContent)
                .containsExactlyElementsOf(
                        first.getSnapshot().getConversationHistory().stream().map(Message::getContent).toList());

        hooks.unregister(HookEventType.ON_START, gate);
        llm.responses.add(LlmResponse.text("second answer"));
        final SubagentExecutionResult resumed = execute(blocked.getSnapshot(), "third goal");

        assertThat(resumed.isSuccess()).isTrue();
        assertThat(resumed.getSnapshot().getSessionId()).isEqualTo(first.getSnapshot().getSessionId());
        assertThat(llm.seenMessages.get(1)).extracting(Message::getContent)
                .containsExactly("first goal", "first answer", "third goal")
                .noneMatch(content -> content.contains(REFUSED_GOAL));
    }

    @Test
    @DisplayName("a blocked fresh fork hands back an empty transcript, so a background run saves nothing to resume")
    void aBlockedFreshForkIsNotResumable() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));
        final InMemorySessionSnapshotStore snapshots = new InMemorySessionSnapshotStore();
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(subagent());
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor(), pool,
                    new DefaultHookExecutionManager(), new InMemorySubagentBehaviorRegistry());
            final SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder()
                    .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagentRegistry(subagents)
                    .toolRegistry(new DefaultToolRegistry()).hookRegistry(hooks)
                    .defaultModel(LlmModel.builder().name("gpt-4").build()).executionEnvironment(forkEnvironment)
                    .executionEnvironmentProvider(request -> forkEnvironment).sessionSnapshotStore(snapshots).build();

            final SubagentExecutionResult result = manager
                    .executeInBackground(env, "task-blocked", "explorer", REFUSED_GOAL, "").join();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains(REASON);
            assertThat(result.getSnapshot().getConversationHistory()).isEmpty();
            assertThat(snapshots.load("task-blocked")).as("nothing ran, so there is nothing to resume").isEmpty();
            assertThat(llm.calls).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a hooks.json onStart command handler that exits 2 stops the fork")
    void aHooksJsonHandlerThatExitsTwoStopsTheFork() throws Exception {
        final VirtualShell hostShell = mock(VirtualShell.class);
        when(hostShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(2, "", REASON, Duration.ofMillis(1)));
        final LayeredHookConfig layered = LayeredHookConfig.builder()
                .put(HookConfigSource.PROJECT, new JacksonHookConfigParser().parse(
                        "{\"hooks\":{\"onStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"./gate.sh\"}]}]}}"))
                .build();
        new HookRegistryApplier(new HostShellActionExecutor(hostShell), null, null, Map.of())
                .apply(new HookConfigMerger().merge(layered), hooks);

        final SubagentExecutionResult result = execute(null);

        assertThat(llm.calls).isZero();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("OnStart", REASON);
        assertThat(onStops).isEmpty();
    }

    @Test
    @DisplayName("a skill-frontmatter onStart shell hook that exits 2 in the fork's shell stops the fork")
    void aSkillFrontmatterHookThatExitsTwoStopsTheFork() throws Exception {
        when(forkShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(2, "", REASON, Duration.ofMillis(1)));
        registerSkillOnStart(Map.of("action", Map.of("type", "shell", "command", "./gate.sh")));

        final SubagentExecutionResult result = execute(null);

        assertThat(llm.calls).isZero();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("OnStart", REASON);
        assertThat(onStops).isEmpty();
    }

    @Test
    @DisplayName("a skill-frontmatter onStart shell hook whose command cannot be run stops the fork, unless failOpen")
    void aSkillHookThatCannotRunStopsTheForkUnlessFailOpen() throws Exception {
        when(forkShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new IllegalStateException("shell is gone"));
        registerSkillOnStart(Map.of("action", Map.of("type", "shell", "command", "./gate.sh")));

        final SubagentExecutionResult blocked = execute(null);

        assertThat(llm.calls).isZero();
        assertThat(blocked.isSuccess()).isFalse();
        assertThat(blocked.getErrorMessage()).contains("guard hook 'gate-skill' (onStart)", "fail-closed");

        final DefaultSubagentExecutorOnStartBlockTest open = new DefaultSubagentExecutorOnStartBlockTest();
        when(open.forkShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new IllegalStateException("shell is gone"));
        open.registerSkillOnStart(Map.of("action", Map.of("type", "shell", "command", "./gate.sh"), "failOpen", true));
        open.llm.responses.add(LlmResponse.text("done"));

        assertThat(open.execute(null).isSuccess()).isTrue();
        assertThat(open.llm.calls).isEqualTo(1);
    }

    /** Registers what {@code SkillHookSetParser} builds from a skill's frontmatter, as the skill view would expose. */
    private void registerSkillOnStart(Map<String, Object> entry) {
        final SkillHookSet set = new SkillHookSetParser(new DefaultShellActionExecutor()).parse("gate-skill",
                Map.of("onStart", List.of(entry)));
        set.getOnStartHooks().forEach(hook -> hooks.register(HookEventType.ON_START, hook));
    }

    private SubagentExecutionResult execute(SessionSnapshot previousSnapshot) {
        return execute(previousSnapshot, "go");
    }

    private static Subagent subagent() {
        return Subagent.of("explorer", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of("you are explorer"));
    }

    private DefaultSubagentExecutor executor() {
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
    }

    private SubagentExecutionResult execute(SessionSnapshot previousSnapshot, String goal) {
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(hooks).executionEnvironment(forkEnvironment)
                .executionEnvironmentProvider(request -> forkEnvironment).outputSink(streamed::append)
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
        return executor().execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal(goal)
                .previousSnapshot(previousSnapshot).build());
    }

    /** Returns queued responses in order, counting calls and keeping what each one was sent. */
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
