package at.aimon.core.knowledge.wiki;

import java.util.List;

/**
 * Pluggable strategy that turns a user question + a list of supporting wiki pages into a synthesized
 * {@link Answer}. This is the LLM-driven half of the "search → load → synthesize → optionally file back" query
 * flow described in {@code docs/references/llm-wiki.md}.
 *
 * <p>
 * The contract is intentionally narrow: the strategy receives the {@link AnswerRequest} (question + cost knobs)
 * and a pre-loaded list of context pages (already capped at {@code maxContextPages} by the storage layer), and
 * returns a single {@link Answer}. It does not perform the search itself — keeping search and synthesis as
 * separate concerns means the same strategy can be reused regardless of which {@link WikiSearchStrategy} found
 * the candidates.
 *
 * <p>
 * <b>Never-throw contract</b>: implementations must never throw on transient LLM or parse failures. They should
 * log at WARN and return an {@link Answer} containing whatever fallback content makes sense — typically a
 * deterministic concatenation of the supporting page summaries. The storage layer wraps any thrown
 * {@link RuntimeException} into a runtime exception so contract violations surface loudly rather than silently.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 */
public interface WikiAnswerStrategy {

    /**
     * Synthesizes an answer to the given request from the supplied context pages.
     *
     * @param scope
     *            the wiki scope (must not be null) — used by implementations for attribution / observability
     * @param request
     *            the answer request (must not be null)
     * @param contextPages
     *            the supporting wiki pages already loaded by the storage layer (must not be null; may be empty)
     * @return the synthesized answer (never null)
     */
    Answer answer(WikiScope scope, AnswerRequest request, List<WikiPage> contextPages);
}
