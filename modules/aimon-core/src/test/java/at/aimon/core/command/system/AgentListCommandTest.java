package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;

class AgentListCommandTest {

    @Test
    void constructorShouldRejectNullRegistry() {
        assertThatThrownBy(() -> new AgentListCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveCorrectNameAndDescription() {
        AgentListCommand command = new AgentListCommand(emptyRegistry());

        assertThat(command.getName()).isEqualTo("agents");
        assertThat(command.getMetadata().getDescription()).hasValue("Display all registered subagents");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeShouldRejectNullContext() {
        AgentListCommand command = new AgentListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeShouldRejectNullRequest() {
        AgentListCommand command = new AgentListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisplayNoSubagentsWhenRegistryIsEmpty() {
        AgentListCommand command = new AgentListCommand(emptyRegistry());

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No subagents registered.");
    }

    @Test
    void shouldDisplaySubagentWithDescription() {
        StubSubagentRegistry registry = new StubSubagentRegistry();
        registry.addSubagent(stubSubagent("code-reviewer", "Reviews code changes", null));

        AgentListCommand command = new AgentListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("code-reviewer - Reviews code changes");
        assertThat(result.getResponse()).contains("Total: 1 subagent(s)");
    }

    @Test
    void shouldDisplaySubagentWithModel() {
        StubSubagentRegistry registry = new StubSubagentRegistry();
        registry.addSubagent(stubSubagent("code-reviewer", "Reviews code changes", "sonnet"));

        AgentListCommand command = new AgentListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("code-reviewer - Reviews code changes");
        assertThat(result.getResponse()).contains("model: sonnet");
    }

    @Test
    void shouldDisplayMultipleSubagents() {
        StubSubagentRegistry registry = new StubSubagentRegistry();
        registry.addSubagent(stubSubagent("code-reviewer", "Reviews code", "sonnet"));
        registry.addSubagent(stubSubagent("test-generator", "Generates tests", "haiku"));

        AgentListCommand command = new AgentListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("code-reviewer");
        assertThat(result.getResponse()).contains("test-generator");
        assertThat(result.getResponse()).contains("Total: 2 subagent(s)");
    }

    @Test
    void shouldHandleSubagentWithoutDescription() {
        StubSubagentRegistry registry = new StubSubagentRegistry();
        registry.addSubagent(stubSubagent("simple-agent", null, null));

        AgentListCommand command = new AgentListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("simple-agent");
        assertThat(result.getResponse()).doesNotContain(" - null");
    }

    private CommandExecutionContext createContext() {
        AgentListCommand dummyCommand = new AgentListCommand(emptyRegistry());
        return CommandExecutionContext.builder().command(dummyCommand)
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry()).build();
    }

    private StubSubagentRegistry emptyRegistry() {
        return new StubSubagentRegistry();
    }

    private Subagent stubSubagent(String name, String description, String model) {
        SubagentMetadata metadata = SubagentMetadata.builder().description(description).model(model).build();
        SubagentContent content = SubagentContent.of("test content");
        return Subagent.of(name, metadata, content);
    }

    private static class StubSubagentRegistry implements SubagentRegistry {
        private final List<Subagent> subagents = new ArrayList<>();

        void addSubagent(Subagent subagent) {
            subagents.add(subagent);
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return subagents.stream().filter(s -> s.getName().equals(subagentName)).findFirst();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return List.copyOf(subagents);
        }

        @Override
        public void reloadSubagent(String subagentName) {
        }

        @Override
        public void reloadAll() {
        }
    }
}
