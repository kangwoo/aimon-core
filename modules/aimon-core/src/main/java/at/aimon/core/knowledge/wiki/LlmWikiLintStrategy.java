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
 * LLM-powered {@link WikiLintStrategy} that detects the semantic health problems described in
 * {@code docs/references/llm-wiki.md}: contradictions between pages, stale claims, missing concept pages, and
 * data gaps. These are the checks that require actually reading page content — the structural lint already
 * done by {@link DefaultWikiKnowledgeBase#lint(WikiScope)} covers the cheap link/graph checks.
 *
 * <p>
 * The strategy makes a single LLM call per pass with a JSON envelope request: the user message lists every
 * page (title, type, tags, truncated body) and the system prompt asks for a JSON array of findings. One call,
 * bounded input, bounded output — no per-page fan-out. Page bodies are truncated at
 * {@link #getMaxBodyCharsPerPage()} characters and the total prompt is capped at
 * {@link #getMaxTotalPromptChars()} so a huge wiki can't accidentally burn through tokens.
 *
 * <p>
 * <b>Failure model</b>: LLM failure, parse failure, blank response, or malformed JSON all degrade to
 * "returned no findings" — the caller still gets the structural lint results from {@code lint()}. Nothing is
 * thrown.
 *
 * <p>
 * <b>Thread-safe</b>, but not fully stateless: a {@link ThreadLocal} tracks {@link #getLastCallCount()} per
 * calling thread so the storage layer can observe the count right after a {@code lint()} call without
 * threading it through the return value. Concurrent calls from different threads see isolated counts. The
 * injected {@link LlmClient} is not owned by this instance.
 */
public final class LlmWikiLintStrategy implements WikiLintStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmWikiLintStrategy.class);

    /** Default per-page body budget (characters) included in the lint prompt. */
    public static final int DEFAULT_MAX_BODY_CHARS_PER_PAGE = 2_000;

    /** Default hard cap on the total prompt size sent to the LLM. */
    public static final int DEFAULT_MAX_TOTAL_PROMPT_CHARS = 60_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    // @formatter:off
    private static final String DEFAULT_LINT_SYSTEM_PROMPT = """
            You are a wiki knowledge base health checker. You will be given a list of WIKI PAGES. Your task is \
            to read them and report semantic problems that can only be found by reasoning about the content — \
            not the link graph (the host already checks links and orphans separately).

            Look specifically for:

              1. CONTRADICTION: two or more pages state incompatible facts about the same subject.
              2. STALE: a page's claim has been superseded by information in another page. Report the stale \
            page, not the newer one.
              3. MISSING_CONCEPT: a concept or entity is repeatedly referenced in page bodies but has no \
            dedicated page of its own.
              4. DATA_GAP: an important question about the wiki's subject matter is clearly under-supported \
            by the current pages.

            Output STRICT JSON only, with no markdown fences or commentary, in this exact shape:

            {
              "findings": [
                {
                  "kind": "contradiction|stale|missing_concept|data_gap",
                  "severity": "info|warning",
                  "page_path": "/wiki/.../pages/entity-pod.md",
                  "message": "One-sentence description of the issue."
                }
              ]
            }

            Rules:
              * "findings" may be empty when the wiki is healthy.
              * "page_path" is optional for wiki-wide findings (MISSING_CONCEPT, DATA_GAP) — set it to null or \
            omit it in those cases. For CONTRADICTION and STALE it should point at the page being flagged.
              * Use "warning" severity for CONTRADICTION and STALE, "info" for MISSING_CONCEPT and DATA_GAP.
              * Be conservative — only report clear issues, not stylistic nitpicks.
              * Do NOT wrap the JSON in code fences.""";
    // @formatter:on

    private static final String DEFAULT_COMPONENT = "wiki-lint";
    private static final LlmCallMetadata DEFAULT_LINT_METADATA = LlmCallMetadata.builder().component(DEFAULT_COMPONENT)
            .feature("semantic-lint").build();

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxBodyCharsPerPage;
    private final int maxTotalPromptChars;
    private final String lintSystemPrompt;
    private final LlmCallMetadata lintCallMetadata;

    private final ThreadLocal<Integer> lastCallCount = ThreadLocal.withInitial(() -> 0);

    private LlmWikiLintStrategy(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxBodyCharsPerPage = builder.maxBodyCharsPerPage;
        this.maxTotalPromptChars = builder.maxTotalPromptChars;
        this.lintSystemPrompt = builder.lintSystemPrompt != null
                ? builder.lintSystemPrompt
                : DEFAULT_LINT_SYSTEM_PROMPT;
        this.lintCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_LINT_METADATA)
                : DEFAULT_LINT_METADATA;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the configured per-page body budget. */
    public int getMaxBodyCharsPerPage() {
        return maxBodyCharsPerPage;
    }

    /** Returns the configured total prompt size cap. */
    public int getMaxTotalPromptChars() {
        return maxTotalPromptChars;
    }

    @Override
    public List<LintReport.Issue> lint(WikiScope scope, List<WikiPage> pages) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(pages, "pages must not be null");

        lastCallCount.set(0);

        if (pages.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            final String userMessage = buildLintPrompt(pages);
            lastCallCount.set(1);
            final LlmResponse response = llmClient.sendMessage(lintSystemPrompt, List.of(Message.user(userMessage)),
                    Collections.emptyList(), modelConfig, lintCallMetadata.withTags(scopeTags(scope)));

            if (!response.hasTextContent() || response.getTextContent().isBlank()) {
                log.warn("Semantic lint LLM returned empty content for scope {}", scope);
                return Collections.emptyList();
            }
            return parseLintResponse(response.getTextContent());
        } catch (Exception e) {
            log.warn("Semantic lint LLM failed for scope {}: {}", scope, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public int getLastCallCount() {
        return lastCallCount.get();
    }

    /**
     * Builds the user prompt by listing every page with a truncated body. Pages are included in order; if the
     * total prompt size would exceed {@link #getMaxTotalPromptChars()}, the remaining pages are dropped with a
     * truncation notice so the call still fits a predictable token budget.
     */
    private String buildLintPrompt(List<WikiPage> pages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("WIKI PAGES:\n\n");
        int dropped = 0;
        for (WikiPage page : pages) {
            final String block = buildPageBlock(page);
            if (sb.length() + block.length() > maxTotalPromptChars) {
                dropped++;
                continue;
            }
            sb.append(block);
        }
        if (dropped > 0) {
            sb.append("\n[... ").append(dropped).append(" pages omitted from prompt due to size cap]\n");
            log.warn("Semantic lint prompt capped at {} chars — dropped {} pages", maxTotalPromptChars, dropped);
        }
        return sb.toString();
    }

    private String buildPageBlock(WikiPage page) {
        final StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(page.getPath()).append(" ---\n");
        sb.append("title: ").append(page.getTitle()).append('\n');
        sb.append("type: ").append(page.getType().getToken()).append('\n');
        if (!page.getTags().isEmpty()) {
            sb.append("tags: ").append(String.join(", ", page.getTags())).append('\n');
        }
        sb.append("body:\n").append(truncate(stripFrontmatter(page.getContent()), maxBodyCharsPerPage)).append("\n\n");
        return sb.toString();
    }

    /**
     * Parses the JSON envelope returned by the lint prompt into {@link LintReport.Issue}s. Returns an empty
     * list when the response is unusable — callers already have the structural lint findings and can safely
     * proceed without semantic ones.
     */
    static List<LintReport.Issue> parseLintResponse(String rawResponse) {
        final String stripped = CodeFences.strip(rawResponse);
        final JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (Exception e) {
            log.warn("Failed to parse semantic lint JSON: {}", e.getMessage());
            return Collections.emptyList();
        }

        final JsonNode findings = root.path("findings");
        if (!findings.isArray() || findings.isEmpty()) {
            return Collections.emptyList();
        }

        final List<LintReport.Issue> issues = new ArrayList<>();
        for (JsonNode finding : findings) {
            final LintReport.Issue issue = parseSingleFinding(finding);
            if (issue != null) {
                issues.add(issue);
            }
        }
        return issues;
    }

    private static LintReport.Issue parseSingleFinding(JsonNode finding) {
        final String kind = finding.path("kind").asText("").trim().toLowerCase(Locale.ROOT);
        final String severityToken = finding.path("severity").asText("").trim().toLowerCase(Locale.ROOT);
        final String message = finding.path("message").asText("").trim();
        final String pagePath = finding.hasNonNull("page_path") ? finding.path("page_path").asText("").trim() : null;

        if (kind.isEmpty() || message.isEmpty()) {
            log.warn("Skipping semantic lint finding with empty kind/message");
            return null;
        }

        final LintReport.Severity severity;
        switch (severityToken) {
            case "warning" :
                severity = LintReport.Severity.WARNING;
                break;
            case "error" :
                // Not emitted by the prompt but tolerated so a minor LLM deviation doesn't drop the finding.
                severity = LintReport.Severity.ERROR;
                break;
            default :
                severity = LintReport.Severity.INFO;
                break;
        }

        // Prefix the message with the finding kind so readers of LintReport can tell the semantic findings
        // apart from the structural ones at a glance.
        final String labeled = "[" + kind.toUpperCase(Locale.ROOT) + "] " + message;
        return new LintReport.Issue(severity, pagePath != null && !pagePath.isEmpty() ? pagePath : null, labeled);
    }

    private static String stripFrontmatter(String content) {
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

    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    /** Builder for {@link LlmWikiLintStrategy}. */
    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxBodyCharsPerPage = DEFAULT_MAX_BODY_CHARS_PER_PAGE;
        private int maxTotalPromptChars = DEFAULT_MAX_TOTAL_PROMPT_CHARS;
        private String lintSystemPrompt;
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
         * Sets the per-page body budget.
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

        /**
         * Sets the total prompt size cap.
         *
         * @param maxTotalPromptChars
         *            the cap (must be positive)
         * @return this builder
         */
        public Builder maxTotalPromptChars(int maxTotalPromptChars) {
            if (maxTotalPromptChars <= 0) {
                throw new IllegalArgumentException("maxTotalPromptChars must be positive");
            }
            this.maxTotalPromptChars = maxTotalPromptChars;
            return this;
        }

        /** Overrides the default lint system prompt. Optional. */
        public Builder lintSystemPrompt(String lintSystemPrompt) {
            this.lintSystemPrompt = lintSystemPrompt;
            return this;
        }

        /** Overrides the LLM call metadata used for usage attribution. */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /** Builds the strategy. */
        public LlmWikiLintStrategy build() {
            return new LlmWikiLintStrategy(this);
        }
    }
}
