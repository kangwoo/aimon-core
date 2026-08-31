package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.LiveSessionStatus.Phase;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.session.routing.ClusterSessionStatus;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Cluster-wide {@code status()} resolution on {@link DefaultSessionRouter}: a peer node's {@code STATUS} push is
 * folded into the local projection and surfaced as {@code REMOTE_PROJECTION}, self-broadcast is ignored, and an
 * {@code EVICT} clears the projection. Drives the receive side directly via the shared in-memory bus (the broadcast /
 * heartbeat emit side runs only inside a live turn loop and is exercised by the multi-node integration tests).
 */
@DisplayName("DefaultSessionRouter cluster status resolution")
class DefaultSessionRouterStatusTest {

    private static final SessionId CONV = SessionId.of("conv-status-mgr-1");

    @Test
    @DisplayName("a STATUS push from another node is surfaced as REMOTE_PROJECTION")
    void remoteStatusIsProjected() {
        try (TestManagerHarness harness = TestManagerHarness.builder().nodeId("node-B").build()) {
            harness.manager().events(CONV); // subscribe so the node receives signals for this conversation
            publishStatus(harness, "node-A", runningSnapshot(), 1L, Instant.ofEpochMilli(1700));

            final ClusterSessionStatus result = harness.manager().status(CONV);

            assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.REMOTE_PROJECTION);
            assertThat(result.getOriginNodeId()).contains("node-A");
            assertThat(result.getObservedAt()).contains(Instant.ofEpochMilli(1700));
            assertThat(result.getStatus()).isPresent();
            assertThat(result.getStatus().get().getPhase()).isEqualTo(Phase.RUNNING);
            assertThat(result.getStatus().get().getQueueDepth()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("status() is UNKNOWN when no node has been observed running the conversation")
    void unknownWhenNothingProjected() {
        try (TestManagerHarness harness = TestManagerHarness.builder().nodeId("node-B").build()) {
            final ClusterSessionStatus result = harness.manager().status(CONV);

            assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.UNKNOWN);
            assertThat(result.getStatus()).isEmpty();
            assertThat(result.getSessionId()).isEqualTo(CONV);
        }
    }

    @Test
    @DisplayName("a self-originated STATUS broadcast is not folded into the local projection")
    void selfBroadcastIsIgnored() {
        try (TestManagerHarness harness = TestManagerHarness.builder().nodeId("node-B").build()) {
            harness.manager().events(CONV);
            publishStatus(harness, "node-B", runningSnapshot(), 1L, Instant.ofEpochMilli(1700));

            assertThat(harness.manager().status(CONV).getSource()).isEqualTo(ClusterSessionStatus.Source.UNKNOWN);
        }
    }

    @Test
    @DisplayName("an EVICT from a peer clears the projected status")
    void evictClearsProjection() {
        try (TestManagerHarness harness = TestManagerHarness.builder().nodeId("node-B").build()) {
            harness.manager().events(CONV);
            publishStatus(harness, "node-A", runningSnapshot(), 1L, Instant.ofEpochMilli(1700));
            assertThat(harness.manager().status(CONV).getSource())
                    .isEqualTo(ClusterSessionStatus.Source.REMOTE_PROJECTION);

            harness.signalBus().publish(SessionSignal.builder().sessionId(CONV).kind(SessionSignal.SignalKind.EVICT)
                    .originNodeId("node-A").payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());

            assertThat(harness.manager().status(CONV).getSource()).isEqualTo(ClusterSessionStatus.Source.UNKNOWN);
        }
    }

    private static void publishStatus(TestManagerHarness harness, String originNodeId, LiveSessionStatus snapshot,
            long seq, Instant observedAt) {
        harness.signalBus()
                .publish(SessionSignal.builder().sessionId(CONV).kind(SessionSignal.SignalKind.STATUS)
                        .originNodeId(originNodeId).payload(StatusSnapshotPayload.toPayload(snapshot, seq, observedAt))
                        .build());
    }

    private static LiveSessionStatus runningSnapshot() {
        return LiveSessionStatus.builder().sessionId(CONV).phase(Phase.RUNNING).interruptible(true).queueDepth(2)
                .build();
    }
}
