package at.aimon.spring.boot.health;

import java.util.Objects;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.HealthReport;
import at.aimon.bootstrap.RuntimeDegradations;

/**
 * Reports {@link AimonStack#health()} at {@code /actuator/health/aimon}.
 *
 * <p>
 * A translation and nothing more. Every decision about what counts as unhealthy lives in the stack, where the
 * fields being read are; this class turns three verdicts into Spring's vocabulary and copies the explanation
 * across so an operator reading the endpoint sees what a log line would have said.
 *
 * <h2>DEGRADED is UP, with the reason attached</h2>
 *
 * <p>
 * Spring has no standard status between {@code UP} and {@code DOWN}, and inventing one has a concrete cost:
 * Kubernetes reads the readiness probe as a boolean, and a custom status is not {@code UP}, so a stack running
 * perfectly well without an optional capability would be pulled out of the load balancer and never put back —
 * degradations are frozen at build time and do not clear. So a degraded stack reports {@code UP} and says why in
 * the details. A capability the deployment chose not to configure is not an outage.
 *
 * <p>
 * {@code DOWN} is reserved for what the stack itself calls not-serving, which in practice means closed. That one
 * <em>should</em> take a pod out of rotation.
 *
 * <h2>Recomputed on every call</h2>
 *
 * <p>
 * {@link AimonStack#health()} builds a fresh report each time, so nothing here caches. That matters for the
 * capacity check in particular: it answers whether the resolver is refusing tenants <em>now</em>, and a cached
 * answer would report a node as full for as long as the cache lived after it had recovered.
 */
public class AimonHealthIndicator implements HealthIndicator {

    private final AimonStack stack;

    /**
     * Creates the indicator.
     *
     * @param stack
     *            the stack to report on (must not be null)
     */
    public AimonHealthIndicator(AimonStack stack) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
    }

    @Override
    public Health health() {
        final HealthReport report = stack.health();
        final Health.Builder builder = report.isServing() ? Health.up() : Health.down();
        builder.withDetail("aimonStatus", report.getStatus().name());
        for (HealthReport.Check check : report.getChecks()) {
            // Both halves are kept: the boolean is what an alert matches on, the detail is what the person
            // woken by the alert needs. A passing check with a detail is not noise here — the capacity check
            // carries its counters that way, and they are the numbers that say whether to raise a limit.
            builder.withDetail(check.getName(),
                    check.getDetail() == null ? verdict(check) : verdict(check) + " — " + check.getDetail());
        }
        final RuntimeDegradations degradations = report.getDegradations();
        if (!degradations.isEmpty()) {
            builder.withDetail("degradations",
                    degradations.asList().stream().map(d -> d.getCapability() + ": " + d.getConsequence()).toList());
        }
        return builder.build();
    }

    private static String verdict(HealthReport.Check check) {
        return check.isPassed() ? Status.UP.getCode() : Status.DOWN.getCode();
    }
}
