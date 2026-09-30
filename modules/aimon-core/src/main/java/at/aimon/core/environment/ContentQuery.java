package at.aimon.core.environment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A {@code Grep} query in environment terms: the regular expression, where to look, which files, and how much context
 * to return around each match. Output shaping (mode, pagination, line numbers) stays with the tool.
 */
public final class ContentQuery {

    private final String pattern;
    private final String path;
    private final List<String> extensions;
    private final String glob;
    private final boolean caseInsensitive;
    private final int beforeContext;
    private final int afterContext;
    private final boolean multiline;
    private final BooleanSupplier cancellation;

    private ContentQuery(Builder builder) {
        this.pattern = Objects.requireNonNull(builder.pattern, "pattern must not be null");
        this.path = Objects.requireNonNull(builder.path, "path must not be null");
        this.extensions = List.copyOf(builder.extensions);
        this.glob = builder.glob;
        this.caseInsensitive = builder.caseInsensitive;
        this.beforeContext = builder.beforeContext;
        this.afterContext = builder.afterContext;
        this.multiline = builder.multiline;
        this.cancellation = builder.cancellation;
    }

    /** @return the regular expression */
    public String getPattern() {
        return pattern;
    }

    /** @return the file or directory to search, as the tool received it */
    public String getPath() {
        return path;
    }

    /** @return file-name extensions to keep ({@code .java}); empty keeps every file */
    public List<String> getExtensions() {
        return extensions;
    }

    /** @return a file-name glob to keep, if any */
    public Optional<String> getGlob() {
        return Optional.ofNullable(glob);
    }

    /** @return whether matching ignores case */
    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    /** @return the number of lines to return before each match */
    public int getBeforeContext() {
        return beforeContext;
    }

    /** @return the number of lines to return after each match */
    public int getAfterContext() {
        return afterContext;
    }

    /** @return whether {@code .} matches newlines and a match may span lines */
    public boolean isMultiline() {
        return multiline;
    }

    /**
     * Whether the execution that asked has been cancelled. A search that runs for a while (a process, a remote call)
     * polls this and gives up — throwing, like any other failure — once it turns true.
     *
     * @return {@code true} once the caller no longer wants the answer
     */
    public boolean isCancelled() {
        return cancellation.getAsBoolean();
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ContentQuery}. */
    public static final class Builder {
        private String pattern;
        private String path;
        private List<String> extensions = List.of();
        private String glob;
        private boolean caseInsensitive;
        private int beforeContext;
        private int afterContext;
        private boolean multiline;
        private BooleanSupplier cancellation = () -> false;

        private Builder() {
        }

        /**
         * @param pattern
         *            the regular expression (required)
         * @return this builder
         */
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        /**
         * @param path
         *            the file or directory to search (required)
         * @return this builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * @param extensions
         *            file-name extensions to keep
         * @return this builder
         */
        public Builder extensions(List<String> extensions) {
            this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
            return this;
        }

        /**
         * @param glob
         *            a file-name glob, or null
         * @return this builder
         */
        public Builder glob(String glob) {
            this.glob = glob;
            return this;
        }

        /**
         * @param caseInsensitive
         *            whether matching ignores case
         * @return this builder
         */
        public Builder caseInsensitive(boolean caseInsensitive) {
            this.caseInsensitive = caseInsensitive;
            return this;
        }

        /**
         * @param beforeContext
         *            lines before each match
         * @return this builder
         */
        public Builder beforeContext(int beforeContext) {
            this.beforeContext = beforeContext;
            return this;
        }

        /**
         * @param afterContext
         *            lines after each match
         * @return this builder
         */
        public Builder afterContext(int afterContext) {
            this.afterContext = afterContext;
            return this;
        }

        /**
         * @param multiline
         *            whether a match may span lines
         * @return this builder
         */
        public Builder multiline(boolean multiline) {
            this.multiline = multiline;
            return this;
        }

        /**
         * @param cancellation
         *            polled while the search runs; {@code true} means the caller no longer wants the answer (defaults
         *            to
         *            never)
         * @return this builder
         */
        public Builder cancellation(BooleanSupplier cancellation) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
            return this;
        }

        /** @return the query */
        public ContentQuery build() {
            return new ContentQuery(this);
        }
    }
}
