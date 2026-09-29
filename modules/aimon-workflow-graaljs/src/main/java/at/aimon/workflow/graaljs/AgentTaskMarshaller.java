package at.aimon.workflow.graaljs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.graalvm.polyglot.Value;

import at.aimon.core.base.DefinitionAttributes;
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
 * tools?, maxIterations?, attributes?}}. The identity fields go to the {@link SubagentResolver} as one
 * {@link SubagentDescriptor}; {@code attributes} is read the way an {@code attributes} block of a definition file is
 * ({@link DefinitionAttributes#fromFrontmatter(Object)}), so {@code {sandbox: {slot: 'build'}}} and
 * {@code {'sandbox.slot': 'build'}} are the same attribute and numbers or booleans become their text.
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

        final Subagent subagent = resolver.resolve(SubagentDescriptor.builder().agentType(string(opts, "agentType"))
                .systemPrompt(string(opts, "systemPrompt")).model(string(opts, "model"))
                .tools(stringList(opts, "tools")).maxIterations(integer(opts, "maxIterations"))
                .attributes(attributes(opts)).build());

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

    /**
     * Reads {@code attributes}: absent or null means none; anything but an object, or an entry a definition file could
     * not hold either, is a loud {@link JsScriptException} naming the key.
     */
    private static Map<String, String> attributes(Value opts) {
        final Value v = member(opts, "attributes");
        if (v == null || v.isNull()) {
            return Map.of();
        }
        if (v.hasArrayElements() || !v.hasMembers() || v.canExecute()) {
            throw new JsScriptException("'attributes' must be an object, got: " + v);
        }
        try {
            return DefinitionAttributes.fromFrontmatter(JsMarshalling.deepDetach(v));
        } catch (IllegalArgumentException e) {
            throw new JsScriptException(e.getMessage(), e);
        }
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
