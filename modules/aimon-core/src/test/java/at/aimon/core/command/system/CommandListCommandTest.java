package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandMetadata;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;

class CommandListCommandTest {

    @Test
    void constructorShouldRejectNullRegistry() {
        assertThatThrownBy(() -> new CommandListCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveCorrectNameAndDescription() {
        CommandListCommand command = new CommandListCommand(emptyRegistry());

        assertThat(command.getName()).isEqualTo("commands");
        assertThat(command.getMetadata().getDescription()).hasValue("Display all registered commands");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeShouldRejectNullContext() {
        CommandListCommand command = new CommandListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeShouldRejectNullRequest() {
        CommandListCommand command = new CommandListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisplayNoCommandsWhenRegistryIsEmpty() {
        CommandListCommand command = new CommandListCommand(emptyRegistry());

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No commands registered.");
    }

    @Test
    void shouldDisplaySystemCommands() {
        StubCommandRegistry registry = new StubCommandRegistry();
        registry.addSystemCommand(stubCommand("help", "Display help information"));
        registry.addSystemCommand(stubCommand("version", "Display version"));

        CommandListCommand command = new CommandListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("System commands:");
        assertThat(result.getResponse()).contains("/help - Display help information");
        assertThat(result.getResponse()).contains("/version - Display version");
        assertThat(result.getResponse()).doesNotContain("Optional");
        assertThat(result.getResponse()).contains("Total: 2 command(s)");
    }

    @Test
    void shouldDisplaySkillBackedCommands() {
        StubCommandRegistry registry = new StubCommandRegistry();
        registry.addSkillBackedCommand(stubCommand("commit", "Skill-backed commit"));

        CommandListCommand command = new CommandListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Skill commands:");
        assertThat(result.getResponse()).contains("/commit - Skill-backed commit");
        assertThat(result.getResponse()).doesNotContain("[deprecated]");
        assertThat(result.getResponse()).contains("Total: 1 command(s)");
    }

    @Test
    void shouldDisplayBothSystemAndSkillSections() {
        StubCommandRegistry registry = new StubCommandRegistry();
        registry.addSystemCommand(stubCommand("help", "Display help information"));
        registry.addSkillBackedCommand(stubCommand("commit", "Create a git commit"));

        CommandListCommand command = new CommandListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        String body = result.getResponse();
        assertThat(body).contains("System commands:");
        assertThat(body).contains("Skill commands:");
        assertThat(body).contains("Total: 2 command(s)");
        // Section ordering: System → Skill
        int sysIdx = body.indexOf("System commands:");
        int skillIdx = body.indexOf("Skill commands:");
        assertThat(sysIdx).isLessThan(skillIdx);
    }

    @Test
    void shouldNotRenderAnyDeprecatedMarker() {
        StubCommandRegistry registry = new StubCommandRegistry();
        registry.addSystemCommand(stubCommand("help", "Display help information"));
        registry.addSkillBackedCommand(stubCommand("commit", "Skill-backed commit"));

        CommandListCommand command = new CommandListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).doesNotContain("[deprecated]");
    }

    private CommandExecutionContext createContext() {
        CommandListCommand dummyCommand = new CommandListCommand(emptyRegistry());
        return CommandExecutionContext.builder().command(dummyCommand)
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry()).build();
    }

    private StubCommandRegistry emptyRegistry() {
        return new StubCommandRegistry();
    }

    private Command stubCommand(String name, String description) {
        return new Command() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public CommandMetadata getMetadata() {
                return CommandMetadata.builder().description(description).build();
            }

            @Override
            public CommandType getType() {
                return CommandType.CUSTOM;
            }
        };
    }

    private static class StubCommandRegistry implements CommandRegistry {
        private final List<Command> systemCommands = new ArrayList<>();
        private final List<Command> skillBackedCommands = new ArrayList<>();

        void addSystemCommand(Command command) {
            systemCommands.add(command);
        }

        void addSkillBackedCommand(Command command) {
            skillBackedCommands.add(command);
        }

        @Override
        public Optional<Command> getCommand(String commandName) {
            return getAllCommands().stream().filter(c -> c.getName().equals(commandName)).findFirst();
        }

        @Override
        public List<Command> getAllCommands() {
            List<Command> all = new ArrayList<>(systemCommands);
            all.addAll(skillBackedCommands);
            return List.copyOf(all);
        }

        @Override
        public List<Command> getSystemCommands() {
            return List.copyOf(systemCommands);
        }

        @Override
        public List<Command> getSkillBackedCommands() {
            return List.copyOf(skillBackedCommands);
        }

        @Override
        public boolean hasCommand(String commandName) {
            return getCommand(commandName).isPresent();
        }

        @Override
        public boolean isSystemCommand(String commandName) {
            return systemCommands.stream().anyMatch(c -> c.getName().equals(commandName));
        }

        @Override
        public void reloadCommand(String commandName) {
        }

        @Override
        public void reloadAll() {
        }
    }
}
