package at.aimon.core.agent.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurnId;

/** Unit tests for {@link SkillTurnSuspendedEvent}. */
class SkillTurnSuspendedEventTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final AgentRuntimeId CTX = AgentRuntimeId.of("agent:test-1");
    private static final PendingTurnId PT = PendingTurnId.generate();
    private static final PendingSkillRequest REQ = PendingSkillRequest.builder().toolUseId("tu1").skillName("commit")
            .build();

    @Test
    void buildSucceedsWithAllFields() {
        final SkillTurnSuspendedEvent event = newEvent().build();

        assertThat(event.getTimestamp()).isEqualTo(T0);
        assertThat(event.getAgentRuntimeId()).isEqualTo(CTX);
        assertThat(event.getIteration()).isEqualTo(3);
        assertThat(event.getPendingTurnId()).isEqualTo(PT);
        assertThat(event.getPendingSkills()).containsExactly(REQ);
    }

    @Test
    void buildRequiresMandatoryFields() {
        assertThatThrownBy(() -> SkillTurnSuspendedEvent.builder().build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyPendingSkillsRejected() {
        assertThatThrownBy(() -> SkillTurnSuspendedEvent.builder().timestamp(T0).agentRuntimeId(CTX).iteration(1)
                .pendingTurnId(PT).pendingSkills(List.of()).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingSkillsImmutableSnapshot() {
        final java.util.List<PendingSkillRequest> mutable = new java.util.ArrayList<>(List.of(REQ));
        final SkillTurnSuspendedEvent event = SkillTurnSuspendedEvent.builder().timestamp(T0).agentRuntimeId(CTX)
                .iteration(1).pendingTurnId(PT).pendingSkills(mutable).build();

        mutable.add(PendingSkillRequest.builder().skillName("other").build());

        assertThat(event.getPendingSkills()).hasSize(1);
        assertThatThrownBy(() -> event.getPendingSkills().add(REQ)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toStringIncludesPendingTurnIdAndCount() {
        final String repr = newEvent().build().toString();

        assertThat(repr).contains("SkillTurnSuspendedEvent").contains(PT.toString()).contains("pendingSkills=1");
    }

    @Test
    void equalityBasedOnAllFields() {
        final SkillTurnSuspendedEvent a = newEvent().build();
        final SkillTurnSuspendedEvent b = newEvent().build();
        final SkillTurnSuspendedEvent different = newEvent().pendingTurnId(PendingTurnId.generate()).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    private static SkillTurnSuspendedEvent.Builder newEvent() {
        return SkillTurnSuspendedEvent.builder().timestamp(T0).agentRuntimeId(CTX).iteration(3).pendingTurnId(PT)
                .pendingSkills(List.of(REQ));
    }
}
