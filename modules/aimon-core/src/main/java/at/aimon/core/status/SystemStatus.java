package at.aimon.core.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of the overall system status, composed of multiple {@link StatusSection}s.
 *
 * <p>
 * This is the top-level status model returned by {@link SystemStatusProvider}. It aggregates sections of status
 * information that can be rendered by commands or queried programmatically.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * SystemStatus status = SystemStatus.builder()
 *         .section(StatusSection.builder("Application").entry("Version", "0.0.12").build())
 *         .section(StatusSection.builder("Components").entry("Commands", "5").entry("Tools", "8").build()).build();
 * }
 * </pre>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @see StatusSection
 * @see SystemStatusProvider
 */
public final class SystemStatus {

    /**
     * Creates a new builder.
     *
     * @return A new builder instance (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<StatusSection> sections;

    private SystemStatus(Builder builder) {
        this.sections = builder.sections.isEmpty() ? List.of() : List.copyOf(builder.sections);
    }

    /**
     * Gets the status sections.
     *
     * @return An unmodifiable list of sections (never null, may be empty)
     */
    public List<StatusSection> getSections() {
        return sections;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SystemStatus that = (SystemStatus) o;
        return Objects.equals(sections, that.sections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sections);
    }

    @Override
    public String toString() {
        return "SystemStatus{sections=" + sections + '}';
    }

    /** Builder for SystemStatus. */
    public static final class Builder {
        private final List<StatusSection> sections = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds a status section.
         *
         * @param section
         *            The section to add (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if section is null
         */
        public Builder section(StatusSection section) {
            sections.add(Objects.requireNonNull(section, "Section cannot be null"));
            return this;
        }

        /**
         * Builds the SystemStatus.
         *
         * @return A new SystemStatus instance (never null)
         */
        public SystemStatus build() {
            return new SystemStatus(this);
        }
    }
}
