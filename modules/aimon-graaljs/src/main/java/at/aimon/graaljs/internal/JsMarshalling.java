package at.aimon.graaljs.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.graaljs.exception.JsScriptException;

/**
 * Owner-thread marshalling between GraalJS {@code Value}s and plain Java. Two directions, both invariant-critical:
 *
 * <ul>
 * <li><b>{@link #deepDetach(Value)}</b> — guest → plain Java, <b>recursively</b>. {@code Value.as(Map.class)} returns
 * a <em>shallow live view</em> whose nested nodes stay Context-bound; a fan-out worker touching one violates GraalJS
 * single-thread access. This walk copies every node into {@code String}/{@code Long}/{@code Double}/
 * {@code Boolean}/{@code LinkedHashMap}/{@code ArrayList} so the result holds zero polyglot references.
 * <li><b>{@link #toGuest(Object)}</b> — plain Java → guest, recursively wrapping {@code Map}→{@code ProxyObject} and
 * {@code List}→{@code ProxyArray}. Under {@code HostAccess.NONE} a raw host {@code Map}/{@code List} is inaccessible
 * to guest code, so results must be proxy-wrapped (scalars pass through as polyglot primitives).
 * </ul>
 */
public final class JsMarshalling {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Nesting ceiling for both marshalling directions. Real workflow data (goals, schemas, structured results)
     * is JSON-shaped and shallow; anything deeper is a cyclic guest object or a pathological structure, either of
     * which would otherwise recurse into a {@code StackOverflowError} — an {@code Error} no layer of the tool chain
     * catches. The guard turns it into a loud, catchable {@link JsScriptException}.
     */
    private static final int MAX_DEPTH = 128;

    private JsMarshalling() {
    }

    /**
     * The default budget of one detach: 1 MiB of copied data and 100,000 nodes. A node is any value visited, so a guest
     * object graph that shares references (a DAG whose unfolding is exponential) runs out of nodes rather than heap.
     */
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024;

    /** The default node budget of one detach. */
    public static final long DEFAULT_MAX_NODES = 100_000L;

    /**
     * Recursively copies a guest {@code Value} into plain Java (zero polyglot references), within the default budget.
     * Executable values and host objects are not expected here (schemas/results are data) and fall back to their string
     * form. Structures nested deeper than {@link #MAX_DEPTH} (including cyclic ones), or larger than the budget, raise
     * a {@link JsScriptException}.
     *
     * @param value
     *            the guest value (may be null)
     * @return the plain-Java copy (may be null)
     */
    public static Object deepDetach(Value value) {
        return deepDetach(value, DEFAULT_MAX_BYTES, DEFAULT_MAX_NODES);
    }

    /**
     * Like {@link #deepDetach(Value)}, within an explicit budget ({@code docs/design/tool/programmatic-tool-calling.md}
     * §10.2). The walk carries the budget depth first and checks an array's size against it <em>before</em> allocating
     * anything for it, so {@code new Array(2**31-1)} is refused without a host allocation.
     *
     * @param value
     *            the guest value (may be null)
     * @param maxBytes
     *            the most bytes of strings and keys the copy may hold (an estimate: two bytes per char, eight per
     *            scalar)
     * @param maxNodes
     *            the most values the walk may visit
     * @return the plain-Java copy (may be null)
     * @throws JsScriptException
     *             if the value is deeper than {@link #MAX_DEPTH} or exceeds the budget
     */
    public static Object deepDetach(Value value, long maxBytes, long maxNodes) {
        return deepDetach(value, 0, new Budget(maxBytes, maxNodes));
    }

    /** What a detach may still copy. Single-threaded: one walk on the owner thread. */
    private static final class Budget {
        private final long maxBytes;
        private long bytes;
        private long nodes;

        Budget(long maxBytes, long maxNodes) {
            this.maxBytes = maxBytes;
            this.bytes = maxBytes;
            this.nodes = maxNodes;
        }

        void node() {
            if (--nodes < 0) {
                throw new JsScriptException("value has too many elements to cross the JS/Java boundary");
            }
        }

        void bytes(long count) {
            bytes -= count;
            if (bytes < 0) {
                throw new JsScriptException(
                        "value is larger than the limit of " + maxBytes + " bytes that may cross the JS/Java boundary");
            }
        }
    }

    private static Object deepDetach(Value value, int depth, Budget budget) {
        if (depth > MAX_DEPTH) {
            throw new JsScriptException("value exceeds the maximum nesting depth of " + MAX_DEPTH
                    + " — cyclic structures cannot cross the JS/Java boundary");
        }
        budget.node();
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            budget.bytes(8);
            return value.asBoolean();
        }
        if (value.isString()) {
            final String string = value.asString();
            budget.bytes(2L * string.length());
            return string;
        }
        if (value.isNumber()) {
            budget.bytes(8);
            if (value.fitsInLong()) {
                return value.asLong();
            }
            // JS has only doubles; fold integral doubles (e.g. 6*7 -> 42.0) back to long for clean output.
            final double number = value.asDouble();
            if (number == Math.rint(number) && !Double.isInfinite(number) && number >= Long.MIN_VALUE
                    && number <= Long.MAX_VALUE) {
                return (long) number;
            }
            return number;
        }
        if (value.hasArrayElements()) {
            final long size = value.getArraySize();
            // Checked before the list is allocated: each element costs at least one node and eight bytes.
            budget.bytes(8L * size);
            if (size > Integer.MAX_VALUE - 8) {
                throw new JsScriptException("array is too large to cross the JS/Java boundary");
            }
            final List<Object> list = new ArrayList<>((int) size);
            for (long i = 0; i < size; i++) {
                list.add(deepDetach(value.getArrayElement(i), depth + 1, budget));
            }
            return list;
        }
        if (value.hasMembers() && !value.canExecute()) {
            final Map<String, Object> map = new LinkedHashMap<>();
            for (final String key : value.getMemberKeys()) {
                budget.bytes(2L * key.length());
                map.put(key, deepDetach(value.getMember(key), depth + 1, budget));
            }
            return map;
        }
        final String string = value.toString();
        budget.bytes(2L * string.length());
        return string;
    }

    /**
     * Recursively wraps plain Java for guest consumption: {@code Map}→{@code ProxyObject}, {@code List}→
     * {@code ProxyArray}, scalars ({@code String}/{@code Number}/{@code Boolean}/{@code null}) unchanged.
     */
    public static Object toGuest(Object javaValue) {
        return toGuest(javaValue, 0);
    }

    private static Object toGuest(Object javaValue, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsScriptException("value exceeds the maximum nesting depth of " + MAX_DEPTH
                    + " — cyclic structures cannot cross the JS/Java boundary");
        }
        if (javaValue == null) {
            return null;
        }
        if (javaValue instanceof Map<?, ?> map) {
            final Map<String, Object> guest = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                guest.put(String.valueOf(entry.getKey()), toGuest(entry.getValue(), depth + 1));
            }
            return ProxyObject.fromMap(guest);
        }
        if (javaValue instanceof List<?> list) {
            final List<Object> guest = new ArrayList<>(list.size());
            for (final Object element : list) {
                guest.add(toGuest(element, depth + 1));
            }
            return ProxyArray.fromList(guest);
        }
        return javaValue;
    }

    /**
     * Like {@link #toGuest(Object)} but every nested {@code Map}/{@code List} node is wrapped <b>read-only</b>: any
     * guest mutation attempt ({@code putMember}/{@code removeMember}/array element {@code set}) raises a loud
     * {@link JsScriptException} at every depth, not just the top level. Used for the {@code args} binding, whose
     * contract is deep immutability.
     */
    public static Object toReadOnlyGuest(Object javaValue) {
        return toReadOnlyGuest(javaValue, 0);
    }

    private static Object toReadOnlyGuest(Object javaValue, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsScriptException("value exceeds the maximum nesting depth of " + MAX_DEPTH
                    + " — cyclic structures cannot cross the JS/Java boundary");
        }
        if (javaValue instanceof Map<?, ?> map) {
            final Map<String, Object> guest = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                guest.put(String.valueOf(entry.getKey()), toReadOnlyGuest(entry.getValue(), depth + 1));
            }
            return readOnlyObject(guest);
        }
        if (javaValue instanceof List<?> list) {
            final List<Object> guest = new ArrayList<>(list.size());
            for (final Object element : list) {
                guest.add(toReadOnlyGuest(element, depth + 1));
            }
            return readOnlyArray(guest);
        }
        return javaValue;
    }

    private static ProxyObject readOnlyObject(Map<String, Object> members) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return members.get(key);
            }

            @Override
            public Object getMemberKeys() {
                // A proxy array: under HostAccess.NONE a host String[] is not readable as an array by the guest.
                return ProxyArray.fromArray(members.keySet().toArray());
            }

            @Override
            public boolean hasMember(String key) {
                return members.containsKey(key);
            }

            @Override
            public void putMember(String key, Value value) {
                throw new JsScriptException("args is read-only");
            }

            @Override
            public boolean removeMember(String key) {
                throw new JsScriptException("args is read-only");
            }
        };
    }

    private static ProxyArray readOnlyArray(List<Object> elements) {
        return new ProxyArray() {
            @Override
            public Object get(long index) {
                if (index < 0 || index >= elements.size()) {
                    return null;
                }
                return elements.get((int) index);
            }

            @Override
            public long getSize() {
                return elements.size();
            }

            @Override
            public void set(long index, Value value) {
                throw new JsScriptException("args is read-only");
            }
        };
    }

    /**
     * Serializes a detached (plain-Java) value to a compact JSON string. Used only for {@code Map}/{@code List}
     * return payloads that cross the {@code WorkflowScript<String>} boundary.
     */
    public static String toJson(Object detached) {
        try {
            return JSON.writeValueAsString(detached);
        } catch (JsonProcessingException e) {
            throw new JsScriptException("Failed to serialize workflow result to JSON: " + e.getMessage(), e);
        }
    }
}
