package at.aimon.core.agent.session;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;

/**
 * Immutable configuration for opening a {@link LiveSession}.
 *
 * <p>
 * {@code LiveSessionOptions} carries the per-session parameters that are not part of the bound session identity
 * or agent reference: the default {@link ExecutionBudget} applied to every turn, an optional {@link Locale} for
 * localized system prompt rendering, and an optional source agent identifier that callers can use for audit /
 * attribution purposes.
 *
 * <p>
 * Instances are constructed through the nested {@link Builder}, following the project's builder pattern convention
 * (see {@code at.aimon.core.agent.AgentContent}). All values except {@link #getBudget()} are optional; when omitted,
 * the corresponding getter returns {@link Optional#empty()} and the consuming component (usually
 * {@link DefaultLiveSession}) falls back to a sensible default. The {@code budget} field defaults to
 * {@link ExecutionBudget#unlimited()} so that omitting it preserves the legacy unbounded executor behavior.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     LiveSessionOptions options = LiveSessionOptions.builder()
 *             .budget(ExecutionBudget.builder().maxIterations(20).build()).locale(Locale.ENGLISH)
 *             .sourceAgentId("cli-repl").build();
 * }
 * </pre>
 *
 * @see LiveSession
 * @see LiveSessionFactory
 */
public final class LiveSessionOptions {

    /**
     * Returns the default options: unlimited budget, no locale, no source agent id.
     *
     * @return a {@code LiveSessionOptions} equivalent to {@code builder().build()} (never null)
     */
    public static LiveSessionOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final ExecutionBudget budget;
    private final Locale locale;
    private final String sourceAgentId;

    private LiveSessionOptions(Builder builder) {
        this.budget = builder.budget != null ? builder.budget : ExecutionBudget.unlimited();
        this.locale = builder.locale;
        this.sourceAgentId = builder.sourceAgentId;
    }

    /**
     * Returns the default {@link ExecutionBudget} applied to every turn.
     *
     * <p>
     * Defaults to {@link ExecutionBudget#unlimited()} when the builder did not set one explicitly. The session
     * injects this budget into the internally-built request so that all turns share the same cap.
     *
     * @return the budget (never null)
     */
    public ExecutionBudget getBudget() {
        return budget;
    }

    /**
     * Returns the locale, if one was configured.
     *
     * @return an {@code Optional} wrapping the locale, or empty if unset
     */
    public Optional<Locale> getLocale() {
        return Optional.ofNullable(locale);
    }

    /**
     * Returns the source agent id, if one was configured.
     *
     * <p>
     * This identifier is intended for audit / attribution — for example, the name of the REPL client or the id of an
     * upstream agent that opened the session on behalf of a user.
     *
     * @return an {@code Optional} wrapping the source agent id, or empty if unset
     */
    public Optional<String> getSourceAgentId() {
        return Optional.ofNullable(sourceAgentId);
    }

    /**
     * Returns a copy of these options with only the {@link ExecutionBudget} replaced, preserving the
     * {@link #getLocale()
     * locale} and {@link #getSourceAgentId() source agent id}.
     *
     * <p>
     * Used by {@link DefaultLiveSession} to apply a runtime budget override (e.g. a REPL {@code /budget} command) or a
     * persisted override hydrated at open time, without disturbing the other opener-injected fields.
     *
     * @param budget
     *            the replacement budget; when {@code null} the resulting options fall back to
     *            {@link ExecutionBudget#unlimited()}
     * @return a new {@code LiveSessionOptions} with the given budget and the same locale / source agent id (never
     *         null)
     */
    public LiveSessionOptions withBudget(ExecutionBudget budget) {
        return new Builder().budget(budget).locale(locale).sourceAgentId(sourceAgentId).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LiveSessionOptions that = (LiveSessionOptions) o;
        return budget.equals(that.budget) && Objects.equals(locale, that.locale)
                && Objects.equals(sourceAgentId, that.sourceAgentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(budget, locale, sourceAgentId);
    }

    @Override
    public String toString() {
        return "LiveSessionOptions{budget=" + budget + ", locale=" + locale + ", sourceAgentId='" + sourceAgentId
                + "'}";
    }

    /** Builder for {@link LiveSessionOptions}. */
    public static final class Builder {
        private ExecutionBudget budget;
        private Locale locale;
        private String sourceAgentId;

        private Builder() {
        }

        /**
         * Sets the default {@link ExecutionBudget} applied to every turn of the session.
         *
         * <p>
         * When {@code null} or never set, the built options fall back to {@link ExecutionBudget#unlimited()}.
         *
         * @param budget
         *            the budget (may be null)
         * @return this builder
         */
        public Builder budget(ExecutionBudget budget) {
            this.budget = budget;
            return this;
        }

        /**
         * Sets the optional locale.
         *
         * @param locale
         *            the locale (may be null)
         * @return this builder
         */
        public Builder locale(Locale locale) {
            this.locale = locale;
            return this;
        }

        /**
         * Sets the optional source agent id for audit / attribution purposes.
         *
         * @param sourceAgentId
         *            the source agent id (may be null or blank to indicate "unset")
         * @return this builder
         */
        public Builder sourceAgentId(String sourceAgentId) {
            this.sourceAgentId = sourceAgentId;
            return this;
        }

        /**
         * Builds the {@link LiveSessionOptions}.
         *
         * @return a new {@code LiveSessionOptions} instance (never null)
         */
        public LiveSessionOptions build() {
            return new LiveSessionOptions(this);
        }
    }
}
