package at.aimon.core.tools.wiki;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.wiki.LintReport;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBaseAdmin;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool that performs a health check (lint) on the agent's wiki knowledge base.
 *
 * <p>
 * Validates the integrity of all indexed wiki pages within the current scope, checking for broken links, missing
 * metadata, and malformed content. Returns a structured report of any issues found, grouped by severity.
 *
 * <p>
 * The wiki admin and scope are obtained from the {@link ToolContext} using
 * {@link ToolContextKeys#WIKI_KNOWLEDGE_BASE_ADMIN} and {@link ToolContextKeys#WIKI_SCOPE}.
 *
 * <p>
 * Usage by LLM:
 *
 * <pre>
 * WikiLint()
 * </pre>
 *
 * @see WikiKnowledgeBaseAdmin
 * @see LintReport
 */
public class WikiLintTool extends AbstractTool {

    public static final String TOOL_NAME = "WikiLint";

    private static final Logger log = LoggerFactory.getLogger(WikiLintTool.class);

    /**
     * Creates a WikiLintTool.
     */
    public WikiLintTool() {
        super(TOOL_NAME,
                "Validate the integrity of the agent's wiki knowledge base. "
                        + "Checks for broken links, missing metadata, and malformed content. "
                        + "Returns a report of issues grouped by severity (ERROR, WARNING, INFO).",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of(), "required", List.of());
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Reports diagnostics only; it never rewrites the pages it inspects.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final WikiKnowledgeBaseAdmin admin = context.get(ToolContextKeys.WIKI_KNOWLEDGE_BASE_ADMIN).orElse(null);
            if (admin == null) {
                return ToolResult.error("No wiki knowledge base admin configured for this agent");
            }

            final WikiScope scope = context.get(ToolContextKeys.WIKI_SCOPE).orElse(null);
            if (scope == null) {
                return ToolResult.error("No wiki scope configured for this agent");
            }

            log.debug("Running wiki lint for scope={}", scope);

            final LintReport report = admin.lint(scope);

            log.debug("Wiki lint completed: {} issue(s) across {} page(s)", report.getIssues().size(),
                    report.getCheckedPageCount());
            return ToolResult.success(formatReport(report));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to lint wiki: {}", e.getMessage(), e);
            return ToolResult.error("Failed to lint wiki: " + e.getMessage());
        }
    }

    private static String formatReport(LintReport report) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Wiki Lint Report\n");
        sb.append("================\n");
        sb.append("Checked at : ").append(report.getCheckedAt()).append('\n');
        sb.append("Pages      : ").append(report.getCheckedPageCount()).append('\n');

        if (report.isHealthy()) {
            sb.append("Status     : HEALTHY — no issues found\n");
            return sb.toString();
        }

        sb.append("Issues     : ").append(report.getIssues().size()).append(" (");
        sb.append("ERROR: ").append(report.countBySeverity(LintReport.Severity.ERROR));
        sb.append(", WARNING: ").append(report.countBySeverity(LintReport.Severity.WARNING));
        sb.append(", INFO: ").append(report.countBySeverity(LintReport.Severity.INFO));
        sb.append(")\n\n");

        for (final LintReport.Severity severity : LintReport.Severity.values()) {
            final List<LintReport.Issue> group = report.getIssues().stream().filter(i -> i.getSeverity() == severity)
                    .toList();
            if (!group.isEmpty()) {
                sb.append(severity).append(" (").append(group.size()).append("):\n");
                for (final LintReport.Issue issue : group) {
                    sb.append("  - ");
                    if (issue.getPagePath() != null) {
                        sb.append('[').append(issue.getPagePath()).append("] ");
                    }
                    sb.append(issue.getMessage()).append('\n');
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }
}
