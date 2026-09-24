package at.aimon.bootstrap.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.routing.DeploymentMode;

/**
 * What a {@link SessionSpec} refuses to be built as.
 *
 * <p>
 * All of it is about {@link DeploymentMode#DISTRIBUTED}. Single-node has nothing to check — every field it
 * needs has an in-memory default that is correct for one process — whereas distributed mode is a set of five
 * borrowed collaborators of which any subset can be absent, and four of the five failures are silent at
 * startup: the process starts, serves traffic, and is wrong only once a session moves.
 *
 * <p>
 * Four of the five are also checked by {@code SessionRouterBuilder}, which is the layer that will actually use
 * them. The fifth is not, and cannot be: by the time the router sees a record store there is always one, because
 * {@code AimonStackBuilder} substitutes the in-memory implementation for an unset spec. That substitution is
 * right for single-node and ruinous here, so the check has to live at the layer that still knows the difference
 * between "unset" and "in-memory" — this one.
 */
class SessionSpecTest {

    private static final SessionRecordStore DURABLE_STORE = mock(SessionRecordStore.class);

    /** Everything distributed mode needs, so each case can remove exactly one thing. */
    private static SessionSpec.Builder complete() {
        return SessionSpec.builder().mode(DeploymentMode.DISTRIBUTED).nodeId("pod-a").recordStore(DURABLE_STORE)
                .leaseStore(mock(SessionLeaseStore.class)).signalBus(mock(SessionSignalBus.class))
                .inbox(mock(SessionInbox.class)).idempotencyStore(mock(IdempotencyStore.class));
    }

    @Test
    @DisplayName("the default spec is single node and carries nothing distributed")
    void defaultsAreSingleNode() {
        final SessionSpec spec = SessionSpec.defaults();
        assertThat(spec.getMode()).isEqualTo(DeploymentMode.SINGLE_NODE);
        assertThat(spec.getNodeId()).isEmpty();
        assertThat(spec.getLeaseStore()).isEmpty();
        assertThat(spec.getSignalBus()).isEmpty();
        assertThat(spec.getInbox()).isEmpty();
        assertThat(spec.getIdempotencyStore()).isEmpty();
    }

    @Test
    @DisplayName("a complete distributed spec builds and hands every collaborator on")
    void completeDistributedSpecBuilds() {
        final SessionSpec spec = complete().build();
        assertThat(spec.getMode()).isEqualTo(DeploymentMode.DISTRIBUTED);
        assertThat(spec.getNodeId()).contains("pod-a");
        assertThat(spec.getRecordStore()).contains(DURABLE_STORE);
        assertThat(spec.getLeaseStore()).isPresent();
        assertThat(spec.getSignalBus()).isPresent();
        assertThat(spec.getInbox()).isPresent();
        assertThat(spec.getIdempotencyStore()).isPresent();
    }

    @Test
    @DisplayName("distributed mode with nothing set names all five at once")
    void distributedWithNothingSetNamesAllFive() {
        // One at a time would be five restarts to learn one lesson, and the fifth is the one nobody expects.
        assertThatThrownBy(() -> SessionSpec.builder().mode(DeploymentMode.DISTRIBUTED).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId(")
                .hasMessageContaining("leaseStore(").hasMessageContaining("signalBus(").hasMessageContaining("inbox(")
                .hasMessageContaining("idempotencyStore(").hasMessageContaining("recordStore(");
    }

    @Test
    @DisplayName("a blank node id is missing, not set")
    void blankNodeIdIsMissing() {
        assertThatThrownBy(() -> complete().nodeId("   ").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId(");
    }

    @Test
    @DisplayName("each missing collaborator is named on its own")
    void eachMissingCollaboratorIsNamed() {
        assertThatThrownBy(() -> complete().leaseStore(null).build()).hasMessageContaining("leaseStore(")
                .hasMessageNotContaining("signalBus(");
        assertThatThrownBy(() -> complete().signalBus(null).build()).hasMessageContaining("signalBus(")
                .hasMessageNotContaining("inbox(");
        assertThatThrownBy(() -> complete().inbox(null).build()).hasMessageContaining("inbox(")
                .hasMessageNotContaining("idempotencyStore(");
        assertThatThrownBy(() -> complete().idempotencyStore(null).build()).hasMessageContaining("idempotencyStore(")
                .hasMessageNotContaining("leaseStore(");
    }

    @Test
    @DisplayName("an unset record store is refused — the stack would substitute the in-memory one")
    void unsetRecordStoreIsRefused() {
        assertThatThrownBy(() -> complete().recordStore(null).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordStore(").hasMessageContaining("never seen the conversation");
    }

    @Test
    @DisplayName("an explicit in-memory record store is refused for the same reason, said out loud")
    void inMemoryRecordStoreIsRefused() {
        // The one case the router cannot catch: this store is non-null, correctly typed, and works perfectly on
        // one node. Nothing downstream is in a position to notice that the transcripts never leave this heap.
        assertThatThrownBy(() -> complete().recordStore(new InMemorySessionRecordStore()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(InMemorySessionRecordStore.class.getSimpleName())
                .hasMessageContaining("worse than single node");
    }

    @Test
    @DisplayName("single node accepts the same collaborators without requiring any of them")
    void singleNodeAcceptsButDoesNotRequire() {
        // An application that publishes a durable lease store has asked for its leases to be visible outside
        // this process. Dropping them because the mode is single-node would answer a question it did not ask —
        // and would make turning the mode on a different configuration than turning it off and back.
        final SessionLeaseStore leaseStore = mock(SessionLeaseStore.class);
        final SessionSpec spec = SessionSpec.builder().leaseStore(leaseStore).build();
        assertThat(spec.getMode()).isEqualTo(DeploymentMode.SINGLE_NODE);
        assertThat(spec.getLeaseStore()).contains(leaseStore);
        assertThat(spec.getSignalBus()).isEmpty();
    }

    @Test
    @DisplayName("the segment sweep is off by default and on with an interval")
    void segmentSweepDefaultsOff() {
        assertThat(SessionSpec.defaults().getSegmentSweepInterval()).isEmpty();
        final SessionSpec on = SessionSpec.builder().segmentSweepInterval(Duration.ofHours(1))
                .segmentSweepGrace(Duration.ofHours(48)).build();
        assertThat(on.getSegmentSweepInterval()).contains(Duration.ofHours(1));
        assertThat(on.getSegmentSweepGrace()).contains(Duration.ofHours(48));
        assertThat(SessionSpec.builder().recordStore(DURABLE_STORE).segmentStore(new InMemorySessionLogSegmentStore())
                .segmentSweepInterval(Duration.ofHours(1)).build().getSegmentSweepInterval()).isPresent();
    }

    @Test
    @DisplayName("a sweep that could never run is refused")
    void segmentSweepThatCannotRunIsRefused() {
        assertThatThrownBy(() -> SessionSpec.builder().segmentSweepInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("segmentSweepInterval");
        assertThatThrownBy(() -> SessionSpec.builder().segmentSweepInterval(Duration.ofHours(1))
                .segmentSweepGrace(Duration.ofSeconds(-1)).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentSweepGrace");
        assertThatThrownBy(() -> SessionSpec.builder().segmentSweepGrace(Duration.ofHours(1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never runs");
        assertThatThrownBy(() -> SessionSpec.builder().recordStore(DURABLE_STORE)
                .segmentSweepInterval(Duration.ofHours(1)).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to sweep");
    }
}
