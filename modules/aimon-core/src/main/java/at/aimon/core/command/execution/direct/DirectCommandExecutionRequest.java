package at.aimon.core.command.execution.direct;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.core.command.execution.CommandExecutionRequest;

/**
 * Represents a request to execute a direct command.
 *
 * <p>
 * Encapsulates all necessary information for direct command execution including:
 *
 * <ul>
 * <li>Command arguments
 * <li>User information
 * <li>Previous session snapshot (for multi-turn interactions)
 * </ul>
 *
 * <p>
 * Unlike {@link CommandExecutionRequest}, this does not contain a command field because DirectExecutable instances
 * execute themselves (the command is the object whose method is being called).
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     DirectCommandExecutionRequest request = DirectCommandExecutionRequest.builder().arguments("--verbose")
 *             .principal(principal).previousSnapshot(sessionSnapshot).build();
 *
 *     CommandExecutionResult result = directCommand.execute(context, request);
 * }
 * </pre>
 *
 * @see DirectExecutable
 * @see CommandExecutionRequest
 */
public final class DirectCommandExecutionRequest {
    /**
     * Creates a new DirectCommandExecutionRequest with only arguments.
     *
     * @param arguments
     *            The command arguments (can be null)
     * @return A new DirectCommandExecutionRequest
     */
    public static DirectCommandExecutionRequest of(String arguments) {
        return new DirectCommandExecutionRequest(arguments, (Principal) null, null);
    }

    /**
     * Creates a new builder.
     *
     * @return A new DirectCommandExecutionRequest.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String arguments;
    private final Principal principal;
    private final SessionSnapshot previousSnapshot;

    /**
     * Creates a new DirectCommandExecutionRequest.
     *
     * @param arguments
     *            The command arguments (can be null)
     * @param principal
     *            the principal (can be null)
     * @param previousSnapshot
     *            The previous session snapshot (can be null)
     */
    private DirectCommandExecutionRequest(String arguments, Principal principal, SessionSnapshot previousSnapshot) {
        this.arguments = arguments;
        this.principal = principal;
        this.previousSnapshot = previousSnapshot;
    }

    /**
     * Gets the command arguments.
     *
     * @return Optional containing the arguments, or empty if not provided
     */
    public Optional<String> getArguments() {
        return Optional.ofNullable(arguments);
    }

    /**
     * Gets the principal (caller identity).
     *
     * @return Optional containing the principal, or empty if not provided
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the previous session snapshot.
     *
     * @return Optional containing the previous session snapshot, or empty if not provided
     */
    public Optional<SessionSnapshot> getPreviousSnapshot() {
        return Optional.ofNullable(previousSnapshot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DirectCommandExecutionRequest that = (DirectCommandExecutionRequest) o;
        return Objects.equals(arguments, that.arguments) && Objects.equals(principal, that.principal)
                && Objects.equals(previousSnapshot, that.previousSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(arguments, principal, previousSnapshot);
    }

    @Override
    public String toString() {
        return "DirectCommandExecutionRequest{" + "arguments='" + arguments + '\'' + ", principal=" + principal
                + ", previousSnapshot=" + previousSnapshot + '}';
    }

    /** Builder for DirectCommandExecutionRequest. */
    public static final class Builder {
        private String arguments;
        private Principal principal;
        private SessionSnapshot previousSnapshot;

        private Builder() {
        }

        /**
         * Sets the command arguments.
         *
         * @param arguments
         *            The command arguments (can be null)
         * @return This builder
         */
        public Builder arguments(String arguments) {
            this.arguments = arguments;
            return this;
        }

        /**
         * Sets the principal (caller identity).
         *
         * @param principal
         *            the principal (can be null)
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the previous session snapshot.
         *
         * @param previousSnapshot
         *            The previous session snapshot (can be null)
         * @return This builder
         */
        public Builder previousSnapshot(SessionSnapshot previousSnapshot) {
            this.previousSnapshot = previousSnapshot;
            return this;
        }

        /**
         * Builds the DirectCommandExecutionRequest.
         *
         * @return A new DirectCommandExecutionRequest
         */
        public DirectCommandExecutionRequest build() {
            return new DirectCommandExecutionRequest(arguments, principal, previousSnapshot);
        }
    }
}
