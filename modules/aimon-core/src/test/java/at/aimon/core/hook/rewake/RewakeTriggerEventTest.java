package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class RewakeTriggerEventTest {

    @Test
    void exposesEventTypeAndKey() {
        final RewakeTriggerEvent trigger = new RewakeTriggerEvent("webhook", "ticket-42");

        assertThat(trigger.getEventType()).isEqualTo("webhook");
        assertThat(trigger.getEventKey()).isEqualTo("ticket-42");
    }

    @Test
    void rejectsNullArgs() {
        assertThatNullPointerException().isThrownBy(() -> new RewakeTriggerEvent(null, "k"));
        assertThatNullPointerException().isThrownBy(() -> new RewakeTriggerEvent("t", null));
    }

    @Test
    void rejectsBlankArgs() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RewakeTriggerEvent("", "k"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RewakeTriggerEvent("t", "   "));
    }

    @Test
    void matchesCaseSensitively() {
        final RewakeTriggerEvent a = new RewakeTriggerEvent("Webhook", "Ticket-1");
        final RewakeTriggerEvent b = new RewakeTriggerEvent("webhook", "ticket-1");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equalsAndHashCodeIncludeBothFields() {
        final RewakeTriggerEvent a = new RewakeTriggerEvent("webhook", "ticket-1");
        final RewakeTriggerEvent b = new RewakeTriggerEvent("webhook", "ticket-1");
        final RewakeTriggerEvent diffType = new RewakeTriggerEvent("mcp.notification", "ticket-1");
        final RewakeTriggerEvent diffKey = new RewakeTriggerEvent("webhook", "ticket-2");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(diffType);
        assertThat(a).isNotEqualTo(diffKey);
    }

    @Test
    void toStringIncludesBothFields() {
        assertThat(new RewakeTriggerEvent("webhook", "k").toString()).contains("webhook").contains("k");
    }
}
