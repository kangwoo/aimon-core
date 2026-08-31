package at.aimon.workflow.graaljs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.WorkflowContext;
import at.aimon.core.workflow.exception.WorkflowException;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Installs the proxy-only capability bindings ({@code agent}/{@code parallel}/{@code pipeline}/{@code phase}/
 * {@code log} + read-only {@code args}) that a GraalJS workflow script calls. This is where marshal-before-fan-out
 * happens: fan-out inputs are deep-detached into immutable {@link AgentTask}s on the owner thread,
 * then handed to {@code ctx.parallel} as pure-Java {@code Supplier}s — a worker thread never touches a JS
 * {@code Value}.
 *
 * <p>
 * All bindings are {@link ProxyExecutable}/{@link ProxyObject}s, so under {@code HostAccess.NONE} the guest's total
 * authority is exactly these capabilities — no host reflection surface.
 */
final class WorkflowBindings {

    private WorkflowBindings() {
    }

    /**
     * Binds the capability proxies into the {@code "js"} global scope. Must be called on the owner thread before the
     * author source is evaluated.
     */
    static void install(Context js, WorkflowContext ctx, Map<String, Object> args, SubagentResolver resolver,
            RunFatalCapture capture, boolean allowConsole) {
        Objects.requireNonNull(js, "js context cannot be null");
        Objects.requireNonNull(ctx, "workflow context cannot be null");
        Objects.requireNonNull(resolver, "resolver cannot be null");
        Objects.requireNonNull(capture, "capture cannot be null");

        final Value bindings = js.getBindings("js");
        bindings.putMember("agent", (ProxyExecutable) a -> agent(ctx, resolver, capture, a));
        bindings.putMember("parallel", (ProxyExecutable) a -> parallel(ctx, resolver, capture, a));
        bindings.putMember("pipeline", (ProxyExecutable) a -> pipeline(ctx, resolver, capture, a));
        bindings.putMember("phase", (ProxyExecutable) a -> {
            ctx.phase(requireString(a, 0, "phase"));
            return null;
        });
        bindings.putMember("log", (ProxyExecutable) a -> {
            ctx.log(requireString(a, 0, "log"));
            return null;
        });
        bindings.putMember("args", readOnlyArgs(args));
        if (allowConsole) {
            bindings.putMember("console", consoleBridge(ctx));
        }
    }

    // ---- agent: synchronous single step (owner thread) ----

    private static Object agent(WorkflowContext ctx, SubagentResolver resolver, RunFatalCapture capture, Value[] a) {
        if (a.length == 0 || a[0] == null || a[0].isNull()) {
            throw new JsScriptException("agent(...) requires a prompt string or a descriptor object");
        }
        final AgentTask task;
        if (a[0].isString()) {
            task = AgentTaskMarshaller.toTask(a[0].asString(), a.length > 1 ? a[1] : null, resolver);
        } else {
            task = AgentTaskMarshaller.toTask(a[0], resolver);
        }
        final AgentStepResult result = runAgent(ctx, capture, task);
        if (task.getResultSchema().isPresent()) {
            return JsMarshalling.toGuest(result.structured().orElse(null));
        }
        return AgentResultView.of(result);
    }

    // ---- parallel: descriptor array → marshal-before-fan-out → ctx.parallel barrier ----

    private static Object parallel(WorkflowContext ctx, SubagentResolver resolver, RunFatalCapture capture, Value[] a) {
        if (a.length == 0 || !a[0].hasArrayElements()) {
            throw new JsScriptException("parallel(...) requires an array of descriptor objects");
        }
        final List<Value> descriptors = asList(a[0]);
        final List<Supplier<AgentStepResult>> suppliers = new ArrayList<>(descriptors.size());
        for (final Value descriptor : descriptors) {
            final AgentTask task = AgentTaskMarshaller.toTask(descriptor, resolver); // deep-detach on owner thread
            suppliers.add(() -> ctx.agent(task)); // pure-Java thunk: immutable task + Java ctx, zero Value refs
        }
        final List<AgentStepResult> results = runParallel(ctx, capture, suppliers);
        return AgentResultView.array(results);
    }

    // ---- pipeline: barrier-per-stage desugar (owner thread stage fns → ctx.parallel per stage) ----

    private static Object pipeline(WorkflowContext ctx, SubagentResolver resolver, RunFatalCapture capture, Value[] a) {
        if (a.length < 2 || !a[0].hasArrayElements()) {
            throw new JsScriptException("pipeline(items, stage1, ...) requires an items array and >=1 stage");
        }
        final List<Value> items = asList(a[0]);
        final List<Value> stages = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (!a[i].canExecute()) {
                throw new JsScriptException("pipeline stage " + i + " must be a function");
            }
            stages.add(a[i]);
        }

        List<Object> previous = new ArrayList<>(items); // stage input: the raw item, then each stage's result view
        List<AgentStepResult> stageResults = List.of();
        for (final Value stage : stages) {
            final List<Supplier<AgentStepResult>> suppliers = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                final Value descriptor = stage.execute(previous.get(i), items.get(i), i); // owner thread
                if (descriptor == null || descriptor.isNull()) {
                    suppliers.add(() -> null); // stage drops this item
                    continue;
                }
                final AgentTask task = AgentTaskMarshaller.toTask(descriptor, resolver);
                suppliers.add(() -> ctx.agent(task));
            }
            stageResults = runParallel(ctx, capture, suppliers);
            previous = new ArrayList<>(stageResults.size());
            for (final AgentStepResult result : stageResults) {
                previous.add(result == null ? null : AgentResultView.of(result));
            }
        }
        return AgentResultView.array(stageResults);
    }

    // ---- run-fatal-aware core invocations ----

    private static AgentStepResult runAgent(WorkflowContext ctx, RunFatalCapture capture, AgentTask task) {
        try {
            return ctx.agent(task);
        } catch (WorkflowException e) {
            capture.record(e);
            throw e;
        }
    }

    private static List<AgentStepResult> runParallel(WorkflowContext ctx, RunFatalCapture capture,
            List<Supplier<AgentStepResult>> suppliers) {
        try {
            return ctx.parallel(suppliers);
        } catch (WorkflowException e) {
            capture.record(e);
            throw e;
        }
    }

    // ---- helpers ----

    private static List<Value> asList(Value array) {
        final long size = array.getArraySize();
        final List<Value> list = new ArrayList<>((int) Math.min(size, Integer.MAX_VALUE));
        for (long i = 0; i < size; i++) {
            list.add(array.getArrayElement(i));
        }
        return list;
    }

    private static String requireString(Value[] a, int index, String name) {
        if (a.length <= index || a[index] == null || a[index].isNull()) {
            throw new JsScriptException(name + "(...) requires a string argument");
        }
        return a[index].isString() ? a[index].asString() : a[index].toString();
    }

    private static ProxyObject readOnlyArgs(Map<String, Object> args) {
        // Deep read-only: every nested Map/List node rejects mutation, not just the top level.
        return (ProxyObject) JsMarshalling.toReadOnlyGuest(args == null ? Map.of() : args);
    }

    private static ProxyObject consoleBridge(WorkflowContext ctx) {
        final ProxyExecutable logFn = a -> {
            final StringBuilder line = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(a[i]);
            }
            ctx.log(line.toString());
            return null;
        };
        return ProxyObject.fromMap(Map.of("log", logFn, "warn", logFn, "error", logFn, "info", logFn));
    }
}
