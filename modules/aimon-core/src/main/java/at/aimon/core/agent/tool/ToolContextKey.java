package at.aimon.core.agent.tool;

import java.util.Objects;

/**
 * A typed key for accessing values in {@link ToolContext}.
 *
 * <p>
 * Provides compile-time type safety when storing and retrieving context values, eliminating the need for manual type
 * casts and string-based key lookups.
 *
 * <p>
 * Equality and hash code are based on the key {@link #name()} only, so a {@code ToolContextKey} can be used
 * interchangeably with the legacy string-based API.
 *
 * <p>
 * For generic types such as {@code Set<String>} or {@code Map<String, Object>}, use the raw class at the declaration
 * site and suppress the unchecked warning:
 *
 * <pre>{@code
 * &#64;SuppressWarnings("unchecked")
 * ToolContextKey<Set<String>> READ_FILES = ToolContextKey.of(
 *         "read_tool.read_files", (Class<Set<String>>) (Class<?>) Set.class);
 * }</pre>
 *
 * @param <T>
 *            the value type associated with this key
 * @see ToolContext
 */
public final class ToolContextKey<T> {

    private final String name;
    private final Class<? super T> type;

    private ToolContextKey(String name, Class<? super T> type) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Creates a new typed context key.
     *
     * @param name
     *            the string key name (must not be null)
     * @param type
     *            the value type class (must not be null)
     * @param <T>
     *            the value type
     * @return a new {@code ToolContextKey}
     * @throws NullPointerException
     *             if {@code name} or {@code type} is null
     */
    public static <T> ToolContextKey<T> of(String name, Class<T> type) {
        return new ToolContextKey<>(name, type);
    }

    /**
     * Returns the string key name.
     *
     * @return the key name (never null)
     */
    public String name() {
        return name;
    }

    /**
     * Returns the value type class.
     *
     * @return the type (never null)
     */
    public Class<? super T> type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolContextKey<?> that)) {
            return false;
        }
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "ToolContextKey[" + name + " : " + type.getSimpleName() + "]";
    }
}
