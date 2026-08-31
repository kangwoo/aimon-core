package at.aimon.core.credential;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory implementation of {@link CredentialStore}.
 *
 * <p>
 * Stores credentials as nested unmodifiable maps, making this implementation
 * inherently thread-safe after construction. Use the {@link Builder} to
 * configure profiles and their fields.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * CredentialStore store = InMemoryCredentialStore.builder()
 *     .profile("jira", Map.of("username", "admin", "password", "secret"))
 *     .profile("github", Map.of("token", "ghp_abc123"))
 *     .build();
 * }
 * </pre>
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, Map<String, String>> profiles;

    private InMemoryCredentialStore(Map<String, Map<String, String>> profiles) {
        this.profiles = profiles;
    }

    /**
     * Creates a new builder for constructing an {@link InMemoryCredentialStore}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<String> get(String profile, String field) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(field, "field must not be null");

        Map<String, String> fields = profiles.get(profile);
        if (fields == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fields.get(field));
    }

    @Override
    public Set<String> getProfiles() {
        return profiles.keySet();
    }

    @Override
    public Set<String> getFields(String profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        Map<String, String> fields = profiles.get(profile);
        if (fields == null) {
            return Set.of();
        }
        return fields.keySet();
    }

    /**
     * Builder for {@link InMemoryCredentialStore}.
     */
    public static final class Builder {

        private final Map<String, Map<String, String>> profiles = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a credential profile with its fields.
         *
         * @param name
         *            the profile name (e.g., "jira")
         * @param fields
         *            the field name-value pairs (e.g., "username" -> "admin")
         * @return this builder
         * @throws NullPointerException
         *             if name or fields is null
         */
        public Builder profile(String name, Map<String, String> fields) {
            Objects.requireNonNull(name, "profile name must not be null");
            Objects.requireNonNull(fields, "fields must not be null");
            profiles.put(name, new HashMap<>(fields));
            return this;
        }

        /**
         * Builds an immutable {@link InMemoryCredentialStore}.
         *
         * @return a new InMemoryCredentialStore instance
         */
        public InMemoryCredentialStore build() {
            Map<String, Map<String, String>> immutable = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : profiles.entrySet()) {
                immutable.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
            }
            return new InMemoryCredentialStore(Collections.unmodifiableMap(immutable));
        }
    }
}
