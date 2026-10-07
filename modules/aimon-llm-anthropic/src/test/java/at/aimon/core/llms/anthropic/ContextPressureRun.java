package at.aimon.core.llms.anthropic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.llms.anthropic.ContextEngineLiveRig.MainCallRecorder;
import at.aimon.core.llms.anthropic.ContextEngineLiveRig.ReportDesk;
import at.aimon.core.llms.anthropic.ContextPressureTasks.ContextPressureTask;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Question;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Step;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Stratum;
import at.aimon.core.llms.anthropic.ContextPressureTasks.TaskKind;
import at.aimon.core.llms.anthropic.ContextPressureTasks.Verdict;
import at.aimon.core.tools.session.SessionHistoryTool;

/**
 * Runs one {@link ContextPressureTask} on one comparison rig and reports what came of it: the pressure phase, one
 * fetch per turn, then the question phase, one question per turn, then the scoring (context-engine §13.11).
 *
 * <p>
 * A run never throws for something the model or the provider did. A turn that fails in the pressure phase closes the
 * cell as {@link Status#ERROR}; a question whose turn fails is {@link Verdict#FAILED} and the next one is still
 * asked; a control cell that compacted, or a cell the model did not push enough into, is marked and kept. Whoever paid
 * for the cell gets a row either way.
 *
 * <p>
 * Every number comes from what the rig can already see — the execution results, the two call recorders and the report
 * desk. Nothing was added to production code to observe it.
 *
 * <p>
 * Kept in step with the copy in {@code aimon-llm-openai}.
 */
final class ContextPressureRun {

    /** Below this share of the pressure asked for, a cell did not test what its level says. */
    static final double MIN_PRESSURE_SHARE = 0.9;

    /**
     * A cell is a control when its task plans no more than this share of the rolling threshold: far enough under that
     * neither engine should compact. A level between the control and 1 plans more, and rolling compacting there is
     * the engine doing what it is configured to do, not an invalid control.
     */
    static final double CONTROL_SHARE = 0.6;

    private ContextPressureRun() {
    }

    enum Status {
        OK,
        /** A control cell in which some compaction happened: the control was not a control. */
        INVALID_CONTROL,
        /** The model skipped fetches, and the pressure reached is under {@link #MIN_PRESSURE_SHARE} of the level. */
        INVALID_PRESSURE,
        /** A turn of the pressure phase failed; the questions were not asked. */
        ERROR
    }

    /** The variable a live run is opted into with; its value is the list of levels to run, e.g. {@code 0.3,2}. */
    static final String OPT_IN_VARIABLE = "AIMON_CONTEXT_PRESSURE";

    /**
     * Reads the levels out of {@link #OPT_IN_VARIABLE}'s value before anything is called: a comma-separated list of
     * numbers within the range the generators offer.
     *
     * @throws IllegalArgumentException
     *             when the value is not such a list
     */
    static List<Double> parseLevels(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final List<Double> levels = new ArrayList<>();
        for (String part : value.split(",")) {
            final double level;
            try {
                level = Double.parseDouble(part.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(OPT_IN_VARIABLE + " must be a comma-separated list of pressure"
                        + " levels such as 0.3,2 - not a number: '" + part.trim() + "'", e);
            }
            if (!(level >= ContextPressureTasks.MIN_PRESSURE && level <= ContextPressureTasks.MAX_PRESSURE)) {
                throw new IllegalArgumentException(
                        OPT_IN_VARIABLE + " levels must be between " + ContextPressureTasks.MIN_PRESSURE + " and "
                                + ContextPressureTasks.MAX_PRESSURE + ", got: " + part.trim());
            }
            levels.add(level);
        }
        return List.copyOf(levels);
    }

    /** Where a live run's report goes, relative to the module directory Gradle runs the tests in. */
    static Path reportFile(String provider, String model) {
        final String name = (provider + "-" + model).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]+", "-");
        return Path.of("build", "reports", "context-engine-pressure", name + ".md");
    }

    /** The most a task may plan and still be a control cell, in estimated tokens. */
    static int controlCeiling() {
        return (int) (CONTROL_SHARE * ContextEngineLiveRig.comparisonRollingThreshold());
    }

    /**
     * Runs {@code task} on {@code rig}, which must be a fresh comparison rig over {@code kind}'s engine and the
     * task's reports.
     */
    static ContextPressureResult run(ContextPressureTask task, ContextEngineKind kind, ContextEngineLiveRig rig) {
        Objects.requireNonNull(task, "task cannot be null");
        Objects.requireNonNull(kind, "kind cannot be null");
        Objects.requireNonNull(rig, "rig cannot be null");
        final ReportDesk desk = rig.reportDesk()
                .orElseThrow(() -> new IllegalArgumentException("not a comparison rig: it has no report desk"));
        final MainCallRecorder calls = rig.mainCalls();
        final TokenEstimator estimator = new HeuristicTokenEstimator();
        final ContextPressureResult.Builder result = ContextPressureResult.builder().provider(calls.getProviderName())
                .model(calls.getDefaultModelName().orElse("unknown")).task(task.kind()).engine(kind).seed(task.seed())
                .pressureRequested(task.pressure()).unitTokens(task.unitTokens());

        final List<String> inputs = new ArrayList<>();
        int achievedTokens = 0;
        int skippedFetches = 0;
        int failedTurns = 0;
        String error = null;
        for (Step step : task.steps()) {
            inputs.add(step.input());
            final String failure = failureOf(rig, step.input());
            if (failure != null) {
                failedTurns++;
                error = failure;
                break;
            }
            achievedTokens += ContextPressureTasks.userInputTokens(estimator, step.input());
            if (step.fetchedReportId().isPresent()) {
                final String id = step.fetchedReportId().get();
                if (desk.served(id)) {
                    achievedTokens += ContextPressureTasks.toolResultTokens(estimator, task.reports().get(id));
                } else {
                    // Not asked again: the two engines would then be given different inputs.
                    skippedFetches++;
                }
            }
        }
        final List<CompactionMetadata> inPressurePhase = rig.compactions();

        final List<Answer> answers = new ArrayList<>();
        if (error == null) {
            for (Question question : task.questions()) {
                inputs.add(question.prompt());
                final int historyBefore = calls.toolUsesNamed(SessionHistoryTool.TOOL_NAME);
                final int refusedBefore = desk.refusedCount();
                OrcaAgentExecutionResult turn = null;
                try {
                    turn = rig.tryTurn(question.prompt());
                } catch (RuntimeException e) {
                    // Scored as FAILED below; the executor reports nearly everything as a failed result instead.
                }
                final boolean succeeded = turn != null && turn.isSuccess();
                if (!succeeded) {
                    failedTurns++;
                }
                answers.add(Answer.builder().questionId(question.id()).stratum(question.stratum())
                        .verdict(ContextPressureTasks.verdict(question, succeeded ? turn.getFinalAnswer() : null,
                                succeeded))
                        .iterations(turn == null ? 0 : turn.getIterationCount())
                        .sessionHistoryCalls(calls.toolUsesNamed(SessionHistoryTool.TOOL_NAME) - historyBefore)
                        .refetchAttempts(desk.refusedCount() - refusedBefore).build());
            }
        }

        final List<CompactionMetadata> compactions = rig.compactions();
        final double achieved = (double) achievedTokens / rig.effectiveWindow();
        final Status status;
        if (error != null) {
            status = Status.ERROR;
        } else if (achieved < MIN_PRESSURE_SHARE * task.pressure()) {
            status = Status.INVALID_PRESSURE;
        } else if (task.plannedTokens() <= controlCeiling() && !compactions.isEmpty()) {
            status = Status.INVALID_CONTROL;
        } else {
            status = Status.OK;
        }
        return result.status(status).errorMessage(error).pressureAchieved(achieved).answers(answers)
                .compactions(byKind(compactions)).compactionsInPressurePhase(byKind(inPressurePhase))
                .compactionsByTrigger(byTrigger(compactions))
                .overBlockingLimit((int) compactions.stream().filter(CompactionMetadata::isOverBlockingLimit).count())
                .summaryCalls(rig.summaries().count()).summaryFailures(rig.summaries().failures().size())
                .sentViewTokens(calls.sentViewTokens()).reportedPromptTokens(calls.reportedPromptTokens())
                .sessionHistoryCalls(calls.toolUsesNamed(SessionHistoryTool.TOOL_NAME))
                .refetchAttempts(desk.refusedCount()).skippedFetches(skippedFetches).failedTurns(failedTurns)
                .userInputs(inputs).build();
    }

    /** Runs one turn of the pressure phase; null when it succeeded, otherwise why it did not. */
    private static String failureOf(ContextEngineLiveRig rig, String input) {
        try {
            final OrcaAgentExecutionResult turn = rig.tryTurn(input);
            return turn.isSuccess() ? null : String.valueOf(turn.getErrorMessage());
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    private static Map<CompactionKind, Integer> byKind(List<CompactionMetadata> compactions) {
        final Map<CompactionKind, Integer> counts = new EnumMap<>(CompactionKind.class);
        for (CompactionKind kind : CompactionKind.values()) {
            counts.put(kind, 0);
        }
        compactions.forEach(metadata -> counts.merge(metadata.getKind(), 1, Integer::sum));
        return counts;
    }

    private static Map<CompactionTrigger, Integer> byTrigger(List<CompactionMetadata> compactions) {
        final Map<CompactionTrigger, Integer> counts = new EnumMap<>(CompactionTrigger.class);
        for (CompactionTrigger trigger : CompactionTrigger.values()) {
            counts.put(trigger, 0);
        }
        compactions.forEach(metadata -> counts.merge(metadata.getTrigger(), 1, Integer::sum));
        return counts;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The report as Markdown: a header with the profile's numbers, a table with one row per cell and a table with one
     * row per question. How to read it is in {@code CONTRIBUTING.md} › Live-API tests and context-engine §13.11.
     *
     * @param title
     *            what ran, e.g. the provider and model
     * @param date
     *            the day of the run, as the caller wants it printed
     * @param results
     *            the cells run so far, in order
     */
    static String render(String title, String date, List<ContextPressureResult> results) {
        Objects.requireNonNull(results, "results cannot be null");
        final StringBuilder out = new StringBuilder();
        out.append("# Context engine pressure: ").append(title).append("\n\n");
        out.append("- Date: ").append(date).append('\n');
        out.append("- Profile: context window ").append(ContextEngineLiveRig.COMPARISON_LIMITS.getContextWindow())
                .append(", effective ").append(ContextEngineLiveRig.COMPARISON_LIMITS.getEffectiveContextWindow())
                .append("; default engine compacts at ")
                .append(ContextEngineLiveRig.COMPARISON_LIMITS.getAutoCompactThreshold()).append(", blocks at ")
                .append(ContextEngineLiveRig.COMPARISON_LIMITS.getBlockingLimit()).append("; rolling compacts at ")
                .append(ContextEngineLiveRig.comparisonRollingThreshold()).append(" and prunes tool results of ")
                .append(RollingContextEngine.DEFAULT_PRUNE_MIN_TOKENS).append(" tokens or more\n");
        out.append("- Seeds: ").append(distinct(results, result -> String.valueOf(result.seed()))).append('\n');
        out.append("- Levels: ").append(distinct(results, result -> level(result.pressureRequested()))).append('\n');
        out.append("- Unit tokens: ").append(distinct(results, result -> result.task() + " " + result.unitTokens()))
                .append('\n');
        out.append("- Token counts are estimates (the engines' own), except `reported input`, which is the input")
                .append(" tokens the provider counted. One run is one sample.\n\n");

        out.append("## Cells\n\n");
        out.append("| task | engine | level | reached | status | correct | wrong | abstained | failed | accuracy |")
                .append(" PRUNE | ROLLING | FULL | FALLBACK | pressure-phase PRUNE/ROLLING/FULL | over blocking |")
                .append(" summary calls | summary failures | main calls | sent view sum | sent view max |")
                .append(" sent view mean | reported input | SessionHistory calls | refetch attempts |")
                .append(" skipped fetches | failed turns |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
                .append("---|---|---|---|\n");
        for (ContextPressureResult result : results) {
            final Map<CompactionKind, Integer> kinds = result.compactions();
            final Map<CompactionKind, Integer> pressure = result.compactionsInPressurePhase();
            out.append("| ").append(result.task()).append(" | ").append(result.engine().configValue()).append(" | ")
                    .append(level(result.pressureRequested())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", result.pressureAchieved())).append(" | ")
                    .append(result.status())
                    .append(result.errorMessage().map(message -> ": " + cell(message)).orElse("")).append(" | ")
                    .append(result.count(Verdict.CORRECT)).append(" | ").append(result.count(Verdict.WRONG))
                    .append(" | ").append(result.count(Verdict.ABSTAINED)).append(" | ")
                    .append(result.count(Verdict.FAILED)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", result.accuracy())).append(" | ")
                    .append(kinds.get(CompactionKind.PRUNE)).append(" | ").append(kinds.get(CompactionKind.ROLLING))
                    .append(" | ").append(kinds.get(CompactionKind.FULL)).append(" | ")
                    .append(kinds.get(CompactionKind.FALLBACK)).append(" | ").append(pressure.get(CompactionKind.PRUNE))
                    .append('/').append(pressure.get(CompactionKind.ROLLING)).append('/')
                    .append(pressure.get(CompactionKind.FULL)).append(" | ").append(result.overBlockingLimit())
                    .append(" | ").append(result.summaryCalls()).append(" | ").append(result.summaryFailures())
                    .append(" | ").append(result.mainCalls()).append(" | ").append(result.sentViewTokensSum())
                    .append(" | ").append(result.sentViewTokensMax()).append(" | ").append(result.sentViewTokensMean())
                    .append(" | ").append(result.reportedPromptTokens()).append(" | ")
                    .append(result.sessionHistoryCalls()).append(" | ").append(result.refetchAttempts()).append(" | ")
                    .append(result.skippedFetches()).append(" | ").append(result.failedTurns()).append(" |\n");
        }

        out.append("\n## Questions\n\n");
        out.append("| task | engine | level | question | stratum | verdict | iterations | SessionHistory calls |")
                .append(" refetch attempts |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ContextPressureResult result : results) {
            for (Answer answer : result.answers()) {
                out.append("| ").append(result.task()).append(" | ").append(result.engine().configValue()).append(" | ")
                        .append(level(result.pressureRequested())).append(" | ").append(answer.questionId())
                        .append(" | ").append(answer.stratum()).append(" | ").append(answer.verdict()).append(" | ")
                        .append(answer.iterations()).append(" | ").append(answer.sessionHistoryCalls()).append(" | ")
                        .append(answer.refetchAttempts()).append(" |\n");
            }
        }
        return out.toString();
    }

    /** Writes {@code content} to {@code file}, creating its directory. A report that cannot be written is a failure. */
    static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the pressure report to " + file.toAbsolutePath(), e);
        }
    }

    /** A level as it is written in {@code AIMON_CONTEXT_PRESSURE}. */
    static String level(double pressure) {
        return pressure == Math.rint(pressure) ? String.valueOf((long) pressure) : String.valueOf(pressure);
    }

    private static String distinct(List<ContextPressureResult> results, Function<ContextPressureResult, String> field) {
        return results.stream().map(field).distinct().collect(Collectors.joining(", "));
    }

    private static String cell(String text) {
        final String oneLine = text.replace('\n', ' ').replace('|', '/');
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Result types
    // ------------------------------------------------------------------------------------------------------------

    /** One answer of a run, scored. Immutable. */
    static final class Answer {

        private final String questionId;
        private final Stratum stratum;
        private final Verdict verdict;
        private final int iterations;
        private final int sessionHistoryCalls;
        private final int refetchAttempts;

        private Answer(Builder builder) {
            this.questionId = Objects.requireNonNull(builder.questionId, "questionId cannot be null");
            this.stratum = Objects.requireNonNull(builder.stratum, "stratum cannot be null");
            this.verdict = Objects.requireNonNull(builder.verdict, "verdict cannot be null");
            this.iterations = builder.iterations;
            this.sessionHistoryCalls = builder.sessionHistoryCalls;
            this.refetchAttempts = builder.refetchAttempts;
        }

        static Builder builder() {
            return new Builder();
        }

        String questionId() {
            return questionId;
        }

        Stratum stratum() {
            return stratum;
        }

        Verdict verdict() {
            return verdict;
        }

        /** The iterations the question's turn took; 0 when the turn threw. */
        int iterations() {
            return iterations;
        }

        /** The lookups this answer cost. Always 0 on the default engine, which has no such tool. */
        int sessionHistoryCalls() {
            return sessionHistoryCalls;
        }

        /** How many times the model asked for an already delivered report during this question, and was refused. */
        int refetchAttempts() {
            return refetchAttempts;
        }

        @Override
        public String toString() {
            return questionId + " " + stratum + " " + verdict + " (iterations=" + iterations + ", sessionHistoryCalls="
                    + sessionHistoryCalls + ", refetchAttempts=" + refetchAttempts + ")";
        }

        static final class Builder {

            private String questionId;
            private Stratum stratum;
            private Verdict verdict;
            private int iterations;
            private int sessionHistoryCalls;
            private int refetchAttempts;

            Builder questionId(String questionId) {
                this.questionId = questionId;
                return this;
            }

            Builder stratum(Stratum stratum) {
                this.stratum = stratum;
                return this;
            }

            Builder verdict(Verdict verdict) {
                this.verdict = verdict;
                return this;
            }

            Builder iterations(int iterations) {
                this.iterations = iterations;
                return this;
            }

            Builder sessionHistoryCalls(int sessionHistoryCalls) {
                this.sessionHistoryCalls = sessionHistoryCalls;
                return this;
            }

            Builder refetchAttempts(int refetchAttempts) {
                this.refetchAttempts = refetchAttempts;
                return this;
            }

            Answer build() {
                return new Answer(this);
            }
        }
    }

    /** One cell: a task on an engine at a level with a seed. Immutable. */
    static final class ContextPressureResult {

        private final String provider;
        private final String model;
        private final TaskKind task;
        private final ContextEngineKind engine;
        private final long seed;
        private final double pressureRequested;
        private final double pressureAchieved;
        private final int unitTokens;
        private final Status status;
        private final String errorMessage;
        private final List<Answer> answers;
        private final Map<CompactionKind, Integer> compactions;
        private final Map<CompactionKind, Integer> compactionsInPressurePhase;
        private final Map<CompactionTrigger, Integer> compactionsByTrigger;
        private final int overBlockingLimit;
        private final int summaryCalls;
        private final int summaryFailures;
        private final List<Integer> sentViewTokens;
        private final long reportedPromptTokens;
        private final int sessionHistoryCalls;
        private final int refetchAttempts;
        private final int skippedFetches;
        private final int failedTurns;
        private final List<String> userInputs;

        private ContextPressureResult(Builder builder) {
            this.provider = Objects.requireNonNull(builder.provider, "provider cannot be null");
            this.model = Objects.requireNonNull(builder.model, "model cannot be null");
            this.task = Objects.requireNonNull(builder.task, "task cannot be null");
            this.engine = Objects.requireNonNull(builder.engine, "engine cannot be null");
            this.seed = builder.seed;
            this.pressureRequested = builder.pressureRequested;
            this.pressureAchieved = builder.pressureAchieved;
            this.unitTokens = builder.unitTokens;
            this.status = Objects.requireNonNull(builder.status, "status cannot be null");
            this.errorMessage = builder.errorMessage;
            this.answers = List.copyOf(builder.answers);
            this.compactions = Collections.unmodifiableMap(new EnumMap<>(builder.compactions));
            this.compactionsInPressurePhase = Collections
                    .unmodifiableMap(new EnumMap<>(builder.compactionsInPressurePhase));
            this.compactionsByTrigger = Collections.unmodifiableMap(new EnumMap<>(builder.compactionsByTrigger));
            this.overBlockingLimit = builder.overBlockingLimit;
            this.summaryCalls = builder.summaryCalls;
            this.summaryFailures = builder.summaryFailures;
            this.sentViewTokens = List.copyOf(builder.sentViewTokens);
            this.reportedPromptTokens = builder.reportedPromptTokens;
            this.sessionHistoryCalls = builder.sessionHistoryCalls;
            this.refetchAttempts = builder.refetchAttempts;
            this.skippedFetches = builder.skippedFetches;
            this.failedTurns = builder.failedTurns;
            this.userInputs = List.copyOf(builder.userInputs);
        }

        static Builder builder() {
            return new Builder();
        }

        String provider() {
            return provider;
        }

        String model() {
            return model;
        }

        TaskKind task() {
            return task;
        }

        ContextEngineKind engine() {
            return engine;
        }

        long seed() {
            return seed;
        }

        double pressureRequested() {
            return pressureRequested;
        }

        /** The pressure phase's inputs that reached the log, in estimated tokens, over the effective window. */
        double pressureAchieved() {
            return pressureAchieved;
        }

        int unitTokens() {
            return unitTokens;
        }

        Status status() {
            return status;
        }

        /** Why the cell is {@link Status#ERROR}; empty otherwise. */
        Optional<String> errorMessage() {
            return Optional.ofNullable(errorMessage);
        }

        /** In the order asked; empty when the pressure phase failed. */
        List<Answer> answers() {
            return answers;
        }

        int count(Verdict verdict) {
            return (int) answers.stream().filter(answer -> answer.verdict() == verdict).count();
        }

        int count(Verdict verdict, Stratum stratum) {
            return (int) answers.stream().filter(answer -> answer.verdict() == verdict && answer.stratum() == stratum)
                    .count();
        }

        /** Correct answers over questions asked; 0 when none were. */
        double accuracy() {
            return answers.isEmpty() ? 0.0 : (double) count(Verdict.CORRECT) / answers.size();
        }

        /** Every compaction of the run, by kind; every kind has an entry. */
        Map<CompactionKind, Integer> compactions() {
            return compactions;
        }

        /**
         * The compactions before the first question, by kind. The difference from {@link #compactions()} is what the
         * lookups of the question phase brought on.
         */
        Map<CompactionKind, Integer> compactionsInPressurePhase() {
            return compactionsInPressurePhase;
        }

        Map<CompactionTrigger, Integer> compactionsByTrigger() {
            return compactionsByTrigger;
        }

        /** Compactions that left the view at or over the blocking limit. */
        int overBlockingLimit() {
            return overBlockingLimit;
        }

        int summaryCalls() {
            return summaryCalls;
        }

        int summaryFailures() {
            return summaryFailures;
        }

        /** The executor's calls. */
        int mainCalls() {
            return sentViewTokens.size();
        }

        /** The estimated size of each call's view, in call order. */
        List<Integer> sentViewTokens() {
            return sentViewTokens;
        }

        /** The cost proxy: what was sent in all, by estimate. */
        long sentViewTokensSum() {
            return sentViewTokens.stream().mapToLong(Integer::longValue).sum();
        }

        int sentViewTokensMax() {
            return sentViewTokens.stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        long sentViewTokensMean() {
            return sentViewTokens.isEmpty() ? 0 : sentViewTokensSum() / sentViewTokens.size();
        }

        /** The input tokens the provider counted over the executor's calls; 0 from a scripted client. */
        long reportedPromptTokens() {
            return reportedPromptTokens;
        }

        int sessionHistoryCalls() {
            return sessionHistoryCalls;
        }

        /** Requests for an already delivered report, over the whole run. */
        int refetchAttempts() {
            return refetchAttempts;
        }

        /** Fetches the model was told to make and did not. */
        int skippedFetches() {
            return skippedFetches;
        }

        int failedTurns() {
            return failedTurns;
        }

        /** Every user input the run gave, in order: the same list for both engines unless a cell stopped early. */
        List<String> userInputs() {
            return userInputs;
        }

        static final class Builder {

            private String provider;
            private String model;
            private TaskKind task;
            private ContextEngineKind engine;
            private long seed;
            private double pressureRequested;
            private double pressureAchieved;
            private int unitTokens;
            private Status status;
            private String errorMessage;
            private List<Answer> answers = List.of();
            private Map<CompactionKind, Integer> compactions = Map.of();
            private Map<CompactionKind, Integer> compactionsInPressurePhase = Map.of();
            private Map<CompactionTrigger, Integer> compactionsByTrigger = Map.of();
            private int overBlockingLimit;
            private int summaryCalls;
            private int summaryFailures;
            private List<Integer> sentViewTokens = List.of();
            private long reportedPromptTokens;
            private int sessionHistoryCalls;
            private int refetchAttempts;
            private int skippedFetches;
            private int failedTurns;
            private List<String> userInputs = List.of();

            Builder provider(String provider) {
                this.provider = provider;
                return this;
            }

            Builder model(String model) {
                this.model = model;
                return this;
            }

            Builder task(TaskKind task) {
                this.task = task;
                return this;
            }

            Builder engine(ContextEngineKind engine) {
                this.engine = engine;
                return this;
            }

            Builder seed(long seed) {
                this.seed = seed;
                return this;
            }

            Builder pressureRequested(double pressureRequested) {
                this.pressureRequested = pressureRequested;
                return this;
            }

            Builder pressureAchieved(double pressureAchieved) {
                this.pressureAchieved = pressureAchieved;
                return this;
            }

            Builder unitTokens(int unitTokens) {
                this.unitTokens = unitTokens;
                return this;
            }

            Builder status(Status status) {
                this.status = status;
                return this;
            }

            Builder errorMessage(String errorMessage) {
                this.errorMessage = errorMessage;
                return this;
            }

            Builder answers(List<Answer> answers) {
                this.answers = answers;
                return this;
            }

            Builder compactions(Map<CompactionKind, Integer> compactions) {
                this.compactions = compactions;
                return this;
            }

            Builder compactionsInPressurePhase(Map<CompactionKind, Integer> compactionsInPressurePhase) {
                this.compactionsInPressurePhase = compactionsInPressurePhase;
                return this;
            }

            Builder compactionsByTrigger(Map<CompactionTrigger, Integer> compactionsByTrigger) {
                this.compactionsByTrigger = compactionsByTrigger;
                return this;
            }

            Builder overBlockingLimit(int overBlockingLimit) {
                this.overBlockingLimit = overBlockingLimit;
                return this;
            }

            Builder summaryCalls(int summaryCalls) {
                this.summaryCalls = summaryCalls;
                return this;
            }

            Builder summaryFailures(int summaryFailures) {
                this.summaryFailures = summaryFailures;
                return this;
            }

            Builder sentViewTokens(List<Integer> sentViewTokens) {
                this.sentViewTokens = sentViewTokens;
                return this;
            }

            Builder reportedPromptTokens(long reportedPromptTokens) {
                this.reportedPromptTokens = reportedPromptTokens;
                return this;
            }

            Builder sessionHistoryCalls(int sessionHistoryCalls) {
                this.sessionHistoryCalls = sessionHistoryCalls;
                return this;
            }

            Builder refetchAttempts(int refetchAttempts) {
                this.refetchAttempts = refetchAttempts;
                return this;
            }

            Builder skippedFetches(int skippedFetches) {
                this.skippedFetches = skippedFetches;
                return this;
            }

            Builder failedTurns(int failedTurns) {
                this.failedTurns = failedTurns;
                return this;
            }

            Builder userInputs(List<String> userInputs) {
                this.userInputs = userInputs;
                return this;
            }

            ContextPressureResult build() {
                return new ContextPressureResult(this);
            }
        }
    }
}
