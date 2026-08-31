package at.aimon.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.exception.AimonTeardownException;

/**
 * Ordered ownership list for everything an {@link AimonStack} must release at shutdown.
 *
 * <p>
 * Assembly registers a collaborator here at the moment it is created, so a collaborator cannot be added to the
 * stack without landing in the teardown plan. That is the point of the type: the plan is a by-product of
 * assembly rather than a parallel list somebody has to remember to update.
 *
 * <h2>Execution order</h2>
 *
 * <ul>
 * <li>Across phases: {@link TeardownPhase} declaration order. That enum documents why each phase follows its
 * predecessor.
 * <li>Within a phase: <b>reverse registration order</b>. Entries in one phase are meant to be siblings, but
 * when one was built from another the later one is the dependent, so unwinding is the safe default. If two
 * entries in a phase have a real ordering requirement, that is a signal they belong in different phases.
 * </ul>
 *
 * <h2>Failure handling</h2>
 *
 * <p>
 * {@link #closeAll()} runs <b>every</b> entry even when earlier ones throw, collects the failures, and raises a
 * single {@link AimonTeardownException} with each failure attached as a suppressed exception. This is a
 * deliberate strengthening of the CLI shutdown it is modelled on, where six of the fourteen steps were
 * unguarded and a throw from any of them abandoned every later step — including the ones that stop daemon
 * threads.
 *
 * <p>
 * {@link #closeAll()} is idempotent: a second call is a no-op. Container-managed lifecycles routinely call a
 * destroy method more than once (explicit {@code close()} plus context shutdown), and double-closing an MCP
 * transport is not harmless.
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Registration is expected to happen on the single assembling thread and is <b>not</b> synchronized;
 * {@link #closeAll()} is safe to call from any thread and guarantees the plan runs at most once.
 */
public final class TeardownRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeardownRegistry.class);

    /**
     * A single owned resource together with the phase and label under which it will be released.
     */
    public static final class Entry {

        private final TeardownPhase phase;
        private final String label;
        private final AutoCloseable resource;

        private Entry(TeardownPhase phase, String label, AutoCloseable resource) {
            this.phase = phase;
            this.label = label;
            this.resource = resource;
        }

        /**
         * Returns the phase this entry runs in.
         *
         * @return the teardown phase
         */
        public TeardownPhase getPhase() {
            return phase;
        }

        /**
         * Returns the human-readable label used in logs and failure reports.
         *
         * @return the entry label
         */
        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return phase + "/" + label;
        }
    }

    private final Map<TeardownPhase, List<Entry>> entriesByPhase = new EnumMap<>(TeardownPhase.class);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Registers a resource, deriving the label from its runtime class.
     *
     * <p>
     * Prefer {@link #own(TeardownPhase, String, AutoCloseable)} when the resource is a lambda or when several
     * entries in the same phase share a class — the derived label would not tell them apart in a failure
     * report.
     *
     * @param phase
     *            the phase in which to release the resource (must not be null)
     * @param resource
     *            the resource to release (must not be null)
     * @param <T>
     *            the resource type
     * @return {@code resource}, so registration can wrap a construction expression
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalStateException
     *             if the plan has already run
     */
    public <T extends AutoCloseable> T own(TeardownPhase phase, T resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        return own(phase, resource.getClass().getSimpleName(), resource);
    }

    /**
     * Registers a resource under an explicit label.
     *
     * <p>
     * {@link AutoCloseable} is a functional interface, so a collaborator whose release method is not
     * {@code close()} registers as a lambda:
     *
     * <pre>
     * {@code
     * teardown.own(TeardownPhase.MEMORY_QUEUE, "memoryQueue.stop()", memoryQueue::stop);
     * teardown.own(TeardownPhase.RUNTIME_REGISTRY, "unregister(" + id + ")", () -> registry.unregister(id));
     * }
     * </pre>
     *
     * <p>
     * That shape matters: {@code stop()} and {@code unregister(id)} are exactly the release methods a
     * container cannot infer, which is why they must be written down here.
     *
     * @param phase
     *            the phase in which to release the resource (must not be null)
     * @param label
     *            a human-readable label for logs and failure reports (must not be null or blank)
     * @param resource
     *            the resource to release (must not be null)
     * @param <T>
     *            the resource type
     * @return {@code resource}, so registration can wrap a construction expression
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if {@code label} is blank
     * @throws IllegalStateException
     *             if the plan has already run
     */
    public <T extends AutoCloseable> T own(TeardownPhase phase, String label, T resource) {
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (closed.get()) {
            throw new IllegalStateException(
                    "Cannot register '" + label + "': the teardown plan has already run. Registering after "
                            + "shutdown would leak the resource, because closeAll() never runs a second time.");
        }
        entriesByPhase.computeIfAbsent(phase, p -> new ArrayList<>()).add(new Entry(phase, label, resource));
        return resource;
    }

    /**
     * Registers {@code resource} only when it is non-null, and returns it unchanged.
     *
     * <p>
     * Convenience for the many optional subsystems (tracing, peer memory, scheduling) whose builders return
     * {@code null} when the feature is off, so assembly does not need an {@code if} around every registration.
     *
     * @param phase
     *            the phase in which to release the resource (must not be null)
     * @param label
     *            a human-readable label (must not be null or blank)
     * @param resource
     *            the resource to release, or {@code null} when the feature is disabled
     * @param <T>
     *            the resource type
     * @return {@code resource}, possibly {@code null}
     * @throws IllegalStateException
     *             if the plan has already run and {@code resource} is non-null
     */
    public <T extends AutoCloseable> T ownIfPresent(TeardownPhase phase, String label, T resource) {
        if (resource == null) {
            return null;
        }
        return own(phase, label, resource);
    }

    /**
     * Returns the teardown plan in execution order.
     *
     * <p>
     * Exposed so a stack can report its own shutdown contract and so tests can assert the order without
     * actually tearing a stack down.
     *
     * @return an immutable, ordered list of entries
     */
    public List<Entry> entries() {
        final List<Entry> ordered = new ArrayList<>();
        for (TeardownPhase phase : TeardownPhase.values()) {
            final List<Entry> inPhase = entriesByPhase.get(phase);
            if (inPhase == null) {
                continue;
            }
            // Reverse registration order within a phase — see the class javadoc.
            for (int i = inPhase.size() - 1; i >= 0; i--) {
                ordered.add(inPhase.get(i));
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Returns whether the plan has already run.
     *
     * @return {@code true} once {@link #closeAll()} has been entered
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Runs the whole teardown plan exactly once.
     *
     * <p>
     * Every entry runs regardless of earlier failures. Failures are logged as they happen and re-raised
     * together at the end so a caller sees all of them rather than only the first.
     *
     * @throws AimonTeardownException
     *             if one or more entries threw; each failure is attached as a suppressed exception
     */
    public void closeAll() {
        if (!closed.compareAndSet(false, true)) {
            log.debug("Teardown plan already ran; ignoring repeat closeAll()");
            return;
        }

        final List<Entry> plan = entries();
        log.debug("Tearing down AIMON stack: {} entries across {} phase(s)", plan.size(), entriesByPhase.size());

        final List<String> failedEntries = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();

        for (Entry entry : plan) {
            try {
                entry.resource.close();
                log.debug("Teardown ok: {}", entry);
            } catch (Exception e) {
                // Isolate: a failing entry must not prevent later entries from stopping their threads.
                log.warn("Teardown failed for {}: {}", entry, e.getMessage(), e);
                failedEntries.add(entry.toString());
                failures.add(new AimonTeardownEntryFailure(entry.toString(), e));
            }
        }

        if (!failedEntries.isEmpty()) {
            final AimonTeardownException aggregate = new AimonTeardownException(failedEntries);
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }

        log.debug("AIMON stack teardown complete");
    }

    /**
     * Wrapper that keeps the failing entry's identity attached to its cause, so a suppressed exception in the
     * aggregate still says which entry produced it.
     */
    private static final class AimonTeardownEntryFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AimonTeardownEntryFailure(String entry, Throwable cause) {
            super("Teardown entry failed: " + entry, cause);
        }
    }
}
