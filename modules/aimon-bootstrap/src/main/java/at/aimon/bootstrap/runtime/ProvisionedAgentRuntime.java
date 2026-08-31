package at.aimon.bootstrap.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntime;

/**
 * An agent runtime together with everything that must be closed when it is.
 *
 * <p>
 * This type exists because {@code AgentRuntime.close()} does not fan out. It closes what the runtime itself owns
 * — its MCP client manager, its agent-scoped workflow runner — and nothing else. The file system the runtime was
 * built with, and any {@link AutoCloseable} tool a provider contributed, are not reached by it.
 *
 * <p>
 * For the agents enumerated at startup that gap is invisible: they live as long as the process, and the stack's
 * teardown plan closes their file systems by name. For tenant runtimes it is a leak with a rate. Each evicted
 * tenant leaves a file system handle behind, so the cost of the omission scales with how many customers the
 * deployment serves — which is exactly the axis nobody watches until the process runs out of descriptors.
 *
 * <p>
 * So a provisioner returns the runtime <b>and</b> its dependents, and eviction closes the pair.
 */
public final class ProvisionedAgentRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProvisionedAgentRuntime.class);

    private final AgentRuntime runtime;
    private final List<AutoCloseable> ownedResources;

    private ProvisionedAgentRuntime(Builder builder) {
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime must not be null");
        this.ownedResources = List.copyOf(builder.ownedResources);
    }

    /**
     * Creates a builder for the given runtime.
     *
     * @param runtime
     *            the runtime that was provisioned (must not be null)
     * @return a new builder
     */
    public static Builder builder(AgentRuntime runtime) {
        return new Builder(runtime);
    }

    /**
     * Returns the runtime.
     *
     * @return the runtime, never null
     */
    public AgentRuntime getRuntime() {
        return runtime;
    }

    /**
     * Returns the resources that must be closed together with the runtime, in the order they were added.
     *
     * @return an immutable list, possibly empty
     */
    public List<AutoCloseable> getOwnedResources() {
        return ownedResources;
    }

    /**
     * Closes the runtime and then its dependents, and reports rather than raises.
     *
     * <p>
     * Two decisions are worth stating. The runtime goes <b>first</b>: it is the thing that might still be using
     * the file system, so closing the file system first would turn a shutdown into a failing write. And every
     * entry runs even when an earlier one throws — this is called from eviction, which is triggered by an
     * unrelated caller's request, and a throw there would fail that caller for a resource they never asked
     * about.
     */
    @Override
    public void close() {
        closeQuietly("runtime " + runtime.getId(), runtime);
        // Reverse order: a resource added later may have been built from an earlier one.
        for (int i = ownedResources.size() - 1; i >= 0; i--) {
            closeQuietly("resource of " + runtime.getId(), ownedResources.get(i));
        }
    }

    private static void closeQuietly(String what, Object candidate) {
        if (!(candidate instanceof AutoCloseable closeable)) {
            // An AgentRuntime is not required to be closeable; the core interface has no close().
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Failed to close {}: {}", what, e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "ProvisionedAgentRuntime[" + runtime.getId() + ", owned=" + ownedResources.size() + "]";
    }

    /** Builder for {@link ProvisionedAgentRuntime}. */
    public static final class Builder {

        private final AgentRuntime runtime;
        private final List<AutoCloseable> ownedResources = new ArrayList<>();

        private Builder(AgentRuntime runtime) {
            this.runtime = runtime;
        }

        /**
         * Adds a resource whose lifetime is the runtime's.
         *
         * <p>
         * Only for resources created <i>for this runtime</i>. A collaborator shared with the rest of the stack —
         * a caller-supplied file system, the knowledge store — must not be added: it would be closed the first
         * time any one tenant is evicted, and every other tenant would then be using a closed object.
         *
         * @param resource
         *            the resource; {@code null} is accepted and ignored so an optional one needs no surrounding
         *            {@code if}
         * @return this builder
         */
        public Builder owns(AutoCloseable resource) {
            if (resource != null) {
                ownedResources.add(resource);
            }
            return this;
        }

        /**
         * Builds the value.
         *
         * @return an immutable provisioned runtime
         */
        public ProvisionedAgentRuntime build() {
            return new ProvisionedAgentRuntime(this);
        }
    }
}
