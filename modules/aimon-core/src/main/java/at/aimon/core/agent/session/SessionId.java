package at.aimon.core.agent.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a session identifier.
 *
 * <p>
 * Encapsulates validation logic and ensures type safety for session ids. Instances are immutable and can be safely
 * used as map keys.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SessionId id = new SessionId("user-session-123");
 *     repository.provision(id);
 *
 *     // Validation happens at construction time
 *     new SessionId(""); // throws IllegalArgumentException
 *     new SessionId(null); // throws NullPointerException
 * }
 * </pre>
 */
public final class SessionId {

    private final String value;

    /**
     * Creates a new session id with validation.
     *
     * @param value
     *            the session identifier value (must not be null or blank)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public SessionId(String value) {
        Objects.requireNonNull(value, "Session id cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Session id cannot be blank");
        }
        this.value = value;
    }

    /**
     * Creates a new SessionId.
     *
     * @param value
     *            The session id value
     * @return A new SessionId with the given value
     */
    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /**
     * Generates a new random SessionId.
     *
     * @return A new SessionId with a random UUID value
     */
    public static SessionId generate() {
        return new SessionId(UUID.randomUUID().toString());
    }

    /**
     * Returns the session id value.
     *
     * @return the identifier value (never null or blank)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionId that = (SessionId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
