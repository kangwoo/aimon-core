package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentMetadata - the context policy is part of the agent's identity")
class AgentMetadataTest {

    private static AgentMetadata.Builder named() {
        return AgentMetadata.builder().name("a");
    }

    @Test
    @DisplayName("a null policy is the empty policy, and equal to one set explicitly")
    void nullPolicyIsNone() {
        final AgentMetadata unset = named().build();
        final AgentMetadata explicitNone = named().contextPolicy(ContextPolicy.none()).build();

        assertThat(unset.getContextPolicy()).isEqualTo(ContextPolicy.none());
        assertThat(unset).isEqualTo(explicitNone).hasSameHashCodeAs(explicitNone);
        assertThat(unset.toString()).doesNotContain("contextPolicy");
    }

    @Test
    @DisplayName("metadata with and without a policy differ; equal policies are equal")
    void policyTakesPartInEquality() {
        final ContextPolicy policy = ContextPolicy.builder().maxWindowTokens(500_000).autoCompactRatio(0.5).build();
        final AgentMetadata with = named().contextPolicy(policy).build();
        final AgentMetadata same = named()
                .contextPolicy(ContextPolicy.builder().maxWindowTokens(500_000).autoCompactRatio(0.5).build()).build();

        assertThat(with).isNotEqualTo(named().build());
        assertThat(with).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(with.toString()).contains("contextPolicy=");
    }
}
