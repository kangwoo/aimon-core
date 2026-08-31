package at.aimon.core.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Entry point for a type-preserving, N-stage {@code pipeline} builder (design §6.1).
 *
 * <p>
 * Java generics cannot express an arbitrary-N pipeline through a single erased signature, so the two-stage
 * {@link WorkflowContext#pipeline(List, java.util.function.Function, java.util.function.BiFunction)} overload does
 * not generalise. This builder threads the intermediate type through each {@link Stage#then} call, giving compile-time
 * type safety for any number of stages. It is pure static composition — it desugars to
 * {@link WorkflowContext#parallel}
 * over per-item stage chains and adds no new execution primitive to the {@link WorkflowContext} SPI.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * List<String> verdicts = Pipeline.over(diffs)
 *         .then((d, orig) -> ctx.agent(bugReviewer, "find bugs in " + d))
 *         .then((r, orig) -> r.text())
 *         .then((text, orig) -> ctx.agent(verifier, "refute: " + text).text())
 *         .run(ctx);
 * }
 * </pre>
 */
public final class Pipeline {

    private Pipeline() {
    }

    /**
     * Starts stacking stages over {@code items}. The initial stage is the identity (no transformation yet).
     *
     * @param items
     *            the items to process (must not be null; defensively copied; may contain null elements — a failed
     *            prior fan-out position — which flow to the stages: stage code must be null-tolerant, as for
     *            {@link WorkflowContext#pipeline})
     * @param <I>
     *            the item type
     * @return a {@link Stage} at the identity position
     */
    public static <I> Stage<I, I> over(List<I> items) {
        Objects.requireNonNull(items, "items cannot be null");
        return new Stage<>(Collections.unmodifiableList(new ArrayList<>(items)), Function.identity());
    }
}
