package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.exception.SkillNotFoundException;

@DisplayName("CompositeSkillRegistry Tests")
class CompositeSkillRegistryTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should throw exception when registries is null")
        void shouldThrowWhenRegistriesIsNull() {
            assertThatThrownBy(() -> new CompositeSkillRegistry(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Registries cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when registries is empty")
        void shouldThrowWhenRegistriesIsEmpty() {
            assertThatThrownBy(() -> new CompositeSkillRegistry(List.of())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Registries cannot be empty");
        }

        @Test
        @DisplayName("Should create with single registry")
        void shouldCreateWithSingleRegistry() {
            StubSkillRegistry stub = new StubSkillRegistry();
            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));
            assertThat(composite).isNotNull();
        }

        @Test
        @DisplayName("Should be immune to external list modification")
        void shouldBeImmuneToExternalListModification() {
            StubSkillRegistry stub = new StubSkillRegistry();
            stub.addSkill(createSkill("commit", "Commit guide"));

            List<SkillRegistry> mutable = new ArrayList<>();
            mutable.add(stub);
            CompositeSkillRegistry composite = new CompositeSkillRegistry(mutable);

            mutable.clear();

            assertThat(composite.getSkill("commit")).isPresent();
        }
    }

    @Nested
    @DisplayName("getSkill")
    class GetSkillTests {

        @Test
        @DisplayName("Should return skill from single registry")
        void shouldReturnSkillFromSingleRegistry() {
            StubSkillRegistry stub = new StubSkillRegistry();
            stub.addSkill(createSkill("commit", "Commit guide"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            Optional<Skill> result = composite.getSkill("commit");
            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("commit");
        }

        @Test
        @DisplayName("Should return empty when skill not found in any registry")
        void shouldReturnEmptyWhenNotFound() {
            StubSkillRegistry stub = new StubSkillRegistry();
            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            Optional<Skill> result = composite.getSkill("non-existent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return skill from higher-priority registry when same name exists")
        void shouldReturnFromHigherPriorityRegistry() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));

            StubSkillRegistry user = new StubSkillRegistry();
            user.addSkill(createSkill("commit", "User commit"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            Optional<Skill> result = composite.getSkill("commit");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("User commit");
        }

        @Test
        @DisplayName("Should return skill from highest-priority registry with three registries")
        void shouldReturnFromHighestPriorityWithThreeRegistries() {
            StubSkillRegistry low = new StubSkillRegistry();
            low.addSkill(createSkill("commit", "Low-priority commit"));

            StubSkillRegistry mid = new StubSkillRegistry();
            mid.addSkill(createSkill("commit", "Mid-priority commit"));

            StubSkillRegistry high = new StubSkillRegistry();
            high.addSkill(createSkill("commit", "High-priority commit"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(low, mid, high));

            Optional<Skill> result = composite.getSkill("commit");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("High-priority commit");
        }

        @Test
        @DisplayName("Should fall back to middle registry when not in highest with three registries")
        void shouldFallbackToMiddleRegistryWithThreeRegistries() {
            StubSkillRegistry low = new StubSkillRegistry();
            low.addSkill(createSkill("commit", "Low-priority commit"));

            StubSkillRegistry mid = new StubSkillRegistry();
            mid.addSkill(createSkill("commit", "Mid-priority commit"));

            StubSkillRegistry high = new StubSkillRegistry();
            // high does not have "commit"

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(low, mid, high));

            Optional<Skill> result = composite.getSkill("commit");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("Mid-priority commit");
        }

        @Test
        @DisplayName("Should fall back to lower-priority registry when not in higher")
        void shouldFallbackToLowerPriorityRegistry() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));

            StubSkillRegistry user = new StubSkillRegistry();

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            Optional<Skill> result = composite.getSkill("commit");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("Built-in commit");
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            StubSkillRegistry stub = new StubSkillRegistry();
            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            assertThatThrownBy(() -> composite.getSkill(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Skill name cannot be null");
        }
    }

    @Nested
    @DisplayName("getAllSkills")
    class GetAllSkillsTests {

        @Test
        @DisplayName("Should return all skills from single registry")
        void shouldReturnAllFromSingleRegistry() {
            StubSkillRegistry stub = new StubSkillRegistry();
            stub.addSkill(createSkill("commit", "Commit guide"));
            stub.addSkill(createSkill("summarize", "Summarize guide"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            List<Skill> all = composite.getAllSkills();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Skill::getName).containsExactlyInAnyOrder("commit", "summarize");
        }

        @Test
        @DisplayName("Should merge skills from multiple registries")
        void shouldMergeFromMultipleRegistries() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));
            builtin.addSkill(createSkill("summarize", "Built-in summarize"));

            StubSkillRegistry user = new StubSkillRegistry();
            user.addSkill(createSkill("custom", "User custom"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            List<Skill> all = composite.getAllSkills();
            assertThat(all).hasSize(3);
            assertThat(all).extracting(Skill::getName).containsExactlyInAnyOrder("commit", "summarize", "custom");
        }

        @Test
        @DisplayName("Should override lower-priority skill with higher-priority one")
        void shouldOverrideLowerPrioritySkill() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));
            builtin.addSkill(createSkill("summarize", "Built-in summarize"));

            StubSkillRegistry user = new StubSkillRegistry();
            user.addSkill(createSkill("commit", "User commit"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            List<Skill> all = composite.getAllSkills();
            assertThat(all).hasSize(2);

            Skill commit = all.stream().filter(s -> s.getName().equals("commit")).findFirst().orElseThrow();
            assertThat(commit.getMetadata().getDescription()).isEqualTo("User commit");
        }

        @Test
        @DisplayName("Should correctly merge and override with three registries")
        void shouldMergeAndOverrideWithThreeRegistries() {
            StubSkillRegistry low = new StubSkillRegistry();
            low.addSkill(createSkill("commit", "Low commit"));
            low.addSkill(createSkill("summarize", "Low summarize"));

            StubSkillRegistry mid = new StubSkillRegistry();
            mid.addSkill(createSkill("commit", "Mid commit"));
            mid.addSkill(createSkill("review", "Mid review"));

            StubSkillRegistry high = new StubSkillRegistry();
            high.addSkill(createSkill("commit", "High commit"));
            high.addSkill(createSkill("custom", "High custom"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(low, mid, high));

            List<Skill> all = composite.getAllSkills();
            assertThat(all).hasSize(4);
            assertThat(all).extracting(Skill::getName).containsExactlyInAnyOrder("commit", "summarize", "review",
                    "custom");

            // "commit" should be the highest-priority version
            Skill commit = all.stream().filter(s -> s.getName().equals("commit")).findFirst().orElseThrow();
            assertThat(commit.getMetadata().getDescription()).isEqualTo("High commit");
        }

        @Test
        @DisplayName("Should return empty list when all registries are empty")
        void shouldReturnEmptyWhenAllRegistriesEmpty() {
            StubSkillRegistry stub = new StubSkillRegistry();
            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            List<Skill> all = composite.getAllSkills();
            assertThat(all).isEmpty();
        }
    }

    @Nested
    @DisplayName("reloadSkill")
    class ReloadSkillTests {

        @Test
        @DisplayName("Should propagate reload to registries that have the skill")
        void shouldPropagateReloadToRegistriesWithSkill() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));

            StubSkillRegistry user = new StubSkillRegistry();
            user.addSkill(createSkill("commit", "User commit"));

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            composite.reloadSkill("commit");

            assertThat(builtin.getLastReloadedName()).isEqualTo("commit");
            assertThat(user.getLastReloadedName()).isEqualTo("commit");
        }

        @Test
        @DisplayName("Should succeed when skill exists in only one registry")
        void shouldSucceedWhenSkillExistsInOneRegistry() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            builtin.addSkill(createSkill("commit", "Built-in commit"));

            StubSkillRegistry user = new StubSkillRegistry();
            // user does not have "commit"

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            composite.reloadSkill("commit");

            assertThat(builtin.getLastReloadedName()).isEqualTo("commit");
        }

        @Test
        @DisplayName("Should throw SkillNotFoundException when skill not found in any registry")
        void shouldThrowWhenSkillNotFoundInAnyRegistry() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            StubSkillRegistry user = new StubSkillRegistry();

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            assertThatThrownBy(() -> composite.reloadSkill("non-existent")).isInstanceOf(SkillNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            StubSkillRegistry stub = new StubSkillRegistry();
            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(stub));

            assertThatThrownBy(() -> composite.reloadSkill(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Skill name cannot be null");
        }
    }

    @Nested
    @DisplayName("reloadAll")
    class ReloadAllTests {

        @Test
        @DisplayName("Should propagate reloadAll to all registries")
        void shouldPropagateReloadAllToAllRegistries() {
            StubSkillRegistry builtin = new StubSkillRegistry();
            StubSkillRegistry user = new StubSkillRegistry();

            CompositeSkillRegistry composite = new CompositeSkillRegistry(List.of(builtin, user));

            composite.reloadAll();

            assertThat(builtin.isReloadAllCalled()).isTrue();
            assertThat(user.isReloadAllCalled()).isTrue();
        }
    }

    // --- Helper methods ---

    private static Skill createSkill(String name, String description) {
        SkillMetadata metadata = SkillMetadata.builder().name(name).description(description).build();
        SkillContent content = SkillContent.of("Instructions for " + name);
        return Skill.builder().name(name).metadata(metadata).content(content).build();
    }

    // --- Stub implementation for testing ---

    private static class StubSkillRegistry implements SkillRegistry {

        private final List<Skill> skills = new ArrayList<>();
        private String lastReloadedName;
        private boolean reloadAllCalled;

        void addSkill(Skill skill) {
            skills.add(skill);
        }

        String getLastReloadedName() {
            return lastReloadedName;
        }

        boolean isReloadAllCalled() {
            return reloadAllCalled;
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return skills.stream().filter(s -> s.getName().equals(skillName)).findFirst();
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(skills);
        }

        @Override
        public void reloadSkill(String skillName) {
            lastReloadedName = skillName;
            boolean found = skills.stream().anyMatch(s -> s.getName().equals(skillName));
            if (!found) {
                throw new SkillNotFoundException(skillName);
            }
        }

        @Override
        public void reloadAll() {
            reloadAllCalled = true;
        }
    }
}
