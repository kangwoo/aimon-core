package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.CollectedBatch;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.base.Principal;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What every {@code SessionInbox} owes a batch that contains one entry it cannot decode.
 *
 * <p>
 * All three backends remove an entry from storage <em>before</em> the codec runs, so a decode failure used to take
 * the whole batch with it: the readable messages were deleted and returned to nobody, and their submitters were left
 * waiting on turns that no node would ever run. The contract here is the fix, and it is a contract rather than three
 * tests because the three backends have to answer it identically — the design is
 * <a href="../../../../../../../../docs/design/session/inbox-collect-durability.md">inbox-collect-durability.md</a>.
 *
 * <p>
 * A backend joins by subclassing, implementing the three hooks below, and tagging itself {@code @Tag("docker")}.
 * {@code InMemorySessionInbox} does not join and cannot: it stores the envelopes themselves, so it has no decode
 * step that could fail. That is an absence of subject matter rather than a gap in coverage.
 *
 * <h2>Why the entry has to be planted in the middle</h2>
 *
 * <p>
 * MongoDB's {@code collect} loops {@code findOneAndDelete}, accumulating into a list it throws away on failure, so a
 * poison entry there costs the good entries <em>ahead</em> of it as well. Planting first or last hides that variant
 * — the batch would be lost the same way on every backend and the test would pass against a fix that only handled
 * one shape. {@link #plantUnreadableEntry} is therefore always called between two deliveries, with a short pause on
 * either side: MongoDB orders by {@code deliveredAt} at millisecond precision, and this repository already met that
 * collision in {@code MongoSessionInboxIntegrationTest}. What the pause buys is asserted by
 * {@link #readableEntriesSurviveAnUnreadableOneInTheMiddle}, which pins the returned <em>order</em>.
 */
public abstract class AbstractSessionInboxDurabilityContractTest {

    /**
     * Mongo's {@code deliveredAt} has millisecond precision, so back-to-back writes can collide and leave the order
     * of a batch undefined. Two milliseconds is what the pre-existing Mongo suite uses for the same reason.
     */
    private static final long ORDERING_GAP_MILLIS = 2L;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger inboxLogger;

    /**
     * @return the inbox under test, already bound to this backend's container
     */
    protected abstract SessionInbox inbox();

    /**
     * Writes an entry this build cannot decode, straight through the backend's own API — the codec would refuse to
     * produce one, which is the point.
     *
     * <p>
     * Implementations must make the entry land in {@code tier} and take its ordering position from the moment of the
     * call, exactly as {@code deliver} would: MongoDB stamps {@code deliveredAt} server-side with {@code $$NOW}, so a
     * planting implementation that used a client clock could be re-ordered by skew.
     *
     * @param id
     *            the session to plant into
     * @param tier
     *            the priority tier the entry belongs to
     * @param turnId
     *            the turn id to write into the entry, or null to write none
     * @param idempotencyKey
     *            the idempotency key to write into the entry, or null to write none
     * @return the backend id the entry was stored under
     */
    protected abstract InboundMessageId plantUnreadableEntry(SessionId id, QueuedInputPriority tier, String turnId,
            String idempotencyKey);

    /**
     * @param id
     *            the session to count
     * @return how many entries the backend still holds for {@code id}, readable or not
     */
    protected abstract long countStored(SessionId id);

    /**
     * Starts capturing the inbox's own log. Called from the one scenario that asserts on it rather than from a
     * {@code @BeforeEach}: JUnit runs a superclass's {@code @BeforeEach} first, and at that point the subclass has
     * not wired its container yet, so {@link #inbox()} is still null.
     *
     * <p>
     * The three inbox classes all log through {@code LoggerFactory.getLogger(<that class>)}, so the instance the
     * subclass hands back names its own logger. Attaching to ROOT instead would pick up driver and Testcontainers
     * chatter, and a fourth abstract method would only restate what {@link #inbox()} already knows.
     */
    private void captureInboxLog() {
        inboxLogger = (Logger) LoggerFactory.getLogger(inbox().getClass());
        logAppender = new ListAppender<>();
        logAppender.start();
        inboxLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (inboxLogger != null && logAppender != null) {
            inboxLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    @DisplayName("an entry this build cannot decode costs itself and nothing else")
    void readableEntriesSurviveAnUnreadableOneInTheMiddle() throws InterruptedException {
        final SessionId id = SessionId.of("c-durability-1");
        inbox().deliver(message(id, "first"));
        Thread.sleep(ORDERING_GAP_MILLIS);
        plantUnreadableEntry(id, QueuedInputPriority.NEXT, null, null);
        Thread.sleep(ORDERING_GAP_MILLIS);
        inbox().deliver(message(id, "third"));

        final CollectedBatch batch = inbox().collect(id, QueuedInputPriority.LATER);

        // containsExactly, not contains: the order is what proves the poison really sat between the two, which is
        // the only arrangement that exercises MongoDB's "the ones already deleted go too" variant.
        assertThat(batch.getMessages()).extracting(m -> m.getUserInput().asText()).containsExactly("first", "third");
        assertThat(batch.getUnreadable()).hasSize(1);
    }

    @Test
    @DisplayName("the unreadable entry is gone from storage too — no retry loop, no poison left behind")
    void theWholeBatchLeavesStorage() throws InterruptedException {
        final SessionId id = SessionId.of("c-durability-2");
        inbox().deliver(message(id, "first"));
        Thread.sleep(ORDERING_GAP_MILLIS);
        plantUnreadableEntry(id, QueuedInputPriority.NEXT, null, null);
        Thread.sleep(ORDERING_GAP_MILLIS);
        inbox().deliver(message(id, "third"));

        inbox().collect(id, QueuedInputPriority.LATER);

        assertThat(countStored(id)).as("a dropped entry that stayed would be re-read on every later collect").isZero();
        assertThat(inbox().isEmpty(id)).isTrue();
        assertThat(inbox().collect(id, QueuedInputPriority.LATER).isEmpty())
                .as("and the next collect finds nothing at all").isTrue();
    }

    @Test
    @DisplayName("the drop is audible: one WARN per entry, naming the session")
    void everyDropIsLoggedAtWarnNamingTheSession() throws InterruptedException {
        captureInboxLog();
        final SessionId id = SessionId.of("c-durability-3");
        inbox().deliver(message(id, "first"));
        Thread.sleep(ORDERING_GAP_MILLIS);
        plantUnreadableEntry(id, QueuedInputPriority.NEXT, null, null);

        inbox().collect(id, QueuedInputPriority.LATER);

        assertThat(logAppender.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        // An operator who cannot name the session cannot tell anyone their turn did not run — the same reason the
        // user-input degradation path carries one.
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains(id.value());
    }

    @Test
    @DisplayName("what is still legible of the entry's address rides back with it")
    void theAddressIsRecoveredWhenTheDocumentStillCarriesIt() throws InterruptedException {
        final SessionId id = SessionId.of("c-durability-4");
        inbox().deliver(message(id, "first"));
        Thread.sleep(ORDERING_GAP_MILLIS);
        final InboundMessageId planted = plantUnreadableEntry(id, QueuedInputPriority.NEXT, "turn-abc", "key-abc");

        final CollectedBatch batch = inbox().collect(id, QueuedInputPriority.LATER);

        assertThat(batch.getUnreadable()).singleElement().satisfies(entry -> {
            assertThat(entry.getId()).isEqualTo(planted);
            assertThat(entry.getTurnId()).hasValue("turn-abc");
            assertThat(entry.getIdempotencyKey()).hasValue("key-abc");
            assertThat(entry.isAddressable()).isTrue();
            assertThat(entry.getReason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("an entry with no address still comes back, reported rather than thrown")
    void anEntryWithoutAnAddressIsStillReported() {
        final SessionId id = SessionId.of("c-durability-5");
        plantUnreadableEntry(id, QueuedInputPriority.NEXT, null, null);

        final CollectedBatch batch = inbox().collect(id, QueuedInputPriority.LATER);

        assertThat(batch.getMessages()).isEmpty();
        assertThat(batch.getUnreadable()).singleElement().satisfies(entry -> {
            assertThat(entry.getTurnId()).isEmpty();
            assertThat(entry.getIdempotencyKey()).isEmpty();
            assertThat(entry.isAddressable()).isFalse();
        });
        // The shape the router has to survive: nothing readable, one unreadable. Treating an empty message list as
        // "nothing happened" is what would drop the report on the floor.
        assertThat(batch.isEmpty()).isFalse();
    }

    /**
     * A plain deliverable message, so a subclass writes no fixture of its own.
     *
     * @param id
     *            the session
     * @param text
     *            the user input text
     * @return the envelope
     */
    protected final InboundMessage message(SessionId id, String text) {
        return InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput(text)
                .priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(java.time.Instant.parse("2026-04-27T10:00:00Z")).build();
    }
}
