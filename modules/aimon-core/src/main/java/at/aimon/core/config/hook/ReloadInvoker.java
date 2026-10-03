package at.aimon.core.config.hook;

import java.util.Objects;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;

/**
 * Identity carried by {@link HookRegistryReloader} when firing {@code OnConfigReload} events.
 *
 * <p>
 * Bundles the {@link InvokerType}, invoker name, and {@link UserLocale} so the reloader's constructor stays under
 * the project Checkstyle parameter-count limit. Immutable; safe to share between reloaders.
 */
public final class ReloadInvoker {

    private final InvokerType type;
    private final String name;
    private final UserLocale userLocale;

    /**
     * Creates an invoker identity.
     *
     * @param type
     *            invoker type (must not be null)
     * @param name
     *            invoker name (must not be null)
     * @param userLocale
     *            the user locale to embed in the OnConfigReload context (must not be null)
     */
    public ReloadInvoker(InvokerType type, String name, UserLocale userLocale) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.userLocale = Objects.requireNonNull(userLocale, "userLocale cannot be null");
    }

    /** @return the invoker type (never null) */
    public InvokerType getType() {
        return type;
    }

    /** @return the invoker name (never null) */
    public String getName() {
        return name;
    }

    /** @return the user locale (never null) */
    public UserLocale getUserLocale() {
        return userLocale;
    }
}
