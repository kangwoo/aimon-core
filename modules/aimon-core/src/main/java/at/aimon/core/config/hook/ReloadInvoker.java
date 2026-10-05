package at.aimon.core.config.hook;

import java.util.Objects;

import at.aimon.core.agent.InvokerType;

/**
 * Identity carried by {@link HookRegistryReloader} when firing {@code OnConfigReload} events.
 *
 * <p>
 * Bundles the {@link InvokerType} and the invoker name so the reloader's constructor stays under the project
 * Checkstyle parameter-count limit. Immutable; safe to share between reloaders.
 */
public final class ReloadInvoker {

    private final InvokerType type;
    private final String name;

    /**
     * Creates an invoker identity.
     *
     * @param type
     *            invoker type (must not be null)
     * @param name
     *            invoker name (must not be null)
     */
    public ReloadInvoker(InvokerType type, String name) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
    }

    /** @return the invoker type (never null) */
    public InvokerType getType() {
        return type;
    }

    /** @return the invoker name (never null) */
    public String getName() {
        return name;
    }
}
