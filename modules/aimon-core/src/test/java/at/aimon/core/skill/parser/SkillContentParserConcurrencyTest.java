package at.aimon.core.skill.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SkillContentParser} is called from many threads at once, so no parse may observe another's state.
 *
 * <p>
 * It used to hold {@code private static final Yaml YAML = new Yaml()} — one instance shared by every caller — while its
 * own javadoc claimed "Thread-safe and stateless". snakeyaml documents the opposite: {@code Yaml.loadFromReader}
 * publishes each call's freshly built {@code Composer} onto the <em>shared</em> {@code BaseConstructor} via
 * {@code constructor.setComposer(composer)} and then reads it straight back out of that same field, and
 * {@code BaseConstructor} additionally carries a plain {@code HashMap constructedObjects} and {@code HashSet
 * recursiveObjects} that every concurrent construction mutates and clears.
 *
 * <p>
 * The damage is mostly silent. Measured against the shared instance at this test's workload, roughly five in six
 * failures threw nothing at all and simply returned a frontmatter map with keys missing or with another skill's values
 * in them. A dropped key is not cosmetic here: {@code allowed-tools} going missing makes
 * {@code Skill.hasToolRestrictions()} false, and {@code SkillPermissionManager} then fails <em>open</em>. Worse, the
 * degraded skill is cached by {@code DefaultSkillRegistry}, so a momentary race is served for the rest of the process
 * lifetime.
 *
 * <p>
 * That is why this asserts per-thread payload identity rather than the absence of exceptions — an exception-only
 * assertion would measure the minority failure mode. The structural half of the invariant, that no {@code Yaml} is
 * held in a field anywhere in main sources, is pinned separately by
 * {@code at.aimon.core.architecture.YamlParserInstanceArchitectureTest}; without it a global {@code synchronized} block
 * would turn this test green while serialising every skill parse in the framework.
 */
@DisplayName("SkillContentParser concurrency contract")
class SkillContentParserConcurrencyTest {

    private static final int THREADS = 8;

    private static final int ITERATIONS_PER_THREAD = 1_000;

    /** Enough failures to diagnose the mode; the rest would only lengthen the report. */
    private static final int ANOMALY_CAP = 20;

    @Test
    @DisplayName("concurrent parses each return their own frontmatter, never another thread's and never a partial map")
    void concurrentParsesDoNotCrossContaminate() throws Exception {
        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch startGate = new CountDownLatch(1);
        final List<String> anomalies = new CopyOnWriteArrayList<>();
        final AtomicInteger completedParses = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int index = t;
            final String document = skillDocument(index);
            final Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < ITERATIONS_PER_THREAD && anomalies.size() < ANOMALY_CAP; n++) {
                    peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        final Map<String, Object> frontmatter = SkillContentParser.parse(document).getFrontmatter();
                        if (!isEntirelyOwnedBy(frontmatter, index)) {
                            anomalies.add("thread " + index + " observed foreign/partial frontmatter: " + frontmatter);
                        }
                    } catch (Throwable e) {
                        anomalies.add("thread " + index + " threw " + e.getClass().getName() + ": " + e.getMessage());
                    } finally {
                        inFlight.decrementAndGet();
                        completedParses.incrementAndGet();
                    }
                }
            }, "skill-parser-" + index);
            threads.add(thread);
            thread.start();
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("all worker threads reached the start gate").isTrue();
        startGate.countDown();
        for (final Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertThat(thread.isAlive()).as("worker %s finished within the timeout", thread.getName()).isFalse();
        }

        // Anti-vacuity guards. Without these, "no anomalies" is an unfalsifiable negative: a runner that serialised
        // the workers, or workers that returned early, would report success just as loudly as a correct parser.
        assertThat(completedParses.get()).as("the workload actually ran").isGreaterThanOrEqualTo(THREADS);
        assertThat(peakInFlight.get()).as("parses must genuinely overlap, otherwise this test proves nothing")
                .isGreaterThan(1);

        assertThat(anomalies).as("SkillContentParser.parse must be safe to call from many threads at once").isEmpty();
    }

    /**
     * One distinct SKILL.md per thread, every value carrying that thread's own index, so that receiving a neighbour's
     * document is detectable at all. Identical payloads would make cross-contamination invisible by construction. The
     * nested map and the sequence are deliberate: they lengthen the construction phase, which is where the shared
     * {@code BaseConstructor} collections are mutated.
     */
    private static String skillDocument(int index) {
        return "---\n" + "name: skill-" + index + "\n" + "description: Description number " + index + "\n"
                + "license: MIT\n" + "index: " + index + "\n" + "allowed-tools:\n" + "  - Read\n" + "  - Grep\n"
                + "  - tool-" + index + "\n" + "meta:\n" + "  owner: owner-" + index + "\n" + "  tier: " + index + "\n"
                + "---\n" + "# Skill " + index + "\nBody of skill " + index + ".\n";
    }

    /** Every field, including the nested ones, must carry this thread's index. */
    private static boolean isEntirelyOwnedBy(Map<String, Object> frontmatter, int index) {
        return ("skill-" + index).equals(frontmatter.get("name"))
                && Integer.valueOf(index).equals(frontmatter.get("index")) && "MIT".equals(frontmatter.get("license"))
                && ("Description number " + index).equals(frontmatter.get("description"))
                && frontmatter.get("meta") instanceof Map<?, ?> meta && ("owner-" + index).equals(meta.get("owner"))
                && frontmatter.get("allowed-tools") instanceof List<?> tools && tools.size() == 3
                && ("tool-" + index).equals(tools.get(2));
    }
}
