package at.aimon.graaljs.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.graaljs.GraalJsEngineHolder;
import at.aimon.graaljs.exception.JsScriptException;

/**
 * One program's run in a GraalJS context — the event loop of the {@code ExecuteCode} JavaScript engine
 * ({@code docs/design/tool/programmatic-tool-calling.md} §10.2).
 *
 * <p>
 * Everything that touches the context happens on the calling (owner) thread. A tool call becomes a JavaScript promise
 * whose arguments were copied out of the guest within a budget; the bridge completes it on a host thread, and that
 * completion is queued for the owner thread, which resolves or rejects the promise. The owner waits on the queue with
 * {@code poll(timeout)} and looks at the cancellation signal and the deadline between polls — closing the context from
 * the watchdog alone could not wake an owner blocked on the queue.
 *
 * <p>
 * The program is stopped — by the watchdog closing the context, through a local stop signal — when the execution is
 * cancelled, when the wall clock runs out, when its output passes the limit, or when the results it took in pass the
 * limit. Whatever stopped it decides the status. A returned value is rendered and cut inside the guest before it is
 * copied out, so a huge string is never copied to the host whole.
 *
 * <p>
 * Single use. Not thread-safe; the owner thread drives it from {@link #run()}.
 */
public final class JsProgramRun {

    /** How long the event loop waits on its queue before it looks at the stop signal again. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private static final String PRELUDE = """
            (function (out, err, maxReturn) {
              const fmt = v => {
                if (typeof v === 'string') { return v; }
                try { const s = JSON.stringify(v); return s === undefined ? String(v) : s; }
                catch (e) { return String(v); }
              };
              const line = a => a.map(fmt).join(' ');
              globalThis.console = Object.freeze({
                log: (...a) => out(line(a)), info: (...a) => out(line(a)), debug: (...a) => out(line(a)),
                error: (...a) => err(line(a)), warn: (...a) => err(line(a))
              });
              class ToolError extends Error { constructor(m) { super(m); this.name = 'ToolError'; } }
              globalThis.ToolError = ToolError;
              return Object.freeze({
                promise: start => new Promise((resolve, reject) => start(resolve, reject)),
                toolError: m => new ToolError(m),
                render: v => v === undefined ? undefined : fmt(v).slice(0, maxReturn + 1),
                describe: e => (e instanceof Error) ? (e.name + ': ' + e.message) : fmt(e)
              });
            })
            """;

    private final GraalJsEngineHolder holder;
    private final CodeExecutionRequest request;
    private final ToolCallBridge bridge;
    private final CodeExecutionLimits limits;

    private final BlockingQueue<Runnable> completions = new LinkedBlockingQueue<>();
    private final DefaultInterruptCoordinator stopper = new DefaultInterruptCoordinator();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicReference<String> limitReason = new AtomicReference<>();
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder logs = new StringBuilder();
    private long outputChars;
    private long resultChars;
    private int pending;

    private Value helpers;
    private boolean settled;
    private boolean rejected;
    private Value settledValue;

    /**
     * @param holder
     *            the shared engine holder (must not be null)
     * @param request
     *            the program (must not be null)
     * @param bridge
     *            the program's way to call tools (must not be null)
     */
    public JsProgramRun(GraalJsEngineHolder holder, CodeExecutionRequest request, ToolCallBridge bridge) {
        this.holder = Objects.requireNonNull(holder, "holder cannot be null");
        this.request = Objects.requireNonNull(request, "request cannot be null");
        this.bridge = Objects.requireNonNull(bridge, "bridge cannot be null");
        this.limits = request.getLimits();
    }

    /**
     * Runs the program to its end, or until something stops it.
     *
     * @return the outcome (never null)
     */
    public CodeExecutionOutcome run() {
        final CancellationSignal cancellation = request.getCancellation();
        final CancellationSignal.Registration forward = cancellation
                .onCancel(() -> stopper.requestInterrupt(InterruptReason.PARENT_CANCELLED));
        final Context js = JsContextFactory.create(holder.engine(), limits.getMaxStatements());
        final CancellationWatchdog watchdog = holder.watchdog(js, stopper.getSignal(), request.getWallClock(),
                () -> timedOut.set(true));
        try {
            install(js);
            final Value promise = js.eval(Source.create("js", "(async () => {\n" + request.getCode() + "\n})()"));
            promise.invokeMember("then", (ProxyExecutable) a -> settle(false, a),
                    (ProxyExecutable) a -> settle(true, a));
            loop();
            return outcome(js);
        } catch (PolyglotException e) {
            return failure(e);
        } catch (JsScriptException e) {
            return status(CodeExecutionStatus.SCRIPT_ERROR, e.getMessage());
        } catch (IllegalStateException e) {
            // The watchdog closed the context while the owner was about to use it: something stopped the program.
            return stoppedOr(CodeExecutionStatus.SCRIPT_ERROR, e.getMessage());
        } finally {
            watchdog.cancel();
            forward.remove();
            stopper.close();
            try {
                js.close(true);
            } catch (RuntimeException ignored) {
                // already closed by the watchdog — the outcome is decided.
            }
        }
    }

    private void install(Context js) {
        helpers = js.eval(Source.create("js", PRELUDE)).execute((ProxyExecutable) a -> {
            write(stdout, a);
            return null;
        }, (ProxyExecutable) a -> {
            write(logs, a);
            return null;
        }, limits.getMaxObservationChars());
        final Map<String, Object> members = new LinkedHashMap<>();
        for (ToolDefinition tool : request.getTools()) {
            members.put(tool.getName(), (ProxyExecutable) a -> call(tool.getName(), a));
        }
        js.getBindings("js").putMember("tools", new ToolsObject(members));
    }

    private Object call(String toolName, Value[] arguments) {
        return helpers.getMember("promise").execute((ProxyExecutable) callbacks -> {
            final Value resolve = callbacks[0];
            final Value reject = callbacks[1];
            final Map<String, Object> input;
            try {
                input = argumentsOf(arguments);
            } catch (JsScriptException e) {
                reject.execute(helpers.getMember("toolError").execute(e.getMessage()));
                return null;
            }
            final CompletableFuture<ToolDispatchResult> future;
            try {
                future = bridge.callAsync(toolName, input);
            } catch (RuntimeException e) {
                // The bridge contract is a failed future, but a bridge that throws instead must not leave a call
                // counted as in flight: the "never settles" check reads that count.
                reject.execute(helpers.getMember("toolError").execute("tool call failed: " + e.getMessage()));
                return null;
            }
            if (future == null) {
                reject.execute(helpers.getMember("toolError").execute("tool call failed: no result"));
                return null;
            }
            // Counted only once the call is really in flight; its completion is queued for this thread, so the
            // decrement in complete() cannot run before this increment.
            pending++;
            future.whenComplete((result, failure) -> completions.add(() -> complete(resolve, reject, result, failure)));
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> argumentsOf(Value[] arguments) {
        if (arguments.length == 0 || arguments[0] == null || arguments[0].isNull()) {
            return Map.of();
        }
        final Object detached = JsMarshalling.deepDetach(arguments[0], limits.getMaxArgumentBytes(),
                JsMarshalling.DEFAULT_MAX_NODES);
        if (!(detached instanceof Map<?, ?> map)) {
            throw new JsScriptException("tool arguments must be an object, got " + arguments[0]);
        }
        return (Map<String, Object>) map;
    }

    /** Owner thread: hands one completed call back to the program. */
    private void complete(Value resolve, Value reject, ToolDispatchResult result, Throwable failure) {
        pending--;
        if (failure != null || result == null) {
            reject.execute(helpers.getMember("toolError")
                    .execute("tool call failed: " + (failure == null ? "no result" : failure.getMessage())));
            return;
        }
        String text = result.getText();
        resultChars += text.length();
        if (resultChars > limits.getMaxResultBytes()) {
            stop("the program took in more than " + limits.getMaxResultBytes() + " bytes of tool results");
            return;
        }
        final int max = limits.getMaxArgumentBytes();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n[truncated " + (text.length() - max) + " chars]";
        }
        if (result.isError()) {
            reject.execute(helpers.getMember("toolError").execute(text));
        } else {
            resolve.execute(text);
        }
    }

    private void loop() {
        while (!settled) {
            if (stopped()) {
                return;
            }
            final Runnable completion;
            try {
                completion = completions.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopper.requestInterrupt(InterruptReason.PARENT_CANCELLED);
                return;
            }
            if (completion != null) {
                completion.run();
            } else if (pending == 0 && completions.isEmpty() && !settled && !stopped()) {
                // Nothing is running and nothing will complete: the program awaits a promise that never settles.
                settled = true;
                rejected = true;
                settledValue = null;
            }
        }
    }

    private boolean stopped() {
        return stopper.getSignal().isCancelled() || timedOut.get();
    }

    private Object settle(boolean asRejection, Value[] arguments) {
        settled = true;
        rejected = asRejection;
        settledValue = arguments.length > 0 ? arguments[0] : null;
        return null;
    }

    private CodeExecutionOutcome outcome(Context js) {
        if (!settled || stopped()) {
            return stoppedOr(CodeExecutionStatus.CANCELLED, "the program was stopped");
        }
        if (rejected) {
            final String message = settledValue == null
                    ? "the program awaits a promise that never settles"
                    : helpers.getMember("describe").execute(settledValue).asString();
            return status(CodeExecutionStatus.SCRIPT_ERROR, message);
        }
        final Value rendered = helpers.getMember("render").execute(settledValue);
        final String returnValue = rendered == null || rendered.isNull() ? null : rendered.asString();
        return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).stdout(stdout.toString())
                .logs(logs.toString()).returnValue(returnValue).build();
    }

    private CodeExecutionOutcome failure(PolyglotException e) {
        if (e.isResourceExhausted()) {
            // Resource exhaustion is the statement limit in practice, but GraalJS reports a guest stack overflow the
            // same way, so the message names the limit without claiming it was the one hit.
            return status(CodeExecutionStatus.LIMIT_EXCEEDED, "the program hit a resource limit (statement limit "
                    + limits.getMaxStatements() + "): " + e.getMessage());
        }
        if (e.isCancelled() || stopped()) {
            return stoppedOr(CodeExecutionStatus.CANCELLED, "the program was stopped");
        }
        if (e.isHostException() && e.asHostException() instanceof JsScriptException script) {
            return status(CodeExecutionStatus.SCRIPT_ERROR, script.getMessage());
        }
        return status(CodeExecutionStatus.SCRIPT_ERROR, e.getMessage());
    }

    /** The status of a stopped program, by what stopped it; {@code otherwise} when nothing did. */
    private CodeExecutionOutcome stoppedOr(CodeExecutionStatus otherwise, String message) {
        if (limitReason.get() != null) {
            return status(CodeExecutionStatus.LIMIT_EXCEEDED, limitReason.get());
        }
        if (bridge.limitExceeded().isPresent()) {
            return status(CodeExecutionStatus.LIMIT_EXCEEDED, bridge.limitExceeded().get());
        }
        if (timedOut.get()) {
            return status(CodeExecutionStatus.TIMED_OUT,
                    "the program ran longer than " + request.getWallClock().toSeconds() + "s");
        }
        if (request.getCancellation().isCancelled() || stopper.getSignal().isCancelled()) {
            return status(CodeExecutionStatus.CANCELLED, "the execution was cancelled");
        }
        return status(otherwise, message);
    }

    private CodeExecutionOutcome status(CodeExecutionStatus status, String message) {
        return CodeExecutionOutcome.builder().status(status).stdout(stdout.toString()).logs(logs.toString())
                .errorMessage(message).build();
    }

    private void stop(String reason) {
        limitReason.compareAndSet(null, reason);
        stopper.requestInterrupt(InterruptReason.TASK_CANCELLED);
    }

    /** Owner thread: one console line into a buffer, within the shared output limit. */
    private void write(StringBuilder buffer, Value[] arguments) {
        if (limitReason.get() != null) {
            throw new JsScriptException(limitReason.get());
        }
        final String line = arguments.length == 0 || arguments[0] == null ? "" : arguments[0].asString();
        outputChars += line.length() + 1L;
        if (outputChars > limits.getMaxScriptOutputBytes()) {
            final String reason = "the program wrote more than " + limits.getMaxScriptOutputBytes()
                    + " characters of output";
            stop(reason);
            throw new JsScriptException(reason);
        }
        buffer.append(line).append('\n');
    }

    /** The guest's {@code tools}: one function per callable tool, read-only. */
    private static final class ToolsObject implements ProxyObject {

        private final Map<String, Object> members;

        ToolsObject(Map<String, Object> members) {
            this.members = Map.copyOf(members);
        }

        @Override
        public Object getMember(String key) {
            return members.get(key);
        }

        @Override
        public Object getMemberKeys() {
            // A proxy array: under HostAccess.NONE a host String[] is not readable as an array by the guest.
            return ProxyArray.fromArray(members.keySet().stream().sorted().toArray());
        }

        @Override
        public boolean hasMember(String key) {
            return members.containsKey(key);
        }

        @Override
        public void putMember(String key, Value value) {
            throw new JsScriptException("tools is read-only");
        }

        @Override
        public boolean removeMember(String key) {
            throw new JsScriptException("tools is read-only");
        }
    }
}
