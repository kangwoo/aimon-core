package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.task.InMemoryTaskResultStore;
import at.aimon.core.subagent.task.TaskResult;
import at.aimon.core.tools.task.AgentOutputTool;

/**
 * The manager side of a structured result: every result of a fork whose policy requires one says so, including the
 * results the manager makes itself, so a missing report is shown as missing on every path.
 */
@DisplayName("DefaultSubagentExecutionManager structured result")
class DefaultSubagentExecutionManagerStructuredResultTest {

    private static final String NAME = "worker";
    private static final SubagentExecutionPolicy POLICY = SubagentExecutionPolicy.builder("review-worker")
            .resultSchema(Map.of("type", "object")).build();

    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("a fork that threw: the manager's failure result is marked required")
    void failureMadeByTheManager() {
        final SubagentExecutionResult result = new DefaultSubagentExecutionManager(executor((c, r) -> {
            throw new IllegalStateException("boom");
        }), bgPool, null).execute(launch().executionPolicy(POLICY).build(), "task-1", NAME, "review", "");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isStructuredResultRequired()).isTrue();
    }

    @Test
    @DisplayName("an unconfirmed exit is marked required")
    void lingeringRunIsMarkedRequired() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor((c, r) -> {
            while (true) {
                try {
                    if (release.await(30, TimeUnit.SECONDS)) {
                        return success();
                    }
                } catch (InterruptedException e) {
                    // ignores interrupts on purpose
                }
            }
        }), bgPool, null);
        final SubagentExecutionPolicy timed = SubagentExecutionPolicy.builder("review-worker")
                .resultSchema(Map.of("type", "object")).maxWallClock(Duration.ofMillis(100))
                .exitGracePeriod(Duration.ofMillis(100)).build();
        try {
            final SubagentExecutionResult result = manager.execute(launch().executionPolicy(timed).build(), "task-1",
                    NAME, "review", "");

            assertThat(result.isExitConfirmed()).isFalse();
            assertThat(result.isStructuredResultRequired()).isTrue();
            assertThat(result.getStructuredResult()).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("background: a failed fork's stored result is required, and AgentOutput shows it missing")
    void backgroundFailureIsStoredAsMissing() throws Exception {
        final InMemoryTaskResultStore store = new InMemoryTaskResultStore();
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor((c, r) -> {
            throw new IllegalStateException("boom");
        }), bgPool, null);

        manager.executeInBackground(launch().executionPolicy(POLICY).taskResultStore(store).build(), "task-bg", NAME,
                "review", "").get(10, TimeUnit.SECONDS);

        // Blocks until the task is terminal, by when its result has been saved.
        final ToolResult output = new AgentOutputTool(manager, null, store)
                .execute(ToolInput.of(Map.of("taskId", "task-bg", "block", true)), ToolContext.empty());
        assertThat(output.getContent()).contains(SubagentResultFormatter.STRUCTURED_RESULT_MISSING_LINE);
        final TaskResult stored = store.load("task-bg").orElseThrow();
        assertThat(stored.isStructuredResultRequired()).isTrue();
    }

    @Test
    @DisplayName("without a result schema nothing is marked")
    void noSchemaNothingMarked() {
        final SubagentExecutionResult result = new DefaultSubagentExecutionManager(executor((c, r) -> {
            throw new IllegalStateException("boom");
        }), bgPool, null).execute(
                launch().executionPolicy(SubagentExecutionPolicy.builder("w").maxToolCalls(2).build()).build(),
                "task-1", NAME, "review", "");

        assertThat(result.isStructuredResultRequired()).isFalse();
    }

    private static SubagentExecutor executor(
            BiFunction<SubagentExecutionContext, SubagentExecutionRequest, SubagentExecutionResult> body) {
        return new SubagentExecutor() {
            @Override
            public SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request) {
                return body.apply(context, request);
            }

            @Override
            public Set<SubagentPolicyCapability> supportedPolicyCapabilities() {
                return EnumSet.allOf(SubagentPolicyCapability.class);
            }
        };
    }

    private static SubagentLaunchContext.Builder launch() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(NAME).systemPrompt("you review").build());
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(registry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static SubagentExecutionResult success() {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success("ok", SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
