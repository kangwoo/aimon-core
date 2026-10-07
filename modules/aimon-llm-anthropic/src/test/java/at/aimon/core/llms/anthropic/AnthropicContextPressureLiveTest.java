package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llms.anthropic.ContextPressureRun.ContextPressureResult;
import at.aimon.core.llms.anthropic.ContextPressureRun.Status;
import at.aimon.core.llms.anthropic.ContextPressureTasks.ContextPressureTask;
import at.aimon.core.llms.anthropic.ContextPressureTasks.TaskKind;

/**
 * The two context engines compared on the tasks of {@link ContextPressureTasks} against the real Anthropic API: the
 * baseline a third engine would have to beat (context-engine §13.11).
 *
 * <p>
 * <strong>What it produces.</strong> A report, not a verdict. Each level in {@code AIMON_CONTEXT_PRESSURE} is run for
 * each of the three tasks on the default and the rolling engine, the same task instance for both, and every cell's
 * accuracy and cost go to {@code build/reports/context-engine-pressure/} under this module, rewritten after each cell
 * so that a run that dies keeps what it already paid for. Accuracy is what is being measured, so nothing here asserts
 * it: the assertions are the harness's own — a cell at level 2 or more that reached its pressure did compact, and the
 * report has a row for every cell.
 *
 * <p>
 * <strong>Cost, and why a key is not enough to run it.</strong> A scripted model makes about 130 calls at level 0.3,
 * 510 at level 2 and 960 at level 4 over the six cells, sending about 0.4M, 2.8M and 5.9M estimated tokens; a real
 * model makes at least that. That is two orders of magnitude over the rest of the live tier, so this class is gated on
 * {@code ANTHROPIC_KEY} like {@link AnthropicContextEngineLiveTest} <em>and</em> skips itself unless
 * {@code AIMON_CONTEXT_PRESSURE} lists the levels to run — a shell that merely has the key exported does not pay for
 * it. How to run it and how to read the report is under {@code CONTRIBUTING.md} › Live-API tests.
 *
 * <p>
 * The mechanics are held by a keyless twin, {@link ContextPressureRunTest}, in every ordinary build.
 */
@DisplayName("Context engines compared under view pressure against the real Anthropic API")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY", matches = ".+")
class AnthropicContextPressureLiveTest {

    /** The model {@link AnthropicContextEngineLiveTest} runs on. */
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private static final long SEED = 1;

    @TempDir
    Path tempDir;

    // One method: were the cells separate tests, a partial re-run would leave half a report.
    @Test
    @DisplayName("every task on both engines at each level asked for, written to the pressure report")
    void comparesTheEnginesUnderPressure() throws Exception {
        final String optIn = System.getenv(ContextPressureRun.OPT_IN_VARIABLE);
        assumeTrue(optIn != null && !optIn.isBlank(),
                ContextPressureRun.OPT_IN_VARIABLE + " is not set, so this billed comparison is skipped; set it to"
                        + " the levels to run, e.g. 0.3,2 (see CONTRIBUTING.md, Live-API tests)");
        final List<Double> levels = ContextPressureRun.parseLevels(optIn);
        final Path report = ContextPressureRun.reportFile("Anthropic", MODEL);
        final List<ContextPressureResult> results = new ArrayList<>();
        try (AnthropicLlmClient client = new AnthropicLlmClient(AnthropicConfig.builder()
                .apiKey(System.getenv("ANTHROPIC_KEY")).model(MODEL).maxTokens(2000).build())) {
            final String title = client.getProviderName() + " " + MODEL;
            for (double level : levels) {
                for (TaskKind kind : TaskKind.values()) {
                    final ContextPressureTask task = ContextPressureTasks.generate(kind, SEED, level,
                            ContextEngineLiveRig.COMPARISON_LIMITS.getEffectiveContextWindow(),
                            new HeuristicTokenEstimator());
                    for (ContextEngineKind engine : ContextEngineKind.values()) {
                        final ContextEngineLiveRig rig = ContextEngineLiveRig.forComparison(engine, client,
                                LlmModel.builder().build(), tempDir.resolve("cell-" + results.size()), task.reports());
                        results.add(ContextPressureRun.run(task, engine, rig));
                        ContextPressureRun.write(report,
                                ContextPressureRun.render(title, LocalDate.now().toString(), results));
                    }
                }
            }
        }

        assertThat(report).as("the pressure report").exists();
        assertThat(results).hasSize(levels.size() * TaskKind.values().length * ContextEngineKind.values().length);
        // Not the model's mood: OK means the pressure was reached, and no engine holds two windows without compacting.
        assertThat(results).filteredOn(result -> result.status() == Status.OK && result.pressureRequested() >= 2)
                .allSatisfy(result -> assertThat(
                        result.compactionsInPressurePhase().values().stream().mapToInt(Integer::intValue).sum())
                        .as("compactions in the pressure phase of %s on %s at %s", result.task(), result.engine(),
                                result.pressureRequested())
                        .isPositive());
    }
}
