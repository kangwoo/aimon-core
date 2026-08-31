package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.LiveSessionStatus.Phase;
import at.aimon.core.agent.session.SessionId;
import at.aimon.session.routing.internal.StatusProjection.RemoteStatus;

/**
 * Ordering and lifecycle contract for {@link StatusProjection}: the bus guarantees no ordering, so the projection must
 * keep the freshest snapshot per session while still letting holder hand-offs take effect.
 */
@DisplayName("StatusProjection ordering and lifecycle")
class StatusProjectionTest {

    private static final SessionId CONV = SessionId.of("conv-proj-1");

    @Test
    @DisplayName("a folded snapshot is returned by lookup")
    void appliesAndLooksUp() {
        final StatusProjection projection = new StatusProjection();
        projection.apply(CONV, status(Phase.RUNNING), "node-A", Instant.ofEpochMilli(1000), 1);

        final RemoteStatus result = projection.lookup(CONV).orElseThrow();
        assertThat(result.status().getPhase()).isEqualTo(Phase.RUNNING);
        assertThat(result.originNodeId()).isEqualTo("node-A");
        assertThat(result.observedAt()).isEqualTo(Instant.ofEpochMilli(1000));
        assertThat(result.seq()).isEqualTo(1);
    }

    @Test
    @DisplayName("a newer seq from the same origin replaces the previous snapshot")
    void newerSeqFromSameOriginWins() {
        final StatusProjection projection = new StatusProjection();
        projection.apply(CONV, status(Phase.RUNNING), "node-A", Instant.ofEpochMilli(1000), 1);
        projection.apply(CONV, status(Phase.IDLE), "node-A", Instant.ofEpochMilli(2000), 2);

        assertThat(projection.lookup(CONV).orElseThrow().status().getPhase()).isEqualTo(Phase.IDLE);
    }

    @Test
    @DisplayName("an out-of-order (older seq) snapshot from the same origin is dropped")
    void olderSeqFromSameOriginIsDropped() {
        final StatusProjection projection = new StatusProjection();
        projection.apply(CONV, status(Phase.IDLE), "node-A", Instant.ofEpochMilli(2000), 5);
        projection.apply(CONV, status(Phase.RUNNING), "node-A", Instant.ofEpochMilli(1000), 3);

        final RemoteStatus result = projection.lookup(CONV).orElseThrow();
        assertThat(result.status().getPhase()).isEqualTo(Phase.IDLE);
        assertThat(result.seq()).isEqualTo(5);
    }

    @Test
    @DisplayName("a snapshot from a different origin (hand-off) always wins, even with a lower seq")
    void handoffFromDifferentOriginAlwaysWins() {
        final StatusProjection projection = new StatusProjection();
        projection.apply(CONV, status(Phase.RUNNING), "node-A", Instant.ofEpochMilli(2000), 9);
        // New holder node-B starts its own seq counter low; it must still replace node-A's stale snapshot.
        projection.apply(CONV, status(Phase.IDLE), "node-B", Instant.ofEpochMilli(2500), 1);

        final RemoteStatus result = projection.lookup(CONV).orElseThrow();
        assertThat(result.originNodeId()).isEqualTo("node-B");
        assertThat(result.status().getPhase()).isEqualTo(Phase.IDLE);
    }

    @Test
    @DisplayName("remove drops the projected entry")
    void removeClears() {
        final StatusProjection projection = new StatusProjection();
        projection.apply(CONV, status(Phase.RUNNING), "node-A", Instant.ofEpochMilli(1000), 1);
        projection.remove(CONV);

        assertThat(projection.lookup(CONV)).isEmpty();
    }

    private static LiveSessionStatus status(Phase phase) {
        return LiveSessionStatus.builder().sessionId(CONV).phase(phase).build();
    }
}
