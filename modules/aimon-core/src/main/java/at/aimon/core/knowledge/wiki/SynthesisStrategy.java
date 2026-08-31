package at.aimon.core.knowledge.wiki;

import java.util.List;

/**
 * Pluggable strategy that turns the existing entity / concept pages of a wiki scope into higher-level
 * {@link WikiPageType#OVERVIEW} and {@link WikiPageType#SYNTHESIS} pages.
 *
 * <p>
 * Where {@link WikiPageGenerator#extractPages} produces wiki pages from a single raw source document,
 * {@code SynthesisStrategy} produces pages from a set of <i>existing wiki pages</i>. This is the second-pass
 * workflow described in {@code docs/references/llm-wiki.md}: after enough entities and concepts have
 * accumulated, the LLM is asked to step back and write topic overviews and cross-cluster syntheses.
 *
 * <p>
 * The returned pages are {@link GeneratedPage} instances that the storage layer will write to disk using the
 * usual file-name and reconciliation rules — typically with strategy {@link GeneratedPage.UpdateStrategy#CREATE}
 * (idempotent re-runs) or {@link GeneratedPage.UpdateStrategy#REPLACE} when the caller explicitly asked for an
 * overwrite via {@link SynthesizeOptions#isOverwrite()}.
 *
 * <p>
 * <b>Cost contract</b>: implementations must respect {@link SynthesizeOptions#getMaxLlmCalls()} as a hard cap
 * — once reached the strategy should stop and return whatever pages it has produced so far. Callers can detect
 * truncation via {@link SynthesizeResult#getLlmCallCount()} and {@link SynthesizeResult#getSkippedCount()}.
 *
 * <p>
 * <b>Never-throw contract</b>: implementations must never throw. Per-cluster failures should be recorded as
 * error strings on the surrounding {@link SynthesizeResult} (the storage layer manages that aggregation), and
 * the strategy should keep going with the remaining clusters.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 */
public interface SynthesisStrategy {

    /**
     * Synthesizes overview / synthesis pages from the given source pages.
     *
     * <p>
     * The {@code sourcePages} list contains the wiki pages the strategy is allowed to draw from. Callers
     * typically pre-filter this list to exclude existing OVERVIEW and SYNTHESIS pages so the strategy does not
     * recursively synthesize its own outputs.
     *
     * @param scope
     *            the wiki scope (must not be null) — used by implementations for attribution / observability
     * @param sourcePages
     *            the existing wiki pages available as raw material (must not be null; may be empty)
     * @param options
     *            the synthesis options controlling fan-out and cost (must not be null)
     * @return the list of generated pages, never null. May be empty when there is nothing to synthesize.
     */
    List<GeneratedPage> synthesize(WikiScope scope, List<WikiPage> sourcePages, SynthesizeOptions options);

    /**
     * Returns the number of LLM calls the most recent {@link #synthesize} invocation issued. Implementations
     * that don't talk to an LLM may return 0. The default returns 0 to keep simple deterministic strategies
     * (e.g., template-based fallbacks) free of mandatory bookkeeping.
     *
     * <p>
     * This is intentionally a separate method rather than a return-value field so the strategy can stay
     * stateless across calls — implementations are allowed to track call count per-thread (e.g., via a
     * {@link ThreadLocal}) or to reset it on entry.
     *
     * @return the number of LLM calls issued by the most recent synthesize invocation
     */
    default int getLastCallCount() {
        return 0;
    }
}
