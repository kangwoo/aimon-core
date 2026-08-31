package at.aimon.core.command.execution;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;

/**
 * Represents a request to execute a command.
 *
 * <p>
 * Encapsulates all necessary information for command execution including:
 *
 * <ul>
 * <li>The command to execute
 * <li>Raw argument string (before parsing)
 * <li>Command arguments (as a list)
 * <li>User information
 * <li>Previous session snapshot (for multi-turn interactions)
 * </ul>
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
 *     CommandExecutionRequest request = CommandExecutionRequest.builder().command(command)
 *             .rawArguments("--verbose \"file.txt\"").arguments(List.of("--verbose", "file.txt"))
 *             .principal(principal).previousSnapshot(sessionSnapshot).build();
 *
 *     CommandExecutionResult result = executor.execute(request);
 * }
 * </pre>
 */
public final class CommandExecutionRequest {
    /**
     * Creates a new builder.
     *
     * @return A new CommandExecutionRequest.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String rawArguments;
    private final List<String> arguments;
    private final Principal principal;
    private final SessionSnapshot previousSnapshot;

    /**
     * Creates a new CommandExecutionRequest.
     *
     * @param rawArguments
     *            The raw argument string (can be null)
     * @param arguments
     *            The command arguments list (can be null or empty)
     * @param principal
     *            the principal (can be null)
     * @param previousSnapshot
     *            The previous session snapshot (can be null)
     * @throws NullPointerException
     *             if command is null
     */
    private CommandExecutionRequest(String rawArguments, List<String> arguments, Principal principal,
            SessionSnapshot previousSnapshot) {
        this.rawArguments = rawArguments == null ? "" : rawArguments;
        this.arguments = arguments == null ? Collections.emptyList() : List.copyOf(arguments);
        this.principal = principal;
        this.previousSnapshot = previousSnapshot;
    }

    /**
     * Gets the raw argument string before parsing.
     *
     * @return The raw argument string (never null, may be empty)
     */
    public String getRawArguments() {
        return rawArguments;
    }

    /**
     * Gets the command arguments.
     *
     * @return List of arguments (never null, may be empty)
     */
    public List<String> getArguments() {
        return arguments;
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

    /** Builder for CommandExecutionRequest. */
    public static final class Builder {
        private String rawArguments;
        private List<String> arguments;
        private Principal principal;
        private SessionSnapshot previousSnapshot;

        private Builder() {
        }

        /**
         * Sets the raw argument string.
         *
         * @param rawArguments
         *            The raw argument string (can be null)
         * @return This builder
         */
        public Builder rawArguments(String rawArguments) {
            this.rawArguments = rawArguments;
            return this;
        }

        /**
         * Sets the command arguments.
         *
         * @param arguments
         *            The command arguments list (can be null or empty)
         * @return This builder
         */
        public Builder arguments(List<String> arguments) {
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
         * Builds the CommandExecutionRequest.
         *
         * @return A new CommandExecutionRequest
         * @throws NullPointerException
         *             if command is null
         */
        public CommandExecutionRequest build() {
            return new CommandExecutionRequest(rawArguments, arguments, principal, previousSnapshot);
        }
    }
}
