package at.aimon.core.tools.wiki;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.wiki.IngestOptions;
import at.aimon.core.knowledge.wiki.IngestResult;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiSource;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool that ingests documents from a VFS directory into the agent's wiki knowledge base.
 *
 * <p>
 * Reads raw source documents from the specified directory on the configured {@link VirtualFileSystem}, processes them,
 * and indexes them in the {@link WikiKnowledgeBase}. The wiki knowledge base and scope are obtained from the
 * {@link ToolContext} using {@link ToolContextKeys#WIKI_KNOWLEDGE_BASE} and {@link ToolContextKeys#WIKI_SCOPE}.
 * The file system is obtained from {@link ToolContextKeys#VIRTUAL_FILE_SYSTEM}.
 *
 * <p>
 * Usage by LLM:
 *
 * <pre>
 * WikiIngest(source_directory: "/raw/articles")
 * WikiIngest(source_directory: "/raw/runbooks", overwrite: true, file_patterns: "*.md")
 * </pre>
 *
 * @see WikiKnowledgeBase
 * @see WikiSource
 * @see IngestOptions
 */
public class WikiIngestTool extends AbstractTool {

    public static final String TOOL_NAME = "WikiIngest";

    private static final Logger log = LoggerFactory.getLogger(WikiIngestTool.class);

    /**
     * Creates a WikiIngestTool.
     */
    public WikiIngestTool() {
        super(TOOL_NAME,
                "Ingest documents from a VFS directory into the agent's wiki knowledge base. "
                        + "Reads raw source files and builds a searchable wiki index. "
                        + "Use this tool to populate or update the wiki with new content.",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("source_directory",
                Map.of("type", "string", "description",
                        "The VFS directory path containing raw source documents to ingest"),
                "overwrite",
                Map.of("type", "boolean", "description", "Whether to overwrite existing wiki pages (default: false)"),
                "file_patterns",
                Map.of("type", "string", "description",
                        "Comma-separated glob patterns for selecting files (default: '*.md,*.txt'). "
                                + "Example: '*.md,*.txt'")),
                "required", List.of("source_directory"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final WikiKnowledgeBase wiki = context.get(ToolContextKeys.WIKI_KNOWLEDGE_BASE).orElse(null);
            if (wiki == null) {
                return ToolResult.error("No wiki knowledge base configured for this agent");
            }

            final WikiScope scope = context.get(ToolContextKeys.WIKI_SCOPE).orElse(null);
            if (scope == null) {
                return ToolResult.error("No wiki scope configured for this agent");
            }

            final VirtualFileSystem vfs = context.get(ToolContextKeys.VIRTUAL_FILE_SYSTEM).orElse(null);
            if (vfs == null) {
                return ToolResult.error("No virtual file system configured for this agent");
            }

            final String sourceDirectory = input.getRequiredString("source_directory");
            final boolean overwrite = input.getBoolean("overwrite", false);
            final String filePatternsRaw = input.getStringOrNull("file_patterns");

            log.debug("Ingesting wiki from directory='{}', overwrite={}, scope={}", sourceDirectory, overwrite, scope);

            final IngestOptions.Builder optionsBuilder = IngestOptions.builder().overwrite(overwrite);

            if (filePatternsRaw != null && !filePatternsRaw.isBlank()) {
                final List<String> patterns = Arrays.stream(filePatternsRaw.split(",")).map(String::trim)
                        .filter(p -> !p.isEmpty()).collect(Collectors.toList());
                if (!patterns.isEmpty()) {
                    optionsBuilder.filePatterns(patterns);
                }
            }

            final WikiSource source = WikiSource.builder().fileSystem(vfs).directory(sourceDirectory).build();
            final IngestOptions options = optionsBuilder.build();

            final IngestResult result = wiki.ingest(scope, source, options);

            log.debug("Wiki ingest completed: {}", result);
            return ToolResult.success(formatResult(sourceDirectory, result));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to ingest wiki: {}", e.getMessage(), e);
            return ToolResult.error("Failed to ingest wiki: " + e.getMessage());
        }
    }

    private static String formatResult(String sourceDirectory, IngestResult result) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Wiki ingest completed from: ").append(sourceDirectory).append('\n');
        sb.append("  Documents ingested : ").append(result.getIngestedCount()).append('\n');
        sb.append("  Documents skipped  : ").append(result.getSkippedCount()).append('\n');
        sb.append("  Pages created      : ").append(result.getCreatedPageCount()).append('\n');
        sb.append("  Pages updated      : ").append(result.getUpdatedPageCount()).append('\n');
        sb.append("  Duration           : ").append(result.getDurationMs()).append(" ms\n");

        if (!result.getErrors().isEmpty()) {
            sb.append("  Errors (").append(result.getErrors().size()).append("):\n");
            for (final String error : result.getErrors()) {
                sb.append("    - ").append(error).append('\n');
            }
        }

        return sb.toString();
    }
}
