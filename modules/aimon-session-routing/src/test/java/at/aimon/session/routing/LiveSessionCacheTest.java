package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.session.routing.fixture.TestLiveSession;

/**
 * WS-02 design §12: cache eviction scenarios.
 */
@DisplayName("LiveSessionCache TTL + LRU eviction")
class LiveSessionCacheTest {

    @Test
    @DisplayName("WS-02-B3: idle TTL evicts entries and closes the underlying session")
    void idleTtlEvictsAndCloses() {
        // Expiry is variable rather than expireAfterAccess, but an unpinned entry behaves exactly like it; we drive
        // virtual time via a ticker so the test is deterministic.
        final ManualTicker ticker = new ManualTicker();
        final TestLiveSession[] opened = new TestLiveSession[1];
        final LiveSessionOpener opener = (id, agentRuntimeId, options, openAttributes) -> {
            opened[0] = new TestLiveSession(id);
            return opened[0];
        };
        final LiveSessionCache cache = new LiveSessionCache(opener, Duration.ofMinutes(5), 100, ticker);

        final SessionId id = SessionId.of("c-3");
        final LiveSession session = cache
                .ensureOpen(id, AgentRuntimeId.fromName("alpha"), LiveSessionOptions.defaults()).getSession();
        assertThat(cache.peek(id)).isPresent();

        // Advance past idleTtl and trigger lazy maintenance.
        ticker.advance(Duration.ofMinutes(6));
        cache.sweep();

        assertThat(cache.peek(id)).as("idle entry must be swept").isEmpty();
        assertThat(((TestLiveSession) session).isClosed()).as("evicted session must be closed").isTrue();
        assertThat(opened[0]).isSameAs(session);
    }

    @Test
    @DisplayName("WS-02-B4: maxCachedSessions enforces LRU eviction and closes the loser")
    void maxEntriesEvictsLeastRecent() {
        final TestLiveSession[] sessions = new TestLiveSession[2];
        final LiveSessionOpener opener = (id, agentRuntimeId, options, openAttributes) -> {
            final TestLiveSession s = new TestLiveSession(id);
            if (id.value().equals("c-a")) {
                sessions[0] = s;
            } else {
                sessions[1] = s;
            }
            return s;
        };
        final LiveSessionCache cache = new LiveSessionCache(opener, Duration.ofMinutes(10), 1);

        final SessionId a = SessionId.of("c-a");
        final SessionId b = SessionId.of("c-b");
        final AgentRuntimeId ctx = AgentRuntimeId.fromName("alpha");

        cache.ensureOpen(a, ctx, LiveSessionOptions.defaults());
        cache.ensureOpen(b, ctx, LiveSessionOptions.defaults());
        cache.sweep();

        // With maxEntries=1 the older entry must have been evicted by Caffeine's size cap.
        assertThat(cache.peek(b)).as("most recent insert survives").isPresent();
        assertThat(cache.peek(a)).as("older entry must be evicted by maximumSize cap").isEmpty();
        assertThat(sessions[0].isClosed()).as("evicted session must be closed").isTrue();
    }

    @Test
    @DisplayName("a pinned entry is never idle, and its TTL starts counting at the unpin")
    void pinnedEntryIsNeverIdle() {
        final ManualTicker ticker = new ManualTicker();
        final LiveSessionCache cache = new LiveSessionCache(openerRecording(new TestLiveSession[1]),
                Duration.ofMinutes(5), 100, ticker);

        final SessionId id = SessionId.of("c-pin-1");
        final LiveSessionCache.SessionEntry entry = cache.acquire(id, AgentRuntimeId.fromName("alpha"),
                LiveSessionOptions.defaults(), OpenAttributes.empty());
        final TestLiveSession session = (TestLiveSession) entry.getSession();

        // A read of a pinned entry must not put the ordinary TTL back on it: the manager peeks at cached sessions from
        // its own bookkeeping, and a sweep five minutes after one of those peeks would expire the entry mid-turn.
        ticker.advance(Duration.ofMinutes(4));
        assertThat(cache.peek(id)).isPresent();
        ticker.advance(Duration.ofMinutes(6));
        cache.sweep();

        // Deferring the close would not be enough here: an expired entry leaves the map at once, so the session's
        // next submission would miss the cache and open a second session while this one is still running its turn.
        assertThat(cache.peek(id)).as("a pinned session is not idle, however long its turn takes").isPresent();
        assertThat(session.isClosed()).isFalse();

        // Ten minutes of pinned time cost the entry nothing — the TTL runs from the unpin. (Each peek below is itself
        // an access, so it too restarts the five minutes.)
        entry.unpin();
        ticker.advance(Duration.ofMinutes(4));
        cache.sweep();
        assertThat(cache.peek(id)).as("the idle TTL runs from the unpin, not from the acquire").isPresent();

        ticker.advance(Duration.ofMinutes(6));
        cache.sweep();
        assertThat(cache.peek(id)).as("and it does expire once the turn is over").isEmpty();
        assertThat(session.isClosed()).as("expiry closes the session it is reclaiming").isTrue();
    }

    @Test
    @DisplayName("an explicit eviction removes a pinned entry from the cache but defers the close until unpin")
    void pinnedEntryIsEvictedButNotClosedUntilUnpinned() {
        final LiveSessionCache cache = new LiveSessionCache(openerRecording(new TestLiveSession[1]),
                Duration.ofMinutes(5), 100);

        final SessionId id = SessionId.of("c-pin-4");
        final LiveSessionCache.SessionEntry entry = cache.acquire(id, AgentRuntimeId.fromName("alpha"),
                LiveSessionOptions.defaults(), OpenAttributes.empty());
        final TestLiveSession session = (TestLiveSession) entry.getSession();

        cache.evict(id);

        // Unlike the idle TTL, a removal somebody asked for is not negotiable — a yield or a delete has to take the
        // entry out now. The pin only defers the close, so the running turn still writes to a live session.
        assertThat(cache.peek(id)).as("an explicit eviction removes the entry even while pinned").isEmpty();
        assertThat(session.isClosed()).as("close must be deferred while the entry is pinned").isFalse();

        entry.unpin();
        assertThat(session.isClosed()).as("the last unpin performs the deferred close").isTrue();
    }

    @Test
    @DisplayName("acquire after an eviction opens a fresh session rather than handing back the closing one")
    void acquireAfterEvictionOpensAFreshSession() {
        final LiveSessionCache cache = new LiveSessionCache(openerRecording(new TestLiveSession[1]),
                Duration.ofMinutes(5), 100);

        final SessionId id = SessionId.of("c-pin-2");
        final AgentRuntimeId ctx = AgentRuntimeId.fromName("alpha");
        final LiveSessionCache.SessionEntry first = cache.acquire(id, ctx, LiveSessionOptions.defaults(),
                OpenAttributes.empty());
        cache.evict(id);

        final LiveSessionCache.SessionEntry second = cache.acquire(id, ctx, LiveSessionOptions.defaults(),
                OpenAttributes.empty());
        assertThat(second).as("the evicted entry must not be reused").isNotSameAs(first);
        assertThat(((TestLiveSession) first.getSession()).isClosed()).as("the first session is still pinned").isFalse();

        first.unpin();
        assertThat(((TestLiveSession) first.getSession()).isClosed()).isTrue();
        assertThat(((TestLiveSession) second.getSession()).isClosed()).as("the live session must stay open").isFalse();
        second.unpin();
    }

    @Test
    @DisplayName("unpinning an entry that was never evicted leaves the session open and cached")
    void unpinWithoutEvictionKeepsTheSessionOpen() {
        final LiveSessionCache cache = new LiveSessionCache(openerRecording(new TestLiveSession[1]),
                Duration.ofMinutes(5), 100);

        final SessionId id = SessionId.of("c-pin-3");
        final LiveSessionCache.SessionEntry entry = cache.acquire(id, AgentRuntimeId.fromName("alpha"),
                LiveSessionOptions.defaults(), OpenAttributes.empty());
        entry.unpin();

        assertThat(cache.peek(id)).as("unpin must not evict").isPresent();
        assertThat(((TestLiveSession) entry.getSession()).isClosed()).isFalse();
    }

    private static LiveSessionOpener openerRecording(TestLiveSession[] sink) {
        return (id, agentRuntimeId, options, openAttributes) -> {
            sink[0] = new TestLiveSession(id);
            return sink[0];
        };
    }

    /** Minimal Ticker wrapper used to bookkeep virtual time within a test. */
    private static final class ManualTicker implements Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration d) {
            nanos += TimeUnit.NANOSECONDS.convert(d.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
