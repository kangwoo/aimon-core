package at.aimon.workflow.graaljs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.workflow.WorkflowContext;
import at.aimon.core.workflow.WorkflowScript;
import at.aimon.core.workflow.exception.WorkflowException;
import at.aimon.workflow.graaljs.exception.JsScriptCancelledException;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * The single seam wrapping a JavaScript source as a {@link WorkflowScript}{@code <String>}, run unchanged by the
 * existing {@code WorkflowRunner} machinery (foreground/background/budget/cancellation/resume).
 *
 * <p>
 * The author body is wrapped in {@code (async () => { <source> })()} so {@code return}/{@code await} are legal and
 * the workflow result is captured as the promise's resolved value (GraalJS module eval returns {@code undefined} and
 * top-level {@code return} is a {@code SyntaxError}). Everything runs on the owner thread; the per-run {@code Context}
 * is created and force-closed here so it is torn down before the runner's per-run {@code finally} trips the signal.
 * Fan-out inputs are deep-detached into immutable {@code AgentTask}s before any worker runs.
 */
public final class GraalJsWorkflowScript implements WorkflowScript<String> {

    private static final Runnable NOOP_DEADLINE_ACTION = () -> {
    };

    private final String source;
    private final Map<String, Object> args;
    private final JsSandboxConfig sandbox;
    private final GraalJsEngineHolder engines;
    private final SubagentResolver subagents;
    private final CancellationSignal signal;
    private final Runnable wallClockAction;

    /**
     * Full constructor. {@code args}/{@code signal} may be {@code null} (treated as empty / no-op).
     * {@code wallClockAction} runs once on wall-clock expiry <em>before</em> the watchdog closes the context —
     * embedders pass a run-signal trip here so in-flight leaves terminate cooperatively (the close alone only unwinds
     * compute-bound guest code; it cannot free an owner parked in a {@code ctx.parallel} host join).
     */
    public GraalJsWorkflowScript(String source, Map<String, Object> args, JsSandboxConfig sandbox,
            GraalJsEngineHolder engines, SubagentResolver subagents, CancellationSignal signal,
            Runnable wallClockAction) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox cannot be null");
        this.engines = Objects.requireNonNull(engines, "engines cannot be null");
        this.subagents = Objects.requireNonNull(subagents, "subagents cannot be null");
        this.signal = signal == null ? NoopCancellationSignal.INSTANCE : signal;
        this.wallClockAction = wallClockAction == null ? NOOP_DEADLINE_ACTION : wallClockAction;
    }

    /** Constructor without a wall-clock action (deadline close only). */
    public GraalJsWorkflowScript(String source, Map<String, Object> args, JsSandboxConfig sandbox,
            GraalJsEngineHolder engines, SubagentResolver subagents, CancellationSignal signal) {
        this(source, args, sandbox, engines, subagents, signal, null);
    }

    /** Convenience constructor with default {@link SubagentResolver#inline()} and no cancellation signal. */
    public GraalJsWorkflowScript(String source, Map<String, Object> args, JsSandboxConfig sandbox,
            GraalJsEngineHolder engines) {
        this(source, args, sandbox, engines, SubagentResolver.inline(), null);
    }

    @Override
    public String run(WorkflowContext ctx) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        final RunFatalCapture capture = new RunFatalCapture();
        final Context js = JsContextFactory.create(engines.engine(), sandbox);
        final CancellationWatchdog watchdog = engines.watchdog(js, signal, sandbox.wallClockTimeout(), wallClockAction);
        try {
            WorkflowBindings.install(js, ctx, args, subagents, capture, sandbox.allowConsole());

            final String prelude = sandbox.determinismMode().preludeSource();
            if (!prelude.isEmpty()) {
                js.eval(Source.create("js", prelude));
            }

            final Value promise = js.eval(Source.create("js", "(async () => {\n" + source + "\n})()"));
            final Value settled = JsResultMarshaller.settle(promise);
            final String result = JsResultMarshaller.detach(settled);

            throwIfRunFatal(capture); // run-fatal wins over any guest try/catch (C41)
            return result;
        } catch (WorkflowException e) {
            throw e; // run-fatal control signal propagates unchanged to the runner
        } catch (JsScriptException e) {
            throwIfRunFatal(capture);
            throw e;
        } catch (PolyglotException e) {
            throwIfRunFatal(capture);
            // Resource exhaustion also reports isCancelled(), so it must be classified first: a statement-limit
            // overrun is a script defect the LLM can fix, not a cancellation.
            if (e.isResourceExhausted()) {
                throw new JsScriptException("GraalJS statement limit exceeded — the script ran too much pure-JS "
                        + "computation without yielding: " + e.getMessage(), e);
            }
            if (e.isCancelled()) {
                throw new JsScriptCancelledException("GraalJS workflow cancelled: " + e.getMessage(), e);
            }
            throw new JsScriptException("GraalJS workflow error: " + e.getMessage(), e);
        } finally {
            watchdog.cancel();
            closeQuietly(js);
        }
    }

    private static void throwIfRunFatal(RunFatalCapture capture) {
        final WorkflowException fatal = capture.get();
        if (fatal != null) {
            throw fatal;
        }
    }

    private static void closeQuietly(Context js) {
        try {
            js.close(true);
        } catch (RuntimeException ignored) {
            // already closed by the watchdog, or closing mid-cancel — the run outcome is already decided.
        }
    }
}
