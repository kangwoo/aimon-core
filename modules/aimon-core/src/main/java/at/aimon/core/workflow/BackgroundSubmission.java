package at.aimon.core.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.quota.QuotaGuard;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * What a background workflow run takes from the call that submitted it ({@link WorkflowRunner#runInBackground(
 * WorkflowScript, RunId, BackgroundSubmission)}): the submitter's usage-quota guard and its tool ceiling — the
 * allow-list and programmatic settings of the loop that made the call.
 *
 * <p>
 * The runner's base launch context carries the <em>agent's</em> ceiling, because the runner is agent-scoped. Without
 * the submitter's, a skill or a fork that launched a background run would escape its own ceiling: the run's subagents
 * would be bounded by the agent alone (register TC-25). The runner narrows its base by these values, so a run can only
 * do less than both. Every field is optional: a field left unset means the base launch context's value alone.
 *
 * <p>
 * A submission built from a tool call ({@code BackgroundSubmissions.of}) does not leave the ceiling unset, though: it
 * always sets both fields, from the call's published ceiling. A tool context that publishes no programmatic settings
 * gives {@link ProgrammaticToolSettings#none()}, so the run's subagents get no {@code ExecuteCode} rather than the
 * agent's — the ceiling fails closed. (An absent allow-list gives the empty one, which means "unrestricted" and so
 * leaves the base's.)
 *
 * <p>
 * {@link #ceilingDigest()} names the ceiling for the run id, so an identical request from a narrower caller does not
 * join a run in flight under a wider one. Immutable.
 */
public final class BackgroundSubmission {

    /** A submission that takes nothing from its caller: every value is the base launch context's. */
    private static final BackgroundSubmission NONE = builder().build();

    /** Hex characters of the SHA-256 kept by {@link #ceilingDigest()}. */
    private static final int DIGEST_HEX_CHARS = 16;

    private final QuotaGuard quotaGuard;
    private final List<AllowedTool> callerAllowedTools;
    private final ProgrammaticToolSettings callerProgrammatic;

    private BackgroundSubmission(Builder builder) {
        this.quotaGuard = builder.quotaGuard;
        this.callerAllowedTools = builder.callerAllowedTools == null ? null : List.copyOf(builder.callerAllowedTools);
        this.callerProgrammatic = builder.callerProgrammatic;
    }

    /**
     * Returns a submission that takes nothing from its caller.
     *
     * @return the empty submission (never null)
     */
    public static BackgroundSubmission none() {
        return NONE;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the submitting execution's quota guard.
     *
     * @return the guard, or empty for the base launch context's
     */
    public Optional<QuotaGuard> getQuotaGuard() {
        return Optional.ofNullable(quotaGuard);
    }

    /**
     * Returns the submitter's allow-list; an empty list means the submitter is unrestricted.
     *
     * @return the allow-list, or empty for the base launch context's alone
     */
    public Optional<List<AllowedTool>> getCallerAllowedTools() {
        return Optional.ofNullable(callerAllowedTools);
    }

    /**
     * Returns the submitter's programmatic settings.
     *
     * @return the settings, or empty for the base launch context's alone
     */
    public Optional<ProgrammaticToolSettings> getCallerProgrammatic() {
        return Optional.ofNullable(callerProgrammatic);
    }

    /**
     * Returns a stable name for this submission's ceiling: a 16-hex-character prefix of the SHA-256 of the canonical
     * allow-list and programmatic settings, as submitted (before the runner narrows them). Two submissions with the
     * same ceiling give the same digest, so the same request from the same caller still joins its run in flight.
     *
     * @return the digest (never null)
     */
    public String ceilingDigest() {
        final String canonical = "allowed="
                + (callerAllowedTools == null ? "-" : AllowedTool.formatList(callerAllowedTools)) + ";programmatic="
                + (callerProgrammatic == null ? "-" : callerProgrammatic.toString());
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Builder for {@link BackgroundSubmission}. */
    public static final class Builder {
        private QuotaGuard quotaGuard;
        private List<AllowedTool> callerAllowedTools;
        private ProgrammaticToolSettings callerProgrammatic;

        private Builder() {
        }

        /**
         * Sets the submitting execution's quota guard.
         *
         * @param quotaGuard
         *            the guard, or null for the base launch context's
         * @return this builder
         */
        public Builder quotaGuard(QuotaGuard quotaGuard) {
            this.quotaGuard = quotaGuard;
            return this;
        }

        /**
         * Sets the submitter's allow-list.
         *
         * @param callerAllowedTools
         *            the allow-list (empty means unrestricted), or null for the base launch context's alone
         * @return this builder
         */
        public Builder callerAllowedTools(List<AllowedTool> callerAllowedTools) {
            this.callerAllowedTools = callerAllowedTools;
            return this;
        }

        /**
         * Sets the submitter's programmatic settings.
         *
         * @param callerProgrammatic
         *            the settings, or null for the base launch context's alone
         * @return this builder
         */
        public Builder callerProgrammatic(ProgrammaticToolSettings callerProgrammatic) {
            this.callerProgrammatic = callerProgrammatic;
            return this;
        }

        /**
         * Builds the submission.
         *
         * @return the submission (never null)
         */
        public BackgroundSubmission build() {
            return new BackgroundSubmission(this);
        }
    }
}
