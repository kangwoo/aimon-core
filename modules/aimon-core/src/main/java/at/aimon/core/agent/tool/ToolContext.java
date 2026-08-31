package at.aimon.core.agent.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;

/**
 * Provides contextual information for tool execution.
 *
 * <p>
 * ToolContext is an immutable container that holds arbitrary key-value pairs that can be used during tool execution. It
 * provides type-safe access to context data and supports the Builder pattern for flexible construction.
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 * <li>Passing configuration parameters to tools (e.g., working directory, environment variables)
 * <li>Sharing state between tool invocations (e.g., file system instance, execution metadata)
 * <li>Providing runtime information (e.g., user ID, session data, executor type)
 * <li>Identifying executor type (main agent, subagent, command executor)
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create context with builder
 *     ToolContext context = ToolContext.builder().put("fileSystem", virtualFileSystem).put("environment", environment)
 *             .put("executorType", InvokerType.MAIN_AGENT).build();
 *
 *     // Type-safe retrieval
 *     Optional<VirtualFileSystem> fs = context.get("fileSystem", VirtualFileSystem.class);
 *     Optional<Environment> env = context.get("environment", Environment.class);
 *
 *     // Check for presence
 *     if (context.containsKey("executorType")) {
 *         InvokerType type = context.get("executorType", InvokerType.class).orElseThrow();
 *     }
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class provides structural immutability: the map itself is unmodifiable and defensively copied during
 * construction. However, values stored in the context are not deep-copied; callers should ensure that values are
 * effectively immutable types (e.g., {@code String}, {@code Integer}, {@code List.of(...)}) to maintain full
 * thread safety.
 *
 * @see Tool
 * @see Environment
 * @see InvokerType
 */
public final class ToolContext {

    private final Map<String, Object> context;

    /**
     * Creates a new ToolContext with the given context map.
     *
     * <p>
     * A defensive copy of the provided map is made to ensure immutability.
     *
     * @param context
     *            the context map (must not be null)
     * @throws NullPointerException
     *             if context is null
     */
    public ToolContext(Map<String, Object> context) {
        Objects.requireNonNull(context, "context must not be null");
        this.context = Collections.unmodifiableMap(new HashMap<>(context));
    }

    /**
     * Returns an unmodifiable view of the context map.
     *
     * @return the context map (never null)
     */
    public Map<String, Object> getContext() {
        return this.context;
    }

    /**
     * Gets a value from the context by key.
     *
     * <p>
     * Returns the value associated with the given key, or an empty Optional if the key is not present. Note that the
     * returned value is of type Object and may require casting. For type-safe retrieval, use
     * {@link #get(String, Class)} instead.
     *
     * @param key
     *            the key to look up (must not be null)
     * @return an Optional containing the value if present, empty otherwise
     * @throws NullPointerException
     *             if key is null
     * @see #get(String, Class)
     */
    public Optional<Object> get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(context.get(key));
    }

    /**
     * Gets a value from the context by key and casts it to the expected type.
     *
     * <p>
     * This method provides type-safe retrieval of context values. It returns an empty Optional if:
     * <ul>
     * <li>The key is not present in the context
     * <li>The value exists but is not an instance of the expected type
     * </ul>
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * {
     *     &#64;code
     *     Optional<VirtualFileSystem> fs = context.get("fileSystem", VirtualFileSystem.class);
     *     fs.ifPresent(system -> {
     *         // Use the file system
     *     });
     * }
     * </pre>
     *
     * @param <T>
     *            the expected type
     * @param key
     *            the key to look up (must not be null)
     * @param type
     *            the expected type class (must not be null)
     * @return an Optional containing the typed value if present and of correct type, empty otherwise
     * @throws NullPointerException
     *             if key or type is null
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return Optional.ofNullable(context.get(key)).filter(type::isInstance).map(type::cast);
    }

    /**
     * Gets a value from the context using a typed key.
     *
     * <p>
     * This method provides compile-time type safety when retrieving context values. It returns an empty Optional if the
     * key is not present or the stored value is not an instance of the key's type.
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * {
     *     &#64;code
     *     ToolContextKey<Environment> ENV_KEY = ToolContextKey.of("environment", Environment.class);
     *     Optional<Environment> env = context.get(ENV_KEY);
     * }
     * </pre>
     *
     * @param <T>
     *            the value type
     * @param key
     *            the typed key (must not be null)
     * @return an Optional containing the typed value if present and of correct type, empty otherwise
     * @throws NullPointerException
     *             if key is null
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(ToolContextKey<T> key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(context.get(key.name())).filter(key.type()::isInstance).map(v -> (T) v);
    }

    /**
     * Checks if the context contains a value for the given key.
     *
     * @param key
     *            the key to check
     * @return true if the context contains the key, false otherwise
     * @throws NullPointerException
     *             if key is null
     */
    public boolean containsKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return context.containsKey(key);
    }

    /**
     * Checks if the context contains a value for the given typed key.
     *
     * @param key
     *            the typed key to check (must not be null)
     * @return true if the context contains the key, false otherwise
     * @throws NullPointerException
     *             if key is null
     */
    public boolean containsKey(ToolContextKey<?> key) {
        Objects.requireNonNull(key, "key must not be null");
        return context.containsKey(key.name());
    }

    /**
     * Returns the number of entries in the context.
     *
     * @return the size of the context map
     */
    public int size() {
        return context.size();
    }

    /**
     * Checks if the context is empty.
     *
     * @return true if the context contains no entries, false otherwise
     */
    public boolean isEmpty() {
        return context.isEmpty();
    }

    /**
     * Creates a new empty ToolContext.
     *
     * @return an empty ToolContext
     */
    public static ToolContext empty() {
        return new ToolContext(Collections.emptyMap());
    }

    /**
     * Creates a new Builder for constructing ToolContext instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ToolContext instances.
     *
     * <p>
     * The Builder pattern allows flexible construction of ToolContext with multiple key-value pairs. All put operations
     * validate that keys and values are non-null.
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * {
     *     &#64;code
     *     ToolContext context = ToolContext.builder().put("key1", value1).put("key2", value2).putAll(additionalData)
     *             .build();
     * }
     * </pre>
     */
    public static final class Builder {
        private final Map<String, Object> context = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a key-value pair to the context.
         *
         * @param key
         *            the key (must not be null)
         * @param value
         *            the value (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if key or value is null
         */
        public Builder put(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            this.context.put(key, value);
            return this;
        }

        /**
         * Adds a typed key-value pair to the context.
         *
         * @param <T>
         *            the value type
         * @param key
         *            the typed key (must not be null)
         * @param value
         *            the value (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if key or value is null
         */
        public <T> Builder put(ToolContextKey<T> key, T value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            this.context.put(key.name(), value);
            return this;
        }

        /**
         * Adds all entries from the given map to the context.
         *
         * <p>
         * This is a convenience method for adding multiple key-value pairs at once. Each entry in the provided map is
         * validated to ensure non-null keys and values.
         *
         * @param context
         *            the map to add (must not be null, keys and values must not be null)
         * @return this builder for method chaining
         * @throws NullPointerException
         *             if context is null or contains null keys/values
         */
        public Builder putAll(Map<String, Object> context) {
            Objects.requireNonNull(context, "context must not be null");
            context.forEach(this::put);
            return this;
        }

        /**
         * Builds a new ToolContext with the accumulated entries.
         *
         * @return a new ToolContext
         */
        public ToolContext build() {
            return new ToolContext(this.context);
        }
    }
}
