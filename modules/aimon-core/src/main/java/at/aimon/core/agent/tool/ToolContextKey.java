package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>
 * <b>Write-once keys.</b> A key created with {@link #writeOnce(String, Class)} may be written at most once into a
 * {@link ToolContext.Builder}: a second write of the same <em>name</em> — through the key, through the string
 * {@code put(String, Object)} or through {@code putAll} — throws. The names live in a registry owned by this class
 * rather than in any one builder, so a context copied with {@code builder().putAll(ctx.getContext())} stays
 * protected: the copy is the first write into the new builder, and any later write of that name throws. The
 * framework's own write-once names ({@code executionEnvironment}, {@code executionEnvironmentProvider},
 * {@code hookRegistry}) are in the registry from the start, so they are checked even before the class declaring their
 * constants ({@code at.aimon.core.tools.ToolContextKeys}) is initialised. A write-once key declared anywhere else is
 * registered when the class declaring it is initialised (EE-32).
 *
 * @param <T>
 *            the value type associated with this key
 * @see ToolContext
 */
public final class ToolContextKey<T> {

    /**
     * The framework's write-once names, registered without waiting for the class that declares their keys to be
     * initialised. Must list every {@link #writeOnce(String, Class)} key in {@code at.aimon.core.tools.ToolContextKeys}
     * ({@code ToolContextKeysWriteOnceTest} holds the two together).
     */
    private static final Set<String> BUILT_IN_WRITE_ONCE_NAMES = Set.of("executionEnvironment",
            "executionEnvironmentProvider", "hookRegistry");

    private static final Set<String> WRITE_ONCE_NAMES = ConcurrentHashMap.newKeySet();

    static {
        WRITE_ONCE_NAMES.addAll(BUILT_IN_WRITE_ONCE_NAMES);
    }

    private final String name;
    private final Class<? super T> type;
    private final boolean writeOnce;

    private ToolContextKey(String name, Class<? super T> type, boolean writeOnce) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.writeOnce = writeOnce;
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
        return new ToolContextKey<>(name, type, false);
    }

    /**
     * Creates a write-once key and records its name in the write-once registry, so that no builder accepts a second
     * write of that name.
     *
     * @param name
     *            the string key name (must not be null)
     * @param type
     *            the value type class (must not be null)
     * @param <T>
     *            the value type
     * @return a new write-once {@code ToolContextKey}
     */
    public static <T> ToolContextKey<T> writeOnce(String name, Class<T> type) {
        final ToolContextKey<T> key = new ToolContextKey<>(name, type, true);
        WRITE_ONCE_NAMES.add(name);
        return key;
    }

    /**
     * Whether a key name was declared write-once by any {@link #writeOnce(String, Class)} key.
     *
     * @param name
     *            the key name
     * @return {@code true} if a second write of that name must be rejected
     */
    public static boolean isWriteOnceName(String name) {
        return WRITE_ONCE_NAMES.contains(name);
    }

    /**
     * Whether this key is write-once.
     *
     * @return {@code true} if created with {@link #writeOnce(String, Class)}
     */
    public boolean isWriteOnce() {
        return writeOnce;
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
