package at.aimon.core.skill.hook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DenyActionTest {

    @Test
    void getReason_returnsConfiguredReason() {
        DenyAction action = new DenyAction("nope");

        assertThat(action.getReason()).isEqualTo("nope");
    }

    @Test
    void constructor_nullReason_throws() {
        assertThatThrownBy(() -> new DenyAction(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_blankReason_throws() {
        assertThatThrownBy(() -> new DenyAction("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void equalsAndHashCode_sameReason_equal() {
        assertThat(new DenyAction("x")).isEqualTo(new DenyAction("x")).hasSameHashCodeAs(new DenyAction("x"));
        assertThat(new DenyAction("x")).isNotEqualTo(new DenyAction("y"));
    }

    @Test
    void toString_includesReason() {
        assertThat(new DenyAction("blocked").toString()).contains("blocked");
    }

    @Test
    void implementsHookActionMarker() {
        assertThat((HookAction) new DenyAction("x")).isInstanceOf(DenyAction.class);
    }
}
