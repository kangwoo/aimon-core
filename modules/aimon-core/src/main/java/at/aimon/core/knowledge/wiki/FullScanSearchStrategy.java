package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Reference full-scan {@link WikiSearchStrategy}: iterates every page under a scope's {@code pages/} directory and
 * ranks results by literal keyword match count.
 *
 * <p>
 * This is the original {@link DefaultWikiKnowledgeBase} search behavior, preserved here so it remains available as a
 * cold-start fallback for {@link IndexFirstSearchStrategy} (when the index is missing or unreadable) and as a
 * reference implementation for very small wikis where the index-first drill-down offers no benefit.
 *
 * <p>
 * Thread-safe and stateless.
 */
public final class FullScanSearchStrategy implements WikiSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(FullScanSearchStrategy.class);

    @Override
    public List<WikiPage> search(WikiSearchQuery query, WikiSearchContext ctx) {
        return searchWithScores(query, ctx).stream().map(WikiSearchResult::getPage).toList();
    }

    @Override
    public List<WikiSearchResult> searchWithScores(WikiSearchQuery query, WikiSearchContext ctx) {
        final VirtualFileSystem fs = ctx.getFileSystem();
        final String pagesDir = ctx.getPagesDirectory();

        try {
            if (!fs.exists(pagesDir)) {
                return Collections.emptyList();
            }

            final List<String> pageFiles = WikiIo.listPageFiles(fs, pagesDir);
            if (pageFiles.isEmpty()) {
                return Collections.emptyList();
            }

            final String queryTextLower = query.getQueryText().toLowerCase();
            final List<String> tags = query.getTags();
            final List<String> pathPatterns = query.getPagePathPatterns();

            final List<Scored> scored = new ArrayList<>();

            for (String pageFilePath : pageFiles) {
                if (!pathPatterns.isEmpty() && !WikiIo.matchesFilePatterns(pageFilePath, pathPatterns)) {
                    continue;
                }
                // Type filter is checked from the file name BEFORE reading the page body — type prefixes are
                // authoritative under the naming convention, so we can skip the I/O entirely for pages
                // the caller didn't ask for.
                if (!query.matchesType(WikiPageType.fromFileName(WikiIo.extractFileName(pageFilePath)))) {
                    continue;
                }

                try {
                    final String content = WikiIo.readContent(fs, pageFilePath);
                    if (content.isEmpty()) {
                        continue;
                    }

                    final WikiPage page = WikiIo.parseWikiPage(pageFilePath, content);

                    if (!tags.isEmpty() && !page.getTags().containsAll(tags)) {
                        continue;
                    }

                    final int matchCount = WikiIo.countOccurrences(content.toLowerCase(), queryTextLower);
                    if (matchCount > 0) {
                        scored.add(new Scored(page, matchCount));
                    }
                } catch (Exception e) {
                    log.warn("Failed to read/parse wiki page during search: {}", pageFilePath, e);
                }
            }

            return scored.stream().sorted(Comparator.comparingInt((Scored s) -> s.score).reversed())
                    .limit(query.getMaxResults()).map(s -> new WikiSearchResult(s.page, s.score)).toList();

        } catch (Exception e) {
            log.error("Unexpected error during full-scan search for scope {}: {}", ctx.getScope(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static final class Scored {

        final WikiPage page;
        final int score;

        Scored(WikiPage page, int score) {
            this.page = page;
            this.score = score;
        }
    }
}
