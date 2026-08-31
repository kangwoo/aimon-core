package at.aimon.core.command.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.command.CommandMetadata;
import at.aimon.core.command.CommandType;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

class SkillBackedCommandTest {

    @Test
    void shouldExposeSkillNameAsCommandName() {
        Skill skill = simpleSkill("commit", "Create a git commit");
        SkillBackedCommand command = new SkillBackedCommand(skill);

        assertThat(command.getName()).isEqualTo("commit");
    }

    @Test
    void shouldClassifyAsCustomCommandType() {
        SkillBackedCommand command = new SkillBackedCommand(simpleSkill("commit", "desc"));

        assertThat(command.getType()).isEqualTo(CommandType.CUSTOM);
    }

    @Test
    void shouldMapDescriptionAndMaxIterationsIntoCommandMetadata() {
        SkillMetadata m = SkillMetadata.builder().name("deploy").description("Deploy current branch").maxIterations(42)
                .build();
        Skill skill = Skill.builder().name("deploy").metadata(m).content(SkillContent.of("body")).build();

        CommandMetadata metadata = new SkillBackedCommand(skill).getMetadata();

        assertThat(metadata.getDescription()).contains("Deploy current branch");
        assertThat(metadata.getMaxIterations()).isEqualTo(42);
        assertThat(metadata.hasPermissionRestrictions()).isFalse();
    }

    @Test
    void shouldRoundTripAllowedToolsThroughCommandMetadata() {
        SkillMetadata m = SkillMetadata.builder().name("commit").description("desc")
                .allowedToolsList(List.of("Read", "Bash(git add:*)")).build();
        Skill skill = Skill.builder().name("commit").metadata(m).content(SkillContent.of("body")).build();

        SkillBackedCommand command = new SkillBackedCommand(skill);

        assertThat(command.hasPermissionRestrictions()).isTrue();
        assertThat(command.getAllowedTools()).hasSize(2);
        assertThat(command.getAllowedTools().get(0).getToolName()).isEqualTo("Read");
        assertThat(command.getAllowedTools().get(1).getToolName()).isEqualTo("Bash");
        // CommandMetadata round-trip should preserve the parsed AllowedTool list (specs survive through toString()).
        assertThat(command.getMetadata().getAllowedTools()).isEqualTo(command.getAllowedTools());
    }

    @Test
    void shouldExposeSkillBodyAsCommandContentRaw() {
        Skill skill = simpleSkill("commit", "desc", "## Task\nRun !`git status` for $ARGUMENTS");
        SkillBackedCommand command = new SkillBackedCommand(skill);

        // Tokens were removed from CommandContent in SK-08-F: SkillContentRenderer scans the body lazily.
        assertThat(command.getContent().getRawContent()).isEqualTo("## Task\nRun !`git status` for $ARGUMENTS");
    }

    @Test
    void shouldRejectNullSkill() {
        assertThatThrownBy(() -> new SkillBackedCommand(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill");
    }

    @Test
    void shouldExposeUnderlyingSkillForExecution() {
        Skill skill = simpleSkill("commit", "desc");
        SkillBackedCommand command = new SkillBackedCommand(skill);

        assertThat(command.getSkill()).isSameAs(skill);
    }

    @Test
    void shouldRespectInvokePolicyAgnosticOfWrapper() {
        // Adapter does not enforce policy itself; the registry filters. We document the no-op behavior.
        SkillMetadata m = SkillMetadata.builder().name("model-only").description("model-only")
                .invokePolicy(InvokePolicy.of(false, true)).build();
        Skill skill = Skill.builder().name("model-only").metadata(m).content(SkillContent.of("body")).build();

        SkillBackedCommand command = new SkillBackedCommand(skill);

        assertThat(command.getName()).isEqualTo("model-only");
    }

    @Test
    void shouldImplementEqualsAndHashCodeBasedOnSkill() {
        Skill skill = simpleSkill("commit", "desc");
        Skill other = simpleSkill("commit", "desc");

        assertThat(new SkillBackedCommand(skill)).isEqualTo(new SkillBackedCommand(other));
        assertThat(new SkillBackedCommand(skill).hashCode()).isEqualTo(new SkillBackedCommand(other).hashCode());
    }

    private static Skill simpleSkill(String name, String description) {
        return simpleSkill(name, description, "body");
    }

    private static Skill simpleSkill(String name, String description, String body) {
        SkillMetadata m = SkillMetadata.builder().name(name).description(description).build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of(body)).build();
    }
}
