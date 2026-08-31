package at.aimon.core.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;

@DisplayName("AgentBundle Tests")
class AgentBundleTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with agent only")
        void shouldBuildWithAgentOnly() {
            Agent agent = mock(Agent.class);

            AgentBundle bundle = AgentBundle.builder().agent(agent).build();

            assertThat(bundle.getAgent()).isSameAs(agent);
            assertThat(bundle.getSubagentRegistry()).isEmpty();
            assertThat(bundle.getSkillRegistry()).isEmpty();
        }

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            Agent agent = mock(Agent.class);
            SubagentRegistry subagentRegistry = mock(SubagentRegistry.class);
            SkillRegistry skillRegistry = mock(SkillRegistry.class);

            AgentBundle bundle = AgentBundle.builder().agent(agent).subagentRegistry(subagentRegistry)
                    .skillRegistry(skillRegistry).build();

            assertThat(bundle.getAgent()).isSameAs(agent);
            assertThat(bundle.getSubagentRegistry()).containsSame(subagentRegistry);
            assertThat(bundle.getSkillRegistry()).containsSame(skillRegistry);
        }

        @Test
        @DisplayName("Should throw when agent is null")
        void shouldThrowWhenAgentIsNull() {
            assertThatThrownBy(() -> AgentBundle.builder().build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent cannot be null");
        }

        @Test
        @DisplayName("Should allow null subagent registry")
        void shouldAllowNullSubagentRegistry() {
            Agent agent = mock(Agent.class);

            AgentBundle bundle = AgentBundle.builder().agent(agent).subagentRegistry(null).build();

            assertThat(bundle.getSubagentRegistry()).isEmpty();
        }

        @Test
        @DisplayName("Should allow null skill registry")
        void shouldAllowNullSkillRegistry() {
            Agent agent = mock(Agent.class);

            AgentBundle bundle = AgentBundle.builder().agent(agent).skillRegistry(null).build();

            assertThat(bundle.getSkillRegistry()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Should include registry presence info")
        void shouldIncludeRegistryPresenceInfo() {
            Agent agent = mock(Agent.class);
            SubagentRegistry subagentRegistry = mock(SubagentRegistry.class);

            AgentBundle bundle = AgentBundle.builder().agent(agent).subagentRegistry(subagentRegistry).build();

            String result = bundle.toString();
            assertThat(result).contains("hasSubagentRegistry=true");
            assertThat(result).contains("hasSkillRegistry=false");
        }
    }

}
