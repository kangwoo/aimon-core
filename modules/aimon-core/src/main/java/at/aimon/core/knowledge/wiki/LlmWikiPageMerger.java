package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * LLM-powered implementation of {@link WikiPageMerger}.
 *
 * <p>
 * Sends the existing page and the incoming generated page to an {@link LlmClient} with a system prompt that
 * instructs the model to combine them while preserving cross-references, removing duplication, and resolving
 * contradictions. The response is a small JSON envelope ({@code {"title": ..., "tags": [...], "body": "..."}})
 * which is then re-wrapped in canonical wiki frontmatter.
 *
 * <p>
 * <b>Failure model</b>: any LLM call failure, parse failure, or empty response triggers a deterministic fallback
 * that appends the incoming body under a {@code ## Update from <source>} section in the existing page. This is
 * intentionally information-preserving rather than clever — losing data on a merge is the worst possible failure
 * mode for a long-lived wiki page, so the fallback always wins compared to dropping content.
 *
 * <p>
 * Thread-safe and stateless. The provided {@link LlmClient} is not owned by this instance.
 *
 * <pre>{@code
 * WikiPageMerger merger = LlmWikiPageMerger.builder()
 *         .llmClient(llmClient)
 *         .modelConfig(LlmModel.builder().name("gpt-4o-mini").build())
 *         .build();
 * }</pre>
 *
 * @see WikiPageMerger
 * @see DefaultWikiKnowledgeBase
 */
public final class LlmWikiPageMerger implements WikiPageMerger {

    private static final Logger log = LoggerFactory.getLogger(LlmWikiPageMerger.class);

    /** Default maximum characters of merged input (existing + incoming) sent to the LLM. */
    public static final int DEFAULT_MAX_MERGE_INPUT_LENGTH = 48_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    // @formatter:off
    private static final String DEFAULT_MERGE_SYSTEM_PROMPT = """
            You are a wiki knowledge base maintainer. Your task is to merge a NEW version of a wiki page with the \
            EXISTING on-disk version, producing one cohesive page that preserves information from both.

            Rules:
              * Preserve every distinct fact from BOTH inputs. When in doubt, keep more rather than less.
              * Remove exact duplicate sentences and clearly redundant sections.
              * Resolve contradictions by stating the more recent fact and noting the change in-line where useful.
              * Preserve every [[wiki-link]] cross reference from the existing page; add new ones from the incoming \
            page when applicable.
              * Maintain a clear structure: a single H1 heading, followed by ## sub-sections.
              * The merged title and tag set should reflect the combined scope. Use existing tags when appropriate \
            and add new ones that strengthen discoverability.

            Output STRICT JSON only, with no markdown fences and no commentary, in this exact shape:

            {
              "title": "Human Readable Title",
              "tags": ["tag1", "tag2"],
              "body": "# Title\\n\\n<merged markdown body>"
            }

            The "body" must NOT include YAML frontmatter — the host writes the frontmatter from the JSON fields.""";
    // @formatter:on

    private static final String DEFAULT_COMPONENT = "wiki-merger";
    private static final LlmCallMetadata DEFAULT_MERGE_METADATA = LlmCallMetadata.builder().component(DEFAULT_COMPONENT)
            .feature("page-merge").build();

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxMergeInputLength;
    private final String mergeSystemPrompt;
    private final LlmCallMetadata mergeCallMetadata;

    private LlmWikiPageMerger(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxMergeInputLength = builder.maxMergeInputLength;
        this.mergeSystemPrompt = builder.mergeSystemPrompt != null
                ? builder.mergeSystemPrompt
                : DEFAULT_MERGE_SYSTEM_PROMPT;
        this.mergeCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_MERGE_METADATA)
                : DEFAULT_MERGE_METADATA;
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public GeneratedPage merge(WikiScope scope, WikiPage existing, GeneratedPage incoming) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(existing, "existing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");

        final List<String> mergedDerivedFrom = mergeDerivedFrom(existing.getDerivedFrom(), incoming.getDerivedFrom());

        try {
            final String userMessage = buildMergePrompt(existing, incoming);
            final LlmResponse response = llmClient.sendMessage(mergeSystemPrompt, List.of(Message.user(userMessage)),
                    Collections.emptyList(), modelConfig, mergeCallMetadata.withTags(scopeTags(scope)));

            if (response.hasTextContent() && !response.getTextContent().isBlank()) {
                final GeneratedPage merged = parseMergeResponse(response.getTextContent(), incoming, mergedDerivedFrom);
                if (merged != null) {
                    log.debug("LLM merged page slug={} for scope {}", incoming.getSlug(), scope);
                    return merged;
                }
                log.warn("LLM merge returned unparseable content for slug={}, using append-only fallback",
                        incoming.getSlug());
            } else {
                log.warn("LLM merge returned empty content for slug={}, using append-only fallback",
                        incoming.getSlug());
            }
        } catch (Exception e) {
            log.warn("LLM merge failed for slug={}, using append-only fallback: {}", incoming.getSlug(),
                    e.getMessage());
        }

        return buildAppendOnlyFallback(existing, incoming, mergedDerivedFrom);
    }

    /**
     * Builds the merge prompt body. The existing and incoming markdown bodies are sent verbatim — including
     * frontmatter — so the LLM has full context. The combined size is capped at {@link #getMaxMergeInputLength()}
     * to keep the call within sensible token budgets; if exceeded, the existing page wins (it's the larger
     * accumulated history) and the incoming page is truncated.
     */
    private String buildMergePrompt(WikiPage existing, GeneratedPage incoming) {
        final int budget = maxMergeInputLength;
        final String existingContent = existing.getContent();
        String incomingContent = incoming.getContent();

        final int combined = existingContent.length() + incomingContent.length();
        if (combined > budget) {
            final int incomingBudget = Math.max(0, budget - existingContent.length());
            if (incomingBudget < incomingContent.length()) {
                log.warn("Merge input exceeds budget {} for slug={}; truncating incoming content from {} to {}", budget,
                        incoming.getSlug(), incomingContent.length(), incomingBudget);
                incomingContent = incomingContent.substring(0, Math.max(0, incomingBudget))
                        + "\n\n[... incoming content truncated]";
            }
        }

        return "EXISTING wiki page (path=" + existing.getPath() + "):\n```markdown\n" + existingContent
                + "\n```\n\nINCOMING new content (slug=" + incoming.getSlug() + "):\n```markdown\n" + incomingContent
                + "\n```";
    }

    /**
     * Parses the merge JSON envelope and rebuilds a {@link GeneratedPage} with the same type/slug as
     * {@code incoming} so the storage layer writes it to the same file. Returns {@code null} when the response
     * is unusable; the caller falls back to the append-only path.
     */
    static GeneratedPage parseMergeResponse(String rawResponse, GeneratedPage incoming,
            List<String> mergedDerivedFrom) {
        final String stripped = CodeFences.strip(rawResponse);
        final JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (Exception e) {
            log.warn("Failed to parse merge JSON for slug={}: {}", incoming.getSlug(), e.getMessage());
            return null;
        }

        final String title = root.path("title").asText("").trim();
        final String body = root.path("body").asText("");
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Merge response for slug={} has empty title or body", incoming.getSlug());
            return null;
        }

        final List<String> tags = new ArrayList<>();
        final JsonNode tagsNode = root.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode t : tagsNode) {
                final String s = t.asText("").trim();
                if (!s.isEmpty()) {
                    tags.add(s);
                }
            }
        }

        final String content = buildMergedMarkdown(incoming.getType(), title, tags, mergedDerivedFrom, body);

        return GeneratedPage.builder().type(incoming.getType()).slug(incoming.getSlug()).title(title).content(content)
                .tags(tags).derivedFrom(mergedDerivedFrom)
                // The merge has already happened — tell the storage layer to unconditionally write the result.
                .strategy(GeneratedPage.UpdateStrategy.REPLACE).build();
    }

    /**
     * Deterministic fallback used whenever the LLM merge cannot complete cleanly. Appends the incoming page's
     * body to the existing page's body under a clearly marked update section. This is information-preserving by
     * design: losing data on a merge is the worst possible failure mode for a long-lived wiki page.
     */
    static GeneratedPage buildAppendOnlyFallback(WikiPage existing, GeneratedPage incoming,
            List<String> mergedDerivedFrom) {
        final String existingBody = stripFrontmatter(existing.getContent());
        final String incomingBody = stripFrontmatter(incoming.getContent());

        final String incomingSourceLabel = incoming.getDerivedFrom().isEmpty()
                ? "new content"
                : incoming.getDerivedFrom().get(0);

        // Use a union of existing + incoming tags so the merged page is at least as discoverable as before.
        final List<String> mergedTags = mergeTags(existing.getTags(), incoming.getTags());

        final StringBuilder sb = new StringBuilder();
        sb.append(existingBody);
        if (!existingBody.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\n## Update from ").append(incomingSourceLabel).append("\n\n");
        sb.append(incomingBody);
        if (!incomingBody.endsWith("\n")) {
            sb.append('\n');
        }

        final String content = buildMergedMarkdown(incoming.getType(), existing.getTitle(), mergedTags,
                mergedDerivedFrom, sb.toString());

        return GeneratedPage.builder().type(incoming.getType()).slug(incoming.getSlug()).title(existing.getTitle())
                .content(content).tags(mergedTags).derivedFrom(mergedDerivedFrom)
                .strategy(GeneratedPage.UpdateStrategy.REPLACE).build();
    }

    /**
     * Returns the union of two derived-from lists, preserving order from the existing list first and
     * de-duplicating exact matches. This is how a long-lived entity/concept page accumulates the set of sources
     * that have contributed to it.
     */
    static List<String> mergeDerivedFrom(List<String> existingDerivedFrom, List<String> incomingDerivedFrom) {
        final Set<String> seen = new LinkedHashSet<>();
        if (existingDerivedFrom != null) {
            seen.addAll(existingDerivedFrom);
        }
        if (incomingDerivedFrom != null) {
            seen.addAll(incomingDerivedFrom);
        }
        return Collections.unmodifiableList(new ArrayList<>(seen));
    }

    private static List<String> mergeTags(List<String> a, List<String> b) {
        final Set<String> seen = new LinkedHashSet<>();
        if (a != null) {
            seen.addAll(a);
        }
        if (b != null) {
            seen.addAll(b);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Strips a leading {@code ---\n...\n---\n} YAML frontmatter block, returning just the markdown body. Used by
     * the append-only fallback so the existing frontmatter doesn't get duplicated when the body is re-wrapped.
     *
     * <p>
     * Any blank lines that follow the closing {@code ---} fence (a common cosmetic separator) are also consumed,
     * so the returned body starts with the first non-empty line. The result is left untouched if the input does
     * not begin with a frontmatter block, or if the closing fence is missing.
     */
    static String stripFrontmatter(String content) {
        if (content == null || content.isEmpty() || !content.startsWith("---\n")) {
            return content == null ? "" : content;
        }
        final int closing = content.indexOf("\n---", 4);
        if (closing < 0) {
            return content;
        }
        int after = closing + 4;
        // Consume all newline characters that follow the closing fence so the returned body starts at the first
        // line of actual content. This handles both the canonical "---\n\n# Title" form and the more compact
        // "---\n# Title" form without leaving a stray leading newline.
        while (after < content.length() && content.charAt(after) == '\n') {
            after++;
        }
        return content.substring(after);
    }

    /**
     * Wraps a merged body in canonical wiki frontmatter. Mirrors {@link LlmWikiPageGenerator}'s frontmatter shape
     * so a merged page is indistinguishable on disk from a freshly extracted one.
     */
    private static String buildMergedMarkdown(WikiPageType type, String title, List<String> tags,
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

    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    /** Returns the configured maximum merge input length. */
    public int getMaxMergeInputLength() {
        return maxMergeInputLength;
    }

    /**
     * Builder for {@link LlmWikiPageMerger}.
     */
    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxMergeInputLength = DEFAULT_MAX_MERGE_INPUT_LENGTH;
        private String mergeSystemPrompt;
        private LlmCallMetadata llmCallMetadata;

        private Builder() {
        }

        /** Sets the {@link LlmClient}. Required. */
        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        /** Sets the LLM model configuration. Optional, defaults to provider default. */
        public Builder modelConfig(LlmModel modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Sets the maximum combined characters of existing + incoming content sent to the LLM.
         *
         * @param maxMergeInputLength
         *            the maximum length (must be positive)
         * @return this builder
         */
        public Builder maxMergeInputLength(int maxMergeInputLength) {
            if (maxMergeInputLength <= 0) {
                throw new IllegalArgumentException("maxMergeInputLength must be positive");
            }
            this.maxMergeInputLength = maxMergeInputLength;
            return this;
        }

        /** Overrides the default merge system prompt. Optional. */
        public Builder mergeSystemPrompt(String mergeSystemPrompt) {
            this.mergeSystemPrompt = mergeSystemPrompt;
            return this;
        }

        /**
         * Overrides the LLM call metadata used for usage attribution. Caller-supplied fields win on collision; any
         * unset fields fall back to the defaults ({@code component=wiki-merger}, {@code feature=page-merge}).
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /** Builds the merger. */
        public LlmWikiPageMerger build() {
            return new LlmWikiPageMerger(this);
        }
    }
}
