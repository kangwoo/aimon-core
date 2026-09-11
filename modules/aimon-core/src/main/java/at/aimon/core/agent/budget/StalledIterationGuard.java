package at.aimon.core.agent.budget;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.ToolUseResult;

/**
 * The death-spiral guard every tool loop with one shares: a turn ({@code OrcaAgentExecutor}), a subagent fork
 * ({@code DefaultSubagentExecutor}) and a skill's loop ({@code LlmSkillExecutor}).
 *
 * <p>
 * An iteration is <em>stalled</em> when it issued at least one tool call and every result came back an error — the
 * model kept acting and made no forward progress. {@link #MAX_CONSECUTIVE_STALLED_ITERATIONS} stalled iterations in a
 * row stop the loop before its next LLM call, with {@link CompletionReason#ERROR} on the two executors and a failure on
 * a skill; any iteration that made progress resets the streak. A response cut off at {@code max_tokens} inside its tool
 * calls counts, because every one of its calls is refused rather than run ({@link TruncatedResponses}).
 *
 * <p>
 * One definition, for the reason {@link TruncatedResponses} is one: the loops cannot disagree about what "no progress"
 * means. It lives here rather than on the main executor because the fork may not reference
 * {@code at.aimon.core.agent.impl}.
 *
 * <p>
 * What the guard does not decide is cancellation. An iteration whose unstarted calls were short-circuited by a tripped
 * signal also reads all-error, so each loop settles cancellation before it records an iteration here: the turn resets
 * the streak when its signal is tripped, and the fork records only after its iteration-tail cancellation check.
 *
 * <p>
 * Not thread-safe. One instance per execution, confined to its loop, like {@link BudgetTracker}.
 */
public final class StalledIterationGuard {

    /** Consecutive stalled iterations tolerated before the loop stops. */
    public static final int MAX_CONSECUTIVE_STALLED_ITERATIONS = 3;

    private int consecutiveStalledIterations;

    /** Whether every iteration of the current streak was a cut response whose calls were refused. */
    private boolean everyStalledIterationRefusedAtMaxTokens;

    /**
     * Whether a completed iteration was stalled.
     *
     * <p>
     * An iteration with no tool calls is never stalled: a loop treats an empty-tool response as its final answer, and
     * this predicate is not reached for it. An iteration where at least one call succeeded is progress. This keeps the
     * guard conservative — it fires on iterations that are not converging, not on one transient tool failure.
     *
     * @param toolUseResults
     *            the results of the iteration's tool calls (must not be null; may be empty)
     * @return {@code true} iff the iteration issued at least one tool call and every result is an error
     */
    public static boolean isStalled(List<ToolUseResult> toolUseResults) {
        Objects.requireNonNull(toolUseResults, "toolUseResults cannot be null");
        return !toolUseResults.isEmpty() && toolUseResults.stream().allMatch(ToolUseResult::isError);
    }

    /**
     * Records one completed tool iteration. A non-stalled iteration resets the streak.
     *
     * @param toolUseResults
     *            the results the iteration committed (must not be null)
     * @param refusedAtMaxTokens
     *            whether this iteration's calls were refused because the response was cut off at {@code max_tokens}
     * @return {@code true} iff this iteration brings the streak to {@link #MAX_CONSECUTIVE_STALLED_ITERATIONS}
     */
    public boolean recordToolIteration(List<ToolUseResult> toolUseResults, boolean refusedAtMaxTokens) {
        if (!isStalled(toolUseResults)) {
            reset();
            return false;
        }
        everyStalledIterationRefusedAtMaxTokens = consecutiveStalledIterations == 0
                ? refusedAtMaxTokens
                : everyStalledIterationRefusedAtMaxTokens && refusedAtMaxTokens;
        consecutiveStalledIterations++;
        return consecutiveStalledIterations >= MAX_CONSECUTIVE_STALLED_ITERATIONS;
    }

    /**
     * Clears the streak, as an iteration that made progress does.
     */
    public void reset() {
        consecutiveStalledIterations = 0;
        everyStalledIterationRefusedAtMaxTokens = false;
    }

    /**
     * @return the number of stalled iterations in the current streak
     */
    public int getConsecutiveStalledIterations() {
        return consecutiveStalledIterations;
    }

    /**
     * The failure message of an execution this guard stopped. Meaningful after {@link #recordToolIteration} returned
     * {@code true}.
     *
     * <p>
     * The text the turn has always used, and — only when every iteration of the streak was a cut response whose calls
     * were refused — a clause saying so. A failing tool invites another tool; a cut response invites less output per
     * response. Nothing that reads the {@link CompletionReason} branches on that difference, but a parent model reading
     * this message does, and this is the only place it can see it. A mixed streak gets the plain text.
     *
     * @return the stop message (never null)
     */
    public String stopMessage() {
        final String message = "Execution aborted: " + consecutiveStalledIterations
                + " consecutive tool-only iterations made no progress (all tool calls failed)";
        if (!everyStalledIterationRefusedAtMaxTokens) {
            return message;
        }
        return message + " — each of those responses was cut off at max_tokens, and its tool calls were refused";
    }
}
