package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentCodec;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.llm.Message;

/**
 * The contract every {@link SessionLogSegmentStore} backend must honour (session-log §5.6), written once and run
 * against each.
 *
 * <p>
 * The assertion that matters most is the byte-for-byte payload: the record's manifest keeps the payload's hash, so a
 * backend that normalised the string on the way through — trimmed it, re-encoded it, dropped a NUL — would turn every
 * sealed range into a gap. The rest is the SPI's shape: new ids only, session scoping, absence is not failure.
 *
 * <p>
 * Subclasses return a store over an emptied backend from {@link #store()}; each test uses its own session ids.
 */
public abstract class AbstractSessionLogSegmentStoreContractTest {

    /** Millisecond-truncated: Mongo stores {@code Date}, which has no finer precision. */
    private static final Instant CREATED = Instant.parse("2026-09-24T10:15:30.123Z");

    /**
     * @return the store under test, over an emptied backend
     */
    protected abstract SessionLogSegmentStore store();

    @Test
    @DisplayName("a stored segment reads back exactly, payload byte for byte")
    void roundTrip() {
        final SessionId session = SessionId.of("seg-roundtrip");
        final String payload = SessionLogSegmentCodec.encode(List.of(
                SessionLogEntry.of(3, Message.user("Hello \u0000 😀 \"quoted\" $key.dot"), LogOrigin.CONVERSATION),
                SessionLogEntry.of(5, Message.assistant("answer"), LogOrigin.CONVERSATION)));
        final SessionLogSegment segment = segment(session, SegmentId.generate(), payload);

        store().put(segment);

        final SessionLogSegment read = store().get(session, segment.getId()).orElseThrow();
        assertThat(read).isEqualTo(segment);
        assertThat(SessionLogSegmentCodec.contentHash(read.getPayload()))
                .isEqualTo(SessionLogSegmentCodec.contentHash(payload));
        assertThat(SessionLogSegmentCodec.decode(read.getPayload())).extracting(SessionLogEntry::getSeq)
                .containsExactly(3L, 5L);
    }

    @Test
    @DisplayName("absence is an empty answer, and a segment is invisible to other sessions")
    void absenceAndScoping() {
        final SessionId owner = SessionId.of("seg-owner");
        final SessionId other = SessionId.of("seg-other");
        final SessionLogSegment segment = segment(owner, SegmentId.generate(), "payload");
        store().put(segment);

        assertThat(store().get(owner, SegmentId.generate())).isEmpty();
        assertThat(store().get(other, segment.getId())).isEmpty();
        assertThat(store().list(other)).isEmpty();
        store().delete(other, segment.getId());
        store().deleteAll(other);
        assertThat(store().get(owner, segment.getId())).isPresent();
    }

    @Test
    @DisplayName("a duplicate id is rejected and never overwrites")
    void duplicateIdRejected() {
        final SessionId session = SessionId.of("seg-duplicate");
        final SegmentId id = SegmentId.generate();
        store().put(segment(session, id, "first"));

        assertThatThrownBy(() -> store().put(segment(session, id, "second")))
                .isInstanceOf(SessionLogSegmentStoreException.class);
        assertThat(store().get(session, id).orElseThrow().getPayload()).isEqualTo("first");
    }

    @Test
    @DisplayName("list reports id and creation time of the session's segments")
    void listReportsInfo() {
        final SessionId session = SessionId.of("seg-list");
        final SessionLogSegment a = segment(session, SegmentId.generate(), "a");
        final SessionLogSegment b = SessionLogSegment.builder().sessionId(session).id(SegmentId.generate()).fromSeq(20)
                .toSeq(30).entryCount(4).payload("b").createdAt(CREATED.plus(1, ChronoUnit.HOURS)).build();
        store().put(a);
        store().put(b);

        assertThat(store().list(session)).containsExactlyInAnyOrder(SegmentInfo.of(a.getId(), CREATED),
                SegmentInfo.of(b.getId(), CREATED.plus(1, ChronoUnit.HOURS)));
    }

    @Test
    @DisplayName("delete removes one segment; deleteAll removes the session's segments only")
    void deletes() {
        final SessionId session = SessionId.of("seg-delete");
        final SessionId bystander = SessionId.of("seg-bystander");
        final SessionLogSegment a = segment(session, SegmentId.generate(), "a");
        final SessionLogSegment b = segment(session, SegmentId.generate(), "b");
        final SessionLogSegment c = segment(bystander, SegmentId.generate(), "c");
        store().put(a);
        store().put(b);
        store().put(c);

        store().delete(session, a.getId());
        assertThat(store().get(session, a.getId())).isEmpty();
        assertThat(store().list(session)).extracting(SegmentInfo::getId).containsExactly(b.getId());

        store().deleteAll(session);
        assertThat(store().list(session)).isEmpty();
        assertThat(store().get(session, b.getId())).isEmpty();
        assertThat(store().get(bystander, c.getId())).contains(c);
    }

    @Test
    @DisplayName("the same segment id in two sessions is two segments")
    void sameIdInTwoSessions() {
        // The SPI scopes an id by session. A backend keyed on the id alone would refuse the second put — or worse,
        // let one session's delete remove the other's segment.
        final SessionId first = SessionId.of("seg-shared-a");
        final SessionId second = SessionId.of("seg-shared-b");
        final SegmentId id = SegmentId.generate();
        store().put(segment(first, id, "first"));
        store().put(segment(second, id, "second"));

        assertThat(store().get(first, id).orElseThrow().getPayload()).isEqualTo("first");
        assertThat(store().get(second, id).orElseThrow().getPayload()).isEqualTo("second");

        store().delete(first, id);
        assertThat(store().get(first, id)).isEmpty();
        assertThat(store().get(second, id).orElseThrow().getPayload()).isEqualTo("second");
        assertThat(store().list(second)).extracting(SegmentInfo::getId).containsExactly(id);
    }

    @Test
    @DisplayName("a session id that extends another's is a different session")
    void sessionIdsThatExtendEachOtherStayApart() {
        // A backend that builds keys by concatenating the session id with a suffix can make "X:created" name one of
        // "X"'s keys. Listing, reading and deleting either session must leave the other untouched.
        final SessionId base = SessionId.of("seg-X");
        final SessionId extended = SessionId.of("seg-X:created");
        final SessionLogSegment a = segment(base, SegmentId.generate(), "base");
        final SessionLogSegment b = segment(extended, SegmentId.generate(), "extended");
        store().put(a);
        store().put(b);

        assertThat(store().list(base)).extracting(SegmentInfo::getId).containsExactly(a.getId());
        assertThat(store().list(extended)).extracting(SegmentInfo::getId).containsExactly(b.getId());

        store().deleteAll(extended);
        assertThat(store().list(base)).extracting(SegmentInfo::getId).containsExactly(a.getId());
        assertThat(store().get(base, a.getId())).contains(a);

        store().deleteAll(base);
        assertThat(store().list(base)).isEmpty();
    }

    @Test
    @DisplayName("a full scan reports every session with an old segment, however small the pages")
    void scanReportsEverySessionWithAnOldSegment() {
        // Odd ids on purpose: a backend that derives the id from a key must give back exactly what was stored.
        final List<SessionId> sessions = List.of(SessionId.of("seg-scan-a"), SessionId.of("seg-scan-b:created"),
                SessionId.of("seg-scan-*?[c]"), SessionId.of("seg-scan-}d{"));
        for (SessionId session : sessions) {
            store().put(segment(session, SegmentId.generate(), "old"));
        }

        for (int limit : new int[]{1, 2, 1000}) {
            assertThat(fullScan(CREATED.plus(1, ChronoUnit.HOURS), limit)).as("limit %d", limit).containsAll(sessions);
        }
    }

    @Test
    @DisplayName("a scan still reports a session once one of its old segments is gone, and not after all are")
    void scanFollowsDeletes() {
        final SessionId session = SessionId.of("seg-scan-deleted");
        final SessionLogSegment a = segment(session, SegmentId.generate(), "a");
        final SessionLogSegment b = segment(session, SegmentId.generate(), "b");
        store().put(a);
        store().put(b);

        store().delete(session, a.getId());
        assertThat(fullScan(CREATED.plus(1, ChronoUnit.HOURS), 10)).contains(session);

        store().deleteAll(session);
        assertThat(fullScan(CREATED.plus(1, ChronoUnit.HOURS), 10)).doesNotContain(session);
    }

    @Test
    @DisplayName("a scan rejects a non-positive limit")
    void scanRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> store().scanSessions(CREATED, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Runs one pass to the end, guarding against a cursor that never finishes. */
    private Set<SessionId> fullScan(Instant createdBefore, int limit) {
        final Set<SessionId> seen = new HashSet<>();
        final List<String> cursors = new ArrayList<>();
        String cursor = null;
        do {
            final SegmentScanPage page = store().scanSessions(createdBefore, cursor, limit);
            seen.addAll(page.getSessionIds());
            cursor = page.getNextCursor().orElse(null);
            cursors.add(cursor);
            assertThat(cursors.size()).as("the scan must finish").isLessThan(100_000);
        } while (cursor != null);
        return seen;
    }

    private static SessionLogSegment segment(SessionId session, SegmentId id, String payload) {
        return SessionLogSegment.builder().sessionId(session).id(id).fromSeq(3).toSeq(6).entryCount(2).payload(payload)
                .createdAt(CREATED).build();
    }
}
