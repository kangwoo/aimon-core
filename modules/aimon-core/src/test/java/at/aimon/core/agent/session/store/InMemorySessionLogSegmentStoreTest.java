package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

/**
 * The in-memory store's concurrency; its contract is run in {@code aimon-session-testkit}.
 */
@DisplayName("InMemorySessionLogSegmentStore")
class InMemorySessionLogSegmentStoreTest {

    @Test
    @DisplayName("a put racing the delete of the session's last segment is never lost")
    void putRacingLastDeleteIsKept() throws Exception {
        // Deleting the last segment detaches the session's inner map. A put that had already fetched that map and
        // wrote into it afterwards would vanish — the manifest naming it would then read as a gap.
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2_000; i++) {
                final InMemorySessionLogSegmentStore store = new InMemorySessionLogSegmentStore();
                final SessionId session = SessionId.of("race-" + i);
                final SessionLogSegment first = segment(session);
                final SessionLogSegment second = segment(session);
                store.put(first);

                final CountDownLatch start = new CountDownLatch(1);
                final Future<?> deleting = pool.submit(() -> {
                    await(start);
                    store.delete(session, first.getId());
                });
                final Future<?> putting = pool.submit(() -> {
                    await(start);
                    store.put(second);
                });
                start.countDown();
                deleting.get(5, TimeUnit.SECONDS);
                putting.get(5, TimeUnit.SECONDS);

                assertThat(store.get(session, second.getId())).as("iteration %d", i).contains(second);
                assertThat(store.list(session)).extracting(SegmentInfo::getId).containsExactly(second.getId());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static SessionLogSegment segment(SessionId session) {
        return SessionLogSegment.builder().sessionId(session).id(SegmentId.generate()).fromSeq(1).toSeq(2).entryCount(1)
                .payload("p").createdAt(Instant.EPOCH).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
