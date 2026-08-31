package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;

@DisplayName("StepKey — runId + agentRuntimeId + structural step-path")
class StepKeyTest {

    private static final RunId RUN = RunId.from("audit");
    private static final AgentRuntimeId CTX_A = AgentRuntimeId.fromName("agent-a");
    private static final AgentRuntimeId CTX_B = AgentRuntimeId.fromName("agent-b");

    @Test
    @DisplayName("composes the canonical value <runId>/<agentRuntimeId>/<path>")
    void composeValue() {
        final StepKey key = StepKey.of(RUN, CTX_A, "p0/0/a0");

        assertThat(key.value()).isEqualTo("run:audit/agent:agent-a/p0/0/a0");
        assertThat(key.runId()).isEqualTo(RUN);
        assertThat(key.agentRuntimeId()).contains(CTX_A);
        assertThat(key.path()).isEqualTo("p0/0/a0");
        assertThat(key).hasToString("run:audit/agent:agent-a/p0/0/a0");
    }

    @Test
    @DisplayName("a null context renders as '-' and surfaces as an empty Optional")
    void nullContext() {
        final StepKey key = StepKey.of(RUN, null, "a0");

        assertThat(key.value()).isEqualTo("run:audit/-/a0");
        assertThat(key.agentRuntimeId()).isEmpty();
    }

    @Test
    @DisplayName("equals/hashCode key off the composite value; path or context differences make distinct keys")
    void equality() {
        assertThat(StepKey.of(RUN, CTX_A, "a0")).isEqualTo(StepKey.of(RUN, CTX_A, "a0"))
                .hasSameHashCodeAs(StepKey.of(RUN, CTX_A, "a0"));
        // structural: sibling parallels / identical-input branches must not collide
        assertThat(StepKey.of(RUN, CTX_A, "p0/0/a0")).isNotEqualTo(StepKey.of(RUN, CTX_A, "p0/1/a0"));
        assertThat(StepKey.of(RUN, CTX_A, "p0/0/a0")).isNotEqualTo(StepKey.of(RUN, CTX_A, "p1/0/a0"));
        // isolation: same path, different owning context → different key
        assertThat(StepKey.of(RUN, CTX_A, "a0")).isNotEqualTo(StepKey.of(RUN, CTX_B, "a0"));
    }

    @Test
    @DisplayName("rejects a null runId or a null/blank path")
    void rejectsInvalid() {
        assertThatThrownBy(() -> StepKey.of(null, CTX_A, "a0")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StepKey.of(RUN, CTX_A, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepKey.of(RUN, CTX_A, "  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
