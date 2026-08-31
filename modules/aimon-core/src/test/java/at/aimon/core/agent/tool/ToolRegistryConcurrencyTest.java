package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.search.KeywordToolSearchStrategy;
import at.aimon.core.agent.tool.search.ToolSearchCatalog;

/**
 * Registry-level concurrency contract tests. Both registry implementations sit at the agent-scoped
 * {@code ExecutionContext} level and are therefore shared by every concurrent session of an agent, so their
 * documented threading contracts must actually hold.
 *
 * <ul>
 * <li>{@link ToolSearchCatalog} declares itself thread-safe (backed by a
 * {@link java.util.concurrent.ConcurrentHashMap}):
 * concurrent registration from many threads, interleaved with concurrent reads, must neither throw nor lose a
 * registration.</li>
 * <li>{@link DefaultToolRegistry} documents that it is <b>not</b> thread-safe for concurrent mutation; its supported
 * concurrent access pattern is populate-once-then-read. This test pins that pattern: after a single-threaded population
 * the registry is hammered by many reader threads and every read observes the full, consistent tool set without
 * throwing.</li>
 * </ul>
 *
 * <p>
 * These are characterization tests — they lock in each type's declared contract, not an aspirational one. In particular
 * the {@code DefaultToolRegistry} test deliberately does not mutate concurrently, because that is outside its contract.
 */
@DisplayName("Tool registry concurrency contracts")
class ToolRegistryConcurrencyTest {

    /** A minimally-defined tool with a caller-chosen unique name. */
    private static final class NamedTool extends AbstractTool {
        NamedTool(String name) {
            super(name, "named tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("ok");
        }
    }

    @Test
    @DisplayName("ToolSearchCatalog: concurrent register interleaved with concurrent reads loses nothing and never throws")
    void toolSearchCatalogConcurrentRegisterAndReadIsSafe() throws InterruptedException {
        final int toolCount = 200;
        final int readers = 4;
        final ToolSearchCatalog catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());

        final ExecutorService pool = Executors.newFixedThreadPool(toolCount / 8 + readers);
        final CountDownLatch startGate = new CountDownLatch(1);
        final AtomicBoolean writersDone = new AtomicBoolean(false);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        // Writers: each registers one distinct tool.
        for (int i = 0; i < toolCount; i++) {
            final String name = "cat-tool-" + i;
            tasks.add(() -> {
                try {
                    startGate.await();
                    catalog.register(new NamedTool(name));
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        // Readers: spin over the catalog's query surface while registration is in flight.
        // A reader is torn down by shutdownNow(), so its own interrupt is teardown, not a catalog
        // failure — the pool has fewer threads than writers, so a reader can be dequeued at the
        // instant shutdownNow() fires and take the interrupt inside an already-open await().
        for (int r = 0; r < readers; r++) {
            tasks.add(() -> {
                try {
                    startGate.await();
                    while (!writersDone.get()) {
                        catalog.size();
                        catalog.findAll();
                        catalog.findByName("cat-tool-0");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        try {
            final List<java.util.concurrent.Future<?>> writerFutures = new ArrayList<>();
            for (int i = 0; i < toolCount; i++) {
                writerFutures.add(pool.submit(tasks.get(i)));
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(tasks.get(toolCount + r));
            }
            startGate.countDown();
            for (java.util.concurrent.Future<?> f : writerFutures) {
                f.get(30, TimeUnit.SECONDS);
            }
            writersDone.set(true);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            failures.add(e);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).as("concurrent register/read on the thread-safe catalog must not throw").isEmpty();
        assertThat(catalog.size()).as("every concurrent registration is retained").isEqualTo(toolCount);
        for (int i = 0; i < toolCount; i++) {
            assertThat(catalog.findByName("cat-tool-" + i)).as("tool %d is findable after concurrent registration", i)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("DefaultToolRegistry: populate-once then concurrent reads observe the full set and never throw")
    void defaultToolRegistryReadOnlyConcurrencyIsSafe() throws InterruptedException {
        final int toolCount = 100;
        final int readers = 8;

        // Single-threaded population — the ONLY mutation phase, per the registry's documented contract.
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (int i = 0; i < toolCount; i++) {
            registry.register(new NamedTool("reg-tool-" + i));
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch ready = new CountDownLatch(readers);
        final CountDownLatch startGate = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            final Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    while (!stop.get()) {
                        final List<Tool> all = registry.findAll();
                        final Set<String> names = registry.getToolNames();
                        if (all.size() != toolCount || names.size() != toolCount) {
                            throw new AssertionError("partial view: all=" + all.size() + " names=" + names.size());
                        }
                        if (registry.findByName("reg-tool-0").isEmpty()) {
                            throw new AssertionError("missing reg-tool-0");
                        }
                        if (registry.size() != toolCount) {
                            throw new AssertionError("size drifted: " + registry.size());
                        }
                    }
                } catch (Throwable t2) {
                    failures.add(t2);
                }
            }, "registry-reader-" + r);
            threads.add(t);
            t.start();
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).as("all reader threads started").isTrue();
        startGate.countDown();
        // Let the readers spin over the stable registry for a short while.
        Thread.sleep(200);
        stop.set(true);
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(failures).as("concurrent reads of a populate-once registry must be consistent and crash-free")
                .isEmpty();
        assertThat(registry.size()).isEqualTo(toolCount);
    }
}
