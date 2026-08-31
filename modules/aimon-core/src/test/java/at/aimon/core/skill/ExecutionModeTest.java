package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExecutionModeTest {

    @Test
    void parse_LowercaseInline() {
        assertThat(ExecutionMode.parse("inline")).isEqualTo(ExecutionMode.INLINE);
    }

    @Test
    void parse_UppercaseFork() {
        assertThat(ExecutionMode.parse("FORK")).isEqualTo(ExecutionMode.FORK);
    }

    @Test
    void parse_MixedCaseAndPaddingTolerated() {
        assertThat(ExecutionMode.parse("  Fork  ")).isEqualTo(ExecutionMode.FORK);
    }

    @Test
    void parse_NullThrows() {
        assertThatThrownBy(() -> ExecutionMode.parse(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Execution mode");
    }

    @Test
    void parse_BlankThrows() {
        assertThatThrownBy(() -> ExecutionMode.parse("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void parse_UnknownThrows() {
        assertThatThrownBy(() -> ExecutionMode.parse("hybrid")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hybrid").hasMessageContaining("inline").hasMessageContaining("fork");
    }
}
