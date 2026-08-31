package at.aimon.core.status;

import java.util.Objects;

/**
 * Immutable name-value pair representing a single status item.
 *
 * <p>
 * Used as a building block within {@link StatusSection} to represent individual status information such as version
 * numbers, counts, or connection states.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * StatusEntry entry = StatusEntry.of("Version", "0.0.12");
 * String name = entry.getName();   // "Version"
 * String value = entry.getValue(); // "0.0.12"
 * }
 * </pre>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @see StatusSection
 * @see SystemStatus
 */
public final class StatusEntry {

    /**
     * Creates a new StatusEntry.
     *
     * @param name
     *            The entry name (must not be null)
     * @param value
     *            The entry value (must not be null)
     * @return A new StatusEntry instance (never null)
     * @throws NullPointerException
     *             if name or value is null
     */
    public static StatusEntry of(String name, String value) {
        return new StatusEntry(name, value);
    }

    private final String name;
    private final String value;

    private StatusEntry(String name, String value) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.value = Objects.requireNonNull(value, "Value cannot be null");
    }

    /**
     * Gets the entry name.
     *
     * @return The entry name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the entry value.
     *
     * @return The entry value (never null)
     */
    public String getValue() {
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
        final StatusEntry that = (StatusEntry) o;
        return Objects.equals(name, that.name) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public String toString() {
        return "StatusEntry{name='" + name + "', value='" + value + "'}";
    }
}
