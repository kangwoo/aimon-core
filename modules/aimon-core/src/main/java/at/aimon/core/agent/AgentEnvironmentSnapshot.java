package at.aimon.core.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of the ambient environment an agent executes in, collected once per
 * {@link AgentRuntime}.
 *
 * <p>
 * An {@code AgentEnvironmentSnapshot} captures facts that are stable for the lifetime of the agent's execution
 * context: the working directory, the instant the snapshot was taken, the runtime {@link Environment}, and an optional
 * map of user-defined extensions. Because these values do not change between ReAct turns, they are collected exactly
 * once by an {@link AgentEnvironmentSnapshotProvider} and reused across iterations instead of being re-derived every
 * turn.
 *
 * <p>
 * The snapshot is <b>agent-scoped, not session-scoped</b>: it is keyed by {@link AgentRuntimeId}, so every
 * session and every {@code LiveSession} running against the same agent observes the same instance. Anything that
 * must vary per session belongs on the session, and anything that must vary per turn belongs on the request
 * — not here.
 *
 * <p>
 * Instances are immutable and thread-safe. The {@link #getExtensions() extensions} map is defensively copied at build
 * time so that mutations to the caller's original map cannot affect the built instance.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().workingDirectory("/workspace/project")
 *             .currentDate(Instant.now()).environment(Environment.createDefault())
 *             .extensions(Map.of("gitBranch", "main")).build();
 * }
 * </pre>
 *
 * @see AgentEnvironmentSnapshotProvider
 */
public final class AgentEnvironmentSnapshot {

    private final String workingDirectory;
    private final Instant currentDate;
    private final Environment environment;
    private final Map<String, String> extensions;

    private AgentEnvironmentSnapshot(Builder builder) {
        this.workingDirectory = Objects.requireNonNull(builder.workingDirectory, "workingDirectory must not be null");
        this.currentDate = Objects.requireNonNull(builder.currentDate, "currentDate must not be null");
        this.environment = Objects.requireNonNull(builder.environment, "environment must not be null");
        this.extensions = builder.extensions != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.extensions))
                : Map.of();
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the working directory captured when the snapshot was taken.
     *
     * @return the working directory (never null)
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Gets the instant at which this snapshot was taken.
     *
     * <p>
     * Named {@code currentDate} per the design plan; it is a timestamp, not a calendar date.
     *
     * @return the snapshot timestamp (never null)
     */
    public Instant getCurrentDate() {
        return currentDate;
    }

    /**
     * Gets the runtime environment captured when the snapshot was taken.
     *
     * @return the environment (never null)
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Gets the user-defined extension map.
     *
     * @return an immutable map of extensions (never null, may be empty)
     */
    public Map<String, String> getExtensions() {
        return extensions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AgentEnvironmentSnapshot that = (AgentEnvironmentSnapshot) o;
        return workingDirectory.equals(that.workingDirectory) && currentDate.equals(that.currentDate)
                && environment.equals(that.environment) && extensions.equals(that.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workingDirectory, currentDate, environment, extensions);
    }

    @Override
    public String toString() {
        return "AgentEnvironmentSnapshot{" + "workingDirectory='" + workingDirectory + '\'' + ", currentDate="
                + currentDate + ", environment=" + environment + ", extensions=" + extensions + '}';
    }

    /** Builder for {@link AgentEnvironmentSnapshot}. */
    public static final class Builder {
        private String workingDirectory;
        private Instant currentDate;
        private Environment environment;
        private Map<String, String> extensions;

        private Builder() {
        }

        /**
         * Sets the working directory captured when the snapshot was taken.
         *
         * @param workingDirectory
         *            the working directory (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if {@code workingDirectory} is null
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
            return this;
        }

        /**
         * Sets the snapshot timestamp.
         *
         * @param currentDate
         *            the instant the snapshot was taken (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if {@code currentDate} is null
         */
        public Builder currentDate(Instant currentDate) {
            this.currentDate = Objects.requireNonNull(currentDate, "currentDate must not be null");
            return this;
        }

        /**
         * Sets the runtime environment.
         *
         * @param environment
         *            the environment (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if {@code environment} is null
         */
        public Builder environment(Environment environment) {
            this.environment = Objects.requireNonNull(environment, "environment must not be null");
            return this;
        }

        /**
         * Sets the user-defined extensions map.
         *
         * <p>
         * The map is defensively copied at {@link #build()} time into an insertion-ordered map, so later mutations to
         * the caller's map have no effect on the built instance and downstream consumers see the original iteration
         * order.
         *
         * <p>
         * Neither keys nor values may be {@code null}: the map is copied into a {@link java.util.LinkedHashMap}, which
         * tolerates null values — however, downstream consumers (notably {@code UserContextMessageBuilder} and the
         * {@code <system-reminder>} renderer) skip or reject null entries silently. Callers should pre-filter their
         * input accordingly.
         *
         * @param extensions
         *            the extension map (must not be null; may be empty; keys and values should be non-null)
         * @return this builder
         * @throws NullPointerException
         *             if {@code extensions} is null
         */
        public Builder extensions(Map<String, String> extensions) {
            this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
            return this;
        }

        /**
         * Builds the {@link AgentEnvironmentSnapshot}.
         *
         * @return a new {@link AgentEnvironmentSnapshot}
         * @throws NullPointerException
         *             if any required field ({@code workingDirectory}, {@code currentDate}, {@code environment}) is
         *             unset
         */
        public AgentEnvironmentSnapshot build() {
            return new AgentEnvironmentSnapshot(this);
        }
    }
}
