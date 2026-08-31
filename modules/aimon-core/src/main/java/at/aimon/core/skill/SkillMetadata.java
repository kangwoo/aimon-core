package at.aimon.core.skill;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.skill.hook.SkillHookSet;

/**
 * Immutable metadata for an Agent Skill.
 *
 * <p>
 * Contains all fields defined in the Agent Skills standard:
 *
 * <ul>
 * <li><b>Required:</b> name, description
 * <li><b>Optional:</b> license, compatibility, metadata, allowedTools
 * </ul>
 *
 * <p>
 * The name field must be 1-64 characters, lowercase letters/numbers/hyphens only, no start/end hyphen, no consecutive
 * hyphens.
 *
 * <p>
 * The description field must be 1-1024 characters and describe what the skill does and when to use it.
 *
 * <p>
 * The allowedTools field is space-delimited and parsed lazily into AllowedTool objects.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillMetadata metadata = SkillMetadata.builder().name("alert-analysis")
 *             .description("Analyzes alerts from Prometheus").license("MIT").compatibility("Requires Python 3.8+")
 *             .putMetadata("author", "aimon-team").putMetadata("version", "1.0.0")
 *             .allowedTools("Read Grep Bash(python:*)").build();
 * }
 * </pre>
 */
public final class SkillMetadata {

    /** Default max ReAct iterations when a skill is invoked by the user. */
    public static final int DEFAULT_MAX_ITERATIONS = 100;

    /**
     * Creates a new builder.
     *
     * @return A new builder instance (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    // Required fields (Agent Skills Standard)
    private final String name;
    private final String description;
    // Optional fields (Agent Skills Standard)
    private final String license;
    private final String compatibility;
    private final Map<String, String> metadata;
    private final List<AllowedTool> allowedTools; // Parsed allowed tools (single source of truth)
    // AIMON extension: ordered list of named argument placeholders (e.g. ["message", "title"])
    private final List<String> argumentNames;
    // AIMON extension: who is allowed to invoke this skill (user / model)
    private final InvokePolicy invokePolicy;
    // AIMON extension: max ReAct iterations when the skill runs as a user-invoked command
    private final int maxIterations;
    // AIMON extension: how the skill body is executed (inline in the parent agent vs forked into a SubAgent)
    private final ExecutionMode executionMode;
    // AIMON extension: SubAgent name to fork into when executionMode == FORK; null for INLINE
    private final String forkAgentName;
    // AIMON extension: hooks active only while this skill is being invoked (per SkillHookActivator)
    private final SkillHookSet hooks;

    private SkillMetadata(Builder builder) {
        name = Objects.requireNonNull(builder.name, "Name cannot be null");
        description = Objects.requireNonNull(builder.description, "Description cannot be null");
        license = builder.license;
        compatibility = builder.compatibility;
        metadata = builder.metadata.isEmpty() ? Map.of() : Map.copyOf(builder.metadata);
        allowedTools = builder.allowedTools.isEmpty() ? List.of() : List.copyOf(builder.allowedTools);
        argumentNames = validateArgumentNames(builder.argumentNames);
        invokePolicy = builder.invokePolicy == null ? InvokePolicy.defaults() : builder.invokePolicy;
        maxIterations = resolveMaxIterations(builder.maxIterations);
        executionMode = builder.executionMode == null ? ExecutionMode.INLINE : builder.executionMode;
        forkAgentName = resolveForkAgentName(executionMode, builder.forkAgentName);
        hooks = builder.hooks == null ? SkillHookSet.empty() : builder.hooks;
    }

    private static String resolveForkAgentName(ExecutionMode mode, String raw) {
        final boolean hasValue = raw != null && !raw.isBlank();
        if (mode == ExecutionMode.FORK) {
            if (!hasValue) {
                throw new IllegalArgumentException("execution.agent must be set when execution.mode is 'fork'");
            }
            return raw.trim();
        }
        // mode == INLINE
        if (hasValue) {
            throw new IllegalArgumentException(
                    "execution.agent is only valid when execution.mode is 'fork', but mode is 'inline'");
        }
        return null;
    }

    private static int resolveMaxIterations(Integer raw) {
        final int resolved = raw == null ? DEFAULT_MAX_ITERATIONS : raw;
        if (resolved <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive, but was: " + resolved);
        }
        return resolved;
    }

    private static List<String> validateArgumentNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        final Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (n == null) {
                throw new IllegalArgumentException("Argument name cannot be null");
            }
            if (n.isBlank()) {
                throw new IllegalArgumentException("Argument name cannot be blank");
            }
            if (!seen.add(n)) {
                throw new IllegalArgumentException("Duplicate argument name: " + n);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Gets the skill name.
     *
     * @return The skill name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the skill description.
     *
     * @return The skill description (never null)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the license information.
     *
     * @return The license, or null if not specified
     */
    public String getLicense() {
        return license;
    }

    /**
     * Gets the compatibility requirements.
     *
     * @return The compatibility requirements, or null if not specified
     */
    public String getCompatibility() {
        return compatibility;
    }

    /**
     * Gets additional metadata.
     *
     * @return Unmodifiable map of metadata (never null, may be empty)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Gets parsed allowed tools.
     *
     * @return Unmodifiable list of allowed tools (never null, may be empty)
     */
    public List<AllowedTool> getAllowedTools() {
        return allowedTools;
    }

    /**
     * Checks if this skill has tools restrictions.
     *
     * @return true if allowed-tools is specified, false otherwise
     */
    public boolean hasToolRestrictions() {
        return !allowedTools.isEmpty();
    }

    /**
     * Gets the ordered list of named argument placeholders declared by this skill (AIMON extension).
     *
     * <p>
     * Each entry corresponds to one positional token from the {@code args} string supplied by the caller. For example,
     * {@code argumentNames = ["message", "title"]} together with {@code args = "Hello \"hi there\""} maps
     * {@code $message}
     * → {@code "Hello"} and {@code $title} → {@code "hi there"}.
     *
     * @return Unmodifiable list of argument names (never null, may be empty)
     */
    public List<String> getArgumentNames() {
        return argumentNames;
    }

    /**
     * Gets the invoke policy declared by this skill (AIMON extension).
     *
     * <p>
     * When the SKILL.md frontmatter omits the {@code invoke} block, this returns {@link InvokePolicy#defaults()}.
     *
     * @return The invoke policy (never null)
     */
    public InvokePolicy getInvokePolicy() {
        return invokePolicy;
    }

    /**
     * Gets the max ReAct iteration count permitted when this skill is invoked by the user (AIMON extension).
     *
     * <p>
     * Defaults to {@link #DEFAULT_MAX_ITERATIONS} when the SKILL.md frontmatter omits {@code max-iterations}. The model
     * invocation path ({@code SkillTool}) is unaffected by this value.
     *
     * @return The max iteration count (always positive)
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Gets the execution mode for the skill body (AIMON extension).
     *
     * <p>
     * Defaults to {@link ExecutionMode#INLINE} when the SKILL.md frontmatter omits the {@code execution} block.
     *
     * @return The execution mode (never null)
     */
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    /**
     * Gets the SubAgent name this skill forks into when {@link #getExecutionMode()} is {@link ExecutionMode#FORK}
     * (AIMON extension).
     *
     * <p>
     * Always {@code null} for {@link ExecutionMode#INLINE}. Always non-null and non-blank for
     * {@link ExecutionMode#FORK}
     * (validated at build time). Resolution to an actual SubAgent happens at execute time, not at load time, so the
     * referenced agent does not need to exist when the skill is parsed.
     *
     * @return The SubAgent name, or {@code null} when mode is {@code INLINE}
     */
    public String getForkAgentName() {
        return forkAgentName;
    }

    /**
     * Gets the per-skill hook bundle (AIMON extension).
     *
     * <p>
     * The hooks contained here are activated at the start of each {@code SkillTool.execute()} call (via
     * {@link at.aimon.core.skill.hook.SkillHookActivator}) and deactivated when the call returns. They are most
     * meaningful for fork-mode skills, where the scope spans the spawned SubAgent's lifetime.
     *
     * @return The hook set (never null; empty when no hooks are declared)
     */
    public SkillHookSet getHooks() {
        return hooks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SkillMetadata that = (SkillMetadata) o;
        return maxIterations == that.maxIterations && Objects.equals(name, that.name)
                && Objects.equals(description, that.description) && Objects.equals(license, that.license)
                && Objects.equals(compatibility, that.compatibility) && Objects.equals(metadata, that.metadata)
                && Objects.equals(allowedTools, that.allowedTools) && Objects.equals(argumentNames, that.argumentNames)
                && Objects.equals(invokePolicy, that.invokePolicy) && executionMode == that.executionMode
                && Objects.equals(forkAgentName, that.forkAgentName) && Objects.equals(hooks, that.hooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, license, compatibility, metadata, allowedTools, argumentNames,
                invokePolicy, maxIterations, executionMode, forkAgentName, hooks);
    }

    @Override
    public String toString() {
        return "SkillMetadata{" + "name='" + name + '\'' + ", description='" + description + '\'' + ", license='"
                + license + '\'' + ", compatibility='" + compatibility + '\'' + ", metadata=" + metadata
                + ", allowedTools='" + allowedTools + '\'' + ", argumentNames=" + argumentNames + ", invokePolicy="
                + invokePolicy + ", maxIterations=" + maxIterations + ", executionMode=" + executionMode
                + ", forkAgentName='" + forkAgentName + '\'' + ", hooks=" + hooks + '}';
    }

    /** Builder for SkillMetadata. */
    public static final class Builder {
        private String name;
        private String description;
        private String license;
        private String compatibility;
        private Map<String, String> metadata = Map.of();
        private List<AllowedTool> allowedTools = List.of();
        private List<String> argumentNames = List.of();
        private InvokePolicy invokePolicy;
        private Integer maxIterations;
        private ExecutionMode executionMode;
        private String forkAgentName;
        private SkillHookSet hooks;

        private Builder() {
        }

        /**
         * Sets the skill name (required).
         *
         * @param name
         *            The skill name (must not be null)
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the skill description (required).
         *
         * @param description
         *            The skill description (must not be null)
         * @return This builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the license.
         *
         * @param license
         *            The license
         * @return This builder
         */
        public Builder license(String license) {
            this.license = license;
            return this;
        }

        /**
         * Sets the compatibility requirements.
         *
         * @param compatibility
         *            The compatibility requirements
         * @return This builder
         */
        public Builder compatibility(String compatibility) {
            this.compatibility = compatibility;
            return this;
        }

        /**
         * Sets the metadata map.
         *
         * @param metadata
         *            The metadata map (must not be null)
         * @return This builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
            return this;
        }

        /**
         * Adds a metadata entry.
         *
         * @param key
         *            The metadata key (must not be null)
         * @param value
         *            The metadata value (must not be null)
         * @return This builder
         */
        public Builder putMetadata(String key, String value) {
            Objects.requireNonNull(key, "Key cannot be null");
            Objects.requireNonNull(value, "Value cannot be null");
            if (metadata.isEmpty()) {
                metadata = new HashMap<>();
            }
            if (!(metadata instanceof HashMap)) {
                metadata = new HashMap<>(metadata);
            }
            metadata.put(key, value);
            return this;
        }

        /**
         * Sets the allowed-tools from a space-delimited string.
         *
         * <p>
         * Parses the string into AllowedTool objects.
         *
         * @param allowedToolsString
         *            The space-delimited allowed-tools string
         * @return This builder
         */
        public Builder allowedTools(String allowedToolsString) {
            if (allowedToolsString == null || allowedToolsString.isBlank()) {
                allowedTools = List.of();
            } else {
                final String[] specs = allowedToolsString.split("\\s+");
                allowedTools = Arrays.stream(specs).map(AllowedTool::parse).toList();
            }
            return this;
        }

        /**
         * Sets the allowed-tools from a list of tools specification strings.
         *
         * <p>
         * Parses each string into AllowedTool objects.
         *
         * @param toolSpecs
         *            The list of allowed tools specifications
         * @return This builder
         */
        public Builder allowedToolsList(List<String> toolSpecs) {
            Objects.requireNonNull(toolSpecs, "Tool specs cannot be null");
            allowedTools = toolSpecs.stream().map(AllowedTool::parse).toList();
            return this;
        }

        /**
         * Sets the allowed-tools directly from parsed AllowedTool objects.
         *
         * @param allowedTools
         *            The list of allowed tools
         * @return This builder
         */
        public Builder allowedToolsObjects(List<AllowedTool> allowedTools) {
            this.allowedTools = Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");
            return this;
        }

        /**
         * Sets the named argument placeholders (AIMON extension).
         *
         * <p>
         * Validation (uniqueness, blank checks) is performed when {@link #build()} is called.
         *
         * @param argumentNames
         *            The ordered list of argument names; null is treated as empty
         * @return This builder
         */
        public Builder argumentNames(List<String> argumentNames) {
            this.argumentNames = (argumentNames == null) ? List.of() : argumentNames;
            return this;
        }

        /**
         * Sets the invoke policy (AIMON extension).
         *
         * @param invokePolicy
         *            The invoke policy; {@code null} resolves to {@link InvokePolicy#defaults()} at build time
         * @return This builder
         */
        public Builder invokePolicy(InvokePolicy invokePolicy) {
            this.invokePolicy = invokePolicy;
            return this;
        }

        /**
         * Sets the max ReAct iteration count for user-invoked execution (AIMON extension).
         *
         * @param maxIterations
         *            The max iteration count; must be positive. {@code null} (or unset) resolves to
         *            {@link SkillMetadata#DEFAULT_MAX_ITERATIONS} at build time.
         * @return This builder
         * @throws IllegalArgumentException
         *             at build time if the resolved value is not positive
         */
        public Builder maxIterations(Integer maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the execution mode (AIMON extension).
         *
         * @param executionMode
         *            The mode; {@code null} resolves to {@link ExecutionMode#INLINE} at build time
         * @return This builder
         */
        public Builder executionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        /**
         * Sets the SubAgent name to fork into when execution mode is {@link ExecutionMode#FORK} (AIMON extension).
         *
         * <p>
         * Validation against the current execution mode happens at {@link #build()} time:
         * <ul>
         * <li>FORK + null/blank → throws {@link IllegalArgumentException}
         * <li>INLINE + non-blank → throws {@link IllegalArgumentException} (likely a frontmatter mistake)
         * </ul>
         *
         * @param forkAgentName
         *            The SubAgent name; {@code null} or blank is allowed only when mode is {@code INLINE}
         * @return This builder
         */
        public Builder forkAgentName(String forkAgentName) {
            this.forkAgentName = forkAgentName;
            return this;
        }

        /**
         * Sets the per-skill hook bundle (AIMON extension).
         *
         * @param hooks
         *            The hooks active only while this skill is being invoked; {@code null} resolves to
         *            {@link SkillHookSet#empty()} at build time
         * @return This builder
         */
        public Builder hooks(SkillHookSet hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * Builds the SkillMetadata.
         *
         * @return A new SkillMetadata instance (never null)
         * @throws NullPointerException
         *             if required fields are null
         */
        public SkillMetadata build() {
            return new SkillMetadata(this);
        }
    }
}
