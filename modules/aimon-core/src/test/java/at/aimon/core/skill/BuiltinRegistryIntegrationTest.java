package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.subagent.CompositeSubagentRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.parser.MarkdownSubagentParser;
import at.aimon.core.subagent.parser.SubagentContentParser;
import at.aimon.core.subagent.repository.ClasspathSubagentRepository;

/**
 * Integration tests for the built-in registry chain.
 *
 * <p>
 * Verifies the full pipeline: ClasspathRepository → real Parser → DefaultRegistry → CompositeRegistry → parsed domain
 * object.
 *
 * <p>
 * Uses test classpath resources under {@code builtin/agents} and {@code builtin/skills}.
 */
@DisplayName("Built-in Registry Integration Tests")
class BuiltinRegistryIntegrationTest {

    private static final String BUILTIN_AGENTS_PATH = "builtin/agents";
    private static final String BUILTIN_SKILLS_PATH = "builtin/skills";

    @Nested
    @DisplayName("Subagent: ClasspathRepository → DefaultRegistry chain")
    class SubagentChainTests {

        @Test
        @DisplayName("Should load and parse subagent from classpath resource")
        void shouldLoadAndParseSubagentFromClasspath() {
            SubagentRegistry registry = createBuiltinSubagentRegistry();

            Optional<Subagent> result = registry.getSubagent("explore");

            assertThat(result).isPresent();
            Subagent explore = result.get();
            assertThat(explore.getName()).isEqualTo("explore");
            assertThat(explore.getMetadata().getDescription()).contains("exploring codebases");
            assertThat(explore.getContent().getSystemPrompt()).contains("codebase exploration agent");
        }

        @Test
        @DisplayName("Should list all subagents from classpath resources")
        void shouldListAllSubagentsFromClasspath() {
            SubagentRegistry registry = createBuiltinSubagentRegistry();

            List<Subagent> all = registry.getAllSubagents();

            assertThat(all).extracting(Subagent::getName).containsExactlyInAnyOrder("explore", "code-reviewer");
        }

        @Test
        @DisplayName("Should return empty for non-existent subagent")
        void shouldReturnEmptyForNonExistent() {
            SubagentRegistry registry = createBuiltinSubagentRegistry();

            Optional<Subagent> result = registry.getSubagent("non-existent");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Skill: ClasspathRepository → DefaultRegistry chain")
    class SkillChainTests {

        @Test
        @DisplayName("Should load and parse skill from classpath resource")
        void shouldLoadAndParseSkillFromClasspath() {
            SkillRegistry registry = createBuiltinSkillRegistry();

            Optional<Skill> result = registry.getSkill("commit");

            assertThat(result).isPresent();
            Skill commit = result.get();
            assertThat(commit.getName()).isEqualTo("commit");
            assertThat(commit.getMetadata().getDescription()).contains("commit message");
            assertThat(commit.getContent().getInstructions()).contains("Commit Message Guide");
        }

        @Test
        @DisplayName("Should list all skills from classpath resources")
        void shouldListAllSkillsFromClasspath() {
            SkillRegistry registry = createBuiltinSkillRegistry();

            List<Skill> all = registry.getAllSkills();

            assertThat(all).extracting(Skill::getName).containsExactlyInAnyOrder("commit", "summarize");
        }

        @Test
        @DisplayName("Should return empty for non-existent skill")
        void shouldReturnEmptyForNonExistent() {
            SkillRegistry registry = createBuiltinSkillRegistry();

            Optional<Skill> result = registry.getSkill("non-existent");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Composite: builtin + stub user registry")
    class CompositeChainTests {

        @Test
        @DisplayName("Should compose builtin subagent registry with stub user registry")
        void shouldComposeBuiltinSubagentWithUserRegistry() {
            SubagentRegistry builtinRegistry = createBuiltinSubagentRegistry();
            SubagentRegistry compositeRegistry = new CompositeSubagentRegistry(
                    List.of(builtinRegistry, new EmptySubagentRegistry()));

            Optional<Subagent> result = compositeRegistry.getSubagent("explore");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("explore");

            List<Subagent> all = compositeRegistry.getAllSubagents();
            assertThat(all).extracting(Subagent::getName).containsExactlyInAnyOrder("explore", "code-reviewer");
        }

        @Test
        @DisplayName("Should compose builtin skill registry with stub user registry")
        void shouldComposeBuiltinSkillWithUserRegistry() {
            SkillRegistry builtinRegistry = createBuiltinSkillRegistry();
            SkillRegistry compositeRegistry = new CompositeSkillRegistry(
                    List.of(builtinRegistry, new EmptySkillRegistry()));

            Optional<Skill> result = compositeRegistry.getSkill("commit");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("commit");

            List<Skill> all = compositeRegistry.getAllSkills();
            assertThat(all).extracting(Skill::getName).containsExactlyInAnyOrder("commit", "summarize");
        }
    }

    // --- Factory methods ---

    private static SubagentRegistry createBuiltinSubagentRegistry() {
        ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BUILTIN_AGENTS_PATH);
        MarkdownSubagentParser parser = new MarkdownSubagentParser(new SubagentContentParser());
        return new DefaultSubagentRegistry(repository, parser);
    }

    private static SkillRegistry createBuiltinSkillRegistry() {
        ClasspathSkillRepository repository = new ClasspathSkillRepository(BUILTIN_SKILLS_PATH);
        MarkdownSkillParser parser = new MarkdownSkillParser();
        return new DefaultSkillRegistry(repository, parser);
    }

    // --- Empty stub registries for Composite testing ---

    private static class EmptySubagentRegistry implements SubagentRegistry {

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.empty();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return List.of();
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }

    private static class EmptySkillRegistry implements SkillRegistry {

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.empty();
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.of();
        }

        @Override
        public void reloadSkill(String skillName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }
}
