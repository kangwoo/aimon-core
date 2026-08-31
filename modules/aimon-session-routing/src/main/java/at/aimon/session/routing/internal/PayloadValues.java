package at.aimon.session.routing.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tolerant accessors for the flat {@code Map<String, Object>} payloads carried on the cross-node
 * {@link at.aimon.core.agent.session.signal.SessionSignalBus}.
 *
 * <p>
 * A JSON string round-trip through the Redis/Mongo/Postgres signal codecs normalizes numbers: an in-{@code int}-range
 * {@code long} comes back as {@code Integer} and integers may widen to {@code Double}. Reading every numeric field
 * through {@link Number} makes payload decoders survive that normalization regardless of the concrete boxed type that
 * arrives. Each accessor fails fast with {@link NullPointerException}/{@link ClassCastException} on a missing or
 * wrong-typed field so a malformed payload is rejected (decoders translate that into a dropped signal rather than a
 * crash).
 */
final class PayloadValues {

    private PayloadValues() {
    }

    static String asString(Object value) {
        return (String) Objects.requireNonNull(value, "missing string field");
    }

    static boolean asBoolean(Object value) {
        return (Boolean) Objects.requireNonNull(value, "missing boolean field");
    }

    static int asInt(Object value) {
        return ((Number) Objects.requireNonNull(value, "missing numeric field")).intValue();
    }

    static long asLong(Object value) {
        return ((Number) Objects.requireNonNull(value, "missing numeric field")).longValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value, "missing map field");
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return (List<Object>) Objects.requireNonNull(value, "missing list field");
    }
}
