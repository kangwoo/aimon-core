package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.task.TaskStopSignal;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Integration tests for {@link RedisTaskStopSignal} against a real Redis container — the cross-node stop broadcast that
 * carries a {@code Task.stop} from the node that received it to the node that owns the running task (design §5.3.2 ②,
 * §7).
 *
 * <p>
 * Two signal instances on different connections model two instances: {@code signalA} broadcasts, {@code signalB}
 * receives.
 */
@DisplayName("RedisTaskStopSignal integration")
@Tag("docker")
class RedisTaskStopSignalIntegrationTest {

    private StatefulRedisConnection<String, String> publishA;
    private StatefulRedisPubSubConnection<String, String> subscribeA;
    private StatefulRedisConnection<String, String> publishB;
    private StatefulRedisPubSubConnection<String, String> subscribeB;

    private RedisTaskStopSignal signalA;
    private RedisTaskStopSignal signalB;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        publishA = RedisTestSupport.connect();
        subscribeA = RedisTestSupport.connectPubSub();
        publishB = RedisTestSupport.connect();
        subscribeB = RedisTestSupport.connectPubSub();
        signalA = new RedisTaskStopSignal(publishA, subscribeA);
        signalB = new RedisTaskStopSignal(publishB, subscribeB);
    }

    @AfterEach
    void tearDown() {
        closeQuietly(signalA);
        closeQuietly(signalB);
        closeQuietly(publishA);
        closeQuietly(subscribeA);
        closeQuietly(publishB);
        closeQuietly(subscribeB);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    @Test
    @DisplayName("a stop broadcast from node A reaches node B's handler")
    void broadcastReachesRemoteNode() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (TaskStopSignal.Subscription sub = signalB.subscribe(received::offer)) {
            // Lettuce SUBSCRIBE has a small server-side propagation delay; give it a moment to settle.
            Thread.sleep(100);

            signalA.broadcastStop("t-remote");

            assertThat(received.poll(3, TimeUnit.SECONDS)).isEqualTo("t-remote");
        }
    }

    @Test
    @DisplayName("the broadcasting node also delivers to its own local handler (loopback)")
    void broadcastLoopsBackToPublisher() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (TaskStopSignal.Subscription sub = signalA.subscribe(received::offer)) {
            Thread.sleep(100);

            signalA.broadcastStop("t-self");

            assertThat(received.poll(3, TimeUnit.SECONDS)).isEqualTo("t-self");
        }
    }

    @Test
    @DisplayName("closing a subscription stops further delivery to that handler")
    void closedSubscriptionNoLongerReceives() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        TaskStopSignal.Subscription sub = signalB.subscribe(received::offer);
        Thread.sleep(100);

        signalA.broadcastStop("t1");
        assertThat(received.poll(3, TimeUnit.SECONDS)).isEqualTo("t1");

        sub.close();
        signalA.broadcastStop("t2");
        // The handler was removed; nothing more should arrive on it.
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }
}
