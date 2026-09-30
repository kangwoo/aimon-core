package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.llm.Message;

/**
 * {@link SessionLogReadCache}: three answers per segment, and a segment id scoped by its session.
 */
class SessionLogReadCacheTest {

    @Test
    void theSameSegmentIdInTwoSessionsIsTwoEntries() {
        final SessionLogReadCache cache = SessionLogReadCache.create();
        final SegmentId shared = SegmentId.generate();
        final List<SessionLogEntry> first = List.of(SessionLogEntry.of(0, Message.user("a"), LogOrigin.CONVERSATION));
        cache.put(SessionId.of("one"), shared, first);

        assertThat(cache.get(SessionId.of("two"), shared)).as("not loaded for the other session").isNull();

        cache.put(SessionId.of("two"), shared, null);
        assertThat(cache.get(SessionId.of("one"), shared)).contains(first);
        assertThat(cache.get(SessionId.of("two"), shared)).as("loaded, unreadable").isEmpty();
        assertThat(cache.size()).isEqualTo(2);
    }
}
