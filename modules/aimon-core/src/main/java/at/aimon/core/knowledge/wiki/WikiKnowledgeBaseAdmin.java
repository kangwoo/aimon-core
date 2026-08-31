package at.aimon.core.knowledge.wiki;

/**
 * Administrative and diagnostic operations for a wiki knowledge base.
 *
 * <p>
 * This interface is intentionally separate from {@link WikiKnowledgeBase} to follow the Interface Segregation
 * Principle. Core runtime callers (agents, search pipelines) depend only on {@link WikiKnowledgeBase}; operational
 * tooling that needs lint checks or audit logs depends on this interface.
 *
 * <p>
 * {@code DefaultWikiKnowledgeBase} implements both {@link WikiKnowledgeBase} and {@link WikiKnowledgeBaseAdmin},
 * allowing a single instance to serve both roles when needed.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@link #lint(WikiScope)} — validates the integrity of ingested pages within a scope and returns a structured
 * report of any issues found (broken links, missing metadata, malformed content, etc.)
 * <li>{@link #getLog(WikiScope, int)} — returns a bounded audit log of recent ingestion and administrative events for
 * the given scope
 * </ul>
 *
 * @see WikiKnowledgeBase
 * @see WikiScope
 * @see LintReport
 * @see WikiLog
 */
public interface WikiKnowledgeBaseAdmin {

    /**
     * Validates the integrity of wiki pages within the given scope.
     *
     * <p>
     * Inspects all indexed pages for structural problems such as broken links, missing required metadata, or malformed
     * content. Returns a {@link LintReport} summarising any issues found. An empty report indicates no issues.
     *
     * @param scope
     *            the wiki scope to lint (must not be null)
     * @return a lint report for the scope (never null)
     * @throws NullPointerException
     *             if scope is null
     */
    LintReport lint(WikiScope scope);

    /**
     * Returns a bounded audit log of recent events for the given scope.
     *
     * <p>
     * Events include ingestion runs, re-ingestion, and administrative operations. Entries are ordered from most recent
     * to oldest. If fewer than {@code limit} events have occurred, all available entries are returned.
     *
     * @param scope
     *            the wiki scope whose log is requested (must not be null)
     * @param limit
     *            the maximum number of log entries to return (must be &gt; 0)
     * @return the audit log for the scope (never null)
     * @throws NullPointerException
     *             if scope is null
     * @throws IllegalArgumentException
     *             if limit is not positive
     */
    WikiLog getLog(WikiScope scope, int limit);

    /**
     * Migrates the YAML frontmatter of every wiki page in the given scope to the current schema, adding the
     * {@code type:} field when it is missing. The type is inferred from the file-name prefix
     * (e.g., {@code summary-foo.md} → {@code type: summary}); pages whose name does not match any recognized
     * prefix default to {@link WikiPageType#SUMMARY}.
     *
     * <p>
     * The migration is intentionally idempotent — re-running it on a fully-migrated wiki returns a result with
     * {@code migratedCount=0}. Pages with malformed frontmatter or no frontmatter at all are left untouched and
     * counted under {@link MigrationResult#getSkippedCount()}.
     *
     * <p>
     * Implementations that don't support migration may return {@link MigrationResult#empty()} from the default,
     * which is what the default method does. {@code DefaultWikiKnowledgeBase} provides a real implementation.
     *
     * @param scope
     *            the wiki scope to migrate (must not be null)
     * @return the migration result with statistics (never null)
     * @throws NullPointerException
     *             if scope is null
     */
    default MigrationResult migrateFrontmatter(WikiScope scope) {
        return MigrationResult.empty();
    }
}
