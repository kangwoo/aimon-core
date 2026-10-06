package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Unit tests for {@link ScheduledTaskInterruptCodec} — the round-trip, the frozen wire keys the round-trip cannot see,
 * and what an older node does with a reason only a newer one knows.
 */
@DisplayName("ScheduledTaskInterruptCodec — round-trip, frozen keys, and reasons from a newer node")
class ScheduledTaskInterruptCodecTest {

    private final ScheduledTaskInterruptCodec codec = new ScheduledTaskInterruptCodec();

    @ParameterizedTest
    @EnumSource(InterruptReason.class)
    @DisplayName("a request survives encode → decode for every reason")
    void roundTrip(InterruptReason reason) {
        final ScheduledTaskInterruptCodec.Decoded decoded = codec
                .decode(codec.encode(ScheduledTaskId.of("task-1"), reason, "node-A"));

        assertThat(decoded.getTaskId()).isEqualTo(ScheduledTaskId.of("task-1"));
        assertThat(decoded.getReason()).isEqualTo(reason);
        assertThat(decoded.getOriginNodeId()).isEqualTo("node-A");
    }

    @Test
    @DisplayName("the wire keys are spelled as the rest of the fleet reads them")
    void wireKeysAreFrozen() {
        // FROZEN WIRE FORMAT. Every node reads this collection, including nodes on the previous release, so the keys
        // are cross-node protocol. Spelled out rather than taken from DocumentKeys: a rename sweep carries the
        // constant and its references together, so a constant-based assertion can never fail.
        final Document doc = codec.encode(ScheduledTaskId.of("task-2"), InterruptReason.TASK_CANCELLED, "node-A");

        assertThat(doc.getString("taskId")).isEqualTo("task-2");
        assertThat(doc.getString("reason")).isEqualTo("TASK_CANCELLED");
        assertThat(doc.getString("originNodeId")).isEqualTo("node-A");
    }

    @Test
    @DisplayName("a request already published under those keys still decodes")
    void wireKeysAreFrozenOnDecode() {
        final Document published = new Document("taskId", "task-3").append("reason", "USER_SIGINT")
                .append("originNodeId", "node-B");

        final ScheduledTaskInterruptCodec.Decoded decoded = codec.decode(published);

        assertThat(decoded.getTaskId()).isEqualTo(ScheduledTaskId.of("task-3"));
        assertThat(decoded.getReason()).isEqualTo(InterruptReason.USER_SIGINT);
        assertThat(decoded.getOriginNodeId()).isEqualTo("node-B");
    }

    @Test
    @DisplayName("a reason this build does not know is still a stop request")
    void unknownReasonIsHonoured() {
        // The rolling-upgrade case: a newer node added a reason and published with it. Throwing here would discard
        // the request and leave the run going for a task somebody asked to stop.
        final Document fromNewerNode = new Document("taskId", "task-4").append("reason", "REASON_ADDED_NEXT_RELEASE")
                .append("originNodeId", "node-B");

        final ScheduledTaskInterruptCodec.Decoded decoded = codec.decode(fromNewerNode);

        assertThat(decoded.getTaskId()).isEqualTo(ScheduledTaskId.of("task-4"));
        assertThat(decoded.getReason()).isEqualTo(InterruptReason.TASK_CANCELLED);
    }

    @Test
    @DisplayName("a missing reason is read the same way")
    void missingReasonIsHonoured() {
        assertThat(codec.decode(new Document("taskId", "task-5")).getReason())
                .isEqualTo(InterruptReason.TASK_CANCELLED);
    }

    @Test
    @DisplayName("a request naming no task is rejected — there is nothing to stop")
    void missingTaskIdIsRejected() {
        assertThatThrownBy(() -> codec.decode(new Document("reason", "TASK_CANCELLED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taskId");
    }
}
