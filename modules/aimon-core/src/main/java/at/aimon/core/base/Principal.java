package at.aimon.core.base;

import java.util.Objects;

/**
 * Represents an identity that can own or execute scheduled tasks.
 *
 * <p>
 * A Principal can be a user, group, system, or service. This class is used for ownership tracking and access control
 * for
 * scheduled tasks.
 *
 * <h2>Principal Types</h2>
 * <ul>
 * <li>{@link Type#USER} - Individual user account</li>
 * <li>{@link Type#GROUP} - Group of users</li>
 * <li>{@link Type#SYSTEM} - System-level principal (e.g., scheduler itself)</li>
 * <li>{@link Type#SERVICE} - External service or application</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * // Create a user principal
 * Principal user = Principal.user("user-123", "John Doe");
 *
 * // Create a system principal
 * Principal system = Principal.system();
 *
 * // Create a service principal
 * Principal service = Principal.service("backup-service", "Backup Service");
 *
 * // Create via builder
 * Principal principal = Principal.builder()
 *     .type(Principal.Type.USER)
 *     .id("user-456")
 *     .displayName("Jane Doe")
 *     .build();
 * }</pre>
 *
 * <p>
 * Immutable value object.
 */
public final class Principal {

    /**
     * The type of principal.
     */
    public enum Type {
        /** Individual user account */
        USER,
        /** Group of users */
        GROUP,
        /** System-level principal */
        SYSTEM,
        /** External service or application */
        SERVICE
    }

    private final Type type;
    private final String id;
    private final String displayName;

    private Principal(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "Principal type cannot be null");
        this.id = Objects.requireNonNull(builder.id, "Principal id cannot be null");
        this.displayName = Objects.requireNonNull(builder.displayName, "Principal displayName cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Principal id cannot be blank");
        }
    }

    /**
     * Creates a new builder.
     *
     * @return A new Principal.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a user principal.
     *
     * @param id
     *            the user identifier
     * @return a new user Principal
     */
    public static Principal user(String id) {
        return builder().type(Type.USER).id(id).displayName(id).build();
    }

    /**
     * Creates a user principal with a display name.
     *
     * @param id
     *            the user identifier
     * @param displayName
     *            the human-readable name
     * @return a new user Principal
     */
    public static Principal user(String id, String displayName) {
        return builder().type(Type.USER).id(id).displayName(displayName).build();
    }

    /**
     * Creates a group principal.
     *
     * @param id
     *            the group identifier
     * @param displayName
     *            the human-readable name
     * @return a new group Principal
     */
    public static Principal group(String id, String displayName) {
        return builder().type(Type.GROUP).id(id).displayName(displayName).build();
    }

    /**
     * Creates a system principal with the default system identifier.
     *
     * @return a new system Principal
     */
    public static Principal system() {
        return builder().type(Type.SYSTEM).id("system").displayName("System").build();
    }

    /**
     * Creates a service principal.
     *
     * @param id
     *            the service identifier
     * @param displayName
     *            the human-readable name
     * @return a new service Principal
     */
    public static Principal service(String id, String displayName) {
        return builder().type(Type.SERVICE).id(id).displayName(displayName).build();
    }

    /**
     * Gets the type of this principal.
     *
     * @return the principal type (never null)
     */
    public Type getType() {
        return type;
    }

    /**
     * Gets the unique identifier.
     *
     * @return the principal id (never null)
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the human-readable display name.
     *
     * @return the display name (never null)
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Checks if this principal is a user.
     *
     * @return true if this is a user principal
     */
    public boolean isUser() {
        return type == Type.USER;
    }

    /**
     * Checks if this principal is a group.
     *
     * @return true if this is a group principal
     */
    public boolean isGroup() {
        return type == Type.GROUP;
    }

    /**
     * Checks if this principal is the system.
     *
     * @return true if this is a system principal
     */
    public boolean isSystem() {
        return type == Type.SYSTEM;
    }

    /**
     * Checks if this principal is a service.
     *
     * @return true if this is a service principal
     */
    public boolean isService() {
        return type == Type.SERVICE;
    }

    /**
     * Identity is {@code type} + {@code id}. {@link #getDisplayName() displayName} is deliberately excluded.
     *
     * <p>
     * It used to be included, which made a rename produce a different principal. That is not what any caller
     * wants from this type: {@code ScheduledTaskManager} compares a task's owner against the caller to decide
     * whether the caller may touch it, so a user whose display name changed lost access to their own tasks.
     * Usage keys that embed a principal ({@code LlmUsageKey}) split one person's metering across their old and
     * new name for the same reason.
     *
     * <p>
     * It also put a burden on anything that has to rebuild a principal from the wire. A remote memory backend
     * stores the peer id and has no column for a display name, so the principal it reconstructs could never
     * equal the one it was handed — the contract suite caught exactly that, and the two strings printed
     * identically because {@code toString()} omits the field that differed.
     *
     * <p>
     * {@link at.aimon.core.memory.Workspace#equals(Object)} next door already compared ids alone. The two
     * halves of a {@code PeerView} therefore disagreed about what equality meant, and the composite inherited
     * the stricter one. This makes them agree.
     *
     * <p>
     * <b>Do not regenerate this method.</b> An IDE's "generate equals and hashCode" includes every field and
     * would silently reintroduce all of the above; that is how the display name got in.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Principal that = (Principal) o;
        return type == that.type && id.equals(that.id);
    }

    /** Hashes the same two fields {@link #equals(Object)} compares — see there for why the third is absent. */
    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        return "Principal{" + "type=" + type + ", id='" + id + "', displayName='" + displayName + "'}";
    }

    /** Builder for Principal. */
    public static final class Builder {
        private Type type;
        private String id;
        private String displayName;

        private Builder() {
        }

        /**
         * Sets the principal type.
         *
         * @param type
         *            the type of principal (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if type is null
         */
        public Builder type(Type type) {
            this.type = Objects.requireNonNull(type, "Principal type cannot be null");
            return this;
        }

        /**
         * Sets the unique identifier.
         *
         * @param id
         *            unique identifier (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if id is null
         */
        public Builder id(String id) {
            this.id = Objects.requireNonNull(id, "Principal id cannot be null");
            return this;
        }

        /**
         * Sets the human-readable display name.
         *
         * @param displayName
         *            the display name (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if displayName is null
         */
        public Builder displayName(String displayName) {
            this.displayName = Objects.requireNonNull(displayName, "Principal displayName cannot be null");
            return this;
        }

        /**
         * Builds the Principal.
         *
         * @return a new Principal
         * @throws NullPointerException
         *             if type, id, or displayName is null
         * @throws IllegalArgumentException
         *             if id is blank
         */
        public Principal build() {
            return new Principal(this);
        }
    }
}
