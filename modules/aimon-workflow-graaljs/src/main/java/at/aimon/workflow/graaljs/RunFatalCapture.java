package at.aimon.workflow.graaljs;

import at.aimon.core.workflow.exception.WorkflowException;

/**
 * Captures the first run-fatal {@link WorkflowException} raised while driving core primitives from a JS binding,
 * so it survives the async-wrapper's promise machinery.
 *
 * <p>
 * A run-fatal exception thrown inside {@code (async () => { ... })()} becomes a promise <em>rejection</em> — and a
 * guest {@code try/catch} around {@code agent()} could even swallow it. Both would erase the run-fatal semantics the
 * fan-out dispatcher relies on. The bindings record the exception here and rethrow; {@code GraalJsWorkflowScript}
 * re-throws the captured exception after settlement, so a run-fatal abort always wins over guest control flow.
 *
 * <p>
 * Written only on the owner thread (the {@code agent}/{@code parallel}/{@code pipeline} bindings and the dispatcher's
 * run-fatal rethrow both surface on it); {@code volatile} guards against any incidental cross-thread read.
 */
final class RunFatalCapture {

    private volatile WorkflowException fatal;

    /** Records the first run-fatal exception; later ones are ignored (the first aborts the run). */
    void record(WorkflowException exception) {
        if (fatal == null) {
            fatal = exception;
        }
    }

    /** The captured run-fatal exception, or {@code null} if none occurred. */
    WorkflowException get() {
        return fatal;
    }
}
