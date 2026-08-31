package at.aimon.core.command.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.command.Command;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;

class SkillBackedCommandRegistryTest {

    @Test
    void shouldRejectNullSkillRegistry() {
        assertThatThrownBy(() -> new SkillBackedCommandRegistry(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill registry");
    }

    @Test
    void shouldExposeOnlyUserInvocableSkills() {
        StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(skill("commit", InvokePolicy.of(true, true)));
        skills.put(skill("model-only", InvokePolicy.of(false, true)));

        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(skills);

        assertThat(registry.getAllCommands()).extracting(Command::getName).containsExactly("commit");
        assertThat(registry.hasCommand("commit")).isTrue();
        assertThat(registry.hasCommand("model-only")).isFalse();
        assertThat(registry.getCommand("model-only")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownCommand() {
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(new StubSkillRegistry());

        assertThat(registry.getCommand("missing")).isEmpty();
        assertThat(registry.hasCommand("missing")).isFalse();
    }

    @Test
    void shouldWrapResolvedSkillAsSkillBackedCommand() {
        StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(skill("commit", InvokePolicy.of(true, true)));
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(skills);

        Optional<Command> command = registry.getCommand("commit");

        assertThat(command).isPresent().get().isInstanceOf(SkillBackedCommand.class);
        assertThat(command.get().getName()).isEqualTo("commit");
    }

    @Test
    void shouldTreatAllAdaptedCommandsAsSkillBacked() {
        StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(skill("commit", InvokePolicy.of(true, true)));
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(skills);

        assertThat(registry.getSystemCommands()).isEmpty();
        assertThat(registry.isSystemCommand("commit")).isFalse();
        assertThat(registry.getSkillBackedCommands()).extracting(Command::getName).containsExactly("commit");
    }

    @Test
    void shouldNotCacheAdapters() {
        // Reflecting reload semantics: re-asking should re-wrap, not return a stale instance.
        StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(skill("commit", InvokePolicy.of(true, true)));
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(skills);

        Command first = registry.getCommand("commit").orElseThrow();

        // Mutate the underlying skill (simulating a reload that produced a new Skill instance).
        skills.put(skillWithDescription("commit", "updated"));
        Command second = registry.getCommand("commit").orElseThrow();

        assertThat(first).isNotSameAs(second);
        assertThat(second.getMetadata().getDescription()).contains("updated");
    }

    @Test
    void shouldDelegateReloadToSkillRegistry() {
        StubSkillRegistry skills = new StubSkillRegistry();
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(skills);

        registry.reloadCommand("commit");
        registry.reloadAll();

        assertThat(skills.reloadedSkills).containsExactly("commit");
        assertThat(skills.reloadAllCount).isEqualTo(1);
    }

    @Test
    void shouldRejectNullCommandName() {
        SkillBackedCommandRegistry registry = new SkillBackedCommandRegistry(new StubSkillRegistry());

        assertThatThrownBy(() -> registry.getCommand(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.hasCommand(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.isSystemCommand(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.reloadCommand(null)).isInstanceOf(NullPointerException.class);
    }

    private static Skill skill(String name, InvokePolicy policy) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc-" + name).invokePolicy(policy).build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static Skill skillWithDescription(String name, String description) {
        SkillMetadata m = SkillMetadata.builder().name(name).description(description)
                .invokePolicy(InvokePolicy.of(true, true)).build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static final class StubSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new LinkedHashMap<>();
        private final List<String> reloadedSkills = new ArrayList<>();
        private int reloadAllCount;

        void put(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            reloadedSkills.add(skillName);
        }

        @Override
        public void reloadAll() {
            reloadAllCount++;
        }
    }
}
