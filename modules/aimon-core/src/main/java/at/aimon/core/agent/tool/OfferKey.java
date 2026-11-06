package at.aimon.core.agent.tool;

import java.util.Objects;

/**
 * A typed key for an attribute carried by a {@link ToolOfferContext}.
 *
 * <p>
 * The offer context lives in {@code at.aimon.core.agent.tool}, which may not depend on the execution environment or on
 * tool packages ({@code PackageDependencyArchitectureTest}). So it carries what it knows about the environment the way
 * {@link ToolContext} does — as typed attributes whose keys are defined by the package that owns the value (the
 * environment keys {@code ExecuteCode} reads are defined in {@code at.aimon.core.tools.code}).
 *
 * <p>
 * Keys compare by identity: two keys with the same name are different keys. Define each one once, as a constant.
 *
 * @param <T>
 *            the attribute's value type
 * @see ToolOfferContext#get(OfferKey)
 */
public final class OfferKey<T> {

    private final String name;
    private final Class<T> type;

    private OfferKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    /**
     * Creates a key.
     *
     * @param name
     *            a name for diagnostics (must not be null)
     * @param type
     *            the value type (must not be null)
     * @param <T>
     *            the value type
     * @return a new key (never null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static <T> OfferKey<T> of(String name, Class<T> type) {
        return new OfferKey<>(name, type);
    }

    /**
     * Returns the key's name.
     *
     * @return the name (never null)
     */
    public String name() {
        return name;
    }

    /**
     * Returns the value type.
     *
     * @return the type (never null)
     */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "OfferKey{" + name + ": " + type.getSimpleName() + '}';
    }
}
