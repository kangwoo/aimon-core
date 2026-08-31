package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultAgentRegistry Tests")
class DefaultAgentRegistryTest {

    private DefaultAgentRegistry registry;
    private Agent agent1;
    private Agent agent2;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        agent1 = DefaultAgent.builder().name("agent1").systemPrompt("Agent 1 prompt").build();
        agent2 = DefaultAgent.builder().name("agent2").systemPrompt("Agent 2 prompt").build();
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("Should register agent successfully")
        void testRegisterAgent() {
            registry.register(agent1);

            assertThat(registry.findByName("agent1")).isPresent().hasValue(agent1);
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should register multiple agents")
        void testRegisterMultipleAgents() {
            registry.register(agent1);
            registry.register(agent2);

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.findByName("agent1")).isPresent();
            assertThat(registry.findByName("agent2")).isPresent();
        }

        @Test
        @DisplayName("Should replace existing agent with same name")
        void testReplaceExistingAgent() {
            registry.register(agent1);
            Agent replacement = DefaultAgent.builder().name("agent1").systemPrompt("Replacement agent prompt").build();
            registry.register(replacement);

            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.findByName("agent1")).hasValue(replacement);
        }

        @Test
        @DisplayName("Should reject null agent")
        void testRejectNullAgent() {
            assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent cannot be null");
        }
    }

    @Nested
    @DisplayName("Unregistration")
    class Unregistration {

        @Test
        @DisplayName("Should unregister agent successfully")
        void testUnregisterAgent() {
            registry.register(agent1);
            registry.unregister("agent1");

            assertThat(registry.findByName("agent1")).isEmpty();
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle unregistering non-existent agent")
        void testUnregisterNonExistentAgent() {
            assertThatNoException().isThrownBy(() -> registry.unregister("nonexistent"));
        }

        @Test
        @DisplayName("Should reject null agent name")
        void testUnregisterRejectsNull() {
            assertThatThrownBy(() -> registry.unregister(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent name cannot be null");
        }
    }

    @Nested
    @DisplayName("Retrieval")
    class Retrieval {

        @Test
        @DisplayName("Should find agent by name")
        void testFindByName() {
            registry.register(agent1);

            var retrieved = registry.findByName("agent1");

            assertThat(retrieved).isPresent().hasValue(agent1);
        }

        @Test
        @DisplayName("Should return empty Optional for non-existent agent")
        void testFindNonExistentAgent() {
            var retrieved = registry.findByName("nonexistent");

            assertThat(retrieved).isEmpty();
        }

        @Test
        @DisplayName("Should reject null agent name in findByName")
        void testFindByNameRejectsNull() {
            assertThatThrownBy(() -> registry.findByName(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent name cannot be null");
        }
    }

    @Nested
    @DisplayName("FindAll")
    class FindAll {

        @Test
        @DisplayName("Should return all registered agents")
        void testFindAll() {
            registry.register(agent1);
            registry.register(agent2);

            List<Agent> agents = registry.findAll();

            assertThat(agents).hasSize(2).containsExactlyInAnyOrder(agent1, agent2);
        }

        @Test
        @DisplayName("Should return empty list when no agents registered")
        void testFindAllEmpty() {
            List<Agent> agents = registry.findAll();

            assertThat(agents).isEmpty();
        }

        @Test
        @DisplayName("Should return immutable list")
        void testFindAllImmutable() {
            registry.register(agent1);
            List<Agent> agents = registry.findAll();

            assertThatThrownBy(() -> agents.add(agent2)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Size and Empty")
    class SizeAndEmpty {

        @Test
        @DisplayName("Should return correct size")
        void testSize() {
            assertThat(registry.size()).isEqualTo(0);

            registry.register(agent1);
            assertThat(registry.size()).isEqualTo(1);

            registry.register(agent2);
            assertThat(registry.size()).isEqualTo(2);

            registry.unregister("agent1");
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should check if empty")
        void testIsEmpty() {
            assertThat(registry.isEmpty()).isTrue();

            registry.register(agent1);
            assertThat(registry.isEmpty()).isFalse();

            registry.clear();
            assertThat(registry.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Clear")
    class Clear {

        @Test
        @DisplayName("Should clear all agents")
        void testClear() {
            registry.register(agent1);
            registry.register(agent2);

            registry.clear();

            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle clearing empty registry")
        void testClearEmpty() {
            assertThatNoException().isThrownBy(() -> registry.clear());
        }
    }
}
