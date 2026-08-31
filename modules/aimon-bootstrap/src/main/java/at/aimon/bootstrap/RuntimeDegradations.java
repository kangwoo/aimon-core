package at.aimon.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The capabilities the stack does <b>not</b> have, recorded at build time.
 *
 * <p>
 * A stack that assembles without throwing is not automatically a stack that can do everything the caller
 * assumed. Scheduling may be off, so a skill that registers a cron task will fail at the moment it runs.
 * Credentials may be unresolvable, so a tool referencing one will get nothing. Each of these is a legitimate
 * configuration — none is an error at bootstrap — and each is invisible until something needs the missing piece
 * and fails somewhere unrelated.
 *
 * <p>
 * So the builder writes them down as it goes, and the stack exposes the list. The point is not to change any
 * behaviour: it is that "why did my scheduled task never fire" has an answer that can be printed at startup
 * rather than reconstructed from a stack trace three days later.
 *
 * <p>
 * A degradation is not a failure. Anything that genuinely prevents the stack from working throws
 * {@link at.aimon.bootstrap.exception.AimonBootstrapException} during the build instead of landing here.
 */
public final class RuntimeDegradations {

    /** One missing capability and its consequence. */
    public static final class Degradation {

        private final String capability;
        private final String consequence;

        private Degradation(String capability, String consequence) {
            this.capability = Objects.requireNonNull(capability, "capability must not be null");
            this.consequence = Objects.requireNonNull(consequence, "consequence must not be null");
        }

        /**
         * Returns the capability that is absent.
         *
         * @return the capability name, never null
         */
        public String getCapability() {
            return capability;
        }

        /**
         * Returns what will not work because of it — written for whoever hits the failure, not for whoever
         * configured the stack.
         *
         * @return the consequence, never null
         */
        public String getConsequence() {
            return consequence;
        }

        @Override
        public String toString() {
            return capability + ": " + consequence;
        }
    }

    private static final RuntimeDegradations NONE = new RuntimeDegradations(List.of());

    private final List<Degradation> degradations;

    private RuntimeDegradations(List<Degradation> degradations) {
        this.degradations = List.copyOf(degradations);
    }

    /**
     * Returns the empty set of degradations.
     *
     * @return a shared empty instance
     */
    public static RuntimeDegradations none() {
        return NONE;
    }

    /**
     * Creates a collector the builder appends to while assembling.
     *
     * @return a new mutable collector
     */
    public static Collector collector() {
        return new Collector();
    }

    /**
     * Returns every recorded degradation, in the order the builder found them.
     *
     * @return an immutable list
     */
    public List<Degradation> asList() {
        return degradations;
    }

    /**
     * Returns whether the stack has every capability it can have.
     *
     * @return {@code true} when nothing was degraded
     */
    public boolean isEmpty() {
        return degradations.isEmpty();
    }

    /**
     * Returns whether a named capability is degraded.
     *
     * @param capability
     *            the capability name
     * @return {@code true} when it was recorded as degraded
     */
    public boolean has(String capability) {
        return degradations.stream().anyMatch(d -> d.getCapability().equals(capability));
    }

    /**
     * Renders one line per degradation, for logging at startup.
     *
     * @return a human-readable multi-line summary, or a single line when there are none
     */
    public String describe() {
        if (degradations.isEmpty()) {
            return "No degradations — every optional capability is present.";
        }
        final StringBuilder sb = new StringBuilder("Stack assembled with ").append(degradations.size())
                .append(" degradation(s):");
        for (Degradation d : degradations) {
            sb.append(System.lineSeparator()).append("  - ").append(d);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RuntimeDegradations" + degradations;
    }

    /** Accumulates degradations during assembly. Not thread-safe; the builder runs on one thread. */
    public static final class Collector {

        private final List<Degradation> collected = new ArrayList<>();

        private Collector() {
        }

        /**
         * Records a missing capability.
         *
         * @param capability
         *            the capability name (must not be null)
         * @param consequence
         *            what will not work because of it (must not be null)
         * @return this collector
         */
        public Collector add(String capability, String consequence) {
            collected.add(new Degradation(capability, consequence));
            return this;
        }

        /**
         * Freezes the collected degradations.
         *
         * @return an immutable value
         */
        public RuntimeDegradations build() {
            return collected.isEmpty() ? NONE : new RuntimeDegradations(collected);
        }
    }
}
