package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.tools.skill.SkillTool;

/**
 * Regression integration test.
 *
 * <p>
 * Exercises the full chain — {@link ClasspathSkillRepository} → {@link MarkdownSkillParser} →
 * {@link DefaultSkillRegistry} → {@link SkillTool} with {@link DefaultSkillContentRenderer} — against the built-in
 * skill fixtures {@code commit} and {@code summarize}. Confirms that calling these existing skills with and without an
 * {@code args} argument produces correct output and does not regress prior behavior.
 */
@DisplayName("SK-IT-1: Built-in skill activation through SkillTool")
class BuiltinSkillToolIntegrationTest {

    private static final String BUILTIN_SKILLS_PATH = "builtin/skills";

    private static SkillTool builtinSkillTool() {
        final SkillRegistry registry = new DefaultSkillRegistry(new ClasspathSkillRepository(BUILTIN_SKILLS_PATH),
                new MarkdownSkillParser());
        return new SkillTool(registry, new DefaultSkillContentRenderer());
    }

    @Test
    @DisplayName("commit skill — no args returns original instructions without trailer")
    void commit_NoArgs_OriginalInstructionsNoTrailer() {
        final SkillTool tool = builtinSkillTool();

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Skill: commit").contains("Commit Message Guide")
                .contains("feat: New feature").doesNotContain("ARGUMENTS:");
    }

    @Test
    @DisplayName("commit skill — args appended as trailer when body has no placeholder")
    void commit_WithArgs_TrailerAppended() {
        final SkillTool tool = builtinSkillTool();

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit", "args", "scope: agent fix")),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Commit Message Guide").contains("ARGUMENTS: scope: agent fix");
    }

    @Test
    @DisplayName("summarize skill — no args returns original instructions without trailer")
    void summarize_NoArgs_OriginalInstructionsNoTrailer() {
        final SkillTool tool = builtinSkillTool();

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "summarize")), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Skill: summarize").contains("Summarization Guide")
                .contains("Identify the main topic").doesNotContain("ARGUMENTS:");
    }

    @Test
    @DisplayName("summarize skill — args appended as trailer when body has no placeholder")
    void summarize_WithArgs_TrailerAppended() {
        final SkillTool tool = builtinSkillTool();

        final ToolResult result = tool.execute(
                ToolInput.of(Map.of("skill", "summarize", "args", "\"meeting notes\" 5min")), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Summarization Guide").contains("ARGUMENTS: \"meeting notes\" 5min");
    }

    @Test
    @DisplayName("Both fixtures — args=\"\" treated as no args (no trailer)")
    void emptyArgsString_BehavesAsNoArgs() {
        final SkillTool tool = builtinSkillTool();

        final ToolResult commit = tool.execute(ToolInput.of(Map.of("skill", "commit", "args", "")),
                ToolContext.empty());
        final ToolResult summarize = tool.execute(ToolInput.of(Map.of("skill", "summarize", "args", "")),
                ToolContext.empty());

        assertThat(commit.isSuccess()).isTrue();
        assertThat(commit.getContent()).doesNotContain("ARGUMENTS:");
        assertThat(summarize.isSuccess()).isTrue();
        assertThat(summarize.getContent()).doesNotContain("ARGUMENTS:");
    }
}
