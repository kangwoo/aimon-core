package at.aimon.core.agent.definition.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.agent.definition.exception.AgentDefinitionParseException;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;

@DisplayName("MarkdownAgentDefinitionParser — programmatic-tools / programmatic-only / programmatic-engine")
class MarkdownAgentDefinitionParserProgrammaticTest {

    private final MarkdownAgentDefinitionParser parser = new MarkdownAgentDefinitionParser();

    private AgentDefinition parse(String frontmatter) {
        final InputStream in = new ByteArrayInputStream(
                ("---\nname: test\n" + frontmatter + "---\nbody").getBytes(StandardCharsets.UTF_8));
        return parser.parse(in);
    }

    @Test
    @DisplayName("absent keys mean no programmatic tool calling")
    void absent() {
        assertThat(parse("").getProgrammatic()).isEqualTo(ProgrammaticToolSettings.none());
    }

    @Test
    @DisplayName("parses the three keys, lists or comma strings")
    void parsesKeys() {
        final ProgrammaticToolSettings settings = parse("""
                programmatic-tools:
                  - Read
                  - Grep
                  - mcp__prometheus__query
                programmatic-only: mcp__prometheus__query
                programmatic-engine: JavaScript
                """).getProgrammatic();
        assertThat(settings.getTools()).containsExactly("Read", "Grep", "mcp__prometheus__query");
        assertThat(settings.getCodeOnly()).containsExactly("mcp__prometheus__query");
        assertThat(settings.getEngine()).isEqualTo(ProgrammaticEngineChoice.JAVASCRIPT);
        assertThat(settings.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("refuses at load time what can be checked without the tools")
    void refusals() {
        assertThatThrownBy(() -> parse("programmatic-tools: Read\nprogrammatic-only: Grep\n"))
                .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("subset");
        assertThatThrownBy(() -> parse("programmatic-tools: mcp__x__*\n"))
                .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("exact tool name");
        assertThatThrownBy(() -> parse("programmatic-tools: Bash(git:*)\n"))
                .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("exact tool name");
        assertThatThrownBy(() -> parse("programmatic-tools: Read\nprogrammatic-engine: ruby\n"))
                .isInstanceOf(AgentDefinitionParseException.class).hasMessageContaining("unknown programmatic engine");
        assertThatThrownBy(() -> parse("programmatic-only: Read\n")).isInstanceOf(AgentDefinitionParseException.class)
                .hasMessageContaining("need 'programmatic-tools'");
        assertThatThrownBy(() -> parse("programmaticTools: Read\n")).isInstanceOf(AgentDefinitionParseException.class)
                .hasMessageContaining("kebab-case");
    }
}
