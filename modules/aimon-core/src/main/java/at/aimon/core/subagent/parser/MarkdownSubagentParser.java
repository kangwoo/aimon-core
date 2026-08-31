package at.aimon.core.subagent.parser;

import java.util.Objects;

import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

/**
 * Parses subagent files from agents/*.md directory.
 *
 * <p>
 * Expected format:
 *
 * <pre>
 * {@code
 * ---
 * name: code-reviewer
 * description: Expert code reviewer. Use when reviewing code for quality, security, and best practices.
 * when-to-use: When you need code review or quality analysis
 * allowed-tools: Read, Grep, Glob, Bash
 * model: sonnet
 * max-iterations: 50
 * ---
 * You are an expert code reviewer...
 * }
 * </pre>
 *
 * <p>
 * The description field should include when and how to use this subagent.
 *
 * <p>
 * Reuses Command system's AllowedTool.parse() for consistency.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentParser parser = new MarkdownSubagentParser(new SubagentContentParser());
 *     Subagent subagent = parser.parse("code-reviewer", fileContent);
 * }
 * </pre>
 */
public class MarkdownSubagentParser implements SubagentParser {
    private final SubagentContentParser subagentContentParser;

    /**
     * Creates a new MarkdownSubagentParser.
     *
     * @param subagentContentParser
     *            The content parser (must not be null)
     * @throws NullPointerException
     *             if subagentContentParser is null
     */
    public MarkdownSubagentParser(SubagentContentParser subagentContentParser) {
        this.subagentContentParser = Objects.requireNonNull(subagentContentParser,
                "Subagent content parser cannot be null");
    }

    @Override
    public Subagent parse(String subagentName, String content) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");

        // Parse YAML frontmatter + markdown body
        final SubagentContentResult result = subagentContentParser.parse(content);
        final String systemPrompt = result.getSystemPrompt();

        // Build metadata. max-iterations is only forwarded when present so the metadata default (1000) stays intact.
        final SubagentMetadata.Builder metadataBuilder = SubagentMetadata.builder().description(result.getDescription())
                .whenToUse(result.getWhenToUse()).tools(result.getTools()).model(result.getModel());
        if (result.getMaxIterations() != null) {
            metadataBuilder.maxIterations(result.getMaxIterations());
        }
        final SubagentMetadata metadata = metadataBuilder.build();

        // Create Subagent
        final SubagentContent subagentContent = SubagentContent.of(systemPrompt);
        return Subagent.of(subagentName, metadata, subagentContent);
    }
}
