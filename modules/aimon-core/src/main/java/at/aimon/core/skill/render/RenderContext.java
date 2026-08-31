package at.aimon.core.skill.render;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;

/**
 * Context information passed to {@link SkillContentRenderer#render}.
 *
 * <p>
 * All fields are optional. Implementations should treat missing values as "unknown" rather than failing, so that the
 * renderer can run in lightweight contexts (such as tests) without coupling to the full agent runtime.
 *
 * <p>
 * Immutable and thread-safe. Use {@link #builder()} to construct, or {@link #empty()} when no contextual information is
 * available.
 */
public final class RenderContext {

    private static final RenderContext EMPTY = new Builder().build();

    private final String agentRuntimeId;
    private final String sessionId;
    private final String executionId;
    private final Principal principal;
    private final String skillBaseDir;
    private final Map<String, String> additionalVariables;

    private RenderContext(Builder builder) {
        this.agentRuntimeId = builder.agentRuntimeId;
        this.sessionId = builder.sessionId;
        this.executionId = builder.executionId;
        this.principal = builder.principal;
        this.skillBaseDir = builder.skillBaseDir;
        this.additionalVariables = (builder.additionalVariables == null || builder.additionalVariables.isEmpty())
                ? Map.of()
                : Map.copyOf(builder.additionalVariables);
    }

    /**
     * Creates a new builder.
     *
     * @return A new builder instance (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a shared empty context with no fields populated.
     *
     * @return The empty context (never null)
     */
    public static RenderContext empty() {
        return EMPTY;
    }

    /**
     * Gets the identifier of the agent runtime that is rendering the skill ({@code agent:<name>} or
     * {@code agent:<name>:<discriminator>}).
     *
     * <p>
     * The value is <b>agent-scoped, not session-scoped</b>: it is derived deterministically from the
     * {@code (Agent, discriminator)} pair, so every session served by the same agent — and every cron re-fire —
     * sees the identical string. It must not be used as a per-run uniqueness discriminator.
     *
     * @return The agent runtime identifier, or empty if unknown
     */
    public Optional<String> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * Gets the identifier of the session the skill is being rendered for.
     *
     * <p>
     * Unlike {@link #getAgentRuntimeId()} this <b>is</b> per-session: two concurrent sessions of the same agent render
     * different values, which makes it the right discriminator for a skill body that needs a private scratch path.
     *
     * <p>
     * Empty when the run rendering the skill has no session of its own — a subagent fork, a scheduled routine — in
     * which case {@link #getExecutionId()} carries that run's identity instead. The two form an exclusive pair; see
     * that accessor for why the renderer does not merge them.
     *
     * <p>
     * This accessor used to be a deprecated alias of {@link #getAgentRuntimeId()}. The alias was withdrawn and the name
     * now reads the session id it always promised — see the {@code AIMON_SESSION_ID} entry in the changelog.
     *
     * @return The session identifier, or empty if unknown
     */
    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Gets the identity of the run rendering the skill when that run has no session of its own.
     *
     * <p>
     * Populated for a run that is not a session's turn — a subagent fork, a skill fork, a scheduled routine — and
     * empty for one that is, where {@link #getSessionId()} names it instead. Node-local and never persisted: it
     * identifies <i>this</i> run and nothing durable.
     *
     * <p>
     * Kept as a second field rather than folded into {@link #getSessionId()} deliberately. A fork used to render a
     * minted session id here, which a body could not tell apart from the user's own, and a merged accessor would put
     * that back — the caller would once again be unable to ask whether the value it received names a session. The
     * exclusive pair mirrors {@code SkillHookEnv.AIMON_SESSION_ID} / {@code AIMON_EXECUTION_ID}, which resolved the
     * same problem for hook environments.
     *
     * @return The execution identifier, or empty when the run is a session's turn (or when nothing is known)
     */
    public Optional<String> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * Gets the principal that triggered the skill activation.
     *
     * @return The principal, or empty if unknown
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the skill's base resource directory (typically a virtual filesystem path).
     *
     * @return The base directory, or empty if not applicable
     */
    public Optional<String> getSkillBaseDir() {
        return Optional.ofNullable(skillBaseDir);
    }

    /**
     * Gets the additional variable map. Renderers consult this map when expanding {@code ${VAR}} placeholders that are
     * not covered by the built-in {@code AIMON_*} variables.
     *
     * @return An unmodifiable map (never null, may be empty)
     */
    public Map<String, String> getAdditionalVariables() {
        return additionalVariables;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RenderContext that = (RenderContext) o;
        return Objects.equals(agentRuntimeId, that.agentRuntimeId) && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(executionId, that.executionId) && Objects.equals(principal, that.principal)
                && Objects.equals(skillBaseDir, that.skillBaseDir)
                && Objects.equals(additionalVariables, that.additionalVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentRuntimeId, sessionId, executionId, principal, skillBaseDir, additionalVariables);
    }

    @Override
    public String toString() {
        return "RenderContext{" + "agentRuntimeId='" + agentRuntimeId + '\'' + ", sessionId='" + sessionId + '\''
                + ", executionId='" + executionId + '\'' + ", principal=" + principal + ", skillBaseDir='"
                + skillBaseDir + '\'' + ", additionalVariables=" + additionalVariables + '}';
    }

    /** Builder for {@link RenderContext}. */
    public static final class Builder {
        private String agentRuntimeId;
        private String sessionId;
        private String executionId;
        private Principal principal;
        private String skillBaseDir;
        private Map<String, String> additionalVariables;

        private Builder() {
        }

        /**
         * Sets the agent runtime identifier. See {@link RenderContext#getAgentRuntimeId()} for its scope.
         *
         * @param agentRuntimeId
         *            The agent runtime identifier (may be null)
         * @return This builder
         */
        public Builder agentRuntimeId(String agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the session identifier. See {@link RenderContext#getSessionId()} for its scope.
         *
         * @param sessionId
         *            The session identifier (may be null)
         * @return This builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the identity of a run that has no session of its own. See {@link RenderContext#getExecutionId()}.
         *
         * @param executionId
         *            The execution identifier (may be null)
         * @return This builder
         */
        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        /**
         * Sets the principal that triggered the skill activation.
         *
         * @param principal
         *            The principal (may be null)
         * @return This builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the skill's base resource directory.
         *
         * @param skillBaseDir
         *            The base directory (may be null)
         * @return This builder
         */
        public Builder skillBaseDir(String skillBaseDir) {
            this.skillBaseDir = skillBaseDir;
            return this;
        }

        /**
         * Sets the additional variable map. The map is defensively copied at build time.
         *
         * @param additionalVariables
         *            The variable map (may be null or empty)
         * @return This builder
         */
        public Builder additionalVariables(Map<String, String> additionalVariables) {
            this.additionalVariables = additionalVariables;
            return this;
        }

        /**
         * Builds the {@link RenderContext}.
         *
         * @return A new RenderContext instance (never null)
         */
        public RenderContext build() {
            return new RenderContext(this);
        }
    }
}
