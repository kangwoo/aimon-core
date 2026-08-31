package at.aimon.core.workflow;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import at.aimon.core.subagent.Subagent;

/**
 * The run-scoped context a {@link WorkflowScript} receives, exposing the workflow primitives.
 *
 * <p>
 * All methods are safe to call from the worker threads a {@code parallel}/{@code pipeline} fan-out spawns.
 */
public interface WorkflowContext {

    /**
     * Runs one subagent to completion and returns its result. A subagent <em>execution</em> failure is not thrown but
     * returned as an unsuccessful {@link AgentStepResult}. This call does, however, throw
     * {@link at.aimon.core.workflow.exception.WorkflowBudgetExceededException} if the run's agent-count
     * backstop is exceeded (a run-fatal condition that aborts the run even from a fan-out worker).
     *
     * @param task
     *            the task to run (must not be null)
     * @return the step result (never null)
     */
    AgentStepResult agent(AgentTask task);

    /**
     * Convenience overload of {@link #agent(AgentTask)} for an inline subagent and goal.
     *
     * @param subagent
     *            the inline subagent (must not be null)
     * @param goal
     *            the goal (must not be null)
     * @return the step result (never null)
     */
    default AgentStepResult agent(Subagent subagent, String goal) {
        return agent(AgentTask.of(subagent, goal));
    }

    /**
     * Barrier fan-out: runs every thunk concurrently and waits for all to finish. The returned list matches input order
     * regardless of completion order. A thunk's ordinary <em>execution failure</em> (unexpected throw) yields
     * {@code null} at that position (mirroring Claude Code's {@code parallel}); a run-fatal
     * {@link at.aimon.core.workflow.exception.WorkflowException} is not isolated but propagates to abort the
     * run. Consumers must therefore be null-safe.
     *
     * @param thunks
     *            the tasks to run (must not be null)
     * @param <R>
     *            the result type
     * @return the results in input order (never null)
     */
    <R> List<R> parallel(List<Supplier<R>> thunks);

    /**
     * Item-parallel two-stage pipeline: each item flows through {@code stage1} then {@code stage2} independently. Items
     * run concurrently with no barrier between stages (item A may be in {@code stage2} while item B is still in
     * {@code stage1}); the returned list matches input order. {@code stage1} inputs must be null-safe, as for
     * {@link #parallel}. For three or more stages compose manually, e.g.
     * {@code parallel(items.map(i -> () -> s3(s2(s1(i), i), i)))}.
     *
     * @param items
     *            the items to process (must not be null)
     * @param stage1
     *            the first stage (must not be null)
     * @param stage2
     *            the second stage; receives the {@code stage1} result and the original item (must not be null)
     * @param <I>
     *            the item type
     * @param <A>
     *            the intermediate (stage1) type
     * @param <R>
     *            the result type
     * @return the results in input order (never null)
     */
    <I, A, R> List<R> pipeline(List<I> items, Function<I, A> stage1, BiFunction<A, I, R> stage2);

    /**
     * Starts a new progress phase (a grouping label for subsequent events).
     *
     * @param title
     *            the phase title
     */
    void phase(String title);

    /**
     * Emits a free-form progress message.
     *
     * @param message
     *            the message
     */
    void log(String message);
}
