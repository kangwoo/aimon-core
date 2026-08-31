package at.aimon.core.agent.context;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * The read-only inputs a {@link ContextProvider} may consult when producing {@link ContextBlock context blocks}.
 *
 * <p>
 * All fields are optional so providers can be composed freely: a provider that needs a {@link VirtualFileSystem} simply
 * yields nothing when none is present, rather than failing. The request carries no mutable state and does not itself
 * touch the filesystem or environment — it merely hands references to the providers.
 *
 * <p>
 * Instances are immutable and thread-safe (as immutable as the references they hold).
 */
public final class ContextAssemblyRequest {

    private final Environment environment;
    private final VirtualFileSystem fileSystem;
    private final String agentName;
    private final int iteration;

    private ContextAssemblyRequest(Builder builder) {
        this.environment = builder.environment;
        this.fileSystem = builder.fileSystem;
        this.agentName = builder.agentName;
        this.iteration = builder.iteration;
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
        }
    }

    /**
     * @return the runtime environment, or empty when none is bound
     */
    public Optional<Environment> getEnvironment() {
        return Optional.ofNullable(environment);
    }

    /**
     * @return the session's virtual filesystem, or empty when none is bound
     */
    public Optional<VirtualFileSystem> getFileSystem() {
        return Optional.ofNullable(fileSystem);
    }

    /**
     * @return the invoking agent's name, or empty when unknown
     */
    public Optional<String> getAgentName() {
        return Optional.ofNullable(agentName);
    }

    /**
     * @return the ReAct iteration this request is being assembled for ({@code 0} at turn start); used by providers that
     *         refresh on a cadence
     */
    public int getIteration() {
        return iteration;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ContextAssemblyRequest} instances. */
    public static final class Builder {
        private Environment environment;
        private VirtualFileSystem fileSystem;
        private String agentName;
        private int iteration;

        private Builder() {
        }

        /**
         * @param environment
         *            the runtime environment (may be null)
         * @return this builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * @param fileSystem
         *            the session's virtual filesystem (may be null)
         * @return this builder
         */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * @param agentName
         *            the invoking agent's name (may be null)
         * @return this builder
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * @param iteration
         *            the ReAct iteration (must be &gt;= 0)
         * @return this builder
         */
        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        /**
         * @return the built request
         * @throws IllegalArgumentException
         *             if iteration is negative
         */
        public ContextAssemblyRequest build() {
            return new ContextAssemblyRequest(this);
        }
    }

    @Override
    public String toString() {
        return "ContextAssemblyRequest{environment=" + environment + ", fileSystem="
                + (fileSystem == null ? "null" : fileSystem.getClass().getSimpleName()) + ", agentName='" + agentName
                + '\'' + ", iteration=" + iteration + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ContextAssemblyRequest that = (ContextAssemblyRequest) o;
        return iteration == that.iteration && Objects.equals(environment, that.environment)
                && Objects.equals(fileSystem, that.fileSystem) && Objects.equals(agentName, that.agentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environment, fileSystem, agentName, iteration);
    }
}
