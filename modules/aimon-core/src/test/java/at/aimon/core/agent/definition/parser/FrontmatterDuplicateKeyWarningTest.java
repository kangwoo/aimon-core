package at.aimon.core.agent.definition.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.skill.parser.SkillContentParser;
import at.aimon.core.skill.parser.SkillContentResult;
import at.aimon.core.subagent.parser.SubagentContentParser;
import at.aimon.core.subagent.parser.SubagentContentResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Backlog CE-2: a key written twice in front matter is snakeyaml's last-wins, and the earlier value is gone before
 * anything can report it. The three front-matter parsers keep their {@code LoaderOptions} at the defaults on purpose
 * — refusing a duplicate would stop definitions that load today — so what these tests pin is the other half: the
 * parse result is exactly what it was, and the loss is now said out loud.
 */
@DisplayName("Front matter - a key written twice is reported, and still parses to the last value")
class FrontmatterDuplicateKeyWarningTest {

    private static <T> Captured<T> capture(Class<?> loggerOwner, Supplier<T> parse) {
        final Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final T result = parse.get();
            return new Captured<>(result, appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static AgentDefinition parseAgent(String content) {
        return new MarkdownAgentDefinitionParser()
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("agent: a duplicated nested key warns with its path and the agent's name; the last value is used")
    void agentDefinitionWarns() {
        final Captured<AgentDefinition> captured = capture(MarkdownAgentDefinitionParser.class, () -> parseAgent("""
                ---
                name: coder
                maxIterations: 10
                model:
                  name: test-model
                  temperature: 0.2
                  temperature: 0.9
                maxIterations: 20
                ---
                Prompt.
                """));

        assertThat(captured.result.getModel().getTemperature()).contains(0.9);
        assertThat(captured.result.getMaxIterations()).isEqualTo(20);
        assertThat(captured.warnings).hasSize(1);
        assertThat(captured.warnings.get(0)).contains("'coder'").contains("`maxIterations`")
                .contains("`model.temperature`").contains("earlier value is discarded");
    }

    @Test
    @DisplayName("agent: a definition with no duplicate is silent")
    void agentDefinitionWithoutADuplicateIsSilent() {
        final Captured<AgentDefinition> captured = capture(MarkdownAgentDefinitionParser.class, () -> parseAgent("""
                ---
                name: coder
                model:
                  name: test-model
                  temperature: 0.2
                ---
                Prompt.
                """));

        assertThat(captured.warnings).isEmpty();
    }

    @Test
    @DisplayName("skill: a duplicated key warns with its path and the skill's name; the last value is used")
    void skillWarns() {
        final Captured<SkillContentResult> captured = capture(SkillContentParser.class,
                () -> SkillContentParser.parse("""
                        ---
                        name: deploy
                        description: first
                        allowed-tools: Read
                        description: second
                        ---

                        Body.
                        """));

        assertThat(captured.result.getFrontmatter()).containsEntry("description", "second");
        assertThat(captured.warnings).hasSize(1);
        assertThat(captured.warnings.get(0)).contains("'deploy'").contains("`description`")
                .contains("earlier value is discarded");
    }

    @Test
    @DisplayName("skill: front matter with no duplicate is silent")
    void skillWithoutADuplicateIsSilent() {
        final Captured<SkillContentResult> captured = capture(SkillContentParser.class,
                () -> SkillContentParser.parse("---\nname: deploy\ndescription: only\n---\n\nBody.\n"));

        assertThat(captured.warnings).isEmpty();
    }

    @Test
    @DisplayName("subagent: a duplicated key warns with its path; the last value is used")
    void subagentWarns() {
        final Captured<SubagentContentResult> captured = capture(SubagentContentParser.class,
                () -> new SubagentContentParser().parse("""
                        ---
                        description: Reviews code
                        model: first-model
                        model: second-model
                        ---

                        Prompt.
                        """));

        assertThat(captured.result.getModel()).isEqualTo("second-model");
        assertThat(captured.warnings).hasSize(1);
        assertThat(captured.warnings.get(0)).contains("`model`").contains("Reviews code")
                .contains("earlier value is discarded");
    }

    @Test
    @DisplayName("subagent: front matter with no duplicate is silent")
    void subagentWithoutADuplicateIsSilent() {
        final Captured<SubagentContentResult> captured = capture(SubagentContentParser.class,
                () -> new SubagentContentParser().parse("---\ndescription: Reviews code\n---\n\nPrompt.\n"));

        assertThat(captured.warnings).isEmpty();
    }

    private static final class Captured<T> {
        private final T result;
        private final List<String> warnings;

        Captured(T result, List<String> warnings) {
            this.result = result;
            this.warnings = warnings;
        }
    }
}
