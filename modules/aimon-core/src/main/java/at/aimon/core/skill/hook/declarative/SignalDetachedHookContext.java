package at.aimon.core.skill.hook.declarative;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * A view of a firing context that answers "no signal" for {@link #getExecutionCancellation()} and delegates
 * everything else.
 *
 * <p>
 * It is what a hook that declared {@code ignoreInterrupt} hands its {@link ShellActionExecutor}: the executor then
 * builds the command's options without a cancellation token and registers nothing on the execution's signal, so the
 * command starts and runs exactly as one fired on an already-cancelled execution does under the report rule. The
 * option is per hook and the context is per chain, which is why this is a view and not a field of the context.
 *
 * <p>
 * The view is a plain {@link HookContext}, not the event's own context type: an executor must use what it is handed
 * through this interface only.
 *
 * <p>
 * Immutable; thread-safe as long as the wrapped context is.
 */
final class SignalDetachedHookContext implements HookContext {

    private final HookContext delegate;

    SignalDetachedHookContext(HookContext delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
    }

    @Override
    public InvokerType getInvokerType() {
        return delegate.getInvokerType();
    }

    @Override
    public String getInvokerName() {
        return delegate.getInvokerName();
    }

    @Override
    public HookRegistry getHookRegistry() {
        return delegate.getHookRegistry();
    }

    @Override
    public Optional<ExecutionEnvironment> getExecutionEnvironment() {
        return delegate.getExecutionEnvironment();
    }

    @Override
    public Optional<EnvironmentDescriptor> getEnvironmentDescriptor() {
        return delegate.getEnvironmentDescriptor();
    }

    /** Always empty: the command this view is handed to is not tied to the execution's signal. */
    @Override
    public Optional<CancellationSignal> getExecutionCancellation() {
        return Optional.empty();
    }

    @Override
    public Instant getTimestamp() {
        return delegate.getTimestamp();
    }

    @Override
    public Map<String, Object> getExecutionAttributes() {
        return delegate.getExecutionAttributes();
    }
}
