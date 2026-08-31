package at.aimon.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A point-in-time answer to "is this stack able to serve a turn right now?".
 *
 * <p>
 * Deliberately cheap and local: every check reads a field or an already-held reference, and none of them calls
 * the LLM, touches a database, or opens a socket. A health endpoint that probes the model provider stops being
 * a health endpoint and becomes a load generator — and it reports the provider's outage as the application's,
 * which is the wrong signal for whatever restarts pods.
 *
 * <p>
 * So the question this answers is narrow, and worth stating exactly: <i>the stack's own components are
 * assembled, started, and not shut down</i>. Whether the LLM will answer is not knowable without asking it.
 *
 * <h2>Status</h2>
 *
 * <ul>
 * <li>{@link Status#UP} — every check passed.
 * <li>{@link Status#DEGRADED} — the stack can serve turns, but a capability is missing (see
 * {@link RuntimeDegradations}). A liveness probe should treat this as healthy; a readiness probe may not.
 * <li>{@link Status#DOWN} — the stack cannot serve a turn. In practice this means it has been closed.
 * </ul>
 */
public final class HealthReport {

    /** Overall verdict. */
    public enum Status {
        /** Everything is present and running. */
        UP,
        /** Serving, with a missing optional capability. */
        DEGRADED,
        /** Not serving. */
        DOWN
    }

    /** One named condition and whether it holds. */
    public static final class Check {

        private final String name;
        private final boolean passed;
        private final String detail;

        private Check(String name, boolean passed, String detail) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.passed = passed;
            this.detail = detail;
        }

        /**
         * Returns the check name.
         *
         * @return the name, never null
         */
        public String getName() {
            return name;
        }

        /**
         * Returns whether the condition holds.
         *
         * @return {@code true} when it passed
         */
        public boolean isPassed() {
            return passed;
        }

        /**
         * Returns the explanatory detail, when there is one.
         *
         * @return the detail, or null
         */
        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return name + "=" + (passed ? "ok" : "FAILED") + (detail == null ? "" : " (" + detail + ")");
        }
    }

    private final Status status;
    private final List<Check> checks;
    private final RuntimeDegradations degradations;

    private HealthReport(Status status, List<Check> checks, RuntimeDegradations degradations) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.checks = List.copyOf(checks);
        this.degradations = Objects.requireNonNull(degradations, "degradations must not be null");
    }

    /**
     * Creates a builder.
     *
     * @param degradations
     *            the stack's recorded degradations (must not be null)
     * @return a new builder
     */
    public static Builder builder(RuntimeDegradations degradations) {
        return new Builder(degradations);
    }

    /**
     * Returns the overall verdict.
     *
     * @return the status, never null
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Returns whether the stack can serve a turn — {@link Status#UP} or {@link Status#DEGRADED}.
     *
     * @return {@code true} when serving
     */
    public boolean isServing() {
        return status != Status.DOWN;
    }

    /**
     * Returns every check that was run, in evaluation order.
     *
     * @return an immutable list
     */
    public List<Check> getChecks() {
        return checks;
    }

    /**
     * Returns the degradations that produced a {@link Status#DEGRADED} verdict.
     *
     * @return the degradations, never null
     */
    public RuntimeDegradations getDegradations() {
        return degradations;
    }

    /**
     * Renders the report for a log line or a diagnostic endpoint.
     *
     * @return a human-readable multi-line summary
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder("AIMON stack health: ").append(status);
        for (Check check : checks) {
            sb.append(System.lineSeparator()).append("  - ").append(check);
        }
        if (!degradations.isEmpty()) {
            sb.append(System.lineSeparator()).append(degradations.describe());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "HealthReport[" + status + ", checks=" + checks.size() + ", degradations=" + degradations.asList().size()
                + "]";
    }

    /** Builder for {@link HealthReport}. */
    public static final class Builder {

        private final List<Check> checks = new ArrayList<>();
        private final RuntimeDegradations degradations;
        private boolean down;

        private Builder(RuntimeDegradations degradations) {
            this.degradations = Objects.requireNonNull(degradations, "degradations must not be null");
        }

        /**
         * Records a check. A failed check forces {@link Status#DOWN} — checks are for conditions that make the
         * stack unable to serve, not for missing optional capabilities, which are degradations.
         *
         * @param name
         *            the check name (must not be null)
         * @param passed
         *            whether the condition holds
         * @param detail
         *            an explanation, typically only when it failed (may be null)
         * @return this builder
         */
        public Builder check(String name, boolean passed, String detail) {
            checks.add(new Check(name, passed, detail));
            if (!passed) {
                down = true;
            }
            return this;
        }

        /**
         * Builds the report, deriving the status from the checks and the degradations.
         *
         * @return the immutable report
         */
        public HealthReport build() {
            final Status status;
            if (down) {
                status = Status.DOWN;
            } else if (degradations.isEmpty()) {
                status = Status.UP;
            } else {
                status = Status.DEGRADED;
            }
            return new HealthReport(status, checks, degradations);
        }
    }
}
