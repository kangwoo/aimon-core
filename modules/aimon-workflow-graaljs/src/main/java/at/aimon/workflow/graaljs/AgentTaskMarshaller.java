package at.aimon.workflow.graaljs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.graalvm.polyglot.Value;

import at.aimon.core.subagent.Subagent;
import at.aimon.core.workflow.AgentTask;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Marshals a JS agent descriptor ({@code Value}) into an immutable core {@link AgentTask}, entirely on the owner
 * thread (marshal-before-fan-out). Every {@code Value}-derived field is recursively deep-detached via
 * {@link JsMarshalling#deepDetach(Value)} so the resulting {@code AgentTask} holds zero polyglot references and is
 * safe to capture in a pure-Java {@code Supplier} for a fan-out worker.
 *
 * <p>
 * Descriptor shape: {@code {agentType?, systemPrompt?, goal|prompt, schema?, isolation?, label?, phase?, model?,
 * tools?, maxIterations?}}.
 */
final class AgentTaskMarshaller {

    private static final String WORKTREE_ISOLATION = "worktree";

    private AgentTaskMarshaller() {
    }

    /** Object-form: {@code agent({goal, ...})} / {@code parallel([{...}])}. Goal is read from the descriptor. */
    static AgentTask toTask(Value descriptor, SubagentResolver resolver) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        Objects.requireNonNull(resolver, "resolver cannot be null");
        String goal = string(descriptor, "goal");
        if (goal == null) {
            goal = string(descriptor, "prompt");
        }
        return build(goal, descriptor, resolver);
    }

    /** String-form: {@code agent('do the thing', {agentType, schema, ...})}. Opts may be {@code null}. */
    static AgentTask toTask(String goal, Value opts, SubagentResolver resolver) {
        Objects.requireNonNull(resolver, "resolver cannot be null");
        return build(goal, opts, resolver);
    }

    private static AgentTask build(String goal, Value opts, SubagentResolver resolver) {
        if (goal == null || goal.isBlank()) {
            throw new JsScriptException("agent requires a non-empty 'goal' (or 'prompt')");
        }

        final Subagent subagent = resolver.resolve(string(opts, "agentType"), string(opts, "systemPrompt"),
                string(opts, "model"), stringList(opts, "tools"), integer(opts, "maxIterations"));

        final AgentTask.Builder builder = AgentTask.builder().subagent(subagent).goal(goal);

        final String label = string(opts, "label");
        if (label != null) {
            builder.label(label);
        }
        final String phase = string(opts, "phase");
        if (phase != null) {
            builder.phase(phase);
        }
        final Value schema = member(opts, "schema");
        if (schema != null && !schema.isNull()) {
            builder.resultSchema(detachSchema(schema));
        }
        if (WORKTREE_ISOLATION.equals(string(opts, "isolation"))) {
            builder.isolate(true);
        }
        return builder.build();
    }

    private static Map<String, Object> detachSchema(Value schema) {
        final Object detached = JsMarshalling.deepDetach(schema);
        if (!(detached instanceof Map)) {
            throw new JsScriptException("schema must be an object, got: " + schema);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) detached;
        return map;
    }

    // ---- descriptor field readers (null-safe over a possibly-null opts Value) ----

    private static Value member(Value opts, String key) {
        if (opts == null || opts.isNull() || !opts.hasMembers() || !opts.hasMember(key)) {
            return null;
        }
        return opts.getMember(key);
    }

    private static String string(Value opts, String key) {
        final Value v = member(opts, key);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isString() ? v.asString() : v.toString();
    }

    private static Integer integer(Value opts, String key) {
        final Value v = member(opts, key);
        if (v == null || v.isNull() || !v.isNumber() || !v.fitsInInt()) {
            return null;
        }
        return v.asInt();
    }

    private static List<String> stringList(Value opts, String key) {
        final Value v = member(opts, key);
        if (v == null || v.isNull() || !v.hasArrayElements()) {
            return null;
        }
        final long size = v.getArraySize();
        final List<String> list = new ArrayList<>((int) Math.min(size, Integer.MAX_VALUE));
        for (long i = 0; i < size; i++) {
            final Value element = v.getArrayElement(i);
            list.add(element.isString() ? element.asString() : element.toString());
        }
        return list;
    }
}
