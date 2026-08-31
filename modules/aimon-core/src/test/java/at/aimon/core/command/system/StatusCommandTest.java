package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.status.StatusSection;
import at.aimon.core.status.SystemStatus;
import at.aimon.core.status.SystemStatusProvider;

class StatusCommandTest {

    @Test
    void constructorShouldRejectNullProvider() {
        assertThatThrownBy(() -> new StatusCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveCorrectNameAndDescription() {
        StatusCommand command = new StatusCommand(emptyProvider());

        assertThat(command.getName()).isEqualTo("status");
        assertThat(command.getMetadata().getDescription()).hasValue("Display system status information");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeShouldRejectNullContext() {
        StatusCommand command = new StatusCommand(emptyProvider());

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeShouldRejectNullRequest() {
        StatusCommand command = new StatusCommand(emptyProvider());

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisplayNoStatusWhenEmpty() {
        StatusCommand command = new StatusCommand(emptyProvider());

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("System Status:");
        assertThat(result.getResponse()).contains("No status information available.");
    }

    @Test
    void shouldDisplaySingleSection() {
        SystemStatusProvider provider = () -> SystemStatus.builder()
                .section(StatusSection.builder("Application").entry("Version", "0.0.12").build()).build();

        StatusCommand command = new StatusCommand(provider);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("System Status:");
        assertThat(result.getResponse()).contains("Application:");
        assertThat(result.getResponse()).contains("Version: 0.0.12");
    }

    @Test
    void shouldDisplayMultipleSections() {
        SystemStatusProvider provider = () -> SystemStatus.builder()
                .section(StatusSection.builder("Application").entry("Version", "0.0.12").build())
                .section(StatusSection.builder("Components").entry("Commands", "5").entry("Tools", "8").build())
                .build();

        StatusCommand command = new StatusCommand(provider);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Application:");
        assertThat(result.getResponse()).contains("Version: 0.0.12");
        assertThat(result.getResponse()).contains("Components:");
        assertThat(result.getResponse()).contains("Commands: 5");
        assertThat(result.getResponse()).contains("Tools: 8");
    }

    @Test
    void shouldDisplaySectionWithNoEntries() {
        SystemStatusProvider provider = () -> SystemStatus.builder()
                .section(StatusSection.builder("Empty Section").build()).build();

        StatusCommand command = new StatusCommand(provider);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Empty Section:");
    }

    @Test
    void shouldHandleProviderReturningNull() {
        SystemStatusProvider provider = () -> null;

        StatusCommand command = new StatusCommand(provider);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("System Status:");
        assertThat(result.getResponse()).contains("Failed to retrieve status:");
    }

    @Test
    void shouldHandleProviderThrowingException() {
        SystemStatusProvider provider = () -> {
            throw new RuntimeException("Connection refused");
        };

        StatusCommand command = new StatusCommand(provider);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("System Status:");
        assertThat(result.getResponse()).contains("Failed to retrieve status:");
        assertThat(result.getResponse()).contains("Connection refused");
    }

    private CommandExecutionContext createContext() {
        StatusCommand dummyCommand = new StatusCommand(emptyProvider());
        return CommandExecutionContext.builder().command(dummyCommand)
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry()).build();
    }

    private SystemStatusProvider emptyProvider() {
        return () -> SystemStatus.builder().build();
    }
}
