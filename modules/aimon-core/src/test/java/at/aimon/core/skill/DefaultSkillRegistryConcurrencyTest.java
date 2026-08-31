package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.skill.exception.SkillNotFoundException;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.repository.SkillRepository;

/**
 * Regression tests for B-27: the concurrency the class javadoc promises.
 *
 * <p>
 * The javadoc claimed thread-safety while {@code getSkill} was a get-then-put — N threads asking for one skill each
 * ran the repository I/O and the parse — and {@code reloadAll} was a clear-then-refill, leaving a window in which
 * every reader saw an empty registry, and an emptied one for good if a load threw part-way. Each test below fixes
 * one clause of the rewritten javadoc.
 */
@Timeout(30)
class DefaultSkillRegistryConcurrencyTest {

    private static String skillSource(String name) {
        return "---\nname: " + name + "\ndescription: A skill named " + name + "\n---\n\nBody of " + name + "\n";
    }

    private static DefaultSkillRegistry registryOver(FakeSkillRepository repository) {
        return new DefaultSkillRegistry(repository, new MarkdownSkillParser());
    }

    @Test
    @DisplayName("concurrent getSkill for one name loads it once, not once per caller")
    void concurrentGetSkillLoadsOnce() throws Exception {
        final int callers = 8;
        final FakeSkillRepository repository = new FakeSkillRepository("a");
        // Widen the window the get-then-put lost: without it the racers mostly serialise behind each other anyway.
        repository.loadDelayMillis = 150;
        final DefaultSkillRegistry registry = registryOver(repository);

        final CountDownLatch startGate = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            final List<Future<Optional<Skill>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return registry.getSkill("a");
                }));
            }
            startGate.countDown();

            Skill first = null;
            for (Future<Optional<Skill>> future : futures) {
                final Optional<Skill> loaded = future.get(20, TimeUnit.SECONDS);
                assertThat(loaded).isPresent();
                if (first == null) {
                    first = loaded.get();
                } else {
                    // Every caller gets the winner's instance — the losers waited rather than parsing their own.
                    assertThat(loaded).containsSame(first);
                }
            }
            assertThat(repository.loads.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a reload in flight keeps serving the previous cache instead of an empty one")
    void reloadInFlightKeepsServingPreviousCache() throws Exception {
        final FakeSkillRepository repository = new FakeSkillRepository("a", "b");
        final DefaultSkillRegistry registry = registryOver(repository);

        final Skill primedA = registry.getSkill("a").orElseThrow();
        registry.getSkill("b").orElseThrow();
        assertThat(repository.loads.get()).isEqualTo(2);

        final CountDownLatch reloadEntered = new CountDownLatch(1);
        final CountDownLatch releaseReload = new CountDownLatch(1);
        repository.onLoad = () -> {
            reloadEntered.countDown();
            await(releaseReload);
        };

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<?> reload = pool.submit(registry::reloadAll);
            assertThat(reloadEntered.await(20, TimeUnit.SECONDS)).isTrue();

            // The reload is now parked inside its first load. A clear-then-refill would have emptied the cache
            // already, so this call would miss, re-load, and park behind the same latch.
            assertThat(registry.getSkill("a")).containsSame(primedA);
            assertThat(repository.loads.get()).isEqualTo(3);

            releaseReload.countDown();
            reload.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // ...and once it completes, the swap is visible: same content, freshly built instance.
        assertThat(registry.getSkill("a")).isPresent().get().isNotSameAs(primedA);
    }

    @Test
    @DisplayName("a reload that throws part-way leaves the previous cache serving, not an emptied one")
    void failedReloadLeavesPreviousCacheServing() {
        final FakeSkillRepository repository = new FakeSkillRepository("a");
        final DefaultSkillRegistry registry = registryOver(repository);

        final Skill primedA = registry.getSkill("a").orElseThrow();

        repository.onLoad = () -> {
            throw new IllegalStateException("repository is unreachable");
        };
        assertThatThrownBy(registry::reloadAll).isInstanceOf(IllegalStateException.class);

        assertThat(registry.getSkill("a")).containsSame(primedA);
    }

    @Test
    @DisplayName("a miss is not cached, so a skill stays loadable once it appears")
    void missIsNotCached() {
        final FakeSkillRepository repository = new FakeSkillRepository("a");
        final DefaultSkillRegistry registry = registryOver(repository);

        assertThat(registry.getSkill("later")).isEmpty();

        repository.names.add("later");

        assertThat(registry.getSkill("later")).isPresent();
    }

    @Test
    @DisplayName("reloadSkill drops a skill that has since been deleted rather than leaving it cached")
    void reloadSkillDropsADeletedSkill() {
        final FakeSkillRepository repository = new FakeSkillRepository("a");
        final DefaultSkillRegistry registry = registryOver(repository);

        registry.getSkill("a").orElseThrow();
        repository.names.remove("a");

        assertThatThrownBy(() -> registry.reloadSkill("a")).isInstanceOf(SkillNotFoundException.class);
        assertThat(registry.getSkill("a")).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test to release the load");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A repository that counts loads and lets a test park or fail one.
     *
     * <p>
     * Only {@link #findByName(String)} is instrumented: it is the one call every load makes exactly once, so its count
     * <em>is</em> the load count.
     */
    private static final class FakeSkillRepository implements SkillRepository {

        private final List<String> names = new CopyOnWriteArrayList<>();
        private final AtomicInteger loads = new AtomicInteger();

        private volatile Runnable onLoad = () -> {
        };
        private volatile long loadDelayMillis;

        private FakeSkillRepository(String... skillNames) {
            names.addAll(List.of(skillNames));
        }

        @Override
        public Optional<String> findByName(String skillName) {
            if (!names.contains(skillName)) {
                return Optional.empty();
            }
            loads.incrementAndGet();
            if (loadDelayMillis > 0) {
                try {
                    Thread.sleep(loadDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            onLoad.run();
            return Optional.of(skillSource(skillName));
        }

        @Override
        public List<String> findAllNames() {
            return List.copyOf(names);
        }

        @Override
        public boolean exists(String skillName) {
            return names.contains(skillName);
        }

        @Override
        public Map<String, String> findRootFiles(String skillName) {
            return Map.of();
        }

        @Override
        public Map<String, String> findScripts(String skillName) {
            return Map.of();
        }

        @Override
        public Map<String, String> findReferences(String skillName) {
            return Map.of();
        }

        @Override
        public Map<String, String> findAssets(String skillName) {
            return Map.of();
        }
    }
}
