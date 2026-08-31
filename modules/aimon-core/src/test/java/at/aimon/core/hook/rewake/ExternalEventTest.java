package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExternalEventTest {

    @Test
    void buildsWithRequiredFieldsOnly() {
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("ticket-1").build();

        assertThat(event.getEventType()).isEqualTo("webhook");
        assertThat(event.getEventKey()).isEqualTo("ticket-1");
        assertThat(event.getPayload()).isEmpty();
        assertThat(event.getIdempotencyKey()).isEmpty();
        assertThat(event.getSourceTransport()).isEmpty();
        assertThat(event.getReceivedAt()).isNotNull();
    }

    @Test
    void carriesOptionalMetadata() {
        final Instant arrival = Instant.parse("2026-05-09T10:15:30Z");
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("ticket-1")
                .payload("status", "approved").payload(Map.of("approver", "alice")).idempotencyKey("idem-42")
                .sourceTransport("webhook").receivedAt(arrival).build();

        assertThat(event.getPayload()).containsEntry("status", "approved").containsEntry("approver", "alice");
        assertThat(event.getIdempotencyKey()).contains("idem-42");
        assertThat(event.getSourceTransport()).contains("webhook");
        assertThat(event.getReceivedAt()).isEqualTo(arrival);
    }

    @Test
    void payloadIsImmutableSnapshot() {
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("k")
                .payload("status", "approved").build();

        assertThatThrownBy(() -> event.getPayload().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void receivedAtDefaultsToNowAtBuildTime() {
        final Instant before = Instant.now();
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("k").build();
        final Instant after = Instant.now();

        assertThat(event.getReceivedAt()).isBetween(before, after);
    }

    @Test
    void rejectsNullEventType() {
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().eventType(null));
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().eventKey("k").build());
    }

    @Test
    void rejectsNullEventKey() {
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().eventKey(null));
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().eventType("t").build());
    }

    @Test
    void rejectsBlankEventType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalEvent.builder().eventType("   ").eventKey("k").build());
    }

    @Test
    void rejectsBlankEventKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalEvent.builder().eventType("t").eventKey("").build());
    }

    @Test
    void rejectsNullPayloadKeyOrValue() {
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().payload(null, "v"));
        assertThatNullPointerException().isThrownBy(() -> ExternalEvent.builder().payload("k", null));
    }

    @Test
    void payloadEntriesOverwriteByKey() {
        final ExternalEvent event = ExternalEvent.builder().eventType("t").eventKey("k").payload("a", "1")
                .payload("a", "2").build();

        assertThat(event.getPayload()).containsExactly(Map.entry("a", "2"));
    }

    @Test
    void equalsAndHashCodeCoverAllFields() {
        final Instant t = Instant.parse("2026-05-09T10:00:00Z");
        final ExternalEvent a = ExternalEvent.builder().eventType("webhook").eventKey("k").payload("p", "v")
                .idempotencyKey("idem").sourceTransport("webhook").receivedAt(t).build();
        final ExternalEvent b = ExternalEvent.builder().eventType("webhook").eventKey("k").payload("p", "v")
                .idempotencyKey("idem").sourceTransport("webhook").receivedAt(t).build();
        final ExternalEvent diffKey = ExternalEvent.builder().eventType("webhook").eventKey("k2").payload("p", "v")
                .idempotencyKey("idem").sourceTransport("webhook").receivedAt(t).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(diffKey);
    }

    @Test
    void toStringIncludesKeyFields() {
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("ticket-1").build();
        assertThat(event.toString()).contains("webhook").contains("ticket-1");
    }
}
