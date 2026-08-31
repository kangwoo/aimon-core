package at.aimon.core.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable section of status information containing a title and a list of {@link StatusEntry} items.
 *
 * <p>
 * Represents a logical grouping of related status entries, such as "Application" or "Components".
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * StatusSection section = StatusSection.builder("Application").entry("Version", "0.0.12").entry("Java", "17.0.1")
 *         .build();
 * }
 * </pre>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @see StatusEntry
 * @see SystemStatus
 */
public final class StatusSection {

    /**
     * Creates a new builder with the specified section title.
     *
     * @param title
     *            The section title (must not be null)
     * @return A new builder instance (never null)
     * @throws NullPointerException
     *             if title is null
     */
    public static Builder builder(String title) {
        return new Builder(title);
    }

    private final String title;
    private final List<StatusEntry> entries;

    private StatusSection(Builder builder) {
        this.title = builder.title;
        this.entries = builder.entries.isEmpty() ? List.of() : List.copyOf(builder.entries);
    }

    /**
     * Gets the section title.
     *
     * @return The section title (never null)
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the status entries in this section.
     *
     * @return An unmodifiable list of entries (never null, may be empty)
     */
    public List<StatusEntry> getEntries() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StatusSection that = (StatusSection) o;
        return Objects.equals(title, that.title) && Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, entries);
    }

    @Override
    public String toString() {
        return "StatusSection{title='" + title + "', entries=" + entries + '}';
    }

    /** Builder for StatusSection. */
    public static final class Builder {
        private final String title;
        private final List<StatusEntry> entries = new ArrayList<>();

        private Builder(String title) {
            this.title = Objects.requireNonNull(title, "Title cannot be null");
        }

        /**
         * Adds a status entry to this section.
         *
         * @param name
         *            The entry name (must not be null)
         * @param value
         *            The entry value (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if name or value is null
         */
        public Builder entry(String name, String value) {
            entries.add(StatusEntry.of(name, value));
            return this;
        }

        /**
         * Adds a status entry with an integer value to this section.
         *
         * @param name
         *            The entry name (must not be null)
         * @param value
         *            The entry value
         * @return This builder
         * @throws NullPointerException
         *             if name is null
         */
        public Builder entry(String name, int value) {
            entries.add(StatusEntry.of(name, String.valueOf(value)));
            return this;
        }

        /**
         * Adds a status entry with a long value to this section.
         *
         * @param name
         *            The entry name (must not be null)
         * @param value
         *            The entry value
         * @return This builder
         * @throws NullPointerException
         *             if name is null
         */
        public Builder entry(String name, long value) {
            entries.add(StatusEntry.of(name, String.valueOf(value)));
            return this;
        }

        /**
         * Builds the StatusSection.
         *
         * @return A new StatusSection instance (never null)
         */
        public StatusSection build() {
            return new StatusSection(this);
        }
    }
}
