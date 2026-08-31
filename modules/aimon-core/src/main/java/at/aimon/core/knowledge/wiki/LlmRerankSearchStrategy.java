package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;

/**
 * LLM-backed rerank search strategy implementing the pattern described in {@code docs/references/llm-wiki.md}
 * line 47: "the LLM reads the index first to find relevant pages, then drills into them."
 *
 * <p>
 * Unlike {@link IndexFirstSearchStrategy}, which approximates the LLM's judgement with token-level keyword
 * scoring, this strategy hands the entire filtered index to an {@link LlmClient} and asks it to pick the most
 * relevant page paths. This restores semantic matching for synonym and natural-language queries
 * ("k8s pod 죽음" &harr; "kubernetes crashloop") that keyword scoring cannot handle, at the cost of one extra LLM
 * call per {@code search()} invocation.
 *
 * <p>
 * Pipeline:
 * <ol>
 * <li><b>Load &amp; filter (no LLM).</b> Read and parse {@code index.md}; drop entries that fail the explicit
 * {@link WikiSearchQuery#getPagePathPatterns() filename glob} or {@link WikiSearchQuery#getTags() tag} filter.
 * These are user-specified hard constraints, not relevance heuristics, so they must be applied before the LLM
 * sees the candidates — otherwise a {@code tags=[kubernetes]} query could produce non-kubernetes hits. Note
 * that the glob is matched against the file name only (see {@code WikiIo.matchesFilePatterns}), not the full
 * path — {@code "alpha-*.md"} works, {@code "**&#47;alpha-*.md"} does not.
 * <li><b>Short-circuit small candidate sets.</b> If the filtered set already has {@code <= maxResults} entries,
 * skip the LLM call and drill down directly. There is nothing for the LLM to rerank. Results in this path are
 * returned in index-file order (which is typically the category grouping the wiki's index generator produced),
 * not relevance order.
 * <li><b>Size guard.</b> If the filtered index exceeds {@link Builder#maxIndexEntries(int)}, delegate to the
 * configured {@link #fallback} strategy. Arbitrarily truncating the candidate list would re-introduce a
 * keyword-based relevance heuristic in front of the LLM — the exact thing this strategy exists to avoid — so
 * "too big to rerank" is treated as "not this strategy's job".
 * <li><b>LLM rerank.</b> Serialize candidates as JSON ({@code {path,title,summary,tags}}) and ask the LLM to
 * pick up to {@code maxResults} paths in descending relevance. No page bodies are sent. The expected response
 * is {@code {"paths": ["/path/a.md", "/path/b.md", ...]}} but the parser is deliberately lenient: it scans for
 * candidate paths (preferring quoted occurrences to avoid substring false positives), preserves first-occurrence
 * order, and discards hallucinated paths. Markdown fences, stray prose, and malformed JSON are all absorbed.
 * <li><b>Drill down.</b> Read the picked pages from the VFS in LLM-assigned order and return them. Stale
 * references (paths removed since the index was written) are silently dropped. No further re-ranking is applied
 * — the LLM's judgement is trusted.
 * </ol>
 *
 * <p>
 * <b>Failure model.</b> Any transient failure — missing/unparseable index, LLM exception, empty response,
 * unrecognised paths — degrades to {@link #fallback} (default: {@link IndexFirstSearchStrategy}). The strategy
 * never throws on transient errors, never returns null, and never propagates provider exceptions, consistent
 * with the {@link WikiSearchStrategy} contract. Lifecycle/configuration errors from upstream (e.g., a
 * mis-wired VFS) are not caught here and propagate as usual.
 *
 * <p>
 * <b>Cost note.</b> Every {@code search()} call makes one LLM call with ~100 tokens per candidate. At the
 * default {@link #DEFAULT_MAX_INDEX_ENTRIES} = {@value #DEFAULT_MAX_INDEX_ENTRIES} this caps input at roughly
 * 20K tokens. Callers embedding this strategy in a hot path (e.g., a ReAct loop with repeated searches) should
 * consider caching at the call site.
 *
 * <p>
 * Thread-safe and stateless. The injected {@link LlmClient}, {@link LlmModel}, and fallback strategy are not
 * owned by this instance.
 *
 * <pre>{@code
 * WikiSearchStrategy strategy = LlmRerankSearchStrategy.builder()
 *         .llmClient(llmClient)
 *         .modelConfig(LlmModel.builder().name("claude-haiku-4-5").build())
 *         .maxIndexEntries(200)
 *         .build();
 * DefaultWikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator, generator, strategy);
 * }</pre>
 *
 * @see WikiSearchStrategy
 * @see IndexFirstSearchStrategy
 */
public final class LlmRerankSearchStrategy implements WikiSearchStrategy {

    /**
     * Default upper bound on the number of index entries sent to the LLM in a single call. Entries beyond this
     * count trigger fallback to the configured fallback strategy. At ~100 tokens per serialized entry, 200
     * entries ≈ 20K input tokens, which fits comfortably in every modern model's context window while keeping
     * per-query cost bounded.
     */
    public static final int DEFAULT_MAX_INDEX_ENTRIES = 200;

    // @formatter:off
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a relevance ranker for a wiki search engine.

            You will receive a user query and a JSON list of wiki page index entries. Each entry has a path, a \
            title, an optional one-line summary, and tags. Your job is to select the entries most relevant to \
            the query and return their paths in descending order of relevance.

            Rules:
            - Respond with ONLY a JSON object of the form: {"paths": ["/path/a.md", "/path/b.md"]}
            - No explanation, no markdown code fences, no prose before or after.
            - Include at most {{MAX_PICKS}} paths.
            - Omit entries that are not relevant — returning fewer paths is better than padding with weak \
            matches.
            - Use only paths that appear verbatim in the input list. Do not invent paths.
            - Judge relevance by meaning, not by surface word overlap: "k8s" matches "kubernetes", \
            "DB 장애" matches "database outage", and so on.""";
    // @formatter:on

    private static final String MAX_PICKS_PLACEHOLDER = "{{MAX_PICKS}}";

    private static final Logger log = LoggerFactory.getLogger(LlmRerankSearchStrategy.class);

    private static final LlmCallMetadata DEFAULT_LLM_CALL_METADATA = LlmCallMetadata.builder().component("wiki-rerank")
            .feature("search").build();

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxIndexEntries;
    private final WikiSearchStrategy fallback;
    private final String systemPromptTemplate;
    private final LlmCallMetadata llmCallMetadata;

    private LlmRerankSearchStrategy(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxIndexEntries = builder.maxIndexEntries;
        this.fallback = builder.fallback != null ? builder.fallback : new IndexFirstSearchStrategy();
        this.systemPromptTemplate = builder.systemPromptTemplate != null
                ? builder.systemPromptTemplate
                : DEFAULT_SYSTEM_PROMPT;
        this.llmCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_LLM_CALL_METADATA)
                : DEFAULT_LLM_CALL_METADATA;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<WikiPage> search(WikiSearchQuery query, WikiSearchContext ctx) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");

        final VirtualFileSystem fs = ctx.getFileSystem();
        final String indexPath = ctx.getIndexPath();

        try {
            // Stage 0: load + parse index. Missing/empty/unparseable index is indistinguishable from "no data
            // for this strategy" — delegate to fallback so cold-start searches never fail worse than baseline.
            if (!fs.exists(indexPath)) {
                log.debug("index.md missing for scope {}, delegating to fallback", ctx.getScope());
                return fallback.search(query, ctx);
            }

            final String indexContent;
            try {
                indexContent = WikiIo.readContent(fs, indexPath);
            } catch (Exception e) {
                log.warn("Failed to read index.md for scope {}, delegating to fallback: {}", ctx.getScope(),
                        e.getMessage());
                return fallback.search(query, ctx);
            }

            final List<IndexParser.IndexEntry> allEntries = IndexParser.parse(indexContent);
            if (allEntries.isEmpty()) {
                log.debug("index.md for scope {} parsed to 0 entries, delegating to fallback", ctx.getScope());
                return fallback.search(query, ctx);
            }

            // Stage 1: explicit filters only (path glob + tags). These are hard constraints supplied by the
            // caller, not relevance heuristics — they must be honoured before the LLM sees candidates.
            final List<IndexParser.IndexEntry> filtered = applyExplicitFilters(allEntries, query);
            if (filtered.isEmpty()) {
                log.debug("No entries matched explicit filters for scope {}, query '{}'", ctx.getScope(),
                        query.getQueryText());
                return Collections.emptyList();
            }

            // Stage 2: size guard. If the filtered set is too large to rerank cheaply, fall back. Deliberately
            // *do not* truncate by keyword score: that would reintroduce the exact heuristic this strategy
            // exists to avoid.
            if (filtered.size() > maxIndexEntries) {
                log.debug("Filtered index size {} exceeds maxIndexEntries={} for scope {}, delegating to fallback",
                        filtered.size(), maxIndexEntries, ctx.getScope());
                return fallback.search(query, ctx);
            }

            // If the filtered set is already small enough to satisfy maxResults without LLM judgement, skip
            // the LLM call entirely — the rerank has nothing to choose between.
            if (filtered.size() <= query.getMaxResults()) {
                log.debug("Filtered index size {} <= maxResults {} for scope {}, skipping LLM rerank", filtered.size(),
                        query.getMaxResults(), ctx.getScope());
                return drillDown(filtered, query, ctx);
            }

            // Stage 3: LLM rerank on the filtered set. One call, input bounded by maxIndexEntries. We ask the
            // LLM for exactly maxResults picks and trust its ordering — there is no downstream re-ranking, so
            // a wider pick budget would just spend tokens without improving the output.
            final int maxPicks = query.getMaxResults();
            final List<String> pickedPaths;
            try {
                pickedPaths = rerankViaLlm(query, filtered, maxPicks, ctx.getScope());
            } catch (RuntimeException e) {
                log.warn("LLM rerank failed for scope {}, delegating to fallback: {}", ctx.getScope(), e.getMessage());
                return fallback.search(query, ctx);
            }

            if (pickedPaths.isEmpty()) {
                log.debug("LLM returned no paths for scope {}, query '{}', delegating to fallback", ctx.getScope(),
                        query.getQueryText());
                return fallback.search(query, ctx);
            }

            // Stage 4: map picked paths back to IndexEntry objects (preserving LLM's order), then drill down
            // to read page bodies.
            final List<IndexParser.IndexEntry> pickedEntries = orderByPickedPaths(filtered, pickedPaths);
            if (pickedEntries.isEmpty()) {
                log.debug("LLM picks did not intersect candidate set for scope {}, delegating to fallback",
                        ctx.getScope());
                return fallback.search(query, ctx);
            }
            return drillDown(pickedEntries, query, ctx);

        } catch (Exception e) {
            log.error("Unexpected error in LLM-rerank search for scope {}: {}", ctx.getScope(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Stage 1: explicit filters
    // -------------------------------------------------------------------------

    private static List<IndexParser.IndexEntry> applyExplicitFilters(List<IndexParser.IndexEntry> entries,
            WikiSearchQuery query) {
        final List<String> pathPatterns = query.getPagePathPatterns();
        final List<String> tagFilter = query.getTags();
        if (pathPatterns.isEmpty() && tagFilter.isEmpty()) {
            return entries;
        }
        final List<IndexParser.IndexEntry> out = new ArrayList<>(entries.size());
        for (IndexParser.IndexEntry entry : entries) {
            if (!pathPatterns.isEmpty() && !WikiIo.matchesFilePatterns(entry.getPath(), pathPatterns)) {
                continue;
            }
            if (!tagFilter.isEmpty() && !entry.getTags().containsAll(tagFilter)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Builds the per-call attribution tags from a {@link WikiScope}. Kept centrally so the tag keys stay consistent
     * across the wiki components and remain in sync with {@link LlmWikiPageGenerator}.
     */
    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    // -------------------------------------------------------------------------
    // Stage 3: LLM call
    // -------------------------------------------------------------------------

    private List<String> rerankViaLlm(WikiSearchQuery query, List<IndexParser.IndexEntry> candidates, int maxPicks,
            WikiScope scope) {
        final String systemPrompt = systemPromptTemplate.replace(MAX_PICKS_PLACEHOLDER, Integer.toString(maxPicks));
        final String userPrompt = buildUserPrompt(query.getQueryText(), candidates);

        final LlmResponse response = llmClient.sendMessage(systemPrompt, List.of(Message.user(userPrompt)),
                Collections.emptyList(), modelConfig, llmCallMetadata.withTags(scopeTags(scope)));

        if (!response.hasTextContent()) {
            log.debug("LLM returned empty text content for rerank");
            return Collections.emptyList();
        }
        final String responseText = response.getTextContent();
        if (responseText == null || responseText.isBlank()) {
            return Collections.emptyList();
        }

        // Build a candidate path set for intersection. Preserves first-occurrence order of the LLM response
        // and silently discards hallucinated paths.
        final Set<String> candidatePaths = new LinkedHashSet<>(candidates.size());
        for (IndexParser.IndexEntry entry : candidates) {
            candidatePaths.add(entry.getPath());
        }
        return parsePickedPaths(responseText, candidatePaths, maxPicks);
    }

    private static String buildUserPrompt(String queryText, List<IndexParser.IndexEntry> candidates) {
        final StringBuilder sb = new StringBuilder(256 + 128 * candidates.size());
        sb.append("Query: ").append(queryText).append("\n\n");
        sb.append("Index entries (JSON array):\n");
        sb.append("[\n");
        for (int i = 0; i < candidates.size(); i++) {
            final IndexParser.IndexEntry e = candidates.get(i);
            sb.append("  {\"path\": \"").append(escapeJson(e.getPath())).append("\"");
            sb.append(", \"title\": \"").append(escapeJson(e.getTitle())).append("\"");
            sb.append(", \"summary\": ");
            if (e.getSummary() == null) {
                sb.append("null");
            } else {
                sb.append('"').append(escapeJson(e.getSummary())).append('"');
            }
            sb.append(", \"tags\": [");
            final List<String> tags = e.getTags();
            for (int t = 0; t < tags.size(); t++) {
                if (t > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(escapeJson(tags.get(t))).append('"');
            }
            sb.append("]}");
            if (i < candidates.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' :
                    sb.append("\\\"");
                    break;
                case '\\' :
                    sb.append("\\\\");
                    break;
                case '\n' :
                    sb.append("\\n");
                    break;
                case '\r' :
                    sb.append("\\r");
                    break;
                case '\t' :
                    sb.append("\\t");
                    break;
                default :
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Lenient response parser
    // -------------------------------------------------------------------------

    /**
     * Extracts picked paths from the LLM response by scanning for each candidate path as a literal substring,
     * preserving first-occurrence order. Deliberately ignores JSON structure: markdown fences, stray prose, or
     * malformed {@code "paths"} arrays are all tolerated because the only signal we trust is "does this
     * response mention a candidate path?". Hallucinated paths never match and are discarded automatically.
     *
     * <p>
     * <b>Quoted-first matching.</b> Each candidate is first searched as {@code "<path>"} (JSON-quoted form),
     * falling back to the bare path only if no quoted hit exists. This avoids the false-positive case where one
     * candidate's path is a substring of another's, or where the LLM mentions a path conversationally in prose
     * alongside a properly-quoted JSON answer — the quoted form almost always appears first in well-formed
     * responses and is unambiguous.
     */
    static List<String> parsePickedPaths(String responseText, Set<String> candidatePaths, int maxPicks) {
        final List<Pick> picks = new ArrayList<>();
        for (String path : candidatePaths) {
            final String quoted = "\"" + path + "\"";
            int idx = responseText.indexOf(quoted);
            if (idx < 0) {
                idx = responseText.indexOf(path);
            }
            if (idx >= 0) {
                picks.add(new Pick(path, idx));
            }
        }
        picks.sort(Comparator.comparingInt(p -> p.index));
        final List<String> ordered = new ArrayList<>(Math.min(picks.size(), maxPicks));
        for (int i = 0; i < picks.size() && i < maxPicks; i++) {
            ordered.add(picks.get(i).path);
        }
        return ordered;
    }

    private static final class Pick {

        final String path;
        final int index;

        Pick(String path, int index) {
            this.path = path;
            this.index = index;
        }
    }

    // -------------------------------------------------------------------------
    // Stage 4: drill down
    // -------------------------------------------------------------------------

    private static List<IndexParser.IndexEntry> orderByPickedPaths(List<IndexParser.IndexEntry> filtered,
            List<String> pickedPaths) {
        final List<IndexParser.IndexEntry> out = new ArrayList<>(pickedPaths.size());
        for (String path : pickedPaths) {
            for (IndexParser.IndexEntry entry : filtered) {
                if (entry.getPath().equals(path)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    private static List<WikiPage> drillDown(List<IndexParser.IndexEntry> entries, WikiSearchQuery query,
            WikiSearchContext ctx) {
        final VirtualFileSystem fs = ctx.getFileSystem();
        final List<WikiPage> out = new ArrayList<>(Math.min(entries.size(), query.getMaxResults()));
        for (IndexParser.IndexEntry entry : entries) {
            if (out.size() >= query.getMaxResults()) {
                break;
            }
            final String path = entry.getPath();
            try {
                if (!fs.exists(path)) {
                    // Stale index: the page was removed but index still references it. Lint reports this.
                    log.debug("Index references missing page (stale): {}", path);
                    continue;
                }
                final String content = WikiIo.readContent(fs, path);
                if (content.isEmpty()) {
                    continue;
                }
                out.add(WikiIo.parseWikiPage(path, content));
            } catch (Exception e) {
                log.warn("Failed to read page {} during drill-down: {}", path, e.getMessage());
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Builder for {@link LlmRerankSearchStrategy}. */
    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxIndexEntries = DEFAULT_MAX_INDEX_ENTRIES;
        private WikiSearchStrategy fallback;
        private String systemPromptTemplate;
        private LlmCallMetadata llmCallMetadata;

        private Builder() {
        }

        /**
         * Sets the LLM client used for rerank calls. Required.
         *
         * @param llmClient
         *            the client (must not be null)
         * @return this builder
         */
        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        /**
         * Sets the model configuration passed on every rerank call. If not set, the provider default is used.
         * For cost control, prefer a small/fast model (e.g., Haiku-class) since rerank prompts are small and
         * latency compounds inside agent loops.
         *
         * @param modelConfig
         *            the model config (may be null for provider default)
         * @return this builder
         */
        public Builder modelConfig(LlmModel modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Sets the maximum number of index entries sent to the LLM in a single rerank call. When the filtered
         * index exceeds this cap, the strategy delegates to the configured fallback instead of truncating
         * candidates (which would reintroduce a keyword-based relevance heuristic). Default:
         * {@value #DEFAULT_MAX_INDEX_ENTRIES}.
         *
         * @param maxIndexEntries
         *            the maximum (must be &gt; 0)
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxIndexEntries <= 0}
         */
        public Builder maxIndexEntries(int maxIndexEntries) {
            if (maxIndexEntries <= 0) {
                throw new IllegalArgumentException("maxIndexEntries must be > 0, got: " + maxIndexEntries);
            }
            this.maxIndexEntries = maxIndexEntries;
            return this;
        }

        /**
         * Sets the fallback strategy used when the LLM rerank cannot or should not run: missing index, parse
         * failure, filtered-set too large, LLM exception, empty/unrecognised response. Default:
         * {@link IndexFirstSearchStrategy}.
         *
         * @param fallback
         *            the fallback strategy (may be null for default)
         * @return this builder
         */
        public Builder fallback(WikiSearchStrategy fallback) {
            this.fallback = fallback;
            return this;
        }

        /**
         * Overrides the default system prompt template.
         *
         * <p>
         * The literal substring {@code {{MAX_PICKS}}}, if present, is replaced with the per-query pick budget
         * ({@code query.getMaxResults()}) at call time. The placeholder is <i>optional</i> — a custom prompt
         * that omits it is passed through unchanged, but the LLM will then not know how many results to
         * return, so omit it only if the prompt instructs the model differently. If not set, a built-in prompt
         * is used.
         *
         * @param systemPromptTemplate
         *            the template (may be null for default)
         * @return this builder
         */
        public Builder systemPromptTemplate(String systemPromptTemplate) {
            this.systemPromptTemplate = systemPromptTemplate;
            return this;
        }

        /**
         * Overrides the LLM call metadata used for usage attribution. Caller-supplied fields win on collision; any
         * unset fields fall back to the strategy's defaults ({@code component=wiki-rerank}, {@code feature=search}).
         *
         * @param llmCallMetadata
         *            the metadata (may be null to use defaults)
         * @return this builder
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /**
         * Builds the strategy.
         *
         * @return a new {@link LlmRerankSearchStrategy}
         * @throws NullPointerException
         *             if {@code llmClient} was not set
         */
        public LlmRerankSearchStrategy build() {
            return new LlmRerankSearchStrategy(this);
        }
    }
}
