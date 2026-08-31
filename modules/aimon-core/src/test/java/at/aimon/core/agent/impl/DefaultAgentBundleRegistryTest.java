package at.aimon.core.agent.impl;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.DefaultAgent;

@DisplayName("DefaultAgentBundleRegistry Tests")
class DefaultAgentBundleRegistryTest {

    private DefaultAgentBundleRegistry registry;
    private AgentBundle bundle1;
    private AgentBundle bundle2;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentBundleRegistry();
        bundle1 = AgentBundle.builder()
                .agent(DefaultAgent.builder().name("agent1").systemPrompt("Agent 1 prompt").build()).build();
        bundle2 = AgentBundle.builder()
                .agent(DefaultAgent.builder().name("agent2").systemPrompt("Agent 2 prompt").build()).build();
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("Should register bundle successfully")
        void testRegisterBundle() {
            registry.register(bundle1);

            assertThat(registry.findByName("agent1")).isPresent().hasValue(bundle1);
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should register multiple bundles")
        void testRegisterMultipleBundles() {
            registry.register(bundle1);
            registry.register(bundle2);

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.findByName("agent1")).isPresent();
            assertThat(registry.findByName("agent2")).isPresent();
        }

        @Test
        @DisplayName("Should replace existing bundle with same agent name")
        void testReplaceExistingBundle() {
            registry.register(bundle1);
            AgentBundle replacement = AgentBundle.builder()
                    .agent(DefaultAgent.builder().name("agent1").systemPrompt("Replacement agent prompt").build())
                    .build();
            registry.register(replacement);

            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.findByName("agent1")).hasValue(replacement);
        }

        @Test
        @DisplayName("Should reject null bundle")
        void testRejectNullBundle() {
            assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("AgentBundle cannot be null");
        }
    }

    @Nested
    @DisplayName("Unregistration")
    class Unregistration {

        @Test
        @DisplayName("Should unregister bundle successfully")
        void testUnregisterBundle() {
            registry.register(bundle1);
            registry.unregister("agent1");

            assertThat(registry.findByName("agent1")).isEmpty();
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle unregistering non-existent bundle")
        void testUnregisterNonExistentBundle() {
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
        @DisplayName("Should find bundle by agent name")
        void testFindByName() {
            registry.register(bundle1);

            var retrieved = registry.findByName("agent1");

            assertThat(retrieved).isPresent().hasValue(bundle1);
        }

        @Test
        @DisplayName("Should return empty Optional for non-existent bundle")
        void testFindNonExistentBundle() {
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
        @DisplayName("Should return all registered bundles")
        void testFindAll() {
            registry.register(bundle1);
            registry.register(bundle2);

            List<AgentBundle> bundles = registry.findAll();

            assertThat(bundles).hasSize(2).containsExactlyInAnyOrder(bundle1, bundle2);
        }

        @Test
        @DisplayName("Should return empty list when no bundles registered")
        void testFindAllEmpty() {
            List<AgentBundle> bundles = registry.findAll();

            assertThat(bundles).isEmpty();
        }

        @Test
        @DisplayName("Should return immutable list")
        void testFindAllImmutable() {
            registry.register(bundle1);
            List<AgentBundle> bundles = registry.findAll();

            assertThatThrownBy(() -> bundles.add(bundle2)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Size and Empty")
    class SizeAndEmpty {

        @Test
        @DisplayName("Should return correct size")
        void testSize() {
            assertThat(registry.size()).isEqualTo(0);

            registry.register(bundle1);
            assertThat(registry.size()).isEqualTo(1);

            registry.register(bundle2);
            assertThat(registry.size()).isEqualTo(2);

            registry.unregister("agent1");
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should check if empty")
        void testIsEmpty() {
            assertThat(registry.isEmpty()).isTrue();

            registry.register(bundle1);
            assertThat(registry.isEmpty()).isFalse();

            registry.clear();
            assertThat(registry.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Clear")
    class Clear {

        @Test
        @DisplayName("Should clear all bundles")
        void testClear() {
            registry.register(bundle1);
            registry.register(bundle2);

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
