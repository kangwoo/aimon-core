package at.aimon.core.mcp;

import java.util.Objects;
import java.util.Optional;

/**
 * The behavioural hints an MCP server may attach to a tool in its {@code tools/list} response.
 *
 * <p>
 * These are the four booleans of the MCP {@code ToolAnnotations} object. They are <b>hints</b> in the protocol's own
 * words — a server asserts them about itself and nothing verifies them — which is why this type only carries what the
 * server said. Whether any of it is believed is {@link McpServerConfig.AnnotationTrust}'s question, and turning what is
 * believed into AIMON declarations is {@link McpToolTraits}'.
 *
 * <h2>Absent is not false</h2>
 *
 * <p>
 * Every hint is optional and each has its own default when omitted, so "absent" and "present and false" have to stay
 * distinguishable up to the point where the default is applied. Hence {@code Optional<Boolean>} accessors for the raw
 * hint and a second set that applies the spec default:
 *
 * <table border="1">
 * <caption>MCP tool annotation hints</caption>
 * <tr>
 * <th>Hint</th>
 * <th>Default when omitted</th>
 * <th>Applied by</th>
 * </tr>
 * <tr>
 * <td>{@code readOnlyHint}</td>
 * <td>{@code false}</td>
 * <td>{@link #isReadOnly()}</td>
 * </tr>
 * <tr>
 * <td>{@code destructiveHint}</td>
 * <td>{@code true}</td>
 * <td>{@link #isDestructive()}</td>
 * </tr>
 * <tr>
 * <td>{@code idempotentHint}</td>
 * <td>{@code false}</td>
 * <td>{@link #isIdempotent()}</td>
 * </tr>
 * <tr>
 * <td>{@code openWorldHint}</td>
 * <td>{@code true}</td>
 * <td>{@link #isOpenWorld()}</td>
 * </tr>
 * </table>
 *
 * <p>
 * The defaults are the conservative reading in every row, and they coincide with AIMON's own defaults on the axes that
 * have one — so a server that sends no annotations at all lands exactly where an undeclared in-tree tool does, without
 * a special case anywhere.
 *
 * <p>
 * {@code idempotentHint} and {@code openWorldHint} are parsed but currently feed no declaration: AIMON has no
 * idempotency axis yet, and {@code openWorldHint} (does the tool touch an open-ended external world?) has no AIMON
 * counterpart planned. They are carried rather than dropped so that adding the reader later is not another round of
 * protocol work.
 *
 * <p>
 * MCP's {@code title} is deliberately not modelled here — this type is about behaviour, and the display name is not
 * behaviour.
 *
 * @see McpToolTraits
 * @see McpServerConfig.AnnotationTrust
 */
public final class McpToolAnnotations {

    private static final McpToolAnnotations EMPTY = builder().build();

    private final Boolean readOnlyHint;
    private final Boolean destructiveHint;
    private final Boolean idempotentHint;
    private final Boolean openWorldHint;

    private McpToolAnnotations(Builder builder) {
        this.readOnlyHint = builder.readOnlyHint;
        this.destructiveHint = builder.destructiveHint;
        this.idempotentHint = builder.idempotentHint;
        this.openWorldHint = builder.openWorldHint;
    }

    /**
     * Returns the instance in which every hint is absent, which is what a server sending no {@code annotations} member
     * means.
     *
     * @return the empty annotations (never null)
     */
    public static McpToolAnnotations empty() {
        return EMPTY;
    }

    /**
     * Creates a builder. Hints left unset stay absent, which is not the same as set to {@code false}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return {@code readOnlyHint} as the server sent it, empty when it sent none
     */
    public Optional<Boolean> getReadOnlyHint() {
        return Optional.ofNullable(readOnlyHint);
    }

    /**
     * @return {@code destructiveHint} as the server sent it, empty when it sent none
     */
    public Optional<Boolean> getDestructiveHint() {
        return Optional.ofNullable(destructiveHint);
    }

    /**
     * @return {@code idempotentHint} as the server sent it, empty when it sent none
     */
    public Optional<Boolean> getIdempotentHint() {
        return Optional.ofNullable(idempotentHint);
    }

    /**
     * @return {@code openWorldHint} as the server sent it, empty when it sent none
     */
    public Optional<Boolean> getOpenWorldHint() {
        return Optional.ofNullable(openWorldHint);
    }

    /**
     * @return whether the tool claims to be read-only, defaulting to {@code false} when the hint is absent
     */
    public boolean isReadOnly() {
        return readOnlyHint != null && readOnlyHint;
    }

    /**
     * @return whether the tool may destroy existing state, defaulting to {@code true} when the hint is absent
     */
    public boolean isDestructive() {
        return destructiveHint == null || destructiveHint;
    }

    /**
     * @return whether repeating a call is claimed to be equivalent to making it once, defaulting to {@code false} when
     *         the hint is absent
     */
    public boolean isIdempotent() {
        return idempotentHint != null && idempotentHint;
    }

    /**
     * @return whether the tool interacts with an open-ended external world, defaulting to {@code true} when the hint is
     *         absent
     */
    public boolean isOpenWorld() {
        return openWorldHint == null || openWorldHint;
    }

    /**
     * @return true when the server sent no hint at all, so every accessor above is answering with the spec default
     */
    public boolean isEmpty() {
        return readOnlyHint == null && destructiveHint == null && idempotentHint == null && openWorldHint == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpToolAnnotations other)) {
            return false;
        }
        return Objects.equals(readOnlyHint, other.readOnlyHint)
                && Objects.equals(destructiveHint, other.destructiveHint)
                && Objects.equals(idempotentHint, other.idempotentHint)
                && Objects.equals(openWorldHint, other.openWorldHint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
    }

    @Override
    public String toString() {
        return "McpToolAnnotations{readOnlyHint=" + readOnlyHint + ", destructiveHint=" + destructiveHint
                + ", idempotentHint=" + idempotentHint + ", openWorldHint=" + openWorldHint + '}';
    }

    /** Builder for {@link McpToolAnnotations}; a hint never set stays absent. */
    public static final class Builder {

        private Boolean readOnlyHint;
        private Boolean destructiveHint;
        private Boolean idempotentHint;
        private Boolean openWorldHint;

        private Builder() {
        }

        /**
         * @param readOnlyHint
         *            the hint, or null to leave it absent
         * @return this builder
         */
        public Builder readOnlyHint(Boolean readOnlyHint) {
            this.readOnlyHint = readOnlyHint;
            return this;
        }

        /**
         * @param destructiveHint
         *            the hint, or null to leave it absent
         * @return this builder
         */
        public Builder destructiveHint(Boolean destructiveHint) {
            this.destructiveHint = destructiveHint;
            return this;
        }

        /**
         * @param idempotentHint
         *            the hint, or null to leave it absent
         * @return this builder
         */
        public Builder idempotentHint(Boolean idempotentHint) {
            this.idempotentHint = idempotentHint;
            return this;
        }

        /**
         * @param openWorldHint
         *            the hint, or null to leave it absent
         * @return this builder
         */
        public Builder openWorldHint(Boolean openWorldHint) {
            this.openWorldHint = openWorldHint;
            return this;
        }

        /**
         * @return the built annotations
         */
        public McpToolAnnotations build() {
            return new McpToolAnnotations(this);
        }
    }
}
