package at.aimon.core.skill.execution;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.render.RenderContext;

/**
 * Represents a request to execute a Skill.
 *
 * <p>
 * Mirrors {@code CommandExecutionRequest} but uses a {@link RenderContext} for rendering the skill body via
 * {@link at.aimon.core.skill.render.SkillContentRenderer}. Introduced in SK-08-C.
 *
 * <p>
 * Immutable value object.
 */
public final class SkillExecutionRequest {

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String rawArguments;
    private final List<String> arguments;
    private final Principal principal;
    private final SessionSnapshot previousSnapshot;
    private final RenderContext renderContext;

    private SkillExecutionRequest(Builder builder) {
        this.rawArguments = builder.rawArguments == null ? "" : builder.rawArguments;
        this.arguments = builder.arguments == null ? Collections.emptyList() : List.copyOf(builder.arguments);
        this.principal = builder.principal;
        this.previousSnapshot = builder.previousSnapshot;
        this.renderContext = builder.renderContext == null ? RenderContext.empty() : builder.renderContext;
    }

    /**
     * Gets the raw argument string before tokenization.
     *
     * @return The raw argument string (never null, may be empty)
     */
    public String getRawArguments() {
        return rawArguments;
    }

    /**
     * Gets the parsed positional arguments.
     *
     * @return Immutable list of arguments (never null, may be empty)
     */
    public List<String> getArguments() {
        return arguments;
    }

    /**
     * Gets the principal (caller identity).
     *
     * @return Optional principal, empty when not provided
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the previous session snapshot.
     *
     * @return Optional snapshot, empty when not provided
     */
    public Optional<SessionSnapshot> getPreviousSnapshot() {
        return Optional.ofNullable(previousSnapshot);
    }

    /**
     * Gets the render context passed through to the {@link at.aimon.core.skill.render.SkillContentRenderer}.
     *
     * @return The render context (never null; defaults to {@link RenderContext#empty()})
     */
    public RenderContext getRenderContext() {
        return renderContext;
    }

    /** Builder for {@link SkillExecutionRequest}. */
    public static final class Builder {
        private String rawArguments;
        private List<String> arguments;
        private Principal principal;
        private SessionSnapshot previousSnapshot;
        private RenderContext renderContext;

        private Builder() {
        }

        /**
         * Sets the raw argument string.
         *
         * @param rawArguments
         *            The raw argument string (may be null, treated as empty)
         * @return This builder
         */
        public Builder rawArguments(String rawArguments) {
            this.rawArguments = rawArguments;
            return this;
        }

        /**
         * Sets the parsed positional arguments.
         *
         * @param arguments
         *            The arguments list (may be null or empty)
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
         *            the principal (may be null)
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
         *            The snapshot (may be null)
         * @return This builder
         */
        public Builder previousSnapshot(SessionSnapshot previousSnapshot) {
            this.previousSnapshot = previousSnapshot;
            return this;
        }

        /**
         * Sets the render context.
         *
         * @param renderContext
         *            The render context (may be null; defaults to {@link RenderContext#empty()})
         * @return This builder
         */
        public Builder renderContext(RenderContext renderContext) {
            this.renderContext = renderContext;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return A new {@link SkillExecutionRequest} instance (never null)
         */
        public SkillExecutionRequest build() {
            return new SkillExecutionRequest(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SkillExecutionRequest that = (SkillExecutionRequest) o;
        return rawArguments.equals(that.rawArguments) && arguments.equals(that.arguments)
                && Objects.equals(principal, that.principal) && Objects.equals(previousSnapshot, that.previousSnapshot)
                && renderContext.equals(that.renderContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawArguments, arguments, principal, previousSnapshot, renderContext);
    }
}
