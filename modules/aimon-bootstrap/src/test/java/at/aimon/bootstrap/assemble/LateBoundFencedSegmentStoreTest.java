package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;

class LateBoundFencedSegmentStoreTest {

    private static final SessionId SESSION = SessionId.of("late-bound");
    private static final Instant CREATED = Instant.parse("2026-09-24T10:00:00Z");

    private final InMemorySessionLogSegmentStore raw = new InMemorySessionLogSegmentStore();
    private final InMemorySessionLogSegmentStore fenced = new InMemorySessionLogSegmentStore();
    private final LateBoundFencedSegmentStore store = new LateBoundFencedSegmentStore(raw);

    private static SessionLogSegment segment(SegmentId id) {
        return SessionLogSegment.builder().sessionId(SESSION).id(id).fromSeq(0).toSeq(1).entryCount(1).payload("[]")
                .createdAt(CREATED).build();
    }

    @Test
    @DisplayName("reads, writes and scans go to the raw store before and after binding")
    void readsAndWritesGoRaw() {
        final SegmentId id = SegmentId.generate();
        store.put(segment(id));

        assertThat(raw.get(SESSION, id)).isPresent();
        assertThat(store.get(SESSION, id)).isPresent();
        assertThat(store.list(SESSION)).hasSize(1);
        assertThat(store.scanSessions(CREATED.plusSeconds(1), null, 10).getSessionIds()).containsExactly(SESSION);
    }

    @Test
    @DisplayName("a delete before binding fails instead of going unfenced")
    void deleteBeforeBindFails() {
        final SegmentId id = SegmentId.generate();
        store.put(segment(id));

        assertThat(store.isBound()).isFalse();
        assertThatThrownBy(() -> store.delete(SESSION, id)).isInstanceOf(SessionLogSegmentStoreException.class);
        assertThatThrownBy(() -> store.deleteAll(SESSION)).isInstanceOf(SessionLogSegmentStoreException.class);
        assertThat(raw.get(SESSION, id)).isPresent();
    }

    @Test
    @DisplayName("once bound, deletes go to the fenced view and only there")
    void deletesGoToTheBoundView() {
        final SegmentId id = SegmentId.generate();
        store.put(segment(id));
        fenced.put(segment(id));
        store.bind(fenced);

        store.delete(SESSION, id);

        assertThat(fenced.get(SESSION, id)).isEmpty();
        assertThat(raw.get(SESSION, id)).as("the raw store is reached only through the view").isPresent();
        store.deleteAll(SESSION);
        assertThat(store.isBound()).isTrue();
    }

    @Test
    @DisplayName("binds once")
    void bindsOnce() {
        store.bind(fenced);
        assertThatThrownBy(() -> store.bind(fenced)).isInstanceOf(IllegalStateException.class);
    }
}
