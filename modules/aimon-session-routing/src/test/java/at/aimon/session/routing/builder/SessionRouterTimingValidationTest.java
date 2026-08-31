package at.aimon.session.routing.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.internal.SessionRouterConfig;

/**
 * The lease-timing invariant {@code lockExtendInterval < lockLease}, and the separation of the {@code STATUS} heartbeat
 * cadence from the renewal tick.
 *
 * <p>
 * The invariant was documented on {@code lockLease(...)} from the day the builder existed and enforced nowhere:
 * {@code build()} only null-checked, and {@code SessionRouterConfig.build()} validated nothing at all. A
 * deployment that inverted the two numbers got no complaint at startup and then lost every session on a timer —
 * the renewer's first tick fires after the lease it exists to extend has already expired, so a peer is free to start a
 * second turn on a session this node believes it holds.
 *
 * <p>
 * The heartbeat tests are the other half: the cadence used to be read straight off {@code lockExtendInterval}, so the
 * two decisions could not be made separately. A heartbeat longer than the lease is a perfectly sensible configuration
 * (stale-ish remote status, healthy lease) and must build; the same number in {@code lockExtendInterval} must not.
 */
@DisplayName("SessionRouterBuilder lease timing validation")
class SessionRouterTimingValidationTest {

    private static final LiveSessionOpener OPENER = (id, ref, opts, attrs) -> {
        throw new AssertionError("opener invoked unexpectedly");
    };

    private static SessionRouterBuilder base() {
        return SessionRouter.builder().sessionOpener(OPENER).sessionRecordStore(new InMemorySessionRecordStore());
    }

    @Test
    @DisplayName("build() rejects a renewal interval equal to the lease")
    void rejectsIntervalEqualToLease() {
        final SessionRouterBuilder builder = base().lockLease(Duration.ofSeconds(30))
                .lockExtendInterval(Duration.ofSeconds(30));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockExtendInterval").hasMessageContaining("lockLease");
    }

    @Test
    @DisplayName("build() rejects a renewal interval longer than the lease")
    void rejectsIntervalLongerThanLease() {
        final SessionRouterBuilder builder = base().lockLease(Duration.ofSeconds(10))
                .lockExtendInterval(Duration.ofSeconds(30));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly less than");
    }

    @Test
    @DisplayName("build() rejects a non-positive lease or renewal interval")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> base().lockLease(Duration.ZERO).build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockLease must be positive");
        assertThatThrownBy(() -> base().lockLease(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lockLease must be positive");
        assertThatThrownBy(() -> base().lockExtendInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lockExtendInterval must be positive");
        assertThatThrownBy(() -> base().statusHeartbeatInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("statusHeartbeatInterval must be positive");
    }

    @Test
    @DisplayName("build() accepts the shipped 30s/10s pair — two missed ticks of headroom")
    void acceptsShippedDefaults() {
        assertThat(SessionRouterBuilder.DEFAULT_LOCK_LEASE)
                .isEqualTo(SessionRouterBuilder.DEFAULT_LOCK_EXTEND_INTERVAL.multipliedBy(3));

        final SessionRouter manager = base().build();
        try {
            assertThat(manager).isNotNull();
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("the internal config refuses the inversion too — tests cannot route around the invariant")
    void configItselfRejectsTheInversion() {
        // The fluent builder above delegates here, and so does every test harness. Asserting it at this level is what
        // makes the invariant a property of the type the manager consumes rather than of one convenience façade.
        final SessionRouterConfig.Builder config = SessionRouterConfig.builder()
                .store(new DefaultSessionStore(new InMemorySessionLeaseStore(), new InMemorySessionRecordStore()))
                .lockLease(Duration.ofSeconds(5)).lockExtendInterval(Duration.ofSeconds(5))
                .statusHeartbeatInterval(Duration.ofSeconds(5));

        assertThatThrownBy(config::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly less than");
    }

    @Test
    @DisplayName("statusHeartbeatInterval is not subject to the lease invariant — it is no longer the same knob")
    void heartbeatIsIndependentOfTheLease() {
        // The very configuration that proves the alias is gone: a heartbeat far longer than the lease. While the two
        // were one knob this was unreachable — asking for a lazy status cadence also asked for a lease that expires
        // before its first renewal.
        final SessionRouterBuilder builder = base().lockLease(Duration.ofSeconds(30))
                .lockExtendInterval(Duration.ofSeconds(10)).statusHeartbeatInterval(Duration.ofMinutes(2));

        assertThatCode(() -> {
            final SessionRouter manager = builder.build();
            manager.close();
        }).doesNotThrowAnyException();
    }
}
