package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Index-first search strategy implementing the pattern described in {@code docs/references/llm-wiki.md}:
 * "When answering a query, the LLM reads the index first to find relevant pages, then drills into them."
 *
 * <p>
 * Algorithm:
 * <ol>
 * <li>Read and parse {@code index.md}. On missing/empty/unparseable index → delegate to
 * {@link FullScanSearchStrategy} so cold-start and pre-ingest searches never fail worse than the brute-force
 * baseline.
 * <li>Filter each index entry by path glob and tag filter <i>against the index itself</i> — no page I/O. Tags are
 * carried in the index format as a trailing {@code {tag1, tag2}} suffix (see {@link IndexParser}), so this stage
 * honours {@link WikiSearchQuery#getTags()} without reopening a single page.
 * <li>Score each remaining entry by matching query tokens against its title (+{@value #TITLE_TOKEN_WEIGHT} per
 * hit) and summary (+{@value #SUMMARY_TOKEN_WEIGHT} per hit), plus a {@value #PHRASE_BONUS}-point bonus for exact
 * phrase matches when the query has multiple tokens. Entries with score 0 are discarded.
 * <li>Take the top {@code maxResults * }{@value #DRILL_DOWN_FACTOR} candidates (minimum {@value #MIN_DRILL_DOWN})
 * and drill down into their full content for keyword-count scoring.
 * <li>Combine scores as {@code indexScore * }{@value #INDEX_WEIGHT}{@code + contentScore * }{@value #CONTENT_WEIGHT}
 * and return the top {@code maxResults}.
 * </ol>
 *
 * <p>
 * Rationale: index entries are LLM-compressed one-line summaries of the underlying pages, so matching against them
 * carries a stronger semantic signal per byte than scanning raw page bodies. Drilling down into only the top few
 * candidates keeps VFS I/O bounded even for wikis with hundreds of pages. The combined scoring ensures that a page
 * whose content deeply matches the query wins over one whose summary happens to mention it once.
 *
 * <p>
 * Thread-safe and stateless.
 *
 * @see FullScanSearchStrategy
 * @see IndexParser
 */
public final class IndexFirstSearchStrategy implements WikiSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(IndexFirstSearchStrategy.class);

    /** Multiplier for how many candidates to drill down into, relative to the requested maxResults. */
    private static final int DRILL_DOWN_FACTOR = 3;

    /** Minimum number of candidates to drill down into, regardless of maxResults. */
    private static final int MIN_DRILL_DOWN = 5;

    /** Weight applied to the index-level score in the combined ranking. */
    private static final double INDEX_WEIGHT = 0.4;

    /** Weight applied to the full-content score in the combined ranking. */
    private static final double CONTENT_WEIGHT = 0.6;

    /** Points awarded per query token found in an entry's title. */
    private static final int TITLE_TOKEN_WEIGHT = 3;

    /** Points awarded per query token found in an entry's summary. */
    private static final int SUMMARY_TOKEN_WEIGHT = 1;

    /** Bonus points when the entire query phrase appears in title or summary (multi-token queries only). */
    private static final int PHRASE_BONUS = 2;

    private final FullScanSearchStrategy fallback;

    /**
     * Creates an index-first strategy with a default {@link FullScanSearchStrategy} fallback.
     */
    public IndexFirstSearchStrategy() {
        this(new FullScanSearchStrategy());
    }

    /**
     * Creates an index-first strategy with a custom fallback. Primarily intended for testing.
     *
     * @param fallback
     *            the strategy used when {@code index.md} is missing or unparseable (must not be null)
     */
    IndexFirstSearchStrategy(FullScanSearchStrategy fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public List<WikiPage> search(WikiSearchQuery query, WikiSearchContext ctx) {
        return searchWithScores(query, ctx).stream().map(WikiSearchResult::getPage).toList();
    }

    @Override
    public List<WikiSearchResult> searchWithScores(WikiSearchQuery query, WikiSearchContext ctx) {
        final VirtualFileSystem fs = ctx.getFileSystem();
        final String indexPath = ctx.getIndexPath();

        try {
            if (!fs.exists(indexPath)) {
                log.debug("index.md missing for scope {}, falling back to full scan", ctx.getScope());
                return fallback.searchWithScores(query, ctx);
            }

            final String indexContent;
            try {
                indexContent = WikiIo.readContent(fs, indexPath);
            } catch (Exception e) {
                log.warn("Failed to read index.md for scope {}, falling back to full scan: {}", ctx.getScope(),
                        e.getMessage());
                return fallback.searchWithScores(query, ctx);
            }

            final List<IndexParser.IndexEntry> entries = IndexParser.parse(indexContent);
            if (entries.isEmpty()) {
                log.debug("index.md for scope {} parsed to 0 entries, falling back to full scan", ctx.getScope());
                return fallback.searchWithScores(query, ctx);
            }

            final String queryLower = query.getQueryText().toLowerCase();
            final List<String> tokens = tokenize(queryLower);
            final List<String> pathPatterns = query.getPagePathPatterns();
            final List<String> tagFilter = query.getTags();

            // Stage 1: index-only scoring (no page I/O). Path glob, tag filter, type filter, and token scoring
            // all happen here against the index entry — the whole point of the index-first pattern is that
            // candidates are selected without opening a single page. The type filter uses
            // WikiPageType.fromFileName so we don't need to read the page body to know its type.
            final List<IndexCandidate> indexCandidates = new ArrayList<>();
            for (IndexParser.IndexEntry entry : entries) {
                if (!pathPatterns.isEmpty() && !WikiIo.matchesFilePatterns(entry.getPath(), pathPatterns)) {
                    continue;
                }
                if (!tagFilter.isEmpty() && !entry.getTags().containsAll(tagFilter)) {
                    continue;
                }
                if (!query.matchesType(WikiPageType.fromFileName(WikiIo.extractFileName(entry.getPath())))) {
                    continue;
                }
                final int score = scoreIndexEntry(entry, queryLower, tokens);
                if (score > 0) {
                    indexCandidates.add(new IndexCandidate(entry, score));
                }
            }

            if (indexCandidates.isEmpty()) {
                log.debug("No index-level matches for query '{}' in scope {}", query.getQueryText(), ctx.getScope());
                return Collections.emptyList();
            }

            // Stage 2: drill down into top candidates for full-content scoring.
            final int drillDownSize = Math.max(MIN_DRILL_DOWN, query.getMaxResults() * DRILL_DOWN_FACTOR);
            final List<IndexCandidate> drillCandidates = indexCandidates.stream()
                    .sorted(Comparator.comparingInt((IndexCandidate c) -> c.score).reversed()).limit(drillDownSize)
                    .toList();

            final List<Ranked> ranked = new ArrayList<>();
            for (IndexCandidate cand : drillCandidates) {
                final String path = cand.entry.getPath();
                try {
                    if (!fs.exists(path)) {
                        // Stale index — the page was removed but index still references it. Lint reports this.
                        log.debug("Index references missing page (stale): {}", path);
                        continue;
                    }
                    final String content = WikiIo.readContent(fs, path);
                    if (content.isEmpty()) {
                        continue;
                    }

                    final WikiPage page = WikiIo.parseWikiPage(path, content);

                    final int contentScore = WikiIo.countOccurrences(content.toLowerCase(), queryLower);

                    // Keep the index contribution even when contentScore is 0: tokens may have matched title only,
                    // which is still a valid hit under the doc's index-first philosophy.
                    final double combined = INDEX_WEIGHT * cand.score + CONTENT_WEIGHT * contentScore;
                    if (combined > 0) {
                        ranked.add(new Ranked(page, combined));
                    }
                } catch (Exception e) {
                    log.warn("Failed to drill into candidate {} during index-first search: {}", path, e.getMessage());
                }
            }

            return ranked.stream().sorted(Comparator.comparingDouble((Ranked r) -> r.score).reversed())
                    .limit(query.getMaxResults()).map(r -> new WikiSearchResult(r.page, r.score)).toList();

        } catch (Exception e) {
            log.error("Unexpected error in index-first search for scope {}: {}", ctx.getScope(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static int scoreIndexEntry(IndexParser.IndexEntry entry, String queryLower, List<String> tokens) {
        final String titleLower = entry.getTitle().toLowerCase();
        final String summaryLower = entry.getSummary() == null ? "" : entry.getSummary().toLowerCase();

        int score = 0;
        for (String token : tokens) {
            if (titleLower.contains(token)) {
                score += TITLE_TOKEN_WEIGHT;
            }
            if (summaryLower.contains(token)) {
                score += SUMMARY_TOKEN_WEIGHT;
            }
        }
        // Multi-token phrase bonus: reward entries whose title/summary contain the query verbatim.
        if (tokens.size() > 1 && (titleLower.contains(queryLower) || summaryLower.contains(queryLower))) {
            score += PHRASE_BONUS;
        }
        return score;
    }

    private static List<String> tokenize(String text) {
        final String[] parts = text.split("\\s+");
        final List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static final class IndexCandidate {

        final IndexParser.IndexEntry entry;
        final int score;

        IndexCandidate(IndexParser.IndexEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private static final class Ranked {

        final WikiPage page;
        final double score;

        Ranked(WikiPage page, double score) {
            this.page = page;
            this.score = score;
        }
    }
}
