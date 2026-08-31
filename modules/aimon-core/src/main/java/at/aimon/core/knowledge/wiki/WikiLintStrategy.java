package at.aimon.core.knowledge.wiki;

import java.util.List;

/**
 * Pluggable strategy for semantic wiki lint — the checks that require reading the actual text of every page
 * and reasoning about it, rather than just inspecting the link graph.
 *
 * <p>
 * The structural lint rules already implemented directly in {@link DefaultWikiKnowledgeBase#lint(WikiScope)}
 * (broken links, orphan pages, duplicate titles, missing tags) are cheap and deterministic. They run on every
 * {@code lint()} call regardless of whether a {@code WikiLintStrategy} is wired.
 *
 * <p>
 * {@code WikiLintStrategy} exists for the harder checks described in {@code docs/references/llm-wiki.md}:
 * <ul>
 * <li><b>Contradictions between pages</b> — two pages stating incompatible facts about the same subject
 * <li><b>Stale claims</b> — pages whose content has been superseded by newer sources
 * <li><b>Missing concept pages</b> — important concepts referenced in page bodies that have no dedicated page
 * <li><b>Data gaps</b> — questions the wiki cannot answer well because the supporting material is thin
 * </ul>
 * Detecting these reliably requires an LLM reading every page, so the strategy is opt-in: callers wire it into
 * {@link DefaultWikiKnowledgeBase} when they want the extra checks and accept the associated LLM cost.
 *
 * <p>
 * The strategy returns additional {@link LintReport.Issue}s which the storage layer merges with the structural
 * findings. It does not modify anything — lint is read-only.
 *
 * <p>
 * <b>Never-throw contract</b>: implementations must never throw on transient LLM or parse failures. They
 * should log at WARN and return whatever issues they managed to find (possibly empty). The storage layer
 * wraps any thrown {@link RuntimeException} into a runtime exception so contract violations surface loudly.
 *
 * <p>
 * <b>Cost contract</b>: implementations must be mindful of LLM cost. A wiki with hundreds of pages should not
 * spend hundreds of LLM calls on a single lint pass — strategies are expected to cap their own fan-out and
 * truncate input when necessary.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 */
public interface WikiLintStrategy {

    /**
     * Runs the semantic lint pass over the given wiki pages.
     *
     * @param scope
     *            the wiki scope (must not be null) — used by implementations for attribution / observability
     * @param pages
     *            all pages in the scope (must not be null; may be empty). The storage layer has already loaded
     *            and parsed them so the strategy doesn't need to hit the VFS.
     * @return the additional lint issues found by semantic analysis (never null, may be empty)
     */
    List<LintReport.Issue> lint(WikiScope scope, List<WikiPage> pages);

    /**
     * Returns the number of LLM calls the most recent {@link #lint} invocation issued. Implementations that
     * don't talk to an LLM may return 0. The default returns 0 so simple deterministic strategies (e.g., a
     * template-based placeholder) stay free of mandatory bookkeeping.
     */
    default int getLastCallCount() {
        return 0;
    }
}
