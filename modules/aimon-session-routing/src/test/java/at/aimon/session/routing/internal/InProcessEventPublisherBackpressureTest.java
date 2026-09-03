package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.session.routing.fixture.TestLiveSession;

/**
 * WS-02-B9: a slow subscriber must NEVER block the producer (design §5.5.1 invariant).
 *
 * <p>
 * The producer thread emits events one after another with no parking. With a non-requesting subscriber,
 * {@code SubmissionPublisher.offer(0L, NANOS, ...)} drops the oldest queued item rather than blocking.
 */
@DisplayName("InProcessEventPublisher backpressure")
class InProcessEventPublisherBackpressureTest {

    @Test
    @DisplayName("WS-02-B9: producer never blocks even if no subscriber demands events")
    void producerDoesNotBlockOnSlowSubscriber() throws Exception {
        try (InProcessEventPublisher publisher = new InProcessEventPublisher()) {
            final SessionId id = SessionId.of("c-9");
            final AtomicInteger received = new AtomicInteger();
            final CountDownLatch subscribed = new CountDownLatch(1);

            publisher.publisherFor(id).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    // Never call s.request(...) — keep demand at zero so the publisher's offer-with-drop kicks in.
                    subscribed.countDown();
                }

                @Override
                public void onNext(AgentExecutionEvent item) {
                    received.incrementAndGet();
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onComplete() {
                }
            });
            assertThat(subscribed.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

            final long start = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                publisher.emit(id, sampleEvent(i));
            }
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            // The exact threshold is generous; the point is the producer didn't park indefinitely on backpressure.
            assertThat(elapsedMs).as("emit must never block — 10k offers should complete promptly").isLessThan(2_000L);
            // No requirement on received count: drop-oldest semantics mean the slow subscriber gets very few events.
            assertThat(received.get()).isLessThanOrEqualTo(10_000);
        }
    }

    private static AgentExecutionEvent sampleEvent(int i) {
        return AssistantTextDelta.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1")).iteration(0)
                .timestamp(Instant.now()).delta("delta-" + i).chunkIndex(i).build();
    }
}
