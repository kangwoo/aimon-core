package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
 * LLM-powered {@link WikiAnswerStrategy}.
 *
 * <p>
 * Builds a single prompt from the user question and the supporting wiki pages, asks the LLM for a strict-JSON
 * envelope describing the answer, and parses it into an {@link Answer}. On any failure (LLM unavailable, parse
 * error, blank response) it falls back to a deterministic answer that lists the page titles — losing the
 * synthesis but preserving the support trail so the caller can still decide what to do with the result.
 *
 * <p>
 * Page bodies are sent to the LLM in lightweight form (frontmatter stripped, body truncated at
 * {@link #getMaxBodyCharsPerPage()}) to keep the prompt size predictable across wikis with very long pages. The
 * default budget is {@link #DEFAULT_MAX_BODY_CHARS_PER_PAGE} characters per page.
 *
 * <pre>{@code
 * WikiAnswerStrategy strategy = LlmWikiAnswerStrategy.builder()
 *         .llmClient(llmClient)
 *         .modelConfig(LlmModel.builder().name("gpt-4o-mini").build())
 *         .build();
 * }</pre>
 *
 * Thread-safe and stateless. The injected {@link LlmClient} is not owned by this instance.
 */
public final class LlmWikiAnswerStrategy implements WikiAnswerStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmWikiAnswerStrategy.class);

    /** Default per-page body budget (characters) included in the answer prompt. */
    public static final int DEFAULT_MAX_BODY_CHARS_PER_PAGE = 4_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    // @formatter:off
    private static final String DEFAULT_ANSWER_SYSTEM_PROMPT = """
            You are a wiki knowledge base assistant. You will be given a USER QUESTION and a small set of \
            SUPPORTING WIKI PAGES that have been retrieved from the wiki as the most relevant context.

            Your task is to answer the question using ONLY the supporting pages. Do not invent facts that are \
            not present in the pages. If the supporting pages do not contain enough information to answer the \
            question, say so explicitly in the answer.

            Output STRICT JSON only, with no markdown fences and no commentary, in this exact shape:

            {
              "title": "Short human-readable title summarizing the answer",
              "body": "# Answer Title\\n\\n<markdown body of the answer with [[slug]] cross-references where useful>"
            }

            Rules:
              * "title" MUST be non-empty and stable across runs for the same question.
              * "body" must NOT include YAML frontmatter — the host writes the frontmatter when filing the answer.
              * Use [[slug]] wiki-links to cite the supporting pages where the corresponding fact appears. The \
            slug for each supporting page is provided alongside its content below.
              * Do NOT wrap the JSON in code fences. Do NOT add explanation before or after the JSON object.""";
    // @formatter:on

    private static final String DEFAULT_COMPONENT = "wiki-answer";
    private static final LlmCallMetadata DEFAULT_ANSWER_METADATA = LlmCallMetadata.builder()
            .component(DEFAULT_COMPONENT).feature("answer-generation").build();

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxBodyCharsPerPage;
    private final String answerSystemPrompt;
    private final LlmCallMetadata answerCallMetadata;

    private LlmWikiAnswerStrategy(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxBodyCharsPerPage = builder.maxBodyCharsPerPage;
        this.answerSystemPrompt = builder.answerSystemPrompt != null
                ? builder.answerSystemPrompt
                : DEFAULT_ANSWER_SYSTEM_PROMPT;
        this.answerCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_ANSWER_METADATA)
                : DEFAULT_ANSWER_METADATA;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the configured per-page body budget. */
    public int getMaxBodyCharsPerPage() {
        return maxBodyCharsPerPage;
    }

    @Override
    public Answer answer(WikiScope scope, AnswerRequest request, List<WikiPage> contextPages) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(contextPages, "contextPages must not be null");

        final List<String> sourceRefs = new ArrayList<>();
        for (WikiPage page : contextPages) {
            sourceRefs.add(page.getPath());
        }

        // No context at all → return a deterministic "no information found" answer rather than calling the
        // LLM. Saves a token round-trip when the wiki is empty for this query.
        if (contextPages.isEmpty()) {
            return Answer.builder().question(request.getQuestion()).title(deriveTitle(request.getQuestion()))
                    .text("No matching wiki pages were found for this question.").sourceRefs(Collections.emptyList())
                    .llmCallCount(0).build();
        }

        try {
            final String userMessage = buildAnswerPrompt(request.getQuestion(), request.getFormat(), contextPages);
            final LlmResponse response = llmClient.sendMessage(answerSystemPrompt, List.of(Message.user(userMessage)),
                    Collections.emptyList(), modelConfig, answerCallMetadata.withTags(scopeTags(scope)));

            if (response.hasTextContent() && !response.getTextContent().isBlank()) {
                final Answer parsed = parseAnswerResponse(response.getTextContent(), request.getQuestion(), sourceRefs);
                if (parsed != null) {
                    log.debug("LLM produced answer for question '{}' in scope {}", request.getQuestion(), scope);
                    return parsed;
                }
                log.warn("LLM answer response was unparseable for question '{}', using fallback",
                        request.getQuestion());
            } else {
                log.warn("LLM answer response was empty for question '{}', using fallback", request.getQuestion());
            }
        } catch (Exception e) {
            log.warn("LLM answer call failed for question '{}', using fallback: {}", request.getQuestion(),
                    e.getMessage());
        }

        return buildFallbackAnswer(request.getQuestion(), contextPages, sourceRefs);
    }

    /**
     * Builds the answer user prompt: the question, an optional format hint, then a numbered list of
     * supporting pages, each with its slug, title, type, and a truncated body. The slug is what the LLM is
     * told to use for [[wiki-links]].
     *
     * <p>
     * When {@code format} is non-null, the prompt tells the LLM to shape the answer body as that form (e.g.,
     * "comparison table", "Marp slide deck", "step-by-step guide"). The host still expects markdown in the
     * JSON envelope — the hint only changes the shape of that markdown, not the transport format.
     */
    private String buildAnswerPrompt(String question, String format, List<WikiPage> contextPages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("USER QUESTION:\n").append(question).append("\n\n");
        if (format != null) {
            sb.append("ANSWER FORMAT HINT:\nShape the answer body as: ").append(format)
                    .append(". The body must still be valid markdown inside the JSON envelope.\n\n");
        }
        sb.append("SUPPORTING WIKI PAGES:\n\n");
        for (int i = 0; i < contextPages.size(); i++) {
            final WikiPage page = contextPages.get(i);
            final String slug = slugFromPath(page.getPath());
            sb.append("--- Page ").append(i + 1).append(" ---\n");
            sb.append("slug: ").append(slug).append('\n');
            sb.append("title: ").append(page.getTitle()).append('\n');
            sb.append("type: ").append(page.getType().getToken()).append('\n');
            if (!page.getTags().isEmpty()) {
                sb.append("tags: ").append(String.join(", ", page.getTags())).append('\n');
            }
            sb.append("body:\n").append(truncate(stripFrontmatter(page.getContent()), maxBodyCharsPerPage))
                    .append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Parses the JSON envelope returned by the answer prompt into an {@link Answer}. Returns {@code null} when
     * the response is unusable; the caller falls back to the deterministic path.
     */
    static Answer parseAnswerResponse(String rawResponse, String question, List<String> sourceRefs) {
        final String stripped = CodeFences.strip(rawResponse);
        final JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (Exception e) {
            log.warn("Failed to parse answer JSON for question '{}': {}", question, e.getMessage());
            return null;
        }
        final String title = root.path("title").asText("").trim();
        final String body = root.path("body").asText("");
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Answer response for question '{}' has empty title or body", question);
            return null;
        }
        return Answer.builder().question(question).title(title).text(body).sourceRefs(sourceRefs).llmCallCount(1)
                .build();
    }

    /**
     * Builds the deterministic fallback answer used whenever the LLM cannot produce a clean response. Lists
     * the supporting page titles so the caller still knows what context was available — losing synthesis but
     * never losing the support trail.
     */
    static Answer buildFallbackAnswer(String question, List<WikiPage> contextPages, List<String> sourceRefs) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# ").append(deriveTitle(question)).append("\n\n");
        sb.append("The wiki contains the following pages relevant to this question, but an LLM-synthesized "
                + "answer could not be produced. Refer to the supporting pages directly:\n\n");
        for (WikiPage page : contextPages) {
            sb.append("- [[").append(slugFromPath(page.getPath())).append("]] — ").append(page.getTitle()).append('\n');
        }
        return Answer.builder().question(question).title(deriveTitle(question)).text(sb.toString())
                .sourceRefs(sourceRefs).llmCallCount(0).build();
    }

    private static String deriveTitle(String question) {
        final String trimmed = question.trim();
        if (trimmed.length() <= 80) {
            return trimmed;
        }
        return trimmed.substring(0, 77) + "...";
    }

    /**
     * Strips a leading {@code ---\n...\n---\n} YAML frontmatter block. Same logic as
     * {@link LlmWikiPageMerger#stripFrontmatter} but duplicated to keep this strategy independent of the
     * merger's package-private surface.
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
        while (after < content.length() && content.charAt(after) == '\n') {
            after++;
        }
        return content.substring(after);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "\n[... truncated]";
    }

    private static String slugFromPath(String path) {
        final int lastSlash = path.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (fileName.endsWith(".md")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        for (WikiPageType type : WikiPageType.values()) {
            final String prefix = type.getPrefix() + "-";
            if (fileName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return fileName.substring(prefix.length());
            }
        }
        return fileName;
    }

    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    /** Builder for {@link LlmWikiAnswerStrategy}. */
    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxBodyCharsPerPage = DEFAULT_MAX_BODY_CHARS_PER_PAGE;
        private String answerSystemPrompt;
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
         * Sets the per-page body budget (characters). Pages whose body exceeds this limit are truncated with a
         * notice in the prompt.
         *
         * @param maxBodyCharsPerPage
         *            the limit (must be positive)
         * @return this builder
         */
        public Builder maxBodyCharsPerPage(int maxBodyCharsPerPage) {
            if (maxBodyCharsPerPage <= 0) {
                throw new IllegalArgumentException("maxBodyCharsPerPage must be positive");
            }
            this.maxBodyCharsPerPage = maxBodyCharsPerPage;
            return this;
        }

        /** Overrides the default answer system prompt. Optional. */
        public Builder answerSystemPrompt(String answerSystemPrompt) {
            this.answerSystemPrompt = answerSystemPrompt;
            return this;
        }

        /** Overrides the LLM call metadata used for usage attribution. */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /** Builds the strategy. */
        public LlmWikiAnswerStrategy build() {
            return new LlmWikiAnswerStrategy(this);
        }
    }
}
