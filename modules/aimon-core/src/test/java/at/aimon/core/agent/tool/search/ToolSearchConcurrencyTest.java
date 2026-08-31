package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.Tool;

@DisplayName("Tool Search Concurrency Tests")
class ToolSearchConcurrencyTest {

    private static final int THREAD_COUNT = 16;
    private static final int OPERATIONS_PER_THREAD = 100;

    private ToolSearchCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());

        // Register some eager and deferred tools
        for (int i = 0; i < 10; i++) {
            catalog.register(MockTools.create("EagerTool" + i, "Eager tool number " + i));
        }
        for (int i = 0; i < 20; i++) {
            catalog.register(
                    SearchableTool.deferred(MockTools.create("DeferredTool" + i, "Deferred tool number " + i)));
        }
    }

    @Nested
    @DisplayName("Catalog Concurrent Read")
    class CatalogConcurrentRead {

        @Test
        @DisplayName("Multiple threads can search() concurrently without error")
        void testConcurrentSearch() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<List<SearchableTool>>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    List<SearchableTool> lastResult = List.of();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        lastResult = catalog.search("deferred tool " + (threadId % 20), 5);
                    }
                    return lastResult;
                }));
            }

            startLatch.countDown();

            for (Future<List<SearchableTool>> future : futures) {
                final List<SearchableTool> result = future.get(10, TimeUnit.SECONDS);
                assertThat(result).isNotNull();
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("Multiple threads can call getEagerTools() and getDeferredTools() concurrently")
        void testConcurrentGetTools() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<Void>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        final List<Tool> eager = catalog.getEagerTools();
                        final List<SearchableTool> deferred = catalog.getDeferredTools();

                        assertThat(eager).isNotNull();
                        assertThat(deferred).isNotNull();
                    }
                    return null;
                }));
            }

            startLatch.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("Catalog Concurrent Read/Write")
    class CatalogConcurrentReadWrite {

        @Test
        @DisplayName("Concurrent register() and search() do not throw exceptions")
        void testConcurrentRegisterAndSearch() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<Void>> futures = new ArrayList<>();

            // Half the threads register new tools
            for (int t = 0; t < THREAD_COUNT / 2; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        catalog.register(
                                SearchableTool.deferred(MockTools.create("NewTool_" + threadId + "_" + i, "New tool")));
                    }
                    return null;
                }));
            }

            // Other half search concurrently
            for (int t = 0; t < THREAD_COUNT / 2; t++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        final List<SearchableTool> results = catalog.search("tool", 5);
                        assertThat(results).isNotNull();

                        final List<Tool> eager = catalog.getEagerTools();
                        assertThat(eager).isNotNull();
                    }
                    return null;
                }));
            }

            startLatch.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("Concurrent register() and unregister() do not corrupt state")
        void testConcurrentRegisterAndUnregister() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<Void>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        final String name = "ConcTool_" + threadId + "_" + i;
                        catalog.register(SearchableTool.deferred(MockTools.create(name, "desc")));
                        catalog.unregister(name);
                    }
                    return null;
                }));
            }

            startLatch.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Original tools should still be intact
            assertThat(catalog.getEagerTools()).hasSize(10);
        }
    }

    @Nested
    @DisplayName("Registry Independent Creation")
    class RegistryIndependentCreation {

        @Test
        @DisplayName("Concurrent createRegistry() returns independent instances")
        void testConcurrentCreateRegistry() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final Set<ToolSearchRegistry> registries = ConcurrentHashMap.newKeySet();

            final List<Future<ToolSearchRegistry>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    final ToolSearchRegistry reg = catalog.createRegistry();
                    registries.add(reg);
                    return reg;
                }));
            }

            startLatch.countDown();

            for (Future<ToolSearchRegistry> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // All registries should be distinct instances
            assertThat(registries).hasSize(THREAD_COUNT);
        }
    }

    @Nested
    @DisplayName("Cross-Session Isolation")
    class CrossSessionIsolation {

        @Test
        @DisplayName("Activation in one session's registry is not visible in another")
        void testCrossConversationIsolation() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<Void>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();

                    // Each thread creates its own session registry
                    final ToolSearchRegistry myRegistry = catalog.createRegistry();

                    // Activate a unique tool for this thread
                    final String toolName = "DeferredTool" + (threadId % 20);
                    myRegistry.searchAndActivate("select:" + toolName, 5);

                    // Verify only the activated tool (+ eager tools) is visible
                    final List<Tool> myTools = myRegistry.findAll();
                    assertThat(myTools).extracting(tool -> tool.getDefinition().getName()).contains(toolName);

                    // Verify other deferred tools are NOT visible
                    final String otherTool = "DeferredTool" + ((threadId + 10) % 20);
                    if (!otherTool.equals(toolName)) {
                        assertThat(myTools).extracting(tool -> tool.getDefinition().getName())
                                .doesNotContain(otherTool);
                    }

                    return null;
                }));
            }

            startLatch.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("Heavy concurrent activation across registries does not corrupt catalog")
        void testHeavyConcurrentActivation() throws Exception {
            final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<Future<Void>> futures = new ArrayList<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    final ToolSearchRegistry myRegistry = catalog.createRegistry();

                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        myRegistry.searchAndActivate("deferred", 5);
                        myRegistry.searchAndActivate("select:DeferredTool0,DeferredTool1", 5);
                        myRegistry.searchAndActivate("+deferred tool", 5);

                        // findAll should never throw
                        final List<Tool> tools = myRegistry.findAll();
                        assertThat(tools).isNotNull();
                    }

                    return null;
                }));
            }

            startLatch.countDown();

            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            // Catalog state should be unchanged
            assertThat(catalog.getEagerTools()).hasSize(10);
            assertThat(catalog.getDeferredTools()).hasSize(20);
        }
    }

}
