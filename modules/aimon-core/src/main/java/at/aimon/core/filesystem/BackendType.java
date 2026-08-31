package at.aimon.core.filesystem;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Value object representing a storage backend type. Provides type-safe backend identification while allowing
 * extensibility for custom backend implementations.
 *
 * <p>
 * Immutable and thread-safe. Pre-defined constants exist for built-in backends, but custom types can be created using
 * {@link #of(String)}. The {@code of} method returns cached instances to ensure that equivalent type strings always
 * return the same BackendType instance (enabling identity comparison with ==).
 *
 * @see #LOCAL
 * @see #GRIDFS
 * @see #S3
 */
public final class BackendType {
    /**
     * Cache for BackendType instances. Ensures that the same type string always returns the same instance, allowing
     * identity comparison (==) in addition to equals().
     */
    private static final Map<String, BackendType> CACHE = new ConcurrentHashMap<>();

    /** Local filesystem backend. */
    public static final BackendType LOCAL = of("LOCAL");

    /** MongoDB GridFS backend. */
    public static final BackendType GRIDFS = of("GRIDFS");

    /** AWS S3 backend. */
    public static final BackendType S3 = of("S3");

    /**
     * Creates a BackendType from a string identifier. Use this method for custom backend types not covered by
     * predefined constants. Type strings are normalized to uppercase for case-insensitive comparison. Returns cached
     * instances to ensure the same type string always returns the same object.
     *
     * @param type
     *            The backend type identifier (case-insensitive)
     * @return A BackendType instance (cached)
     * @throws NullPointerException
     *             if type is null
     * @throws IllegalArgumentException
     *             if type is empty
     */
    public static BackendType of(String type) {
        Objects.requireNonNull(type, "Backend type cannot be null");
        final String normalizedType = type.trim().toUpperCase();

        if (normalizedType.isEmpty()) {
            throw new IllegalArgumentException("Backend type cannot be empty");
        }

        return CACHE.computeIfAbsent(normalizedType, BackendType::new);
    }

    private final String type;

    /**
     * Private constructor. Use {@link #of(String)} to create instances. The type parameter is assumed to be already
     * validated and normalized by the of() method.
     *
     * @param type
     *            The backend type identifier (already normalized)
     */
    private BackendType(String type) {
        this.type = type;
    }

    /**
     * Returns the string identifier for this backend type.
     *
     * @return Backend type string
     */
    public String getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BackendType that = (BackendType) o;
        return type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type);
    }

    @Override
    public String toString() {
        return type;
    }
}
