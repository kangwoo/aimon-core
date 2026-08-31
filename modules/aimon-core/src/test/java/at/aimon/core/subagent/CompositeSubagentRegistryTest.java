package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompositeSubagentRegistry Tests")
class CompositeSubagentRegistryTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should throw exception when registries is null")
        void shouldThrowWhenRegistriesIsNull() {
            assertThatThrownBy(() -> new CompositeSubagentRegistry(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Registries cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when registries is empty")
        void shouldThrowWhenRegistriesIsEmpty() {
            assertThatThrownBy(() -> new CompositeSubagentRegistry(List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Registries cannot be empty");
        }

        @Test
        @DisplayName("Should create with single registry")
        void shouldCreateWithSingleRegistry() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));
            assertThat(composite).isNotNull();
        }

        @Test
        @DisplayName("Should be immune to external list modification")
        void shouldBeImmuneToExternalListModification() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            stub.addSubagent(createSubagent("agent1", "desc1"));

            List<SubagentRegistry> mutable = new ArrayList<>();
            mutable.add(stub);
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(mutable);

            // Modify the original list
            mutable.clear();

            // Composite should still work
            assertThat(composite.getSubagent("agent1")).isPresent();
        }
    }

    @Nested
    @DisplayName("getSubagent")
    class GetSubagentTests {

        @Test
        @DisplayName("Should return subagent from single registry")
        void shouldReturnSubagentFromSingleRegistry() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            stub.addSubagent(createSubagent("explore", "Explorer agent"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            Optional<Subagent> result = composite.getSubagent("explore");
            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("explore");
        }

        @Test
        @DisplayName("Should return empty when subagent not found in any registry")
        void shouldReturnEmptyWhenNotFound() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            Optional<Subagent> result = composite.getSubagent("non-existent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return subagent from higher-priority registry when same name exists")
        void shouldReturnFromHigherPriorityRegistry() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            builtin.addSubagent(createSubagent("explore", "Built-in explorer"));

            StubSubagentRegistry user = new StubSubagentRegistry();
            user.addSubagent(createSubagent("explore", "User-defined explorer"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            Optional<Subagent> result = composite.getSubagent("explore");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("User-defined explorer");
        }

        @Test
        @DisplayName("Should return subagent from highest-priority registry with three registries")
        void shouldReturnFromHighestPriorityWithThreeRegistries() {
            StubSubagentRegistry low = new StubSubagentRegistry();
            low.addSubagent(createSubagent("explore", "Low-priority explorer"));

            StubSubagentRegistry mid = new StubSubagentRegistry();
            mid.addSubagent(createSubagent("explore", "Mid-priority explorer"));

            StubSubagentRegistry high = new StubSubagentRegistry();
            high.addSubagent(createSubagent("explore", "High-priority explorer"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(low, mid, high));

            Optional<Subagent> result = composite.getSubagent("explore");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("High-priority explorer");
        }

        @Test
        @DisplayName("Should fall back to middle registry when not in highest with three registries")
        void shouldFallbackToMiddleRegistryWithThreeRegistries() {
            StubSubagentRegistry low = new StubSubagentRegistry();
            low.addSubagent(createSubagent("explore", "Low-priority explorer"));

            StubSubagentRegistry mid = new StubSubagentRegistry();
            mid.addSubagent(createSubagent("explore", "Mid-priority explorer"));

            StubSubagentRegistry high = new StubSubagentRegistry();
            // high does not have "explore"

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(low, mid, high));

            Optional<Subagent> result = composite.getSubagent("explore");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("Mid-priority explorer");
        }

        @Test
        @DisplayName("Should fall back to lower-priority registry when not in higher")
        void shouldFallbackToLowerPriorityRegistry() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            builtin.addSubagent(createSubagent("explore", "Built-in explorer"));

            StubSubagentRegistry user = new StubSubagentRegistry();
            // User registry does not have "explore"

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            Optional<Subagent> result = composite.getSubagent("explore");
            assertThat(result).isPresent();
            assertThat(result.get().getMetadata().getDescription()).isEqualTo("Built-in explorer");
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            assertThatThrownBy(() -> composite.getSubagent(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Subagent name cannot be null");
        }
    }

    @Nested
    @DisplayName("getAllSubagents")
    class GetAllSubagentsTests {

        @Test
        @DisplayName("Should return all subagents from single registry")
        void shouldReturnAllFromSingleRegistry() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            stub.addSubagent(createSubagent("agent1", "Agent 1"));
            stub.addSubagent(createSubagent("agent2", "Agent 2"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            List<Subagent> all = composite.getAllSubagents();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Subagent::getName).containsExactlyInAnyOrder("agent1", "agent2");
        }

        @Test
        @DisplayName("Should merge subagents from multiple registries")
        void shouldMergeFromMultipleRegistries() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            builtin.addSubagent(createSubagent("explore", "Built-in explore"));
            builtin.addSubagent(createSubagent("reviewer", "Built-in reviewer"));

            StubSubagentRegistry user = new StubSubagentRegistry();
            user.addSubagent(createSubagent("custom", "User custom"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            List<Subagent> all = composite.getAllSubagents();
            assertThat(all).hasSize(3);
            assertThat(all).extracting(Subagent::getName).containsExactlyInAnyOrder("explore", "reviewer", "custom");
        }

        @Test
        @DisplayName("Should override lower-priority subagent with higher-priority one")
        void shouldOverrideLowerPrioritySubagent() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            builtin.addSubagent(createSubagent("explore", "Built-in explore"));
            builtin.addSubagent(createSubagent("reviewer", "Built-in reviewer"));

            StubSubagentRegistry user = new StubSubagentRegistry();
            user.addSubagent(createSubagent("explore", "User explore"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            List<Subagent> all = composite.getAllSubagents();
            assertThat(all).hasSize(2);

            // "explore" should be the user version
            Subagent explore = all.stream().filter(s -> s.getName().equals("explore")).findFirst().orElseThrow();
            assertThat(explore.getMetadata().getDescription()).isEqualTo("User explore");
        }

        @Test
        @DisplayName("Should correctly merge and override with three registries")
        void shouldMergeAndOverrideWithThreeRegistries() {
            StubSubagentRegistry low = new StubSubagentRegistry();
            low.addSubagent(createSubagent("explore", "Low explore"));
            low.addSubagent(createSubagent("reviewer", "Low reviewer"));

            StubSubagentRegistry mid = new StubSubagentRegistry();
            mid.addSubagent(createSubagent("explore", "Mid explore"));
            mid.addSubagent(createSubagent("analyzer", "Mid analyzer"));

            StubSubagentRegistry high = new StubSubagentRegistry();
            high.addSubagent(createSubagent("explore", "High explore"));
            high.addSubagent(createSubagent("custom", "High custom"));

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(low, mid, high));

            List<Subagent> all = composite.getAllSubagents();
            assertThat(all).hasSize(4);
            assertThat(all).extracting(Subagent::getName).containsExactlyInAnyOrder("explore", "reviewer", "analyzer",
                    "custom");

            // "explore" should be the highest-priority version
            Subagent explore = all.stream().filter(s -> s.getName().equals("explore")).findFirst().orElseThrow();
            assertThat(explore.getMetadata().getDescription()).isEqualTo("High explore");
        }

        @Test
        @DisplayName("Should return empty list when all registries are empty")
        void shouldReturnEmptyWhenAllRegistriesEmpty() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            List<Subagent> all = composite.getAllSubagents();
            assertThat(all).isEmpty();
        }
    }

    @Nested
    @DisplayName("reloadSubagent")
    class ReloadSubagentTests {

        @Test
        @DisplayName("Should propagate reload to all registries")
        void shouldPropagateReloadToAllRegistries() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            StubSubagentRegistry user = new StubSubagentRegistry();

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            composite.reloadSubagent("explore");

            assertThat(builtin.getLastReloadedName()).isEqualTo("explore");
            assertThat(user.getLastReloadedName()).isEqualTo("explore");
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            StubSubagentRegistry stub = new StubSubagentRegistry();
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(stub));

            assertThatThrownBy(() -> composite.reloadSubagent(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Subagent name cannot be null");
        }
    }

    @Nested
    @DisplayName("reloadAll")
    class ReloadAllTests {

        @Test
        @DisplayName("Should propagate reloadAll to all registries")
        void shouldPropagateReloadAllToAllRegistries() {
            StubSubagentRegistry builtin = new StubSubagentRegistry();
            StubSubagentRegistry user = new StubSubagentRegistry();

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(builtin, user));

            composite.reloadAll();

            assertThat(builtin.isReloadAllCalled()).isTrue();
            assertThat(user.isReloadAllCalled()).isTrue();
        }
    }

    // --- Helper methods ---

    private static Subagent createSubagent(String name, String description) {
        SubagentMetadata metadata = SubagentMetadata.builder().description(description).build();
        SubagentContent content = SubagentContent.of("System prompt for " + name);
        return Subagent.of(name, metadata, content);
    }

    // --- Stub implementation for testing ---

    private static class StubSubagentRegistry implements SubagentRegistry {

        private final List<Subagent> subagents = new ArrayList<>();
        private String lastReloadedName;
        private boolean reloadAllCalled;

        void addSubagent(Subagent subagent) {
            subagents.add(subagent);
        }

        String getLastReloadedName() {
            return lastReloadedName;
        }

        boolean isReloadAllCalled() {
            return reloadAllCalled;
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return subagents.stream().filter(s -> s.getName().equals(subagentName)).findFirst();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return new ArrayList<>(subagents);
        }

        @Override
        public void reloadSubagent(String subagentName) {
            lastReloadedName = subagentName;
        }

        @Override
        public void reloadAll() {
            reloadAllCalled = true;
        }
    }
}
