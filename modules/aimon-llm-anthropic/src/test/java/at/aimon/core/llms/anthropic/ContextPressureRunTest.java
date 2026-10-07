package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.llms.anthropic.ContextEngineLiveRig.MainCallRecorder;
import at.aimon.core.llms.anthropic.ContextPressureRun.Answer;
import at.aimon.core.llms.anthropic.ContextPressureRun.ContextPressureResult;
import at.aimon.core.llms.anthropic.ContextPressureRun.Status;
import at.aimon.core.llms.anthropic.ContextPressureTasks.ContextPressureTask;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Question;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Stratum;
import at.aimon.core.llms.anthropic.ContextPressureTasks.TaskKind;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Verdict;
import at.aimon.core.tools.session.SessionHistoryTool;

/**
 * The comparison's wiring and scoring without a provider: the three tasks of {@link ContextPressureTasks} run on both
 * engines of {@link ContextEngineLiveRig#forComparison} by a scripted client that plays a cooperative model.
 *
 * <p>
 * {@link AnthropicContextPressureLiveTest} runs only with a key and an opt-in, and one run of it costs millions of
 * tokens. Without this class nothing in a keyless build would notice that the needle task no longer makes the rolling
 * engine summarize, that a page is no longer large enough to be pruned, that the control level no longer has room, or
 * that the runner reports a lost fact as found — the person paying for the baseline would find out from a table that
 * measures nothing.
 *
 * <p>
 * The assertions are by stratum and by inequality. None names the question a cut falls on: a summary one token longer
 * would move it.
 */
@DisplayName("ContextPressureRun - the comparison tasks against a scripted model")
class ContextPressureRunTest {

    private static final TokenEstimator ESTIMATOR = new HeuristicTokenEstimator();
    private static final int EFFECTIVE = ContextEngineLiveRig.COMPARISON_LIMITS.getEffectiveContextWindow();
    private static final double CONTROL = ContextPressureTasks.CONTROL_PRESSURE;

    @TempDir
    Path tempDir;

    private final AtomicInteger rigs = new AtomicInteger();

    private static ContextPressureTask task(TaskKind kind, double pressure) {
        return ContextPressureTasks.generate(kind, 1, pressure, EFFECTIVE, ESTIMATOR);
    }

    private Cell run(ContextPressureTask task, ContextEngineKind kind, ScriptedModel model) {
        final ContextEngineLiveRig rig = ContextEngineLiveRig.forComparison(kind, model, LlmModel.builder().build(),
                tempDir.resolve("rig-" + rigs.incrementAndGet()), task.reports());
        final Cell cell = new Cell(rig, ContextPressureRun.run(task, kind, rig));
        assertConsistent(task, cell);
        return cell;
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    @DisplayName("control level: both engines answer everything, and neither compacts or looks anything up")
    void atTheControlLevelNothingCompacts(TaskKind kind) {
        final ContextPressureTask task = task(kind, CONTROL);
        for (ContextEngineKind engine : ContextEngineKind.values()) {
            final ContextPressureResult result = run(task, engine, new ScriptedModel(task)).result;

            assertThat(result.status()).as("%s on %s", kind, engine).isEqualTo(Status.OK);
            assertThat(result.count(Verdict.CORRECT)).isEqualTo(task.questions().size());
            assertThat(total(result.compactions())).as("compactions").isZero();
            assertThat(result.sessionHistoryCalls()).isZero();
            assertThat(result.refetchAttempts()).isZero();
            assertThat(result.sentViewTokensMax()).as("the control's room, in a run")
                    .isLessThanOrEqualTo((int) (0.75 * ContextEngineLiveRig.comparisonRollingThreshold()));
        }
    }

    @Test
    @DisplayName("needle on rolling: the units cannot be pruned, so it summarizes, and a lossy summary is looked past")
    void needleMakesRollingSummarize() {
        final ContextPressureTask task = task(TaskKind.NEEDLE, 2);
        final Cell cell = run(task, ContextEngineKind.ROLLING, new ScriptedModel(task));
        final ContextPressureResult result = cell.result;

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.compactionsInPressurePhase().get(CompactionKind.ROLLING)).as("rolling cycles")
                .isGreaterThanOrEqualTo(2);
        assertThat(result.compactionsInPressurePhase().get(CompactionKind.PRUNE)).as("nothing to prune").isZero();
        assertThat(result.compactions().get(CompactionKind.FULL)).as("rolling did not fall back").isZero();
        // Not one lookup per answer: a match is shown with its neighbours, and the next note is one of them.
        assertThat(result.answers()).allMatch(answer -> answer.verdict() == Verdict.CORRECT);
        assertThat(result.answers().get(0).sessionHistoryCalls()).as("the first fact is not in the view")
                .isGreaterThanOrEqualTo(1);
        assertThat(result.summaryFailures()).isZero();
        assertThat(cell.rig.summaries().lastRoles()).isNotEmpty()
                .allMatch(role -> role == Role.USER || role == Role.TOOL);
    }

    @Test
    @DisplayName("needle on rolling, faithful summary: the facts ride the summary and nothing is looked up")
    void aFaithfulSummaryCarriesTheFactsThroughRolling() {
        final ContextPressureTask task = task(TaskKind.NEEDLE, 2);
        final ContextPressureResult result = run(task, ContextEngineKind.ROLLING,
                new ScriptedModel(task).summarizing(Summary.FAITHFUL)).result;

        assertThat(result.compactionsInPressurePhase().get(CompactionKind.ROLLING)).isGreaterThanOrEqualTo(2);
        assertThat(result.count(Verdict.CORRECT)).isEqualTo(task.questions().size());
        assertThat(result.sessionHistoryCalls()).isZero();
    }

    @Test
    @DisplayName("needle on the default engine: a lossy summary loses the facts for good, a faithful one keeps them")
    void theDefaultEngineKeepsOnlyWhatItsSummaryKeeps() {
        final ContextPressureTask task = task(TaskKind.NEEDLE, 2);

        final ContextPressureResult lossy = run(task, ContextEngineKind.DEFAULT, new ScriptedModel(task)).result;
        assertThat(lossy.status()).isEqualTo(Status.OK);
        assertThat(lossy.compactionsInPressurePhase().get(CompactionKind.FULL)).isGreaterThanOrEqualTo(1);
        assertThat(lossy.sessionHistoryCalls()).as("the default engine has no lookup tool").isZero();
        assertThat(lossy.count(Verdict.ABSTAINED, Stratum.EARLY)).isEqualTo(task.questions().size());
        assertThat(lossy.accuracy()).isLessThan(1.0);

        final ContextPressureResult faithful = run(task, ContextEngineKind.DEFAULT,
                new ScriptedModel(task).summarizing(Summary.FAITHFUL)).result;
        assertThat(faithful.compactionsInPressurePhase().get(CompactionKind.FULL)).as("through a second summary too")
                .isGreaterThanOrEqualTo(2);
        assertThat(faithful.count(Verdict.CORRECT)).as("the default engine is not wired to score zero")
                .isEqualTo(task.questions().size());
    }

    @ParameterizedTest
    @EnumSource(value = TaskKind.class, names = {"KEY_VALUE", "LOG_TRIAGE"})
    @DisplayName("pages and dumps on rolling: a prune baseline - no summary in the pressure phase, and elided results"
            + " are read back")
    void largeUnitsArePrunedNotSummarized(TaskKind kind) {
        final ContextPressureTask task = task(kind, 2);
        final ContextPressureResult result = run(task, ContextEngineKind.ROLLING, new ScriptedModel(task)).result;

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.compactionsInPressurePhase().get(CompactionKind.PRUNE)).isGreaterThanOrEqualTo(1);
        assertThat(result.compactionsInPressurePhase().get(CompactionKind.ROLLING)).as("pruning was enough").isZero();
        assertThat(result.compactions().get(CompactionKind.FULL)).isZero();
        // A later early question may be answered from an earlier one's lookup, when the two share a page.
        final List<Answer> early = of(result, Stratum.EARLY);
        assertThat(early).isNotEmpty().allMatch(answer -> answer.verdict() == Verdict.CORRECT);
        assertThat(early.get(0).sessionHistoryCalls()).as("the first early source is elided").isGreaterThanOrEqualTo(1);
        if (kind == TaskKind.KEY_VALUE) {
            assertThat(early).as("a key past the first part of its page is read on by offset")
                    .anyMatch(answer -> answer.sessionHistoryCalls() >= 2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskKind.class, names = {"KEY_VALUE", "LOG_TRIAGE"})
    @DisplayName("pages and dumps on the default engine: what the summary dropped cannot be answered")
    void largeUnitsAreLostToALossySummary(TaskKind kind) {
        final ContextPressureTask task = task(kind, 2);
        final ContextPressureResult result = run(task, ContextEngineKind.DEFAULT, new ScriptedModel(task)).result;

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.compactionsInPressurePhase().get(CompactionKind.FULL)).isGreaterThanOrEqualTo(1);
        assertThat(result.sessionHistoryCalls()).isZero();
        assertThat(of(result, Stratum.EARLY)).isNotEmpty().allMatch(answer -> answer.verdict() == Verdict.ABSTAINED);
        assertThat(result.accuracy()).isLessThan(1.0);
    }

    @Test
    @DisplayName("a model that asks for a delivered report again gets no body, and the attempt is counted")
    void aReportIsHandedOutOnce() {
        final ContextPressureTask task = task(TaskKind.LOG_TRIAGE, 2);

        final Cell onDefault = run(task, ContextEngineKind.DEFAULT, new ScriptedModel(task).refetching());
        assertThat(of(onDefault.result, Stratum.EARLY)).isNotEmpty()
                .allMatch(answer -> answer.verdict() == Verdict.ABSTAINED)
                .allMatch(answer -> answer.refetchAttempts() >= 1);
        assertThat(onDefault.result.refetchAttempts())
                .isEqualTo(onDefault.rig.reportDesk().orElseThrow().refusedCount())
                .isEqualTo(onDefault.result.answers().stream().mapToInt(Answer::refetchAttempts).sum());

        final Cell onRolling = run(task, ContextEngineKind.ROLLING, new ScriptedModel(task).refetching());
        assertThat(of(onRolling.result, Stratum.EARLY)).isNotEmpty()
                .allMatch(answer -> answer.verdict() == Verdict.CORRECT)
                .allMatch(answer -> answer.refetchAttempts() >= 1 && answer.sessionHistoryCalls() >= 1);
    }

    @Test
    @DisplayName("the scenario wirings are untouched: no desk, and a report is served every time")
    void theScenarioRigsHaveNoDesk() {
        final ContextPressureTask task = task(TaskKind.NEEDLE, CONTROL);
        assertThat(ContextEngineLiveRig
                .rolling(new ScriptedModel(task), LlmModel.builder().build(), tempDir.resolve("scenario")).reportDesk())
                .isEmpty();
    }

    @Test
    @DisplayName("both engines are given the same user inputs")
    void bothEnginesGetTheSameInputs() {
        for (TaskKind kind : TaskKind.values()) {
            final ContextPressureTask task = task(kind, 2);
            final ContextPressureResult onDefault = run(task, ContextEngineKind.DEFAULT,
                    new ScriptedModel(task)).result;
            final ContextPressureResult onRolling = run(task, ContextEngineKind.ROLLING,
                    new ScriptedModel(task)).result;

            assertThat(onRolling.userInputs()).isEqualTo(onDefault.userInputs())
                    .hasSize(task.steps().size() + task.questions().size());
        }
    }

    @Test
    @DisplayName("a question whose turn runs out of iterations is FAILED, and the next question is asked and scored")
    void aQuestionThatRunsOutOfIterationsDoesNotStopTheRun() {
        final ContextPressureTask task = task(TaskKind.KEY_VALUE, 2);
        final String stuck = task.questions().get(3).id();
        final ContextPressureResult result = run(task, ContextEngineKind.ROLLING,
                new ScriptedModel(task).searchingForeverOn(stuck)).result;

        assertThat(result.failedTurns()).isEqualTo(1);
        assertThat(result.answers()).hasSize(task.questions().size());
        assertThat(result.answers().get(3).verdict()).isEqualTo(Verdict.FAILED);
        assertThat(result.answers().subList(4, task.questions().size()))
                .allMatch(answer -> answer.verdict() == Verdict.CORRECT);
        assertThat(result.status()).isEqualTo(Status.OK);
    }

    @ParameterizedTest
    @EnumSource(ContextEngineKind.class)
    @DisplayName("a question whose first call the provider refuses is FAILED, and the next question is asked and"
            + " scored")
    void aQuestionTheProviderRefusesDoesNotStopTheRun(ContextEngineKind engine) {
        // The refused turn leaves the log ending on a user message, and the next question appends another.
        final ContextPressureTask task = task(TaskKind.KEY_VALUE, CONTROL);
        final Question refused = task.questions().get(2);
        final ContextPressureResult result = run(task, engine,
                new ScriptedModel(task).failingOn(refused.prompt())).result;

        assertThat(result.failedTurns()).isEqualTo(1);
        assertThat(result.answers().get(2).verdict()).isEqualTo(Verdict.FAILED);
        assertThat(result.count(Verdict.CORRECT)).isEqualTo(task.questions().size() - 1);
        assertThat(result.status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("a model that skips its fetches leaves the cell under pressure, and the cell says so")
    void skippedFetchesInvalidateThePressure() {
        final ContextPressureTask task = task(TaskKind.LOG_TRIAGE, 2);
        final ContextPressureResult result = run(task, ContextEngineKind.ROLLING,
                new ScriptedModel(task).skippingFetches()).result;

        assertThat(result.skippedFetches()).isEqualTo(task.reports().size());
        assertThat(result.status()).isEqualTo(Status.INVALID_PRESSURE);
        assertThat(result.pressureAchieved()).isLessThan(ContextPressureRun.MIN_PRESSURE_SHARE * 2);
    }

    @Test
    @DisplayName("a control cell that compacts is recorded as an invalid control, not failed")
    void aControlThatCompactsIsRecorded() {
        final ContextPressureTask task = task(TaskKind.KEY_VALUE, CONTROL);
        final ContextPressureResult result = run(task, ContextEngineKind.ROLLING,
                new ScriptedModel(task).chatty()).result;

        assertThat(total(result.compactions())).isPositive();
        assertThat(result.status()).isEqualTo(Status.INVALID_CONTROL);
    }

    @Test
    @DisplayName("a turn that fails in the pressure phase closes the cell as an error; the run does not throw")
    void aFailureInThePressurePhaseClosesTheCell() {
        final ContextPressureTask task = task(TaskKind.NEEDLE, CONTROL);
        final String third = task.steps().get(3).input();
        final ContextPressureResult result = run(task, ContextEngineKind.DEFAULT,
                new ScriptedModel(task).failingOn(third)).result;

        assertThat(result.status()).isEqualTo(Status.ERROR);
        assertThat(result.errorMessage()).isPresent();
        assertThat(result.answers()).isEmpty();
        assertThat(result.failedTurns()).isEqualTo(1);
        assertThat(result.userInputs()).hasSize(4);
    }

    @Test
    @DisplayName("the report has one row per cell and one per question, and is written where it is asked to be")
    void theReportHasARowPerCellAndPerQuestion() throws Exception {
        final ContextPressureTask task = task(TaskKind.LOG_TRIAGE, CONTROL);
        final List<ContextPressureResult> results = new ArrayList<>();
        for (ContextEngineKind engine : ContextEngineKind.values()) {
            results.add(run(task, engine, new ScriptedModel(task)).result);
        }

        final String report = ContextPressureRun.render("Scripted", "2026-01-01", results);
        final Path file = tempDir.resolve("reports").resolve("nested").resolve("scripted.md");
        ContextPressureRun.write(file, report);

        assertThat(Files.readString(file)).isEqualTo(report);
        assertThat(rows(report, "| LOG_TRIAGE | default | 0.3 | ")).isEqualTo(1 + task.questions().size());
        assertThat(rows(report, "| LOG_TRIAGE | rolling | 0.3 | ")).isEqualTo(1 + task.questions().size());
        assertThat(report).contains("| OK | " + task.questions().size() + " | 0 | 0 | 0 | 1.00 |")
                .contains("| Q1 | LATE | CORRECT | 1 | 0 | 0 |").contains("effective " + EFFECTIVE);
    }

    @Test
    @DisplayName("the opt-in's value is a list of levels in range, or the run stops before its first call")
    void theOptInValueIsAListOfLevels() {
        assertThat(ContextPressureRun.parseLevels("0.3,2")).containsExactly(0.3, 2.0);
        assertThat(ContextPressureRun.parseLevels(" 4 ")).containsExactly(4.0);
        for (String value : new String[]{"", "true", "0.3,,2", "0", "-2", "0.1", "9", "20", "NaN", "2;4"}) {
            assertThatThrownBy(() -> ContextPressureRun.parseLevels(value)).as("'%s'", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ContextPressureRun.OPT_IN_VARIABLE);
        }
        assertThat(ContextPressureRun.reportFile("OpenAI", "gpt-4o-mini"))
                .isEqualTo(Path.of("build", "reports", "context-engine-pressure", "openai-gpt-4o-mini.md"));
    }

    @Test
    @DisplayName("the recorder forwards each overload to the same overload, and counts each call once")
    void theRecorderDoesNotChangeTheShapeOfACall() {
        final List<String> reached = new ArrayList<>();
        final MainCallRecorder recorder = new MainCallRecorder(new OverloadProbe(reached), ESTIMATOR);
        final List<Message> messages = List.of(Message.user("hello"));
        final LlmModel model = LlmModel.builder().build();
        final LlmStreamSink sink = chunk -> {
        };

        recorder.sendMessage("system", messages, List.of());
        recorder.sendMessage("system", messages, List.of(), model);
        recorder.sendMessage("system", messages, List.of(), model, LlmCallMetadata.empty());
        recorder.sendMessage(SystemPromptParts.empty(), messages, List.of(), model, LlmCallMetadata.empty());
        recorder.sendMessage(SystemPromptParts.empty(), messages, List.of(), model, LlmCallMetadata.empty(),
                LlmCancellation.none());
        recorder.sendMessageStreaming(SystemPromptParts.empty(), messages, List.of(), model, LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), sink);
        recorder.sendMessageStreaming(SystemPromptParts.empty(), messages, List.of(), model, LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), sink, LlmCancellation.none());

        // The three the executor's gateway uses are string-4, parts-cancellable and streaming-cancellable.
        assertThat(reached).containsExactly("string-3", "string-4", "string-metadata", "parts", "parts-cancellable",
                "streaming", "streaming-cancellable");
        assertThat(recorder.count()).isEqualTo(7);
        assertThat(recorder.sentViewTokens()).hasSize(7).allMatch(tokens -> tokens > 0);
        assertThat(recorder.toolUsesNamed("probe")).isEqualTo(7);
        assertThat(recorder.reportedPromptTokens()).isZero();
    }

    /**
     * Not an assertion: the calls a scripted run makes at each level, written where the person who is about to pay for
     * a live run can read them. A scripted model answers in one word and never looks up more than it must, so these
     * are a floor. The file exists only after this module's {@code test} task really ran.
     */
    @Test
    @DisplayName("writes the keyless call counts for every task, engine and level")
    void writesTheKeylessCallCounts() {
        final StringBuilder out = new StringBuilder("# Context engine pressure: calls of a scripted run\n\n")
                .append("A floor for a live run: the scripted model answers in one word, fetches once, and looks up")
                .append(" only what it cannot see. Token counts are the engines' estimates.\n\n")
                .append("| task | engine | level | turns | main calls | summary calls | sent view sum |")
                .append(" sent view max |\n|---|---|---|---|---|---|---|---|\n");
        final Map<String, long[]> perLevel = new LinkedHashMap<>();
        for (double level : new double[]{CONTROL, 2, 4}) {
            for (TaskKind kind : TaskKind.values()) {
                final ContextPressureTask task = task(kind, level);
                for (ContextEngineKind engine : ContextEngineKind.values()) {
                    final ContextPressureResult result = run(task, engine, new ScriptedModel(task)).result;
                    assertThat(result.status()).as("%s on %s at %s", kind, engine, level).isEqualTo(Status.OK);
                    out.append("| ").append(kind).append(" | ").append(engine.configValue()).append(" | ")
                            .append(ContextPressureRun.level(level)).append(" | ").append(result.userInputs().size())
                            .append(" | ").append(result.mainCalls()).append(" | ").append(result.summaryCalls())
                            .append(" | ").append(result.sentViewTokensSum()).append(" | ")
                            .append(result.sentViewTokensMax()).append(" |\n");
                    final long[] sums = perLevel.computeIfAbsent(ContextPressureRun.level(level), key -> new long[3]);
                    sums[0] += result.mainCalls();
                    sums[1] += result.summaryCalls();
                    sums[2] += result.sentViewTokensSum();
                }
            }
        }
        out.append("\n| level | main calls | summary calls | sent view sum |\n|---|---|---|---|\n");
        perLevel.forEach((level, sums) -> out.append("| ").append(level).append(" | ").append(sums[0]).append(" | ")
                .append(sums[1]).append(" | ").append(sums[2]).append(" |\n"));

        // Gradle runs tests in the module directory; from an IDE this lands under whatever its working directory is.
        ContextPressureRun.write(Path.of("build", "reports", "context-engine-pressure", "keyless-call-counts.md"),
                out.toString());
    }

    /** What holds for every run, whatever the model did. */
    private static void assertConsistent(ContextPressureTask task, Cell cell) {
        final ContextPressureResult result = cell.result;
        assertThat(result.mainCalls()).isEqualTo(cell.rig.mainCalls().count());
        assertThat(result.sentViewTokensMax()).as("nothing was sent over the blocking limit")
                .isLessThanOrEqualTo(cell.rig.blockingLimit());
        assertThat(result.task()).isEqualTo(task.kind());
        assertThat(result.provider()).isEqualTo("Scripted");
        for (CompactionKind kind : CompactionKind.values()) {
            assertThat(result.compactionsInPressurePhase().get(kind)).as("%s in the pressure phase", kind)
                    .isLessThanOrEqualTo(result.compactions().get(kind));
        }
        assertThat(total(result.compactionsByTrigger())).isEqualTo(total(result.compactions()));
        if (result.status() == Status.OK || result.status() == Status.INVALID_CONTROL) {
            assertThat(result.pressureAchieved()).isGreaterThanOrEqualTo(result.pressureRequested());
            assertThat(result.skippedFetches()).isZero();
            assertThat(result.answers()).hasSize(task.questions().size());
        }
    }

    private static List<Answer> of(ContextPressureResult result, Stratum stratum) {
        return result.answers().stream().filter(answer -> answer.stratum() == stratum).collect(Collectors.toList());
    }

    private static int total(Map<?, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long rows(String report, String prefix) {
        return report.lines().filter(line -> line.startsWith(prefix)).count();
    }

    /** A message's text and its tool results' text: what the model reads of it. */
    private static String textOf(Message message) {
        final StringBuilder text = new StringBuilder(String.valueOf(message.getContent()));
        message.getToolUseResults().forEach(result -> text.append('\n').append(result.getContent()));
        return text.toString();
    }

    /** One run and the rig it ran on. */
    private static final class Cell {

        private final ContextEngineLiveRig rig;
        private final ContextPressureResult result;

        Cell(ContextEngineLiveRig rig, ContextPressureResult result) {
            this.rig = rig;
            this.result = result;
        }
    }

    private enum Summary {
        /** One sentence with no fact in it. */
        LOSSY,
        /** Carries every fact line it is shown, an earlier summary's included. */
        FAITHFUL
    }

    private enum Trait {
        REFETCHES, SKIPS_FETCHES, CHATTY
    }

    /**
     * Plays the model. It fetches when told to and answers {@code received}; asked a question, it answers from the view
     * when the value is there, and otherwise, when it has {@code SessionHistory}, searches for the question's key and
     * reads a cut match on by its offset until the value shows. With nothing left to try it answers {@code UNKNOWN}. A
     * call without tools is a summary call.
     *
     * <p>
     * It keeps no memory between calls: everything is read off the view it is sent, as a model's would be.
     */
    private static final class ScriptedModel implements LlmClient {

        private static final Pattern FETCH = Pattern.compile("report_id \"([^\"]+)\"");
        private static final Pattern MARKED_TOOL = Pattern.compile(">> \\[seq (\\d+)\\] tool: ");
        private static final Pattern READ_ON = Pattern.compile("read on with seq=(\\d+), offset=(\\d+)\\]");
        private static final String CHATTER = " Let me explain at length why that is my answer.".repeat(45);

        private final ContextPressureTask task;
        private final Map<String, Question> byPrompt = new LinkedHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();
        private final Set<Trait> traits = EnumSet.noneOf(Trait.class);
        private Summary summary = Summary.LOSSY;
        private String searchesForeverOn;
        private String failsOn;

        ScriptedModel(ContextPressureTask task) {
            this.task = task;
            task.questions().forEach(question -> byPrompt.put(question.prompt(), question));
        }

        ScriptedModel summarizing(Summary style) {
            this.summary = style;
            return this;
        }

        /** Asks for the source report again before anything else, when the value is not in the view. */
        ScriptedModel refetching() {
            traits.add(Trait.REFETCHES);
            return this;
        }

        /** Answers a fetch instruction without fetching. */
        ScriptedModel skippingFetches() {
            traits.add(Trait.SKIPS_FETCHES);
            return this;
        }

        /** Pads every answer with a few hundred tokens. */
        ScriptedModel chatty() {
            traits.add(Trait.CHATTY);
            return this;
        }

        /** Never stops searching on the question with this id. */
        ScriptedModel searchingForeverOn(String questionId) {
            this.searchesForeverOn = questionId;
            return this;
        }

        /** Throws, as a provider that refuses the request would, on the first call of the turn with this input. */
        ScriptedModel failingOn(String input) {
            this.failsOn = input;
            return this;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            if (tools.isEmpty()) {
                return LlmResponse.text(summaryOf(systemPrompt, messages));
            }
            int at = messages.size() - 1;
            while (at > 0 && messages.get(at).getRole() != Role.USER) {
                at--;
            }
            final String input = String.valueOf(messages.get(at).getContent());
            final List<Message> since = messages.subList(at + 1, messages.size());
            if (input.equals(failsOn) && since.isEmpty()) {
                throw new IllegalStateException("scripted provider failure");
            }
            final Question question = byPrompt.get(input);
            if (question != null) {
                return answer(question, messages, since, tools);
            }
            final Matcher fetch = FETCH.matcher(input);
            if (fetch.find() && since.isEmpty() && !traits.contains(Trait.SKIPS_FETCHES)) {
                return LlmResponse.tools(List.of(ToolUse.of("report-" + ids.incrementAndGet(),
                        ContextPressureTasks.REPORT_TOOL, Map.of("report_id", fetch.group(1)))));
            }
            return say(input.startsWith("Note ")
                    ? "noted"
                    : input.equals(ContextPressureTasks.BRIEFING) ? "ready" : "received");
        }

        private LlmResponse answer(Question question, List<Message> view, List<Message> since,
                List<ToolDefinition> tools) {
            final boolean canLookUp = tools.stream()
                    .anyMatch(tool -> SessionHistoryTool.TOOL_NAME.equals(tool.getName()));
            if (question.id().equals(searchesForeverOn) && canLookUp) {
                // A query nothing matches, itself included: the results stay small, so the turn ends on the iteration
                // limit and not on a view the lookups filled.
                return lookUp(Map.of("query", "no such text " + ids.incrementAndGet()));
            }
            if (view.stream().anyMatch(message -> textOf(message).contains(question.expected()))) {
                return say(question.expected());
            }
            if (traits.contains(Trait.REFETCHES) && question.sourceReportId().isPresent()
                    && since.stream().flatMap(message -> message.getToolUses().stream())
                            .noneMatch(use -> use.getId().startsWith("re"))) {
                return LlmResponse.tools(List.of(ToolUse.of("refetch-" + ids.incrementAndGet(),
                        ContextPressureTasks.REPORT_TOOL, Map.of("report_id", question.sourceReportId().get()))));
            }
            if (!canLookUp) {
                return say("UNKNOWN");
            }
            String lastLookup = null;
            for (Message message : since) {
                for (ToolUseResult result : message.getToolUseResults()) {
                    if (result.getToolUseId().startsWith("history-")) {
                        lastLookup = String.valueOf(result.getContent());
                    }
                }
            }
            if (lastLookup == null) {
                return lookUp(Map.of("query", question.lookupKey()));
            }
            // The oldest tool message the lookup marked and cut is the original; a later one is an earlier lookup's
            // result quoting it.
            final Set<Long> marked = new TreeSet<>();
            final Matcher mark = MARKED_TOOL.matcher(lastLookup);
            while (mark.find()) {
                marked.add(Long.parseLong(mark.group(1)));
            }
            final TreeMap<Long, Integer> readOn = new TreeMap<>();
            final Matcher more = READ_ON.matcher(lastLookup);
            while (more.find()) {
                if (marked.contains(Long.parseLong(more.group(1)))) {
                    readOn.putIfAbsent(Long.parseLong(more.group(1)), Integer.parseInt(more.group(2)));
                }
            }
            if (readOn.isEmpty()) {
                return say("UNKNOWN");
            }
            return lookUp(Map.of("seq", readOn.firstKey(), "offset", readOn.firstEntry().getValue()));
        }

        private LlmResponse lookUp(Map<String, Object> input) {
            return LlmResponse.tools(
                    List.of(ToolUse.of("history-" + ids.incrementAndGet(), SessionHistoryTool.TOOL_NAME, input)));
        }

        private LlmResponse say(String text) {
            return LlmResponse.text(traits.contains(Trait.CHATTY) ? text + CHATTER : text);
        }

        private String summaryOf(String systemPrompt, List<Message> messages) {
            if (summary == Summary.LOSSY) {
                return "Summary: the user gave some notes and fetched several reports.";
            }
            // The rolling engine hands an earlier summary over in the system prompt; the default engine leaves it in
            // the view as a message. A fact that is not carried from either is lost at the second summary.
            final String shown = systemPrompt + "\n"
                    + messages.stream().map(ContextPressureRunTest::textOf).collect(Collectors.joining("\n"));
            return "Summary: the user gave some notes and fetched several reports. Facts kept:\n" + task.questions()
                    .stream().map(Question::sourceLine).filter(shown::contains).collect(Collectors.joining("\n"));
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }

    /** Says which overload it was reached through, and answers with one tool use and no usage. */
    private static final class OverloadProbe implements LlmClient {

        private final List<String> reached;

        OverloadProbe(List<String> reached) {
            this.reached = reached;
        }

        private LlmResponse reached(String overload) {
            reached.add(overload);
            return LlmResponse.tools(List.of(ToolUse.of("probe-" + reached.size(), "probe", Map.of())));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
            return reached("string-3");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return reached("string-4");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return reached("string-metadata");
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return reached("parts");
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            return reached("parts-cancellable");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            return reached("streaming");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            return reached("streaming-cancellable");
        }

        @Override
        public String getProviderName() {
            return "Probe";
        }
    }
}
