package at.aimon.core.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Immutable metadata parsed from command file YAML frontmatter.
 *
 * <p>
 * Represents the frontmatter section of a command markdown file, which contains:
 *
 * <ul>
 * <li>description: A human-readable description of the command
 * <li>allowed-tools: List of tools permitted during command execution
 * <li>max-iterations: Maximum number of iterations allowed for command execution
 * </ul>
 *
 * <p>
 * <b>Design:</b> Tool specifications (e.g., "Bash(git add:*)", "Read") are parsed into {@link AllowedTool} objects
 * during construction and stored as the single source of truth. This eliminates dual storage and lazy loading
 * complexity while providing strong type safety and immediate validation.
 *
 * <p>
 * Thread-safe immutable value object.
 *
 * <p>
 * <b>Example frontmatter:</b>
 *
 * <pre>
 * {@code
 * ---
 * description: Create a git commit with proper message
 * allowed-tools: Bash(git add:*), Bash(git commit:*), Read
 * ---
 * }
 * </pre>
 *
 * <p>
 * <b>Usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create metadata using Builder pattern (recommended)
 *     CommandMetadata metadata = CommandMetadata.builder().description("Create git commit")
 *             .allowedTools(List.of("Bash(git add:*)", "Bash(git commit:*)")).maxIterations(500).build();
 *
 *     // Create empty metadata
 *     CommandMetadata empty = CommandMetadata.empty();
 *
 *     // Access metadata
 *     Optional<String> desc = metadata.getDescription(); // "Create git commit"
 *     List<AllowedTool> tools = metadata.getAllowedTools(); // Parsed AllowedTool objects
 *     int maxIterations = metadata.getMaxIterations(); // 100 (default)
 * }
 * </pre>
 */
public final class CommandMetadata {
    private static final int DEFAULT_MAX_ITERATIONS = 100;

    /**
     * Creates an empty CommandMetadata with no description or allowed tools.
     *
     * @return An empty CommandMetadata instance
     */
    public static CommandMetadata empty() {
        return new CommandMetadata(null, List.of(), DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Creates a new Builder instance for constructing CommandMetadata.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String description;
    private final List<AllowedTool> allowedTools;
    private final int maxIterations;

    private CommandMetadata(String description, List<AllowedTool> allowedTools, int maxIterations) {
        this.description = description; // Can be null
        this.allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        this.maxIterations = maxIterations;
    }

    /**
     * Returns the command description.
     *
     * @return An Optional containing the description, or empty if not specified
     */
    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns the list of parsed AllowedTool objects.
     *
     * <p>
     * These are parsed from the raw tools specifications during construction.
     *
     * @return An immutable list of AllowedTool objects (never null, may be empty)
     */
    public List<AllowedTool> getAllowedTools() {
        return allowedTools;
    }

    /**
     * Checks if this metadata has permission restrictions.
     *
     * @return true if there are allowed tools defined, false otherwise
     */
    public boolean hasPermissionRestrictions() {
        return !allowedTools.isEmpty();
    }

    /**
     * Returns the maximum number of iterations allowed for command execution.
     *
     * @return The maximum number of iterations (default: 100)
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CommandMetadata that = (CommandMetadata) o;
        return maxIterations == that.maxIterations && Objects.equals(description, that.description)
                && allowedTools.equals(that.allowedTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, allowedTools, maxIterations);
    }

    @Override
    public String toString() {
        return "CommandMetadata{" + "description='" + description + '\'' + ", allowedTools=" + allowedTools
                + ", maxIterations=" + maxIterations + '}';
    }

    /**
     * Builder for creating CommandMetadata instances with fluent API.
     *
     * <p>
     * Provides a convenient way to construct {@link CommandMetadata} objects with optional parameters.
     *
     * <p>
     * <b>Usage:</b>
     *
     * <pre>
     * {
     *     &#64;code
     *     // Create with all fields
     *     CommandMetadata metadata = CommandMetadata.builder().description("Create git commit")
     *             .allowedTools(List.of("Bash(git add:*)", "Read")).maxIterations(500).build();
     *
     *     // Create with minimal fields (uses defaults)
     *     CommandMetadata minimal = CommandMetadata.builder().description("Simple command").build();
     * }
     * </pre>
     */
    public static final class Builder {
        private String description;
        private List<String> allowedTools;
        private Integer maxIterations;

        private Builder() {
        }

        /**
         * Sets the command description.
         *
         * @param description
         *            The human-readable description of the command
         * @return This builder instance for method chaining
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the allowed tools specifications.
         *
         * <p>
         * Tool specifications will be parsed into {@link AllowedTool} objects during build.
         *
         * @param allowedTools
         *            List of tool specification strings (e.g., "Bash(git add:*)", "Read")
         * @return This builder instance for method chaining
         */
        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        /**
         * Sets the maximum number of iterations allowed for command execution.
         *
         * @param maxIterations
         *            The maximum number of iterations (must be positive)
         * @return This builder instance for method chaining
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Builds the CommandMetadata instance.
         *
         * <p>
         * Uses default values for fields not explicitly set:
         * <ul>
         * <li>allowedTools: empty list
         * <li>maxIterations: 100
         * </ul>
         *
         * @return A new immutable CommandMetadata instance
         */
        public CommandMetadata build() {
            final List<AllowedTool> parsedTools = (allowedTools == null || allowedTools.isEmpty())
                    ? List.of()
                    : allowedTools.stream().map(AllowedTool::parse).toList();
            final int resolvedMaxIterations = maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
            if (resolvedMaxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive, but was: " + resolvedMaxIterations);
            }
            return new CommandMetadata(description, parsedTools, resolvedMaxIterations);
        }
    }
}
