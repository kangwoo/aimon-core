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

    /**
     * Reads a numeric field that a payload written by an <em>older</em> node may not carry at all, treating its
     * absence as zero.
     *
     * <p>
     * Deliberately narrow: {@link #asInt(Object)} stays the accessor for every field whose absence really is a
     * malformed payload, and only a field added after a released wire format uses this one. During a rolling upgrade
     * a new node decodes a map an old node wrote; without this, the new field's absence would raise
     * {@link NullPointerException} and the whole signal would be dropped rather than one counter reading zero. The
     * asymmetry with {@code SessionRecordCodec} — whose {@code node.path(...).asInt()} already defaults to 0 — is
     * real: one wire is tolerant by construction and this one is not.
     *
     * @param value
     *            the raw payload value (may be null)
     * @return the value as an int, or 0 when absent
     */
    static int asIntOrZero(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
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
