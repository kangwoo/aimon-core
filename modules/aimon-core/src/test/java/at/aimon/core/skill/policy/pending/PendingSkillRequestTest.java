package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link PendingSkillRequest}. */
class PendingSkillRequestTest {

    @Test
    void buildRequiresSkillName() {
        assertThatThrownBy(() -> PendingSkillRequest.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("skillName");
    }

    @Test
    void buildNormalisesNullArgsToEmptyString() {
        final PendingSkillRequest req = PendingSkillRequest.builder().skillName("commit").build();
        assertThat(req.getArgs()).isEmpty();
    }

    @Test
    void buildPreservesProvidedFields() {
        final PendingSkillRequest req = PendingSkillRequest.builder().toolUseId("tu_42").skillName("commit")
                .args("--scope=feat").build();

        assertThat(req.getToolUseId()).isEqualTo("tu_42");
        assertThat(req.getSkillName()).isEqualTo("commit");
        assertThat(req.getArgs()).isEqualTo("--scope=feat");
    }

    @Test
    void toolUseIdIsOptionalAndDefaultsToNull() {
        assertThat(PendingSkillRequest.builder().skillName("commit").build().getToolUseId()).isNull();
    }

    @Test
    void equalityBasedOnAllFields() {
        final PendingSkillRequest a = PendingSkillRequest.builder().toolUseId("tu1").skillName("commit").args("--x")
                .build();
        final PendingSkillRequest b = PendingSkillRequest.builder().toolUseId("tu1").skillName("commit").args("--x")
                .build();
        final PendingSkillRequest different = PendingSkillRequest.builder().toolUseId("tu2").skillName("commit")
                .args("--x").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void toStringDoesNotLeakArgsContent() {
        final PendingSkillRequest req = PendingSkillRequest.builder().skillName("commit").args("super-secret-token")
                .build();
        assertThat(req.toString()).contains("argsLen=18").doesNotContain("super-secret-token");
    }
}
