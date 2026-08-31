package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;

/**
 * CTX-04 regression + structural tests for {@link OrcaAgentExecutor}'s system-prompt assembly.
 *
 * <p>
 * The bit-equality golden values were captured from the pre-refactor {@code StringBuilder}-based implementation.
 * They are reproduced here as inline constants so the concatenated output of the parts-based builder can be verified
 * against the exact text the LLM used to receive.
 */
@DisplayName("OrcaAgentExecutor system prompt — parts assembly + concatenation invariant")
class OrcaAgentExecutorSystemPromptTest {

    private OrcaAgentExecutor executor;

    @BeforeEach
    void setUp() {
        final LlmClient client = Mockito.mock(LlmClient.class);
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        executor = new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    @Test
    @DisplayName("concatenated() matches the pre-refactor golden String (content + environment)")
    void concatenated_matchesGolden_withEnvironment() {
        final AgentContent content = AgentContent.builder().systemPrompt("You are {{name}}, a helpful assistant.")
                .variables(Map.of("name", "Aimon")).build();
        final Environment environment = Environment.builder().workingDirectory("/tmp/wd").platform("linux-test")
                .osVersion("1.0-test").timeZone(ZoneId.systemDefault()).build();

        // Golden value captured from the pre-CTX-04 StringBuilder implementation.
        final String expected = "You are Aimon, a helpful assistant." + "\n\n"
                + "Here is useful information about the environment you are running in:\n\n" + "**Environment:**\n"
                + "```\n" + "Working directory: /tmp/wd\n" + "Platform: linux-test\n" + "OS Version: 1.0-test\n"
                + "```";

        final SystemPromptParts parts = executor.buildSystemPromptParts(content, Map.of(), environment);

        assertThat(parts.concatenated()).isEqualTo(expected);
    }

    @Test
    @DisplayName("concatenated() matches the pre-refactor golden String with override variables")
    void concatenated_matchesGolden_withOverrideVariables() {
        final AgentContent content = AgentContent.builder().systemPrompt("You are {{name}}, version {{v}}")
                .variables(Map.of("name", "base", "v", "1")).build();
        final Environment environment = Environment.builder().workingDirectory("/work").platform("mac").osVersion("42")
                .timeZone(ZoneId.systemDefault()).build();

        // Override takes precedence over base variables (same rule as AgentContentRenderer).
        final Map<String, Object> overrides = Map.of("name", "override");
        final String expected = "You are override, version 1" + "\n\n"
                + "Here is useful information about the environment you are running in:\n\n" + "**Environment:**\n"
                + "```\n" + "Working directory: /work\n" + "Platform: mac\n" + "OS Version: 42\n" + "```";

        final SystemPromptParts parts = executor.buildSystemPromptParts(content, overrides, environment);

        assertThat(parts.concatenated()).isEqualTo(expected);
    }

    @Test
    @DisplayName("concatenated() matches pre-refactor output when environment is null (content only)")
    void concatenated_matchesGolden_withoutEnvironment() {
        final AgentContent content = AgentContent.builder().systemPrompt("Just agent content.").build();

        final String expected = "Just agent content.";

        final SystemPromptParts parts = executor.buildSystemPromptParts(content, Map.of(), null);

        assertThat(parts.concatenated()).isEqualTo(expected);
    }

    @Test
    @DisplayName("parts structure includes agent-content and environment parts with appropriate kinds")
    void parts_structure_exposesNonStaticKindLabels() {
        final AgentContent content = AgentContent.builder().systemPrompt("Hello").build();
        final Environment environment = Environment.builder().workingDirectory("/x").platform("p").osVersion("o")
                .timeZone(ZoneId.systemDefault()).build();

        final SystemPromptParts parts = executor.buildSystemPromptParts(content, Map.of(), environment);

        final List<SystemPromptPart> list = parts.parts();
        assertThat(list).hasSize(2);

        // Segment 1: agent-content (static in the default fallback path).
        assertThat(list.get(0).getKind()).isEqualTo("agent-content");
        assertThat(list.get(0).getStaticness()).isEqualTo(Staticness.STATIC);
        assertThat(list.get(0).getContent()).isEqualTo("Hello");

        // Segment 2: environment — DYNAMIC, kind = "environment".
        assertThat(list.get(1).getKind()).isEqualTo("environment");
        assertThat(list.get(1).getStaticness()).isEqualTo(Staticness.DYNAMIC);
        assertThat(list.get(1).getContent()).startsWith("Here is useful information about the environment");
        assertThat(list.get(1).getContent()).contains("Working directory: /x");
    }

    @Test
    @DisplayName("at least one part uses a non-STATIC kind label when environment is present")
    void parts_nonStaticKindPresent() {
        final AgentContent content = AgentContent.builder().systemPrompt("Hello").build();
        final Environment environment = Environment.builder().workingDirectory("/x").platform("p").osVersion("o")
                .timeZone(ZoneId.systemDefault()).build();

        final SystemPromptParts parts = executor.buildSystemPromptParts(content, Map.of(), environment);

        assertThat(parts.parts()).anyMatch(p -> p.getStaticness() != Staticness.STATIC);
    }
}
