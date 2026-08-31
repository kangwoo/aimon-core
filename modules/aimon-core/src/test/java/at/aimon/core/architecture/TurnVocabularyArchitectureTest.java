package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A turn is the processing of one user input in a session. Nothing in these five trees is one.
 *
 * <p>
 * Three different reasons put a package on the list:
 *
 * <ul>
 * <li><em>It runs no session.</em> {@code at.aimon.core.subagent..} runs forks, and {@code DefaultSubagentExecutor}
 * says so where it builds the tool context: <em>"No SESSION_ID: this run is not a session's turn"</em>. A fork has an
 * {@code ExecutionId} and no {@code SessionId} at all. {@code at.aimon.core.agent.interrupt..} is the definition site
 * for the signal vocabulary the session path and the fork paths share, so the word it picks propagates outward — it
 * once picked "turn", the subagent tree echoed it, and the two together were the largest of the three misuse clusters.
 * <li><em>Its unit is the iteration.</em> {@code at.aimon.core.agent.loop..} tags ReAct loop re-entries, one per
 * iteration. It is reached from a session's turn, but nothing it names is a turn — a single turn produces as many
 * transitions as it runs iterations. It is on this list because it actually regressed: {@code LoopTransitionReason} had
 * a {@code NEXT_TURN} constant emitted once per iteration into an operator-visible trace attribute.
 * <li><em>Everything it names is an execution.</em> {@code at.aimon.core.tools.task..} and
 * {@code at.aimon.core.tools.workflow..} start work on the fork paths above — that is {@code TaskTool} and
 * {@code WorkflowTool} — and the rest of both trees lists, polls and stops what was started. What is started is a
 * session-less execution regardless of who asked for it, and the asker is not reliably a turn either: a nested
 * {@code Task} inside a fork is dispatched by that fork, and a background one outlives whatever dispatched it, which
 * is why the trackers exist at all. Both trees earned the entry — their comments misused "turn" at seven sites, the
 * caller at six of them and the resumed run's own exchanges at {@code TaskTool}'s feature list.
 * </ul>
 *
 * <p>
 * What this cannot cover is the tree where both units are real. {@code at.aimon.core.agent.impl.orca} runs turns
 * <em>and</em> counts iterations, so {@code turnId} and {@code iterationCount} are both correct there and no
 * name-shaped rule tells a right one from a wrong one — that is where {@code MAX_CONSECUTIVE_STALLED_TURNS} lived, and
 * only review catches its like. Nor does it reach other modules: it imports {@code at.aimon.core}, so the GraalJS
 * frontend of the workflow tool ({@code at.aimon.workflow.graaljs}) is outside it and was corrected by hand. Prose is
 * out of reach everywhere: the misuse this commemorates was mostly comments and javadoc, which no ArchUnit rule reads.
 * The vocabulary rule itself lives in {@code docs/overview/glossary.md} §4.
 *
 * <p>
 * A tool needing to name a turn is not impossible — addressing an interrupt at one would do it. That day this list is
 * the thing to edit, deliberately and with the reason written down; it is not a case for loosening the pattern.
 *
 * <p>
 * All three rules pass as written. This is a regression guard, not a cleanup.
 */
@DisplayName("turn vocabulary architecture")
class TurnVocabularyArchitectureTest {

    /**
     * The trees that run no turn: the session-less executions, the package naming the signals they share, the ReAct
     * loop, whose unit is the iteration, and the tools that start and track the first of those.
     */
    private static final String[] TURN_FREE_PACKAGES = {"at.aimon.core.subagent..", "at.aimon.core.agent.interrupt..",
            "at.aimon.core.agent.loop..", "at.aimon.core.tools.task..", "at.aimon.core.tools.workflow.."};

    /**
     * {@code Turn} as a camel-case word, {@code turn}/{@code turns} as a whole or leading word, or {@code TURN}/
     * {@code TURNS} as a whole constant or a constant's leading, interior or trailing word. Full-match semantics, so
     * each alternative carries its own {@code .*}. {@code return}/{@code RETURN_CODE}/{@code MAX_RETURNS}/
     * {@code saturnPhase} must not trip it; {@link #patternMatchesTurnNamesAndOnlyThose()} pins both directions.
     */
    private static final String TURN_IDENTIFIER = ".*Turn.*|turns?|turns?[A-Z0-9_].*|TURNS?|TURNS?_.*|.*_TURNS?"
            + "|.*_TURNS?_.*";

    private static final JavaClasses TURN_FREE_MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("at.aimon.core");

    @Test
    @DisplayName("no type in the turn-free trees is named for a turn")
    void noTurnFreeTypeIsNamedForATurn() {
        final ArchRule rule = noClasses().that().resideInAnyPackage(TURN_FREE_PACKAGES).should()
                .haveSimpleNameContaining("Turn")
                .as("subagent forks, the shared interrupt signals, the ReAct loop and the tools that start and track "
                        + "forks do not run a session's turn — name the type for the execution, or for the iteration "
                        + "if it counts ReAct passes.");

        rule.check(TURN_FREE_MAIN);
    }

    @Test
    @DisplayName("no member in the turn-free trees is named for a turn")
    void noTurnFreeMemberIsNamedForATurn() {
        // Checked separately from the type rule because a member is where this reappears without anyone noticing: the
        // regression in agent.loop was one enum constant inside a correctly named type, and a type called
        // Turn-something would never have survived review in the first place.
        final ArchRule rule = noMembers().that().areDeclaredInClassesThat().resideInAnyPackage(TURN_FREE_PACKAGES)
                .should().haveNameMatching(TURN_IDENTIFIER)
                .as("a fork has an ExecutionId and no SessionId, a loop transition is emitted per iteration, and a "
                        + "Task or Workflow run is a fork whoever dispatched it — their signals, budgets, counters "
                        + "and reasons belong to an execution or an iteration, not to a turn.");

        rule.check(TURN_FREE_MAIN);
    }

    @Test
    @DisplayName("the identifier pattern matches turn names and only those")
    void patternMatchesTurnNamesAndOnlyThose() {
        // The rules above pass vacuously today, so a hole in the pattern is invisible until the day it lets something
        // through. It has had one: turn[A-Z0-9_] rejected the plural, so `turnsRun` was legal and `turnCount` was not.
        final List<String> named = List.of("turn", "turns", "turnId", "turnCount", "turnsRun", "turnsRemaining",
                "parentTurnId", "howManyTurns", "TURN", "TURNS", "NEXT_TURN", "MAX_TURNS", "TURN_LIMIT",
                "TURNS_REMAINING", "MAX_TURN_COUNT", "Turn", "TurnBudget");
        final List<String> notNamed = List.of("return", "returnValue", "returns", "RETURN", "RETURN_CODE",
                "MAX_RETURNS", "saturnPhase", "SATURN", "nocturne", "iteration", "iterationCount", "executionId");

        assertThat(named).allSatisfy(name -> assertThat(Pattern.matches(TURN_IDENTIFIER, name))
                .withFailMessage("expected %s to be caught as a turn identifier", name).isTrue());
        assertThat(notNamed).allSatisfy(name -> assertThat(Pattern.matches(TURN_IDENTIFIER, name))
                .withFailMessage("expected %s to pass — it is not a turn identifier", name).isFalse());
    }
}
