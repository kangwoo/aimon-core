package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.UpdateResult;

import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.session.mongodb.internal.DocumentKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The failures a test cannot get from a healthy server on demand: a renewal that fails and later recovers, and a claim
 * that errors. Behaviour against a real server lives in the docker-tagged
 * {@code MongoScheduledExecutionGuardIntegrationTest}.
 */
@DisplayName("MongoScheduledExecutionGuard — failures against a mocked collection")
class MongoScheduledExecutionGuardTest {

    /** Beats every 10ms. */
    private static final Duration LEASE = Duration.ofMillis(30);
    private static final long WAIT_TIMEOUT_MS = 10_000L;
    private static final int DUPLICATE_KEY = 11000;

    private final ScheduledTaskId taskId = ScheduledTaskId.generate();
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();
    private MongoDatabase database;
    private MongoScheduledExecutionGuard guard;

    @BeforeEach
    void setUp() {
        final MongoDatabase database = mock(MongoDatabase.class);
        when(database.getCollection(anyString())).thenReturn(collection);
        this.database = database;
        guard = new MongoScheduledExecutionGuard(database, "guards", "node-A", LEASE,
                MongoScheduledExecutionGuard.DEFAULT_MAX_SKEW);
    }

    @AfterEach
    void closeGuard() {
        guard.close();
        appenders.forEach(guardLogger()::detachAppender);
    }

    @Test
    @DisplayName("a renewal that fails leaves the heartbeat running, and it renews again once Mongo is back")
    void heartbeatSurvivesFailedRenewals() throws Exception {
        final AtomicBoolean down = new AtomicBoolean(true);
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger renewed = new AtomicInteger();
        when(collection.updateOne(any(Bson.class), anyList())).thenAnswer(call -> {
            final int attempt = attempts.incrementAndGet();
            if (down.get()) {
                // Both kinds a renewal meets: a server error, and the unchecked one a closed client throws.
                throw attempt % 2 == 0
                        ? new IllegalStateException("state should be: open")
                        : new MongoException("not primary");
            }
            renewed.incrementAndGet();
            return UpdateResult.acknowledged(1L, 1L, null);
        });
        final ExecutionLease lease = grant();

        awaitTrue(() -> attempts.get() >= 4, "the heartbeat retries past failed renewals");
        assertThat(renewed).hasValue(0);
        assertThat(heartbeatThreads()).hasSize(1);

        down.set(false);

        awaitTrue(() -> renewed.get() >= 3, "the heartbeat renews again once Mongo is back");
        assertThat(heartbeatThreads()).hasSize(1);

        lease.close();
        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat ends with its lease");
    }

    @Test
    @DisplayName("a renewal that matches nothing means the lease was lost, and the heartbeat stops")
    void renewalMatchingNothingStopsTheHeartbeat() throws Exception {
        when(collection.updateOne(any(Bson.class), anyList())).thenReturn(UpdateResult.acknowledged(0L, 0L, null));
        final List<ILoggingEvent> warnings = captureWarnings();

        grant();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat stops once the lease is lost");
        assertThat(warnings).singleElement()
                .satisfies(warn -> assertThat(warn.getFormattedMessage()).contains("Lost the lease"));
    }

    /**
     * The release clears the holder, so a renewal on the wire when the lease closes matches nothing. That is not a lost
     * lease, and the heartbeat must end without saying it was.
     */
    @Test
    @DisplayName("closing a lease during a renewal ends the heartbeat")
    void closingDuringARenewalEndsTheHeartbeat() throws Exception {
        final CountDownLatch renewing = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        when(collection.updateOne(any(Bson.class), anyList())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                renewing.countDown();
                assertThat(released.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            }
            return UpdateResult.acknowledged(0L, 0L, null);
        });
        final List<ILoggingEvent> warnings = captureWarnings();
        final ExecutionLease lease = grant();
        assertThat(renewing.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        lease.close();
        released.countDown();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat ends with its lease");
        assertThat(warnings).as("its own release is not a lost lease").isEmpty();
    }

    @Test
    @DisplayName("a Mongo error while claiming surfaces as SchedulingException, with no retry and no heartbeat")
    void claimFailureIsWrapped() {
        final MongoException failure = new MongoException("not primary");
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> guard.tryBegin(taskId)).isInstanceOf(SchedulingException.class)
                .hasMessageContaining(taskId.value()).hasCause(failure);

        verify(collection, times(1)).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class));
        assertThat(heartbeatThreads()).isEmpty();
    }

    @Test
    @DisplayName("a duplicate key from racing first fires is retried once, and the retry decides")
    void duplicateKeyIsRetried() {
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException(DUPLICATE_KEY, "E11000")).thenAnswer(this::echoHolderAsGranted);

        assertThat(guard.tryBegin(taskId)).isPresent();

        verify(collection, times(2)).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("a second duplicate key is a failure, not a grant and not a refusal")
    void repeatedDuplicateKeyIsWrapped() {
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException(DUPLICATE_KEY, "E11000"));

        assertThatThrownBy(() -> guard.tryBegin(taskId)).isInstanceOf(SchedulingException.class);
    }

    @Test
    @DisplayName("a refusal stays a refusal when finding out why fails")
    void failedDiagnosisDoesNotChangeARefusal() {
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document(DocumentKeys.F_HOLDER_ID, "someone-else"));
        when(collection.aggregate(anyList())).thenThrow(new MongoException("not primary"));
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

        assertThat(guard.tryBegin(ScheduledFire.of(taskId, slot, slot))).isEmpty();
    }

    @Test
    @DisplayName("a grant reads nothing more: finding out why a fire was refused is for refusals")
    void grantCostsOneRoundTrip() {
        grant().close();

        verify(collection, never()).aggregate(anyList());
    }

    /**
     * What makes the claim immune to this machine's clock, which no test against a container on the same machine can
     * show: the two clocks agree there, so a build reading the application's clock would pass. Every time in the
     * request
     * has to be {@code $$NOW}, and the only dates in it are the fire's slot and the "before everything" constant.
     */
    @Test
    @DisplayName("the claim sends $$NOW for every time, and no date but the slot: the application's clock has no say")
    void claimTakesEveryTimeFromTheServer() {
        final Duration maxSkew = Duration.ofMinutes(7);
        guard = new MongoScheduledExecutionGuard(database, "guards", "node-A", Duration.ofMinutes(3), maxSkew);
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenAnswer(this::echoHolderAsGranted);
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

        guard.tryBegin(ScheduledFire.of(taskId, slot, slot)).orElseThrow();

        final ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        final ArgumentCaptor<List<Document>> update = ArgumentCaptor.captor();
        verify(collection).findOneAndUpdate(filter.capture(), update.capture(), any(FindOneAndUpdateOptions.class));
        assertThat(filter.getValue()).isEqualTo(new Document(DocumentKeys.F_ID, taskId.value()));

        final List<Object> sent = flatten(update.getValue());
        assertThat(sent).filteredOn(Date.class::isInstance).containsOnly(Date.from(slot), new Date(Long.MIN_VALUE));
        // The stamp and the bound are each $$NOW plus their duration, not a time this machine worked out.
        final Document stage = update.getValue().get(0).get("$set", Document.class);
        final Document stampCondition = stage.get(DocumentKeys.F_LEASE_EXPIRES_AT, Document.class);
        assertThat(stampCondition.get("$cond", List.class).get(1))
                .isEqualTo(new Document("$add", List.of("$$NOW", Duration.ofMinutes(3).toMillis())));
        assertThat(sent).contains(new Document("$add", List.of("$$NOW", maxSkew.toMillis())));
    }

    @Test
    @DisplayName("lease and maxSkew are checked in milliseconds, the unit that reaches the server")
    void durationsAreValidatedInMilliseconds() {
        final Duration ok = Duration.ofMinutes(1);
        for (Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1),
                Duration.ofNanos(999_999))) {
            assertThatThrownBy(() -> new MongoScheduledExecutionGuard(database, "guards", "node-A", ok, bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSkew");
            assertThatThrownBy(() -> new MongoScheduledExecutionGuard(database, "guards", "node-A", bad, ok))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        }
        final Duration smallest = Duration.ofMillis(1);
        new MongoScheduledExecutionGuard(database, "guards", "node-A", ok, smallest).close();
    }

    /** The heartbeat's interval is a third of the lease, so the smallest lease must not make it spin without a wait. */
    @Test
    @DisplayName("the smallest lease still gets a working heartbeat")
    void smallestLeaseStillBeats() throws Exception {
        final AtomicInteger renewals = new AtomicInteger();
        when(collection.updateOne(any(Bson.class), anyList())).thenAnswer(call -> {
            renewals.incrementAndGet();
            return UpdateResult.acknowledged(1L, 1L, null);
        });
        guard = new MongoScheduledExecutionGuard(database, "guards", "node-A", Duration.ofMillis(1),
                MongoScheduledExecutionGuard.DEFAULT_MAX_SKEW);

        final ExecutionLease lease = grant();

        awaitTrue(() -> renewals.get() >= 2, "a 1ms lease beats");
        lease.close();
    }

    @Test
    @DisplayName("lease and maxSkew must not be null")
    void durationsAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MongoScheduledExecutionGuard(database, "guards", "node-A", LEASE, null));
    }

    /** Warnings from the guard from now until the end of the test. */
    private List<ILoggingEvent> captureWarnings() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getLevel() == Level.WARN) {
                    super.append(event);
                }
            }
        };
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        guardLogger().addAppender(appender);
        appenders.add(appender);
        return appender.list;
    }

    private static Logger guardLogger() {
        return (Logger) LoggerFactory.getLogger(MongoScheduledExecutionGuard.class);
    }

    /** Every value in a request, nested documents and lists included, the containers themselves among them. */
    private static List<Object> flatten(Object node) {
        final List<Object> all = new ArrayList<>();
        all.add(node);
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> all.addAll(flatten(value)));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> all.addAll(flatten(value)));
        }
        return all;
    }

    private ExecutionLease grant() {
        when(collection.findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenAnswer(this::echoHolderAsGranted);
        return guard.tryBegin(taskId).orElseThrow();
    }

    /** Answers as the server does for a granted fire: with the document naming the holder the pipeline proposed. */
    @SuppressWarnings("unchecked")
    private Document echoHolderAsGranted(InvocationOnMock call) {
        final Document stage = (Document) call.<List<Document>>getArgument(1).get(0);
        final Document holder = stage.get("$set", Document.class).get(DocumentKeys.F_HOLDER_ID, Document.class);
        final Document literal = (Document) holder.get("$cond", List.class).get(1);
        return new Document(DocumentKeys.F_HOLDER_ID, literal.getString("$literal"));
    }

    private List<Thread> heartbeatThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("mongo-execution-guard-heartbeat-" + taskId)).toList();
    }

    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as(description).isLessThan(deadline);
            Thread.sleep(5L);
        }
    }
}
