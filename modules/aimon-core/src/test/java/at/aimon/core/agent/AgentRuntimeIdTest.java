package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentRuntimeId Tests")
class AgentRuntimeIdTest {

    private static Agent agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    @Test
    @DisplayName("of() reconstructs an id from a serialized value")
    void of_validValue() {
        AgentRuntimeId id = AgentRuntimeId.of("agent:my-agent");

        assertThat(id.value()).isEqualTo("agent:my-agent");
    }

    @Test
    @DisplayName("of() throws NPE for null value")
    void of_null_throwsNPE() {
        assertThatThrownBy(() -> AgentRuntimeId.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("of() throws IAE for value missing 'agent:' prefix")
    void of_missingPrefix_throwsIAE() {
        assertThatThrownBy(() -> AgentRuntimeId.of("foo:bar")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent:");
    }

    @Test
    @DisplayName("of() throws IAE for blank tail (only prefix)")
    void of_blankTail_throwsIAE() {
        assertThatThrownBy(() -> AgentRuntimeId.of("agent:")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-name");
        assertThatThrownBy(() -> AgentRuntimeId.of("agent:   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-name");
    }

    @Test
    @DisplayName("of() throws IAE for empty name segment with discriminator")
    void of_emptyNameSegment_throwsIAE() {
        assertThatThrownBy(() -> AgentRuntimeId.of("agent::tenant-a")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-name");
    }

    @Test
    @DisplayName("of() throws IAE for empty discriminator segment")
    void of_emptyDiscriminatorSegment_throwsIAE() {
        assertThatThrownBy(() -> AgentRuntimeId.of("agent:foo:")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discriminator");
    }

    @Test
    @DisplayName("of() throws IAE for discriminator containing ':'")
    void of_discriminatorWithColon_throwsIAE() {
        assertThatThrownBy(() -> AgentRuntimeId.of("agent:foo:a:b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":");
    }

    @Test
    @DisplayName("of() round-trips both bare and discriminated forms")
    void of_roundTrips() {
        AgentRuntimeId bare = AgentRuntimeId.of("agent:foo");
        assertThat(bare.agentName()).isEqualTo("foo");
        assertThat(bare.discriminator()).isEmpty();

        AgentRuntimeId composite = AgentRuntimeId.of("agent:foo:tenant-a");
        assertThat(composite.agentName()).isEqualTo("foo");
        assertThat(composite.discriminator()).hasValue("tenant-a");
    }

    @Test
    @DisplayName("from(agent) returns 'agent:<name>'")
    void from_agent_returnsPrefixedName() {
        Agent agent = agentNamed("alpha");

        AgentRuntimeId id = AgentRuntimeId.from(agent);

        assertThat(id.value()).isEqualTo("agent:alpha");
    }

    @Test
    @DisplayName("from(agent) for the same agent name produces equal ids")
    void from_agent_isDeterministic() {
        Agent a1 = agentNamed("beta");
        Agent a2 = agentNamed("beta");

        assertThat(AgentRuntimeId.from(a1)).isEqualTo(AgentRuntimeId.from(a2));
    }

    @Test
    @DisplayName("from(agent) throws NPE for null agent")
    void from_nullAgent_throwsNPE() {
        assertThatThrownBy(() -> AgentRuntimeId.from((Agent) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("from(agent, discriminator) returns 'agent:<name>:<discriminator>'")
    void from_agentDiscriminator_returnsCompositeId() {
        Agent agent = agentNamed("gamma");

        AgentRuntimeId tenantA = AgentRuntimeId.from(agent, "tenant-a");
        AgentRuntimeId tenantB = AgentRuntimeId.from(agent, "tenant-b");

        assertThat(tenantA.value()).isEqualTo("agent:gamma:tenant-a");
        assertThat(tenantB.value()).isEqualTo("agent:gamma:tenant-b");
        assertThat(tenantA).isNotEqualTo(tenantB);
    }

    @Test
    @DisplayName("from(agent, discriminator) rejects null/blank discriminator")
    void from_blankDiscriminator_throwsIAE() {
        Agent agent = agentNamed("delta");

        assertThatThrownBy(() -> AgentRuntimeId.from(agent, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discriminator");
        assertThatThrownBy(() -> AgentRuntimeId.from(agent, "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discriminator");
        assertThatThrownBy(() -> AgentRuntimeId.from(agent, "   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discriminator");
    }

    @Test
    @DisplayName("from(agent, discriminator) rejects discriminator containing ':'")
    void from_colonDiscriminator_throwsIAE() {
        Agent agent = agentNamed("epsilon");

        assertThatThrownBy(() -> AgentRuntimeId.from(agent, "x:y")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":");
    }

    @Test
    @DisplayName("equals returns true for same value")
    void equals_sameValue() {
        AgentRuntimeId id1 = AgentRuntimeId.of("agent:x");
        AgentRuntimeId id2 = AgentRuntimeId.of("agent:x");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo("agent:x");
    }

    @Test
    @DisplayName("hashCode is consistent for equal ids")
    void hashCode_consistency() {
        AgentRuntimeId id1 = AgentRuntimeId.of("agent:y");
        AgentRuntimeId id2 = AgentRuntimeId.of("agent:y");

        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    @DisplayName("toString returns the underlying value")
    void toString_returnsValue() {
        AgentRuntimeId id = AgentRuntimeId.of("agent:z");

        assertThat(id.toString()).isEqualTo("agent:z");
    }

    @Test
    @DisplayName("fromName(name) yields the same value as from(agent) when names match")
    void fromName_matchesFromAgent() {
        Agent agent = agentNamed("alpha");

        assertThat(AgentRuntimeId.fromName("alpha")).isEqualTo(AgentRuntimeId.from(agent));
    }

    @Test
    @DisplayName("fromName(name, discriminator) yields the same value as from(agent, discriminator)")
    void fromName_withDiscriminator_matchesFromAgent() {
        Agent agent = agentNamed("alpha");

        assertThat(AgentRuntimeId.fromName("alpha", "tenant-a")).isEqualTo(AgentRuntimeId.from(agent, "tenant-a"));
    }

    @Test
    @DisplayName("fromName(name) rejects null/blank/colon-containing names")
    void fromName_rejectsBadNames() {
        assertThatThrownBy(() -> AgentRuntimeId.fromName(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent name");
        assertThatThrownBy(() -> AgentRuntimeId.fromName("")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent name");
        assertThatThrownBy(() -> AgentRuntimeId.fromName("foo:bar")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":");
    }

    @Test
    @DisplayName("fromName(name, discriminator) rejects bad discriminator")
    void fromName_rejectsBadDiscriminator() {
        assertThatThrownBy(() -> AgentRuntimeId.fromName("alpha", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discriminator");
        assertThatThrownBy(() -> AgentRuntimeId.fromName("alpha", "x:y")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":");
    }

    @Test
    @DisplayName("agentName() returns the name segment for both bare and discriminated ids")
    void agentName_extractsName() {
        assertThat(AgentRuntimeId.fromName("alpha").agentName()).isEqualTo("alpha");
        assertThat(AgentRuntimeId.fromName("alpha", "tenant-a").agentName()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("discriminator() returns empty for bare ids and the segment for composite ids")
    void discriminator_extractsSuffix() {
        assertThat(AgentRuntimeId.fromName("alpha").discriminator()).isEmpty();
        assertThat(AgentRuntimeId.fromName("alpha", "tenant-a").discriminator()).hasValue("tenant-a");
    }
}
