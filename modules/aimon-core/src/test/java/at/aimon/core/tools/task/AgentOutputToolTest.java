package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.InMemoryTaskOutputStore;
import at.aimon.core.subagent.task.InMemoryTaskResultStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.core.subagent.task.TaskResult;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tests for {@link AgentOutputTool}, which reads everything it reports from stores.
 *
 * <p>
 * The two collaborators these tests drive are the ones the tool actually consults: a {@link SubagentTaskController} for
 * lifecycle state (also the ownership gate) and a {@link TaskResultStore} for what a settled task produced. They are
 * written independently on purpose — a task can be terminal with no stored result, and the tool must say so rather than
 * wait forever.
 */
class AgentOutputToolTest {

    private FakeTaskController controller;
    private TaskResultStore resultStore;
    private AgentOutputTool tool;

    @BeforeEach
    void setUp() {
        controller = new FakeTaskController();
        resultStore = new InMemoryTaskResultStore();
        tool = new AgentOutputTool(controller, null, resultStore);
    }

    private SubagentExecutionResult successResult() {
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(2)
                .tokenUsage(TokenUsage.of(40, 60, 100)).timestamps(now, now).build();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        return SubagentExecutionResult.success("the answer", snapshot, metadata);
    }

    /** Registers a task that already settled, with its result saved — the ordering the writer guarantees. */
    private void completed(String taskId) {
        resultStore.save(taskId, TaskResult.from(successResult()));
        controller.add(taskId, null);
        controller.settle(taskId, BackgroundTaskState.COMPLETED);
    }

    @Test
    void constructorRejectsNullController() {
        assertThatNullPointerException().isThrownBy(() -> new AgentOutputTool(null))
                .withMessageContaining("Task controller");
    }

    @Test
    void executeRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> tool.execute(null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(ToolInput.of(), null));
    }

    @Test
    void definitionExposesToolName() {
        assertThat(tool.getDefinition().getName()).isEqualTo(AgentOutputTool.TOOL_NAME);
    }

    @Test
    void executeReturnsErrorForUnknownTask() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "missing")), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not found").contains("missing");
    }

    @Test
    void executeReturnsRunningMessageForNonBlockingActiveTask() {
        controller.add("running", null);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "running", "block", false)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("running").contains("still running");
    }

    @Test
    void executeReturnsFormattedResultForCompletedTask() {
        completed("done");

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "done", "block", false)), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Background Task Result").contains("Task ID: done")
                .contains("Status: SUCCESS").contains("the answer").contains("Iterations: 2").contains("Tokens: 100");
    }

    @Test
    void executeReturnsFormattedResultWhenBlockingOnSettledTask() {
        completed("done");

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "done", "block", true)), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("the answer");
    }

    @Test
    void executeReportsFailureResultForFailedTask() {
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2))
                .timestamps(now, now).build();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        resultStore.save("bad", TaskResult.from(SubagentExecutionResult.failure("kaboom", snapshot, metadata)));
        controller.add("bad", null);
        controller.settle("bad", BackgroundTaskState.FAILED);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "bad", "block", true)), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Status: FAILURE").contains("kaboom");
    }

    @Test
    void settledTaskWithoutStoredResultReportsThatItProducedNone() {
        // The store is wired but holds nothing for this task: it produced no result, or the result was evicted.
        controller.add("empty", null);
        controller.settle("empty", BackgroundTaskState.KILLED);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "empty", "block", true)), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("finished with status KILLED").contains("produced none");
    }

    @Test
    void settledTaskWithoutResultStoreReportsRetentionNotConfigured() {
        // No result store at all — a configuration fact, kept distinct from "the task produced nothing".
        controller.add("done", null);
        controller.settle("done", BackgroundTaskState.COMPLETED);
        AgentOutputTool storelessTool = new AgentOutputTool(controller);

        ToolResult result = storelessTool.execute(ToolInput.of(Map.of("taskId", "done", "block", true)),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("finished with status COMPLETED")
                .contains("result retention is not configured");
    }

    @Test
    void settledTaskWithoutResultPointsAtProgressLogWhenOutputStoreIsWired() {
        controller.add("empty", null);
        controller.settle("empty", BackgroundTaskState.FAILED);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, new InMemoryTaskOutputStore(), resultStore);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "empty", "block", false)),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("from_offset=0").contains(AgentOutputTool.TOOL_NAME);
    }

    @Test
    void executeRequiresTaskIdParameter() {
        ToolResult result = tool.execute(ToolInput.of(Map.of()), ToolContext.empty());

        assertThat(result.isError()).isTrue();
    }

    // --- Bounded blocking wait (wait_up_to) ------------------------------------------------------

    @Test
    void blockingWithWaitUpTo_StillRunningPastDeadline_ReportsStillRunning() {
        controller.add("slow", null);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "slow", "block", true, "wait_up_to", 1)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("still running after 1s");
    }

    @Test
    void blockingWithNonPositiveWaitUpTo_DegradesToSinglePoll() {
        controller.add("slow", null);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "slow", "block", true, "wait_up_to", 0)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("still running after 0s");
    }

    @Test
    void blockingWithWaitUpTo_SettlesWithinDeadline_ReturnsResult() {
        controller.add("soon", null);
        resultStore.save("soon", TaskResult.from(successResult()));
        CompletableFuture.runAsync(() -> {
            sleep(50);
            // Result first, terminal state second — the ordering the tool's poll relies on.
            controller.settle("soon", BackgroundTaskState.COMPLETED);
        });

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "soon", "block", true, "wait_up_to", 5)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("the answer");
    }

    @Test
    void blockingReportsNotFoundWhenTaskDisappearsMidWait() {
        controller.add("evicted", null);
        CompletableFuture.runAsync(() -> {
            sleep(50);
            controller.remove("evicted");
        });

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "evicted", "block", true, "wait_up_to", 5)),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not found").contains("evicted");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Incremental live-output tailing (from_offset) ---------------------------------------------

    @Test
    void fromOffsetWithoutStoreReportsStreamingNotConfigured() {
        controller.add("any", null);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "any", "from_offset", 0)), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("not configured");
    }

    @Test
    void fromOffsetReadsIncrementalDeltaFromStore() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        store.append("live", "hello world");
        controller.add("live", null);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "live", "from_offset", 0)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("incremental").contains("hello world").contains("Next offset: 11")
                .contains("Status: RUNNING");
    }

    @Test
    void fromOffsetAdvancesCursorAndSignalsMore() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        store.append("live", "0123456789");
        controller.add("live", null);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store);

        ToolResult result = streamingTool
                .execute(ToolInput.of(Map.of("taskId", "live", "from_offset", 0, "max_chars", 4)), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("0123").contains("Next offset: 4").contains("more output available");
    }

    @Test
    void fromOffsetAtEndReportsCaughtUpWithNoNewOutput() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        store.append("live", "abc");
        controller.add("live", null);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "live", "from_offset", 3)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("caught up").contains("no new output");
    }

    @Test
    void fromOffsetMidStreamMarksSkippedHead() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        store.append("live", "0123456789");
        controller.add("live", null);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "live", "from_offset", 4)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("456789").contains("skipped");
    }

    // --- Result truncation -----------------------------------------------------------------------

    @Test
    void largeStoredResultIsTruncatedWithRetrievalPointer() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store, resultStore);

        String hugeAnswer = "Z".repeat(SubagentResultFormatter.DEFAULT_MAX_CHARS + 500);
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.of(1, 1, 2))
                .timestamps(now, now).build();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        resultStore.save("big", TaskResult.from(SubagentExecutionResult.success(hugeAnswer, snapshot, metadata)));
        controller.add("big", null);
        controller.settle("big", BackgroundTaskState.COMPLETED);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "big", "block", false)),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("chars omitted");
        // The retrieval pointer references the AgentOutput tool for this task.
        assertThat(result.getContent()).contains(AgentOutputTool.TOOL_NAME).contains("big");
        // The full answer is not inlined verbatim.
        assertThat(result.getContent().length()).isLessThan(hugeAnswer.length());
    }

    // --- Cross-agent isolation (owning-context gate) --------------------------------------------------

    private static final AgentRuntimeId CTX_SELF = AgentRuntimeId.of("agent:self");
    private static final AgentRuntimeId CTX_OTHER = AgentRuntimeId.of("agent:other");

    private static ToolContext contextOf(AgentRuntimeId id) {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, id).build();
    }

    @Test
    void deniesForeignTaskEvenWhenItsResultIsStored() {
        // The result store HAS the settled task's result, so absent a gate it would be served...
        resultStore.save("secret", TaskResult.from(successResult()));
        // ...but the control plane records the task as owned by another agent's context.
        controller.add("secret", CTX_OTHER);
        controller.settle("secret", BackgroundTaskState.COMPLETED);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "secret", "block", false)), contextOf(CTX_SELF));

        assertThat(result.isError()).isTrue();
        // Reported exactly like an absent task — the foreign task's existence (and its result) is not leaked.
        assertThat(result.getContent()).contains("Task not found").contains("secret");
        assertThat(result.getContent()).doesNotContain("the answer");
    }

    @Test
    void servesOwnTaskWhenContextMatches() {
        resultStore.save("mine", TaskResult.from(successResult()));
        controller.add("mine", CTX_SELF);
        controller.settle("mine", BackgroundTaskState.COMPLETED);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "mine", "block", false)), contextOf(CTX_SELF));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("the answer");
    }

    @Test
    void deniesForeignTaskOnIncrementalTailPath() {
        TaskOutputStore store = new InMemoryTaskOutputStore();
        store.append("secret", "confidential progress");
        controller.add("secret", CTX_OTHER);
        AgentOutputTool streamingTool = new AgentOutputTool(controller, store, resultStore);

        ToolResult result = streamingTool.execute(ToolInput.of(Map.of("taskId", "secret", "from_offset", 0)),
                contextOf(CTX_SELF));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not found").doesNotContain("confidential progress");
    }

    @Test
    void withoutCallerContextRetrievalIsUnscoped() {
        // The caller carries no agent runtime id (non-Orca path): the controller still resolves the task's state, but
        // the owning-context filter is not applied, so a task owned by another context is served. Legacy behavior.
        resultStore.save("done", TaskResult.from(successResult()));
        controller.add("done", CTX_OTHER);
        controller.settle("done", BackgroundTaskState.COMPLETED);

        ToolResult result = tool.execute(ToolInput.of(Map.of("taskId", "done", "block", false)), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("the answer");
    }

    /**
     * Minimal {@link SubagentTaskController} standing in for the background-task control plane. It is the tool's source
     * of lifecycle state as well as its ownership gate, so tests drive both through it: {@link #add} registers a
     * running task, {@link #settle} moves it to a terminal state, {@link #remove} evicts it. Mutations are visible
     * across threads because the blocking-wait tests settle a task from another one.
     */
    private static final class FakeTaskController implements SubagentTaskController {

        private final ConcurrentMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();

        void add(String taskId, AgentRuntimeId agentRuntimeId) {
            tasks.put(taskId,
                    BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                            .state(BackgroundTaskState.RUNNING).startTime(Instant.parse("2026-07-08T10:00:00Z"))
                            .agentRuntimeId(agentRuntimeId).build());
        }

        void settle(String taskId, BackgroundTaskState state) {
            tasks.computeIfPresent(taskId, (id, task) -> task.toBuilder().state(state).endTime(Instant.now()).build());
        }

        void remove(String taskId) {
            tasks.remove(taskId);
        }

        @Override
        public List<BackgroundTask> list(TaskQuery query) {
            final List<BackgroundTask> out = new ArrayList<>();
            for (BackgroundTask t : tasks.values()) {
                if (query.matches(t)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override
        public Optional<BackgroundTask> status(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public boolean stop(String taskId) {
            return false;
        }
    }
}
