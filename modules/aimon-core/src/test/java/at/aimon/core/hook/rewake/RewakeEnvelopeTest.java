package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookEventType;

class RewakeEnvelopeTest {

    private static RewakeEnvelope.Builder validBuilder() {
        return RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(AgentRuntimeId.fromName("agent-x"))
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(1))).originalEventType(HookEventType.PRE_TOOL)
                .originatingHookId("hook-1").firstScheduledAt(Instant.parse("2026-01-01T00:00:00Z"))
                .reason("waiting-for-rate-limit");
    }

    @Test
    void buildPopulatesAllFields() {
        final ToolInput input = ToolInput.of(Map.of("k", "v"));
        final RewakeEnvelope env = validBuilder().originalToolName("Bash").originalToolInput(input).attemptNumber(2)
                .payload("ticket", "T-42").build();

        assertThat(env.getEnvelopeId()).isEqualTo("env-1");
        assertThat(env.getAgentRuntimeId().agentName()).isEqualTo("agent-x");
        assertThat(env.getTrigger()).isInstanceOf(RewakeTriggerDelay.class);
        assertThat(env.getOriginalEventType()).isEqualTo(HookEventType.PRE_TOOL);
        assertThat(env.getOriginatingHookId()).isEqualTo("hook-1");
        assertThat(env.getOriginalToolName()).contains("Bash");
        assertThat(env.getOriginalToolInput()).contains(input);
        assertThat(env.getAttemptNumber()).isEqualTo(2);
        assertThat(env.getFirstScheduledAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(env.getPayload()).containsExactly(Map.entry("ticket", "T-42"));
        assertThat(env.getReason()).isEqualTo("waiting-for-rate-limit");
    }

    @Test
    void buildRequiresTrigger() {
        assertThatNullPointerException().isThrownBy(
                () -> RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(AgentRuntimeId.fromName("agent-x"))
                        .originalEventType(HookEventType.PRE_TOOL).originatingHookId("hook-1")
                        .firstScheduledAt(Instant.parse("2026-01-01T00:00:00Z")).reason("r").build());
    }

    @Test
    void defaultAttemptNumberIsOne() {
        final RewakeEnvelope env = validBuilder().build();
        assertThat(env.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void lifecycleEventsLeaveToolFieldsEmpty() {
        final RewakeEnvelope env = validBuilder().originalEventType(HookEventType.ON_STOP).build();
        assertThat(env.getOriginalToolName()).isEmpty();
        assertThat(env.getOriginalToolInput()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatNullPointerException().isThrownBy(() -> RewakeEnvelope.builder().build());
        assertThatNullPointerException().isThrownBy(() -> validBuilder().agentRuntimeId(null).build());
    }

    @Test
    void rejectsBlankEnvelopeId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validBuilder().envelopeId("").build());
    }

    @Test
    void rejectsBlankOriginatingHookId() {
        assertThatIllegalArgumentException().isThrownBy(() -> validBuilder().originatingHookId("   ").build());
    }

    @Test
    void rejectsBlankReason() {
        assertThatIllegalArgumentException().isThrownBy(() -> validBuilder().reason("   ").build());
    }

    @Test
    void rejectsNonPositiveAttemptNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> validBuilder().attemptNumber(0).build());
        assertThatIllegalArgumentException().isThrownBy(() -> validBuilder().attemptNumber(-1).build());
    }

    @Test
    void payloadIsImmutable() {
        final RewakeEnvelope env = validBuilder().payload("k", "v").build();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> env.getPayload().put("k2", "v2"));
    }

    @Test
    void withIncrementedAttemptIncrementsCounter() {
        final RewakeEnvelope env = validBuilder().attemptNumber(2).build();
        final RewakeEnvelope next = env.withIncrementedAttempt();

        assertThat(next.getAttemptNumber()).isEqualTo(3);
        assertThat(next.getEnvelopeId()).isEqualTo(env.getEnvelopeId());
        assertThat(next.getReason()).isEqualTo(env.getReason());
    }

    @Test
    void toBuilderRoundTripsAllFields() {
        final ToolInput input = ToolInput.of(Map.of("k", "v"));
        final RewakeEnvelope original = validBuilder().originalToolName("Bash").originalToolInput(input)
                .attemptNumber(4).payload("k", "v").build();

        assertThat(original.toBuilder().build()).isEqualTo(original);
    }

    @Test
    void equalsAndHashCodeIncludeAllFields() {
        final RewakeEnvelope a = validBuilder().build();
        final RewakeEnvelope b = validBuilder().build();
        final RewakeEnvelope different = validBuilder().envelopeId("env-2").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void toStringMentionsKeyFields() {
        final RewakeEnvelope env = validBuilder().build();
        assertThat(env.toString()).contains("env-1").contains("hook-1").contains("waiting-for-rate-limit");
    }
}
