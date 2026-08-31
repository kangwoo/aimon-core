package at.aimon.core.knowledge.wiki;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable report from a wiki health check (lint) operation.
 *
 * <p>
 * Contains a list of issues found during the lint check, along with a health score and statistics.
 *
 * @see WikiKnowledgeBaseAdmin#lint(WikiScope)
 */
public final class LintReport {

    /**
     * Severity levels for lint issues.
     */
    public enum Severity {
        /** Informational suggestion. */
        INFO,
        /** Potential problem that should be addressed. */
        WARNING,
        /** Significant issue that needs attention. */
        ERROR
    }

    /**
     * An immutable representation of a single lint issue.
     */
    public static final class Issue {

        private final Severity severity;
        private final String pagePath;
        private final String message;

        /**
         * Creates a lint issue.
         *
         * @param severity
         *            the severity level (must not be null)
         * @param pagePath
         *            the affected page path, or null for wiki-wide issues
         * @param message
         *            the issue description (must not be null)
         */
        public Issue(Severity severity, String pagePath, String message) {
            this.severity = Objects.requireNonNull(severity, "severity must not be null");
            this.pagePath = pagePath;
            this.message = Objects.requireNonNull(message, "message must not be null");
        }

        /** Returns the severity level. */
        public Severity getSeverity() {
            return severity;
        }

        /** Returns the affected page path, or {@code null} for wiki-wide issues. */
        public String getPagePath() {
            return pagePath;
        }

        /** Returns the issue description. */
        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return severity + ": " + (pagePath != null ? pagePath + " - " : "") + message;
        }
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<Issue> issues;
    private final int checkedPageCount;
    private final Instant checkedAt;

    private LintReport(Builder builder) {
        this.issues = builder.issues == null ? Collections.emptyList() : Collections.unmodifiableList(builder.issues);
        if (builder.checkedPageCount < 0) {
            throw new IllegalArgumentException("checkedPageCount must be >= 0, got: " + builder.checkedPageCount);
        }
        this.checkedPageCount = builder.checkedPageCount;
        this.checkedAt = Objects.requireNonNull(builder.checkedAt, "checkedAt must not be null");
    }

    /**
     * Returns the lint issues found.
     *
     * @return an unmodifiable list of issues (never null; empty means healthy)
     */
    public List<Issue> getIssues() {
        return issues;
    }

    /**
     * Returns the number of pages checked.
     *
     * @return the checked page count (>= 0)
     */
    public int getCheckedPageCount() {
        return checkedPageCount;
    }

    /**
     * Returns the time the lint check was performed.
     *
     * @return the check time (never null)
     */
    public Instant getCheckedAt() {
        return checkedAt;
    }

    /**
     * Returns {@code true} if no issues were found.
     *
     * @return true if the wiki is healthy
     */
    public boolean isHealthy() {
        return issues.isEmpty();
    }

    /**
     * Returns the count of issues with the given severity.
     *
     * @param severity
     *            the severity to count
     * @return the number of issues with that severity
     */
    public long countBySeverity(Severity severity) {
        return issues.stream().filter(i -> i.getSeverity() == severity).count();
    }

    @Override
    public String toString() {
        return "LintReport{issues=" + issues.size() + ", pages=" + checkedPageCount + ", checkedAt=" + checkedAt + '}';
    }

    /**
     * Builder for {@link LintReport}.
     */
    public static final class Builder {

        private List<Issue> issues;
        private int checkedPageCount;
        private Instant checkedAt;

        private Builder() {
        }

        /**
         * Sets the lint issues.
         *
         * @param issues
         *            the issues found
         * @return this builder
         */
        public Builder issues(List<Issue> issues) {
            this.issues = issues;
            return this;
        }

        /**
         * Sets the number of pages checked.
         *
         * @param checkedPageCount
         *            the page count
         * @return this builder
         */
        public Builder checkedPageCount(int checkedPageCount) {
            this.checkedPageCount = checkedPageCount;
            return this;
        }

        /**
         * Sets the time the lint check was performed.
         *
         * @param checkedAt
         *            the check time
         * @return this builder
         */
        public Builder checkedAt(Instant checkedAt) {
            this.checkedAt = checkedAt;
            return this;
        }

        /**
         * Builds the lint report.
         *
         * @return a new {@link LintReport} instance
         * @throws NullPointerException
         *             if checkedAt is null
         * @throws IllegalArgumentException
         *             if checkedPageCount is negative
         */
        public LintReport build() {
            return new LintReport(this);
        }
    }
}
