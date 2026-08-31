package at.aimon.core.skill.policy.pending;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object identifying a {@link PendingTurn}.
 *
 * <p>
 * Generated when a turn is suspended awaiting user approval; the id is shown to the user (e.g., in {@code /pending}
 * output) so they can target a specific suspended turn with {@code /approve <id>} or {@code /deny <id>}.
 *
 * <p>
 * Immutable; safe to use as a map key.
 */
public final class PendingTurnId {

    private final String value;

    /**
     * Creates a new pending turn id with validation.
     *
     * @param value
     *            the identifier value (must not be null or blank)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public PendingTurnId(String value) {
        Objects.requireNonNull(value, "PendingTurnId value cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PendingTurnId value cannot be blank");
        }
        this.value = value;
    }

    /**
     * Wraps an existing string identifier.
     */
    public static PendingTurnId of(String value) {
        return new PendingTurnId(value);
    }

    /**
     * Generates a new random pending turn id (UUID-based).
     */
    public static PendingTurnId generate() {
        return new PendingTurnId(UUID.randomUUID().toString());
    }

    /**
     * Returns the raw identifier value.
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
        PendingTurnId that = (PendingTurnId) o;
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
