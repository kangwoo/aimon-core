package at.aimon.core.tools.wiki;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBaseAdmin;
import at.aimon.core.knowledge.wiki.WikiLog;
import at.aimon.core.knowledge.wiki.WikiLogEntry;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiStatus;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool that reports the current state of the agent's wiki knowledge base.
 *
 * <p>
 * Returns page count, source count, last ingestion time, wiki directory, and overall state. Optionally includes a
 * bounded audit log of recent operations.
 *
 * <p>
 * The wiki knowledge base and scope are obtained from the {@link ToolContext} using
 * {@link ToolContextKeys#WIKI_KNOWLEDGE_BASE} and {@link ToolContextKeys#WIKI_SCOPE}. If {@code include_log} is
 * {@code true}, the admin interface is also obtained from {@link ToolContextKeys#WIKI_KNOWLEDGE_BASE_ADMIN}.
 *
 * <p>
 * Usage by LLM:
 *
 * <pre>
 * WikiStatus()
 * WikiStatus(include_log: true, log_limit: 20)
 * </pre>
 *
 * @see WikiKnowledgeBase
 * @see WikiKnowledgeBaseAdmin
 * @see WikiStatus
 */
public class WikiStatusTool extends AbstractTool {

    public static final String TOOL_NAME = "WikiStatus";

    private static final Logger log = LoggerFactory.getLogger(WikiStatusTool.class);

    private static final int DEFAULT_LOG_LIMIT = 10;

    /**
     * Creates a WikiStatusTool.
     */
    public WikiStatusTool() {
        super(TOOL_NAME,
                "Report the current state of the agent's wiki knowledge base. "
                        + "Shows page count, source count, last ingestion time, and overall state. "
                        + "Optionally includes a recent audit log of wiki operations.",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("include_log",
                Map.of("type", "boolean", "description", "Whether to include the recent audit log (default: false)"),
                "log_limit",
                Map.of("type", "number", "description",
                        "Maximum number of log entries to return when include_log is true (default: "
                                + DEFAULT_LOG_LIMIT + ")")),
                "required", List.of());
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Reports on the wiki's current state without altering it.
        return SideEffectLevel.READ_ONLY;
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

            final boolean includeLog = input.getBoolean("include_log", false);
            final int logLimit = input.getInteger("log_limit", DEFAULT_LOG_LIMIT);
            if (logLimit < 1) {
                return ToolResult.error("log_limit must be >= 1, got: " + logLimit);
            }

            log.debug("Fetching wiki status for scope={}, includeLog={}", scope, includeLog);

            final WikiStatus status = wiki.getStatus(scope);

            final Optional<WikiLog> wikiLog = includeLog ? fetchLog(context, scope, logLimit) : Optional.empty();

            log.debug("Wiki status: {}", status);
            return ToolResult.success(formatStatus(scope, status, wikiLog));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to fetch wiki status: {}", e.getMessage(), e);
            return ToolResult.error("Failed to fetch wiki status: " + e.getMessage());
        }
    }

    private static Optional<WikiLog> fetchLog(ToolContext context, WikiScope scope, int logLimit) {
        final WikiKnowledgeBaseAdmin admin = context.get(ToolContextKeys.WIKI_KNOWLEDGE_BASE_ADMIN).orElse(null);
        if (admin == null) {
            log.warn("include_log=true but no wiki admin configured; log will be omitted");
            return Optional.empty();
        }
        return Optional.ofNullable(admin.getLog(scope, logLimit));
    }

    private static String formatStatus(WikiScope scope, WikiStatus status, Optional<WikiLog> wikiLog) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Wiki Status\n");
        sb.append("===========\n");
        sb.append("Scope          : ").append(scope.getAgentName()).append(" / ").append(scope.getWikiName())
                .append('\n');
        sb.append("State          : ").append(status.getState()).append('\n');
        sb.append("Pages          : ").append(status.getPageCount()).append('\n');
        sb.append("Sources        : ").append(status.getSourceCount()).append('\n');

        if (status.getWikiDirectory() != null) {
            sb.append("Directory      : ").append(status.getWikiDirectory()).append('\n');
        }

        if (status.getLastIngestedAt() != null) {
            sb.append("Last ingested  : ").append(status.getLastIngestedAt()).append('\n');
        } else {
            sb.append("Last ingested  : never\n");
        }

        wikiLog.ifPresent(logValue -> appendLog(sb, logValue));

        return sb.toString();
    }

    private static void appendLog(StringBuilder sb, WikiLog wikiLog) {
        sb.append("\nRecent Log (showing ").append(wikiLog.getEntries().size()).append(" of ")
                .append(wikiLog.getTotalEntryCount()).append(" entries):\n");

        if (wikiLog.getEntries().isEmpty()) {
            sb.append("  (no log entries)\n");
            return;
        }
        for (final WikiLogEntry entry : wikiLog.getEntries()) {
            sb.append("  [").append(entry.getTimestamp()).append("] ");
            sb.append(entry.getOperation());
            if (entry.getPagePath() != null) {
                sb.append(" — ").append(entry.getPagePath());
            }
            if (entry.getSummary() != null) {
                sb.append(" (").append(entry.getSummary()).append(')');
            }
            sb.append('\n');
        }
    }
}
