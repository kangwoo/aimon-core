package at.aimon.core.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Tenant isolation unit for the memory layer.
 *
 * <p>
 * Every observation, representation, and derivation work unit is scoped to a single
 * {@code Workspace}. Multi-tenant separation is enforced at compile time by requiring
 * a {@code Workspace} (or a workspace-bound id object such as {@link ObservationId})
 * on every store API.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Workspace ws = Workspace.builder()
 *     .id("ws_acme")
 *     .displayName("Acme Operations")
 *     .createdAt(Instant.now())
 *     .build();
 * }</pre>
 */
public final class Workspace {

    private final String id;
    private final String displayName;
    private final Instant createdAt;
    private final Map<String, String> metadata;

    private Workspace(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Workspace id cannot be null");
        if (this.id.isBlank()) {
            throw new IllegalArgumentException("Workspace id cannot be blank");
        }
        this.displayName = Objects.requireNonNull(builder.displayName, "Workspace displayName cannot be null");
        this.createdAt = Objects.requireNonNull(builder.createdAt, "Workspace createdAt cannot be null");
        this.metadata = Map.copyOf(Objects.requireNonNull(builder.metadata, "Workspace metadata cannot be null"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Workspace that = (Workspace) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Workspace{id='" + id + "', displayName='" + displayName + "'}";
    }

    /** Builder for {@link Workspace}. */
    public static final class Builder {
        private String id;
        private String displayName;
        private Instant createdAt = Instant.now();
        private Map<String, String> metadata = Map.of();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = Objects.requireNonNull(id, "id cannot be null");
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
            return this;
        }

        public Workspace build() {
            if (displayName == null) {
                displayName = id;
            }
            return new Workspace(this);
        }
    }
}
