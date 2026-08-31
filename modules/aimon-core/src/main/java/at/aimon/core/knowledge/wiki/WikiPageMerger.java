package at.aimon.core.knowledge.wiki;

/**
 * Reconciles a newly extracted {@link GeneratedPage} with the existing on-disk {@link WikiPage} that occupies the
 * same target file name, producing the merged page that should replace the existing one.
 *
 * <p>
 * Where {@link WikiPageGenerator#extractPages} decides what pages a single source document should produce,
 * {@code WikiPageMerger} decides what to do when one of those pages collides with a page that is already in the
 * wiki — typically because a previous ingest already extracted the same conceptual entity or concept from a
 * different source document.
 *
 * <p>
 * Implementations are responsible for:
 * <ul>
 * <li>Combining the two markdown bodies without losing information present in either side
 * <li>Preserving and updating frontmatter — in particular {@code derived_from} must accumulate the new source
 * <li>Preserving cross-references ({@code [[wiki-link]]}) from the existing page
 * <li>Resolving contradictions or duplicate sections
 * </ul>
 *
 * <p>
 * <b>Never-throw contract</b>: implementations must never throw. On internal failure (LLM unavailable, parse error,
 * etc.) they should return a deterministic fallback that preserves the existing content rather than dropping it.
 * The {@link DefaultWikiKnowledgeBase} defensively wraps any thrown {@link RuntimeException} into a write failure
 * for the surrounding ingest loop, so a misbehaving merger surfaces as an ingest error rather than silent data
 * loss — but the recommended pattern is for implementations to do their own catching and return a fallback.
 *
 * <p>
 * <b>Determinism</b>: implementations should be free of side effects other than calls to their injected LLM client.
 * They must not touch the wiki VFS directly — the storage layer owns reads and writes.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 *
 * @see DefaultWikiKnowledgeBase
 * @see GeneratedPage.UpdateStrategy#MERGE
 */
public interface WikiPageMerger {

    /**
     * Merges {@code incoming} into {@code existing}, returning a new {@link GeneratedPage} whose content replaces
     * the existing on-disk page.
     *
     * <p>
     * The returned page's {@link GeneratedPage#getStrategy()} should be {@link GeneratedPage.UpdateStrategy#REPLACE}
     * — by the time we have called this method we know the merge has happened and the storage layer should
     * unconditionally write the result. The returned page's {@link GeneratedPage#getType()} and
     * {@link GeneratedPage#getSlug()} should match {@code incoming} so the file name remains stable.
     *
     * <p>
     * The returned {@link GeneratedPage#getDerivedFrom()} list should be the union of {@code existing.getDerivedFrom()}
     * and {@code incoming.getDerivedFrom()}, preserving order and de-duplicating exact matches. This is the only
     * way the wiki tracks which sources have contributed to a long-lived entity or concept page.
     *
     * @param scope
     *            the wiki scope (must not be null) — implementations may use it for attribution/observability
     * @param existing
     *            the page already on disk (must not be null)
     * @param incoming
     *            the freshly extracted page (must not be null)
     * @return the merged page (never null, never empty)
     */
    GeneratedPage merge(WikiScope scope, WikiPage existing, GeneratedPage incoming);
}
