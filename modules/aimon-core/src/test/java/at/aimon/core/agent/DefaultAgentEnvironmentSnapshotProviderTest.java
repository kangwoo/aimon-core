package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultAgentEnvironmentSnapshotProvider Tests")
class DefaultAgentEnvironmentSnapshotProviderTest {

    private static AgentRuntime mockContext(AgentRuntimeId id) {
        AgentRuntime context = mock(AgentRuntime.class);
        when(context.getId()).thenReturn(id);
        return context;
    }

    private static AgentEnvironmentSnapshot newSession() {
        return AgentEnvironmentSnapshot.builder().workingDirectory("/wd").currentDate(Instant.now())
                .environment(Environment.createWithWorkingDirectory("/wd")).build();
    }

    @Test
    @DisplayName("Constructor rejects null collector")
    void constructor_rejectsNullCollector() {
        assertThatThrownBy(() -> new DefaultAgentEnvironmentSnapshotProvider(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("collector");
    }

    @Test
    @DisplayName("get() rejects null context")
    void get_rejectsNullContext() {
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(
                ctx -> newSession());

        assertThatThrownBy(() -> provider.get(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    @DisplayName("Repeated get() for same id returns identical instance (reference equality)")
    void get_sameIdReturnsSameInstance() {
        AgentRuntimeId id = AgentRuntimeIds.testCtx("ctx-1");
        AgentRuntime ctx = mockContext(id);
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(
                c -> newSession());

        AgentEnvironmentSnapshot first = provider.get(ctx);
        AgentEnvironmentSnapshot second = provider.get(ctx);

        assertThat(first).isSameAs(second);
        assertThat(provider.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Collector invoked exactly once per id across many calls")
    void collector_invokedOncePerId() {
        AtomicInteger callCount = new AtomicInteger();
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(c -> {
            callCount.incrementAndGet();
            return newSession();
        });

        AgentRuntime ctx = mockContext(AgentRuntimeIds.testCtx("ctx-x"));
        for (int i = 0; i < 10; i++) {
            provider.get(ctx);
        }

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Different ids produce different instances, both cached")
    void differentIds_producedSeparately() {
        AtomicInteger callCount = new AtomicInteger();
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(c -> {
            callCount.incrementAndGet();
            return newSession();
        });

        AgentEnvironmentSnapshot a = provider.get(mockContext(AgentRuntimeIds.testCtx("a")));
        AgentEnvironmentSnapshot b = provider.get(mockContext(AgentRuntimeIds.testCtx("b")));

        assertThat(a).isNotSameAs(b);
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(provider.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate() causes next get() to re-invoke collector")
    void invalidate_forcesRefresh() {
        AtomicInteger callCount = new AtomicInteger();
        Function<AgentRuntime, AgentEnvironmentSnapshot> collector = c -> {
            callCount.incrementAndGet();
            return newSession();
        };
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(collector);

        AgentRuntimeId id = AgentRuntimeIds.testCtx("reload-me");
        AgentRuntime ctx = mockContext(id);

        AgentEnvironmentSnapshot first = provider.get(ctx);
        provider.invalidate(id);
        AgentEnvironmentSnapshot second = provider.get(ctx);

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(first).isNotSameAs(second);
    }

    @Test
    @DisplayName("invalidate() rejects null id")
    void invalidate_rejectsNull() {
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(
                c -> newSession());

        assertThatThrownBy(() -> provider.invalidate(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("Collector returning null surfaces IllegalStateException")
    void collectorReturningNull_raisesISE() {
        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(c -> null);

        assertThatThrownBy(() -> provider.get(mockContext(AgentRuntimeIds.testCtx("nulls"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("null");
    }

    @Test
    @DisplayName("Concurrent get() on same id: collector invoked once, all callers observe same instance")
    void concurrentGet_sameId_singleCollectorInvocation() throws Exception {
        final int threads = 16;
        AtomicInteger callCount = new AtomicInteger();
        CountDownLatch collectorEntered = new CountDownLatch(1);
        CountDownLatch releaseCollector = new CountDownLatch(1);

        Function<AgentRuntime, AgentEnvironmentSnapshot> collector = c -> {
            callCount.incrementAndGet();
            collectorEntered.countDown();
            try {
                // Hold the collector briefly so other threads can pile up on computeIfAbsent
                releaseCollector.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return newSession();
        };

        DefaultAgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(collector);
        AgentRuntimeId sharedId = AgentRuntimeIds.testCtx("shared");
        AgentRuntime ctx = mockContext(sharedId);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<AgentEnvironmentSnapshot>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return provider.get(ctx);
                }));
            }

            startGate.countDown();

            // Wait for the collector to have been entered, then release it so all threads can complete.
            assertThat(collectorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseCollector.countDown();

            AgentEnvironmentSnapshot reference = null;
            for (Future<AgentEnvironmentSnapshot> f : futures) {
                AgentEnvironmentSnapshot result = f.get(5, TimeUnit.SECONDS);
                if (reference == null) {
                    reference = result;
                } else {
                    assertThat(result).isSameAs(reference);
                }
            }

            assertThat(callCount.get()).isEqualTo(1);
            assertThat(provider.size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
