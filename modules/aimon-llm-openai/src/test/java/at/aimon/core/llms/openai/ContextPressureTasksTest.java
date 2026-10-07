package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.llms.openai.ContextPressureTasks.ContextPressureTask;
import at.aimon.core.llms.openai.ContextPressureTasks.Question;
import at.aimon.core.llms.openai.ContextPressureTasks.SourceKind;
import at.aimon.core.llms.openai.ContextPressureTasks.Step;
import at.aimon.core.llms.openai.ContextPressureTasks.Stratum;
import at.aimon.core.llms.openai.ContextPressureTasks.TaskKind;
import at.aimon.core.llms.openai.ContextPressureTasks.Verdict;

/**
 * The comparison tasks as data: that generating one is deterministic, that it carries the pressure it was asked for,
 * that its units are on the side of the prune floor its task needs, that its answers cannot be guessed or found twice,
 * and that scoring says what it should. No rig and no provider.
 */
@DisplayName("ContextPressureTasks - generation, invariants, fingerprint and scoring")
class ContextPressureTasksTest {

    private static final TokenEstimator ESTIMATOR = new HeuristicTokenEstimator();
    private static final int EFFECTIVE = ContextEngineLiveRig.COMPARISON_LIMITS.getEffectiveContextWindow();
    private static final double[] LEVELS = {ContextPressureTasks.CONTROL_PRESSURE, 2, 4};

    private static ContextPressureTask task(TaskKind kind, long seed, double pressure) {
        return ContextPressureTasks.generate(kind, seed, pressure, EFFECTIVE, ESTIMATOR);
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    @DisplayName("the same kind, seed and pressure give the same task, and another seed gives another")
    void generationIsDeterministic(TaskKind kind) {
        final ContextPressureTask first = task(kind, 1, 2);
        final ContextPressureTask again = task(kind, 1, 2);

        assertThat(ContextPressureTasks.fingerprint(again)).isEqualTo(ContextPressureTasks.fingerprint(first));
        assertThat(again.plannedTokens()).isEqualTo(first.plannedTokens());
        assertThat(again.unitTokens()).isEqualTo(first.unitTokens());
        assertThat(again.questions()).extracting(Question::stratum, Question::sourceLine, Question::lookupKey)
                .containsExactlyElementsOf(first.questions().stream()
                        .map(question -> tuple(question.stratum(), question.sourceLine(), question.lookupKey()))
                        .collect(Collectors.toList()));
        assertThat(ContextPressureTasks.fingerprint(task(kind, 2, 2)))
                .isNotEqualTo(ContextPressureTasks.fingerprint(first));
    }

    /**
     * The same three literals stand in the copy of this test in the other provider module. A generator changed in one
     * copy only turns that module's test red; the literal then has to change in both, which is the point.
     */
    @Test
    @DisplayName("the corpus of each task at seed 1, pressure 2 is the one both provider modules give")
    void theCorpusIsTheOneBothModulesGive() {
        final String hint = "the generators changed: if that is intended, change them and this literal in BOTH"
                + " aimon-llm-openai and aimon-llm-anthropic, so the two providers are still given the same input";
        assertThat(ContextPressureTasks.fingerprint(task(TaskKind.NEEDLE, 1, 2))).as(hint)
                .isEqualTo("4f91efae0149992833b566588c8113eda8bdee108683c75affade83348a24e22");
        assertThat(ContextPressureTasks.fingerprint(task(TaskKind.KEY_VALUE, 1, 2))).as(hint)
                .isEqualTo("49fea3794141524ba05b1215fa842b0801a6ebc95afc63eaaf409e3002f7aeab");
        assertThat(ContextPressureTasks.fingerprint(task(TaskKind.LOG_TRIAGE, 1, 2))).as(hint)
                .isEqualTo("fb0c2e0607c690fb0d5e1776d6d3de7bc322b5afc21f2722e804523e90ffd327");
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    @DisplayName("a task plans at least its pressure and overshoots by less than one unit")
    void aTaskCarriesItsPressure(TaskKind kind) {
        for (double level : LEVELS) {
            final ContextPressureTask task = task(kind, 1, level);
            final int oneUnit = task.unitTokens()
                    + ContextPressureTasks.userInputTokens(ESTIMATOR, ContextPressureTasks.fetchInstruction("KV-100"));

            assertThat(task.plannedTokens()).as("%s at %s", kind, level)
                    .isGreaterThanOrEqualTo((int) (level * EFFECTIVE)).isLessThan((int) (level * EFFECTIVE) + oneUnit);
            assertThat(planned(task)).as("plannedTokens is the pressure phase, questions not counted")
                    .isEqualTo(task.plannedTokens());
        }
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    @DisplayName("the control level leaves the rolling threshold far away")
    void theControlLevelHasRoom(TaskKind kind) {
        assertThat(task(kind, 1, ContextPressureTasks.CONTROL_PRESSURE).plannedTokens())
                .as("planned tokens at the control level; tune the unit size to this bound, do not loosen it")
                .isLessThanOrEqualTo(ContextPressureRun.controlCeiling());
    }

    @Test
    @DisplayName("needle units are under the prune floor, and pages and dumps are over it and under a tail's worth")
    void unitSizesAreOnTheRightSideOfThePruneFloor() {
        final int pruneFloor = RollingContextEngine.DEFAULT_PRUNE_MIN_TOKENS;
        final int largest = (int) (0.15 * EFFECTIVE);
        for (double level : LEVELS) {
            assertThat(reportTokens(task(TaskKind.NEEDLE, 1, level))).as("needle units at %s", level).isNotEmpty()
                    .allMatch(tokens -> tokens < pruneFloor);
            for (TaskKind kind : EnumSet.of(TaskKind.KEY_VALUE, TaskKind.LOG_TRIAGE)) {
                assertThat(reportTokens(task(kind, 1, level))).as("%s units at %s", kind, level).isNotEmpty()
                        .allMatch(tokens -> tokens >= pruneFloor && tokens <= largest);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    @DisplayName("every answer is in the corpus exactly once, and no question gives one away")
    void answersAreUniqueAndUnguessable(TaskKind kind) {
        for (double level : LEVELS) {
            final ContextPressureTask task = task(kind, 1, level);
            final String corpus = task.steps().stream().map(Step::input).collect(Collectors.joining("\n")) + "\n"
                    + String.join("\n", task.reports().values());
            final String prompts = task.questions().stream().map(Question::prompt).collect(Collectors.joining("\n"));

            for (Question question : task.questions()) {
                final Set<String> values = new LinkedHashSet<>(question.distractors());
                assertThat(values).as("distractors of %s", question.id()).isNotEmpty()
                        .doesNotContain(question.expected());
                values.add(question.expected());
                for (String value : values) {
                    assertThat(ContextPressureTasks.VALUE_SHAPE.matcher(value).matches()).as(value).isTrue();
                    assertThat(occurrences(corpus, value)).as("%s in the corpus", value).isEqualTo(1);
                    assertThat(prompts).doesNotContain(value);
                    assertThat(values).filteredOn(other -> !other.equals(value))
                            .noneMatch(other -> other.contains(value) || value.contains(other));
                }
                assertThat(occurrences(corpus, question.sourceLine())).as("source line of %s", question.id())
                        .isEqualTo(1);
                assertThat(question.sourceLine()).contains(question.expected());
                assertThat(corpus).contains(question.lookupKey());
                if (question.sourceKind() == SourceKind.IN_TOOL_RESULT) {
                    final String report = task.reports().get(question.sourceReportId().orElseThrow());
                    assertThat(report.substring(question.sourceCharOffset())).startsWith(question.sourceLine());
                } else {
                    assertThat(question.sourceReportId()).isEmpty();
                }
            }
            assertThat(task.steps().get(0).input()).as("the first user message is pinned by rolling's head")
                    .isEqualTo(ContextPressureTasks.BRIEFING);
            assertThat(task.steps().stream().filter(step -> step.fetchedReportId().isPresent())
                    .map(step -> step.fetchedReportId().get())).containsExactlyElementsOf(task.reports().keySet());
        }
    }

    @Test
    @DisplayName("needle asks one early question per fact; the other two tasks ask in every stratum, late first")
    void questionsAreStratified() {
        for (double level : LEVELS) {
            final ContextPressureTask needle = task(TaskKind.NEEDLE, 1, level);
            assertThat(needle.questions()).hasSize(ContextPressureTasks.NEEDLE_FACTS)
                    .allMatch(question -> question.stratum() == Stratum.EARLY
                            && question.sourceKind() == SourceKind.IN_USER_MESSAGE);
            // The facts come before any report: none of them is ever in a rolling tail.
            assertThat(needle.steps().subList(1, 1 + ContextPressureTasks.NEEDLE_FACTS))
                    .allMatch(step -> step.fetchedReportId().isEmpty());

            final ContextPressureTask keyValue = task(TaskKind.KEY_VALUE, 1, level);
            assertThat(keyValue.questions()).hasSize(ContextPressureTasks.KEY_VALUE_QUESTIONS);
            final ContextPressureTask logTriage = task(TaskKind.LOG_TRIAGE, 1, level);
            assertThat(logTriage.questions()).hasSize(ContextPressureTasks.LOG_TRIAGE_QUESTIONS);
            for (ContextPressureTask task : List.of(keyValue, logTriage)) {
                final List<Stratum> asked = task.questions().stream().map(Question::stratum)
                        .collect(Collectors.toList());
                assertThat(asked).contains(Stratum.EARLY, Stratum.MIDDLE, Stratum.LATE);
                assertThat(asked).as("ask order").isSortedAccordingTo((a, b) -> b.compareTo(a));
                final List<String> ids = new ArrayList<>(task.reports().keySet());
                for (Question question : task.questions()) {
                    final int unit = ids.indexOf(question.sourceReportId().orElseThrow());
                    assertThat(unit >= ids.size() - ContextPressureTasks.LATE_UNITS)
                            .as("%s is in the last units", question.id()).isEqualTo(question.stratum() == Stratum.LATE);
                }
            }
            // One early key past the first SessionHistory part, one inside it: both read paths are asked for.
            assertThat(keyValue.questions()).filteredOn(question -> question.stratum() == Stratum.EARLY)
                    .anyMatch(question -> question.sourceCharOffset() > 2_000)
                    .anyMatch(question -> question.sourceCharOffset() < 2_000);
            assertThat(logTriage.questions()).as("the three forms")
                    .anyMatch(question -> question.prompt().startsWith("In dump") && question.distractors().size() == 1)
                    .anyMatch(question -> question.prompt().startsWith("In dump") && question.distractors().size() == 3)
                    .anyMatch(question -> question.prompt().startsWith("Which request id"));
        }
    }

    @Test
    @DisplayName("a pressure outside the offered range is refused before anything is generated")
    void pressureOutOfRangeIsRefused() {
        for (double level : new double[]{0, -1, 0.1, 8.5, Double.NaN}) {
            assertThatThrownBy(() -> task(TaskKind.NEEDLE, 1, level)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the rig's report tool answers to the name the fetch instruction uses")
    void theFetchInstructionNamesTheRigsTool() {
        assertThat(ContextPressureTasks.REPORT_TOOL).isEqualTo(ContextEngineLiveRig.REPORT_TOOL);
    }

    @Test
    @DisplayName("scoring: correct through chatter, wrong on a distractor, abstained on UNKNOWN or no value, failed")
    void scoring() {
        final Question question = Question.builder().id("Q1").prompt("?").expected("QX7-MULE-4417")
                .distractors(Set.of("AB1-WOLF-2210")).stratum(Stratum.EARLY).sourceKind(SourceKind.IN_USER_MESSAGE)
                .sourceLine("line").lookupKey("key").build();

        assertThat(ContextPressureTasks.verdict(question, "QX7-MULE-4417", true)).isEqualTo(Verdict.CORRECT);
        assertThat(ContextPressureTasks.verdict(question, "The value is `qx7-mule-4417`.", true))
                .isEqualTo(Verdict.CORRECT);
        assertThat(ContextPressureTasks.verdict(question, "\"QX7-MULE-4417\"\n", true)).isEqualTo(Verdict.CORRECT);

        assertThat(ContextPressureTasks.verdict(question, "AB1-WOLF-2210", true)).isEqualTo(Verdict.WRONG);
        assertThat(ContextPressureTasks.verdict(question, "QX7-MULE-4417 or AB1-WOLF-2210", true))
                .as("an answer that lists the candidates").isEqualTo(Verdict.WRONG);
        assertThat(ContextPressureTasks.verdict(question, "ZZ9-CRAB-0001", true)).isEqualTo(Verdict.WRONG);

        assertThat(ContextPressureTasks.verdict(question, "UNKNOWN", true)).isEqualTo(Verdict.ABSTAINED);
        assertThat(ContextPressureTasks.verdict(question, "unknown - maybe ZZ9-CRAB-0001", true))
                .isEqualTo(Verdict.ABSTAINED);
        assertThat(ContextPressureTasks.verdict(question, "", true)).isEqualTo(Verdict.ABSTAINED);
        assertThat(ContextPressureTasks.verdict(question, null, true)).isEqualTo(Verdict.ABSTAINED);
        assertThat(ContextPressureTasks.verdict(question, "I could not find that in the reports.", true))
                .isEqualTo(Verdict.ABSTAINED);

        assertThat(ContextPressureTasks.verdict(question, "QX7-MULE-4417", false)).isEqualTo(Verdict.FAILED);
    }

    private static int planned(ContextPressureTask task) {
        int tokens = 0;
        for (Step step : task.steps()) {
            tokens += ContextPressureTasks.userInputTokens(ESTIMATOR, step.input());
            if (step.fetchedReportId().isPresent()) {
                tokens += ContextPressureTasks.toolResultTokens(ESTIMATOR,
                        task.reports().get(step.fetchedReportId().get()));
            }
        }
        return tokens;
    }

    private static List<Integer> reportTokens(ContextPressureTask task) {
        return task.reports().values().stream().map(body -> ContextPressureTasks.toolResultTokens(ESTIMATOR, body))
                .collect(Collectors.toList());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
