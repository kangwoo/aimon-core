package at.aimon.core.tools.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowScript;

/**
 * Covers {@link WorkflowTool}'s background-run branch: without a runner it returns an error (never throws); with
 * an
 * injected runner it submits the run and returns the run id immediately, never touching the foreground execution path.
 */
@DisplayName("WorkflowTool — background run mode")
class WorkflowToolBackgroundModeTest {

    private final LlmModel model = LlmModel.builder().name("gpt-4").build();

    @Test
    @DisplayName("background mode with no runner configured returns an error (not a throw)")
    void backgroundModeWithoutRunnerReturnsError() {
        final WorkflowTool tool = new WorkflowTool(model, new InMemorySubagentRegistry(), new DefaultToolRegistry(),
                new DefaultHookRegistry(), Environment.createDefault(), mock(SubagentExecutionManager.class),
                List.of());

        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "x", "mode", "background")),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Background mode is not available");
    }

    @Test
    @DisplayName("background mode submits to the injected runner and returns the run id immediately")
    void backgroundModeSubmitsToRunner() {
        final RecordingRunner runner = new RecordingRunner();
        final WorkflowTool tool = tool(runner);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "should we ship?", "mode", "background")),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(runner.recordedRunId).isNotNull();
        assertThat(runner.recordedRunId.value()).contains("workflow");
        assertThat(result.getContent()).contains(runner.recordedRunId.value()).contains("/runs");
    }

    @Test
    @DisplayName("an unrecognized mode returns an error rather than silently running foreground")
    void invalidModeReturnsError() {
        final ToolResult result = tool(new RecordingRunner())
                .execute(ToolInput.of(Map.of("prompt", "x", "mode", "async")), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent())
                .contains("Parameter 'mode' must be one of [foreground, background], but was 'async'. "
                        + "The tool was not executed.");
    }

    @Test
    @DisplayName("an explicitly empty mode is rejected rather than read as foreground")
    void emptyModeReturnsError() {
        // The old hand-written check tolerated "" and fell through to foreground. The declared set never allowed it,
        // so the leniency contradicted what the model was told; binding now enforces the declaration.
        final ToolResult result = tool(new RecordingRunner()).execute(ToolInput.of(Map.of("prompt", "x", "mode", "")),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Parameter 'mode' must be one of [foreground, background]");
    }

    @Test
    @DisplayName("the run id is derived from the full request: identical requests share an id, differing ones do not")
    void runIdReflectsFullRequest() {
        final RecordingRunner runner = new RecordingRunner();
        final WorkflowTool tool = tool(runner);

        tool.execute(ToolInput.of(Map.of("prompt", "migrate?", "perspectives", "technical,risk", "mode", "background")),
                ToolContext.empty());
        final RunId first = runner.recordedRunId;
        // Same prompt but different perspectives must NOT collapse onto the same run (the earlier hashCode-only bug).
        tool.execute(ToolInput.of(Map.of("prompt", "migrate?", "perspectives", "cost", "mode", "background")),
                ToolContext.empty());
        final RunId second = runner.recordedRunId;
        // An identical request maps back to the first id (idempotent join, not a duplicate run).
        tool.execute(ToolInput.of(Map.of("prompt", "migrate?", "perspectives", "technical,risk", "mode", "background")),
                ToolContext.empty());
        final RunId third = runner.recordedRunId;

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(third.value()).isEqualTo(first.value());
    }

    private WorkflowTool tool(WorkflowRunner backgroundRunner) {
        return new WorkflowTool(model, new InMemorySubagentRegistry(), new DefaultToolRegistry(),
                new DefaultHookRegistry(), Environment.createDefault(), mock(SubagentExecutionManager.class), List.of(),
                backgroundRunner);
    }

    /**
     * Minimal {@link WorkflowRunner} that records the {@link RunId} handed to {@code runInBackground} and returns
     * an already-completed handle. The foreground/control-plane methods are never exercised by these tests.
     */
    private static final class RecordingRunner implements WorkflowRunner {

        private RunId recordedRunId;

        @Override
        public <T> T run(WorkflowScript<T> script, RunId runId) {
            throw new UnsupportedOperationException("foreground run not used in background tests");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId) {
            this.recordedRunId = runId;
            return (RunHandle<T>) new RunHandle<>(runId, CompletableFuture.completedFuture((Object) "ignored"));
        }

        @Override
        public boolean stop(RunId runId) {
            throw new UnsupportedOperationException("stop not used in background tests");
        }

        @Override
        public List<WorkflowRun> list(RunQuery query) {
            throw new UnsupportedOperationException("list not used in background tests");
        }

        @Override
        public Optional<WorkflowRun> status(RunId runId) {
            throw new UnsupportedOperationException("status not used in background tests");
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
