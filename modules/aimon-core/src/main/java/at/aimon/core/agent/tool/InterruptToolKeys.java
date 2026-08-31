package at.aimon.core.agent.tool;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;

/**
 * Typed {@link ToolContextKey} declarations for cooperative-interrupt wiring injected into the
 * {@link ToolContext} at turn boundaries.
 *
 * <p>
 * These keys live in the {@code at.aimon.core.agent.tool} package alongside {@link ToolContextKey} and
 * {@link ToolContext} so their home does not force {@code at.aimon.core.agent.interrupt} to take a dependency on
 * {@code ToolContextKey} — which would introduce a package cycle with {@code tool → interrupt} (established by
 * {@link at.aimon.core.agent.tool.Tool#getInterruptBehavior()}).
 *
 * <p>
 * Use {@link InterruptAccess} to read these values from a {@link ToolContext} — it handles the no-signal-present
 * fallback and the presence-optional semantics of the registrar.
 */
public final class InterruptToolKeys {

    /**
     * Typed key for the per-turn {@link CancellationSignal}.
     *
     * <p>
     * Injected into {@link ToolContext} by the agent executor at turn start. Cooperative tools read the signal and
     * poll {@link CancellationSignal#isCancelled()} or call {@link CancellationSignal#checkpoint()} so they can return
     * early when the user, a higher-priority queued input, or the runtime requests cancellation. Tools that do not
     * find a signal in the context (e.g. unit-test invocations) should treat it as
     * {@link at.aimon.core.agent.interrupt.NoopCancellationSignal#INSTANCE}.
     */
    public static final ToolContextKey<CancellationSignal> CANCELLATION_SIGNAL = ToolContextKey
            .of("interrupt.cancellationSignal", CancellationSignal.class);

    /**
     * Typed key for the per-tool-execution {@link TerminatorRegistrar}.
     *
     * <p>
     * Injected into {@link ToolContext} only for tools whose {@link Tool#getInterruptBehavior() interrupt behaviour} is
     * {@link InterruptBehavior#THREAD_INTERRUPT} or {@link InterruptBehavior#EXTERNALLY_TERMINATED}. The tool registers
     * a {@link at.aimon.core.agent.interrupt.Terminator} callback against this registrar before starting blocking work
     * so the coordinator can fire it on interrupt. The registrar is closed by the executor when the tool returns.
     */
    public static final ToolContextKey<TerminatorRegistrar> TERMINATOR_REGISTRAR = ToolContextKey
            .of("interrupt.terminatorRegistrar", TerminatorRegistrar.class);

    private InterruptToolKeys() {
        throw new AssertionError("This class should not be instantiated");
    }
}
