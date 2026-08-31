package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;

/**
 * Static accessors that tools and executor-adjacent code use to read the cooperative interrupt wiring out of a
 * {@link ToolContext} without repeating the key lookup + fallback boilerplate.
 *
 * <p>
 * Placed in the {@code at.aimon.core.agent.tool} package so the dependency direction is {@code tool → interrupt}
 * only, matching the package-cycle-free slice established by {@link InterruptToolKeys}.
 *
 * <p>
 * Behaviour:
 *
 * <ul>
 * <li>{@link #signalOf(ToolContext)} never returns {@code null}. If the context does not carry a signal (typical for
 * unit tests or CLI-invoked diagnostic tools) it returns {@link NoopCancellationSignal#INSTANCE} so callers can
 * always call {@link CancellationSignal#isCancelled()} or {@link CancellationSignal#checkpoint()} safely.
 * <li>{@link #registrarOf(ToolContext)} returns an {@link Optional} because only tools declared
 * {@link InterruptBehavior#THREAD_INTERRUPT} or {@link InterruptBehavior#EXTERNALLY_TERMINATED} receive a registrar
 * — cooperative/non-interruptible tools must not silently register terminators.
 * </ul>
 */
public final class InterruptAccess {

    private InterruptAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the {@link CancellationSignal} stored in the given context, or
     * {@link NoopCancellationSignal#INSTANCE} when none is present.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the active signal, or the noop singleton when absent (never null)
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static CancellationSignal signalOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(InterruptToolKeys.CANCELLATION_SIGNAL).orElse(NoopCancellationSignal.INSTANCE);
    }

    /**
     * Returns the {@link TerminatorRegistrar} stored in the given context, if any. Tools whose declared
     * {@link InterruptBehavior} is {@link InterruptBehavior#NON_INTERRUPTIBLE} or
     * {@link InterruptBehavior#COOPERATIVE} should not expect a registrar to be present.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the registrar if the executor injected one, otherwise {@link Optional#empty()}
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static Optional<TerminatorRegistrar> registrarOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(InterruptToolKeys.TERMINATOR_REGISTRAR);
    }
}
