package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;
import at.aimon.workflow.graaljs.exception.JsScriptCancelledException;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Wall-clock watchdog and per-run context lifecycle over the real engine: a compute-bound
 * loop that never trips the statement limit must be unwound by the wall-clock {@code close(true)}, the deadline
 * action (run-signal trip seam) must fire, and the shared engine must keep serving fresh runs after failed and
 * cancelled ones.
 */
@DisplayName("CancellationWatchdog — wall-clock backstop + context lifecycle")
class GraalJsWatchdogTest extends AbstractGraalJsRunTest {

    @Test
    @Timeout(30)
    @DisplayName("Wall-clock expiry unwinds compute-bound JS via close(true) and fires the deadline action")
    void wallClockUnwindsComputeBoundJs() {
        final AtomicBoolean deadlineFired = new AtomicBoolean();
        final JsSandboxConfig config = JsSandboxConfig.builder().maxStatements(Long.MAX_VALUE)
                .wallClockTimeout(Duration.ofMillis(300)).build();
        final GraalJsWorkflowScript script = new GraalJsWorkflowScript("while (true) { }", Map.of(), config, engines,
                SubagentResolver.inline(), null, () -> deadlineFired.set(true));
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env(), WorkflowRunnerOptions.defaults())) {
            assertThatThrownBy(() -> runner.run(script, RunId.from("wd-run")))
                    .isInstanceOf(JsScriptCancelledException.class);
        }
        assertThat(deadlineFired).isTrue();
    }

    @Test
    @Timeout(30)
    @DisplayName("Per-run contexts close on failure and cancellation; the shared engine keeps serving new runs")
    void engineSurvivesFailedAndCancelledRuns() {
        assertThatThrownBy(() -> run("throw new Error('boom');")).isInstanceOf(JsScriptException.class);
        assertThat(run("return 'alive';")).isEqualTo("alive");

        final JsSandboxConfig config = JsSandboxConfig.builder().maxStatements(Long.MAX_VALUE)
                .wallClockTimeout(Duration.ofMillis(300)).build();
        assertThatThrownBy(() -> run("while (true) { }", Map.of(), config))
                .isInstanceOf(JsScriptCancelledException.class);
        assertThat(run("return 'still alive';")).isEqualTo("still alive");
    }
}
