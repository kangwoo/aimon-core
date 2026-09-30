package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import at.aimon.core.agent.session.SessionId;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.SlotHash;

/**
 * Pins the default Redis key prefixes, which name live keys and pub/sub channels in a running cluster.
 *
 * <p>
 * These sit one step further out than the frozen field names the codec tests guard. A
 * {@code SessionRecord -> Session} sweep cannot reach them — the literals carry the abbreviation {@code conv}, which
 * matches none of the swept identifiers. The exposure is what the sweep <em>leaves behind</em>: once the surrounding
 * types are {@code RedisSessionLock} and {@code RedisSessionInbox}, a prefix reading {@code aimon:conv} looks like an
 * oversight, and tidying it to {@code aimon:sess} orphans every deployed key exactly as thoroughly as renaming a field
 * would. For {@link RedisPubSubSignalBus} it is worse than orphaning: publishers and subscribers derive their channel
 * from the prefix, so a change splits a fleet without any error surfacing on either side.
 *
 * <p>
 * The prefixes are deliberately inconsistent with each other ({@code aimon:conv}, {@code aimon:inbox},
 * {@code aimon:conv:idem}) and that inconsistency is also frozen. Harmonizing it is a data migration, not a cleanup.
 *
 * <p>
 * Expectations are literals, never the constants they guard — an assertion routed through the constant moves with the
 * rename and can never fail.
 */
@DisplayName("Redis key prefixes — frozen")
class RedisKeyPrefixFreezeTest {

    @Test
    @DisplayName("the lock prefix still names the deployed lock keys")
    void lockPrefixIsFrozen() {
        assertThat(RedisSessionLeaseStore.DEFAULT_KEY_PREFIX).isEqualTo("aimon:conv");
    }

    @Test
    @DisplayName("the inbox prefix still names the deployed inbox streams")
    void inboxPrefixIsFrozen() {
        assertThat(RedisSessionInbox.DEFAULT_KEY_PREFIX).isEqualTo("aimon:inbox");
    }

    @Test
    @DisplayName("the idempotency prefix still names the deployed entries and their SCAN pattern")
    void idempotencyPrefixIsFrozen() {
        // This prefix is read twice: once to build a key, and once as the SCAN pattern prefix + ":*" behind
        // findStaleInFlight. Changing it therefore hides existing in-flight entries from reaping as well as from
        // lookup, so a duplicate submit is accepted and the stale entry is never cleaned up.
        assertThat(RedisIdempotencyStore.DEFAULT_KEY_PREFIX).isEqualTo("aimon:conv:idem");
    }

    @Test
    @DisplayName("the signal-bus prefix still names the deployed pub/sub channels")
    void signalBusPrefixIsFrozen() {
        // Channels are prefix + ":control:" / ":events:" + id. Publisher and subscriber both derive them from this
        // constant, so a mid-deploy change leaves the two halves talking past each other with nothing logged.
        assertThat(RedisPubSubSignalBus.DEFAULT_KEY_PREFIX).isEqualTo("aimon:conv");
    }

    @Test
    @DisplayName("the record prefix still names the deployed session-record hashes")
    void recordPrefixIsFrozen() {
        // The one prefix here spelled for the session, because it is the one that had no deployed keys when it was
        // written. That makes it the odd one out above, and therefore the likeliest to be "corrected" into line with
        // its neighbours by a later sweep — which would orphan every transcript in the cluster while every test kept
        // passing. Read twice, like the idempotency prefix: once per key, once as the SCAN pattern behind
        // listSessionIds and clear.
        assertThat(RedisSessionRecordStore.DEFAULT_KEY_PREFIX).isEqualTo("aimon:session:record");
    }

    @Test
    @DisplayName("the segment prefix still names the deployed session-log segment hashes")
    void segmentPrefixIsFrozen() {
        // Renaming it would leave every manifest pointing at segments under the old prefix: each sealed range would
        // read back as a gap and garbage collection would never find the old keys to delete.
        assertThat(RedisSessionLogSegmentStore.DEFAULT_KEY_PREFIX).isEqualTo("aimon:session:segment");
    }

    @Test
    @DisplayName("the segment key layout is hash-tagged by session and suffixed after it")
    void segmentKeyLayoutIsFrozen() {
        // Set before the first release, deliberately: the earlier <prefix>:<sid> / <prefix>:<sid>:created pair let
        // session "X:created" own session "X"'s creation hash. Changing the layout after a deployment orphans every
        // sealed range exactly as renaming the prefix would.
        final RedisSessionLogSegmentStore store = new RedisSessionLogSegmentStore(connection());
        assertThat(store.dataKey(SessionId.of("s-1"))).isEqualTo("aimon:session:segment:{s:s-1}:data");
        assertThat(store.createdKey(SessionId.of("s-1"))).isEqualTo("aimon:session:segment:{s:s-1}:created");
    }

    @Test
    @DisplayName("no session's segment key spells another session's")
    void segmentKeysDoNotCollideAcrossSessions() {
        final RedisSessionLogSegmentStore store = new RedisSessionLogSegmentStore(connection());
        final SessionId x = SessionId.of("X");
        final SessionId xCreated = SessionId.of("X:created");
        final SessionId xBrace = SessionId.of("X}:created");
        final SessionId leadingBrace = SessionId.of("}x");
        assertThat(List.of(store.dataKey(x), store.createdKey(x), store.dataKey(xCreated), store.createdKey(xCreated),
                store.dataKey(xBrace), store.createdKey(xBrace), store.dataKey(leadingBrace),
                store.createdKey(leadingBrace))).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("both keys of a session hash to one cluster slot, even when the id contains a brace")
    void segmentKeysShareOneSlot() {
        final RedisSessionLogSegmentStore store = new RedisSessionLogSegmentStore(connection());
        for (String id : new String[]{"s-1", "X:created", "a}b", "{nested}", "tenant:42/session", "}x", "}", "}}",
                "{}"}) {
            final SessionId session = SessionId.of(id);
            assertThat(SlotHash.getSlot(store.dataKey(session))).as(id)
                    .isEqualTo(SlotHash.getSlot(store.createdKey(session)));
        }
    }

    @Test
    @DisplayName("a prefix containing a brace is refused - it would pin every session to one slot")
    void braceInPrefixRefused() {
        assertThatThrownBy(() -> new RedisSessionLogSegmentStore(connection(), "tenant{a}:segment"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<String, String> connection() {
        return Mockito.mock(StatefulRedisConnection.class);
    }
}
