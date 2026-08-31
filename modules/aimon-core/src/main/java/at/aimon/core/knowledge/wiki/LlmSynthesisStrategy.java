package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.text.CodeFences;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;

/**
 * LLM-driven {@link SynthesisStrategy} that turns existing entity / concept pages into topic
 * {@link WikiPageType#OVERVIEW} pages and a single cross-cluster {@link WikiPageType#SYNTHESIS} page.
 *
 * <p>
 * The strategy operates in three stages:
 * <ol>
 * <li><b>Cluster</b> source pages by their primary tag. Pages without tags are dropped from synthesis input
 * (they cannot be reliably grouped). Clusters are capped at {@link SynthesizeOptions#getMaxClusters()}; the
 * largest clusters win.
 * <li>For each cluster (up to the LLM call cap), call the LLM with an "overview" prompt that lists the cluster
 * pages and asks for a JSON envelope describing one OVERVIEW page. Parse and return as a {@link GeneratedPage}.
 * <li>If {@link WikiPageType#SYNTHESIS} is in the requested types AND the call cap still has room, call the
 * LLM once more with a "synthesis" prompt that lists every cluster's overview output and asks for a single
 * SYNTHESIS page tying them together.
 * </ol>
 *
 * <p>
 * <b>Failure model</b>: any per-cluster LLM failure is logged at WARN and the cluster is skipped — the rest
 * of the pass continues. The strategy does not throw. JSON parsing failures degrade the same way.
 *
 * <p>
 * <b>Cost cap</b>: the {@link SynthesizeOptions#getMaxLlmCalls()} cap is enforced strictly. Once reached, the
 * strategy stops issuing calls and the surrounding storage layer reports the remaining clusters as skipped.
 *
 * <p>
 * <b>Thread-safe</b>, but not fully stateless: a {@link ThreadLocal} tracks {@link #getLastCallCount()} per
 * calling thread so the storage layer can read the count immediately after a {@code synthesize()} call
 * without a dedicated return value. Concurrent calls from different threads see isolated counts. The
 * injected {@link LlmClient} is not owned by this instance.
 */
public final class LlmSynthesisStrategy implements SynthesisStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmSynthesisStrategy.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Same slug regex as {@link LlmWikiPageGenerator} — kept duplicate rather than depending on package
     * internals so a future package split doesn't drag the synthesis strategy along.
     */
    private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** Default cap on the number of pages sent to the LLM in a single overview prompt. */
    public static final int DEFAULT_MAX_PAGES_PER_PROMPT = 30;

    // @formatter:off
    private static final String DEFAULT_OVERVIEW_SYSTEM_PROMPT = """
            You are a wiki knowledge base maintainer. You will be given a CLUSTER of related wiki pages \
            (entities, concepts, or comparisons). Your task is to write ONE overview page that introduces the \
            cluster topic and links out to the constituent pages.

            The overview should:
              * Start with a clear definition of the cluster topic.
              * Summarize the key entities / concepts in the cluster, in 1–2 sentences each.
              * Use [[slug]] wiki-links to reference the constituent pages by their slug.
              * End with a short list of related sub-topics if relevant.

            Output STRICT JSON only, no markdown fences or commentary, in this exact shape:

            {
              "title": "Human Readable Topic",
              "slug": "kebab-case-topic",
              "tags": ["tag1", "tag2"],
              "body": "# Topic\\n\\n<markdown body with [[slug]] cross references>"
            }

            Rules:
              * "slug" MUST be lowercase, kebab-case, contain only [a-z0-9-], and be stable across runs for the \
            same topic.
              * "body" must NOT include YAML frontmatter — the host writes the frontmatter from the JSON fields.
              * Do NOT wrap the JSON in code fences.""";

    private static final String DEFAULT_SYNTHESIS_SYSTEM_PROMPT = """
            You are a wiki knowledge base maintainer. You will be given a list of TOPIC OVERVIEWS that already \
            exist in the wiki. Your task is to write ONE synthesis page that ties them together — patterns \
            that span multiple topics, tensions or trade-offs between them, and emergent insights that no \
            single overview captures.

            The synthesis should:
              * Identify cross-topic patterns and recurring themes.
              * Call out tensions, trade-offs, or contradictions between topics.
              * Use [[slug]] wiki-links to reference the topic overviews.
              * Avoid simply restating the overviews — emphasize what is NEW when they are seen together.

            Output STRICT JSON only, no markdown fences or commentary, in this exact shape:

            {
              "title": "Human Readable Synthesis Title",
              "slug": "kebab-case-synthesis",
              "tags": ["tag1", "tag2"],
              "body": "# Synthesis\\n\\n<markdown body with [[slug]] cross references>"
            }

            Rules:
              * "slug" MUST be lowercase, kebab-case, contain only [a-z0-9-], and be stable across runs.
              * "body" must NOT include YAML frontmatter.
              * Do NOT wrap the JSON in code fences.""";
    // @formatter:on

    private static final String DEFAULT_COMPONENT = "wiki-synthesis";
    private static final LlmCallMetadata DEFAULT_OVERVIEW_METADATA = LlmCallMetadata.builder()
            .component(DEFAULT_COMPONENT).feature("overview-generation").build();
    private static final LlmCallMetadata DEFAULT_SYNTHESIS_METADATA = LlmCallMetadata.builder()
            .component(DEFAULT_COMPONENT).feature("synthesis-generation").build();

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxPagesPerPrompt;
    private final String overviewSystemPrompt;
    private final String synthesisSystemPrompt;
    private final LlmCallMetadata overviewCallMetadata;
    private final LlmCallMetadata synthesisCallMetadata;

    /**
     * Per-thread call counter so {@link #getLastCallCount()} reflects the most recent invocation by the
     * current thread without forcing the strategy to be stateful at the instance level.
     */
    private final ThreadLocal<Integer> lastCallCount = ThreadLocal.withInitial(() -> 0);

    private LlmSynthesisStrategy(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxPagesPerPrompt = builder.maxPagesPerPrompt;
        this.overviewSystemPrompt = builder.overviewSystemPrompt != null
                ? builder.overviewSystemPrompt
                : DEFAULT_OVERVIEW_SYSTEM_PROMPT;
        this.synthesisSystemPrompt = builder.synthesisSystemPrompt != null
                ? builder.synthesisSystemPrompt
                : DEFAULT_SYNTHESIS_SYSTEM_PROMPT;
        this.overviewCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_OVERVIEW_METADATA)
                : DEFAULT_OVERVIEW_METADATA;
        this.synthesisCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_SYNTHESIS_METADATA)
                : DEFAULT_SYNTHESIS_METADATA;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<GeneratedPage> synthesize(WikiScope scope, List<WikiPage> sourcePages, SynthesizeOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourcePages, "sourcePages must not be null");
        Objects.requireNonNull(options, "options must not be null");

        lastCallCount.set(0);

        if (sourcePages.isEmpty()) {
            return Collections.emptyList();
        }

        final List<GeneratedPage> result = new ArrayList<>();

        // Stage 1: cluster pages by primary tag.
        final Map<String, List<WikiPage>> clusters = clusterPagesByTag(sourcePages, options.getMaxClusters());
        log.debug("Synthesis for scope {}: {} clusters from {} source pages", scope, clusters.size(),
                sourcePages.size());

        // Stage 2: per-cluster overview pages.
        final List<GeneratedPage> overviews = new ArrayList<>();
        if (options.getTypes().contains(WikiPageType.OVERVIEW)) {
            for (Map.Entry<String, List<WikiPage>> entry : clusters.entrySet()) {
                if (lastCallCount.get() >= options.getMaxLlmCalls()) {
                    log.warn("Synthesis for scope {} hit maxLlmCalls={} before generating overview for cluster '{}'",
                            scope, options.getMaxLlmCalls(), entry.getKey());
                    break;
                }
                final GeneratedPage overview = generateOverviewSafely(scope, entry.getKey(), entry.getValue());
                if (overview != null) {
                    overviews.add(overview);
                }
            }
            result.addAll(overviews);
        }

        // Stage 3: single global synthesis page tying the overviews together.
        if (options.getTypes().contains(WikiPageType.SYNTHESIS) && !overviews.isEmpty()
                && lastCallCount.get() < options.getMaxLlmCalls()) {
            final GeneratedPage synthesis = generateSynthesisSafely(scope, overviews);
            if (synthesis != null) {
                result.add(synthesis);
            }
        }

        return result;
    }

    @Override
    public int getLastCallCount() {
        return lastCallCount.get();
    }

    /**
     * Groups pages by their primary tag (the first tag in {@link WikiPage#getTags()}). Pages without tags are
     * dropped from the synthesis input — they cannot be reliably grouped. Clusters are returned in descending
     * order of size and capped at {@code maxClusters}.
     */
    static Map<String, List<WikiPage>> clusterPagesByTag(List<WikiPage> pages, int maxClusters) {
        final Map<String, List<WikiPage>> clusters = new LinkedHashMap<>();
        for (WikiPage page : pages) {
            if (page.getTags().isEmpty()) {
                continue;
            }
            final String primaryTag = page.getTags().get(0).trim().toLowerCase(Locale.ROOT);
            if (primaryTag.isEmpty()) {
                continue;
            }
            clusters.computeIfAbsent(primaryTag, k -> new ArrayList<>()).add(page);
        }

        if (clusters.size() <= maxClusters) {
            return clusters;
        }

        // Keep the largest clusters by size, breaking ties by tag name for determinism.
        final List<Map.Entry<String, List<WikiPage>>> sorted = new ArrayList<>(clusters.entrySet());
        sorted.sort((a, b) -> {
            final int sizeCompare = Integer.compare(b.getValue().size(), a.getValue().size());
            return sizeCompare != 0 ? sizeCompare : a.getKey().compareTo(b.getKey());
        });
        final Map<String, List<WikiPage>> capped = new LinkedHashMap<>();
        for (int i = 0; i < maxClusters; i++) {
            final Map.Entry<String, List<WikiPage>> entry = sorted.get(i);
            capped.put(entry.getKey(), entry.getValue());
        }
        return capped;
    }

    private GeneratedPage generateOverviewSafely(WikiScope scope, String clusterTag, List<WikiPage> pages) {
        try {
            final String userMessage = buildClusterPrompt(clusterTag, pages);
            final LlmResponse response = callLlm(overviewSystemPrompt, userMessage, scope, overviewCallMetadata);
            if (!response.hasTextContent() || response.getTextContent().isBlank()) {
                log.warn("Overview LLM call returned empty content for cluster '{}'", clusterTag);
                return null;
            }
            return parsePageResponse(response.getTextContent(), WikiPageType.OVERVIEW, clusterTag, pages);
        } catch (Exception e) {
            log.warn("Overview generation failed for cluster '{}': {}", clusterTag, e.getMessage());
            return null;
        }
    }

    private GeneratedPage generateSynthesisSafely(WikiScope scope, List<GeneratedPage> overviews) {
        try {
            final String userMessage = buildSynthesisPrompt(overviews);
            final LlmResponse response = callLlm(synthesisSystemPrompt, userMessage, scope, synthesisCallMetadata);
            if (!response.hasTextContent() || response.getTextContent().isBlank()) {
                log.warn("Synthesis LLM call returned empty content");
                return null;
            }
            // Use a synthetic "cluster tag" so the parser can produce derivedFrom from the overview pages.
            return parseSynthesisResponse(response.getTextContent(), overviews);
        } catch (Exception e) {
            log.warn("Synthesis generation failed: {}", e.getMessage());
            return null;
        }
    }

    private LlmResponse callLlm(String systemPrompt, String userMessage, WikiScope scope, LlmCallMetadata metadata) {
        lastCallCount.set(lastCallCount.get() + 1);
        return llmClient.sendMessage(systemPrompt, List.of(Message.user(userMessage)), Collections.emptyList(),
                modelConfig, metadata.withTags(scopeTags(scope)));
    }

    /**
     * Builds the user message for an overview prompt. Lists the cluster pages with their slug, title, and a
     * short content preview so the LLM can produce [[slug]] wiki-links and a meaningful summary.
     */
    private String buildClusterPrompt(String clusterTag, List<WikiPage> pages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Cluster topic (primary tag): ").append(clusterTag).append("\n\n");
        sb.append("Pages in this cluster:\n");
        final int limit = Math.min(maxPagesPerPrompt, pages.size());
        for (int i = 0; i < limit; i++) {
            final WikiPage page = pages.get(i);
            final String slug = slugFromPath(page.getPath());
            sb.append("- slug: ").append(slug).append('\n');
            sb.append("  title: ").append(page.getTitle()).append('\n');
            sb.append("  type: ").append(page.getType().getToken()).append('\n');
            if (!page.getTags().isEmpty()) {
                sb.append("  tags: ").append(String.join(", ", page.getTags())).append('\n');
            }
            sb.append('\n');
        }
        if (pages.size() > limit) {
            sb.append("[... ").append(pages.size() - limit).append(" more pages omitted from prompt]\n");
        }
        return sb.toString();
    }

    private String buildSynthesisPrompt(List<GeneratedPage> overviews) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Topic overviews already in the wiki:\n\n");
        for (GeneratedPage overview : overviews) {
            sb.append("- slug: ").append(overview.getSlug()).append('\n');
            sb.append("  title: ").append(overview.getTitle()).append('\n');
            if (!overview.getTags().isEmpty()) {
                sb.append("  tags: ").append(String.join(", ", overview.getTags())).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses an overview response into a {@link GeneratedPage}. {@code derivedFrom} is set to the source page
     * paths so re-runs can detect when a cluster's input set has changed.
     */
    static GeneratedPage parsePageResponse(String rawResponse, WikiPageType type, String clusterTag,
            List<WikiPage> sourcePages) {
        final JsonNode root = parseJsonOrNull(rawResponse);
        if (root == null) {
            return null;
        }
        final String slug = root.path("slug").asText("").trim().toLowerCase(Locale.ROOT);
        final String title = root.path("title").asText("").trim();
        final String body = root.path("body").asText("");
        if (slug.isEmpty() || !SLUG_PATTERN.matcher(slug).matches()) {
            log.warn("Skipping {} page for cluster '{}' with invalid slug '{}'", type, clusterTag, slug);
            return null;
        }
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Skipping {} page for cluster '{}' with missing title/body", type, clusterTag);
            return null;
        }

        final List<String> tags = readStringArray(root.path("tags"));
        final List<String> derivedFrom = new ArrayList<>();
        for (WikiPage page : sourcePages) {
            derivedFrom.add(page.getPath());
        }

        final String fullContent = buildPageMarkdown(type, title, tags, derivedFrom, body);
        return GeneratedPage.builder().type(type).slug(slug).title(title).content(fullContent).tags(tags)
                .derivedFrom(derivedFrom).strategy(GeneratedPage.UpdateStrategy.CREATE).build();
    }

    /**
     * Parses a synthesis response. The {@code derivedFrom} list is the union of the overview slugs (rendered
     * as logical paths) so a downstream lint can detect stale syntheses when an overview is removed.
     */
    static GeneratedPage parseSynthesisResponse(String rawResponse, List<GeneratedPage> overviews) {
        final JsonNode root = parseJsonOrNull(rawResponse);
        if (root == null) {
            return null;
        }
        final String slug = root.path("slug").asText("").trim().toLowerCase(Locale.ROOT);
        final String title = root.path("title").asText("").trim();
        final String body = root.path("body").asText("");
        if (slug.isEmpty() || !SLUG_PATTERN.matcher(slug).matches()) {
            log.warn("Skipping synthesis page with invalid slug '{}'", slug);
            return null;
        }
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Skipping synthesis page with missing title/body");
            return null;
        }

        final List<String> tags = readStringArray(root.path("tags"));
        final List<String> derivedFrom = new ArrayList<>();
        for (GeneratedPage overview : overviews) {
            derivedFrom.add(WikiIo.buildPageFileName(overview.getType(), overview.getSlug()));
        }

        final String fullContent = buildPageMarkdown(WikiPageType.SYNTHESIS, title, tags, derivedFrom, body);
        return GeneratedPage.builder().type(WikiPageType.SYNTHESIS).slug(slug).title(title).content(fullContent)
                .tags(tags).derivedFrom(derivedFrom).strategy(GeneratedPage.UpdateStrategy.CREATE).build();
    }

    private static JsonNode parseJsonOrNull(String rawResponse) {
        final String stripped = CodeFences.strip(rawResponse);
        try {
            return JSON.readTree(stripped);
        } catch (Exception e) {
            log.warn("Synthesis JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> readStringArray(JsonNode node) {
        final List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                final String s = item.asText("").trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /**
     * Wraps a synthesis body in canonical wiki frontmatter. Mirrors {@link LlmWikiPageGenerator}'s output so
     * the on-disk shape is identical to extracted pages.
     */
    private static String buildPageMarkdown(WikiPageType type, String title, List<String> tags,
            List<String> derivedFrom, String body) {
        final StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(title).append('\n');
        sb.append("type: ").append(type.getToken()).append('\n');
        sb.append("tags: [");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tags.get(i));
        }
        sb.append("]\n");
        sb.append("derived_from: [");
        for (int i = 0; i < derivedFrom.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(derivedFrom.get(i));
        }
        sb.append("]\n");
        sb.append("---\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String slugFromPath(String path) {
        final int lastSlash = path.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (fileName.endsWith(".md")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        // Strip the type prefix (summary-, entity-, concept-, etc.) so the slug shown to the LLM matches the
        // raw subject identifier — not the on-disk file name.
        for (WikiPageType type : WikiPageType.values()) {
            final String prefix = type.getPrefix() + "-";
            if (fileName.startsWith(prefix)) {
                return fileName.substring(prefix.length());
            }
        }
        return fileName;
    }

    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    /** Builder for {@link LlmSynthesisStrategy}. */
    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxPagesPerPrompt = DEFAULT_MAX_PAGES_PER_PROMPT;
        private String overviewSystemPrompt;
        private String synthesisSystemPrompt;
        private LlmCallMetadata llmCallMetadata;

        private Builder() {
        }

        /** Sets the {@link LlmClient}. Required. */
        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        /** Sets the LLM model configuration. Optional. */
        public Builder modelConfig(LlmModel modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Sets the maximum number of pages included in a single overview prompt. Pages beyond this limit are
         * omitted from the prompt with a notice.
         *
         * @param maxPagesPerPrompt
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxPagesPerPrompt(int maxPagesPerPrompt) {
            if (maxPagesPerPrompt <= 0) {
                throw new IllegalArgumentException("maxPagesPerPrompt must be positive");
            }
            this.maxPagesPerPrompt = maxPagesPerPrompt;
            return this;
        }

        /** Overrides the default overview system prompt. Optional. */
        public Builder overviewSystemPrompt(String overviewSystemPrompt) {
            this.overviewSystemPrompt = overviewSystemPrompt;
            return this;
        }

        /** Overrides the default synthesis system prompt. Optional. */
        public Builder synthesisSystemPrompt(String synthesisSystemPrompt) {
            this.synthesisSystemPrompt = synthesisSystemPrompt;
            return this;
        }

        /** Overrides the LLM call metadata used for usage attribution. */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /** Builds the strategy. */
        public LlmSynthesisStrategy build() {
            return new LlmSynthesisStrategy(this);
        }
    }
}
