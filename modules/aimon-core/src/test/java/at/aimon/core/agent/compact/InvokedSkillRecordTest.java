package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link InvokedSkillRecord}. */
class InvokedSkillRecordTest {

    @Test
    void of_NameOnly_NormalisesArgsToEmptyString() {
        final InvokedSkillRecord record = InvokedSkillRecord.of("commit");

        assertThat(record.getName()).isEqualTo("commit");
        assertThat(record.getArgs()).isEmpty();
        assertThat(record.getArgsOptional()).isEmpty();
    }

    @Test
    void of_NameAndArgs_RetainsBoth() {
        final InvokedSkillRecord record = InvokedSkillRecord.of("commit", "--scope=feat");

        assertThat(record.getName()).isEqualTo("commit");
        assertThat(record.getArgs()).isEqualTo("--scope=feat");
        assertThat(record.getArgsOptional()).contains("--scope=feat");
    }

    @Test
    void of_NullArgs_NormalisedToEmptyString() {
        final InvokedSkillRecord record = InvokedSkillRecord.of("commit", null);

        assertThat(record.getArgs()).isEmpty();
        assertThat(record.getArgsOptional()).isEmpty();
    }

    @Test
    void of_NullName_Throws() {
        assertThatThrownBy(() -> InvokedSkillRecord.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name");
    }

    @Test
    void of_BlankName_Throws() {
        assertThatThrownBy(() -> InvokedSkillRecord.of("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void equality_BasedOnNameAndArgs() {
        final InvokedSkillRecord a = InvokedSkillRecord.of("commit", "--scope=feat");
        final InvokedSkillRecord b = InvokedSkillRecord.of("commit", "--scope=feat");
        final InvokedSkillRecord different = InvokedSkillRecord.of("commit", "--scope=fix");
        final InvokedSkillRecord noArgs = InvokedSkillRecord.of("commit");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(noArgs);
    }

    @Test
    void equality_NullArgsAndEmptyArgsAreEqual() {
        assertThat(InvokedSkillRecord.of("commit", null)).isEqualTo(InvokedSkillRecord.of("commit", ""));
    }

    @Test
    void toString_NoArgsHasNoArgsSegment() {
        assertThat(InvokedSkillRecord.of("commit").toString()).contains("commit").doesNotContain("args=");
    }

    @Test
    void toString_WithArgsContainsArgsSegment() {
        assertThat(InvokedSkillRecord.of("commit", "--scope=feat").toString()).contains("commit")
                .contains("args=\"--scope=feat\"");
    }

    @Test
    void getArgsOptional_EmptyWhenArgsBlankOrAbsent() {
        assertThat(InvokedSkillRecord.of("commit").getArgsOptional()).isEmpty();
        assertThat(InvokedSkillRecord.of("commit", "").getArgsOptional()).isEmpty();
        assertThat(InvokedSkillRecord.of("commit", "x").getArgsOptional()).isEqualTo(Optional.of("x"));
    }
}
