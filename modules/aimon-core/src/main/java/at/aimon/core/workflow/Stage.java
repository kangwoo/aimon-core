package at.aimon.core.workflow;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One accumulated stage of a type-preserving {@link Pipeline} (design §6.1). Immutable — each {@link #then}
 * returns a new {@code Stage}.
 *
 * @param <I>
 *            the original item type
 * @param <C>
 *            the result type accumulated up to this stage
 */
public final class Stage<I, C> {

    private final List<I> items;
    private final Function<I, C> chain;

    Stage(List<I> items, Function<I, C> chain) {
        this.items = items;
        this.chain = chain;
    }

    /**
     * Appends a stage: {@code (currentResult, originalItem) -> newResult}. Mirrors the two-stage {@code pipeline}'s
     * {@code BiFunction<A, I, R>} ergonomics — every stage still sees the original item alongside the prior result.
     *
     * @param stage
     *            the next stage (must not be null)
     * @param <N>
     *            the new result type
     * @return a new {@code Stage} at type {@code N}
     */
    public <N> Stage<I, N> then(BiFunction<C, I, N> stage) {
        Objects.requireNonNull(stage, "stage cannot be null");
        final Function<I, C> prev = chain;
        return new Stage<>(items, i -> stage.apply(prev.apply(i), i));
    }

    /**
     * Runs the accumulated pipeline: each item's whole stage chain becomes one thunk fanned out via
     * {@link WorkflowContext#parallel}. Items run concurrently, stages within an item run sequentially, and there
     * is no barrier between stages across items (wall-clock = slowest single-item chain when top-level and
     * pool-capacity
     * permit). The returned list matches input order.
     *
     * @param ctx
     *            the run context (must not be null)
     * @return the per-item results in input order (never null; a chain that throws yields {@code null} at that
     *         position,
     *         mirroring {@link WorkflowContext#parallel})
     */
    public List<C> run(WorkflowContext ctx) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        final Function<I, C> c = chain;
        return ctx.parallel(items.stream().<Supplier<C>>map(i -> () -> c.apply(i)).toList());
    }
}
