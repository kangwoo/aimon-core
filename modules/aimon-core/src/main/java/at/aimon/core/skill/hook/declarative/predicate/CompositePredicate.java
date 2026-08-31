package at.aimon.core.skill.hook.declarative.predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * {@link ToolInputPredicate} that combines several delegate predicates with a logical operator (AIMON extension).
 *
 * <p>
 * Two combinators are supplied:
 * <ul>
 * <li>{@link #or(ToolInputPredicate...)} — fires when <i>any</i> delegate fires. Short-circuits on the first match.
 * <li>{@link #and(ToolInputPredicate...)} — fires when <i>every</i> delegate fires. Short-circuits on the first
 * miss.
 * </ul>
 *
 * <p>
 * Empty delegate lists are rejected at construction time — callers that mean "always" should use
 * {@link NameOnlyPredicate#ANY} instead. Single-delegate factory calls return the delegate unchanged so that callers
 * never wrap predicates needlessly.
 *
 * <p>
 * Immutable; thread-safe as long as every delegate is.
 */
public final class CompositePredicate implements ToolInputPredicate {

    /** Logical combinator for {@link CompositePredicate}. */
    public enum Op {
        /** Fires when any delegate fires (logical OR). */
        OR,
        /** Fires when every delegate fires (logical AND). */
        AND
    }

    private final Op op;
    private final List<ToolInputPredicate> delegates;

    private CompositePredicate(Op op, List<ToolInputPredicate> delegates) {
        this.op = op;
        this.delegates = delegates;
    }

    /**
     * Builds an OR-combined predicate.
     *
     * @param delegates
     *            The delegate predicates (must not be null, must contain at least one non-null entry)
     * @return The OR-combined predicate, or the single delegate unchanged when {@code delegates.length == 1}
     */
    public static ToolInputPredicate or(ToolInputPredicate... delegates) {
        return combine(Op.OR, delegates);
    }

    /**
     * Builds an AND-combined predicate.
     *
     * @param delegates
     *            The delegate predicates (must not be null, must contain at least one non-null entry)
     * @return The AND-combined predicate, or the single delegate unchanged when {@code delegates.length == 1}
     */
    public static ToolInputPredicate and(ToolInputPredicate... delegates) {
        return combine(Op.AND, delegates);
    }

    private static ToolInputPredicate combine(Op op, ToolInputPredicate... delegates) {
        Objects.requireNonNull(delegates, "Delegates cannot be null");
        if (delegates.length == 0) {
            throw new IllegalArgumentException("At least one delegate is required");
        }
        final List<ToolInputPredicate> copy = new ArrayList<>(delegates.length);
        for (ToolInputPredicate d : delegates) {
            copy.add(Objects.requireNonNull(d, "Delegate cannot be null"));
        }
        if (copy.size() == 1) {
            return copy.get(0);
        }
        return new CompositePredicate(op, Collections.unmodifiableList(copy));
    }

    @Override
    public boolean test(String toolName, ToolInput input) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(input, "Input cannot be null");
        for (ToolInputPredicate d : delegates) {
            final boolean match = d.test(toolName, input);
            if (op == Op.OR && match) {
                return true;
            }
            if (op == Op.AND && !match) {
                return false;
            }
        }
        return op == Op.AND;
    }

    /**
     * Returns the combinator operator.
     *
     * @return The operator (never null)
     */
    public Op getOp() {
        return op;
    }

    /**
     * Returns an unmodifiable view of the delegate predicates in registration order.
     *
     * @return The delegates (never null, never empty)
     */
    public List<ToolInputPredicate> getDelegates() {
        return delegates;
    }

    @Override
    public String toString() {
        final String separator = op == Op.OR ? " | " : " & ";
        return delegates.stream().map(Object::toString).collect(Collectors.joining(separator, "(", ")"));
    }
}
