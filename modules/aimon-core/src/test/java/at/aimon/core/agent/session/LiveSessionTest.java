package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Verifies SESSION-01 default behaviour of {@link LiveSession#events()}: the built-in publisher immediately completes
 * with no events, so callers that subscribe today are not blocked pending STREAM-03.
 */
@DisplayName("LiveSession#events() default publisher (pre-STREAM-03 stub)")
class LiveSessionTest {

    @Test
    @DisplayName("default events() publisher completes immediately with no items")
    void defaultEventsPublisherCompletesImmediately() throws InterruptedException {
        final LiveSession session = new StubSession();
        final Flow.Publisher<AgentExecutionEvent> publisher = session.events();
        assertThat(publisher).isNotNull();

        final List<AgentExecutionEvent> received = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                received.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                completed.set(true);
                latch.countDown();
            }
        });

        assertThat(latch.await(1, TimeUnit.SECONDS)).as("subscriber must be signalled within 1s").isTrue();
        assertThat(errorRef.get()).isNull();
        assertThat(received).isEmpty();
        assertThat(completed).isTrue();
    }

    /** Minimal LiveSession stub that relies on the default {@link LiveSession#events()} implementation. */
    private static final class StubSession implements LiveSession {
        private final SessionId id = SessionId.of("stub-session");

        @Override
        public SessionId getSessionId() {
            return id;
        }

        @Override
        public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
