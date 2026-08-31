package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowScript;

@DisplayName("RunsCommandHandler — /runs list / status / stop")
class RunsCommandHandlerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final RunId RUN_A = RunId.from("workflow", "aaa111");
    private static final RunId RUN_B = RunId.from("workflow", "bbb222");

    @Test
    @DisplayName("a null runner reports that background runs are disabled")
    void disabledWhenNoRunner() {
        assertThat(new RunsCommandHandler(null).handle("")).contains("disabled", "cli.enableWorkflow");
    }

    @Test
    @DisplayName("an empty command (and 'list') lists every run with id and state")
    void listsRuns() {
        final FakeRunner runner = new FakeRunner();
        runner.runs.add(run(RUN_A, WorkflowRunState.RUNNING));
        runner.runs.add(run(RUN_B, WorkflowRunState.COMPLETED));
        final RunsCommandHandler handler = new RunsCommandHandler(runner);

        final String out = handler.handle("");

        assertThat(out).contains("Workflow runs (2)", RUN_A.value(), "RUNNING", RUN_B.value(), "COMPLETED");
        assertThat(handler.handle("list")).isEqualTo(out);
    }

    @Test
    @DisplayName("list with no runs reports none")
    void listsNothing() {
        assertThat(new RunsCommandHandler(new FakeRunner()).handle("list")).isEqualTo("No workflow runs.");
    }

    @Test
    @DisplayName("status shows a known run and reports an unknown one")
    void status() {
        final FakeRunner runner = new FakeRunner();
        runner.runs.add(run(RUN_A, WorkflowRunState.RUNNING));
        final RunsCommandHandler handler = new RunsCommandHandler(runner);

        assertThat(handler.handle("status " + RUN_A.value())).contains(RUN_A.value(), "RUNNING");
        assertThat(handler.handle("status " + RUN_B.value())).contains("No run found");
        assertThat(handler.handle("status")).contains("Usage");
        assertThat(handler.handle("status not a valid id")).contains("Invalid run id");
    }

    @Test
    @DisplayName("stop trips a live run and reports a missing one, forwarding the parsed run id")
    void stop() {
        final FakeRunner runner = new FakeRunner();
        runner.liveRuns.add(RUN_A.value());
        final RunsCommandHandler handler = new RunsCommandHandler(runner);

        assertThat(handler.handle("stop " + RUN_A.value())).contains("Requested stop", RUN_A.value());
        assertThat(runner.stopped).containsExactly(RUN_A.value());
        assertThat(handler.handle("stop " + RUN_B.value())).contains("No live run");
        assertThat(handler.handle("stop")).contains("Usage");
    }

    @Test
    @DisplayName("an unknown sub-command prints usage")
    void unknownSubcommand() {
        assertThat(new RunsCommandHandler(new FakeRunner()).handle("frobnicate")).contains("Usage: /runs");
    }

    private static WorkflowRun run(RunId runId, WorkflowRunState state) {
        return WorkflowRun.builder().runId(runId).scriptName(runId.scriptName()).state(state).startTime(T0).build();
    }

    /** Minimal {@link WorkflowRunner} exposing a preset run list + live-run set for the control-plane methods. */
    private static final class FakeRunner implements WorkflowRunner {
        private final List<WorkflowRun> runs = new ArrayList<>();
        private final List<String> liveRuns = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();

        @Override
        public List<WorkflowRun> list(RunQuery query) {
            return List.copyOf(runs);
        }

        @Override
        public Optional<WorkflowRun> status(RunId runId) {
            return runs.stream().filter(r -> r.getRunId().value().equals(runId.value())).findFirst();
        }

        @Override
        public boolean stop(RunId runId) {
            if (liveRuns.contains(runId.value())) {
                stopped.add(runId.value());
                return true;
            }
            return false;
        }

        @Override
        public <T> T run(WorkflowScript<T> script, RunId runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId) {
            return new RunHandle<>(runId, CompletableFuture.completedFuture(null));
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
