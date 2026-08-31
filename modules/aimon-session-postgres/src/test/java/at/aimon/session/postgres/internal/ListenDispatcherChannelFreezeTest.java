package at.aimon.session.postgres.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code LISTEN} / {@code pg_notify} channel name shared by both ends of the signal doorbell.
 *
 * <p>
 * This is the one frozen identifier in the module that {@code PostgresSchemaFreezeTest} cannot reach: the channel is
 * not in the DDL, so there is no schema text to compare against. Both ends read
 * {@link ListenDispatcher#CHANNEL}, which is exactly what makes it invisible — a rename moves the listener and the
 * notifier together and every tier stays green, the Docker-tagged integration tests included, because a single-version
 * test fleet always agrees with itself.
 *
 * <p>
 * The expectation is written out as a literal rather than routed through the constant. A constant-based assertion
 * follows the rename and can never fail, which is worse than having no test at all.
 */
@DisplayName("Postgres signal doorbell — frozen channel name")
class ListenDispatcherChannelFreezeTest {

    @Test
    @DisplayName("the doorbell channel still has the name deployed nodes are listening on")
    void channelNameIsFrozen() {
        // Renaming this breaks a rolling deploy and nothing else: an un-upgraded node keeps LISTENing on the old
        // channel while an upgraded one NOTIFYs on the new. No signal is lost — the rows live in conversation_signal
        // independently of NOTIFY delivery — but cross-node wakeups silently fall back to the self-poll backstop and
        // arrive seconds late instead of immediately, which reads as latency rather than as a bug.
        assertThat(ListenDispatcher.CHANNEL).isEqualTo("conversation_signal_doorbell");
    }
}
