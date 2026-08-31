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
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;

class SkillListCommandTest {

    @Test
    void constructorShouldRejectNullRegistry() {
        assertThatThrownBy(() -> new SkillListCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveCorrectNameAndDescription() {
        SkillListCommand command = new SkillListCommand(emptyRegistry());

        assertThat(command.getName()).isEqualTo("skills");
        assertThat(command.getMetadata().getDescription()).hasValue("Display all registered skills");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeShouldRejectNullContext() {
        SkillListCommand command = new SkillListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeShouldRejectNullRequest() {
        SkillListCommand command = new SkillListCommand(emptyRegistry());

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisplayNoSkillsWhenRegistryIsEmpty() {
        SkillListCommand command = new SkillListCommand(emptyRegistry());

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No skills registered.");
    }

    @Test
    void shouldDisplaySkillWithDescription() {
        StubSkillRegistry registry = new StubSkillRegistry();
        registry.addSkill(stubSkill("alert-analysis", "Analyzes alerts from monitoring systems"));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse())
                .contains("alert-analysis [model-only] - Analyzes alerts from monitoring systems");
        assertThat(result.getResponse()).contains("Total: 1 skill(s)");
    }

    @Test
    void shouldMarkUserAndModelSkill() {
        StubSkillRegistry registry = new StubSkillRegistry();
        registry.addSkill(stubSkill("commit", "Commit message guide", InvokePolicy.of(true, true)));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("commit [user, model] - Commit message guide");
    }

    @Test
    void shouldMarkUserOnlySkill() {
        StubSkillRegistry registry = new StubSkillRegistry();
        registry.addSkill(stubSkill("rollback", "Rolls back the last deploy", InvokePolicy.of(true, false)));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("rollback [user-only] - Rolls back the last deploy");
    }

    @Test
    void shouldMarkModelOnlySkillByDefault() {
        StubSkillRegistry registry = new StubSkillRegistry();
        // Default policy is {user:false, model:true} → [model-only]
        registry.addSkill(stubSkill("summarize", "Summarizes content"));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("summarize [model-only] - Summarizes content");
    }

    @Test
    void shouldMarkDisabledSkill() {
        StubSkillRegistry registry = new StubSkillRegistry();
        registry.addSkill(stubSkill("legacy", "Old skill kept for archives", InvokePolicy.of(false, false)));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("legacy [disabled] - Old skill kept for archives");
    }

    @Test
    void shouldDisplayMultipleSkills() {
        StubSkillRegistry registry = new StubSkillRegistry();
        registry.addSkill(stubSkill("alert-analysis", "Analyzes alerts"));
        registry.addSkill(stubSkill("log-search", "Searches log files"));
        registry.addSkill(stubSkill("deploy-checker", "Checks deployment status"));

        SkillListCommand command = new SkillListCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("alert-analysis");
        assertThat(result.getResponse()).contains("log-search");
        assertThat(result.getResponse()).contains("deploy-checker");
        assertThat(result.getResponse()).contains("Total: 3 skill(s)");
    }

    private CommandExecutionContext createContext() {
        SkillListCommand dummyCommand = new SkillListCommand(emptyRegistry());
        return CommandExecutionContext.builder().command(dummyCommand)
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry()).build();
    }

    private StubSkillRegistry emptyRegistry() {
        return new StubSkillRegistry();
    }

    private Skill stubSkill(String name, String description) {
        SkillMetadata metadata = SkillMetadata.builder().name(name).description(description).build();
        SkillContent content = SkillContent.of("test content");
        return Skill.builder().name(name).metadata(metadata).content(content).build();
    }

    private Skill stubSkill(String name, String description, InvokePolicy policy) {
        SkillMetadata metadata = SkillMetadata.builder().name(name).description(description).invokePolicy(policy)
                .build();
        SkillContent content = SkillContent.of("test content");
        return Skill.builder().name(name).metadata(metadata).content(content).build();
    }

    private static class StubSkillRegistry implements SkillRegistry {
        private final List<Skill> skills = new ArrayList<>();

        void addSkill(Skill skill) {
            skills.add(skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return skills.stream().filter(s -> s.getName().equals(skillName)).findFirst();
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.copyOf(skills);
        }

        @Override
        public void reloadSkill(String skillName) {
        }

        @Override
        public void reloadAll() {
        }
    }
}
