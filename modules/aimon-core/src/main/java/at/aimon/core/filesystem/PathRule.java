package at.aimon.core.filesystem;

import java.util.Objects;

/**
 * An access rule for one root-anchored directory prefix of a filesystem (execution-environment design §9.2): hide it
 * entirely ({@link Access#DENY}) or allow reads only ({@link Access#READ_ONLY}). The prefix is matched against the
 * {@linkplain VfsPaths#resolveUnder base-relative, normalised} path, on whole segments and ignoring case, so
 * {@code a/../.aimon/x} and {@code .AIMON/x} are caught and {@code .aimon2/x} is not. Immutable.
 */
public final class PathRule {

    /** What a rule allows under its prefix. */
    public enum Access {
        /** Invisible: not listed, {@code exists} is false, every read and write is refused. */
        DENY,
        /** Readable, but nothing may be written, deleted, moved or copied into it. */
        READ_ONLY
    }

    private final String prefix;
    private final Access access;

    private PathRule(Builder builder) {
        Objects.requireNonNull(builder.prefix, "prefix must not be null");
        final String normalized = VfsPaths.normalizeRelative(builder.prefix);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("prefix must be a non-empty relative directory, got: " + builder.prefix);
        }
        this.prefix = normalized;
        this.access = Objects.requireNonNull(builder.access, "access must not be null");
    }

    /**
     * @param prefix
     *            the root-anchored directory prefix
     * @return a rule that hides the prefix
     */
    public static PathRule deny(String prefix) {
        return builder().prefix(prefix).access(Access.DENY).build();
    }

    /**
     * @param prefix
     *            the root-anchored directory prefix
     * @return a rule that allows only reads under the prefix
     */
    public static PathRule readOnly(String prefix) {
        return builder().prefix(prefix).access(Access.READ_ONLY).build();
    }

    /** @return the normalised, root-relative directory prefix (no leading or trailing slash) */
    public String getPrefix() {
        return prefix;
    }

    /** @return what the rule allows */
    public Access getAccess() {
        return access;
    }

    /**
     * @param rootRelative
     *            a normalised root-relative path
     * @return whether the path is the prefix directory itself or lies under it, ignoring case (a case-insensitive
     *         store names one directory with both spellings)
     */
    public boolean covers(String rootRelative) {
        return VfsPaths.isUnderIgnoreCase(rootRelative, prefix);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathRule that)) {
            return false;
        }
        return prefix.equals(that.prefix) && access == that.access;
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, access);
    }

    @Override
    public String toString() {
        return access + " " + prefix + "/";
    }

    /** Builder for {@link PathRule}. */
    public static final class Builder {
        private String prefix;
        private Access access;

        private Builder() {
        }

        /**
         * @param prefix
         *            the root-anchored directory prefix (required)
         * @return this builder
         */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * @param access
         *            what the rule allows (required)
         * @return this builder
         */
        public Builder access(Access access) {
            this.access = access;
            return this;
        }

        /** @return the rule */
        public PathRule build() {
            return new PathRule(this);
        }
    }
}
