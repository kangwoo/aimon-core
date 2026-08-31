package at.aimon.core.base;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Copies a map while dropping entries whose value is {@code null}.
 *
 * <p>
 * <b>The rule this encodes: a JSON {@code null} means "absent".</b> That matches how JSON Schema's {@code required}
 * check is conventionally read, and it matches what models actually do — they routinely fill an optional parameter
 * they have no value for with {@code null} rather than omitting the key.
 *
 * <p>
 * It exists because {@link Map#copyOf(Map)} throws {@link NullPointerException} on such an entry. Two places on the
 * tool-call path used it — {@code ToolUse} (built while converting the LLM response) and {@code ToolInput} — and the
 * first of those runs <em>outside</em> the tool-execution loop, so the throw was not catchable as a tool error: one
 * {@code null} in one optional field failed the whole turn with nothing reported back to the model.
 *
 * <p>
 * The copy is a {@link LinkedHashMap} rather than a {@code Map.of}-style map on purpose: iteration order follows
 * insertion, which keeps {@code keys()} and anything logged from it in the order the caller supplied.
 * {@code Map.copyOf}
 * gives no such guarantee, and a parameter list that reshuffles between runs makes logs harder to diff than they need
 * to be.
 *
 * <p>
 * <b>Not the only {@code null} policy in this codebase.</b> {@code at.aimon.core.workflow.impl.StructuredOutputSupport}
 * validates structured <em>output</em> and treats a {@code null} value as present-but-wrongly-typed — the opposite
 * reading. The two are deliberately separate (different direction, different consumer); see that class and the
 * tool-contract-hardening design §8 before trying to unify them.
 */
public final class NullSafeMaps {

    private NullSafeMaps() {
        throw new UnsupportedOperationException("Utility class must not be instantiated");
    }

    /**
     * Returns an unmodifiable, order-preserving copy of {@code source} with every {@code null}-valued entry removed.
     *
     * @param source
     *            the map to copy (must not be null; its <em>values</em> may be)
     * @return an unmodifiable copy containing only the non-null entries (never null)
     * @throws NullPointerException
     *             if {@code source} itself is null
     */
    public static <V> Map<String, V> withoutNullValues(Map<String, V> source) {
        Objects.requireNonNull(source, "Input data cannot be null");
        final Map<String, V> filtered = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                filtered.put(key, value);
            }
        });
        return Collections.unmodifiableMap(filtered);
    }
}
