package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.LiveSessionStatus.Phase;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Factory/provenance contract for {@link ClusterSessionStatus} plus the source-compatible
 * {@link SessionRouter#status(SessionId)} default.
 */
@DisplayName("ClusterSessionStatus")
class ClusterSessionStatusTest {

    private static final SessionId CONV = SessionId.of("conv-cluster-1");

    @Test
    @DisplayName("localHolder carries the live snapshot, the node id, and no staleness stamp")
    void localHolderProvenance() {
        final LiveSessionStatus snapshot = idleStatus();

        final ClusterSessionStatus result = ClusterSessionStatus.localHolder(snapshot, "node-A");

        assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.LOCAL_HOLDER);
        assertThat(result.getSessionId()).isEqualTo(CONV);
        assertThat(result.getStatus()).containsSame(snapshot);
        assertThat(result.getOriginNodeId()).contains("node-A");
        assertThat(result.getObservedAt()).isEmpty();
        assertThat(result.isKnown()).isTrue();
    }

    @Test
    @DisplayName("remote carries the projected snapshot, the origin node, and the observedAt stamp")
    void remoteProvenance() {
        final LiveSessionStatus snapshot = idleStatus();
        final Instant observedAt = Instant.ofEpochMilli(1_700_000_000_000L);

        final ClusterSessionStatus result = ClusterSessionStatus.remote(snapshot, "node-B", observedAt);

        assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.REMOTE_PROJECTION);
        assertThat(result.getSessionId()).isEqualTo(CONV);
        assertThat(result.getStatus()).containsSame(snapshot);
        assertThat(result.getOriginNodeId()).contains("node-B");
        assertThat(result.getObservedAt()).contains(observedAt);
        assertThat(result.isKnown()).isTrue();
    }

    @Test
    @DisplayName("unknown carries the conversation id and no snapshot")
    void unknownProvenance() {
        final ClusterSessionStatus result = ClusterSessionStatus.unknown(CONV);

        assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.UNKNOWN);
        assertThat(result.getSessionId()).isEqualTo(CONV);
        assertThat(result.getStatus()).isEmpty();
        assertThat(result.getOriginNodeId()).isEmpty();
        assertThat(result.getObservedAt()).isEmpty();
        assertThat(result.isKnown()).isFalse();
    }

    @Test
    @DisplayName("SessionRouter.status default returns UNKNOWN so existing implementations stay source-compatible")
    void managerDefaultStatusReturnsUnknown() {
        final SessionRouter manager = new SessionRouter() {
            @Override
            public SubmitDisposition submit(SubmitRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void interrupt(SessionId sessionId, InterruptReason reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason) {
                // Abstract on purpose rather than a default that widens to the unaddressed form: an implementation that
                // forgot turn addressing would otherwise silently cancel a turn nobody named.
                throw new UnsupportedOperationException();
            }

            @Override
            public void releaseSession(SessionId sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // no-op
            }
        };

        final ClusterSessionStatus result = manager.status(CONV);

        assertThat(result.getSource()).isEqualTo(ClusterSessionStatus.Source.UNKNOWN);
        assertThat(result.getSessionId()).isEqualTo(CONV);
        assertThat(result.getStatus()).isEmpty();
    }

    private static LiveSessionStatus idleStatus() {
        return LiveSessionStatus.builder().sessionId(CONV).phase(Phase.IDLE).build();
    }
}
