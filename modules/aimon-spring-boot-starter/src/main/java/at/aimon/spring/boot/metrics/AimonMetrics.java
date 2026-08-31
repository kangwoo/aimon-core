package at.aimon.spring.boot.metrics;

import java.util.Objects;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.runtime.AgentRuntimeResolver;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the tenant-runtime meters against the application's {@link MeterRegistry}.
 *
 * <p>
 * These exist because the health endpoint cannot answer the questions they answer. Health is a verdict — serving
 * or not — and the numbers an operator needs in order to act are histories and levels: how close to the cap this
 * node is running, whether refusals happen at all, whether they are a spike or a trend. Putting those in a check
 * was tried and was wrong: a cumulative counter used as a pass/fail latches, and one refusal at 03:00 pinned the
 * report to {@code DOWN} for the life of the process. The counters belong here, where history informs without
 * deciding.
 *
 * <h2>Gauges and function counters, not stored state</h2>
 *
 * <p>
 * Every meter reads through to the resolver at scrape time and this class stores no value of its own. The
 * resolver already keeps the authoritative numbers, and a copy updated from somewhere else would be a second
 * answer that is wrong for exactly as long as nobody looks.
 *
 * <p>
 * The registry holds only a weak reference to a gauge's source object, so the stack has to stay strongly
 * reachable for the meters to keep reporting. It does: this object holds it, and it is a bean.
 */
public final class AimonMetrics {

    /** Prefix every meter shares, so they group in a dashboard and filter in a scrape config. */
    public static final String PREFIX = "aimon.agent.runtimes";

    private final AimonStack stack;

    private AimonMetrics(AimonStack stack) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
    }

    /**
     * Registers the meters and returns the binding.
     *
     * <p>
     * The returned object is what keeps the stack strongly reachable for the gauges; a caller that discards it
     * gets meters that report {@code NaN} as soon as the stack is collected. Holding it as a bean is the point
     * of returning it.
     *
     * @param registry
     *            the registry to register into (must not be null)
     * @param stack
     *            the stack the meters read from (must not be null)
     * @return the binding
     */
    public static AimonMetrics bind(MeterRegistry registry, AimonStack stack) {
        Objects.requireNonNull(registry, "registry must not be null");
        final AimonMetrics metrics = new AimonMetrics(stack);
        final AgentRuntimeResolver resolver = stack.agentRuntimes();

        Gauge.builder(PREFIX + ".active", resolver, AgentRuntimeResolver::trackedCount)
                .description("Tenant agent runtimes this node currently holds").register(registry);
        // The subset of .active that is in use. Without it a refusal is unreadable: active=500 of max=500 with
        // saturated=1 looks the same whether 500 tenants are mid-turn (raise the cap) or three are and the rest
        // are alive only because the idle TTL has not expired (shorten the TTL). Those are opposite changes, and
        // .active minus .leased is the number that tells them apart.
        Gauge.builder(PREFIX + ".leased", resolver, AgentRuntimeResolver::leasedCount)
                .description("Tenant agent runtimes a caller is holding right now").register(registry);
        Gauge.builder(PREFIX + ".max", resolver, r -> r.maxEntries())
                .description("Ceiling on tenant agent runtimes this node will hold").register(registry);
        // A gauge rather than a check: it is the one signal that says "the next new tenant is refused right
        // now", and it comes back down on its own the moment a slot is reclaimed.
        Gauge.builder(PREFIX + ".saturated", resolver, r -> r.isSaturated() ? 1 : 0)
                .description("1 while a request for a tenant this node does not hold would be refused")
                .register(registry);

        FunctionCounter.builder(PREFIX + ".exhausted", resolver, AgentRuntimeResolver::exhaustionCount)
                .description("Requests refused because the node was full with nothing reclaimable").register(registry);
        // Separate from the above because the remedies have nothing in common — one is a limit to raise, the
        // other is a provisioner that throws or a tenant whose configuration cannot be satisfied.
        FunctionCounter.builder(PREFIX + ".provision.failed", resolver, AgentRuntimeResolver::provisionFailureCount)
                .description("Tenant runtimes that failed to build").register(registry);

        return metrics;
    }

    /**
     * Returns the stack these meters read from.
     *
     * @return the stack, never null
     */
    public AimonStack getStack() {
        return stack;
    }
}
