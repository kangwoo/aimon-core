package at.aimon.core.subagent.parser;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.Subagent;

/**
 * Verifies that frontmatter fields flow all the way through to {@link at.aimon.core.subagent.SubagentMetadata}.
 *
 * <p>
 * Regression guard: {@code max-iterations} used to be parsed but never wired into the metadata, so the ReAct
 * cap
 * silently stayed at the default. {@code when-to-use} is likewise surfaced on the metadata.
 */
class MarkdownSubagentParserTest {

    private final MarkdownSubagentParser parser = new MarkdownSubagentParser(new SubagentContentParser());

    @Test
    void parse_MaxIterationsFrontmatter_FlowsIntoMetadata() {
        String md = """
                ---
                description: Bounded reviewer
                max-iterations: 25
                ---

                You are a reviewer.
                """;

        Subagent subagent = parser.parse("bounded", md);

        assertThat(subagent.getMetadata().getMaxIterations()).isEqualTo(25);
    }

    @Test
    void parse_NoMaxIterations_UsesMetadataDefault() {
        String md = """
                ---
                description: Default reviewer
                ---

                You are a reviewer.
                """;

        Subagent subagent = parser.parse("defaulted", md);

        assertThat(subagent.getMetadata().getMaxIterations()).isEqualTo(1000);
    }

    @Test
    void parse_WhenToUseFrontmatter_FlowsIntoMetadata() {
        String md = """
                ---
                description: Code reviewer
                when-to-use: When you need code review or quality analysis
                ---

                You are a reviewer.
                """;

        Subagent subagent = parser.parse("reviewer", md);

        assertThat(subagent.getMetadata().getWhenToUse()).isEqualTo("When you need code review or quality analysis");
    }

    @Test
    void parse_NoWhenToUse_MetadataWhenToUseIsNull() {
        Subagent subagent = parser.parse("plain", "Just a body, no frontmatter.");

        assertThat(subagent.getMetadata().getWhenToUse()).isNull();
    }
}
