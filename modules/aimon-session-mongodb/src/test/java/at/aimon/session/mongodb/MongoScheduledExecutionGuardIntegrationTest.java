package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;

import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.scheduling.testkit.AbstractScheduledExecutionGuardContractTest;
import at.aimon.session.mongodb.internal.DocumentKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Runs the shared guard contract against {@link MongoScheduledExecutionGuard} on a real replica set, plus what is
 * specific to this backend: the slot comparison, the heartbeat, and who may release what.
 *
 * <p>
 * Each node has its own {@link MongoClient}, so the guards share nothing but the collection. A dead node is simulated
 * by
 * writing an expired lease into its document, with a lease long enough that its heartbeat stays out of the way.
 */
@DisplayName("MongoScheduledExecutionGuard integration")
@Tag("docker")
class MongoScheduledExecutionGuardIntegrationTest extends AbstractScheduledExecutionGuardContractTest {

    private static final Instant SLOT = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration LONG_LEASE = Duration.ofHours(1);
    /** Short enough to outlive several times in a test; long enough that a slow CI beat does not lapse it. */
    private static final Duration SHORT_LEASE = Duration.ofMillis(1_500);
    private static final long WAIT_TIMEOUT_MS = 10_000L;

    /** Unique per test, so a heartbeat thread still winding down from the last test is not counted in this one. */
    private final ScheduledTaskId taskId = ScheduledTaskId.generate();
    private final Map<String, MongoClient> clients = new HashMap<>();
    private final Map<String, MongoScheduledExecutionGuard> guards = new HashMap<>();
    private final List<MongoScheduledExecutionGuard> extraGuards = new ArrayList<>();

    @BeforeEach
    void resetSchema() {
        MongoTestSupport.dropAndApplyDdl();
    }

    @AfterEach
    void closeGuards() {
        guards.values().forEach(MongoScheduledExecutionGuard::close);
        extraGuards.forEach(MongoScheduledExecutionGuard::close);
        clients.values().forEach(MongoClient::close);
    }

    @Override
    protected ScheduledExecutionGuard guardFor(String nodeName) {
        return guards.computeIfAbsent(nodeName, name -> guard(name, MongoScheduledExecutionGuard.DEFAULT_LEASE));
    }

    @Override
    protected boolean dedupsOccurrences() {
        return true;
    }

    @Test
    @DisplayName("a misfire older than the last slot that began is refused, and lastSlot does not move back")
    void olderMisfireIsRefused() {
        final Instant newer = SLOT.plusSeconds(120);
        guard("node-A", LONG_LEASE).tryBegin(fire(newer)).orElseThrow().close();

        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT))).isEmpty();

        assertThat(document().getDate(DocumentKeys.F_LAST_SLOT)).isEqualTo(Date.from(newer));
    }

    @Test
    @DisplayName("releasing keeps the document and its lastSlot, and clears the holder")
    void releaseKeepsTheSlot() {
        guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow().close();

        final Document released = document();
        assertThat(released.getDate(DocumentKeys.F_LAST_SLOT)).isEqualTo(Date.from(SLOT));
        assertThat(released.getString(DocumentKeys.F_HOLDER_ID)).isNull();
        // Asked of the server, whose $$NOW stamped it: this machine's clock may be behind the container's.
        assertThat(leaseIsAheadOfServer()).isFalse();
    }

    @Test
    @DisplayName("a fire refused for overlap leaves its slot unspent, so nothing but the lease refused it")
    void overlapRefusalDoesNotAdvanceTheSlot() {
        final ExecutionLease running = guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow();
        final Instant next = SLOT.plusSeconds(60);

        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(next))).isEmpty();
        assertThat(document().getDate(DocumentKeys.F_LAST_SLOT)).isEqualTo(Date.from(SLOT));

        running.close();
        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(next))).isPresent();
    }

    @Test
    @DisplayName("a slot-less fire on a new task writes no lastSlot, and the next slotted fire still begins")
    void slotlessFireOnANewTaskLeavesNoSlot() {
        final MongoScheduledExecutionGuard guard = guard("node-A", LONG_LEASE);

        guard.tryBegin(ScheduledFire.untimed(taskId)).orElseThrow().close();

        assertThat(document().containsKey(DocumentKeys.F_LAST_SLOT)).isFalse();
        assertThat(guard.tryBegin(fire(SLOT))).as("no lastSlot compares older than any slot").isPresent();
    }

    @Test
    @DisplayName("a slot-less fire checks the lease alone, whatever lastSlot says, and leaves it alone")
    void slotlessFireChecksOnlyTheLease() {
        final MongoScheduledExecutionGuard guard = guard("node-A", LONG_LEASE);
        final ExecutionLease running = guard.tryBegin(fire(SLOT)).orElseThrow();

        assertThat(guard.tryBegin(ScheduledFire.untimed(taskId))).as("the lease is live").isEmpty();
        assertThat(guard.tryBegin(taskId)).as("the task-id overload is the same").isEmpty();
        running.close();

        guard.tryBegin(ScheduledFire.untimed(taskId)).orElseThrow().close();
        guard.tryBegin(taskId).orElseThrow().close();

        assertThat(document().getDate(DocumentKeys.F_LAST_SLOT)).isEqualTo(Date.from(SLOT));
        assertThat(guard.tryBegin(fire(SLOT))).as("the slot is still spent").isEmpty();
    }

    @Test
    @DisplayName("an expired lease is taken over by the next slot, but its own slot stays spent")
    void expiredLeaseIsTakenOver() {
        guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow();
        expireLease();

        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT))).as("at most once, even after a crash").isEmpty();
        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT.plusSeconds(60)))).isPresent();
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).startsWith("node-B/");
    }

    @Test
    @DisplayName("closing a lease that was taken over does not release the new holder's")
    void aLapsedLeaseCannotReleaseItsSuccessor() {
        final ExecutionLease lapsed = guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow();
        expireLease();
        guard("node-B", LONG_LEASE).tryBegin(fire(SLOT.plusSeconds(60))).orElseThrow();
        final Document before = document();

        lapsed.close();

        assertThat(document()).isEqualTo(before);
        assertThat(guard("node-C", LONG_LEASE).tryBegin(fire(SLOT.plusSeconds(120)))).isEmpty();
    }

    @Test
    @DisplayName("a lapsed lease cannot release a newer lease of the same node either")
    void holderIdIsPerLeaseNotPerNode() {
        final MongoScheduledExecutionGuard guard = guard("node-A", LONG_LEASE);
        final ExecutionLease lapsed = guard.tryBegin(fire(SLOT)).orElseThrow();
        expireLease();
        guard.tryBegin(fire(SLOT.plusSeconds(60))).orElseThrow();

        lapsed.close();

        assertThat(guard.tryBegin(fire(SLOT.plusSeconds(120)))).as("the newer lease still holds").isEmpty();
    }

    @Test
    @DisplayName("closing a lease twice is harmless: the second close cannot release a successor")
    void closingIsIdempotent() {
        final MongoScheduledExecutionGuard guard = guard("node-A", LONG_LEASE);
        final ExecutionLease first = guard.tryBegin(fire(SLOT)).orElseThrow();
        first.close();
        guard.tryBegin(fire(SLOT.plusSeconds(60))).orElseThrow();

        first.close();

        assertThat(guard.tryBegin(fire(SLOT.plusSeconds(120)))).isEmpty();
    }

    @Test
    @DisplayName("the heartbeat keeps moving a run's lease forward, in server time")
    void heartbeatRenewsTheLease() throws Exception {
        final MongoScheduledExecutionGuard guard = guard("node-A", SHORT_LEASE);
        final ExecutionLease running = guard.tryBegin(fire(SLOT)).orElseThrow();

        // A stamp at or past "the server's time now, plus a lease" can only come from a renewal made after that moment,
        // however long this thread was paused in between. Twice, so it is a heartbeat and not one late write. Whether
        // the lease lapsed during a pause is deliberately not asserted: that measures the machine, not the guard.
        for (int beat = 0; beat < 2; beat++) {
            final Date renewedBy = Date.from(serverNow().plus(SHORT_LEASE));
            awaitTrue(() -> !document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT).before(renewedBy),
                    "a beat renews the lease in server time");
        }
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).startsWith("node-A/");

        running.close();
        assertThat(guard("node-B", SHORT_LEASE).tryBegin(fire(SLOT.plusSeconds(60)))).isPresent();
    }

    @Test
    @DisplayName("a closed lease's heartbeat ends: nothing renews it and its thread is gone")
    void closingEndsTheHeartbeat() throws Exception {
        final ExecutionLease lease = guard("node-A", SHORT_LEASE).tryBegin(fire(SLOT)).orElseThrow();
        assertThat(heartbeatThreads()).hasSize(1);

        lease.close();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat thread ends with its lease");
        final Date released = document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT);
        Thread.sleep(SHORT_LEASE.toMillis());
        assertThat(document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT)).isEqualTo(released);
    }

    @Test
    @DisplayName("a heartbeat that finds another holder stops, and does not take the lease back")
    void lostLeaseEndsTheHeartbeat() throws Exception {
        guard("node-A", SHORT_LEASE).tryBegin(fire(SLOT)).orElseThrow();
        assertThat(heartbeatThreads()).hasSize(1);

        collection().updateOne(new Document(DocumentKeys.F_ID, taskId.value()),
                Updates.set(DocumentKeys.F_HOLDER_ID, "someone-else"));

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat stops once it sees it lost the lease");
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).isEqualTo("someone-else");
    }

    @Test
    @DisplayName("closing the guard stops open heartbeats, releases nothing, and refuses further fires")
    void closingTheGuardStopsHeartbeatsWithoutReleasing() throws Exception {
        final MongoScheduledExecutionGuard guard = guard("node-A", SHORT_LEASE);
        final ExecutionLease open = guard.tryBegin(fire(SLOT)).orElseThrow();

        guard.close();
        guard.close();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "close stops the heartbeat of a lease still open");
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).as("its run may still be going")
                .startsWith("node-A/");
        assertThat(guard.tryBegin(fire(SLOT.plusSeconds(60)))).isEmpty();
        open.close();
    }

    @Test
    @DisplayName("a dead node's lease still refuses other nodes while it lasts")
    void aDeadNodesLeaseHoldsUntilItExpires() {
        final MongoScheduledExecutionGuard dead = guard("node-A", LONG_LEASE);
        dead.tryBegin(fire(SLOT)).orElseThrow();
        // Closing the guard stops the heartbeat and leaves the lease, which is what a dead node's document looks like.
        dead.close();

        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT.plusSeconds(60)))).isEmpty();
    }

    @Test
    @DisplayName("once the lease expires with no heartbeat, a dead node's task is free again")
    void aDeadNodesLeaseExpiresOnItsOwn() throws Exception {
        final MongoScheduledExecutionGuard dead = guard("node-A", SHORT_LEASE);
        dead.tryBegin(fire(SLOT)).orElseThrow();
        dead.close();
        final Instant next = SLOT.plusSeconds(60);
        final MongoScheduledExecutionGuard other = guard("node-B", SHORT_LEASE);

        awaitTrue(() -> {
            final var granted = other.tryBegin(fire(next));
            granted.ifPresent(ExecutionLease::close);
            return granted.isPresent();
        }, "the lease lapses");
    }

    @Test
    @DisplayName("a node id starting with $ is stored as written, not read as a field path")
    void nodeIdIsNotEvaluated() {
        final MongoScheduledExecutionGuard guard = guard("$node", LONG_LEASE);
        guard.tryBegin(ScheduledFire.untimed(taskId)).orElseThrow().close();

        final ExecutionLease stolen = guard.tryBegin(ScheduledFire.untimed(taskId)).orElseThrow();
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).startsWith("$node/");

        stolen.close();
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).isNull();
    }

    @Test
    @DisplayName("with the client gone, tryBegin throws rather than granting, and closing a held lease stays quiet")
    void closedClient() {
        final MongoClient doomed = MongoTestSupport.newClient();
        final MongoScheduledExecutionGuard guard = new MongoScheduledExecutionGuard(
                doomed.getDatabase(MongoTestSupport.DATABASE_NAME), "node-A");
        extraGuards.add(guard);
        final ExecutionLease held = guard.tryBegin(fire(SLOT)).orElseThrow();
        doomed.close();

        // IllegalStateException, not MongoException. The manager skips the fire on any RuntimeException from tryBegin.
        assertThatThrownBy(() -> guard.tryBegin(fire(SLOT.plusSeconds(60)))).isInstanceOf(RuntimeException.class);
        assertThatCode(held::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a server-side failure surfaces as SchedulingException")
    void serverFailureIsWrapped() {
        // A validator that refuses the upsert's insert: a write error that is not the duplicate key of a racing fire.
        final String validated = "guard_validated";
        final var database = MongoTestSupport.sharedDatabase();
        database.getCollection(validated).drop();
        database.createCollection(validated,
                new com.mongodb.client.model.CreateCollectionOptions()
                        .validationOptions(new com.mongodb.client.model.ValidationOptions()
                                .validator(new Document(DocumentKeys.F_HOLDER_ID, new Document("$exists", false)))));
        try {
            final MongoScheduledExecutionGuard guard = new MongoScheduledExecutionGuard(database, validated, "node-A",
                    LONG_LEASE, MongoScheduledExecutionGuard.DEFAULT_MAX_SKEW);
            extraGuards.add(guard);

            assertThatThrownBy(() -> guard.tryBegin(fire(SLOT))).isInstanceOf(SchedulingException.class)
                    .hasMessageContaining(taskId.value());
        } finally {
            database.getCollection(validated).drop();
        }
    }

    @Test
    @DisplayName("the engine counts this guard as seeing other nodes, so per-node triggers converge")
    void engineReconcilesBehindThisGuard() {
        final SchedulingEngine engine = SchedulingEngineBuilder.create().multiNode(true)
                .executionGuard(guardFor("node-A")).build();
        try {
            assertThat(engine.reconcileInterval()).isEqualTo(SchedulingEngineBuilder.DEFAULT_RECONCILE_INTERVAL);
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("a slot too far ahead of the server is refused, with a WARN, and lastSlot stays where it was")
    void futureSlotIsRefusedAndNotRecorded() {
        guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow().close();
        final Document before = document();
        final Instant future = serverNow().plus(Duration.ofDays(1));

        final List<ILoggingEvent> warnings = warningsDuring(
                () -> assertThat(guard("fast-clock", LONG_LEASE).tryBegin(fire(future))).isEmpty());

        assertThat(document()).isEqualTo(before);
        assertThat(warnings).singleElement().satisfies(warn -> assertThat(warn.getFormattedMessage())
                .contains(taskId.value()).contains(future.toString()).contains("ahead of the server"));
        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT.plusSeconds(60)))).as("other nodes carry on")
                .isPresent();
    }

    @Test
    @DisplayName("a first fire with a slot too far ahead is refused and writes no slot, holder or lease")
    void firstFireWithAFutureSlotIsRefused() {
        final Instant future = serverNow().plus(Duration.ofDays(1));

        final List<ILoggingEvent> warnings = warningsDuring(
                () -> assertThat(guard("fast-clock", LONG_LEASE).tryBegin(fire(future))).isEmpty());

        assertThat(document()).isEqualTo(new Document(DocumentKeys.F_ID, taskId.value()));
        assertThat(warnings).hasSize(1);
        assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT))).as("the bare document blocks nothing").isPresent();
    }

    @Test
    @DisplayName("the bound is maxSkew ahead: just inside is granted, just outside refused")
    void boundIsMaxSkewAhead() {
        final Duration skew = Duration.ofHours(1);
        final Duration margin = Duration.ofSeconds(30);
        final MongoScheduledExecutionGuard guard = guardWithSkew("node-A", LONG_LEASE, skew);

        assertThat(guard.tryBegin(fire(serverNow().plus(skew).plus(margin)))).isEmpty();
        assertThat(document().containsKey(DocumentKeys.F_LAST_SLOT)).isFalse();

        final Instant inside = serverNow().plus(skew).minus(margin);
        assertThat(guard.tryBegin(fire(inside))).isPresent();
        assertThat(document().getDate(DocumentKeys.F_LAST_SLOT)).isEqualTo(Date.from(inside));
    }

    @Test
    @DisplayName("a normal duplicate is refused without a WARN, so the WARN means a clock")
    void normalRefusalsDoNotWarn() {
        guard("node-A", LONG_LEASE).tryBegin(fire(SLOT)).orElseThrow().close();

        final List<ILoggingEvent> warnings = warningsDuring(
                () -> assertThat(guard("node-B", LONG_LEASE).tryBegin(fire(SLOT))).isEmpty());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("a document already holding a far-future lastSlot is not repaired: fires are refused, with a WARN")
    void pollutedDocumentIsReportedNotOverwritten() {
        final Date polluted = Date.from(serverNow().plus(Duration.ofDays(30)));
        collection()
                .insertOne(new Document(DocumentKeys.F_ID, taskId.value()).append(DocumentKeys.F_LAST_SLOT, polluted));
        final Document before = document();

        final List<ILoggingEvent> warnings = warningsDuring(
                () -> assertThat(guard("node-A", LONG_LEASE).tryBegin(fire(SLOT))).isEmpty());

        assertThat(document()).isEqualTo(before);
        assertThat(warnings).singleElement().satisfies(warn -> assertThat(warn.getFormattedMessage())
                .contains(polluted.toInstant().toString()).contains(DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS));

        collection().deleteOne(new Document(DocumentKeys.F_ID, taskId.value()));
        assertThat(guard("node-A", LONG_LEASE).tryBegin(fire(SLOT))).as("the operator's way out").isPresent();
    }

    @Test
    @DisplayName("the bound does not touch a slot-less fire, which has no slot to bound")
    void slotlessFireIsNotBounded() {
        collection().insertOne(new Document(DocumentKeys.F_ID, taskId.value()).append(DocumentKeys.F_LAST_SLOT,
                Date.from(serverNow().plus(Duration.ofDays(30)))));

        assertThat(guard("node-A", LONG_LEASE).tryBegin(ScheduledFire.untimed(taskId))).isPresent();
    }

    @Test
    @DisplayName("a first fire's lease lasts the lease duration")
    void coldStartLeaseLastsTheLease() {
        final Duration lease = Duration.ofMinutes(10);
        final Instant before = serverNow();

        guard("node-A", lease).tryBegin(fire(SLOT)).orElseThrow();

        final Instant stamped = document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT).toInstant();
        assertThat(stamped).isBetween(before.plus(lease), serverNow().plus(lease));
    }

    @Test
    @DisplayName("of concurrent first fires on nodes with their own connections, exactly one is granted, every time")
    void concurrentFirstFiresGrantOne() throws Exception {
        final int rounds = 25;
        final int contenders = 6;
        final List<MongoScheduledExecutionGuard> nodes = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            nodes.add(guard("node-" + i, LONG_LEASE));
        }
        final ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int round = 0; round < rounds; round++) {
                final ScheduledTaskId contested = ScheduledTaskId.generate();
                final CountDownLatch go = new CountDownLatch(1);
                final List<Future<Boolean>> attempts = new ArrayList<>();
                for (MongoScheduledExecutionGuard node : nodes) {
                    attempts.add(pool.submit(() -> {
                        go.await();
                        return node.tryBegin(ScheduledFire.of(contested, SLOT, SLOT)).isPresent();
                    }));
                }
                go.countDown();
                int granted = 0;
                for (Future<Boolean> attempt : attempts) {
                    granted += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertThat(granted).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private MongoScheduledExecutionGuard guard(String nodeName, Duration lease) {
        return guardWithSkew(nodeName, lease, MongoScheduledExecutionGuard.DEFAULT_MAX_SKEW);
    }

    private MongoScheduledExecutionGuard guardWithSkew(String nodeName, Duration lease, Duration maxSkew) {
        final MongoClient client = clients.computeIfAbsent(nodeName, n -> MongoTestSupport.newClient());
        final MongoScheduledExecutionGuard guard = new MongoScheduledExecutionGuard(
                client.getDatabase(MongoTestSupport.DATABASE_NAME), DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS,
                nodeName, lease, maxSkew);
        extraGuards.add(guard);
        return guard;
    }

    /** The clock every decision is made by. This machine's may disagree with the container's. */
    private static Instant serverNow() {
        return MongoTestSupport.sharedDatabase().runCommand(new Document("hello", 1)).getDate("localTime").toInstant();
    }

    private boolean leaseIsAheadOfServer() {
        return collection().find(new Document(DocumentKeys.F_ID, taskId.value()).append("$expr",
                new Document("$gt", List.of("$" + DocumentKeys.F_LEASE_EXPIRES_AT, "$$NOW")))).first() != null;
    }

    private static List<ILoggingEvent> warningsDuring(Runnable action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(MongoScheduledExecutionGuard.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private ScheduledFire fire(Instant slot) {
        return ScheduledFire.of(taskId, slot, slot);
    }

    private MongoCollection<Document> collection() {
        return MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS);
    }

    private Document document() {
        return collection().find(new Document(DocumentKeys.F_ID, taskId.value())).first();
    }

    private void expireLease() {
        collection().updateOne(new Document(DocumentKeys.F_ID, taskId.value()),
                Updates.set(DocumentKeys.F_LEASE_EXPIRES_AT, new Date(0L)));
    }

    private List<Thread> heartbeatThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("mongo-execution-guard-heartbeat-" + taskId)).toList();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String description)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as(description).isLessThan(deadline);
            Thread.sleep(50L);
        }
    }
}
