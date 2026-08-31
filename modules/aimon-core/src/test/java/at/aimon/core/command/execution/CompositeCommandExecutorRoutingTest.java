package at.aimon.core.command.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandContent;
import at.aimon.core.command.CommandMetadata;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectCommandExecutor;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.execution.llm.LlmExecutable;
import at.aimon.core.command.execution.skill.SkillBackedCommandExecutor;
import at.aimon.core.command.skill.SkillBackedCommand;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionResult;

@DisplayName("CompositeCommandExecutor routing (SK-08-F)")
class CompositeCommandExecutorRoutingTest {

    private DirectCommandExecutor directExecutor;
    private AtomicBoolean skillCalled;
    private SkillBackedCommandExecutor skillExecutor;

    @BeforeEach
    void setUp() {
        directExecutor = new DirectCommandExecutor();
        skillCalled = new AtomicBoolean(false);
        skillExecutor = new SkillBackedCommandExecutor((c, r) -> {
            skillCalled.set(true);
            return SkillExecutionResult.success("skill-result");
        });
    }

    @Test
    @DisplayName("SkillBackedCommand routes to SkillBackedCommandExecutor")
    void shouldRouteSkillBackedToSkillExecutor() {
        CompositeCommandExecutor composite = new CompositeCommandExecutor(directExecutor, skillExecutor);

        CommandExecutionResult result = composite.execute(buildContext(new SkillBackedCommand(simpleSkill("commit"))),
                buildRequest());

        assertThat(skillCalled).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("skill-result");
    }

    @Test
    @DisplayName("DirectExecutable routes to DirectCommandExecutor")
    void shouldRouteDirectExecutable() {
        CompositeCommandExecutor composite = new CompositeCommandExecutor(directExecutor, skillExecutor);

        CommandExecutionResult result = composite.execute(buildContext(new DirectStubCommand()), buildRequest());

        assertThat(skillCalled).isFalse();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("direct-result");
    }

    @Test
    @DisplayName("Pure LlmExecutable (non-skill, non-direct) is rejected — legacy LLM custom commands removed in SK-08-F")
    void shouldRejectNonSkillNonDirectCommand() {
        CompositeCommandExecutor composite = new CompositeCommandExecutor(directExecutor, skillExecutor);

        assertThatThrownBy(() -> composite.execute(buildContext(new LlmStubCommand()), buildRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No executor available");
    }

    @Test
    @DisplayName("Constructor rejects null direct or skill executor")
    void shouldRejectNullExecutors() {
        assertThatThrownBy(() -> new CompositeCommandExecutor(null, skillExecutor))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompositeCommandExecutor(directExecutor, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Getters expose injected executors")
    void shouldExposeInjectedExecutorsViaGetters() {
        CompositeCommandExecutor composite = new CompositeCommandExecutor(directExecutor, skillExecutor);

        assertThat(composite.getDirectCommandExecutor()).isSameAs(directExecutor);
        assertThat(composite.getSkillBackedCommandExecutor()).isSameAs(skillExecutor);
    }

    private static CommandExecutionContext buildContext(Command command) {
        ToolRegistry toolRegistry = new DefaultToolRegistry();
        return CommandExecutionContext.builder().command(command).defaultModel(LlmModel.builder().build())
                .toolRegistry(toolRegistry).build();
    }

    private static CommandExecutionRequest buildRequest() {
        return CommandExecutionRequest.builder().rawArguments("").arguments(List.of()).build();
    }

    private static Skill simpleSkill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static final class DirectStubCommand extends SystemCommand implements DirectExecutable {
        DirectStubCommand() {
            super("direct-stub", "stub");
        }

        @Override
        public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
            return CommandExecutionResult.success("direct-result");
        }
    }

    private static final class LlmStubCommand implements Command, LlmExecutable {
        @Override
        public String getName() {
            return "llm-stub";
        }

        @Override
        public CommandMetadata getMetadata() {
            return CommandMetadata.builder().description("desc").build();
        }

        @Override
        public CommandType getType() {
            return CommandType.CUSTOM;
        }

        @Override
        public CommandContent getContent() {
            return CommandContent.of("body");
        }

        @Override
        public List<AllowedTool> getAllowedTools() {
            return List.of();
        }

        @Override
        public boolean hasPermissionRestrictions() {
            return false;
        }
    }
}
