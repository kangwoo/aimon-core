package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;

import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.SchedulingException;

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

    private final ScheduledTaskId taskId = ScheduledTaskId.generate();
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);

    private MongoScheduledExecutionGuard guard;

    @BeforeEach
    void setUp() {
        final MongoDatabase database = mock(MongoDatabase.class);
        when(database.getCollection(anyString())).thenReturn(collection);
        guard = new MongoScheduledExecutionGuard(database, "guards", "node-A", LEASE, Clock.systemUTC());
    }

    @AfterEach
    void closeGuard() {
        guard.close();
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

        grant();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat stops once the lease is lost");
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
        final ExecutionLease lease = grant();
        assertThat(renewing.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        lease.close();
        released.countDown();

        awaitTrue(() -> heartbeatThreads().isEmpty(), "the heartbeat ends with its lease");
    }

    @Test
    @DisplayName("a Mongo error while claiming an existing document surfaces as SchedulingException, with no insert")
    void claimFailureIsWrapped() {
        final MongoException failure = new MongoException("not primary");
        when(collection.findOneAndUpdate(any(Bson.class), anyList())).thenThrow(failure);

        assertThatThrownBy(() -> guard.tryBegin(taskId)).isInstanceOf(SchedulingException.class)
                .hasMessageContaining(taskId.value()).hasCause(failure);

        verify(collection, never()).insertOne(any(Document.class));
        assertThat(heartbeatThreads()).isEmpty();
    }

    private ExecutionLease grant() {
        when(collection.findOneAndUpdate(any(Bson.class), anyList())).thenReturn(new Document());
        return guard.tryBegin(taskId).orElseThrow();
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
