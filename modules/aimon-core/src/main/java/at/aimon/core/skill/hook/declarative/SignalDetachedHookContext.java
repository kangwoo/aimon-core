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
 * It is what a declarative hook on a report event ({@code onStop}, {@code subagentStop}, {@code postCompact},
 * {@code postTool}, {@code permissionDenied}, {@code subagentStart}) hands its {@link ShellActionExecutor}: the
 * executor then builds the command's options without a cancellation token and registers nothing on the execution's
 * signal, so an interrupt does not stop the command whether it arrives before the command starts or while it runs.
 * The context itself keeps answering a live signal — that answer is for hooks written in code, which decide for
 * themselves what to tie to it — which is why this is a view and not the context's own answer.
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
