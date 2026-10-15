package at.aimon.core.agent.quota;

import java.util.Locale;
import java.util.Objects;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * One subject usage is attributed to: a session, a principal or a tenant, and its id.
 *
 * <p>
 * The three kinds share one ledger and differ only in the key ({@link QuotaKey}). A principal's id is its type and id
 * together ({@code user/alice}), because that pair is what {@link Principal#equals(Object)} compares: a user and a
 * service that happen to share an id are two subjects, not one.
 *
 * <p>
 * Immutable value object.
 */
public final class QuotaScope {

    /** What kind of subject a scope names. */
    public enum Kind {
        /** One session, keyed by its {@link SessionId}. Survives across the session's turns. */
        SESSION,
        /** One caller identity ({@link Principal}), across every session it opens. */
        PRINCIPAL,
        /** One tenant. The core has no tenant concept; a {@link QuotaScopeResolver} decides the id. */
        TENANT
    }

    private final Kind kind;
    private final String id;

    private QuotaScope(Kind kind, String id) {
        this.kind = Objects.requireNonNull(kind, "Kind cannot be null");
        this.id = Objects.requireNonNull(id, "Id cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Quota scope id cannot be blank");
        }
    }

    /**
     * Creates a scope of the given kind and id.
     *
     * @param kind
     *            the kind (must not be null)
     * @param id
     *            the id within that kind (must not be null or blank)
     * @return a new scope (never null)
     */
    public static QuotaScope of(Kind kind, String id) {
        return new QuotaScope(kind, id);
    }

    /**
     * Creates the scope of one session.
     *
     * @param sessionId
     *            the session (must not be null)
     * @return a {@link Kind#SESSION} scope (never null)
     */
    public static QuotaScope session(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        return new QuotaScope(Kind.SESSION, sessionId.value());
    }

    /**
     * Creates the scope of one principal. The id is {@code <type>/<id>}, lower-cased type first.
     *
     * @param principal
     *            the principal (must not be null)
     * @return a {@link Kind#PRINCIPAL} scope (never null)
     */
    public static QuotaScope principal(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        return new QuotaScope(Kind.PRINCIPAL,
                principal.getType().name().toLowerCase(Locale.ROOT) + "/" + principal.getId());
    }

    /**
     * Creates the scope of one tenant.
     *
     * @param tenantId
     *            the tenant id (must not be null or blank)
     * @return a {@link Kind#TENANT} scope (never null)
     */
    public static QuotaScope tenant(String tenantId) {
        return new QuotaScope(Kind.TENANT, tenantId);
    }

    /**
     * Gets the kind.
     *
     * @return the kind (never null)
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Gets the id within the kind.
     *
     * @return the id (never null or blank)
     */
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaScope that = (QuotaScope) o;
        return kind == that.kind && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
